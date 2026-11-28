/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.time.Instant;
import java.util.UUID;

/** Events of the teams (ADR-31), for the modules whose data names a team. */
public final class TeamEvents {

    private TeamEvents() {
    }

    /** A team was deleted: the issues that named it are left without a team. */
    public record TeamDeleted(UUID teamId, String tenantId, String name, String by, Instant at) {
    }
}
