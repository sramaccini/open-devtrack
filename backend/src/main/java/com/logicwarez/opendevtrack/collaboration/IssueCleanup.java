/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** The comments of a deleted issue go with it, after the commit of the deletion. */
@Component
class IssueCleanup {

    private final CommentRepository comments;

    IssueCleanup(CommentRepository comments) {
        this.comments = comments;
    }

    @ApplicationModuleListener
    void on(IssueDeleted event) {
        comments.deleteByIssue(event.issueId());
    }
}
