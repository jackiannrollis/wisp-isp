package com.corebalt.isp.shared.web;

/** 503: something this host needs is not there right now (a mounted file, a sidecar); the message says what. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(final String message) {
        super(message);
    }
}
