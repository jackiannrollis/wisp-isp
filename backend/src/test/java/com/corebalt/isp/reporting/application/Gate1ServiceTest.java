package com.corebalt.isp.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The matching criterion of Gate 1 (docs/09, ADR 0018) is judged on the counts, not on the rounded percentage. */
class Gate1ServiceTest {

    @Test
    void ADR0018_matchingAbove98IsJudgedOnTheCountsNotTheRoundedPercent() {
        assertThat(Gate1Service.matchingAboveTarget(9_804, 10_000)).isTrue();
        assertThat(Gate1Service.matchingAboveTarget(98, 100)).isFalse();
        assertThat(Gate1Service.matchingAboveTarget(49, 50)).isFalse();
        assertThat(Gate1Service.matchingAboveTarget(99, 100)).isTrue();
        assertThat(Gate1Service.matchingAboveTarget(0, 0)).isNull();
    }
}
