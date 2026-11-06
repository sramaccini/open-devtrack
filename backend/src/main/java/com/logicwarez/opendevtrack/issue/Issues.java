/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.workflow.Workflows;
import com.logicwarez.opendevtrack.workflow.Workflows.Transition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues for the other modules (comments, changelog, boards): found by their key in the tenant of the session. The
 * callers check that the user sees the project; {@link #moveTo} checks the transition itself.
 */
@Service
@Transactional(readOnly = true)
public class Issues {

    private static final Pattern ISSUE_KEY = Pattern.compile("^([A-Z][A-Z0-9]{1,9})-([1-9][0-9]{0,17})$");

    /** The most issues of a project that a board or the backlog reads at once. */
    public static final int MAX_PLANNED = 2000;

    private final IssueRepository issues;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Workflows workflows;
    private final ApplicationEventPublisher events;

    Issues(IssueRepository issues, Projects projects, ProjectAccess access, CurrentUser user, Workflows workflows,
           ApplicationEventPublisher events) {
        this.issues = issues;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.workflows = workflows;
        this.events = events;
    }

    /** The issue with this key ({@code ODT-42}); empty also when its project belongs to another tenant. */
    public Optional<IssueRef> find(String issueKey) {
        return findEntity(issueKey).map(f -> new IssueRef(f.issue().id(), f.project().id(), issueKey));
    }

    /** The issues of the project, in the order of their numbers, at most {@link #MAX_PLANNED}. */
    public List<IssueSummary> ofProject(ProjectRef project) {
        return issues.findByProjectIdOrderByNumber(project.id(), Limit.of(MAX_PLANNED)).stream()
                .map(i -> summary(project, i)).toList();
    }

    /** Outcome of {@link #moveTo}: the status of the issue after it. */
    public record Move(Outcome outcome, String status) {

        public enum Outcome { MOVED, ALREADY_THERE, NOT_FOUND, NOT_PERMITTED, NO_TRANSITION, CHANGED_MEANWHILE }
    }

    /**
     * Moves the issue to one of {@code statuses} with the first transition of the workflow that leads there and can run
     * now for the user (ADR-15): a card dropped on another column of a board. Nothing changes when the issue is already
     * in one of them.
     */
    @Transactional
    public Move moveTo(String issueKey, Set<String> statuses) {
        Optional<Found> found = findEntity(issueKey);
        if (found.isEmpty()) {
            return new Move(Move.Outcome.NOT_FOUND, null);
        }
        ProjectRef project = found.get().project();
        IssueEntity issue = found.get().issue();
        if (statuses.contains(issue.status())) {
            return new Move(Move.Outcome.ALREADY_THERE, issue.status());
        }
        Set<String> permissions = access.permissions(project.id(), user.username());
        if (!permissions.contains(Workflows.TRANSITION)) {
            return new Move(Move.Outcome.NOT_PERMITTED, issue.status());
        }
        Optional<Transition> transition = workflows.available(project.id(), issue.status(),
                IssueController.guardInput(found.get()), IssueController.userInput(user), permissions).stream()
                .filter(t -> statuses.contains(t.to())).findFirst();
        if (transition.isEmpty()) {
            return new Move(Move.Outcome.NO_TRANSITION, issue.status());
        }
        String from = issue.status();
        Instant now = Instant.now();
        issue.moveTo(transition.get().to(), now);
        try {
            issues.saveAndFlush(issue);
        } catch (ObjectOptimisticLockingFailureException e) {
            return new Move(Move.Outcome.CHANGED_MEANWHILE, from);
        }
        events.publishEvent(new IssueChanged(issue.id(), project.id(), IssueController.key(project, issue), user.username(),
                now, List.of(new FieldChange("status", from, issue.status()))));
        return new Move(Move.Outcome.MOVED, issue.status());
    }

    record Found(ProjectRef project, IssueEntity issue) {
    }

    Optional<Found> findEntity(String issueKey) {
        Matcher m = ISSUE_KEY.matcher(issueKey);
        if (!m.matches()) {
            return Optional.empty();
        }
        return projects.find(m.group(1)).flatMap(p -> issues.findByProjectIdAndNumber(p.id(), Long.parseLong(m.group(2)))
                .map(i -> new Found(p, i)));
    }

    private static IssueSummary summary(ProjectRef project, IssueEntity i) {
        return new IssueSummary(i.id(), IssueController.key(project, i), i.number(), i.type().value(), i.summary(), i.status(),
                i.priority().value(), i.assignee(), i.labels(), i.updatedAt());
    }
}
