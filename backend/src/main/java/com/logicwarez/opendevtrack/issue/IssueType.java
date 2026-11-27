/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Types of issue, on three levels (ADR-28): an epic is the parent of stories, tasks and bugs, which are the parents of
 * subtasks.
 */
enum IssueType {

    TASK, BUG, STORY, EPIC, SUBTASK;

    /** The level in the hierarchy: 1 for an epic, 0 for a story, task or bug, -1 for a subtask. */
    int level() {
        return switch (this) {
            case EPIC -> 1;
            case SUBTASK -> -1;
            default -> 0;
        };
    }

    /** Name in the API: {@code task}, {@code bug}, {@code story}, {@code epic}, {@code subtask}. */
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
