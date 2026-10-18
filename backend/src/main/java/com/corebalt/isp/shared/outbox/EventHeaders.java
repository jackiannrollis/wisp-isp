package com.corebalt.isp.shared.outbox;

/** AMQP headers every event message carries (conventions.md, Events and queues). */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String TENANT_ID = "tenantId";
    public static final String CORRELATION_ID = "correlationId";
    public static final String OCCURRED_AT = "occurredAt";

    private EventHeaders() {}
}
