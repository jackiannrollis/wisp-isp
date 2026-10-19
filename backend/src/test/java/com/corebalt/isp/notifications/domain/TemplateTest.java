package com.corebalt.isp.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class TemplateTest {

    @ParameterizedTest
    @EnumSource(Template.class)
    void everyDefaultFitsOneGsm7PartWithRealisticValues(final Template template) {
        final String rendered = Template.render(template.defaultBody(), Template.SAMPLE);
        final SmsSegments segments = SmsSegments.of(rendered);
        assertThat(segments.encoding()).as(rendered).isEqualTo("GSM-7");
        assertThat(segments.segments()).as("%s is %d characters: %s", template, segments.length(), rendered).isOne();
        assertThat(rendered).doesNotContain("{");
    }

    @ParameterizedTest
    @EnumSource(Template.class)
    void M3_defaultsUseOnlyAllowedPlaceholders(final Template template) {
        assertThat(Template.unknownPlaceholders(template.defaultBody())).isEmpty();
    }

    @Test
    void M3_aTemplateCannotAskForAnythingOutsideTheList() {
        assertThat(Template.unknownPlaceholders("Your PIN is {pin}, ID {national_id}, hi {name}"))
                .containsExactly("pin", "national_id");
    }

    @Test
    void segmentsCountGsm7ExtensionsTwiceAndSwitchToUcs2ForOtherCharacters() {
        assertThat(SmsSegments.of("a".repeat(160))).isEqualTo(new SmsSegments("GSM-7", 160, 1));
        assertThat(SmsSegments.of("a".repeat(161)).segments()).isEqualTo(2);
        assertThat(SmsSegments.of("€".repeat(80))).isEqualTo(new SmsSegments("GSM-7", 160, 1));
        assertThat(SmsSegments.of("a".repeat(306)).segments()).isEqualTo(2);
        assertThat(SmsSegments.of("a".repeat(307)).segments()).isEqualTo(3);
        assertThat(SmsSegments.of("Habari 😀")).isEqualTo(new SmsSegments("UCS-2", 9, 1));
        assertThat(SmsSegments.of("’".repeat(71)).segments()).isEqualTo(2);
    }

    @Test
    void M2_remindersWaitForTheWindowInTheTenantsZone() {
        final SendingWindow window = new SendingWindow(LocalTime.of(7, 0), LocalTime.of(21, 0), ZoneId.of("Africa/Nairobi"));
        // 03:00 UTC is 06:00 in Nairobi: held to 07:00 Nairobi.
        assertThat(window.scheduleFor(Instant.parse("2026-11-17T03:00:00Z"), false)).isEqualTo(Instant.parse("2026-11-17T04:00:00Z"));
        assertThat(window.scheduleFor(Instant.parse("2026-11-17T09:00:00Z"), false)).isEqualTo(Instant.parse("2026-11-17T09:00:00Z"));
        // 18:30 UTC is 21:30: next morning.
        assertThat(window.scheduleFor(Instant.parse("2026-11-17T18:30:00Z"), false)).isEqualTo(Instant.parse("2026-11-18T04:00:00Z"));
        assertThat(window.scheduleFor(Instant.parse("2026-11-17T18:30:00Z"), true)).isEqualTo(Instant.parse("2026-11-17T18:30:00Z"));
    }

    @Test
    void renderingFillsKnownValuesAndBlanksMissingOnes() {
        assertThat(Template.render("Hi {name}, {package}.", Map.of("name", "Ann"))).isEqualTo("Hi Ann, .");
        assertThat(Template.render("Pay $5 {name}", Map.of("name", "A$B"))).isEqualTo("Pay $5 A$B");
    }
}
