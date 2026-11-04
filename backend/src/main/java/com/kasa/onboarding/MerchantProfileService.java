package com.kasa.onboarding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.common.TurkishIdentifiers;
import com.kasa.security.SecretCipher;
import com.kasa.tenant.TenantScope;

/**
 * The application a business sends before it may take money. Once approved it is frozen: a new IBAN must not
 * slip in unreviewed, because that is exactly how a hijacked account would redirect a merchant's payouts.
 */
@Service
public class MerchantProfileService {

    public record ProfileInput(String businessType, String legalName, String taxId, String taxOffice, String sector,
                               String website, String phone, String address, String city, String iban) {
    }

    /** {@code iban} is the full number only in the reviewer's view; the merchant sees it masked. */
    public record Profile(String businessType, String legalName, String taxId, String taxOffice, String sector, String website,
                          String phone, String address, String city, String iban, String bankCode, OffsetDateTime submittedAt,
                          boolean editable) {
    }

    private static final Set<String> TYPES = Set.of("INDIVIDUAL", "SOLE_PROPRIETOR", "COMPANY");
    private static final Set<String> SECTORS = Set.of("ECOMMERCE", "FOOD", "EDUCATION", "SERVICES", "RETAIL", "SOFTWARE", "OTHER");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final SecretCipher cipher;
    private final AuditLog auditLog;

    public MerchantProfileService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, SecretCipher cipher, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.cipher = cipher;
        this.auditLog = auditLog;
    }

    public Optional<Profile> get(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            boolean editable = !isActive();
            return jdbc.sql("SELECT * FROM merchant_profiles")
                    .query((rs, rowNum) -> map(rs, masked(rs.getString("iban_last4")), editable))
                    .optional();
        });
    }

    public Profile save(UUID tenantId, ProfileInput input) {
        String iban = validate(input);
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            if (isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "an approved application cannot be changed; contact support");
            }
            jdbc.sql("""
                            INSERT INTO merchant_profiles (tenant_id, business_type, legal_name, tax_id, tax_office, sector, website,
                                                           phone, address, city, iban, iban_last4, bank_code)
                            VALUES (:tenantId, :type, :legalName, :taxId, :taxOffice, :sector, :website, :phone, :address, :city,
                                    :iban, :last4, :bank)
                            ON CONFLICT (tenant_id) DO UPDATE
                            SET business_type = EXCLUDED.business_type, legal_name = EXCLUDED.legal_name, tax_id = EXCLUDED.tax_id,
                                tax_office = EXCLUDED.tax_office, sector = EXCLUDED.sector, website = EXCLUDED.website,
                                phone = EXCLUDED.phone, address = EXCLUDED.address, city = EXCLUDED.city, iban = EXCLUDED.iban,
                                iban_last4 = EXCLUDED.iban_last4, bank_code = EXCLUDED.bank_code, submitted_at = now(), updated_at = now()
                            """)
                    .param("tenantId", tenantId)
                    .param("type", input.businessType())
                    .param("legalName", input.legalName().trim())
                    .param("taxId", input.taxId().trim())
                    .param("taxOffice", input.taxOffice().trim())
                    .param("sector", input.sector())
                    .param("website", blankToNull(input.website()))
                    .param("phone", input.phone().trim())
                    .param("address", input.address().trim())
                    .param("city", input.city().trim())
                    .param("iban", cipher.encrypt(context(tenantId), iban))
                    .param("last4", iban.substring(iban.length() - 4))
                    .param("bank", TurkishIdentifiers.bankCode(iban))
                    .update();
            // The log keeps that it was sent, not what was in it: tax numbers and IBANs do not belong in a permanent record.
            auditLog.record(tenantId, "BUSINESS_PROFILE_SUBMITTED", "TENANT", tenantId, Map.of());
            return jdbc.sql("SELECT * FROM merchant_profiles")
                    .query((rs, rowNum) -> map(rs, masked(rs.getString("iban_last4")), true))
                    .single();
        });
    }

    /** The reviewer's view, with the full IBAN. The caller records who looked. */
    public Optional<Profile> forReview(UUID tenantId) {
        return jdbc.sql("SELECT * FROM find_merchant_profile(:id)")
                .param("id", tenantId)
                .query((rs, rowNum) -> map(rs, cipher.decrypt(context(tenantId), rs.getString("iban")), false))
                .optional();
    }

    public boolean isSubmitted(UUID tenantId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM find_merchant_profile(:id))")
                .param("id", tenantId)
                .query(Boolean.class)
                .single();
    }

    private static String validate(ProfileInput input) {
        if (!TYPES.contains(input.businessType()) || !SECTORS.contains(input.sector())) {
            throw invalid("unknown business type or sector");
        }
        String taxId = input.taxId().trim();
        boolean taxIdOk = input.businessType().equals("COMPANY") ? TurkishIdentifiers.isValidVkn(taxId) : TurkishIdentifiers.isValidTckn(taxId);
        if (!taxIdOk) {
            throw invalid(input.businessType().equals("COMPANY") ? "the tax number is not valid" : "the identity number is not valid");
        }
        String iban = TurkishIdentifiers.normalizeIban(input.iban());
        if (!TurkishIdentifiers.isValidTrIban(iban)) {
            throw invalid("the IBAN is not valid");
        }
        return iban;
    }

    private boolean isActive() {
        return jdbc.sql("SELECT status = 'ACTIVE' FROM tenants").query(Boolean.class).optional().orElse(false);
    }

    private static String masked(String last4) {
        return "TR** **** **** **** **** **" + last4.substring(0, 2) + " " + last4.substring(2);
    }

    private static Profile map(ResultSet rs, String iban, boolean editable) throws SQLException {
        return new Profile(
                rs.getString("business_type"),
                rs.getString("legal_name"),
                rs.getString("tax_id"),
                rs.getString("tax_office"),
                rs.getString("sector"),
                rs.getString("website"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getString("city"),
                iban,
                rs.getString("bank_code"),
                rs.getObject("submitted_at", OffsetDateTime.class),
                editable);
    }

    private static String context(UUID tenantId) {
        return "merchant-iban:" + tenantId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
