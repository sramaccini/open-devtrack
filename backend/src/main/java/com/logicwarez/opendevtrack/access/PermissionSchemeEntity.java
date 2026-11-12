/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A permission scheme of a tenant (ADR-22): the project permissions each project role gives. */
@Entity
@Table(schema = "odt_access", name = "permission_scheme")
class PermissionSchemeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private String name;
    private String description;

    @Column(name = "is_default")
    private boolean isDefault;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> viewer;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> developer;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> admin;

    private String updatedBy;
    private Instant updatedAt;

    @Version
    private long version;

    protected PermissionSchemeEntity() {
    }

    PermissionSchemeEntity(String tenantId, String name, String description, boolean isDefault,
                           Map<ProjectRole, Set<String>> grants, String by, Instant now) {
        this.tenantId = tenantId;
        this.isDefault = isDefault;
        edit(name, description, grants, by, now);
    }

    void edit(String name, String description, Map<ProjectRole, Set<String>> grants, String by, Instant now) {
        this.name = name;
        this.description = description;
        this.viewer = sorted(grants.get(ProjectRole.VIEWER));
        this.developer = sorted(grants.get(ProjectRole.DEVELOPER));
        this.admin = sorted(grants.get(ProjectRole.ADMIN));
        this.updatedBy = by;
        this.updatedAt = now;
    }

    private static List<String> sorted(Set<String> permissions) {
        return new ArrayList<>(new TreeSet<>(permissions));
    }

    /** What each role gives. */
    Map<ProjectRole, Set<String>> grants() {
        return Map.of(ProjectRole.VIEWER, Set.copyOf(viewer), ProjectRole.DEVELOPER, Set.copyOf(developer),
                ProjectRole.ADMIN, Set.copyOf(admin));
    }

    UUID id() {
        return id;
    }

    String tenantId() {
        return tenantId;
    }

    String name() {
        return name;
    }

    String description() {
        return description;
    }

    boolean isDefault() {
        return isDefault;
    }

    String updatedBy() {
        return updatedBy;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    long version() {
        return version;
    }
}
