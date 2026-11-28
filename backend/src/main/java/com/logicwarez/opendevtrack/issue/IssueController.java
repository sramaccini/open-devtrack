/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.ChildRow;
import com.logicwarez.opendevtrack.issue.IssueApi.Children;
import com.logicwarez.opendevtrack.issue.IssueApi.CloneRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.IssuePatch;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRow;
import com.logicwarez.opendevtrack.issue.IssueApi.LabelRow;
import com.logicwarez.opendevtrack.issue.IssueApi.ProjectMoveAnswer;
import com.logicwarez.opendevtrack.issue.IssueApi.ProjectMoveRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueView;
import com.logicwarez.opendevtrack.issue.IssueApi.ParentCandidate;
import com.logicwarez.opendevtrack.issue.IssueApi.ParentView;
import com.logicwarez.opendevtrack.issue.IssueApi.TransitionRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.TransitionView;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueWorkflowStatus;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueWorkflowTransition;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueWorkflow;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import com.logicwarez.opendevtrack.issue.IssueEditor.Fields;
import com.logicwarez.opendevtrack.issue.IssueEditor.InvalidCustom;
import com.logicwarez.opendevtrack.issue.IssueEditor.InvalidField;
import com.logicwarez.opendevtrack.issue.IssueEditor.Parent;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
 *   <li>{@code odt.issue.delete} (admin): delete them;</li>
 *   <li>{@code odt.reporter.edit} (admin): change their reporter;</li>
 *   <li>{@code odt.issue.move} (admin): move them to another project, with {@code odt.issue.create} there (ADR-27).</li>
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
    private static final String NOT_AN_EDITOR = "Only a developer or an admin of the project edits issues";
    private static final String NOT_A_CREATOR = "Only a developer or an admin of the project creates issues";
    private static final String REPORTER = "odt.reporter.edit";
    private static final String CLONE_PREFIX = "CLONE - ";
    private static final int MAX_SUMMARY = 255;

    private final IssueRepository issues;
    private final IssueLinkRepository links;
    private final Issues finder;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;
    private final Workflows workflows;
    private final WatcherRepository watchers;
    private final VoteRepository votes;
    private final CustomFields customFields;
    private final IssueEditor editor;
    private final IssueMover mover;
    private final Clock clock;
    private final LinkTypes linkTypes;

    IssueController(IssueRepository issues, IssueLinkRepository links, Issues finder, Projects projects, ProjectAccess access, CurrentUser user,
                    ApplicationEventPublisher events, Workflows workflows, WatcherRepository watchers,
                    VoteRepository votes, CustomFields customFields, IssueEditor editor, IssueMover mover,
                    LinkTypes linkTypes, @Value("${odt.reporting.zone:UTC}") String zone) {
        this.linkTypes = linkTypes;
        this.editor = editor;
        this.mover = mover;
        this.clock = Clock.system(ZoneId.of(zone));
        this.workflows = workflows;
        this.customFields = customFields;
        this.watchers = watchers;
        this.votes = votes;
        this.issues = issues;
        this.links = links;
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
                        overdue(i.dueOn(), categories.get(i.status()), today) ? "overdue" : null, i.labels(), i.updatedAt(),
                        i.flagged()))
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
            return Problems.forbidden(NOT_A_CREATOR);
        }
        IssueEntity issue;
        try {
            issue = newIssue(project.get(), request, permissions, Instant.now());
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        } catch (InvalidCustom e) {
            return Problems.invalidFields(InvalidCustom.TITLE, e.errors);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(view(project.get(), issue, permissions));
    }

    /**
     * A copy of an issue in its project, as Jira clones: the summary given (else «CLONE - » and the original's), the
     * type, priority, assignee, reviewer, due date, description, labels and custom fields. The user is its reporter, the
     * status the initial one, and it is linked to the original as «clones»; comments, attachments, watchers, votes, the
     * flag and the plan stay with the original.
     */
    @ApiDoc(value = "Copies an issue into a new one of the same project, linked as a clone",
            answer = IssueView.class, status = 201, project = "odt.issue.create")
    @PostMapping("/api/issues/{issueKey}/clone")
    @Transactional
    ResponseEntity<?> cloneIssue(@PathVariable String issueKey, @RequestBody(required = false) CloneRequest request) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectRef project = found.get().project();
        Set<String> permissions = permissions(project);
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        if (!permissions.contains("odt.issue.create")) {
            return Problems.forbidden(NOT_A_CREATOR);
        }
        IssueEntity original = found.get().issue();
        String summary = request == null || request.summary() == null ? cloneSummary(original.summary()) : request.summary();
        IssueRequest fields = new IssueRequest(original.type().value(), summary, original.description(),
                original.priority().value(), original.assignee(),
                original.dueOn() == null ? null : original.dueOn().toString(),
                original.startOn() == null ? null : original.startOn().toString(), original.reviewer(), original.labels(),
                original.custom(), editor.durations(original.originalEstimate()), null,
                editor.parentOf(project, original).key(), null);
        Instant now = Instant.now();
        IssueEntity clone;
        try {
            clone = newIssue(project, fields, permissions, now);
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        } catch (InvalidCustom e) {
            return Problems.invalidFields(InvalidCustom.TITLE, e.errors);
        }
        // clones is a system type (ADR-29): never removed, made with the others if the tenant has none yet.
        LinkTypeEntity clones = linkTypes.find(original.tenantId(), LinkTypeDefaults.CLONES).orElseThrow();
        links.saveAndFlush(new IssueLinkEntity(clone.id(), original.id(), clones.code(), user.username(), now));
        String cloneKey = key(project, clone);
        String originalKey = key(project, original);
        events.publishEvent(new IssueChanged(clone.id(), project.id(), cloneKey, user.username(), now,
                List.of(new FieldChange("links", null, LinkTypes.relation(clones, true) + " " + originalKey))));
        events.publishEvent(new IssueChanged(original.id(), project.id(), originalKey, user.username(), now,
                List.of(new FieldChange("links", null, LinkTypes.relation(clones, false) + " " + cloneKey))));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(project, clone, permissions));
    }

    /**
     * Moves the issue to another project of the tenant (ADR-27, Jira's «Move»): see {@link IssueMover}. With
     * {@code dryRun} nothing changes and the answer says what the move would drop and clear.
     */
    @ApiDoc(value = "Moves an issue to another project, with a new key; the old one keeps leading to it",
            answer = ProjectMoveAnswer.class, project = "odt.issue.move")
    @PostMapping("/api/issues/{issueKey}/move")
    @Transactional
    ResponseEntity<?> move(@PathVariable String issueKey, @RequestBody ProjectMoveRequest request) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(found.get().project()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        return mover.move(found.get(), request, (project, issue) -> view(project, issue, permissions(project)));
    }

    /** «CLONE - » and the summary, within the length of a summary. */
    static String cloneSummary(String summary) {
        String text = CLONE_PREFIX + summary;
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY);
    }

    /** A new issue of the project with the fields of the request, checked, in the initial status of its workflow. */
    private IssueEntity newIssue(ProjectRef project, IssueRequest request, Set<String> permissions, Instant now)
            throws InvalidField, InvalidCustom {
        Fields fields = editor.fields(project, request, Fields.EMPTY);
        Parent parent = editor.parent(project, null, fields.type(), request.parent(), Parent.NONE);
        String initial = workflows.initialStatus(project.id(), fields.type().value());
        editor.checkRequired(project, null, fields, initial, user.username(), permissions);
        IssueEntity issue = new IssueEntity(user.tenantId(), project.id(), projects.nextIssueNumber(project.id()),
                initial, user.username(), now);
        fields.applyTo(issue, now);
        if (parent.id() != null) {
            issue.parentTo(parent.id(), now);
        }
        issues.saveAndFlush(issue);
        watchers.watch(issue.id(), issue.reporter(), now);
        editor.watchAssignee(issue, now);
        editor.watchReviewer(issue, now);
        List<FieldChange> created = new ArrayList<>(Fields.of(issue).changesFrom(Fields.EMPTY, editor.durations()));
        if (parent.key() != null) {
            created.add(new FieldChange("parent", null, parent.key()));
        }
        events.publishEvent(new IssueCreated(issue.id(), project.id(), key(project, issue), user.username(), now,
                created));
        return issue;
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
        Set<String> permissions = permissions(found.get().project());
        if (!permissions.contains("odt.issue.edit")) {
            return Problems.forbidden(NOT_AN_EDITOR);
        }
        return save(found.get(), request, permissions, null);
    }

    /**
     * Changes only the fields named in the body, from the version read with the issue, or 412 if someone saved it in
     * the meantime: the other fields keep their values, and the custom fields given leave the others as they are. The
     * checks are those of the full change. The reporter changes only here, with {@code odt.reporter.edit} (admin, as
     * in Jira), to a member of the project.
     */
    @ApiDoc(value = "Changes some fields of an issue", answer = IssueView.class, project = "odt.issue.edit")
    @PatchMapping("/api/issues/{issueKey}")
    @Transactional
    ResponseEntity<?> patch(@PathVariable String issueKey, @RequestBody IssuePatch body) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get().project());
        if (!permissions.contains("odt.issue.edit")) {
            return Problems.forbidden(NOT_AN_EDITOR);
        }
        String reporter = null;
        if (body.has("reporter")) {
            if (!permissions.contains(REPORTER)) {
                return Problems.forbidden("Only an admin of the project changes the reporter");
            }
            reporter = blankToNull(body.getReporter());
            if (reporter == null) {
                return Problems.badRequest("The reporter is required");
            }
            if (access.role(found.get().project().id(), reporter).isEmpty()) {
                return Problems.badRequest("The reporter must be a member of the project");
            }
        }
        IssueRequest request;
        try {
            request = patched(found.get().issue(), body);
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        }
        return save(found.get(), request, permissions, reporter);
    }

    /** The issue as it is with the fields of a PATCH on top, as the request of a full change. */
    private IssueRequest patched(IssueEntity issue, IssuePatch patch) throws InvalidField {
        if (patch.has("type") && patch.getType() == null) {
            throw new InvalidField("The type is required");
        }
        if (patch.has("priority") && patch.getPriority() == null) {
            throw new InvalidField("The priority is required");
        }
        Map<String, Object> custom = null;
        if (patch.has("custom")) {
            custom = new LinkedHashMap<>(issue.custom());
            if (patch.getCustom() != null) {
                custom.putAll(patch.getCustom());
            }
        }
        List<String> labels = issue.labels();
        if (patch.has("labels")) {
            labels = patch.getLabels() == null ? List.of() : patch.getLabels();
        }
        return new IssueRequest(
                patch.has("type") ? patch.getType() : issue.type().value(),
                patch.has("summary") ? patch.getSummary() : issue.summary(),
                patch.has("description") ? patch.getDescription() : issue.description(),
                patch.has("priority") ? patch.getPriority() : issue.priority().value(),
                patch.has("assignee") ? patch.getAssignee() : issue.assignee(),
                patch.has("dueOn") ? patch.getDueOn() : issue.dueOn() == null ? null : issue.dueOn().toString(),
                patch.has("startOn") ? patch.getStartOn() : issue.startOn() == null ? null : issue.startOn().toString(),
                patch.has("reviewer") ? patch.getReviewer() : issue.reviewer(),
                labels,
                custom,
                patch.has("originalEstimate") ? patch.getOriginalEstimate() : editor.durations(issue.originalEstimate()),
                patch.has("remainingEstimate") ? patch.getRemainingEstimate() : editor.durations(issue.remainingEstimate()),
                patch.has("parent") ? (patch.getParent() == null ? "" : patch.getParent()) : null,
                patch.getVersion());
    }

    /**
     * Checks a change against the version read with the issue and saves it, with its changelog and its event; a
     * {@code reporter} not null is the new reporter, already checked.
     */
    private ResponseEntity<?> save(Found found, IssueRequest request, Set<String> permissions, String reporter) {
        ProjectRef project = found.project();
        IssueEntity issue = found.issue();
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
        List<FieldChange> changes = new ArrayList<>(edit.changes());
        boolean newReporter = reporter != null && !reporter.equals(issue.reporter());
        if (newReporter) {
            changes.add(new FieldChange("reporter", issue.reporter(), reporter));
        }
        if (changes.isEmpty()) {
            return ResponseEntity.ok(view(project, issue, permissions));
        }
        Instant now = Instant.now();
        editor.apply(issue, edit, now);
        if (newReporter) {
            issue.reportedBy(reporter, now);
        }
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
        ProjectRef project = found.get().project();
        IssueEntity issue = found.get().issue();
        Instant now = Instant.now();
        String parentKey = key(project, issue);
        for (IssueEntity child : issues.findByParentIdOrderByNumber(issue.id())) {
            if (child.type() == IssueType.SUBTASK) {
                issues.delete(child);
                events.publishEvent(new IssueDeleted(child.id(), project.id(), key(project, child), user.username(), now));
            } else {
                child.parentTo(null, now);
                issues.save(child);
                events.publishEvent(new IssueChanged(child.id(), project.id(), key(project, child), user.username(), now,
                        List.of(new FieldChange("parent", parentKey, null))));
            }
        }
        issues.delete(issue);
        events.publishEvent(new IssueDeleted(issue.id(), project.id(), parentKey, user.username(), now));
        return ResponseEntity.noContent().build();
    }

    /**
     * The children of the issue (ADR-28): the subtasks of a story, task or bug, the issues of an epic, with how many
     * are in each category of status.
     */
    @ApiDoc(value = "The children of an issue: its subtasks, or the issues of an epic, with their progress",
            answer = Children.class, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/children")
    ResponseEntity<?> children(@PathVariable String issueKey) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(found.get().project()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        ProjectRef project = found.get().project();
        IssueEntity issue = found.get().issue();
        Map<String, String> categories = categories(issue);
        Map<String, Integer> progress = new LinkedHashMap<>();
        progress.put("todo", 0);
        progress.put("in_progress", 0);
        progress.put("done", 0);
        List<ChildRow> rows = issues.findByParentIdOrderByNumber(issue.id()).stream().map(c -> {
            String category = categories.getOrDefault(c.status(), "todo");
            progress.merge(category, 1, Integer::sum);
            return new ChildRow(key(project, c), c.type().value(), c.summary(), c.status(), category,
                    c.priority().value(), c.assignee());
        }).toList();
        return ResponseEntity.ok(new Children(rows, progress, progress.get("done"), rows.size(), childType(issue),
                String.join(", ", rows.stream().map(ChildRow::key).toList())));
    }

    /**
     * The issues of the project that can be the parent of an issue of {@code type} (ADR-28): epics for a story, task or
     * bug, those for a subtask, none for an epic; whose key or summary has {@code q}, without {@code exclude} (the issue
     * itself), at most 20.
     */
    @ApiDoc(value = "The issues of a project that can be the parent of an issue of a type",
            answer = ParentCandidate.class, list = true, project = "odt.issue.browse", internal = true)
    @GetMapping("/api/projects/{key}/parents")
    ResponseEntity<?> parents(@PathVariable String key, @RequestParam(required = false) String type,
                              @RequestParam(required = false) String q, @RequestParam(required = false) String exclude) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        if (!permissions(project.get()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        IssueType child = type == null || type.isBlank() ? IssueType.TASK : IssueType.parse(type).orElse(null);
        if (child == null) {
            return Problems.badRequest("The type is one of " + String.join(", ", IssueType.names()));
        }
        String word = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        String skip = exclude == null ? "" : exclude.strip().toUpperCase(Locale.ROOT);
        List<ParentCandidate> rows = issues.findByProjectIdOrderByNumber(project.get().id(), Limit.of(MAX_LIST)).stream()
                .filter(i -> i.type().level() == child.level() + 1)
                .filter(i -> !key(project.get(), i).equals(skip))
                .filter(i -> word.isEmpty() || key(project.get(), i).toLowerCase(Locale.ROOT).contains(word)
                        || i.summary().toLowerCase(Locale.ROOT).contains(word))
                .limit(MAX_SUGGESTED)
                .map(i -> new ParentCandidate(key(project.get(), i), key(project.get(), i) + " " + i.summary()))
                .toList();
        return ResponseEntity.ok(rows);
    }

    /** The type a new child of the issue takes: a task under an epic, a subtask under the others, none under a subtask. */
    static String childType(IssueEntity issue) {
        return switch (issue.type()) {
            case EPIC -> IssueType.TASK.value();
            case SUBTASK -> null;
            default -> IssueType.SUBTASK.value();
        };
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
        Map<String, String> categories = categories(issue);
        return ResponseEntity.ok(workflows.available(issue.projectId(), issue.type().value(), issue.status(),
                guardInput(found.get()), userInput(), permissions).stream()
                .map(t -> new TransitionView(t.code(), t.to(), categories.get(t.to()), issue.version())).toList());
    }

    /** The workflow the issue follows (the one its scheme gives to its type), to draw it; 404 in a project without one. */
    @ApiDoc(value = "The workflow an issue follows, with its statuses and transitions",
            answer = IssueWorkflow.class, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/workflow")
    ResponseEntity<?> workflow(@PathVariable String issueKey) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(found.get().project()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        IssueEntity issue = found.get().issue();
        return workflows.graph(issue.projectId(), issue.type().value())
                .<ResponseEntity<?>>map(g -> ResponseEntity.ok(new IssueWorkflow(g.name(), issue.status(),
                        categories(issue).get(issue.status()),
                        g.statuses().stream().map(s -> new IssueWorkflowStatus(s.code(), s.category())).toList(),
                        g.transitions().stream().map(t -> new IssueWorkflowTransition(t.code(), t.from(), t.to())).toList())))
                .orElseGet(() -> Problems.notFound("The project of the issue has no workflow"));
    }

    /** The category of each status of the project, by code (a code has one category in a project). */
    private Map<String, String> categories(IssueEntity issue) {
        Map<String, String> categories = new HashMap<>();
        workflows.statuses(issue.projectId()).forEach(s -> categories.put(s.code(), s.category()));
        return categories;
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
        Map<String, Boolean> perm = Map.ofEntries(
                Map.entry("edit", permissions.contains("odt.issue.edit")),
                Map.entry("delete", permissions.contains("odt.issue.delete")),
                Map.entry("transition", permissions.contains("odt.issue.transition")),
                Map.entry("comment", permissions.contains("odt.issue.comment")),
                Map.entry("attach", permissions.contains("odt.issue.attach")),
                Map.entry("clone", permissions.contains("odt.issue.create")),
                Map.entry("move", permissions.contains(IssueMover.MOVE)),
                Map.entry("reporter", permissions.contains("odt.issue.edit") && permissions.contains(REPORTER)),
                Map.entry("vote", !user.username().equals(i.reporter()) && !FlagAndVoteController.done(workflows, i)),
                Map.entry("child", permissions.contains("odt.issue.create") && i.type() != IssueType.SUBTASK),
                Map.entry("worklog", permissions.contains("odt.worklog.add")));
        ParentView parent = i.parentId() == null ? null : issues.findById(i.parentId())
                .map(p -> new ParentView(key(project, p), p.summary(), p.type().value(), p.status())).orElse(null);
        return new IssueView(key(project, i), project.key(), i.number(), i.type().value(), i.summary(), i.description(),
                i.status(), i.priority().value(), i.assignee(), i.reporter(), i.dueOn(), i.startOn(), i.reviewer(), i.labels(), i.custom(),
                i.createdAt(), i.updatedAt(), i.version(), watchers.existsByIssueIdAndUsername(i.id(), user.username()),
                i.flagged(), votes.countByIssueId(i.id()), votes.existsByIssueIdAndUsername(i.id(), user.username()), parent,
                parent == null ? null : parent.key(), editor.durations(i.originalEstimate()), i.originalEstimate(),
                editor.durations(i.remainingEstimate()), i.remainingEstimate(), editor.durations(i.timeSpent()), i.timeSpent(),
                perm);
    }
}
