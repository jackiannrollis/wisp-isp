package com.corebalt.isp.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR 0010: the refresh endpoints accept only the platform's own pages, tenant portals included. */
class SessionCookiesTest {

    private final SessionCookies cookies = new SessionCookies(
            List.of("https://admin.corebalt.example", "https://*.corebalt.example", " http://localhost:4201 "));

    @Test
    void ADR0010_exactOriginsAndOneSubdomainLabelForTenantPortals() {
        assertThat(cookies.allowed("https://admin.corebalt.example")).isTrue();
        assertThat(cookies.allowed("https://kahawa.corebalt.example")).isTrue();
        assertThat(cookies.allowed("https://juja-net2.corebalt.example")).isTrue();
        assertThat(cookies.allowed("http://localhost:4201")).isTrue();
    }

    @Test
    void ADR0010_neverADeeperNameTheBareDomainAnotherSchemeOrALookAlike() {
        assertThat(cookies.allowed("https://a.b.corebalt.example")).isFalse();
        assertThat(cookies.allowed("https://corebalt.example")).isFalse();
        assertThat(cookies.allowed("https://.corebalt.example")).isFalse();
        assertThat(cookies.allowed("http://kahawa.corebalt.example")).isFalse();
        assertThat(cookies.allowed("https://kahawa.corebalt.example.evil.test")).isFalse();
        assertThat(cookies.allowed("https://evilcorebalt.example")).isFalse();
        assertThat(cookies.allowed("https://KAHAWA.corebalt.example")).isFalse();
        assertThat(cookies.allowed("https://kahawa.corebalt.example:8443")).isFalse();
    }
}
