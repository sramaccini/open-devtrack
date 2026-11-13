/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import static com.logicwarez.opendevtrack.shared.ConfigurationChanged.values;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.platform.AdminLog;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.Conditions;
import com.logicwarez.opendevtrack.shared.IssueStatuses;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The statuses and transitions of a workflow of a project, for whoever sees the project, edited by an admin of the
 * project ({@code odt.project.edit}, ADR-20). The paths under {@code /workflows/{workflow}} name one workflow of the
 * project; those under {@code /workflow} its default one. Codes never change once created, and a status code has the
 * same category in every workflow of the project. The first status is in the category {@code todo} and each category
 * keeps at least one status; a status is removed by moving the issues that follow the workflow to another one (through
 * {@link IssueStatuses}), with the transitions from or to it. Every change but an addition carries the version of the
 * workflow (412 when it changed meanwhile). A guard is checked when it is saved: a CEL error is a 400.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class WorkflowController {

    static final String NOT_FOUND = "Project not found";
    static final String NO_WORKFLOW = "Workflow not found";
    static final String NOT_ADMIN = "Only an admin of the project changes its workflows";
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,49}$");
    private static final List<String> CATEGORIES = List.of("todo", "in_progress", "done");
    private static final String DEFAULT = "/api/projects/{key}/workflow";
    private static final String ONE = "/api/projects/{key}/workflows/{workflow}";

    private final Projects projects;
    private final Workflows workflows;
    private final WorkflowRepository workflowRepository;
    private final StatusRepository statuses;
    private final TransitionRepository transitions;
    private final IssueStatuses issues;
    private final Conditions guards;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final AdminLog log;

    WorkflowController(Projects projects, Workflows workflows, WorkflowRepository workflowRepository,
                       StatusRepository statuses, TransitionRepository transitions, IssueStatuses issues,
                       Conditions guards, ProjectAccess access, CurrentUser user, AdminLog log) {
        this.projects = projects;
        this.workflows = workflows;
        this.workflowRepository = workflowRepository;
        this.statuses = statuses;
        this.transitions = transitions;
        this.issues = issues;
        this.guards = guards;
        this.access = access;
        this.user = user;
        this.log = log;
    }

    /**
     * A status; the position counts from 1, {@code issues} is how many issues that follow the workflow are in it now.
     */
    record StatusView(String code, String category, int position, long issues, long version,
                      @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    record WorkflowTransitionView(String code, String from, String to, String guard, long version,
                          @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A new status (code, category, position: last when empty) or a change of one (category, position, version). */
    record StatusRequest(String code, String category, Integer position, Long version) {
    }

    /** The removal of a status: where its issues go. */
    record RemoveRequest(String moveTo, Long version) {
    }

    /** A transition: from empty for every status, guard empty for none. */
    record WorkflowTransitionRequest(String code, String from, String to, String guard, Long version) {
    }

    record StatusRemoved(String code, String movedTo, int moved) {
    }

    /** The project and the workflow, for an edit: or the answer that stops it. */
    private record Editing(ProjectRef project, WorkflowEntity workflow, ResponseEntity<?> problem) {
    }

    /** The statuses in their order; with {@code q}, those whose code holds it (the lookups of the screens). */
    @ApiDoc(value = "The statuses of the default workflow, or of one",
            answer = StatusView.class, list = true, project = "odt.issue.browse")
    @GetMapping({DEFAULT + "/statuses", ONE + "/statuses"})
    ResponseEntity<?> statuses(@PathVariable String key, @PathVariable(required = false) UUID workflow,
                               @RequestParam(required = false) String q) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Optional<WorkflowEntity> w = find(project.get(), workflow);
        if (w.isEmpty()) {
            return workflow == null ? ResponseEntity.ok(List.of()) : Problems.notFound(NO_WORKFLOW);
        }
        boolean edit = canEdit(project.get());
        List<String> types = workflows.types(w.get());
        return ResponseEntity.ok(statuses.findByWorkflowIdOrderByPosition(w.get().id()).stream()
                .filter(s -> q == null || s.code().contains(q.strip().toLowerCase(Locale.ROOT)))
                .map(s -> view(project.get(), w.get(), types, s, edit)).toList());
    }

    @ApiDoc(value = "The transitions of the default workflow, or of one",
            answer = WorkflowTransitionView.class, list = true, project = "odt.issue.browse")
    @GetMapping({DEFAULT + "/transitions", ONE + "/transitions"})
    ResponseEntity<?> transitions(@PathVariable String key, @PathVariable(required = false) UUID workflow) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Optional<WorkflowEntity> w = find(project.get(), workflow);
        if (w.isEmpty()) {
            return workflow == null ? ResponseEntity.ok(List.of()) : Problems.notFound(NO_WORKFLOW);
        }
        boolean edit = canEdit(project.get());
        return ResponseEntity.ok(transitions.findByWorkflowIdOrderByPosition(w.get().id()).stream()
                .map(t -> view(w.get(), t, edit)).toList());
    }

    /** What the audit log keeps of a transition: code, from (null for every status), to and guard. */
    private static Map<String, Object> logged(TransitionEntity t) {
        return values("transition", t.code(), "from", t.fromStatus(), "to", t.toStatus(), "guard", t.guardCel());
    }

    @ApiDoc(value = "Adds a status", answer = StatusView.class, status = 201, project = "odt.project.edit")
    @PostMapping({DEFAULT + "/statuses", ONE + "/statuses"})
    @Transactional
    ResponseEntity<?> addStatus(@PathVariable String key, @PathVariable(required = false) UUID workflow,
                                @RequestBody StatusRequest request) {
        Editing editing = editing(key, workflow, request.version(), false);
        if (editing.problem() != null) {
            return editing.problem();
        }
        WorkflowEntity w = editing.workflow();
        String code = request.code() == null ? "" : request.code().strip();
        if (!CODE.matcher(code).matches()) {
            return Problems.badRequest("A code is lower-case letters, digits and _, from a letter, at most 50");
        }
        if (!CATEGORIES.contains(request.category())) {
            return Problems.badRequest("The category is one of todo, in_progress, done");
        }
        List<StatusEntity> all = new ArrayList<>(statuses.findByWorkflowIdOrderByPosition(w.id()));
        if (all.stream().anyMatch(s -> s.code().equals(code))) {
            return Problems.conflict("The workflow already has a status with this code");
        }
        Optional<String> elsewhere = elsewhere(w, code, request.category());
        if (elsewhere.isPresent()) {
            return Problems.badRequest(elsewhere.get());
        }
        int at = request.position() == null ? all.size() + 1 : request.position();
        if (at < 1 || at > all.size() + 1) {
            return Problems.badRequest("The position is from 1 to " + (all.size() + 1));
        }
        StatusEntity status = new StatusEntity(w.id(), code, request.category(), at - 1);
        all.add(at - 1, status);
        Optional<String> wrong = wrong(all);
        if (wrong.isPresent()) {
            return Problems.badRequest(wrong.get());
        }
        number(all);
        statuses.saveAll(all);
        log.project(editing.project().id(), "workflow.status_added", w.name(), null,
                values("status", code, "category", status.category(), "position", at));
        List<String> types = workflows.types(w);
        return saved(w, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(view(editing.project(), w, types, status, true)));
    }

    /** The category or the position of a status; the code never changes. */
    @ApiDoc(value = "Changes a status", answer = StatusView.class, project = "odt.project.edit")
    @PutMapping({DEFAULT + "/statuses/{code}", ONE + "/statuses/{code}"})
    @Transactional
    ResponseEntity<?> changeStatus(@PathVariable String key, @PathVariable(required = false) UUID workflow,
                                   @PathVariable String code, @RequestBody StatusRequest request) {
        Editing editing = editing(key, workflow, request.version(), true);
        if (editing.problem() != null) {
            return editing.problem();
        }
        WorkflowEntity w = editing.workflow();
        List<StatusEntity> all = new ArrayList<>(statuses.findByWorkflowIdOrderByPosition(w.id()));
        Optional<StatusEntity> found = all.stream().filter(s -> s.code().equals(code)).findFirst();
        if (found.isEmpty()) {
            return Problems.notFound("Status not found");
        }
        StatusEntity status = found.get();
        String category = request.category() == null ? status.category() : request.category();
        if (!CATEGORIES.contains(category)) {
            return Problems.badRequest("The category is one of todo, in_progress, done");
        }
        Optional<String> elsewhere = elsewhere(w, code, category);
        if (elsewhere.isPresent()) {
            return Problems.badRequest(elsewhere.get());
        }
        int at = request.position() == null ? all.indexOf(status) + 1 : request.position();
        if (at < 1 || at > all.size()) {
            return Problems.badRequest("The position is from 1 to " + all.size());
        }
        var before = values("status", code, "category", status.category(), "position", all.indexOf(status) + 1);
        status.category(category);
        all.remove(status);
        all.add(at - 1, status);
        Optional<String> wrong = wrong(all);
        if (wrong.isPresent()) {
            return rollback(Problems.badRequest(wrong.get()));
        }
        number(all);
        statuses.saveAll(all);
        log.project(editing.project().id(), "workflow.status_changed", w.name(), before,
                values("status", code, "category", category, "position", at));
        List<String> types = workflows.types(w);
        return saved(w, () -> ResponseEntity.ok(view(editing.project(), w, types, status, true)));
    }

    /** Removes a status: the issues that follow the workflow move to {@code moveTo}, the transitions from or to it go. */
    @ApiDoc(value = "Removes a status, moving its issues to another",
            answer = StatusRemoved.class, project = "odt.project.edit")
    @PostMapping({DEFAULT + "/statuses/{code}/remove", ONE + "/statuses/{code}/remove"})
    @Transactional
    ResponseEntity<?> removeStatus(@PathVariable String key, @PathVariable(required = false) UUID workflow,
                                   @PathVariable String code, @RequestBody RemoveRequest request) {
        Editing editing = editing(key, workflow, request.version(), true);
        if (editing.problem() != null) {
            return editing.problem();
        }
        WorkflowEntity w = editing.workflow();
        List<StatusEntity> all = new ArrayList<>(statuses.findByWorkflowIdOrderByPosition(w.id()));
        Optional<StatusEntity> found = all.stream().filter(s -> s.code().equals(code)).findFirst();
        if (found.isEmpty()) {
            return Problems.notFound("Status not found");
        }
        String moveTo = request.moveTo() == null ? "" : request.moveTo();
        if (moveTo.equals(code) || all.stream().noneMatch(s -> s.code().equals(moveTo))) {
            return Problems.badRequest("The issues move to another status of the workflow");
        }
        all.remove(found.get());
        Optional<String> wrong = wrong(all);
        if (wrong.isPresent()) {
            return Problems.badRequest(wrong.get());
        }
        int moved = issues.move(editing.project().id(), workflows.types(w), code, moveTo);
        transitions.deleteAll(transitions.findByWorkflowIdOrderByPosition(w.id()).stream()
                .filter(t -> code.equals(t.fromStatus()) || code.equals(t.toStatus())).toList());
        statuses.delete(found.get());
        number(all);
        statuses.saveAll(all);
        number(transitions.findByWorkflowIdOrderByPosition(w.id()));
        log.project(editing.project().id(), "workflow.status_removed", w.name(),
                values("status", code, "category", found.get().category()), values("movedTo", moveTo, "issuesMoved", moved));
        return saved(w, () -> ResponseEntity.ok(new StatusRemoved(code, moveTo, moved)));
    }

    @ApiDoc(value = "Adds a transition",
            answer = WorkflowTransitionView.class, status = 201, project = "odt.project.edit")
    @PostMapping({DEFAULT + "/transitions", ONE + "/transitions"})
    @Transactional
    ResponseEntity<?> addTransition(@PathVariable String key, @PathVariable(required = false) UUID workflow,
                                    @RequestBody WorkflowTransitionRequest request) {
        Editing editing = editing(key, workflow, request.version(), false);
        if (editing.problem() != null) {
            return editing.problem();
        }
        WorkflowEntity w = editing.workflow();
        String code = request.code() == null ? "" : request.code().strip();
        if (!CODE.matcher(code).matches()) {
            return Problems.badRequest("A code is lower-case letters, digits and _, from a letter, at most 50");
        }
        List<TransitionEntity> all = transitions.findByWorkflowIdOrderByPosition(w.id());
        if (all.stream().anyMatch(t -> t.code().equals(code))) {
            return Problems.conflict("The workflow already has a transition with this code");
        }
        Optional<ResponseEntity<?>> invalid = invalid(w, request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        TransitionEntity t = new TransitionEntity(w.id(), code, blank(request.from()), request.to(), all.size());
        t.guard(blank(request.guard()));
        transitions.save(t);
        log.project(editing.project().id(), "workflow.transition_added", w.name(), null, logged(t));
        return saved(w, () -> ResponseEntity.status(HttpStatus.CREATED).body(view(w, t, true)));
    }

    /** From, to and guard of a transition; the code never changes. */
    @ApiDoc(value = "Changes a transition", answer = WorkflowTransitionView.class, project = "odt.project.edit")
    @PutMapping({DEFAULT + "/transitions/{code}", ONE + "/transitions/{code}"})
    @Transactional
    ResponseEntity<?> changeTransition(@PathVariable String key, @PathVariable(required = false) UUID workflow,
                                       @PathVariable String code, @RequestBody WorkflowTransitionRequest request) {
        Editing editing = editing(key, workflow, request.version(), true);
        if (editing.problem() != null) {
            return editing.problem();
        }
        WorkflowEntity w = editing.workflow();
        Optional<TransitionEntity> found = transitions.findByWorkflowIdAndCode(w.id(), code);
        if (found.isEmpty()) {
            return Problems.notFound("Transition not found");
        }
        Optional<ResponseEntity<?>> invalid = invalid(w, request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        TransitionEntity t = found.get();
        var before = logged(t);
        t.change(blank(request.from()), request.to(), blank(request.guard()));
        transitions.save(t);
        log.project(editing.project().id(), "workflow.transition_changed", w.name(), before, logged(t));
        return saved(w, () -> ResponseEntity.ok(view(w, t, true)));
    }

    @ApiDoc(value = "Removes a transition", status = 204, project = "odt.project.edit")
    @DeleteMapping({DEFAULT + "/transitions/{code}", ONE + "/transitions/{code}"})
    @Transactional
    ResponseEntity<?> removeTransition(@PathVariable String key, @PathVariable(required = false) UUID workflow,
                                       @PathVariable String code, @RequestParam(required = false) Long version) {
        Editing editing = editing(key, workflow, version, true);
        if (editing.problem() != null) {
            return editing.problem();
        }
        WorkflowEntity w = editing.workflow();
        Optional<TransitionEntity> found = transitions.findByWorkflowIdAndCode(w.id(), code);
        if (found.isEmpty()) {
            return Problems.notFound("Transition not found");
        }
        transitions.delete(found.get());
        log.project(editing.project().id(), "workflow.transition_removed", w.name(), logged(found.get()), null);
        transitions.flush();
        number(transitions.findByWorkflowIdOrderByPosition(w.id()));
        return saved(w, () -> ResponseEntity.noContent().build());
    }

    /** The workflow named by the path, of the project; the default one when the path names none. */
    private Optional<WorkflowEntity> find(ProjectRef project, UUID workflow) {
        return workflow == null ? workflows.workflow(project.id())
                : workflowRepository.findByIdAndProjectId(workflow, project.id());
    }

    /** The project, the workflow and the right to edit it; the version when the change needs it (or gives it). */
    private Editing editing(String key, UUID workflow, Long version, boolean versionNeeded) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return new Editing(null, null, Problems.notFound(NOT_FOUND));
        }
        if (!canEdit(project.get())) {
            return new Editing(null, null, Problems.forbidden(NOT_ADMIN));
        }
        Optional<WorkflowEntity> w = find(project.get(), workflow);
        if (w.isEmpty()) {
            return new Editing(null, null, Problems.notFound(workflow == null ? "The project has no workflow" : NO_WORKFLOW));
        }
        if ((versionNeeded && version == null) || (version != null && version != w.get().version())) {
            return new Editing(null, null, Problems.changedMeanwhile());
        }
        return new Editing(project.get(), w.get(), null);
    }

    /** A code means the same in every workflow of the project (ADR-20, B.3): another category elsewhere is wrong. */
    private Optional<String> elsewhere(WorkflowEntity w, String code, String category) {
        for (WorkflowEntity other : workflowRepository.findByProjectIdOrderByIsDefaultDescIdAsc(w.projectId())) {
            if (other.id().equals(w.id())) {
                continue;
            }
            Optional<StatusEntity> same = statuses.findByWorkflowIdOrderByPosition(other.id()).stream()
                    .filter(s -> s.code().equals(code)).findFirst();
            if (same.isPresent() && !same.get().category().equals(category)) {
                return Optional.of("The status " + code + " is in the category " + same.get().category()
                        + " in the workflow " + other.name() + ": a code means the same in every workflow of the project");
            }
        }
        return Optional.empty();
    }

    /** From (empty for every status) and to are statuses of the workflow, not the same; the guard is CEL. */
    private Optional<ResponseEntity<?>> invalid(WorkflowEntity w, WorkflowTransitionRequest request) {
        List<String> codes = statuses.findByWorkflowIdOrderByPosition(w.id()).stream().map(StatusEntity::code).toList();
        String from = blank(request.from());
        if (request.to() == null || !codes.contains(request.to()) || (from != null && !codes.contains(from))) {
            return Optional.of(Problems.badRequest("From and to are statuses of the workflow"));
        }
        if (request.to().equals(from)) {
            return Optional.of(Problems.badRequest("A transition leads to another status"));
        }
        String guard = blank(request.guard());
        if (guard != null) {
            Optional<String> wrong = guards.check(guard);
            if (wrong.isPresent()) {
                return Optional.of(Problems.badRequest("The condition is not valid CEL: " + wrong.get()));
            }
        }
        return Optional.empty();
    }

    /** What breaks the rules of the statuses (ADR-20, A.2), in their new order. */
    static Optional<String> wrong(List<StatusEntity> ordered) {
        if (ordered.isEmpty() || !ordered.getFirst().category().equals("todo")) {
            return Optional.of("The first status is in the category todo: new issues start there");
        }
        for (String category : CATEGORIES) {
            if (ordered.stream().noneMatch(s -> s.category().equals(category))) {
                return Optional.of("Each category keeps at least one status: " + category + " would have none");
            }
        }
        return Optional.empty();
    }

    private static void number(List<StatusEntity> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).position(i);
        }
    }

    private void number(Iterable<TransitionEntity> ordered) {
        int i = 0;
        for (TransitionEntity t : ordered) {
            t.position(i++);
        }
    }

    /** The workflow marked changed, so its version moves on: 412 when another change got there first. */
    private ResponseEntity<?> saved(WorkflowEntity w, java.util.function.Supplier<ResponseEntity<?>> answer) {
        w.changed(Instant.now());
        try {
            workflowRepository.saveAndFlush(w);
            statuses.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            return rollback(Problems.changedMeanwhile());
        }
        return answer.get();
    }

    /** An answer that undoes the changes already made to the entities of the transaction. */
    static ResponseEntity<?> rollback(ResponseEntity<?> answer) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return answer;
    }

    private StatusView view(ProjectRef project, WorkflowEntity w, List<String> types, StatusEntity s, boolean edit) {
        return new StatusView(s.code(), s.category(), s.position() + 1, issues.count(project.id(), types, s.code()),
                w.version(), Map.of("edit", edit));
    }

    private static WorkflowTransitionView view(WorkflowEntity w, TransitionEntity t, boolean edit) {
        return new WorkflowTransitionView(t.code(), t.fromStatus(), t.toStatus(), t.guardCel(), w.version(), Map.of("edit", edit));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private boolean canEdit(ProjectRef project) {
        return access.permissions(project.id(), user.username()).contains("odt.project.edit");
    }
}
