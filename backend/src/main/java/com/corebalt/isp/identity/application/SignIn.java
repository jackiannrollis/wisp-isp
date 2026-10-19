package com.corebalt.isp.identity.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The answer to a sign-in step. {@code status} SIGNED_IN carries the access token (the refresh
 * token goes in a cookie, never in the body); every other status carries a {@code challenge} for the
 * next step: CHANGE_PASSWORD, TOTP_REQUIRED, TOTP_ENROLL (with the new secret and its otpauth URI,
 * and for platform admins the recovery codes, all shown once), PIN_SETUP, or CHOOSE_ACCOUNT (one
 * PIN_SETUP challenge per account sharing the phone).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignIn(
        String status,
        String accessToken,
        Long expiresIn,
        String challenge,
        String totpSecret,
        String otpauthUri,
        List<String> recoveryCodes,
        List<Account> accounts,
        @JsonIgnore RefreshTokens.Issued refresh) {

    public record Account(String number, String name, String challenge) {}

    public static SignIn signedIn(final String accessToken, final RefreshTokens.Issued refresh) {
        return new SignIn("SIGNED_IN", accessToken, TokenIssuer.ACCESS_TTL.toSeconds(), null, null, null, null, null, refresh);
    }

    public static SignIn next(final String status, final String challenge) {
        return new SignIn(status, null, null, challenge, null, null, null, null, null);
    }

    public static SignIn enroll(final String challenge, final String secret, final String uri, final List<String> recoveryCodes) {
        return new SignIn("TOTP_ENROLL", null, null, challenge, secret, uri, recoveryCodes, null, null);
    }

    public static SignIn chooseAccount(final List<Account> accounts) {
        return new SignIn("CHOOSE_ACCOUNT", null, null, null, null, null, null, accounts, null);
    }
}
