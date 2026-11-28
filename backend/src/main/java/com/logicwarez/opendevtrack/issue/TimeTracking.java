/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.shared.Durations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The settings of time tracking of the installation (ADR-30): how many hours make a day and how many days a week, for
 * the durations written as {@code 1w 2d 3h 30m}.
 */
@Component
public class TimeTracking {

    private final Durations durations;

    TimeTracking(@Value("${odt.time-tracking.hours-per-day:8}") int hoursPerDay,
                 @Value("${odt.time-tracking.days-per-week:5}") int daysPerWeek) {
        this.durations = new Durations(hoursPerDay, daysPerWeek);
    }

    public Durations durations() {
        return durations;
    }
}
