/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.reporting;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reports of a project (ADR-17) for its dashboard: indicators, burndown, velocity, cumulative flow, created vs
 * resolved. For the members of the project (and the admin of the tenant).
 */
@RestController
@PreAuthorize("hasAuthority('odt.project.browse')")
class ReportController {

    static final String BROWSE = "odt.issue.browse";

    private final Reports reports;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;

    ReportController(Reports reports, Projects projects, ProjectAccess access, CurrentUser user) {
        this.reports = reports;
        this.projects = projects;
        this.access = access;
        this.user = user;
    }

    @GetMapping("/api/projects/{key}/reports/summary")
    ResponseEntity<?> summary(@PathVariable String key) {
        return of(key, p -> reports.summary(p.id()));
    }

    @GetMapping("/api/projects/{key}/reports/sprints")
    ResponseEntity<?> sprints(@PathVariable String key, @RequestParam(required = false) String q) {
        return of(key, p -> reports.sprints(p.id(), q));
    }

    @GetMapping("/api/projects/{key}/reports/burndown")
    ResponseEntity<?> burndown(@PathVariable String key, @RequestParam(required = false) String sprint) {
        UUID sprintId;
        try {
            sprintId = sprint == null || sprint.isBlank() ? null : UUID.fromString(sprint);
        } catch (IllegalArgumentException e) {
            return Problems.badRequest("Not a sprint: " + sprint);
        }
        return of(key, p -> reports.burndown(p.id(), sprintId));
    }

    @GetMapping("/api/projects/{key}/reports/velocity")
    ResponseEntity<?> velocity(@PathVariable String key) {
        return of(key, p -> reports.velocity(p.id()));
    }

    @GetMapping("/api/projects/{key}/reports/flow")
    ResponseEntity<?> flow(@PathVariable String key) {
        return of(key, p -> reports.flow(p.id()));
    }

    @GetMapping("/api/projects/{key}/reports/created-resolved")
    ResponseEntity<?> createdResolved(@PathVariable String key) {
        return of(key, p -> reports.createdResolved(p.id()));
    }

    private ResponseEntity<?> of(String key, Function<ProjectRef, Object> report) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        if (!access.permissions(project.get().id(), user.username()).contains(BROWSE)) {
            return Problems.forbidden("Only the members of the project see its reports");
        }
        return ResponseEntity.ok(report.apply(project.get()));
    }
}
