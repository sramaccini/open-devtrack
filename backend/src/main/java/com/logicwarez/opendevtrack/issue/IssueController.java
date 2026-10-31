/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRow;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueView;
import com.logicwarez.opendevtrack.issue.IssueApi.TransitionRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.TransitionView;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.workflow.Workflows;
import com.logicwarez.opendevtrack.workflow.Workflows.Decision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
 * The status changes only through the transitions. An issue of a project of another tenant
 * answers 404, as one that does not exist (ADR-09). Every change is published as an event of {@link IssueEvents}.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class IssueController {

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,49}$");
    private static final int MAX_LABELS = 20;
    private static final int MAX_DESCRIPTION = 32_000;
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

    IssueController(IssueRepository issues, Issues finder, Projects projects, ProjectAccess access, CurrentUser user,
                    ApplicationEventPublisher events, Workflows workflows) {
        this.workflows = workflows;
        this.issues = issues;
        this.finder = finder;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.events = events;
    }

    /** Issues of the project, newest first; {@code assignee=me} for those of the user. */
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
        List<IssueRow> rows = issues.search(project.get().id(), blankToNull(status), who, text, Limit.of(MAX_LIST)).stream()
                .map(i -> new IssueRow(key(project.get(), i), i.number(), i.type().value(), i.summary(), i.status(),
                        i.priority().value(), i.assignee(), i.updatedAt()))
                .toList();
        return ResponseEntity.ok(rows);
    }

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
        try {
            fields = fields(project.get(), request);
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        }
        Instant now = Instant.now();
        IssueEntity issue = new IssueEntity(user.tenantId(), project.get().id(), projects.nextIssueNumber(project.get().id()),
                workflows.initialStatus(project.get().id()), user.username(), now);
        fields.applyTo(issue, now);
        issues.saveAndFlush(issue);
        events.publishEvent(new IssueCreated(issue.id(), project.get().id(), key(project.get(), issue), user.username(), now,
                Fields.of(issue).changesFrom(Fields.EMPTY)));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(project.get(), issue, permissions));
    }

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
        Fields fields;
        try {
            fields = fields(project, request);
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        }
        if (request.version() == null || request.version() != issue.version()) {
            return Problems.changedMeanwhile();
        }
        List<FieldChange> changes = fields.changesFrom(Fields.of(issue));
        if (changes.isEmpty()) {
            return ResponseEntity.ok(view(project, issue, permissions));
        }
        Instant now = Instant.now();
        fields.applyTo(issue, now);
        try {
            issues.saveAndFlush(issue);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        events.publishEvent(new IssueChanged(issue.id(), project.id(), key(project, issue), user.username(), now, changes));
        return ResponseEntity.ok(view(project, issue, permissions));
    }

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

    /** The transitions that can run now on the issue, for the user (empty without {@code odt.issue.transition}). */
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
        return ResponseEntity.ok(workflows.available(issue.projectId(), issue.status(), guardInput(found.get()), userInput(),
                permissions).stream().map(t -> new TransitionView(t.code(), t.to(), issue.version())).toList());
    }

    /** Runs a transition, from the version read with the issue: 409 when it cannot run now, 412 if the issue changed. */
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
        Decision decision = workflows.decide(issue.projectId(), request.transition() == null ? "" : request.transition(),
                issue.status(), guardInput(found.get()), userInput(), permissions);
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

    /** The issue as the guards see it ({@code issue.assignee}, {@code issue.labels}…); workflow adds the status. */
    private static Map<String, Object> guardInput(Found found) {
        IssueEntity i = found.issue();
        Map<String, Object> issue = new HashMap<>();
        issue.put("key", key(found.project(), i));
        issue.put("project", found.project().key());
        issue.put("type", i.type().value());
        issue.put("summary", i.summary());
        issue.put("description", i.description());
        issue.put("priority", i.priority().value());
        issue.put("assignee", i.assignee());
        issue.put("reporter", i.reporter());
        issue.put("labels", i.labels() == null ? List.of() : i.labels());
        return issue;
    }

    private Map<String, Object> userInput() {
        return Map.of("username", user.username(), "roles", List.copyOf(user.session().roles()));
    }

    private Optional<Found> find(String issueKey) {
        return finder.findEntity(issueKey);
    }

    private Set<String> permissions(ProjectRef project) {
        return access.permissions(project.id(), user.username());
    }

    /** The fields of a request, checked; type and priority default to task and medium. */
    private record Fields(IssueType type, String summary, String description, Priority priority, String assignee,
                          List<String> labels) {

        void applyTo(IssueEntity issue, Instant now) {
            issue.edit(type, summary, description, priority, assignee, labels, now);
        }

        static final Fields EMPTY = new Fields(null, null, null, null, null, List.of());

        static Fields of(IssueEntity issue) {
            return new Fields(issue.type(), issue.summary(), issue.description(), issue.priority(), issue.assignee(),
                    issue.labels());
        }

        /** The fields that differ from {@code before}, in the order of the form. */
        List<FieldChange> changesFrom(Fields before) {
            List<FieldChange> changes = new ArrayList<>();
            change(changes, "type", before.type == null ? null : before.type.value(), type == null ? null : type.value());
            change(changes, "summary", before.summary, summary);
            change(changes, "priority", before.priority == null ? null : before.priority.value(),
                    priority == null ? null : priority.value());
            change(changes, "assignee", before.assignee, assignee);
            change(changes, "labels", text(before.labels), text(labels));
            change(changes, "description", before.description, description);
            return changes;
        }

        private static void change(List<FieldChange> changes, String field, String from, String to) {
            if (!Objects.equals(from, to)) {
                changes.add(new FieldChange(field, from, to));
            }
        }

        private static String text(List<String> labels) {
            return labels == null || labels.isEmpty() ? null : String.join(", ", labels);
        }
    }

    private static final class InvalidField extends Exception {
        InvalidField(String message) {
            super(message);
        }
    }

    private Fields fields(ProjectRef project, IssueRequest request) throws InvalidField {
        IssueType type = request.type() == null ? IssueType.TASK : IssueType.parse(request.type())
                .orElseThrow(() -> new InvalidField("The type is one of " + String.join(", ", IssueType.names())));
        Priority priority = request.priority() == null ? Priority.MEDIUM : Priority.parse(request.priority())
                .orElseThrow(() -> new InvalidField("The priority is one of " + String.join(", ", Priority.names())));
        if (request.summary() == null || request.summary().isBlank() || request.summary().strip().length() > 255) {
            throw new InvalidField("The summary is required, at most 255 characters");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION) {
            throw new InvalidField("The description is at most " + MAX_DESCRIPTION + " characters");
        }
        String assignee = blankToNull(request.assignee());
        if (assignee != null && access.role(project.id(), assignee).isEmpty()) {
            throw new InvalidField("The assignee must be a member of the project");
        }
        return new Fields(type, request.summary().strip(), blankToNull(request.description()), priority, assignee,
                labels(request.labels()));
    }

    /** Labels in lower case, without repetitions, in the order given. */
    private static List<String> labels(List<String> given) throws InvalidField {
        if (given == null) {
            return List.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (String label : given) {
            String l = label == null ? "" : label.strip().toLowerCase(Locale.ROOT);
            if (!LABEL.matcher(l).matches()) {
                throw new InvalidField("A label is 1 to 50 letters, digits, dots, dashes or underscores, without spaces");
            }
            labels.add(l);
        }
        if (labels.size() > MAX_LABELS) {
            throw new InvalidField("At most " + MAX_LABELS + " labels");
        }
        return new ArrayList<>(labels);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String key(ProjectRef project, IssueEntity issue) {
        return project.key() + "-" + issue.number();
    }

    private IssueView view(ProjectRef project, IssueEntity i, Set<String> permissions) {
        Map<String, Boolean> perm = Map.of(
                "edit", permissions.contains("odt.issue.edit"),
                "delete", permissions.contains("odt.issue.delete"),
                "transition", permissions.contains("odt.issue.transition"),
                "comment", permissions.contains("odt.issue.comment"));
        return new IssueView(key(project, i), project.key(), i.number(), i.type().value(), i.summary(), i.description(),
                i.status(), i.priority().value(), i.assignee(), i.reporter(), i.labels(), i.createdAt(), i.updatedAt(),
                i.version(), perm);
    }
}
