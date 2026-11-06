/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import com.logicwarez.opendevtrack.collaboration.AttachmentEvents.AttachmentAdded;
import com.logicwarez.opendevtrack.collaboration.AttachmentEvents.AttachmentRemoved;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Each attachment added or removed is a row of the changelog of its issue, field {@code attachments} (ADR-14). */
@Component
class AttachmentLog {

    static final String FIELD = "attachments";

    private final IssueChangeRepository changes;

    AttachmentLog(IssueChangeRepository changes) {
        this.changes = changes;
    }

    @ApplicationModuleListener
    void on(AttachmentAdded event) {
        changes.save(new IssueChangeEntity(event.issueId(), event.by(), event.at(), FIELD, null, event.filename()));
    }

    @ApplicationModuleListener
    void on(AttachmentRemoved event) {
        changes.save(new IssueChangeEntity(event.issueId(), event.by(), event.at(), FIELD, event.filename(), null));
    }
}
