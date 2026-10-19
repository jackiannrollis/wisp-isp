package com.corebalt.isp.identity.application;

/**
 * A refused sign-in step (401). The message is shown to the person signing in, so it never says
 * whether the account, the password or the code was wrong.
 */
public class AuthException extends RuntimeException {

    private final boolean tooManyAttempts;

    public AuthException(final String message) {
        this(message, false);
    }

    public AuthException(final String message, final boolean tooManyAttempts) {
        super(message);
        this.tooManyAttempts = tooManyAttempts;
    }

    public boolean tooManyAttempts() {
        return tooManyAttempts;
    }
}
