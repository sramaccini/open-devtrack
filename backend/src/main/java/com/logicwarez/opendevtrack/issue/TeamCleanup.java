/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.TeamEvents.TeamDeleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** A team deleted in {@code access} leaves its issues without a team (ADR-31), after the commit of the deletion. */
@Component
class TeamCleanup {

    private final Issues issues;

    TeamCleanup(Issues issues) {
        this.issues = issues;
    }

    @ApplicationModuleListener
    void on(TeamDeleted event) {
        issues.teamDeleted(event.teamId(), event.tenantId(), event.name(), event.by(), event.at());
    }
}
