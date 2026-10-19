package com.corebalt.isp.shared.web;

/**
 * A resource that does not exist for the caller's tenant. Rows of another tenant are invisible
 * under row-level security, so they are reported the same way (T1): never "forbidden".
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(final String resource) {
        super(resource + " not found");
    }
}
