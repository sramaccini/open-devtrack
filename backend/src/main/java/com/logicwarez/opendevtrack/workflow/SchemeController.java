/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.IssueStatuses;
import com.logicwarez.opendevtrack.workflow.Workflows.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * The workflows of a project and the types of issue each one serves (ADR-20, B), edited by an admin of the project. A
 * new workflow is a copy of the default one; a type that no workflow maps follows the default. When a type moves to
 * another workflow, its issues in a status that workflow lacks move to its first status of the same category (else to
 * its first status), recorded in their history. A workflow is removed when it is not the default and serves no type.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class SchemeController {

    private static final String WORKFLOWS = "/api/projects/{key}/workflows";
    private static final int MAX_NAME = 100;

    private final Projects projects;
    private final Workflows workflows;
    private final WorkflowRepository workflowRepository;
    private final SchemeRepository schemes;
    private final StatusRepository statuses;
    private final TransitionRepository transitions;
    private final IssueStatuses issues;
    private final ProjectAccess access;
    private final CurrentUser user;

    SchemeController(Projects projects, Workflows workflows, WorkflowRepository workflowRepository,
                     SchemeRepository schemes, StatusRepository statuses, TransitionRepository transitions,
                     IssueStatuses issues, ProjectAccess access, CurrentUser user) {
        this.projects = projects;
        this.workflows = workflows;
        this.workflowRepository = workflowRepository;
        this.schemes = schemes;
        this.statuses = statuses;
        this.transitions = transitions;
        this.issues = issues;
        this.access = access;
        this.user = user;
    }

    /** A workflow, the types whose issues follow it, and how many statuses and transitions it has. */
    record WorkflowView(UUID id, String name, @JsonProperty("default") boolean isDefault, List<String> types,
                        int statuses, int transitions, long version, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A new workflow (its name), or a change: name, and the types mapped to it (not for the default). */
    record WorkflowRequest(String name, List<String> types, Long version) {
    }

    @ApiDoc(value = "The workflows of a project, the default first",
            answer = WorkflowView.class, list = true, project = "odt.issue.browse")
    @GetMapping(WORKFLOWS)
    ResponseEntity<?> list(@PathVariable String key) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(WorkflowController.NOT_FOUND);
        }
        boolean edit = canEdit(project.get());
        return ResponseEntity.ok(workflowRepository.findByProjectIdOrderByIsDefaultDescIdAsc(project.get().id()).stream()
                .map(w -> view(w, edit)).toList());
    }

    @ApiDoc(value = "A workflow of a project", answer = WorkflowView.class, project = "odt.issue.browse")
    @GetMapping(WORKFLOWS + "/{workflow}")
    ResponseEntity<?> get(@PathVariable String key, @PathVariable UUID workflow) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(WorkflowController.NOT_FOUND);
        }
        return workflowRepository.findByIdAndProjectId(workflow, project.get().id())
                .<ResponseEntity<?>>map(w -> ResponseEntity.ok(view(w, canEdit(project.get()))))
                .orElseGet(() -> Problems.notFound(WorkflowController.NO_WORKFLOW));
    }

    /** A copy of the default workflow, with its statuses and transitions, under a new name; it serves no type yet. */
    @ApiDoc(value = "Adds a workflow, a copy of the default",
            answer = WorkflowView.class, status = 201, project = "odt.project.edit")
    @PostMapping(WORKFLOWS)
    @Transactional
    ResponseEntity<?> add(@PathVariable String key, @RequestBody WorkflowRequest request) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(WorkflowController.NOT_FOUND);
        }
        if (!canEdit(project.get())) {
            return Problems.forbidden(WorkflowController.NOT_ADMIN);
        }
        Optional<WorkflowEntity> source = workflows.workflow(project.get().id());
        if (source.isEmpty()) {
            return Problems.notFound("The project has no workflow");
        }
        String name = name(request.name());
        Optional<ResponseEntity<?>> wrong = wrongName(project.get(), null, name);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        WorkflowEntity copy = workflowRepository.save(new WorkflowEntity(source.get().tenantId(), project.get().id(),
                name, false));
        for (StatusEntity s : statuses.findByWorkflowIdOrderByPosition(source.get().id())) {
            statuses.save(new StatusEntity(copy.id(), s.code(), s.category(), s.position()));
        }
        List<TransitionEntity> from = transitions.findByWorkflowIdOrderByPosition(source.get().id());
        for (int i = 0; i < from.size(); i++) {
            TransitionEntity t = from.get(i);
            TransitionEntity c = new TransitionEntity(copy.id(), t.code(), t.fromStatus(), t.toStatus(), i);
            c.guard(t.guardCel());
            transitions.save(c);
        }
        workflowRepository.flush();
        return ResponseEntity.status(HttpStatus.CREATED).body(view(copy, true));
    }

    /**
     * Renames the workflow and maps types to it: a type taken from another workflow, or given back to the default, has
     * its issues moved where their status is missing (ADR-20, B.5).
     */
    @ApiDoc(value = "Renames a workflow and sets the issue types it serves",
            answer = WorkflowView.class, project = "odt.project.edit")
    @PutMapping(WORKFLOWS + "/{workflow}")
    @Transactional
    ResponseEntity<?> change(@PathVariable String key, @PathVariable UUID workflow, @RequestBody WorkflowRequest request) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(WorkflowController.NOT_FOUND);
        }
        if (!canEdit(project.get())) {
            return Problems.forbidden(WorkflowController.NOT_ADMIN);
        }
        Optional<WorkflowEntity> found = workflowRepository.findByIdAndProjectId(workflow, project.get().id());
        if (found.isEmpty()) {
            return Problems.notFound(WorkflowController.NO_WORKFLOW);
        }
        WorkflowEntity w = found.get();
        if (request.version() == null || request.version() != w.version()) {
            return Problems.changedMeanwhile();
        }
        String name = request.name() == null ? w.name() : name(request.name());
        Optional<ResponseEntity<?>> wrong = wrongName(project.get(), w, name);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        List<String> before = workflows.types(w);
        Set<String> after = request.types() == null ? new LinkedHashSet<>(before) : new LinkedHashSet<>(request.types());
        if (!issues.types().containsAll(after)) {
            return Problems.badRequest("The types are among " + String.join(", ", issues.types()));
        }
        if (w.isDefault() && !after.equals(new LinkedHashSet<>(before))) {
            return Problems.badRequest("The default workflow serves the types that no other workflow maps");
        }
        w.rename(name);
        if (!w.isDefault()) {
            map(project.get(), w, before, after);
        }
        w.changed(Instant.now());
        try {
            workflowRepository.saveAndFlush(w);
        } catch (ObjectOptimisticLockingFailureException e) {
            return WorkflowController.rollback(Problems.changedMeanwhile());
        }
        return ResponseEntity.ok(view(w, true));
    }

    /** Removes a workflow that is not the default and serves no type, with its statuses and transitions. */
    @ApiDoc(value = "Removes a workflow that serves no issue type", status = 204, project = "odt.project.edit")
    @DeleteMapping(WORKFLOWS + "/{workflow}")
    @Transactional
    ResponseEntity<?> remove(@PathVariable String key, @PathVariable UUID workflow,
                             @RequestParam(required = false) Long version) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(WorkflowController.NOT_FOUND);
        }
        if (!canEdit(project.get())) {
            return Problems.forbidden(WorkflowController.NOT_ADMIN);
        }
        Optional<WorkflowEntity> found = workflowRepository.findByIdAndProjectId(workflow, project.get().id());
        if (found.isEmpty()) {
            return Problems.notFound(WorkflowController.NO_WORKFLOW);
        }
        WorkflowEntity w = found.get();
        if (version == null || version != w.version()) {
            return Problems.changedMeanwhile();
        }
        if (w.isDefault()) {
            return Problems.conflict("The default workflow of the project stays");
        }
        if (!workflows.types(w).isEmpty()) {
            return Problems.conflict("A workflow is removed when no type maps to it");
        }
        workflowRepository.delete(w);
        workflowRepository.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * The scheme rows of the workflow become {@code after}; each type that changes workflow has its issues placed in the
     * new one.
     */
    private void map(ProjectRef project, WorkflowEntity w, List<String> before, Set<String> after) {
        WorkflowEntity fallback = workflows.workflow(project.id()).orElseThrow();
        List<Status> all = workflows.statuses(project.id());
        for (String type : before) {
            if (!after.contains(type)) {
                schemes.deleteById(new SchemeEntity.Key(project.id(), type));
                schemes.flush();
                place(project, type, all, workflows.statuses(fallback));
            }
        }
        for (String type : after) {
            if (!before.contains(type)) {
                SchemeEntity row = schemes.findByProjectIdAndIssueType(project.id(), type)
                        .orElseGet(() -> new SchemeEntity(project.id(), type, w.id()));
                row.workflow(w.id());
                schemes.save(row);
                place(project, type, all, workflows.statuses(w));
            }
        }
        schemes.flush();
    }

    /** The issues of the type move from each status the target lacks to its place there. */
    private void place(ProjectRef project, String type, List<Status> all, List<Status> target) {
        for (Status s : all) {
            String to = Workflows.statusIn(target, s.code(), s.category());
            if (!to.equals(s.code())) {
                issues.move(project.id(), List.of(type), s.code(), to);
            }
        }
    }

    private Optional<ResponseEntity<?>> wrongName(ProjectRef project, WorkflowEntity self, String name) {
        if (name.isEmpty() || name.length() > MAX_NAME) {
            return Optional.of(Problems.badRequest("A workflow has a name, at most " + MAX_NAME + " characters"));
        }
        boolean taken = workflowRepository.findByProjectIdOrderByIsDefaultDescIdAsc(project.id()).stream()
                .anyMatch(o -> (self == null || !o.id().equals(self.id()))
                        && o.name().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
        return taken ? Optional.of(Problems.conflict("The project already has a workflow with this name"))
                : Optional.empty();
    }

    private WorkflowView view(WorkflowEntity w, boolean edit) {
        List<String> types = workflows.types(w);
        return new WorkflowView(w.id(), w.name(), w.isDefault(), new ArrayList<>(types),
                statuses.findByWorkflowIdOrderByPosition(w.id()).size(),
                transitions.findByWorkflowIdOrderByPosition(w.id()).size(), w.version(),
                Map.of("edit", edit, "types", edit && !w.isDefault(),
                        "remove", edit && !w.isDefault() && types.isEmpty()));
    }

    private static String name(String name) {
        return name == null ? "" : name.strip();
    }

    private boolean canEdit(ProjectRef project) {
        return access.permissions(project.id(), user.username()).contains("odt.project.edit");
    }
}
