package com.corebalt.isp.network.domain;

import com.corebalt.isp.shared.web.ConflictException;

/** N10: an action on a router that has been retired; retirement is final (409, shown to staff). */
public class RouterRetiredException extends ConflictException {

    public RouterRetiredException(final String routerName) {
        super("N10: router '" + routerName + "' is retired; retirement is final. Register a new router instead.");
    }
}
