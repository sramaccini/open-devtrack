/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Durations as Jira writes them (ADR-30). */
class DurationsTest {

    private final Durations jira = new Durations(8, 5);

    @Test
    void theTextBecomesMinutesWithTheDaysAndWeeksOfTheInstallation() throws Exception {
        assertThat(jira.minutes("1w 2d 3h 30m")).isEqualTo(2400 + 960 + 180 + 30);
        assertThat(jira.minutes("1d30m")).isEqualTo(510);
        assertThat(jira.minutes(" 2H ")).isEqualTo(120);
        assertThat(jira.minutes("45")).isEqualTo(45);
        assertThat(jira.minutesOrZero("0m")).isZero();
        assertThat(new Durations(6, 4).minutes("1w 1d")).isEqualTo(24 * 60 + 6 * 60);
    }

    @Test
    void wrongTextsAreRefused() {
        assertThatThrownBy(() -> jira.minutes("0m")).isInstanceOf(Durations.WrongDuration.class);
        assertThatThrownBy(() -> jira.minutes("2 hours")).isInstanceOf(Durations.WrongDuration.class);
        assertThatThrownBy(() -> jira.minutes("3h 1d")).isInstanceOf(Durations.WrongDuration.class);
        assertThatThrownBy(() -> jira.minutesOrZero("")).isInstanceOf(Durations.WrongDuration.class);
        assertThatThrownBy(() -> jira.minutes("10001h")).isInstanceOf(Durations.WrongDuration.class);
        assertThatThrownBy(() -> new Durations(0, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minutesAreWrittenWithTheLargestPartsFirst() {
        assertThat(jira.text(2400 + 960 + 180 + 30)).isEqualTo("1w 2d 3h 30m");
        assertThat(jira.text(510)).isEqualTo("1d 30m");
        assertThat(jira.text(60)).isEqualTo("1h");
        assertThat(jira.text(0)).isEqualTo("0m");
        assertThat(jira.textOrNull(null)).isNull();
    }
}
