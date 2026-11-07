/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Events of the sprints (ADR-15), published inside the transaction of the change; for the reports (F4). */
public final class SprintEvents {

    private SprintEvents() {
    }

    /** {@code committedPoints}: the story points of the issues in the sprint when it starts (ADR-17). */
    public record SprintStarted(UUID sprintId, UUID projectId, String name, LocalDate startsOn, LocalDate endsOn,
                                int committedPoints, String by, Instant at) {
    }

    /**
     * {@code done}: the issues done in the sprint; {@code carried}: those that went to the backlog or to another sprint;
     * the points of each, as they are at the completion (ADR-17).
     */
    public record SprintCompleted(UUID sprintId, UUID projectId, String name, int done, int carried, int donePoints,
                                  int carriedPoints, String by, Instant at) {
    }
}
