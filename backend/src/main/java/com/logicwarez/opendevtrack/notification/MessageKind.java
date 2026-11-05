/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import java.util.Locale;

/** What a message tells (ADR-13), from the most to the least direct: a user gets one message per change. */
enum MessageKind {
    MENTIONED, ASSIGNED, COMMENTED, TRANSITIONED;

    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    static MessageKind parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
