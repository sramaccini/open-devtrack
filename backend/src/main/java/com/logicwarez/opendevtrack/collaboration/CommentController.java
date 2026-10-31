/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueRef;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comments of the issues (E4), in restricted Markdown. Whoever sees the issue reads them; {@code odt.issue.comment}
 * (developer) writes them; the author edits and deletes their own, an admin of the project ({@code odt.issue.delete})
 * also deletes those of others.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class CommentController {

    private static final int MAX_BODY = 32_000;
    private static final String ISSUE_NOT_FOUND = "Issue not found";
    private static final String NOT_FOUND = "Comment not found";

    private final Issues issues;
    private final CommentRepository comments;
    private final ProjectAccess access;
    private final CurrentUser user;

    CommentController(Issues issues, CommentRepository comments, ProjectAccess access, CurrentUser user) {
        this.issues = issues;
        this.comments = comments;
        this.access = access;
        this.user = user;
    }

    record CommentView(UUID id, String author, String body, Instant createdAt, Instant updatedAt, boolean edited,
                       long version, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    record CommentRequest(String body, Long version) {
    }

    @GetMapping("/api/issues/{issueKey}/comments")
    ResponseEntity<?> list(@PathVariable String issueKey) {
        Optional<IssueRef> issue = issues.find(issueKey);
        if (issue.isEmpty()) {
            return Problems.notFound(ISSUE_NOT_FOUND);
        }
        Set<String> permissions = permissions(issue.get());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden("Only the members of the project see its issues");
        }
        return ResponseEntity.ok(comments.findByIssueIdOrderByCreatedAtAscIdAsc(issue.get().id()).stream()
                .map(c -> view(c, permissions)).toList());
    }

    @PostMapping("/api/issues/{issueKey}/comments")
    @Transactional
    ResponseEntity<?> add(@PathVariable String issueKey, @RequestBody CommentRequest request) {
        Optional<IssueRef> issue = issues.find(issueKey);
        if (issue.isEmpty()) {
            return Problems.notFound(ISSUE_NOT_FOUND);
        }
        Set<String> permissions = permissions(issue.get());
        if (!permissions.contains("odt.issue.comment")) {
            return Problems.forbidden("Only a developer or an admin of the project comments");
        }
        Optional<String> invalid = check(request.body());
        if (invalid.isPresent()) {
            return Problems.badRequest(invalid.get());
        }
        CommentEntity comment = comments.saveAndFlush(new CommentEntity(issue.get().id(), user.username(),
                request.body().strip(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(comment, permissions));
    }

    /** Only the author, from the version read, or 412 if it changed in the meantime. */
    @PutMapping("/api/issues/{issueKey}/comments/{id}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable String issueKey, @PathVariable UUID id, @RequestBody CommentRequest request) {
        Optional<Found> found = find(issueKey, id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        CommentEntity comment = found.get().comment();
        if (!canEdit(comment, found.get().permissions())) {
            return Problems.forbidden("Only the author edits a comment");
        }
        Optional<String> invalid = check(request.body());
        if (invalid.isPresent()) {
            return Problems.badRequest(invalid.get());
        }
        if (request.version() == null || request.version() != comment.version()) {
            return Problems.changedMeanwhile();
        }
        comment.edit(request.body().strip(), Instant.now());
        try {
            comments.saveAndFlush(comment);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        return ResponseEntity.ok(view(comment, found.get().permissions()));
    }

    @DeleteMapping("/api/issues/{issueKey}/comments/{id}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable String issueKey, @PathVariable UUID id) {
        Optional<Found> found = find(issueKey, id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!canDelete(found.get().comment(), found.get().permissions())) {
            return Problems.forbidden("Only the author or an admin of the project deletes a comment");
        }
        comments.delete(found.get().comment());
        return ResponseEntity.noContent().build();
    }

    private record Found(CommentEntity comment, Set<String> permissions) {
    }

    /** The comment of that issue, seen by the user; another issue's comment is not found. */
    private Optional<Found> find(String issueKey, UUID id) {
        return issues.find(issueKey).flatMap(issue -> {
            Set<String> permissions = permissions(issue);
            if (!permissions.contains("odt.issue.browse")) {
                return Optional.empty();
            }
            return comments.findById(id).filter(c -> c.issueId().equals(issue.id())).map(c -> new Found(c, permissions));
        });
    }

    private Set<String> permissions(IssueRef issue) {
        return access.permissions(issue.projectId(), user.username());
    }

    private boolean canEdit(CommentEntity comment, Set<String> permissions) {
        return permissions.contains("odt.issue.comment") && comment.author().equals(user.username());
    }

    private boolean canDelete(CommentEntity comment, Set<String> permissions) {
        return canEdit(comment, permissions) || permissions.contains("odt.issue.delete");
    }

    private static Optional<String> check(String body) {
        if (body == null || body.isBlank()) {
            return Optional.of("The comment is empty");
        }
        if (body.length() > MAX_BODY) {
            return Optional.of("A comment is at most " + MAX_BODY + " characters");
        }
        return Optional.empty();
    }

    private CommentView view(CommentEntity c, Set<String> permissions) {
        return new CommentView(c.id(), c.author(), c.body(), c.createdAt(), c.updatedAt(), !c.updatedAt().equals(c.createdAt()),
                c.version(), Map.of("edit", canEdit(c, permissions), "delete", canDelete(c, permissions)));
    }
}
