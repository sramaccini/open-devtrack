/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRequest;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems.FieldError;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.workflow.Workflows;
import com.logicwarez.opendevtrack.workflow.Workflows.Transition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues for the other modules (comments, changelog, boards): found by their key in the tenant of the session. The
 * callers check that the user sees the project; {@link #moveTo} checks the transition and the change of lane
 * itself.
 */
@Service
@Transactional(readOnly = true)
public class Issues {

    /** The most issues of a project that a board or the backlog reads at once. */
    public static final int MAX_PLANNED = 2000;

    private final IssueRepository issues;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Workflows workflows;
    private final ApplicationEventPublisher events;
    private final IssueEditor editor;
    private final MovedKeys moved;

    Issues(IssueRepository issues, Projects projects, ProjectAccess access, CurrentUser user, Workflows workflows,
           ApplicationEventPublisher events, IssueEditor editor, MovedKeys moved) {
        this.editor = editor;
        this.moved = moved;
        this.issues = issues;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.workflows = workflows;
        this.events = events;
    }

    /**
     * The issue with this key ({@code ODT-42}), or that had it before it moved to another project (ADR-27), with its
     * key now; empty also when its project belongs to another tenant.
     */
    public Optional<IssueRef> find(String issueKey) {
        return findEntity(issueKey).map(f -> new IssueRef(f.issue().id(), f.project().id(),
                IssueController.key(f.project(), f.issue())));
    }

    /** Whether the issue is a subtask (ADR-28), which has no story points. */
    public boolean subtask(UUID issueId) {
        return issues.findById(issueId).map(i -> i.type() == IssueType.SUBTASK).orElse(false);
    }

    /** The summary of the issue with this key in the project, without the session: for the mails (ADR-18). */
    public Optional<String> summary(UUID projectId, String issueKey) {
        int dash = issueKey.lastIndexOf('-');
        long number;
        try {
            number = Long.parseLong(issueKey.substring(dash + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return issues.findByProjectIdAndNumber(projectId, number)
                .or(() -> moved.issueOf(projectId, number).flatMap(issues::findById))
                .map(IssueEntity::summary);
    }

    /**
     * The issue with every field, without the session: for the listeners of other modules, which run after the commit
     * and check the tenant themselves. Empty when the issue or its project is gone.
     */
    public Optional<IssueDocument> document(UUID issueId) {
        return issues.findById(issueId).flatMap(i -> projects.byId(i.projectId()).map(p -> new IssueDocument(i.id(),
                i.tenantId(), p.id(), p.key(), IssueController.key(p, i), i.number(), i.type().value(), i.summary(),
                i.description(), i.status(), i.priority().value(), i.priority().ordinal() + 1, i.assignee(), i.reporter(),
                i.dueOn(), i.reviewer(), List.copyOf(i.labels()), new HashMap<>(i.custom()), i.createdAt(), i.updatedAt(),
                i.flagged(), moved.of(i.id()), i.parentId() == null ? null : issues.findById(i.parentId())
                        .map(parent -> IssueController.key(p, parent)).orElse(null))));
    }

    /** The identifiers of every issue of the project, in the order of their numbers: to rebuild an index. */
    public List<UUID> idsOf(UUID projectId) {
        return issues.findIdsByProjectId(projectId);
    }

    /** The issues of the project, in the order of their numbers, at most {@link #MAX_PLANNED}. */
    public List<IssueSummary> ofProject(ProjectRef project) {
        return issues.findByProjectIdOrderByNumber(project.id(), Limit.of(MAX_PLANNED)).stream()
                .map(i -> summary(project, i)).toList();
    }

    /** Outcome of {@link #moveTo}: the status of the issue after it, and why the issue did not move. */
    public record Move(Outcome outcome, String status, String problem) {

        public enum Outcome {
            MOVED, ALREADY_THERE, NOT_FOUND, NOT_PERMITTED, NOT_EDITABLE, INVALID, NO_TRANSITION, CHANGED_MEANWHILE
        }

        Move(Outcome outcome, String status) {
            this(outcome, status, null);
        }
    }

    /** The fields of the issue that the lanes of a board may follow, and a move between them changes. */
    public static final List<String> LANE_FIELDS = List.of("assignee", "priority", "type");

    /**
     * The lane a card is dropped in: the field of the lanes of the board (one of {@link #LANE_FIELDS}) and its value,
     * which for the assignee is null or blank for nobody.
     */
    public record Lane(String field, String value) {
    }

    /**
     * Moves the issue to one of {@code statuses} with the first transition of the workflow that leads there and can run
     * now for the user (ADR-15): a card dropped on another column of a board. Nothing changes when the issue is already
     * in one of them.
     *
     * <p>With a {@code lane} whose value is not the one of the issue, the card was dropped in another lane too: the
     * field changes with the rules of the form of the issue ({@link IssueEditor}: {@code odt.issue.edit}, a member as
     * assignee and not the reviewer, the custom fields a new type requires, its status in the workflow of the new type),
     * and the transition is the one of the issue as it is after the change. Both are one change and one event, or
     * nothing changes.
     */
    @Transactional
    public Move moveTo(String issueKey, Set<String> statuses, Lane lane) {
        Optional<Found> found = findEntity(issueKey);
        if (found.isEmpty()) {
            return new Move(Move.Outcome.NOT_FOUND, null);
        }
        ProjectRef project = found.get().project();
        IssueEntity issue = found.get().issue();
        Set<String> permissions = access.permissions(project.id(), user.username());
        IssueEditor.Edit edit = null;
        if (lane != null && !sameLane(issue, lane)) {
            if (!permissions.contains("odt.issue.edit")) {
                return new Move(Move.Outcome.NOT_EDITABLE, issue.status());
            }
            try {
                edit = editor.edit(project, issue, laneRequest(issue, lane), permissions);
            } catch (IssueEditor.InvalidField e) {
                return new Move(Move.Outcome.INVALID, issue.status(), e.getMessage());
            } catch (IssueEditor.InvalidCustom e) {
                return new Move(Move.Outcome.INVALID, issue.status(), IssueEditor.InvalidCustom.TITLE + ": "
                        + String.join(", ", e.errors.stream().map(FieldError::path).toList()));
            }
        }
        String status = edit == null ? issue.status() : edit.status();
        Optional<Transition> transition = Optional.empty();
        if (!statuses.contains(status)) {
            if (!permissions.contains(Workflows.TRANSITION)) {
                return new Move(Move.Outcome.NOT_PERMITTED, issue.status());
            }
            Map<String, Object> guardInput = edit == null ? IssueController.guardInput(found.get())
                    : edit.guardInput(project, issue);
            String type = edit == null ? issue.type().value() : edit.fields().type().value();
            transition = workflows.available(project.id(), type, status, guardInput, IssueController.userInput(user),
                    permissions).stream().filter(t -> statuses.contains(t.to())).findFirst();
            if (transition.isEmpty()) {
                return new Move(Move.Outcome.NO_TRANSITION, issue.status());
            }
        } else if (edit == null) {
            return new Move(Move.Outcome.ALREADY_THERE, issue.status());
        }
        String from = issue.status();
        Instant now = Instant.now();
        List<FieldChange> changes = new ArrayList<>();
        if (edit != null) {
            edit.changes().stream().filter(c -> !c.field().equals("status")).forEach(changes::add);
            editor.apply(issue, edit, now);
        }
        transition.ifPresent(t -> issue.moveTo(t.to(), now));
        if (!from.equals(issue.status())) {
            changes.add(new FieldChange("status", from, issue.status()));
        }
        try {
            issues.saveAndFlush(issue);
        } catch (ObjectOptimisticLockingFailureException e) {
            return new Move(Move.Outcome.CHANGED_MEANWHILE, from);
        }
        editor.watch(issue, changes, now);
        events.publishEvent(new IssueChanged(issue.id(), project.id(), IssueController.key(project, issue), user.username(),
                now, changes));
        return new Move(Move.Outcome.MOVED, issue.status());
    }

    /** Whether the issue is already in the lane: the same assignee, priority or type. */
    private static boolean sameLane(IssueEntity issue, Lane lane) {
        String value = IssueEditor.blankToNull(lane.value());
        return switch (lane.field()) {
            case "assignee" -> Objects.equals(issue.assignee(), value);
            case "priority" -> value != null && Priority.parse(value).map(p -> p == issue.priority()).orElse(false);
            case "type" -> value != null && IssueType.parse(value).map(t -> t == issue.type()).orElse(false);
            default -> true;
        };
    }

    /** The issue as it is with the field of the lane changed, as the form would send it. */
    private static IssueRequest laneRequest(IssueEntity issue, Lane lane) {
        IssueRequest now = IssueEditor.unchanged(issue);
        String value = IssueEditor.blankToNull(lane.value());
        String given = value == null ? "" : value;
        return switch (lane.field()) {
            case "assignee" -> new IssueRequest(now.type(), now.summary(), now.description(), now.priority(), value,
                    now.dueOn(), now.reviewer(), now.labels(), null, null, now.version());
            case "priority" -> new IssueRequest(now.type(), now.summary(), now.description(), given, now.assignee(),
                    now.dueOn(), now.reviewer(), now.labels(), null, null, now.version());
            default -> new IssueRequest(given, now.summary(), now.description(), now.priority(), now.assignee(),
                    now.dueOn(), now.reviewer(), now.labels(), null, null, now.version());
        };
    }

    record Found(ProjectRef project, IssueEntity issue) {
    }

    /** The issue with this key, or that had it before it moved: then with the project it is in now. */
    Optional<Found> findEntity(String issueKey) {
        Matcher m = MovedKeys.ISSUE_KEY.matcher(issueKey);
        if (!m.matches()) {
            return Optional.empty();
        }
        long number = Long.parseLong(m.group(2));
        return projects.find(m.group(1)).flatMap(p -> issues.findByProjectIdAndNumber(p.id(), number)
                .map(i -> new Found(p, i))
                .or(() -> moved.issueOf(p.id(), number).flatMap(issues::findById)
                        .flatMap(i -> projects.get(i.projectId()).map(now -> new Found(now, i)))));
    }

    private static IssueSummary summary(ProjectRef project, IssueEntity i) {
        return new IssueSummary(i.id(), IssueController.key(project, i), i.number(), i.type().value(), i.summary(), i.status(),
                i.priority().value(), i.assignee(), i.labels(), i.updatedAt(), i.flagged());
    }
}
