/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRow;
import com.logicwarez.opendevtrack.issue.IssueApi.LabelRow;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueView;
import com.logicwarez.opendevtrack.issue.IssueApi.TransitionRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.TransitionView;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import com.logicwarez.opendevtrack.issue.IssueEditor.Fields;
import com.logicwarez.opendevtrack.issue.IssueEditor.InvalidCustom;
import com.logicwarez.opendevtrack.issue.IssueEditor.InvalidField;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.workflow.Workflows;
import com.logicwarez.opendevtrack.workflow.Workflows.Decision;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues of the projects (E2), by the role of the user in the project (module access):
 *
 * <ul>
 *   <li>{@code odt.issue.browse} (viewer): list and read them;</li>
 *   <li>{@code odt.issue.create}, {@code odt.issue.edit} (developer): create them, change their fields;</li>
 *   <li>{@code odt.issue.transition} (developer): move them through the workflow of the project, when the guard of the
 *   transition is true (module workflow, ADR-10);</li>
 *   <li>{@code odt.issue.delete} (admin): delete them.</li>
 * </ul>
 * The status changes only through the transitions; the reporter, the assignee and the reviewer watch the issue
 * ({@link WatcherController}). An issue of a project of another tenant answers 404, as one that does not exist
 * (ADR-09). Every change is published as an event of {@link IssueEvents}.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class IssueController {

    private static final int MAX_SUGGESTED = 20;
    private static final int MAX_LIST = 500;
    private static final String NOT_FOUND = "Issue not found";
    private static final String NOT_A_MEMBER = "Only the members of the project see its issues";

    private final IssueRepository issues;
    private final Issues finder;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;
    private final Workflows workflows;
    private final WatcherRepository watchers;
    private final CustomFields customFields;
    private final IssueEditor editor;
    private final Clock clock;

    IssueController(IssueRepository issues, Issues finder, Projects projects, ProjectAccess access, CurrentUser user,
                    ApplicationEventPublisher events, Workflows workflows, WatcherRepository watchers,
                    CustomFields customFields, IssueEditor editor, @Value("${odt.reporting.zone:UTC}") String zone) {
        this.editor = editor;
        this.clock = Clock.system(ZoneId.of(zone));
        this.workflows = workflows;
        this.customFields = customFields;
        this.watchers = watchers;
        this.issues = issues;
        this.finder = finder;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.events = events;
    }

    /** Issues of the project, newest first; {@code assignee=me} for those of the user. */
    @ApiDoc(value = "The issues of a project, newest first (at most 500; the search pages them)",
            answer = IssueRow.class, list = true, project = "odt.issue.browse")
    @GetMapping("/api/projects/{key}/issues")
    ResponseEntity<?> list(@PathVariable String key, @RequestParam(required = false) String status,
                           @RequestParam(required = false) String assignee, @RequestParam(required = false) String q) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        if (!permissions(project.get()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        String who = "me".equals(assignee) ? user.username() : blankToNull(assignee);
        String text = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        Map<String, String> categories = new HashMap<>();
        workflows.statuses(project.get().id()).forEach(s -> categories.put(s.code(), s.category()));
        LocalDate today = LocalDate.now(clock);
        List<IssueRow> rows = issues.search(project.get().id(), blankToNull(status), who, text, Limit.of(MAX_LIST)).stream()
                .map(i -> new IssueRow(key(project.get(), i), i.number(), i.type().value(), i.summary(), i.status(),
                        i.priority().value(), i.assignee(), i.dueOn(),
                        overdue(i.dueOn(), categories.get(i.status()), today) ? "overdue" : null, i.labels(), i.updatedAt()))
                .toList();
        return ResponseEntity.ok(rows);
    }

    /**
     * The labels already used in the issues of the project that start with {@code q} (all without it), in alphabetical
     * order, at most 20: the suggestions of the labels field.
     */
    @ApiDoc(value = "The labels used in a project that start with q",
            answer = LabelRow.class, list = true, project = "odt.issue.browse", internal = true)
    @GetMapping("/api/projects/{key}/labels")
    ResponseEntity<?> labels(@PathVariable String key, @RequestParam(required = false) String q) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        if (!permissions(project.get()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        String prefix = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return ResponseEntity.ok(issues.labels(project.get().id(), prefix, MAX_SUGGESTED).stream().map(LabelRow::new).toList());
    }

    @ApiDoc(value = "Creates an issue", answer = IssueView.class, status = 201, project = "odt.issue.create")
    @PostMapping("/api/projects/{key}/issues")
    @Transactional
    ResponseEntity<?> create(@PathVariable String key, @RequestBody IssueRequest request) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        Set<String> permissions = permissions(project.get());
        if (!permissions.contains("odt.issue.create")) {
            return Problems.forbidden("Only a developer or an admin of the project creates issues");
        }
        Fields fields;
        String initial;
        try {
            fields = editor.fields(project.get(), request, Fields.EMPTY);
            initial = workflows.initialStatus(project.get().id(), fields.type().value());
            editor.checkRequired(project.get(), null, fields, initial, user.username(), permissions);
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        } catch (InvalidCustom e) {
            return Problems.invalidFields(InvalidCustom.TITLE, e.errors);
        }
        Instant now = Instant.now();
        IssueEntity issue = new IssueEntity(user.tenantId(), project.get().id(), projects.nextIssueNumber(project.get().id()),
                initial, user.username(), now);
        fields.applyTo(issue, now);
        issues.saveAndFlush(issue);
        watchers.watch(issue.id(), issue.reporter(), now);
        editor.watchAssignee(issue, now);
        editor.watchReviewer(issue, now);
        events.publishEvent(new IssueCreated(issue.id(), project.get().id(), key(project.get(), issue), user.username(), now,
                Fields.of(issue).changesFrom(Fields.EMPTY)));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(project.get(), issue, permissions));
    }

    @ApiDoc(value = "An issue", answer = IssueView.class, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}")
    ResponseEntity<?> get(@PathVariable String issueKey) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get().project());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        return ResponseEntity.ok(view(found.get().project(), found.get().issue(), permissions));
    }

    /** Changes the fields, from the version read with the issue, or 412 if someone saved it in the meantime. */
    @ApiDoc(value = "Changes the fields of an issue", answer = IssueView.class, project = "odt.issue.edit")
    @PutMapping("/api/issues/{issueKey}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable String issueKey, @RequestBody IssueRequest request) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectRef project = found.get().project();
        IssueEntity issue = found.get().issue();
        Set<String> permissions = permissions(project);
        if (!permissions.contains("odt.issue.edit")) {
            return Problems.forbidden("Only a developer or an admin of the project edits issues");
        }
        IssueEditor.Edit edit;
        try {
            edit = editor.edit(project, issue, request, permissions);
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        } catch (InvalidCustom e) {
            return Problems.invalidFields(InvalidCustom.TITLE, e.errors);
        }
        if (request.version() == null || request.version() != issue.version()) {
            return Problems.changedMeanwhile();
        }
        List<FieldChange> changes = edit.changes();
        if (changes.isEmpty()) {
            return ResponseEntity.ok(view(project, issue, permissions));
        }
        Instant now = Instant.now();
        editor.apply(issue, edit, now);
        try {
            issues.saveAndFlush(issue);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        editor.watch(issue, changes, now);
        events.publishEvent(new IssueChanged(issue.id(), project.id(), key(project, issue), user.username(), now, changes));
        return ResponseEntity.ok(view(project, issue, permissions));
    }

    @ApiDoc(value = "Deletes an issue", status = 204, project = "odt.issue.delete")
    @DeleteMapping("/api/issues/{issueKey}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable String issueKey) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(found.get().project()).contains("odt.issue.delete")) {
            return Problems.forbidden("Only an admin of the project deletes issues");
        }
        issues.delete(found.get().issue());
        events.publishEvent(new IssueDeleted(found.get().issue().id(), found.get().project().id(),
                key(found.get().project(), found.get().issue()), user.username(), Instant.now()));
        return ResponseEntity.noContent().build();
    }

    /** The custom fields of the issue for {@code fieldsFrom}, required as their rules say for the issue as it is. */
    @ApiDoc(value = "The custom fields of an issue, as the fields of a form",
            answer = CustomFields.FieldRow.class, list = true, project = "odt.issue.browse", internal = true)
    @GetMapping("/api/issues/{issueKey}/fields")
    ResponseEntity<?> customFields(@PathVariable String issueKey) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get().project());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        return ResponseEntity.ok(customFields.rows(found.get().project().id(), guardInput(found.get()), userInput(),
                permissions));
    }

    /** The transitions that can run now on the issue, for the user (empty without {@code odt.issue.transition}). */
    @ApiDoc(value = "The transitions the user may take from the status of an issue",
            answer = TransitionView.class, list = true, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/transitions")
    ResponseEntity<?> transitions(@PathVariable String issueKey) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get().project());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        IssueEntity issue = found.get().issue();
        return ResponseEntity.ok(workflows.available(issue.projectId(), issue.type().value(), issue.status(),
                guardInput(found.get()), userInput(), permissions).stream()
                .map(t -> new TransitionView(t.code(), t.to(), issue.version())).toList());
    }

    /** Runs a transition, from the version read with the issue: 409 when it cannot run now, 412 if the issue changed. */
    @ApiDoc(value = "Takes a transition of the workflow", answer = IssueView.class, project = "odt.issue.transition")
    @PostMapping("/api/issues/{issueKey}/transitions")
    @Transactional
    ResponseEntity<?> transition(@PathVariable String issueKey, @RequestBody TransitionRequest request) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectRef project = found.get().project();
        IssueEntity issue = found.get().issue();
        Set<String> permissions = permissions(project);
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        Decision decision = workflows.decide(issue.projectId(), issue.type().value(),
                request.transition() == null ? "" : request.transition(), issue.status(), guardInput(found.get()),
                userInput(), permissions);
        switch (decision.outcome()) {
            case UNKNOWN -> {
                return Problems.badRequest("No transition with this name in the workflow of the project");
            }
            case NOT_PERMITTED -> {
                return Problems.forbidden("Only a developer or an admin of the project moves issues");
            }
            case NOT_FROM_HERE -> {
                return Problems.conflict("The transition does not start from the status of the issue");
            }
            case GUARD_FALSE -> {
                return Problems.conflict("The conditions of the transition are not met");
            }
            case ALLOWED -> {
                // Below.
            }
        }
        if (request.version() == null || request.version() != issue.version()) {
            return Problems.changedMeanwhile();
        }
        String from = issue.status();
        Instant now = Instant.now();
        issue.moveTo(decision.transition().to(), now);
        try {
            issues.saveAndFlush(issue);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        events.publishEvent(new IssueChanged(issue.id(), project.id(), key(project, issue), user.username(), now,
                List.of(new FieldChange("status", from, issue.status()))));
        return ResponseEntity.ok(view(project, issue, permissions));
    }

    /** Past its due date and not done (ADR-19): the day is in the zone of the reports. */
    static boolean overdue(LocalDate dueOn, String category, LocalDate today) {
        return dueOn != null && dueOn.isBefore(today) && !"done".equals(category);
    }

    /** The issue as the guards see it ({@code issue.assignee}, {@code issue.custom.customer}…). */
    static Map<String, Object> guardInput(Found found) {
        IssueEntity i = found.issue();
        return IssueEditor.conditionInput(found.project(), key(found.project(), i), Fields.of(i), i.status(), i.reporter());
    }

    private Map<String, Object> userInput() {
        return userInput(user);
    }

    /** The user as the conditions see it ({@code user.username}, {@code user.roles}). */
    static Map<String, Object> userInput(CurrentUser user) {
        return Map.of("username", user.username(), "roles", List.copyOf(user.session().roles()));
    }

    private Optional<Found> find(String issueKey) {
        return finder.findEntity(issueKey);
    }

    private Set<String> permissions(ProjectRef project) {
        return access.permissions(project.id(), user.username());
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    static String key(ProjectRef project, IssueEntity issue) {
        return project.key() + "-" + issue.number();
    }

    private IssueView view(ProjectRef project, IssueEntity i, Set<String> permissions) {
        Map<String, Boolean> perm = Map.of(
                "edit", permissions.contains("odt.issue.edit"),
                "delete", permissions.contains("odt.issue.delete"),
                "transition", permissions.contains("odt.issue.transition"),
                "comment", permissions.contains("odt.issue.comment"),
                "attach", permissions.contains("odt.issue.attach"));
        return new IssueView(key(project, i), project.key(), i.number(), i.type().value(), i.summary(), i.description(),
                i.status(), i.priority().value(), i.assignee(), i.reporter(), i.dueOn(), i.reviewer(), i.labels(), i.custom(),
                i.createdAt(), i.updatedAt(), i.version(), watchers.existsByIssueIdAndUsername(i.id(), user.username()), perm);
    }
}
