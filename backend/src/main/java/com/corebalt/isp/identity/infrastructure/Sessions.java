package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.AuthException;
import com.corebalt.isp.identity.application.SessionRefresh;
import com.corebalt.isp.identity.application.SignIn;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** The shared shape of the three sign-in APIs: sign-in answers, refresh and sign-out. */
@Component
class Sessions {

    private final SessionCookies cookies;
    private final SessionRefresh refresh;

    Sessions(final SessionCookies cookies, final SessionRefresh refresh) {
        this.cookies = cookies;
        this.refresh = refresh;
    }

    /** A signed-in answer sets the refresh cookie; other steps carry only the challenge. */
    ResponseEntity<SignIn> answer(final String path, final SignIn signIn) {
        final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (signIn.refresh() != null) {
            response.header(HttpHeaders.SET_COOKIE, cookies.set(path, signIn.refresh()));
        }
        return response.body(signIn);
    }

    ResponseEntity<SignIn> refresh(final String path, final String subjectType, final HttpServletRequest request) {
        final String token = cookies.read(request).orElseThrow(() -> new AuthException("Sign in again."));
        try {
            return answer(path, refresh.refresh(subjectType, token));
        } catch (AuthException e) {
            return ResponseEntity.status(401).header(HttpHeaders.SET_COOKIE, cookies.clear(path))
                    .body(SignIn.next("SIGNED_OUT", null));
        }
    }

    ResponseEntity<Void> signOut(final String path, final HttpServletRequest request) {
        cookies.read(request).ifPresent(refresh::signOut);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear(path)).build();
    }
}
