package com.corebalt.isp.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MpesaParsingTest {

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void P3_numbersAreReadAsPlainHashedOrMasked() {
        assertThat(Msisdn.parse("254712345678")).isEqualTo(new Msisdn.Plain("+254712345678"));
        assertThat(Msisdn.parse("0712345678")).isEqualTo(new Msisdn.Plain("+254712345678"));
        assertThat(Msisdn.parse("2547 ***** 126")).isEqualTo(new Msisdn.Masked("2547", "126"));
        assertThat(Msisdn.parse("2547*****126")).isEqualTo(new Msisdn.Masked("2547", "126"));
        assertThat(Msisdn.parse("A".repeat(64))).isEqualTo(new Msisdn.Hashed("a".repeat(64)));
        assertThat(Msisdn.parse("")).isEqualTo(new Msisdn.Absent());
        assertThat(Msisdn.parse("12")).isEqualTo(new Msisdn.Absent());
        assertThat(Msisdn.display("254712345678")).isEqualTo("+254712***678");
    }

    @Test
    void P3_referencesAreNormalisedToCustomerNumbers() {
        assertThat(AccountReference.normalise(" cb001238 ", "CB")).contains("CB001238");
        assertThat(AccountReference.normalise("CB 001 238", "CB")).contains("CB001238");
        assertThat(AccountReference.normalise("cb-1238", "CB")).contains("CB001238");
        assertThat(AccountReference.normalise("1238", "CB")).contains("CB001238");
        assertThat(AccountReference.normalise("CB001238-2", "CB")).contains("CB001238");
        assertThat(AccountReference.normalise("ZZ001238", "CB")).isEmpty();
        assertThat(AccountReference.normalise("HOUSE 4", "CB")).isEmpty();
        assertThat(AccountReference.normalise("1234567", "CB")).isEmpty();
    }

    @Test
    void B2_amountsBecomeExactMinorUnitsAndTimesAreNairobi() {
        final IncomingPayment payment = MpesaCallbacks.c2bConfirmation(json.readTree("""
                {"TransID":"QK1","TransAmount":"2500.50","TransTime":"20261117143012","BillRefNumber":"CB001238",
                 "MSISDN":"2547 ***** 126","FirstName":"JOHN","MiddleName":"","LastName":"DOE"}
                """), "{}");
        assertThat(payment.amountMinor()).isEqualTo(250_050);
        assertThat(payment.receivedAt()).isEqualTo(Instant.parse("2026-11-17T11:30:12Z"));
        assertThat(payment.payerName()).isEqualTo("JOHN DOE");
        assertThatThrownBy(() -> MpesaCallbacks.c2bConfirmation(json.readTree("{\"TransAmount\":\"10\"}"), "{}"))
                .isInstanceOf(MpesaCallbacks.UnreadableCallback.class).hasMessage("missing TransID");
        assertThatThrownBy(() -> MpesaCallbacks.minorUnits("10.001")).isInstanceOf(MpesaCallbacks.UnreadableCallback.class);
    }
}
