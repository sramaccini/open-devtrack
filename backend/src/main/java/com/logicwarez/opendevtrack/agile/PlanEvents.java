/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import java.util.UUID;

/** Events of the plan of the issues (ADR-16), published inside the transaction of the change. */
public final class PlanEvents {

    private PlanEvents() {
    }

    /** The rank, the sprint or the points of the issue changed: read them again from {@link Planning}. */
    public record IssuePlanned(UUID issueId, UUID projectId) {
    }
}
