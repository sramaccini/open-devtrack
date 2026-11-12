/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.identity.TenantUsers;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * The groups of the tenant (ADR-22): every user of the projects reads them (the admin of a project adds one to it),
 * the admin of the tenant ({@value ProjectAccess#TENANT_ADMIN}) adds, changes and removes them and their members. A
 * change that would leave a project without an admin is refused (409): removing the group, or the last admin of a
 * project from a group that is its only admin.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class GroupController {

    private static final String GROUPS = "/api/groups";
    private static final String ADMIN = "hasAuthority('" + ProjectAccess.TENANT_ADMIN + "')";
    private static final String NOT_FOUND = "Group not found";
    private static final String LAST_ADMIN = "A project keeps at least one admin: give it another one first";
    private static final int MAX_NAME = 100;
    private static final int MAX_DESCRIPTION = 500;

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final ProjectGroupRepository projectGroups;
    private final ProjectAccess access;
    private final TenantUsers users;
    private final CurrentUser user;

    GroupController(GroupRepository groups, GroupMemberRepository members, ProjectGroupRepository projectGroups,
                    ProjectAccess access, TenantUsers users, CurrentUser user) {
        this.groups = groups;
        this.members = members;
        this.projectGroups = projectGroups;
        this.access = access;
        this.users = users;
        this.user = user;
    }

    /** A group, how many members it has and in how many projects it is. */
    record GroupView(UUID id, String name, String description, long members, long projects, long version,
                     @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A new group, or a change: name, description, and the version read for a change. */
    record GroupRequest(String name, String description, Long version) {
    }

    /** A user in a group. */
    record GroupMemberView(String username, String addedBy, Instant addedAt,
                           @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A user to add to a group. */
    record GroupMemberRequest(String username) {
    }

    @ApiDoc(value = "The groups of the workspace; q keeps those whose name contains it", answer = GroupView.class,
            list = true)
    @GetMapping(GROUPS)
    List<GroupView> list(@RequestParam(required = false) String q) {
        String text = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return groups.findByTenantIdOrderByNameAsc(user.tenantId()).stream()
                .filter(g -> g.name().toLowerCase(Locale.ROOT).contains(text))
                .map(this::view).toList();
    }

    @ApiDoc(value = "A group", answer = GroupView.class)
    @GetMapping(GROUPS + "/{id}")
    ResponseEntity<?> get(@PathVariable UUID id) {
        return find(id).<ResponseEntity<?>>map(g -> ResponseEntity.ok(view(g)))
                .orElseGet(() -> Problems.notFound(NOT_FOUND));
    }

    @ApiDoc(value = "Adds a group", answer = GroupView.class, status = 201)
    @PostMapping(GROUPS)
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> add(@RequestBody GroupRequest request) {
        Optional<ResponseEntity<?>> wrong = wrong(request, null);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        GroupEntity added = groups.saveAndFlush(new GroupEntity(user.tenantId(), request.name().strip(),
                blankToNull(request.description()), user.username(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(added));
    }

    @ApiDoc(value = "Changes the name and the description of a group", answer = GroupView.class)
    @PutMapping(GROUPS + "/{id}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody GroupRequest request) {
        Optional<GroupEntity> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Optional<ResponseEntity<?>> wrong = wrong(request, id);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        if (request.version() == null || request.version() != found.get().version()) {
            return Problems.changedMeanwhile();
        }
        found.get().edit(request.name().strip(), blankToNull(request.description()));
        return ResponseEntity.ok(view(groups.saveAndFlush(found.get())));
    }

    @ApiDoc(value = "Removes a group and its places in the projects", status = 204)
    @DeleteMapping(GROUPS + "/{id}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> remove(@PathVariable UUID id) {
        Optional<GroupEntity> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!access.projectsKeepAdmin(id, roles -> roles.groupRoles.remove(id))) {
            return Problems.conflict(LAST_ADMIN);
        }
        groups.delete(found.get());
        return ResponseEntity.noContent().build();
    }

    @ApiDoc(value = "The members of a group", answer = GroupMemberView.class, list = true)
    @GetMapping(GROUPS + "/{id}/members")
    ResponseEntity<?> members(@PathVariable UUID id) {
        if (find(id).isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        boolean manage = user.can(ProjectAccess.TENANT_ADMIN);
        return ResponseEntity.ok(members.findByGroupIdOrderByUsername(id).stream()
                .map(m -> new GroupMemberView(m.username(), m.addedBy(), m.addedAt(), Map.of("remove", manage)))
                .toList());
    }

    @ApiDoc(value = "Adds a user of the workspace to a group", answer = GroupMemberView.class, status = 201)
    @PostMapping(GROUPS + "/{id}/members")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> addMember(@PathVariable UUID id, @RequestBody GroupMemberRequest request) {
        if (find(id).isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (request == null || request.username() == null || users.find(request.username().strip()).isEmpty()) {
            return Problems.badRequest("No user with this name in the workspace");
        }
        String username = request.username().strip();
        if (members.existsById(new GroupMemberEntity.Key(id, username))) {
            return Problems.conflict("Already in the group");
        }
        GroupMemberEntity added = members.save(new GroupMemberEntity(id, username, user.username(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new GroupMemberView(added.username(), added.addedBy(), added.addedAt(), Map.of("remove", true)));
    }

    @ApiDoc(value = "Removes a user from a group", status = 204)
    @DeleteMapping(GROUPS + "/{id}/members/{username}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> removeMember(@PathVariable UUID id, @PathVariable String username) {
        if (find(id).isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        GroupMemberEntity.Key key = new GroupMemberEntity.Key(id, username);
        if (!members.existsById(key)) {
            return Problems.notFound("Not in the group");
        }
        if (!access.projectsKeepAdmin(id, roles -> roles.groupMembers.computeIfPresent(id, (group, names) -> {
            names.remove(username);
            return names;
        }))) {
            return Problems.conflict(LAST_ADMIN);
        }
        members.deleteById(key);
        return ResponseEntity.noContent().build();
    }

    private Optional<GroupEntity> find(UUID id) {
        return groups.findByIdAndTenantId(id, user.tenantId());
    }

    private Optional<ResponseEntity<?>> wrong(GroupRequest request, UUID except) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.name().strip().length() > MAX_NAME) {
            return Optional.of(Problems.badRequest("The name is required, at most " + MAX_NAME + " characters"));
        }
        if (request.description() != null && request.description().strip().length() > MAX_DESCRIPTION) {
            return Optional.of(Problems.badRequest("The description is at most " + MAX_DESCRIPTION + " characters"));
        }
        if (groups.nameTaken(user.tenantId(), request.name().strip(), except)) {
            return Optional.of(Problems.conflict("A group with this name already exists"));
        }
        return Optional.empty();
    }

    private GroupView view(GroupEntity g) {
        boolean admin = user.can(ProjectAccess.TENANT_ADMIN);
        return new GroupView(g.id(), g.name(), g.description(), members.countByGroupId(g.id()),
                projectGroups.findByGroupId(g.id()).size(), g.version(), Map.of("edit", admin, "remove", admin));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
