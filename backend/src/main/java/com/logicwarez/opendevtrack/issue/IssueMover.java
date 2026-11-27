/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueView;
import com.logicwarez.opendevtrack.issue.IssueApi.ProjectMoveAnswer;
import com.logicwarez.opendevtrack.issue.IssueApi.ProjectMoveRequest;
import com.logicwarez.opendevtrack.issue.IssueEditor.Fields;
import com.logicwarez.opendevtrack.issue.IssueEditor.InvalidCustom;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueMoved;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.platform.Problems.FieldError;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Moves an issue to another project of the tenant (ADR-27), as Jira's «Move»: with {@code odt.issue.move} in the
 * source and {@code odt.issue.create} in the target. The issue keeps its id, and with it its comments, attachments,
 * history, links, watchers, votes, labels, flag and points; it takes the next number of the target, and its old key
 * keeps leading to it ({@link MovedKeys}). What does not fit in the target is never dropped silently: the custom fields
 * that the target does not have with the same key and type (or whose value it would refuse) must be listed in
 * {@code fields.drop}, and the assignee and the reviewer who are not members of the target are cleared and named in the
 * answer. A dry run tells all of it without moving.
 */
@Component
class IssueMover {

    static final String MOVE = "odt.issue.move";
    /** The code of a value that the target cannot keep and that the request did not list in {@code fields.drop}. */
    static final String DROPPED = "dropped";

    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Workflows workflows;
    private final CustomFields customFields;
    private final IssueEditor editor;
    private final IssueRepository issues;
    private final MovedKeys moved;
    private final ApplicationEventPublisher events;
    private final ResourceBundleMessageSource texts = new ResourceBundleMessageSource();

    IssueMover(Projects projects, ProjectAccess access, CurrentUser user, Workflows workflows, CustomFields customFields,
               IssueEditor editor, IssueRepository issues, MovedKeys moved, ApplicationEventPublisher events) {
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.workflows = workflows;
        this.customFields = customFields;
        this.editor = editor;
        this.issues = issues;
        this.moved = moved;
        this.events = events;
        texts.setBasename("issue/export");
        texts.setDefaultEncoding("UTF-8");
        texts.setFallbackToSystemLocale(false);
    }

    /**
     * The move of {@code found}, already seen by the user: the answer with the issue as {@code view} draws it in the
     * target, or a Problem.
     */
    ResponseEntity<?> move(Found found, ProjectMoveRequest request, BiFunction<ProjectRef, IssueEntity, IssueView> view) {
        ProjectRef source = found.project();
        IssueEntity issue = found.issue();
        if (!access.permissions(source.id(), user.username()).contains(MOVE)) {
            return Problems.forbidden("Only an admin of the project moves its issues to another project");
        }
        if (request == null || request.project() == null || request.project().isBlank()) {
            return Problems.badRequest("The target project is required");
        }
        Optional<ProjectRef> found2 = projects.find(request.project().strip().toUpperCase(Locale.ROOT));
        Set<String> there = found2.map(p -> access.permissions(p.id(), user.username())).orElse(Set.of());
        if (found2.isEmpty() || !there.contains("odt.issue.browse")) {
            return Problems.notFound("Project not found");
        }
        ProjectRef target = found2.get();
        if (target.id().equals(source.id())) {
            return Problems.badRequest("The issue is already in this project");
        }
        if (!there.contains("odt.issue.create")) {
            return Problems.forbidden("Only a developer or an admin of the target project creates issues there");
        }
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        if (!dryRun && (request.version() == null || request.version() != issue.version())) {
            return Problems.changedMeanwhile();
        }
        IssueType type = issue.type();
        if (request.type() != null && !request.type().isBlank()) {
            Optional<IssueType> given = IssueType.parse(request.type());
            if (given.isEmpty()) {
                return Problems.badRequest("The type is one of " + String.join(", ", IssueType.names()));
            }
            type = given.get();
        }
        String status;
        if (request.status() != null && !request.status().isBlank()) {
            List<String> codes = workflows.statuses(target.id(), type.value()).stream().map(Workflows.Status::code).toList();
            if (!codes.contains(request.status())) {
                return Problems.badRequest("The status is one of the workflow of " + target.key() + " for the type: "
                        + String.join(", ", codes));
            }
            status = request.status();
        } else {
            status = workflows.statusFor(source.id(), issue.status(), target.id(), type.value());
        }

        Map<String, FieldDefinitionEntity> sourceFields = byKey(source);
        Map<String, FieldDefinitionEntity> targetFields = byKey(target);
        Map<String, Object> kept = new LinkedHashMap<>();
        List<String> lost = new ArrayList<>();
        issue.custom().forEach((key, value) -> {
            FieldDefinitionEntity from = sourceFields.get(key);
            FieldDefinitionEntity to = targetFields.get(key);
            CustomFields.Checked checked = to == null || to.archived() || from == null || from.type() != to.type()
                    ? null : customFields.check(target.id(), Map.of(key, value), Map.of());
            if (checked != null && checked.errors().isEmpty() && checked.values().containsKey(key)) {
                kept.put(key, checked.values().get(key));
            } else {
                lost.add(key);
            }
        });
        String assignee = member(target, issue.assignee());
        String reviewer = member(target, issue.reviewer());
        List<String> cleared = new ArrayList<>();
        if (!Objects.equals(assignee, issue.assignee())) {
            cleared.add("assignee");
        }
        if (!Objects.equals(reviewer, issue.reviewer())) {
            cleared.add("reviewer");
        }
        String lostText = lostText(issue, lost, cleared, sourceFields);
        String fromKey = IssueController.key(source, issue);
        if (dryRun) {
            return ResponseEntity.ok(new ProjectMoveAnswer(null, fromKey, lost, cleared, lostText, null));
        }

        Set<String> drop = request.fields() == null || request.fields().drop() == null ? Set.of()
                : Set.copyOf(request.fields().drop());
        List<FieldError> undeclared = lost.stream().filter(k -> !drop.contains(k))
                .map(k -> new FieldError("/custom/" + k, DROPPED)).toList();
        if (!undeclared.isEmpty()) {
            return Problems.invalidFields("Some values cannot be kept in " + target.key()
                    + ": list them in fields.drop to move the issue without them", undeclared);
        }
        Fields fields = new Fields(type, issue.summary(), issue.description(), issue.priority(), assignee, issue.dueOn(),
                reviewer, issue.labels(), kept);
        try {
            editor.checkRequired(target, null, fields, status, issue.reporter(), there);
        } catch (InvalidCustom e) {
            return Problems.invalidFields(InvalidCustom.TITLE, e.errors);
        }

        Instant now = Instant.now();
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("project", source.key(), target.key()));
        long number = projects.nextIssueNumber(target.id());
        String toKey = target.key() + "-" + number;
        changes.add(new FieldChange("key", fromKey, toKey));
        if (type != issue.type()) {
            changes.add(new FieldChange("type", issue.type().value(), type.value()));
        }
        if (!status.equals(issue.status())) {
            changes.add(new FieldChange("status", issue.status(), status));
        }
        if (cleared.contains("assignee")) {
            changes.add(new FieldChange("assignee", issue.assignee(), null));
        }
        if (cleared.contains("reviewer")) {
            changes.add(new FieldChange("reviewer", issue.reviewer(), null));
        }
        lost.forEach(k -> changes.add(new FieldChange("custom." + k, CustomFields.text(issue.custom().get(k)), null)));
        moved.add(source.tenantId(), source.id(), issue.number(), issue.id(), now);
        issue.movedTo(target.id(), number, type, status, assignee, reviewer, kept, now);
        try {
            issues.saveAndFlush(issue);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        events.publishEvent(new IssueMoved(issue.id(), source.id(), target.id(), fromKey, toKey, user.username(), now,
                changes));
        return ResponseEntity.ok(new ProjectMoveAnswer(toKey, fromKey, lost, cleared, lostText, view.apply(target, issue)));
    }

    private Map<String, FieldDefinitionEntity> byKey(ProjectRef project) {
        return customFields.all(project.id()).stream()
                .collect(Collectors.toMap(FieldDefinitionEntity::key, Function.identity()));
    }

    /** The user when a member of the project, else nobody. */
    private String member(ProjectRef project, String username) {
        return username == null || access.role(project.id(), username).isEmpty() ? null : username;
    }

    /** What the move loses, for the confirm of the screen: the names of the fields, and the people cleared. */
    private String lostText(IssueEntity issue, List<String> lost, List<String> cleared,
                            Map<String, FieldDefinitionEntity> fields) {
        Locale locale = LocaleContextHolder.getLocale();
        List<String> parts = new ArrayList<>();
        lost.forEach(k -> parts.add(Optional.ofNullable(fields.get(k)).map(FieldDefinitionEntity::label).orElse(k)));
        if (cleared.contains("assignee")) {
            parts.add(texts.getMessage("field.assignee", null, locale) + " (" + issue.assignee() + ")");
        }
        if (cleared.contains("reviewer")) {
            parts.add(texts.getMessage("field.reviewer", null, locale) + " (" + issue.reviewer() + ")");
        }
        return String.join(", ", parts);
    }
}
