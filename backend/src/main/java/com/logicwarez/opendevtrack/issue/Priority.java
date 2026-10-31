/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Priority of an issue, from the lowest. */
enum Priority {

    LOWEST, LOW, MEDIUM, HIGH, HIGHEST;

    /** Name in the API: {@code lowest} … {@code highest}. */
    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<Priority> parse(String value) {
        return Arrays.stream(values()).filter(p -> p.value().equals(value)).findFirst();
    }

    static List<String> names() {
        return Arrays.stream(values()).map(Priority::value).toList();
    }
}
