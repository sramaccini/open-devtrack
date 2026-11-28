/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import java.time.Instant;
import java.util.UUID;

/** Events of the work log (ADR-30), published inside the transaction of the change. */
public final class WorkLogEvents {

    private WorkLogEvents() {
    }

    /** Work logged: {@code minutes} of it. */
    public record WorkLogged(UUID workLogId, UUID issueId, UUID projectId, String issueKey, String by, Instant at,
                             int minutes) {
    }

    /** A work log changed: {@code minutes} as it is now. */
    public record WorkLogChanged(UUID workLogId, UUID issueId, UUID projectId, String issueKey, String by, Instant at,
                                 int minutes) {
    }

    /** A work log deleted: {@code minutes} it had. */
    public record WorkLogDeleted(UUID workLogId, UUID issueId, UUID projectId, String issueKey, String by, Instant at,
                                 int minutes) {
    }
}
