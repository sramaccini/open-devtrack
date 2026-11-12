/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import com.logicwarez.opendevtrack.platform.CurrentUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project roles: who has which role in a project, and the permissions it gives under the permission scheme of the
 * project ({@link PermissionSchemes}, ADR-22). The project is only an identifier: the module {@code project} checks the
 * tenant before calling here (ADR-09), and the scheme is looked for in the tenant of the session.
 *
 * <p>A user has a role of their own, or one through the groups that are members of the project, or both: the highest
 * counts (ADR-22). A project always keeps one admin, counting those through groups.
 *
 * <p>These permissions reach the screens as row permissions ({@code _perm}) in the answers of the modules, and the
 * checks of {@code /authz/check} with a project or an issue through {@code project.ProjectPermissions} (A1); they are
 * not in {@code /authz/capabilities}, since they depend on the project.
 *
 * <p>The admin of the tenant (global permission {@value #TENANT_ADMIN}) holds the role admin in every project of the
 * tenant, member or not (ADR-07).
 */
@Service
@Transactional(readOnly = true)
public class ProjectAccess {

    /** Outcome of a change of role or of a removal. */
    public enum Change { DONE, NOT_A_MEMBER, LAST_ADMIN }

    /** Global permission of the admin of the tenant. */
    public static final String TENANT_ADMIN = "odt.project.admin";

    private final MemberRepository members;
    private final ProjectGroupRepository projectGroups;
    private final GroupMemberRepository groupMembers;
    private final GroupRepository groups;
    private final PermissionSchemes schemes;
    private final CurrentUser user;

    ProjectAccess(MemberRepository members, ProjectGroupRepository projectGroups, GroupMemberRepository groupMembers,
                  GroupRepository groups, PermissionSchemes schemes, CurrentUser user) {
        this.members = members;
        this.projectGroups = projectGroups;
        this.groupMembers = groupMembers;
        this.groups = groups;
        this.schemes = schemes;
        this.user = user;
    }

    /** The role of the user in the project: the highest of their own and those of their groups. */
    public Optional<ProjectRole> role(UUID projectId, String username) {
        return Optional.ofNullable(roles(List.of(projectId), username).get(projectId));
    }

    /**
     * Permissions of the user in the project; empty for someone who is not a member. For the user of the session who is
     * admin of the tenant, those of the role admin.
     */
    public Set<String> permissions(UUID projectId, String username) {
        Optional<ProjectRole> own = role(projectId, username);
        return given(projectId, username.equals(user.username()) ? effective(own) : own);
    }

    /** Permissions of the user of the session in the project, whose own role there is already known (for lists). */
    public Set<String> permissions(UUID projectId, Optional<ProjectRole> own) {
        return given(projectId, effective(own));
    }

    private Set<String> given(UUID projectId, Optional<ProjectRole> role) {
        return role.map(r -> schemes.permissions(user.tenantId(), projectId, r)).orElse(Set.of());
    }

    /** The role that decides what the user of the session can do: admin for an admin of the tenant, else their own. */
    public Optional<ProjectRole> effective(Optional<ProjectRole> own) {
        return user.can(TENANT_ADMIN) ? Optional.of(ProjectRole.ADMIN) : own;
    }

    /**
     * Roles of the user in several projects at once, for lists: project → the highest role, only where the user is a
     * member, by themselves or through a group.
     */
    public Map<UUID, ProjectRole> roles(Collection<UUID> projectIds, String username) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProjectRole> roles = new HashMap<>();
        members.findByProjectIdInAndUsername(projectIds, username).forEach(m -> roles.merge(m.projectId(), m.role(),
                ProjectAccess::highest));
        Set<UUID> mine = groupMembers.findByUsername(username).stream().map(GroupMemberEntity::groupId)
                .collect(Collectors.toSet());
        if (!mine.isEmpty()) {
            projectGroups.findByProjectIdIn(projectIds).stream().filter(g -> mine.contains(g.groupId()))
                    .forEach(g -> roles.merge(g.projectId(), g.role(), ProjectAccess::highest));
        }
        return Map.copyOf(roles);
    }

    /** The members of the project, by name: those with a role of their own and those of its groups. */
    public List<ProjectMember> members(UUID projectId) {
        Map<String, ProjectMember> byName = new TreeMap<>();
        for (MemberEntity m : members.findByProjectIdOrderByUsername(projectId)) {
            byName.put(m.username(), m.toMember());
        }
        List<ProjectGroupEntity> granted = projectGroups.findByProjectId(projectId);
        if (!granted.isEmpty()) {
            Map<UUID, String> names = groups.findByIdIn(granted.stream().map(ProjectGroupEntity::groupId).toList())
                    .stream().collect(Collectors.toMap(GroupEntity::id, GroupEntity::name));
            Map<UUID, ProjectGroupEntity> byGroup = granted.stream()
                    .collect(Collectors.toMap(ProjectGroupEntity::groupId, g -> g));
            for (GroupMemberEntity gm : groupMembers.findByGroupIdIn(byGroup.keySet())) {
                ProjectGroupEntity g = byGroup.get(gm.groupId());
                ProjectMember known = byName.get(gm.username());
                if (known == null) {
                    byName.put(gm.username(), new ProjectMember(gm.username(), g.role(), null,
                            List.of(names.get(g.groupId())), g.grantedBy(), g.grantedAt()));
                } else {
                    List<String> via = new ArrayList<>(known.groups());
                    via.add(names.get(g.groupId()));
                    via.sort(String::compareTo);
                    byName.put(gm.username(), new ProjectMember(known.username(), highest(known.role(), g.role()),
                            known.own(), List.copyOf(via), known.grantedBy(), known.grantedAt()));
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /** Gives the user the role in the project, replacing their own; the project keeps an admin. */
    @Transactional
    public Change grant(UUID projectId, String username, ProjectRole role, String grantedBy) {
        if (!keepsAdmin(projectId, s -> s.own.put(username, role))) {
            return Change.LAST_ADMIN;
        }
        MemberEntity member = members.findByProjectIdAndUsername(projectId, username)
                .orElseGet(() -> new MemberEntity(projectId, username));
        member.grant(role, grantedBy, Instant.now());
        members.save(member);
        return Change.DONE;
    }

    /** Removes the role of the user in the project (not those through groups); the project keeps an admin. */
    @Transactional
    public Change revoke(UUID projectId, String username) {
        Optional<MemberEntity> member = members.findByProjectIdAndUsername(projectId, username);
        if (member.isEmpty()) {
            return Change.NOT_A_MEMBER;
        }
        if (!keepsAdmin(projectId, s -> s.own.remove(username))) {
            return Change.LAST_ADMIN;
        }
        members.delete(member.get());
        return Change.DONE;
    }

    /** The groups that are members of the project, with their roles. */
    public List<ProjectGroup> groups(UUID projectId) {
        List<ProjectGroupEntity> granted = projectGroups.findByProjectId(projectId);
        Map<UUID, GroupEntity> byId = groups.findByIdIn(granted.stream().map(ProjectGroupEntity::groupId).toList())
                .stream().collect(Collectors.toMap(GroupEntity::id, g -> g));
        return granted.stream()
                .map(g -> new ProjectGroup(g.groupId(), byId.get(g.groupId()).name(), g.role(), g.grantedBy(),
                        g.grantedAt()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    /**
     * Gives the group of the tenant a role in the project, replacing the one it had; the project keeps an admin. Empty
     * when the tenant has no such group.
     */
    @Transactional
    public Optional<Change> grantGroup(UUID projectId, UUID groupId, ProjectRole role, String grantedBy) {
        if (groups.findByIdAndTenantId(groupId, user.tenantId()).isEmpty()) {
            return Optional.empty();
        }
        if (!keepsAdmin(projectId, s -> s.groupRoles.put(groupId, role))) {
            return Optional.of(Change.LAST_ADMIN);
        }
        ProjectGroupEntity granted = projectGroups.findByProjectIdAndGroupId(projectId, groupId)
                .orElseGet(() -> new ProjectGroupEntity(projectId, groupId));
        granted.grant(role, grantedBy, Instant.now());
        projectGroups.save(granted);
        return Optional.of(Change.DONE);
    }

    /** Removes the group from the project; the project keeps an admin. */
    @Transactional
    public Change revokeGroup(UUID projectId, UUID groupId) {
        Optional<ProjectGroupEntity> granted = projectGroups.findByProjectIdAndGroupId(projectId, groupId);
        if (granted.isEmpty()) {
            return Change.NOT_A_MEMBER;
        }
        if (!keepsAdmin(projectId, s -> s.groupRoles.remove(groupId))) {
            return Change.LAST_ADMIN;
        }
        projectGroups.delete(granted.get());
        return Change.DONE;
    }

    /** Whether every project of the group keeps an admin after the change of the group. */
    boolean projectsKeepAdmin(UUID groupId, Consumer<Roles> change) {
        return projectGroups.findByGroupId(groupId).stream().allMatch(g -> keepsAdmin(g.projectId(), change));
    }

    /** Who holds which role in a project, by themselves and through groups: to try a change before making it. */
    static final class Roles {

        final Map<String, ProjectRole> own = new HashMap<>();
        final Map<UUID, ProjectRole> groupRoles = new HashMap<>();
        final Map<UUID, Set<String>> groupMembers = new HashMap<>();

        boolean hasAdmin() {
            return own.containsValue(ProjectRole.ADMIN) || groupRoles.entrySet().stream()
                    .anyMatch(g -> g.getValue() == ProjectRole.ADMIN && !groupMembers.getOrDefault(g.getKey(), Set.of()).isEmpty());
        }
    }

    private boolean keepsAdmin(UUID projectId, Consumer<Roles> change) {
        Roles roles = new Roles();
        members.findByProjectIdOrderByUsername(projectId).forEach(m -> roles.own.put(m.username(), m.role()));
        projectGroups.findByProjectId(projectId).forEach(g -> roles.groupRoles.put(g.groupId(), g.role()));
        groupMembers.findByGroupIdIn(roles.groupRoles.keySet()).forEach(gm -> roles.groupMembers
                .computeIfAbsent(gm.groupId(), id -> new HashSet<>()).add(gm.username()));
        // A project with no admin (which the checks never leave) does not block the changes
        boolean before = roles.hasAdmin();
        change.accept(roles);
        return !before || roles.hasAdmin();
    }

    private static ProjectRole highest(ProjectRole a, ProjectRole b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
