/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.logicwarez.opendevtrack.platform.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The comments of an issue as the user sees them, with what they may do on each: for the API of the comments and for
 * the activity of an issue in the module audit. The caller has checked that the user sees the issue.
 */
@Component
public class IssueComments {

    private final CommentRepository comments;
    private final CurrentUser user;

    IssueComments(CommentRepository comments, CurrentUser user) {
        this.comments = comments;
        this.user = user;
    }

    /** A comment, with {@code edit} and {@code delete} for the user. */
    public record Comment(UUID id, String author, String body, Instant createdAt, Instant updatedAt, boolean edited,
                          long version, Map<String, Boolean> perm) {
    }

    /** Oldest first; {@code permissions} are those of the user in the project of the issue. */
    public List<Comment> of(UUID issueId, Set<String> permissions) {
        return comments.findByIssueIdOrderByCreatedAtAscIdAsc(issueId).stream().map(c -> view(c, permissions)).toList();
    }

    Comment view(CommentEntity c, Set<String> permissions) {
        return new Comment(c.id(), c.author(), c.body(), c.createdAt(), c.updatedAt(),
                !c.updatedAt().equals(c.createdAt()), c.version(),
                Map.of("edit", canEdit(c, permissions), "delete", canDelete(c, permissions)));
    }

    /** Its author changes a comment while they may comment. */
    boolean canEdit(CommentEntity comment, Set<String> permissions) {
        return permissions.contains("odt.issue.comment") && comment.author().equals(user.username());
    }

    /** Its author, or whoever may delete issues, deletes a comment. */
    boolean canDelete(CommentEntity comment, Set<String> permissions) {
        return canEdit(comment, permissions) || permissions.contains("odt.issue.delete");
    }
}
