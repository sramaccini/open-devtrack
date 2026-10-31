/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Writes the changelog of the issues from the events of the module issue, after their commit and in a transaction of
 * its own; an event whose listener fails stays in the Event Publication Registry and is republished.
 */
@Component
class Changelog {

    /** The field of the row that marks the creation of an issue, before the values it was created with. */
    static final String CREATED = "created";

    private final IssueChangeRepository changes;

    Changelog(IssueChangeRepository changes) {
        this.changes = changes;
    }

    @ApplicationModuleListener
    void on(IssueCreated event) {
        changes.save(new IssueChangeEntity(event.issueId(), event.by(), event.at(), CREATED, null, event.key()));
        save(event.issueId(), event.by(), event.at(), event.fields());
    }

    @ApplicationModuleListener
    void on(IssueChanged event) {
        save(event.issueId(), event.by(), event.at(), event.changes());
    }

    private void save(UUID issueId, String by, Instant at, List<FieldChange> fields) {
        changes.saveAll(fields.stream().map(f -> new IssueChangeEntity(issueId, by, at, f.field(), f.from(), f.to())).toList());
    }
}
