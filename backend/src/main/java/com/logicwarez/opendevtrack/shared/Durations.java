/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations of time tracking as Jira writes them (ADR-30): {@code 1w 2d 3h 30m}, any of the four parts in this order,
 * kept as whole minutes. A day and a week are as long as the installation says ({@code hoursPerDay},
 * {@code daysPerWeek}).
 */
public record Durations(int hoursPerDay, int daysPerWeek) {

    /** At most 10 000 hours, in minutes. */
    public static final int MAX_MINUTES = 600_000;

    private static final Pattern TEXT = Pattern.compile(
            "^(?:(\\d{1,6})w)?\\s*(?:(\\d{1,6})d)?\\s*(?:(\\d{1,6})h)?\\s*(?:(\\d{1,6})m)?$");

    public Durations {
        if (hoursPerDay < 1 || hoursPerDay > 24 || daysPerWeek < 1 || daysPerWeek > 7) {
            throw new IllegalArgumentException("Hours per day are 1 to 24, days per week 1 to 7");
        }
    }

    /** A duration that is not right, with the reason in English. */
    public static final class WrongDuration extends Exception {
        public WrongDuration(String message) {
            super(message);
        }
    }

    /** The minutes of a text, at least one; a bare number is minutes. */
    public int minutes(String text) throws WrongDuration {
        int minutes = minutesOrZero(text);
        if (minutes < 1) {
            throw new WrongDuration("A duration is at least one minute");
        }
        return minutes;
    }

    /** The minutes of a text, zero allowed ({@code 0m}), as an estimate may be. */
    public int minutesOrZero(String text) throws WrongDuration {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (t.matches("\\d{1,6}")) {
            return checked(Long.parseLong(t));
        }
        Matcher m = TEXT.matcher(t);
        if (t.isEmpty() || !m.matches()) {
            throw new WrongDuration("A duration is written as 1w 2d 3h 30m");
        }
        long minutes = part(m, 1) * daysPerWeek * hoursPerDay * 60L + part(m, 2) * hoursPerDay * 60L
                + part(m, 3) * 60L + part(m, 4);
        return checked(minutes);
    }

    /** The text of some minutes, with the largest parts first: {@code 1d 2h 30m}; {@code 0m} for none. */
    public String text(int minutes) {
        if (minutes <= 0) {
            return "0m";
        }
        int week = daysPerWeek * hoursPerDay * 60;
        int day = hoursPerDay * 60;
        List<String> parts = new ArrayList<>();
        int rest = minutes;
        if (rest >= week) {
            parts.add(rest / week + "w");
            rest %= week;
        }
        if (rest >= day) {
            parts.add(rest / day + "d");
            rest %= day;
        }
        if (rest >= 60) {
            parts.add(rest / 60 + "h");
            rest %= 60;
        }
        if (rest > 0) {
            parts.add(rest + "m");
        }
        return String.join(" ", parts);
    }

    /** The text of some minutes, or {@code null} for none. */
    public String textOrNull(Integer minutes) {
        return minutes == null ? null : text(minutes);
    }

    private static long part(Matcher m, int group) {
        return m.group(group) == null ? 0 : Long.parseLong(m.group(group));
    }

    private static int checked(long minutes) throws WrongDuration {
        if (minutes > MAX_MINUTES) {
            throw new WrongDuration("A duration is at most 10000 hours");
        }
        return (int) minutes;
    }
}
