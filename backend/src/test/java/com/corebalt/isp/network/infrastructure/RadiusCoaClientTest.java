package com.corebalt.isp.network.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebalt.isp.support.FakeNas;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The client against a fake NAS on loopback: what counts as an answer (N5). */
class RadiusCoaClientTest {

    private static final String SECRET = "fake-router-secret";

    private final FakeNas nas = new FakeNas("127.0.0.1", 0, SECRET);
    private final RadiusCoaClient client = new RadiusCoaClient(nas.port(), Duration.ofMillis(500));

    @AfterEach
    void close() {
        nas.close();
    }

    @Test
    void N5_ackIsAnAnswer() throws Exception {
        assertThat(send().ack()).isTrue();
        assertThat(nas.received()).singleElement().satisfies(r -> {
            assertThat(r.signedWithSecret()).isTrue();
            assertThat(r.packet().code()).isEqualTo(RadiusPacket.DISCONNECT_REQUEST);
            assertThat(r.packet().string(RadiusPacket.USER_NAME)).contains("CB000001");
        });
    }

    @Test
    void N5_nakReportsItsErrorCause() throws Exception {
        nas.mode(FakeNas.Mode.SESSION_GONE);
        assertThat(send()).isEqualTo(new RadiusCoaClient.Answer(false, 503));
    }

    @Test
    void N5_aReplyNotSignedWithTheSecretIsIgnored() {
        nas.mode(FakeNas.Mode.FORGED);
        assertThatThrownBy(this::send).isInstanceOf(RadiusCoaClient.NoAnswer.class);
    }

    @Test
    void N5_silenceIsNoAnswer() {
        nas.mode(FakeNas.Mode.SILENT);
        assertThatThrownBy(this::send).isInstanceOf(RadiusCoaClient.NoAnswer.class).hasMessageContaining("no answer");
    }

    private RadiusCoaClient.Answer send() throws RadiusCoaClient.NoAnswer {
        return client.send("127.0.0.1", SECRET.getBytes(StandardCharsets.UTF_8), RadiusPacket.DISCONNECT_REQUEST, List.of(
                RadiusPacket.Attribute.string(RadiusPacket.USER_NAME, "CB000001"),
                RadiusPacket.Attribute.string(RadiusPacket.ACCT_SESSION_ID, "81a00004")));
    }
}
