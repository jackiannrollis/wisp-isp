package com.corebalt.isp.tenancy.api;

import java.util.List;
import java.util.Optional;

/**
 * The current tenant's secrets (docs/07 Secrets, T4), envelope-encrypted. {@link #reveal} is for
 * module code that must use a secret (for example the Daraja client); nothing may return, log or
 * publish what it gives back. Staff can only replace a secret, never read it.
 */
public interface SecretStore {

    void put(SecretKind kind, String value);

    Optional<String> reveal(SecretKind kind);

    /** Which secrets are set, with their key version; never the values. */
    List<StoredSecret> list();

    enum SecretKind {
        MPESA_CONSUMER_KEY,
        MPESA_CONSUMER_SECRET,
        MPESA_PASSKEY,
        MPESA_INITIATOR_NAME,
        MPESA_SECURITY_CREDENTIAL,
        /** The path token of the tenant's callback URLs; the route table keeps only its hash. */
        MPESA_CALLBACK_TOKEN,
        SMS_API_KEY,
        /** The path token of the tenant's SMS delivery-report URL; the route table keeps only its hash. */
        SMS_CALLBACK_TOKEN
    }

    record StoredSecret(SecretKind kind, int keyVersion, java.time.Instant updatedAt) {}
}
