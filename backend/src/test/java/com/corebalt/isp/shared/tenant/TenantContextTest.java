package com.corebalt.isp.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void T1_missingContextIsAnErrorNotAllTenants() {
        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(TenantContextMissingException.class)
                .hasMessageStartingWith("T1");
        assertThatThrownBy(TenantContext::requireScope)
                .isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void T1_platformScopeIsNotATenant() {
        TenantContext.callAsPlatform(
                "test",
                () -> {
                    assertThat(TenantContext.requireScope()).isInstanceOf(TenantScope.Platform.class);
                    assertThatThrownBy(TenantContext::requireTenantId)
                            .isInstanceOf(TenantContextMissingException.class);
                    return null;
                });
    }

    @Test
    void contextIsSetForTheWorkAndRestoredAfterwards() {
        final UUID outer = UUID.randomUUID();
        final UUID inner = UUID.randomUUID();
        TenantContext.runAsTenant(
                outer,
                () -> {
                    assertThat(MDC.get(TenantContext.MDC_KEY)).isEqualTo(outer.toString());
                    TenantContext.runAsTenant(
                            inner, () -> assertThat(TenantContext.requireTenantId()).isEqualTo(inner));
                    assertThat(TenantContext.requireTenantId()).isEqualTo(outer);
                    assertThat(MDC.get(TenantContext.MDC_KEY)).isEqualTo(outer.toString());
                });
        assertThat(TenantContext.current()).isEmpty();
        assertThat(MDC.get(TenantContext.MDC_KEY)).isNull();
    }

    @Test
    void contextIsClearedWhenTheWorkThrows() {
        assertThatThrownBy(
                        () ->
                                TenantContext.runAsTenant(
                                        UUID.randomUUID(),
                                        () -> {
                                            throw new IllegalStateException("boom");
                                        }))
                .hasMessage("boom");
        assertThat(TenantContext.current()).isEmpty();
    }
}
