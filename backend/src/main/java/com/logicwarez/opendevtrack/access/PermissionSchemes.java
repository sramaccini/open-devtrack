/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permission schemes (ADR-22): which project permissions each project role gives in a project. A project uses the
 * scheme picked for it, else the default scheme of its tenant. What each project gets is kept in memory and dropped
 * whenever a scheme or the choice of a project changes.
 */
@Service
@Transactional(readOnly = true)
public class PermissionSchemes {

    /** The project permissions of the catalogue: the only ones a scheme can hold. */
    public static final Set<String> CATALOGUE = ProjectRole.ADMIN.permissions();

    /** The scheme of a project. */
    public record SchemeRef(UUID id, String name) {
    }

    private record Resolved(SchemeRef scheme, Map<ProjectRole, Set<String>> grants) {
    }

    private final PermissionSchemeRepository schemes;
    private final ProjectSchemeRepository projectSchemes;
    private final DefaultSchemes defaults;
    private final Map<UUID, Resolved> byProject = new ConcurrentHashMap<>();

    PermissionSchemes(PermissionSchemeRepository schemes, ProjectSchemeRepository projectSchemes,
                      DefaultSchemes defaults) {
        this.schemes = schemes;
        this.projectSchemes = projectSchemes;
        this.defaults = defaults;
    }

    /** The permissions the role gives in the project of the tenant. */
    public Set<String> permissions(String tenantId, UUID projectId, ProjectRole role) {
        return resolve(tenantId, projectId).grants().get(role);
    }

    /** The scheme the project uses. */
    public SchemeRef scheme(String tenantId, UUID projectId) {
        return resolve(tenantId, projectId).scheme();
    }

    /**
     * Makes the project use the scheme of the tenant; the default one drops the choice. Empty when the tenant has no such
     * scheme.
     */
    @Transactional
    public Optional<SchemeRef> use(String tenantId, UUID projectId, UUID schemeId) {
        Optional<PermissionSchemeEntity> scheme = schemes.findByIdAndTenantId(schemeId, tenantId);
        if (scheme.isEmpty()) {
            return Optional.empty();
        }
        if (scheme.get().isDefault()) {
            projectSchemes.deleteById(projectId);
        } else {
            ProjectSchemeEntity chosen = projectSchemes.findById(projectId).orElseGet(() ->
                    new ProjectSchemeEntity(projectId, schemeId));
            chosen.use(schemeId);
            projectSchemes.save(chosen);
        }
        changed();
        return Optional.of(new SchemeRef(scheme.get().id(), scheme.get().name()));
    }

    /** Why the sets cannot make a scheme, or empty: only permissions of the catalogue, and the rules of ADR-22. */
    public static Optional<String> wrong(Map<ProjectRole, Set<String>> grants) {
        for (ProjectRole role : ProjectRole.values()) {
            Set<String> given = grants.get(role);
            if (given == null) {
                return Optional.of("The permissions of the role " + role.value() + " are missing");
            }
            Optional<String> unknown = given.stream().filter(p -> !CATALOGUE.contains(p)).findFirst();
            if (unknown.isPresent()) {
                return Optional.of(unknown.get() + " is not a project permission");
            }
            if (!given.contains("odt.issue.browse")) {
                return Optional.of("Every role sees the issues: odt.issue.browse is missing from " + role.value());
            }
        }
        Set<String> admin = grants.get(ProjectRole.ADMIN);
        if (!admin.contains("odt.project.edit") || !admin.contains("odt.member.manage")) {
            return Optional.of("The role admin always edits the project and manages its members");
        }
        return Optional.empty();
    }

    /** The default scheme of the tenant, made the first time. */
    PermissionSchemeEntity defaultOf(String tenantId) {
        return schemes.findByTenantIdAndIsDefaultTrue(tenantId).orElseGet(() -> {
            try {
                return defaults.make(tenantId);
            } catch (DataIntegrityViolationException meanwhile) {
                return schemes.findByTenantIdAndIsDefaultTrue(tenantId).orElseThrow(() -> meanwhile);
            }
        });
    }

    boolean inUse(UUID schemeId) {
        return projectSchemes.countBySchemeId(schemeId) > 0;
    }

    /** A scheme or the choice of a project changed: what the projects get is read again. */
    void changed() {
        byProject.clear();
    }

    private Resolved resolve(String tenantId, UUID projectId) {
        Resolved known = byProject.get(projectId);
        if (known != null) {
            return known;
        }
        PermissionSchemeEntity scheme = projectSchemes.findById(projectId)
                .flatMap(chosen -> schemes.findByIdAndTenantId(chosen.schemeId(), tenantId))
                .orElseGet(() -> defaultOf(tenantId));
        Resolved resolved = new Resolved(new SchemeRef(scheme.id(), scheme.name()), scheme.grants());
        byProject.put(projectId, resolved);
        return resolved;
    }
}
