package com.corebalt.isp.subscriptions.domain;

import com.corebalt.isp.shared.web.ConflictException;

/**
 * A transition the lifecycle does not allow (docs/04 Stored-state transitions). The message names
 * the rule and is shown to staff (409), so it never carries personal data or secrets.
 */
public class LifecycleViolation extends ConflictException {

    public LifecycleViolation(final String message) {
        super(message);
    }
}
