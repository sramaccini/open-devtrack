/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import java.util.List;
import java.util.Locale;

/** What a message tells (ADR-13), from the most to the least direct: a user gets one message per change. */
enum MessageKind {
    MENTIONED, ASSIGNED, REVIEW_REQUESTED, COMMENTED, TRANSITIONED,
    /** A webhook of a project the user is an admin of was turned off (ADR-21, B11): in the application only. */
    WEBHOOK_OFF;

    /** The kinds of the notification settings (ADR-18): all but {@link #WEBHOOK_OFF}, which has no choice. */
    static final List<MessageKind> SETTABLE = List.of(MENTIONED, ASSIGNED, REVIEW_REQUESTED, COMMENTED, TRANSITIONED);

    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The value without underscores ({@code reviewRequested}), for the names of the fields of a form. */
    String key() {
        String[] words = value().split("_");
        StringBuilder key = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            key.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return key.toString();
    }

    static MessageKind parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
