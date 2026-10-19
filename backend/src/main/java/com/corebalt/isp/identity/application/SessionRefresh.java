package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.security.Tokens;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Exchanges a refresh token for a new access token and a rotated refresh token (ADR 0010). The token
 * must belong to the API it is presented to (staff, portal or platform), and its owner must still be
 * allowed to sign in; otherwise its family is revoked.
 */
@Service
public class SessionRefresh {

    private final RefreshTokens refreshTokens;
    private final StaffSignIn staff;
    private final PortalSignIn portal;
    private final PlatformSignIn platform;

    SessionRefresh(final RefreshTokens refreshTokens, final StaffSignIn staff, final PortalSignIn portal,
                   final PlatformSignIn platform) {
        this.refreshTokens = refreshTokens;
        this.staff = staff;
        this.portal = portal;
        this.platform = platform;
    }

    public SignIn refresh(final String subjectType, final String token) {
        final RefreshTokens.Rotated rotated = refreshTokens.rotate(token)
                .orElseThrow(() -> new AuthException("Sign in again."));
        final RefreshTokens.Owner owner = rotated.owner();
        final Optional<String> access = !subjectType.equals(owner.subjectType()) ? Optional.empty()
                : switch (owner.subjectType()) {
                    case Tokens.STAFF -> staff.reissue(owner.tenantId(), owner.subjectId());
                    case Tokens.CUSTOMER -> portal.reissue(owner.tenantId(), owner.subjectId());
                    case Tokens.PLATFORM -> platform.reissue(owner.subjectId());
                    default -> Optional.empty();
                };
        if (access.isEmpty()) {
            refreshTokens.revokeFamilyOf(rotated.issued().token());
            throw new AuthException("Sign in again.");
        }
        return SignIn.signedIn(access.get(), rotated.issued());
    }

    public void signOut(final String token) {
        refreshTokens.revokeFamilyOf(token);
    }
}
