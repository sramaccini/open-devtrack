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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workflow of a project: its statuses and transitions for whoever sees the project, the guard of a transition
 * changed by an admin of the project ({@code odt.project.edit}). A guard is checked when it is saved: a CEL error is a
 * 400 with its position.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class WorkflowController {

    private static final String NOT_FOUND = "Project not found";

    private final Projects projects;
    private final Workflows workflows;
    private final TransitionRepository transitions;
    private final Conditions guards;
    private final ProjectAccess access;
    private final CurrentUser user;

    WorkflowController(Projects projects, Workflows workflows, TransitionRepository transitions, Conditions guards,
                       ProjectAccess access, CurrentUser user) {
        this.projects = projects;
        this.workflows = workflows;
        this.transitions = transitions;
        this.guards = guards;
        this.access = access;
        this.user = user;
    }

    record TransitionView(String code, String from, String to, String guard,
                          @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    record GuardRequest(String guard) {
    }

    @GetMapping("/api/projects/{key}/workflow/statuses")
    ResponseEntity<?> statuses(@PathVariable String key) {
        return projects.find(key).<ResponseEntity<?>>map(p -> ResponseEntity.ok(workflows.statuses(p.id())))
                .orElseGet(() -> Problems.notFound(NOT_FOUND));
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
                        .map(t -> new TransitionView(t.code(), t.fromStatus(), t.toStatus(), t.guardCel(), Map.of("edit", edit)))
                        .toList())
                .orElse(List.of()));
    }

    /** The condition of a transition, in CEL; empty for none. */
    @PutMapping("/api/projects/{key}/workflow/transitions/{code}")
    @Transactional
    ResponseEntity<?> guard(@PathVariable String key, @PathVariable String code, @RequestBody GuardRequest request) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!canEdit(project.get())) {
            return Problems.forbidden("Only an admin of the project changes its workflow");
        }
        Optional<TransitionEntity> found = workflows.workflow(project.get().id())
                .flatMap(w -> transitions.findByWorkflowIdAndCode(w.id(), code));
        if (found.isEmpty()) {
            return Problems.notFound("Transition not found");
        }
        String guard = request.guard() == null || request.guard().isBlank() ? null : request.guard().strip();
        if (guard != null) {
            Optional<String> invalid = guards.check(guard);
            if (invalid.isPresent()) {
                return Problems.badRequest("The condition is not valid CEL: " + invalid.get());
            }
        }
        TransitionEntity t = found.get();
        t.guard(guard);
        transitions.save(t);
        return ResponseEntity.ok(new TransitionView(t.code(), t.fromStatus(), t.toStatus(), t.guardCel(), Map.of("edit", true)));
    }

    private boolean canEdit(ProjectRef project) {
        return access.permissions(project.id(), user.username()).contains("odt.project.edit");
    }
}
