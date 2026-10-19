package com.corebalt.isp.customers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource({
        "0712345678, +254712345678",
        "0712 345 678, +254712345678",
        "0110-123-456, +254110123456",
        "712345678, +254712345678",
        "254712345678, +254712345678",
        "+254 712 345 678, +254712345678",
        "+44 20 7946 0958, +442079460958"
    })
    void normalisesToE164(final String input, final String expected) {
        assertThat(PhoneNumbers.normalise(input)).hasValue(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "0812345678", "+0712345678", "07123456789", "phone", "+2547123456789012"})
    void rejectsWhatIsNotAPhoneNumber(final String input) {
        assertThat(PhoneNumbers.normalise(input)).isEmpty();
    }
}
