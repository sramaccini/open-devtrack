/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.ShareRequest;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueShared;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sharing an issue, as Jira's «Share»: whoever sees it sends it to other members of the project who see it too, with
 * an optional message. Nothing changes on the issue; module notification writes a message to each, as their
 * preferences say.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class ShareController {

    static final int MAX_USERS = 10;
    static final int MAX_MESSAGE = 1000;

    private static final String BROWSE = "odt.issue.browse";

    private final Issues finder;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;

    ShareController(Issues finder, ProjectAccess access, CurrentUser user, ApplicationEventPublisher events) {
        this.finder = finder;
        this.access = access;
        this.user = user;
        this.events = events;
    }

    @ApiDoc(value = "Shares an issue with members of its project, with an optional message", status = 204,
            project = BROWSE)
    @PostMapping("/api/issues/{issueKey}/share")
    @Transactional
    ResponseEntity<?> share(@PathVariable String issueKey, @RequestBody ShareRequest request) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound("Issue not found");
        }
        Found issue = found.get();
        if (!access.permissions(issue.project().id(), user.username()).contains(BROWSE)) {
            return Problems.forbidden("Only the members of the project see its issues");
        }
        Set<String> users = new LinkedHashSet<>(request.users() == null ? List.of() : request.users());
        users.remove(user.username());
        if (users.isEmpty()) {
            return Problems.badRequest("Choose at least one person to share the issue with, other than yourself");
        }
        if (users.size() > MAX_USERS) {
            return Problems.badRequest("An issue is shared with at most " + MAX_USERS + " people at a time");
        }
        String message = request.message() == null || request.message().isBlank() ? null : request.message().strip();
        if (message != null && message.length() > MAX_MESSAGE) {
            return Problems.badRequest("The message is at most " + MAX_MESSAGE + " characters");
        }
        for (String username : users) {
            if (!access.permissions(issue.project().id(), username).contains(BROWSE)) {
                return Problems.badRequest(username + " does not see the issues of the project");
            }
        }
        events.publishEvent(new IssueShared(issue.issue().id(), issue.project().id(),
                IssueController.key(issue.project(), issue.issue()), user.username(), Instant.now(), List.copyOf(users),
                message));
        return ResponseEntity.noContent().build();
    }
}
