/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import com.logicwarez.opendevtrack.platform.CurrentUser;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project roles: who has which role in a project, and the permissions it gives. The project is only an identifier: the
 * module {@code project} checks the tenant before calling here (ADR-09).
 *
 * <p>Until authz-library has an extension point for the capabilities (request A1), these permissions reach the screens
 * as row permissions ({@code _perm}) in the answers of the modules, not through {@code /authz/capabilities}.
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
    private final CurrentUser user;

    ProjectAccess(MemberRepository members, CurrentUser user) {
        this.members = members;
        this.user = user;
    }

    public Optional<ProjectRole> role(UUID projectId, String username) {
        return members.findByProjectIdAndUsername(projectId, username).map(MemberEntity::role);
    }

    /**
     * Permissions of the user in the project; empty for someone who is not a member. For the user of the session who is
     * admin of the tenant, those of the role admin.
     */
    public Set<String> permissions(UUID projectId, String username) {
        Optional<ProjectRole> own = role(projectId, username);
        return (username.equals(user.username()) ? effective(own) : own).map(ProjectRole::permissions).orElse(Set.of());
    }

    /** The role that decides what the user of the session can do: admin for an admin of the tenant, else their own. */
    public Optional<ProjectRole> effective(Optional<ProjectRole> own) {
        return user.can(TENANT_ADMIN) ? Optional.of(ProjectRole.ADMIN) : own;
    }

    /** Roles of the user in several projects at once, for lists: project → role, only where the user is a member. */
    public Map<UUID, ProjectRole> roles(Collection<UUID> projectIds, String username) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return members.findByProjectIdInAndUsername(projectIds, username).stream()
                .collect(Collectors.toUnmodifiableMap(MemberEntity::projectId, MemberEntity::role));
    }

    public List<ProjectMember> members(UUID projectId) {
        return members.findByProjectIdOrderByUsername(projectId).stream().map(MemberEntity::toMember).toList();
    }

    /** Gives the user the role in the project, replacing the one they had; the last admin keeps the role. */
    @Transactional
    public Change grant(UUID projectId, String username, ProjectRole role, String grantedBy) {
        MemberEntity member = members.findByProjectIdAndUsername(projectId, username)
                .orElseGet(() -> new MemberEntity(projectId, username));
        if (member.role() == ProjectRole.ADMIN && role != ProjectRole.ADMIN && lastAdmin(projectId)) {
            return Change.LAST_ADMIN;
        }
        member.grant(role, grantedBy, Instant.now());
        members.save(member);
        return Change.DONE;
    }

    /** Removes the user from the project; a project always keeps one admin. */
    @Transactional
    public Change revoke(UUID projectId, String username) {
        Optional<MemberEntity> member = members.findByProjectIdAndUsername(projectId, username);
        if (member.isEmpty()) {
            return Change.NOT_A_MEMBER;
        }
        if (member.get().role() == ProjectRole.ADMIN && lastAdmin(projectId)) {
            return Change.LAST_ADMIN;
        }
        members.delete(member.get());
        return Change.DONE;
    }

    private boolean lastAdmin(UUID projectId) {
        return members.countByProjectIdAndRole(projectId, ProjectRole.ADMIN) == 1;
    }
}
