package com.corebalt.isp.support;

import java.util.concurrent.atomic.AtomicInteger;

/** Creates the rows a line needs, through the API, for one tenant. */
public final class Catalog {

    private static final AtomicInteger TUNNEL_HOST = new AtomicInteger(10);

    private Catalog() {}

    /** A PPPoE package: 10 Mbit/s down, 5 up, throttled to 2/1 (Mikrotik-Rate-Limit 5M/10M, 1M/2M). */
    public static String pppoePackage(final Api api, final String name) {
        return id(api.post(
                "/api/v1/packages",
                """
                {"name":"%s","downloadKbps":10000,"uploadKbps":5000,"durationMinutes":43200,
                 "price":{"amount":250000,"currency":"KES"},"throttleDownloadKbps":2000,"throttleUploadKbps":1000}
                """.formatted(name)));
    }

    /** A router on a tunnel address no other test uses (they are unique across tenants). */
    public static Api.Response router(final Api api, final String name) {
        final int host = TUNNEL_HOST.incrementAndGet();
        return api.post(
                "/api/v1/routers",
                "{\"name\":\"" + name + "\",\"tunnelIp\":\"10.201." + (host / 250) + "." + (host % 250 + 1) + "\"}");
    }

    public static String customer(final Api api, final String name, final String phone) {
        return id(api.post("/api/v1/customers", "{\"name\":\"" + name + "\",\"phone\":\"" + phone + "\"}"));
    }

    private static String id(final Api.Response response) {
        if (response.status() != 201) {
            throw new IllegalStateException("setup failed: " + response.status() + " " + response.body());
        }
        return response.json().get("id").asString();
    }
}
