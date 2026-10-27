package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.Impersonations;
import com.corebalt.isp.identity.application.PlatformSignIn;
import com.corebalt.isp.identity.application.PortalSignIn;
import com.corebalt.isp.identity.application.SignIn;
import com.corebalt.isp.identity.application.StaffSignIn;
import com.corebalt.isp.shared.security.JwtKeys;
import com.corebalt.isp.shared.security.Tokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public sign-in APIs (ADR 0010). Each step answers with {@link SignIn}: either the next
 * challenge or, once signed in, an access token in the body and the refresh token in an httpOnly
 * cookie scoped to the API's {@code session} path.
 */
final class SignInControllers {

    private SignInControllers() {}

    record StaffLogin(@NotBlank String tenant, @NotBlank String email, @NotBlank String password) {}

    record PlatformLogin(@NotBlank String email, @NotBlank String password) {}

    record NewPassword(@NotBlank String challenge, @NotBlank String newPassword) {}

    record Code(@NotBlank String challenge, @NotBlank String code) {}

    record CodeRequest(@NotBlank String tenant, @NotBlank String phone) {}

    record CodeCheck(@NotBlank String tenant, @NotBlank String phone, @NotBlank String code) {}

    record NewPin(@NotBlank String challenge, @NotBlank String pin) {}

    record PinLogin(@NotBlank String tenant, @NotBlank String account, @NotBlank String pin) {}

    @RestController
    @Tag(name = "Sign-in")
    static class Jwks {

        private final JwtKeys keys;

        Jwks(final JwtKeys keys) {
            this.keys = keys;
        }

        @GetMapping("/.well-known/jwks.json")
        @Operation(summary = "Public keys that verify the platform's tokens, retired ones included")
        ResponseEntity<Map<String, Object>> jwks() {
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
                    .body(keys.publicKeys().toJSONObject(true));
        }
    }

    @RestController
    @RequestMapping("/api/v1/auth")
    @Tag(name = "Sign-in")
    static class Staff {

        private final StaffSignIn signIn;
        private final Sessions sessions;
        private final Impersonations impersonations;

        Staff(final StaffSignIn signIn, final Sessions sessions, final Impersonations impersonations) {
            this.signIn = signIn;
            this.sessions = sessions;
            this.impersonations = impersonations;
        }

        record HandoffCode(@NotBlank @Size(max = 100) String code) {}

        /**
         * ADR 0017: the admin app's hand-off page exchanges the console's single-use code for the impersonation
         * token. No refresh token and no cookie: the token lives in the page's memory until the impersonation ends.
         */
        @PostMapping("/impersonation")
        @Operation(summary = "Exchange a single-use impersonation hand-off code (60 seconds) for the impersonation's token")
        ResponseEntity<Impersonations.Exchanged> impersonation(@Valid @RequestBody final HandoffCode request) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(impersonations.exchange(request.code()));
        }

        @PostMapping("/login")
        @Operation(summary = "Staff sign-in: organisation code or subdomain, email, password")
        ResponseEntity<SignIn> login(@Valid @RequestBody final StaffLogin request) {
            return sessions.answer(SessionCookies.STAFF_PATH, signIn.login(request.tenant(), request.email(), request.password()));
        }

        @PostMapping("/password")
        @Operation(summary = "Set a new password (CHANGE_PASSWORD challenge)")
        ResponseEntity<SignIn> password(@Valid @RequestBody final NewPassword request) {
            return sessions.answer(SessionCookies.STAFF_PATH, signIn.changePassword(request.challenge(), request.newPassword()));
        }

        @PostMapping("/totp")
        @Operation(summary = "The authenticator code (TOTP_REQUIRED challenge)")
        ResponseEntity<SignIn> totp(@Valid @RequestBody final Code request) {
            return sessions.answer(SessionCookies.STAFF_PATH, signIn.verifyTotp(request.challenge(), request.code()));
        }

        @PostMapping("/totp/enroll")
        @Operation(summary = "Confirm the authenticator set up from TOTP_ENROLL with its first code")
        ResponseEntity<SignIn> enroll(@Valid @RequestBody final Code request) {
            return sessions.answer(SessionCookies.STAFF_PATH, signIn.enrollTotp(request.challenge(), request.code()));
        }

        @PostMapping(SessionCookies.STAFF_PATH_SUFFIX + "/refresh")
        @Operation(summary = "A new access token from the refresh cookie (rotated); Origin must be a Corebalt page")
        ResponseEntity<SignIn> refresh(final HttpServletRequest request) {
            return sessions.refresh(SessionCookies.STAFF_PATH, Tokens.STAFF, request);
        }

        @PostMapping(SessionCookies.STAFF_PATH_SUFFIX + "/logout")
        @Operation(summary = "Sign out: the refresh token's family is revoked")
        ResponseEntity<Void> logout(final HttpServletRequest request) {
            return sessions.signOut(SessionCookies.STAFF_PATH, request);
        }
    }

    @RestController
    @RequestMapping("/api/v1/portal-auth")
    @Tag(name = "Sign-in")
    static class Portal {

        private final PortalSignIn signIn;
        private final Sessions sessions;

        Portal(final PortalSignIn signIn, final Sessions sessions) {
            this.signIn = signIn;
            this.sessions = sessions;
        }

        @PostMapping("/code")
        @Operation(summary = "Send a sign-in code by SMS (to set or reset the PIN); the answer never says whether the number is known")
        ResponseEntity<PortalSignIn.CodeRequested> code(@Valid @RequestBody final CodeRequest request,
                                                        final HttpServletRequest http) {
            return ResponseEntity.accepted().body(signIn.requestCode(request.tenant(), request.phone(), http.getRemoteAddr()));
        }

        @PostMapping("/code/verify")
        @Operation(summary = "Check the code: PIN_SETUP, or CHOOSE_ACCOUNT when several accounts share the phone")
        SignIn verify(@Valid @RequestBody final CodeCheck request) {
            return signIn.verifyCode(request.tenant(), request.phone(), request.code());
        }

        @PostMapping("/pin")
        @Operation(summary = "Set the PIN (PIN_SETUP challenge) and sign in")
        ResponseEntity<SignIn> pin(@Valid @RequestBody final NewPin request) {
            return sessions.answer(SessionCookies.PORTAL_PATH, signIn.setPin(request.challenge(), request.pin()));
        }

        @PostMapping("/login")
        @Operation(summary = "Sign in with the customer number (or phone) and PIN")
        ResponseEntity<SignIn> login(@Valid @RequestBody final PinLogin request) {
            return sessions.answer(SessionCookies.PORTAL_PATH, signIn.login(request.tenant(), request.account(), request.pin()));
        }

        @PostMapping(SessionCookies.PORTAL_PATH_SUFFIX + "/refresh")
        @Operation(summary = "A new access token from the refresh cookie (rotated)")
        ResponseEntity<SignIn> refresh(final HttpServletRequest request) {
            return sessions.refresh(SessionCookies.PORTAL_PATH, Tokens.CUSTOMER, request);
        }

        @PostMapping(SessionCookies.PORTAL_PATH_SUFFIX + "/logout")
        @Operation(summary = "Sign out")
        ResponseEntity<Void> logout(final HttpServletRequest request) {
            return sessions.signOut(SessionCookies.PORTAL_PATH, request);
        }
    }

    @RestController
    @RequestMapping("/api/platform/auth")
    @Tag(name = "Platform sign-in")
    static class Platform {

        private final PlatformSignIn signIn;
        private final Sessions sessions;

        Platform(final PlatformSignIn signIn, final Sessions sessions) {
            this.signIn = signIn;
            this.sessions = sessions;
        }

        @PostMapping("/login")
        @Operation(summary = "Platform admin sign-in; TOTP is mandatory")
        ResponseEntity<SignIn> login(@Valid @RequestBody final PlatformLogin request) {
            return sessions.answer(SessionCookies.PLATFORM_PATH, signIn.login(request.email(), request.password()));
        }

        @PostMapping("/password")
        ResponseEntity<SignIn> password(@Valid @RequestBody final NewPassword request) {
            return sessions.answer(SessionCookies.PLATFORM_PATH, signIn.changePassword(request.challenge(), request.newPassword()));
        }

        @PostMapping("/totp")
        @Operation(summary = "TOTP code, or a recovery code (XXXX-XXXX), each usable once")
        ResponseEntity<SignIn> totp(@Valid @RequestBody final Code request) {
            return sessions.answer(SessionCookies.PLATFORM_PATH, signIn.verifyTotp(request.challenge(), request.code()));
        }

        @PostMapping("/totp/enroll")
        ResponseEntity<SignIn> enroll(@Valid @RequestBody final Code request) {
            return sessions.answer(SessionCookies.PLATFORM_PATH, signIn.enrollTotp(request.challenge(), request.code()));
        }

        @PostMapping(SessionCookies.PLATFORM_PATH_SUFFIX + "/refresh")
        ResponseEntity<SignIn> refresh(final HttpServletRequest request) {
            return sessions.refresh(SessionCookies.PLATFORM_PATH, Tokens.PLATFORM, request);
        }

        @PostMapping(SessionCookies.PLATFORM_PATH_SUFFIX + "/logout")
        ResponseEntity<Void> logout(final HttpServletRequest request) {
            return sessions.signOut(SessionCookies.PLATFORM_PATH, request);
        }
    }
}
