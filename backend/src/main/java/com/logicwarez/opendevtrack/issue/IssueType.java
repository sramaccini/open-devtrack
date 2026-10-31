/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Types of issue; per-project types and custom fields come later in F2. */
enum IssueType {

    TASK, BUG, STORY, EPIC;

    /** Name in the API: {@code task}, {@code bug}, {@code story}, {@code epic}. */
    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<IssueType> parse(String value) {
        return Arrays.stream(values()).filter(t -> t.value().equals(value)).findFirst();
    }

    static List<String> names() {
        return Arrays.stream(values()).map(IssueType::value).toList();
    }
}
