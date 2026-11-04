package com.kasa.bank;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The card bank's 3-D Secure server (its "ACS"), simulated. It decides whether to ask the cardholder for a code,
 * shows its own page, counts wrong codes, and signs its verdict. In real life this belongs to the customer's bank;
 * nothing here may read our tables, and we learn its verdict only through the signed result.
 */
@Component
public class SimulatedAcs {

    /** Either the bank waves the payment through at once, or the customer must visit its page. */
    public record Started(boolean challenge, ThreeDsSigner.SignedResult frictionless) {
    }

    public record Challenge(UUID id, String merchantName, long amount, String currency, String cardHint, String status,
                            int attemptsLeft, OffsetDateTime expiresAt, String testCode, String returnUrl) {
    }

    /** What the bank's page does next: ask again, or send the customer back with a signed verdict. */
    public record Answer(String status, int attemptsLeft, ThreeDsSigner.SignedResult result, String returnUrl) {
    }

    public static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ThreeDsSigner signer;
    private final SecureRandom random = new SecureRandom();

    public SimulatedAcs(JdbcClient jdbc, TransactionTemplate tx, ThreeDsSigner signer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.signer = signer;
    }

    /** Cards whose token starts with "tok_3ds" are enrolled for a challenge; for the rest the bank needs nothing more. */
    public Started start(UUID authenticationId, String cardToken, String merchantName, long amount, String currency, String returnUrl) {
        if (!cardToken.startsWith("tok_3ds")) {
            return new Started(false, signer.sign(authenticationId, "Y", "05", Instant.now().getEpochSecond()));
        }
        jdbc.sql("""
                        INSERT INTO sim_acs_challenges (id, merchant_name, amount, currency, card_hint, code, return_url, status, expires_at)
                        VALUES (:id, :merchant, :amount, :currency, :hint, :code, :returnUrl, 'PENDING', now() + make_interval(secs => :seconds))
                        """)
                .param("id", authenticationId)
                .param("merchant", merchantName)
                .param("amount", amount)
                .param("currency", currency)
                .param("hint", "**** " + (1000 + Math.floorMod(cardToken.hashCode(), 9000)))
                .param("code", String.format("%06d", random.nextInt(1_000_000)))
                .param("returnUrl", returnUrl)
                .param("seconds", CHALLENGE_LIFETIME.toSeconds())
                .update();
        return new Started(true, null);
    }

    public Challenge challenge(UUID id) {
        return jdbc.sql("SELECT *, expires_at < now() AS expired FROM sim_acs_challenges WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new Challenge(
                        rs.getObject("id", UUID.class),
                        rs.getString("merchant_name"),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        rs.getString("card_hint"),
                        rs.getBoolean("expired") && rs.getString("status").equals("PENDING") ? "EXPIRED" : rs.getString("status"),
                        MAX_ATTEMPTS - rs.getInt("attempts"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        // A test bank shows the code on screen; a real one would send it by SMS.
                        rs.getString("code"),
                        rs.getString("return_url")))
                .optional()
                .orElseThrow(SimulatedAcs::notFound);
    }

    /** The row lock makes counting wrong codes exact, however fast someone guesses in parallel. */
    public Answer answer(UUID id, String code, boolean cancelled) {
        return tx.execute(status -> {
            record Row(String code, int attempts, String status, boolean expired, String returnUrl) {
            }
            Row row = jdbc.sql("SELECT code, attempts, status, expires_at < now() AS expired, return_url FROM sim_acs_challenges WHERE id = :id FOR UPDATE")
                    .param("id", id)
                    .query((rs, rowNum) -> new Row(rs.getString("code"), rs.getInt("attempts"), rs.getString("status"),
                            rs.getBoolean("expired"), rs.getString("return_url")))
                    .optional()
                    .orElseThrow(SimulatedAcs::notFound);
            if (!row.status().equals("PENDING")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this verification is already finished");
            }
            boolean correct = !cancelled && !row.expired() && java.security.MessageDigest.isEqual(
                    row.code().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    String.valueOf(code).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            int attempts = row.attempts() + (correct || cancelled ? 0 : 1);
            String verdict = correct ? "Y" : cancelled || row.expired() || attempts >= MAX_ATTEMPTS ? "N" : "PENDING";
            jdbc.sql("UPDATE sim_acs_challenges SET attempts = :attempts, status = :status WHERE id = :id")
                    .param("attempts", attempts)
                    .param("status", verdict)
                    .param("id", id)
                    .update();
            if (verdict.equals("PENDING")) {
                return new Answer("PENDING", MAX_ATTEMPTS - attempts, null, null);
            }
            ThreeDsSigner.SignedResult result = signer.sign(id, verdict, verdict.equals("Y") ? "05" : "07", Instant.now().getEpochSecond());
            return new Answer(verdict, MAX_ATTEMPTS - attempts, result, row.returnUrl());
        });
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "verification not found");
    }
}
