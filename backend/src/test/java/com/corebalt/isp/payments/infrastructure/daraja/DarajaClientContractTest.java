package com.corebalt.isp.payments.infrastructure.daraja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebalt.isp.support.FakeDaraja;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the requests we send to Daraja (docs/05 APIs used), so a sandbox run only has to confirm
 * Safaricom's side. Credentials are obvious fakes; 174379 and its passkey are Safaricom's public
 * sandbox test values' shape, not real ones.
 */
class DarajaClientContractTest {

    /** 10:00 UTC is 13:00 in Nairobi. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
    static final DarajaClient.Credentials CREDENTIALS = new DarajaClient.Credentials(
            true, "174379", false, "fake-key", "fake-secret", "fake-passkey", "fake-initiator", "fake-security-credential");

    final JsonMapper json = JsonMapper.builder().build();
    final FakeDaraja daraja = new FakeDaraja();
    final DarajaClient client = new DarajaClient(json, CLOCK, daraja.baseUrl(), "http://unused.invalid");

    @AfterEach
    void stop() {
        daraja.close();
    }

    @Test
    void oauthUsesBasicAuthWithClientCredentialsAndIsCached() {
        assertThat(client.accessToken(CREDENTIALS)).isEqualTo("fake-access-token");
        client.accessToken(CREDENTIALS);
        final var oauth = daraja.requestsTo("/oauth/v1/generate");
        assertThat(oauth).hasSize(1);
        assertThat(oauth.getFirst().method()).isEqualTo("GET");
        assertThat(oauth.getFirst().path()).isEqualTo("/oauth/v1/generate?grant_type=client_credentials");
        assertThat(oauth.getFirst().header("Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("fake-key:fake-secret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void c2bRegisterUrlIsV2WithResponseTypeCompleted() {
        client.registerUrls(CREDENTIALS, "https://isp.example/callbacks/mpesa/t/c2b/confirmation",
                "https://isp.example/callbacks/mpesa/t/c2b/validation");
        final var request = daraja.requestsTo("/mpesa/c2b/v2/registerurl").getFirst();
        assertThat(request.header("Authorization")).isEqualTo("Bearer fake-access-token");
        assertThat(body(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ShortCode", "174379",
                "ResponseType", "Completed",
                "ConfirmationURL", "https://isp.example/callbacks/mpesa/t/c2b/confirmation",
                "ValidationURL", "https://isp.example/callbacks/mpesa/t/c2b/validation"));
    }

    @Test
    void stkPushSendsPasswordTimestampInNairobiAndWholeShillings() {
        final var result = client.stkPush(CREDENTIALS, 2500, "254708374149", "CB001238", "Internet", "https://isp.example/cb/stk");
        assertThat(result.checkoutRequestId()).isEqualTo("ws_CO_191220191020363925");
        final Map<String, Object> body = body(daraja.requestsTo("/mpesa/stkpush/v1/processrequest").getFirst());
        assertThat(body).containsEntry("BusinessShortCode", "174379")
                .containsEntry("Timestamp", "20261117130000")
                .containsEntry("Password", Base64.getEncoder().encodeToString(
                        "174379fake-passkey20261117130000".getBytes(StandardCharsets.UTF_8)))
                .containsEntry("TransactionType", "CustomerPayBillOnline")
                .containsEntry("Amount", 2500)
                .containsEntry("PartyA", "254708374149")
                .containsEntry("PartyB", "174379")
                .containsEntry("PhoneNumber", "254708374149")
                .containsEntry("CallBackURL", "https://isp.example/cb/stk")
                .containsEntry("AccountReference", "CB001238")
                .containsEntry("TransactionDesc", "Internet");
        assertThat(body).hasSize(11);
    }

    @Test
    void tillsUseBuyGoods() {
        final var till = new DarajaClient.Credentials(true, "600000", true, "k", "s", "p", null, null);
        client.stkPush(till, 10, "254708374149", "CB001238", "Internet", "https://isp.example/cb/stk");
        assertThat(body(daraja.requestsTo("/mpesa/stkpush/v1/processrequest").getFirst()))
                .containsEntry("TransactionType", "CustomerBuyGoodsOnline");
    }

    @Test
    void stkQueryWhileStillProcessingIsNotAnError() {
        final var result = client.stkQuery(CREDENTIALS, "ws_CO_1");
        assertThat(result.stillProcessing()).isTrue();
        assertThat(body(daraja.requestsTo("/mpesa/stkpushquery/v1/query").getFirst()))
                .containsOnlyKeys("BusinessShortCode", "Password", "Timestamp", "CheckoutRequestID")
                .containsEntry("CheckoutRequestID", "ws_CO_1");

        daraja.respond("/mpesa/stkpushquery/v1/query", 200,
                "{\"ResponseCode\":\"0\",\"ResultCode\":\"1032\",\"ResultDesc\":\"Request cancelled by user\"}");
        assertThat(client.stkQuery(CREDENTIALS, "ws_CO_1").resultCode()).isEqualTo("1032");
    }

    @Test
    void transactionStatusUsesTheStoredSecurityCredential() {
        client.transactionStatus(CREDENTIALS, "QKA81LK5CY", "https://isp.example/r", "https://isp.example/t");
        assertThat(body(daraja.requestsTo("/mpesa/transactionstatus/v1/query").getFirst()))
                .containsEntry("Initiator", "fake-initiator")
                .containsEntry("SecurityCredential", "fake-security-credential")
                .containsEntry("CommandID", "TransactionStatusQuery")
                .containsEntry("TransactionID", "QKA81LK5CY")
                .containsEntry("PartyA", "174379")
                .containsEntry("IdentifierType", "4")
                .containsEntry("ResultURL", "https://isp.example/r")
                .containsEntry("QueueTimeOutURL", "https://isp.example/t")
                .containsKeys("Remarks", "Occasion");
    }

    @Test
    void errorsCarryTheDarajaCodeButNoPayload() {
        daraja.respond("/mpesa/stkpush/v1/processrequest", 400,
                "{\"requestId\":\"x\",\"errorCode\":\"400.002.02\",\"errorMessage\":\"Bad Request - Invalid Amount\"}");
        assertThatThrownBy(() -> client.stkPush(CREDENTIALS, 0, "254708374149", "CB1", "x", "https://x"))
                .isInstanceOf(DarajaClient.DarajaException.class)
                .hasMessage("Daraja: HTTP 400 (400.002.02)")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("fake"));
        assertThat(CREDENTIALS.toString()).doesNotContain("fake-secret").doesNotContain("fake-passkey");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(final FakeDaraja.Recorded request) {
        return json.readValue(request.body(), Map.class);
    }
}
