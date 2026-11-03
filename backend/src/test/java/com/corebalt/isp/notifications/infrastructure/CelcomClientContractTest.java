package com.corebalt.isp.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebalt.isp.notifications.domain.SendOutcome;
import com.corebalt.isp.support.FakeCelcom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Celcom's published send contract; all credentials, recipients and responses are fakes. */
class CelcomClientContractTest {

    final FakeCelcom sms = new FakeCelcom();
    final CelcomClient client = new CelcomClient(JsonMapper.builder().build(), sms.baseUrl());
    static final String PHONE = "+254712345678";

    @AfterEach
    void stop() { sms.close(); }

    private CelcomClient.Result send(final String key) {
        return client.send(FakeCelcom.PARTNER_ID, key, "COREBALT", PHONE, "KES 2,500 received & thanks");
    }

    @Test
    void credentialsAndContentTravelAsJsonAndTheRecipientIsInternationalDigits() {
        sms.registerSender("COREBALT");
        final var result = send(FakeCelcom.API_KEY);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.messageId()).matches("[0-9]+");
        final var request = sms.requestsTo(PHONE).getFirst();
        assertThat(request.headers()).containsEntry("content-type", "application/json")
                .containsEntry("accept", "application/json").doesNotContainKey("apikey");
        assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "partnerID", FakeCelcom.PARTNER_ID, "apikey", FakeCelcom.API_KEY,
                "mobile", "254712345678", "message", "KES 2,500 received & thanks",
                "shortcode", "COREBALT", "pass_type", "plain"));
    }

    @Test
    void unregisteredSenderAndWrongCredentialsAreTenantProblemsEvenWithHttp200() {
        var result = send(FakeCelcom.API_KEY);
        assertThat(result.statusCode()).isEqualTo(1001);
        assertThat(SendOutcome.ofRecipient(result.statusCode(), result.status(), result.messageId()))
                .isInstanceOf(SendOutcome.TenantProblem.class);
        result = send("wrong-key");
        assertThat(result.statusCode()).isEqualTo(1006);
        assertThat(SendOutcome.ofRecipient(result.statusCode(), result.status(), result.messageId()))
                .isEqualTo(new SendOutcome.TenantProblem("SMS_AUTH_FAILED", "1006 Invalid credentials"));
    }

    @Test
    void missingIdWrongRecipientMalformedAndMultipleAcknowledgementsAreNeverSuccessful() {
        sms.registerSender("COREBALT");
        for (final String body : java.util.List.of("not JSON", "{}", "{\"responses\":[]}",
                "{\"responses\":[{\"respose-code\":200,\"mobile\":254712345678}]}",
                "{\"responses\":[{\"respose-code\":200,\"mobile\":254711111111,\"messageid\":123}]}",
                "{\"responses\":[{},{}]}")) {
            sms.respondWith(PHONE, body);
            assertThatThrownBy(() -> send(FakeCelcom.API_KEY)).isInstanceOf(CelcomClient.TransportFailure.class);
        }
    }

    @Test
    void rawProviderTextCannotLeakSecretsToOutcomes() {
        sms.respondWith(PHONE, "{\"responses\":[{\"response-code\":1006,\"response-description\":\""
                + FakeCelcom.API_KEY + " +254712345678 secret message\"}]}");
        assertThat(send(FakeCelcom.API_KEY).status()).isEqualTo("Invalid credentials");
    }

    @Test
    void publishedCodesPreserveRetriesInvalidNumberFlagsAndTenantAlerts() {
        assertThat(SendOutcome.ofRecipient(200, "Accepted", "123"))
                .isEqualTo(new SendOutcome.Sent("123", "Accepted"));
        assertThat(SendOutcome.ofRecipient(200, "Accepted", null)).isInstanceOf(SendOutcome.Transient.class);
        assertThat(SendOutcome.ofRecipient(1003, "Invalid mobile number", null))
                .isEqualTo(new SendOutcome.Permanent("INVALID_NUMBER", "1003 Invalid mobile number"));
        assertThat(SendOutcome.ofRecipient(1004, "Low bulk credits", null))
                .isEqualTo(new SendOutcome.TenantProblem("SMS_CREDIT_EXHAUSTED", "1004 Low bulk credits"));
        assertThat(SendOutcome.ofRecipient(4090, "Internal error", null)).isInstanceOf(SendOutcome.Transient.class);
        assertThat(SendOutcome.ofTransport(0, "timeout")).isInstanceOf(SendOutcome.Transient.class);
        assertThat(SendOutcome.ofTransport(403, "HTTP 403"))
                .isEqualTo(new SendOutcome.TenantProblem("SMS_AUTH_FAILED", "HTTP 403"));
    }

    @Test
    void deliveryRequiresTheLiveVerifiedStatusAndTheRequestedMessageId() {
        assertThat(client.deliveryReport(FakeCelcom.PARTNER_ID, FakeCelcom.API_KEY, "123"))
                .isEqualTo(new CelcomClient.DeliveryReport(true, "DeliveredToTerminal"));
        sms.deliveryResponse("123", "{\"response-code\":200,\"message-id\":\"other\",\"delivery-status\":32,\"delivery-description\":\"DeliveredToTerminal\"}");
        assertThatThrownBy(() -> client.deliveryReport(FakeCelcom.PARTNER_ID, FakeCelcom.API_KEY, "123"))
                .isInstanceOf(CelcomClient.TransportFailure.class);
        sms.deliveryResponse("123", "{\"response-code\":200,\"message-id\":\"123\",\"delivery-status\":32,\"delivery-description\":\"unverified\"}");
        assertThat(client.deliveryReport(FakeCelcom.PARTNER_ID, FakeCelcom.API_KEY, "123").delivered()).isFalse();
        sms.deliveryResponse("123", "{\"response-code\":1008}");
        assertThat(client.deliveryReport(FakeCelcom.PARTNER_ID, FakeCelcom.API_KEY, "123").delivered()).isFalse();
    }

    @Test
    void httpFailuresDoNotExposeResponseBodies() {
        sms.registerSender("COREBALT");
        sms.failFor(PHONE, 502);
        assertThatThrownBy(() -> send(FakeCelcom.API_KEY))
                .isInstanceOfSatisfying(CelcomClient.TransportFailure.class,
                        e -> assertThat(e.getMessage()).isEqualTo("HTTP 502"));
    }
}
