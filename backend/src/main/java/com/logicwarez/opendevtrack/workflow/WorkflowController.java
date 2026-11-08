/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
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
 * The workflow of a project: its statuses and transitions for whoever sees the project, edited by an admin of the
 * project ({@code odt.project.edit}, ADR-20). Codes never change once created. The first status is in the category
 * {@code todo} and each category keeps at least one status; a status is removed by moving its issues to another one
 * (through {@link IssueStatuses}), with the transitions from or to it. Every change but an addition carries the
 * version of the workflow (412 when it changed meanwhile). A guard is checked when it is saved: a CEL error is a 400.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class WorkflowController {

    private static final String NOT_FOUND = "Project not found";
    private static final String NOT_ADMIN = "Only an admin of the project changes its workflow";
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,49}$");
    private static final List<String> CATEGORIES = List.of("todo", "in_progress", "done");

    private final Projects projects;
    private final Workflows workflows;
    private final WorkflowRepository workflowRepository;
    private final StatusRepository statuses;
    private final TransitionRepository transitions;
    private final IssueStatuses issues;
    private final Conditions guards;
    private final ProjectAccess access;
    private final CurrentUser user;

    WorkflowController(Projects projects, Workflows workflows, WorkflowRepository workflowRepository,
                       StatusRepository statuses, TransitionRepository transitions, IssueStatuses issues,
                       Conditions guards, ProjectAccess access, CurrentUser user) {
        this.projects = projects;
        this.workflows = workflows;
        this.workflowRepository = workflowRepository;
        this.statuses = statuses;
        this.transitions = transitions;
        this.issues = issues;
        this.guards = guards;
        this.access = access;
        this.user = user;
    }

    /** A status; the position counts from 1, {@code issues} is how many issues are in it now. */
    record StatusView(String code, String category, int position, long issues, long version,
                      @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    record TransitionView(String code, String from, String to, String guard, long version,
                          @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A new status (code, category, position: last when empty) or a change of one (category, position, version). */
    record StatusRequest(String code, String category, Integer position, Long version) {
    }

    /** The removal of a status: where its issues go. */
    record RemoveRequest(String moveTo, Long version) {
    }

    /** A transition: from empty for every status, guard empty for none. */
    record TransitionRequest(String code, String from, String to, String guard, Long version) {
    }

    record Removed(String code, String movedTo, int moved) {
    }

    /** The project and its workflow, for an edit: or the answer that stops it. */
    private record Editing(ProjectRef project, WorkflowEntity workflow, ResponseEntity<?> problem) {
    }

    /** The statuses in their order; with {@code q}, those whose code holds it (the lookups of the screens). */
    @GetMapping("/api/projects/{key}/workflow/statuses")
    ResponseEntity<?> statuses(@PathVariable String key, @RequestParam(required = false) String q) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        boolean edit = canEdit(project.get());
        return ResponseEntity.ok(workflows.workflow(project.get().id())
                .map(w -> statuses.findByWorkflowIdOrderByPosition(w.id()).stream()
                        .filter(s -> q == null || s.code().contains(q.strip().toLowerCase(Locale.ROOT)))
                        .map(s -> view(project.get(), w, s, edit)).toList())
                .orElse(List.of()));
    }

    @GetMapping("/api/projects/{key}/workflow/transitions")
    ResponseEntity<?> transitions(@PathVariable String key) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        boolean edit = canEdit(project.get());
        return ResponseEntity.ok(workflows.workflow(project.get().id())
                .map(w -> transitions.findByWorkflowIdOrderByPosition(w.id()).stream()
                        .map(t -> view(w, t, edit)).toList())
                .orElse(List.of()));
    }

    @PostMapping("/api/projects/{key}/workflow/statuses")
    @Transactional
    ResponseEntity<?> addStatus(@PathVariable String key, @RequestBody StatusRequest request) {
        Editing editing = editing(key, request.version(), false);
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
        return saved(w, () -> ResponseEntity.status(HttpStatus.CREATED).body(view(editing.project(), w, status, true)));
    }

    /** The category or the position of a status; the code never changes. */
    @PutMapping("/api/projects/{key}/workflow/statuses/{code}")
    @Transactional
    ResponseEntity<?> changeStatus(@PathVariable String key, @PathVariable String code, @RequestBody StatusRequest request) {
        Editing editing = editing(key, request.version(), true);
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
        int at = request.position() == null ? all.indexOf(status) + 1 : request.position();
        if (at < 1 || at > all.size()) {
            return Problems.badRequest("The position is from 1 to " + all.size());
        }
        status.category(category);
        all.remove(status);
        all.add(at - 1, status);
        Optional<String> wrong = wrong(all);
        if (wrong.isPresent()) {
            return rollback(Problems.badRequest(wrong.get()));
        }
        number(all);
        statuses.saveAll(all);
        return saved(w, () -> ResponseEntity.ok(view(editing.project(), w, status, true)));
    }

    /** Removes a status: its issues move to {@code moveTo}, the transitions from or to it go. */
    @PostMapping("/api/projects/{key}/workflow/statuses/{code}/remove")
    @Transactional
    ResponseEntity<?> removeStatus(@PathVariable String key, @PathVariable String code, @RequestBody RemoveRequest request) {
        Editing editing = editing(key, request.version(), true);
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
        int moved = issues.move(editing.project().id(), code, moveTo);
        transitions.deleteAll(transitions.findByWorkflowIdOrderByPosition(w.id()).stream()
                .filter(t -> code.equals(t.fromStatus()) || code.equals(t.toStatus())).toList());
        statuses.delete(found.get());
        number(all);
        statuses.saveAll(all);
        number(transitions.findByWorkflowIdOrderByPosition(w.id()));
        return saved(w, () -> ResponseEntity.ok(new Removed(code, moveTo, moved)));
    }

    @PostMapping("/api/projects/{key}/workflow/transitions")
    @Transactional
    ResponseEntity<?> addTransition(@PathVariable String key, @RequestBody TransitionRequest request) {
        Editing editing = editing(key, request.version(), false);
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
        return saved(w, () -> ResponseEntity.status(HttpStatus.CREATED).body(view(w, t, true)));
    }

    /** From, to and guard of a transition; the code never changes. */
    @PutMapping("/api/projects/{key}/workflow/transitions/{code}")
    @Transactional
    ResponseEntity<?> changeTransition(@PathVariable String key, @PathVariable String code,
                                       @RequestBody TransitionRequest request) {
        Editing editing = editing(key, request.version(), true);
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
        t.change(blank(request.from()), request.to(), blank(request.guard()));
        transitions.save(t);
        return saved(w, () -> ResponseEntity.ok(view(w, t, true)));
    }

    @DeleteMapping("/api/projects/{key}/workflow/transitions/{code}")
    @Transactional
    ResponseEntity<?> removeTransition(@PathVariable String key, @PathVariable String code,
                                       @RequestParam(required = false) Long version) {
        Editing editing = editing(key, version, true);
        if (editing.problem() != null) {
            return editing.problem();
        }
        WorkflowEntity w = editing.workflow();
        Optional<TransitionEntity> found = transitions.findByWorkflowIdAndCode(w.id(), code);
        if (found.isEmpty()) {
            return Problems.notFound("Transition not found");
        }
        transitions.delete(found.get());
        transitions.flush();
        number(transitions.findByWorkflowIdOrderByPosition(w.id()));
        return saved(w, () -> ResponseEntity.noContent().build());
    }

    /** The project, its workflow and the right to edit it; the version when the change needs it (or gives it). */
    private Editing editing(String key, Long version, boolean versionNeeded) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return new Editing(null, null, Problems.notFound(NOT_FOUND));
        }
        if (!canEdit(project.get())) {
            return new Editing(null, null, Problems.forbidden(NOT_ADMIN));
        }
        Optional<WorkflowEntity> w = workflows.workflow(project.get().id());
        if (w.isEmpty()) {
            return new Editing(null, null, Problems.notFound("The project has no workflow"));
        }
        if ((versionNeeded && version == null) || (version != null && version != w.get().version())) {
            return new Editing(null, null, Problems.changedMeanwhile());
        }
        return new Editing(project.get(), w.get(), null);
    }

    /** From (empty for every status) and to are statuses of the workflow, not the same; the guard is CEL. */
    private Optional<ResponseEntity<?>> invalid(WorkflowEntity w, TransitionRequest request) {
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
    private static ResponseEntity<?> rollback(ResponseEntity<?> answer) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return answer;
    }

    private StatusView view(ProjectRef project, WorkflowEntity w, StatusEntity s, boolean edit) {
        return new StatusView(s.code(), s.category(), s.position() + 1, issues.count(project.id(), s.code()), w.version(),
                Map.of("edit", edit));
    }

    private static TransitionView view(WorkflowEntity w, TransitionEntity t, boolean edit) {
        return new TransitionView(t.code(), t.fromStatus(), t.toStatus(), t.guardCel(), w.version(), Map.of("edit", edit));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private boolean canEdit(ProjectRef project) {
        return access.permissions(project.id(), user.username()).contains("odt.project.edit");
    }
}
