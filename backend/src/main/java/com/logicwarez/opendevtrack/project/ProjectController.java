/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.access.ProjectAccess.Change;
import com.logicwarez.opendevtrack.access.ProjectMember;
import com.logicwarez.opendevtrack.access.ProjectRole;
import com.logicwarez.opendevtrack.identity.TenantUsers;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectApi.CreateProject;
import com.logicwarez.opendevtrack.project.ProjectApi.EditProject;
import com.logicwarez.opendevtrack.project.ProjectApi.MemberRequest;
import com.logicwarez.opendevtrack.project.ProjectApi.MemberView;
import com.logicwarez.opendevtrack.project.ProjectApi.ProjectView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Projects of the tenant of the session (E1) and their members.
 *
 * <ul>
 *   <li>{@code odt.project.browse} (global): list and read the projects of the tenant and their members;</li>
 *   <li>{@code odt.project.create} (global): create one; the creator becomes its lead and admin;</li>
 *   <li>{@code odt.project.edit}, {@code odt.member.manage} (project role admin): edit it, manage its members.</li>
 * </ul>
 * A project of another tenant answers 404, as one that does not exist (ADR-09).
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class ProjectController {

    static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    private static final String NOT_FOUND = "Project not found";

    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final TenantUsers users;
    private final CurrentUser user;

    ProjectController(ProjectRepository projects, ProjectAccess access, TenantUsers users, CurrentUser user) {
        this.projects = projects;
        this.access = access;
        this.users = users;
        this.user = user;
    }

    @GetMapping("/api/projects")
    List<ProjectView> list() {
        List<ProjectEntity> all = projects.findByTenantIdOrderByKey(user.tenantId());
        Map<java.util.UUID, ProjectRole> roles = access.roles(all.stream().map(ProjectEntity::id).toList(), user.username());
        return all.stream().map(p -> view(p, Optional.ofNullable(roles.get(p.id())))).toList();
    }

    @GetMapping("/api/projects/{key}")
    ResponseEntity<?> get(@PathVariable String key) {
        return find(key).<ResponseEntity<?>>map(p -> ResponseEntity.ok(view(p, access.role(p.id(), user.username()))))
                .orElseGet(() -> Problems.notFound(NOT_FOUND));
    }

    @PostMapping("/api/projects")
    @PreAuthorize("hasAuthority('odt.project.create')")
    @Transactional
    ResponseEntity<?> create(@RequestBody CreateProject request) {
        String key = request.key() == null ? "" : request.key().strip();
        if (!KEY.matcher(key).matches()) {
            return Problems.badRequest("The key is 2 to 10 capital letters or digits, starting with a letter (for example ODT)");
        }
        Optional<String> invalid = checkName(request.name(), request.description());
        if (invalid.isPresent()) {
            return Problems.badRequest(invalid.get());
        }
        if (projects.existsByTenantIdAndKey(user.tenantId(), key)) {
            return Problems.conflict("A project with the key " + key + " already exists");
        }
        ProjectEntity project = projects.saveAndFlush(new ProjectEntity(user.tenantId(), key, request.name().strip(),
                blankToNull(request.description()), user.username(), Instant.now()));
        access.grant(project.id(), user.username(), ProjectRole.ADMIN, user.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(project, Optional.of(ProjectRole.ADMIN)));
    }

    /** Name, description and lead; the version read with the project, or 412 if someone saved in the meantime. */
    @PutMapping("/api/projects/{key}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable String key, @RequestBody EditProject request) {
        Optional<ProjectEntity> found = find(key);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectEntity project = found.get();
        if (!can(project, "odt.project.edit")) {
            return Problems.forbidden("Only an admin of the project can edit it");
        }
        Optional<String> invalid = checkName(request.name(), request.description());
        if (invalid.isPresent()) {
            return Problems.badRequest(invalid.get());
        }
        String lead = request.lead() == null || request.lead().isBlank() ? project.lead() : request.lead().strip();
        if (access.role(project.id(), lead).isEmpty()) {
            return Problems.badRequest("The lead must be a member of the project");
        }
        if (request.version() == null || request.version() != project.version()) {
            return Problems.changedMeanwhile();
        }
        project.edit(request.name().strip(), blankToNull(request.description()), lead);
        try {
            projects.saveAndFlush(project);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        return ResponseEntity.ok(view(project, access.role(project.id(), user.username())));
    }

    @GetMapping("/api/projects/{key}/members")
    ResponseEntity<?> members(@PathVariable String key) {
        return find(key).<ResponseEntity<?>>map(p -> {
            boolean manage = can(p, "odt.member.manage");
            return ResponseEntity.ok(access.members(p.id()).stream().map(m -> memberView(m, manage)).toList());
        }).orElseGet(() -> Problems.notFound(NOT_FOUND));
    }

    /** Adds a member, or changes the role of one: only users of the tenant. */
    @PostMapping("/api/projects/{key}/members")
    @Transactional
    ResponseEntity<?> addMember(@PathVariable String key, @RequestBody MemberRequest request) {
        return setRole(key, request.username(), request.role());
    }

    @PutMapping("/api/projects/{key}/members/{username}")
    @Transactional
    ResponseEntity<?> changeRole(@PathVariable String key, @PathVariable String username, @RequestBody MemberRequest request) {
        return setRole(key, username, request.role());
    }

    @DeleteMapping("/api/projects/{key}/members/{username}")
    @Transactional
    ResponseEntity<?> removeMember(@PathVariable String key, @PathVariable String username) {
        Optional<ProjectEntity> found = find(key);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!can(found.get(), "odt.member.manage")) {
            return Problems.forbidden("Only an admin of the project can manage its members");
        }
        return switch (access.revoke(found.get().id(), username)) {
            case DONE -> ResponseEntity.noContent().build();
            case NOT_A_MEMBER -> Problems.notFound("Not a member of the project");
            case LAST_ADMIN -> Problems.conflict("A project keeps at least one admin");
        };
    }

    private ResponseEntity<?> setRole(String key, String username, String roleName) {
        Optional<ProjectEntity> found = find(key);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectEntity project = found.get();
        if (!can(project, "odt.member.manage")) {
            return Problems.forbidden("Only an admin of the project can manage its members");
        }
        Optional<ProjectRole> role = ProjectRole.parse(roleName);
        if (role.isEmpty()) {
            return Problems.badRequest("The role is one of " + String.join(", ", ProjectRole.names()));
        }
        if (username == null || users.find(username.strip()).isEmpty()) {
            return Problems.badRequest("No user with this name in the workspace");
        }
        String member = username.strip();
        if (access.grant(project.id(), member, role.get(), user.username()) == Change.LAST_ADMIN) {
            return Problems.conflict("A project keeps at least one admin");
        }
        ProjectMember saved = access.members(project.id()).stream().filter(m -> m.username().equals(member)).findFirst().orElseThrow();
        return ResponseEntity.ok(memberView(saved, true));
    }

    private Optional<ProjectEntity> find(String key) {
        return projects.findByTenantIdAndKey(user.tenantId(), key);
    }

    private boolean can(ProjectEntity project, String permission) {
        return access.permissions(project.id(), user.username()).contains(permission);
    }

    private static Optional<String> checkName(String name, String description) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            return Optional.of("The name is required, at most 100 characters");
        }
        if (description != null && description.length() > 2000) {
            return Optional.of("The description is at most 2000 characters");
        }
        return Optional.empty();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private ProjectView view(ProjectEntity p, Optional<ProjectRole> role) {
        Set<String> permissions = role.map(ProjectRole::permissions).orElse(Set.of());
        Map<String, Boolean> perm = Map.of(
                "edit", permissions.contains("odt.project.edit"),
                "manageMembers", permissions.contains("odt.member.manage"),
                "createIssue", permissions.contains("odt.issue.create"));
        return new ProjectView(p.id(), p.key(), p.name(), p.description(), p.lead(), role.map(ProjectRole::value).orElse(null),
                p.createdAt(), p.version(), perm);
    }

    private MemberView memberView(ProjectMember m, boolean manage) {
        return new MemberView(m.username(), m.role().value(), m.grantedBy(), m.grantedAt(),
                Map.of("change", manage, "remove", manage && !m.username().equals(user.username())));
    }
}
