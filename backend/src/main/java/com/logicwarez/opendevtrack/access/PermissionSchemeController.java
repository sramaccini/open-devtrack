/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import static com.logicwarez.opendevtrack.shared.ConfigurationChanged.values;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.platform.AdminLog;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The permission schemes of the tenant (ADR-22): every user of the projects reads them (the admin of a project picks
 * one), the admin of the tenant ({@value ProjectAccess#TENANT_ADMIN}) adds, changes and removes them. A new scheme is a
 * copy of the default one. The default scheme cannot be removed, nor a scheme that a project uses (409).
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class PermissionSchemeController {

    private static final String SCHEMES = "/api/permission-schemes";
    private static final String NOT_FOUND = "Permission scheme not found";
    private static final String ADMIN = "hasAuthority('" + ProjectAccess.TENANT_ADMIN + "')";
    private static final int MAX_NAME = 100;
    private static final int MAX_DESCRIPTION = 500;

    private final PermissionSchemeRepository repository;
    private final PermissionSchemes schemes;
    private final CurrentUser user;
    private final AdminLog log;

    PermissionSchemeController(PermissionSchemeRepository repository, PermissionSchemes schemes, CurrentUser user,
                               AdminLog log) {
        this.repository = repository;
        this.schemes = schemes;
        this.user = user;
        this.log = log;
    }

    /** A scheme: what each project role gives, and whether projects use it. */
    record SchemeView(UUID id, String name, String description, @JsonProperty("default") boolean isDefault,
                      List<String> viewer, List<String> developer, List<String> admin, boolean inUse, String updatedBy,
                      Instant updatedAt, long version, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A new scheme (name, description), or a change: also the permissions of each role, and the version read. */
    record SchemeRequest(String name, String description, List<String> viewer, List<String> developer,
                         List<String> admin, Long version) {
    }

    @ApiDoc(value = "The permission schemes of the workspace, the default first", answer = SchemeView.class, list = true)
    @GetMapping(SCHEMES)
    List<SchemeView> list() {
        schemes.defaultOf(user.tenantId());
        return repository.findByTenantIdOrderByIsDefaultDescNameAsc(user.tenantId()).stream().map(this::view).toList();
    }

    @ApiDoc(value = "A permission scheme", answer = SchemeView.class)
    @GetMapping(SCHEMES + "/{id}")
    ResponseEntity<?> get(@PathVariable UUID id) {
        return repository.findByIdAndTenantId(id, user.tenantId()).<ResponseEntity<?>>map(s -> ResponseEntity.ok(view(s)))
                .orElseGet(() -> Problems.notFound(NOT_FOUND));
    }

    @ApiDoc(value = "Adds a permission scheme, a copy of the default", answer = SchemeView.class, status = 201)
    @PostMapping(SCHEMES)
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> add(@RequestBody SchemeRequest request) {
        Optional<ResponseEntity<?>> wrong = wrongText(request, null);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        PermissionSchemeEntity source = schemes.defaultOf(user.tenantId());
        PermissionSchemeEntity added = repository.saveAndFlush(new PermissionSchemeEntity(user.tenantId(),
                request.name().strip(), blankToNull(request.description()), false, source.grants(), user.username(),
                Instant.now()));
        log.tenant("scheme.created", added.name(), null, logged(added));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(added));
    }

    @ApiDoc(value = "Changes a permission scheme", answer = SchemeView.class)
    @PutMapping(SCHEMES + "/{id}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody SchemeRequest request) {
        Optional<PermissionSchemeEntity> found = repository.findByIdAndTenantId(id, user.tenantId());
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Optional<ResponseEntity<?>> wrong = wrongText(request, id);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        Map<ProjectRole, Set<String>> grants = new EnumMap<>(ProjectRole.class);
        put(grants, ProjectRole.VIEWER, request.viewer());
        put(grants, ProjectRole.DEVELOPER, request.developer());
        put(grants, ProjectRole.ADMIN, request.admin());
        Optional<String> invalid = PermissionSchemes.wrong(grants);
        if (invalid.isPresent()) {
            return Problems.badRequest(invalid.get());
        }
        if (request.version() == null || request.version() != found.get().version()) {
            return Problems.changedMeanwhile();
        }
        var before = logged(found.get());
        found.get().edit(request.name().strip(), blankToNull(request.description()), grants, user.username(),
                Instant.now());
        PermissionSchemeEntity saved = repository.saveAndFlush(found.get());
        schemes.changed();
        log.tenant("scheme.changed", saved.name(), before, logged(saved));
        return ResponseEntity.ok(view(saved));
    }

    @ApiDoc(value = "Removes a permission scheme no project uses", status = 204)
    @DeleteMapping(SCHEMES + "/{id}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> remove(@PathVariable UUID id) {
        Optional<PermissionSchemeEntity> found = repository.findByIdAndTenantId(id, user.tenantId());
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (found.get().isDefault()) {
            return Problems.conflict("The default scheme cannot be removed");
        }
        if (schemes.inUse(id)) {
            return Problems.conflict("Projects use this scheme: pick another one for them first");
        }
        repository.delete(found.get());
        log.tenant("scheme.removed", found.get().name(), logged(found.get()), null);
        schemes.changed();
        return ResponseEntity.noContent().build();
    }

    /** What the audit log keeps of a scheme: its name, description and the sorted permissions of each role. */
    private static Map<String, Object> logged(PermissionSchemeEntity scheme) {
        Map<ProjectRole, Set<String>> grants = scheme.grants();
        return values("name", scheme.name(), "description", scheme.description(),
                "viewer", sorted(grants.get(ProjectRole.VIEWER)), "developer", sorted(grants.get(ProjectRole.DEVELOPER)),
                "admin", sorted(grants.get(ProjectRole.ADMIN)));
    }

    private static void put(Map<ProjectRole, Set<String>> grants, ProjectRole role, List<String> permissions) {
        if (permissions != null) {
            grants.put(role, new HashSet<>(permissions));
        }
    }

    private Optional<ResponseEntity<?>> wrongText(SchemeRequest request, UUID except) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.name().strip().length() > MAX_NAME) {
            return Optional.of(Problems.badRequest("The name is required, at most " + MAX_NAME + " characters"));
        }
        if (request.description() != null && request.description().strip().length() > MAX_DESCRIPTION) {
            return Optional.of(Problems.badRequest("The description is at most " + MAX_DESCRIPTION + " characters"));
        }
        if (repository.nameTaken(user.tenantId(), request.name().strip(), except)) {
            return Optional.of(Problems.conflict("A permission scheme with this name already exists"));
        }
        return Optional.empty();
    }

    private SchemeView view(PermissionSchemeEntity s) {
        Map<ProjectRole, Set<String>> grants = s.grants();
        boolean admin = user.can(ProjectAccess.TENANT_ADMIN);
        boolean inUse = schemes.inUse(s.id());
        return new SchemeView(s.id(), s.name(), s.description(), s.isDefault(), sorted(grants.get(ProjectRole.VIEWER)),
                sorted(grants.get(ProjectRole.DEVELOPER)), sorted(grants.get(ProjectRole.ADMIN)), inUse, s.updatedBy(),
                s.updatedAt(), s.version(), Map.of("edit", admin, "remove", admin && !s.isDefault() && !inUse));
    }

    private static List<String> sorted(Set<String> permissions) {
        return List.copyOf(new TreeSet<>(permissions));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
