package com.kasa.auth;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.admin.AdminAccountService;
import com.kasa.demo.DemoAccountService;
import com.kasa.security.RateLimitProperties;
import com.kasa.security.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Size(max = 200) String businessName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record TwoFactorRequest(@NotBlank @Size(max = 128) String challengeToken, @NotBlank @Size(max = 32) String code) {
    }

    public record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record ResetConfirmRequest(@NotBlank @Size(max = 128) String token, @NotBlank @Size(min = 12, max = 72) String newPassword) {
    }

    public record VerifyEmailRequest(@NotBlank @Size(max = 128) String token) {
    }

    public record GoogleRequest(@NotBlank @Size(max = 4096) String credential) {
    }

    public record GoogleRegisterRequest(@NotBlank @Size(max = 2048) String signupToken, @NotBlank @Size(max = 200) String businessName) {
    }

    /** What the sign-in page may offer. The Google client id is public: Google's button prints it into the page anyway. */
    public record Providers(String googleClientId, boolean demo) {
    }

    public record GoogleSignInResponse(UUID tenantId, String accessToken, Long expiresInSeconds, boolean twoFactorRequired,
                                       String challengeToken, boolean registrationRequired, String signupToken, String email,
                                       String suggestedName) {
    }

    private final AuthService authService;
    private final PasswordResetService passwordResets;
    private final AdminAccountService admins;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;
    private final EmailVerificationService emailVerification;

    private final GoogleSignInService google;
    private final GoogleProperties googleProperties;
    private final DemoAccountService demo;

    public AuthController(AuthService authService, PasswordResetService passwordResets, AdminAccountService admins,
                          RateLimiter rateLimiter, RateLimitProperties limits, EmailVerificationService emailVerification,
                          GoogleSignInService google, GoogleProperties googleProperties, DemoAccountService demo) {
        this.demo = demo;
        this.google = google;
        this.googleProperties = googleProperties;
        this.emailVerification = emailVerification;
        this.admins = admins;
        this.passwordResets = passwordResets;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.TokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        rateLimiter.check("register:" + http.getRemoteAddr(), limits.registerPerIp());
        return authService.register(request.businessName(), request.email(), request.password());
    }

    // Keys on address and email together, so an attacker cannot lock a merchant out just by knowing their email.
    @PostMapping("/login")
    public AuthService.LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        rateLimiter.check("login-ip:" + ip, limits.loginPerIp());
        rateLimiter.check("login:" + ip + ":" + request.email().trim().toLowerCase(Locale.ROOT), limits.loginPerIpAndEmail());
        return authService.login(request.email(), request.password(), ip);
    }

    @GetMapping("/providers")
    public Providers providers() {
        return new Providers(googleProperties.enabled() ? googleProperties.clientId() : null, demo.enabled());
    }

    /** Opens the shared sample business as its read-only viewer. No account and no password: that is the point. */
    @PostMapping("/demo")
    public AuthService.TokenResponse demo(HttpServletRequest http) {
        rateLimiter.check("login-ip:" + http.getRemoteAddr(), limits.loginPerIp());
        return demo.signIn();
    }

    @PostMapping("/google")
    public GoogleSignInResponse google(@Valid @RequestBody GoogleRequest request, HttpServletRequest http) {
        rateLimiter.check("login-ip:" + http.getRemoteAddr(), limits.loginPerIp());
        GoogleSignInService.SignIn signIn = google.signIn(request.credential());
        AuthService.LoginResponse login = signIn.login();
        return login == null
                ? new GoogleSignInResponse(null, null, null, false, null, true, signIn.signupToken(), signIn.email(), signIn.suggestedName())
                : new GoogleSignInResponse(login.tenantId(), login.accessToken(), login.expiresInSeconds(), login.twoFactorRequired(),
                        login.challengeToken(), false, null, null, null);
    }

    @PostMapping("/google/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.TokenResponse googleRegister(@Valid @RequestBody GoogleRegisterRequest request, HttpServletRequest http) {
        rateLimiter.check("register:" + http.getRemoteAddr(), limits.registerPerIp());
        return google.register(request.signupToken(), request.businessName());
    }

    @PostMapping("/login/admin")
    public AdminAccountService.AdminToken completeAdminLogin(@Valid @RequestBody TwoFactorRequest request, HttpServletRequest http) {
        rateLimiter.check("login-ip:" + http.getRemoteAddr(), limits.loginPerIp());
        return admins.completeLogin(http.getRemoteAddr(), request.challengeToken(), request.code().trim());
    }

    @PostMapping("/login/two-factor")
    public AuthService.TokenResponse completeLogin(@Valid @RequestBody TwoFactorRequest request, HttpServletRequest http) {
        rateLimiter.check("login-ip:" + http.getRemoteAddr(), limits.loginPerIp());
        return authService.completeLogin(request.challengeToken(), request.code().trim());
    }

    // Always answers 202, whatever happened, so the answer says nothing about who has an account.
    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestReset(@Valid @RequestBody ResetRequest request, HttpServletRequest http) {
        rateLimiter.check("reset-ip:" + http.getRemoteAddr(), limits.passwordResetPerIp());
        // Also per email, so a stranger cannot flood someone's mailbox from many addresses.
        rateLimiter.check("reset-email:" + request.email().trim().toLowerCase(Locale.ROOT), limits.passwordResetPerIp());
        passwordResets.request(request.email());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request, HttpServletRequest http) {
        rateLimiter.check("reset-ip:" + http.getRemoteAddr(), limits.passwordResetPerIp());
        emailVerification.confirm(request.token());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmReset(@Valid @RequestBody ResetConfirmRequest request, HttpServletRequest http) {
        rateLimiter.check("reset-ip:" + http.getRemoteAddr(), limits.passwordResetPerIp());
        passwordResets.confirm(request.token(), request.newPassword());
    }
}
