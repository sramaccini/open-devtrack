/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import java.util.List;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The comments and attachments of a deleted issue go with it, after the commit of the deletion; the bytes of the
 * attachments after the commit of their metadata (ADR-14).
 */
@Component
class IssueCleanup {

    private final CommentRepository comments;
    private final AttachmentRepository attachments;
    private final AttachmentBytes bytes;

    IssueCleanup(CommentRepository comments, AttachmentRepository attachments, AttachmentBytes bytes) {
        this.comments = comments;
        this.attachments = attachments;
        this.bytes = bytes;
    }

    @ApplicationModuleListener
    void on(IssueDeleted event) {
        comments.deleteByIssue(event.issueId());
        List<AttachmentEntity> gone = attachments.findByIssueIdOrderByUploadedAtAscIdAsc(event.issueId());
        attachments.deleteAll(gone);
        gone.forEach(bytes::removeAfterCommit);
    }
}
