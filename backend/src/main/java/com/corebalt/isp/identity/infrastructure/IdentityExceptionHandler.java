package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.AuthException;
import com.corebalt.isp.identity.application.PortalSignIn;
import com.corebalt.isp.shared.web.ProblemResponseWriter;
import com.corebalt.isp.shared.web.ProblemTypes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Refused sign-in steps are 401; lockouts and send limits are 429. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityExceptionHandler {

    @ExceptionHandler(AuthException.class)
    ProblemDetail refused(final AuthException e) {
        return e.tooManyAttempts()
                ? ProblemResponseWriter.problem(HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.TOO_MANY_ATTEMPTS, e.getMessage())
                : ProblemResponseWriter.problem(HttpStatus.UNAUTHORIZED, ProblemTypes.SIGN_IN_FAILED, e.getMessage());
    }

    @ExceptionHandler(PortalSignIn.TooManyRequests.class)
    ProblemDetail limited(final PortalSignIn.TooManyRequests e) {
        return ProblemResponseWriter.problem(HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.TOO_MANY_ATTEMPTS, e.getMessage());
    }
}
