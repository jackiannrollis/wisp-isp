package com.corebalt.isp.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebalt.isp.notifications.domain.SendOutcome;
import com.corebalt.isp.support.FakeAfricasTalking;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the request sent to Africa's Talking and the answer-to-behaviour mapping agreed for step 7,
 * so a sandbox run only has to confirm their side. Credentials and numbers are fakes.
 */
class AfricasTalkingClientContractTest {

    final FakeAfricasTalking at = new FakeAfricasTalking();
    final AfricasTalkingClient client = new AfricasTalkingClient(JsonMapper.builder().build(), at.baseUrl(), "http://unused.invalid");

    @AfterEach
    void stop() {
        at.close();
    }

    @Test
    void theRequestIsFormEncodedWithTheApiKeyHeaderAndAnE164Recipient() {
        at.registerSender("COREBALT");
        final AfricasTalkingClient.Result result =
                client.send(true, "sandbox", FakeAfricasTalking.API_KEY, "COREBALT", "+254712345678", "KES 2,500 received & thanks");
        assertThat(result.statusCode()).isEqualTo(101);
        assertThat(result.messageId()).startsWith("ATXid_");
        final FakeAfricasTalking.Recorded request = at.requestsTo("+254712345678").getFirst();
        assertThat(request.headers()).containsEntry("apikey", FakeAfricasTalking.API_KEY)
                .containsEntry("accept", "application/json")
                .containsEntry("content-type", "application/x-www-form-urlencoded");
        assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "username", "sandbox", "to", "+254712345678", "message", "KES 2,500 received & thanks", "from", "COREBALT"));
    }

    @Test
    void anUnregisteredSenderIdIsRefused() {
        final AfricasTalkingClient.Result result =
                client.send(true, "sandbox", FakeAfricasTalking.API_KEY, "UNKNOWN", "+254712345678", "x");
        assertThat(result.statusCode()).isEqualTo(402);
        assertThat(SendOutcome.ofRecipient(result.statusCode(), result.status(), result.messageId()))
                .isInstanceOf(SendOutcome.TenantProblem.class);
    }

    @Test
    void aWrongApiKeyIsHttp401AndATenantProblem() {
        assertThatThrownBy(() -> client.send(true, "sandbox", "wrong-key", "COREBALT", "+254712345678", "x"))
                .isInstanceOfSatisfying(AfricasTalkingClient.TransportFailure.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(401);
                    assertThat(SendOutcome.ofTransport(e.httpStatus(), e.getMessage()))
                            .isEqualTo(new SendOutcome.TenantProblem("SMS_AUTH_FAILED", "HTTP 401"));
                });
    }

    @Test
    void statusCodesMapToTheAgreedBehaviour() {
        assertThat(SendOutcome.ofRecipient(102, "Queued", "ATXid_1")).isEqualTo(new SendOutcome.Sent("ATXid_1", "Queued"));
        assertThat(SendOutcome.ofRecipient(403, "InvalidPhoneNumber", null))
                .isEqualTo(new SendOutcome.Permanent("INVALID_NUMBER", "403 InvalidPhoneNumber"));
        assertThat(SendOutcome.ofRecipient(406, "UserInBlacklist", null))
                .isEqualTo(new SendOutcome.Permanent("BLACKLISTED", "406 UserInBlacklist"));
        assertThat(SendOutcome.ofRecipient(405, "InsufficientBalance", null))
                .isEqualTo(new SendOutcome.TenantProblem("SMS_CREDIT_EXHAUSTED", "405 InsufficientBalance"));
        assertThat(SendOutcome.ofRecipient(500, "InternalServerError", null)).isInstanceOf(SendOutcome.Transient.class);
        assertThat(SendOutcome.ofRecipient(502, "RejectedByGateway", null)).isInstanceOf(SendOutcome.Transient.class);
        assertThat(SendOutcome.ofTransport(0, "timeout")).isEqualTo(new SendOutcome.Transient("timeout"));
        assertThat(SendOutcome.ofTransport(503, "HTTP 503")).isInstanceOf(SendOutcome.Transient.class);
    }

    @Test
    void anHttpServerErrorIsATransportFailure() {
        at.registerSender("COREBALT");
        at.failFor("+254700000500", 502);
        assertThatThrownBy(() -> client.send(true, "sandbox", FakeAfricasTalking.API_KEY, "COREBALT", "+254700000500", "x"))
                .isInstanceOfSatisfying(AfricasTalkingClient.TransportFailure.class, e -> assertThat(e.httpStatus()).isEqualTo(502));
    }
}
