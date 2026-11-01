/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Watchers of the issues (E2), the users to tell of their changes. Whoever sees an issue watches it or stops
 * ({@code me}); {@code odt.issue.edit} also adds and removes the other members of the project. The reporter and the
 * assignee watch it from the start ({@link IssueController}).
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class WatcherController {

    private static final String ME = "me";
    private static final String NOT_FOUND = "Issue not found";
    private static final String NOT_A_MEMBER = "Only the members of the project see its issues";

    private final Issues finder;
    private final WatcherRepository watchers;
    private final ProjectAccess access;
    private final CurrentUser user;

    WatcherController(Issues finder, WatcherRepository watchers, ProjectAccess access, CurrentUser user) {
        this.finder = finder;
        this.watchers = watchers;
        this.access = access;
        this.user = user;
    }

    record WatcherView(String username, Instant since, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** {@code username} empty or {@code me} for the user. */
    record WatchRequest(String username) {
    }

    @GetMapping("/api/issues/{issueKey}/watchers")
    ResponseEntity<?> list(@PathVariable String issueKey) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        return ResponseEntity.ok(watchers.findByIssueIdOrderByUsername(found.get().issue().id()).stream()
                .map(w -> new WatcherView(w.username(), w.createdAt(),
                        Map.of("delete", w.username().equals(user.username()) || permissions.contains("odt.issue.edit"))))
                .toList());
    }

    @PostMapping("/api/issues/{issueKey}/watchers")
    @Transactional
    ResponseEntity<?> watch(@PathVariable String issueKey, @RequestBody(required = false) WatchRequest request) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        String username = who(request == null ? null : request.username());
        if (!username.equals(user.username())) {
            if (!permissions.contains("odt.issue.edit")) {
                return Problems.forbidden("Only a developer or an admin of the project adds other watchers");
            }
            if (access.role(found.get().project().id(), username).isEmpty()) {
                return Problems.badRequest("A watcher must be a member of the project");
            }
        }
        watchers.watch(found.get().issue().id(), username, Instant.now());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/issues/{issueKey}/watchers/{username}")
    @Transactional
    ResponseEntity<?> unwatch(@PathVariable String issueKey, @PathVariable String username) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        String who = who(username);
        if (!who.equals(user.username()) && !permissions.contains("odt.issue.edit")) {
            return Problems.forbidden("Only a developer or an admin of the project removes other watchers");
        }
        Optional<WatcherEntity> watcher = watchers.findByIssueIdAndUsername(found.get().issue().id(), who);
        if (watcher.isEmpty()) {
            return Problems.notFound("Not a watcher of the issue");
        }
        watchers.delete(watcher.get());
        return ResponseEntity.noContent().build();
    }

    private String who(String username) {
        return username == null || username.isBlank() || ME.equals(username) ? user.username() : username.strip();
    }

    private Set<String> permissions(Found found) {
        return access.permissions(found.project().id(), user.username());
    }
}
