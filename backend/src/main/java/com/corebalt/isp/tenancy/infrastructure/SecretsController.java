package com.corebalt.isp.tenancy.infrastructure;

import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.tenancy.api.SecretStore;
import com.corebalt.isp.tenancy.api.SecretStore.SecretKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Write-only tenant secrets (T4): entered once, replaced, never displayed again (docs/07). */
@RestController
@RequestMapping("/api/v1/settings/secrets")
@Tag(name = "Settings")
@PreAuthorize("hasAuthority('settings.secrets')")
class SecretsController {

    private final SecretStore secrets;

    SecretsController(final SecretStore secrets) {
        this.secrets = secrets;
    }

    @GetMapping
    @Operation(summary = "Which secrets are set (never their values)")
    List<SecretStore.StoredSecret> list() {
        return secrets.list();
    }

    @PutMapping("/{kind}")
    @PreAuthorize("hasAuthority('settings.secrets')")
    @Operation(summary = "Set or replace a secret; the value can never be read back")
    ResponseEntity<Void> put(@PathVariable final SecretKind kind, @Valid @RequestBody final SecretValue body) {
        if (kind == SecretKind.MPESA_CALLBACK_TOKEN || kind == SecretKind.SMS_CALLBACK_TOKEN) {
            // Issued by the M-Pesa or SMS settings together with its route hash; never set by hand.
            throw InvalidRequestException.of("kind", "set by the platform, not by staff");
        }
        secrets.put(kind, body.value().strip());
        return ResponseEntity.noContent().build();
    }

    record SecretValue(@NotBlank @Size(max = 4096) String value) {}
}
