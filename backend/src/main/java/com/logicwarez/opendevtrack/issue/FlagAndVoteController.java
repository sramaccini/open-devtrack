/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The flag and the votes of an issue, as in Jira. Whoever edits the issue flags it as impeded, or removes the flag: a
 * change of the issue, in its history as «Flagged». Whoever sees the issue votes for it once, or takes the vote back;
 * as in Jira, not the reporter, and nobody once the issue is done.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class FlagAndVoteController {

    /** The value of the flag in the history, as Jira names it. */
    static final String IMPEDIMENT = "Impediment";

    private static final String NOT_FOUND = "Issue not found";
    private static final String NOT_A_MEMBER = "Only the members of the project see its issues";

    private final Issues finder;
    private final IssueRepository issues;
    private final VoteRepository votes;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;
    private final Workflows workflows;

    FlagAndVoteController(Issues finder, IssueRepository issues, VoteRepository votes, ProjectAccess access,
                          CurrentUser user, ApplicationEventPublisher events, Workflows workflows) {
        this.finder = finder;
        this.issues = issues;
        this.votes = votes;
        this.access = access;
        this.user = user;
        this.events = events;
        this.workflows = workflows;
    }

    record VoterView(String username, Instant since) {
    }

    @ApiDoc(value = "Flags an issue as impeded", status = 204, project = "odt.issue.edit")
    @PutMapping("/api/issues/{issueKey}/flag")
    @Transactional
    ResponseEntity<?> flag(@PathVariable String issueKey) {
        return setFlag(issueKey, true);
    }

    @ApiDoc(value = "Removes the flag of an issue", status = 204, project = "odt.issue.edit")
    @DeleteMapping("/api/issues/{issueKey}/flag")
    @Transactional
    ResponseEntity<?> unflag(@PathVariable String issueKey) {
        return setFlag(issueKey, false);
    }

    @ApiDoc(value = "Who voted for an issue", answer = VoterView.class, list = true, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/votes")
    ResponseEntity<?> voters(@PathVariable String issueKey) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(found.get()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        return ResponseEntity.ok(votes.findByIssueIdOrderByUsername(found.get().issue().id()).stream()
                .map(v -> new VoterView(v.username(), v.createdAt())).toList());
    }

    @ApiDoc(value = "Votes for an issue", status = 204, project = "odt.issue.browse")
    @PostMapping("/api/issues/{issueKey}/votes")
    @Transactional
    ResponseEntity<?> vote(@PathVariable String issueKey) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(found.get()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        Optional<ResponseEntity<?>> refused = refused(found.get());
        if (refused.isPresent()) {
            return refused.get();
        }
        votes.vote(found.get().issue().id(), user.username(), Instant.now());
        return ResponseEntity.noContent().build();
    }

    @ApiDoc(value = "Takes back the vote for an issue", status = 204, project = "odt.issue.browse")
    @DeleteMapping("/api/issues/{issueKey}/votes/me")
    @Transactional
    ResponseEntity<?> unvote(@PathVariable String issueKey) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(found.get()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        Optional<ResponseEntity<?>> refused = refused(found.get());
        if (refused.isPresent()) {
            return refused.get();
        }
        if (votes.unvote(found.get().issue().id(), user.username()) == 0) {
            return Problems.notFound("No vote for the issue");
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> setFlag(String issueKey, boolean flagged) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        if (!permissions.contains("odt.issue.edit")) {
            return Problems.forbidden("Only a developer or an admin of the project flags an issue");
        }
        IssueEntity issue = found.get().issue();
        if (issue.flagged() == flagged) {
            return ResponseEntity.noContent().build();
        }
        Instant now = Instant.now();
        issue.flag(flagged, now);
        issues.saveAndFlush(issue);
        events.publishEvent(new IssueChanged(issue.id(), issue.projectId(),
                IssueController.key(found.get().project(), issue), user.username(), now,
                List.of(new FieldChange("flagged", flagged ? null : IMPEDIMENT, flagged ? IMPEDIMENT : null))));
        return ResponseEntity.noContent().build();
    }

    /** Why the user cannot vote, or change the vote, for the issue: its reporter, or the issue is done. */
    private Optional<ResponseEntity<?>> refused(Found found) {
        IssueEntity issue = found.issue();
        if (user.username().equals(issue.reporter())) {
            return Optional.of(Problems.conflict("The reporter does not vote for the issue"));
        }
        if (done(workflows, issue)) {
            return Optional.of(Problems.conflict("The votes of a done issue do not change"));
        }
        return Optional.empty();
    }

    /** Whether the issue is in a status of the category done. */
    static boolean done(Workflows workflows, IssueEntity issue) {
        return workflows.statuses(issue.projectId()).stream()
                .anyMatch(s -> s.code().equals(issue.status()) && "done".equals(s.category()));
    }

    private Set<String> permissions(Found found) {
        return access.permissions(found.project().id(), user.username());
    }
}
