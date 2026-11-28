/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A type of link of a tenant (ADR-29): its code, which never changes, and how each end reads it: ODT-1
 * {@code outward} ODT-2, ODT-2 {@code inward} ODT-1. A type whose two descriptions are the same reads the same from
 * both ends, as {@code relates}.
 */
@Entity
@Table(schema = "odt_issue", name = "link_type")
class LinkTypeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private String code;
    private String outward;
    private String inward;

    @Column(name = "is_system")
    private boolean system;

    private String updatedBy;
    private Instant updatedAt;

    @Version
    private long version;

    protected LinkTypeEntity() {
    }

    LinkTypeEntity(String tenantId, String code, String outward, String inward, boolean system, String updatedBy,
                   Instant updatedAt) {
        this.tenantId = tenantId;
        this.code = code;
        this.outward = outward;
        this.inward = inward;
        this.system = system;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    void describe(String outward, String inward, String updatedBy, Instant updatedAt) {
        this.outward = outward;
        this.inward = inward;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    UUID id() {
        return id;
    }

    String tenantId() {
        return tenantId;
    }

    String code() {
        return code;
    }

    String outward() {
        return outward;
    }

    String inward() {
        return inward;
    }

    boolean system() {
        return system;
    }

    /** Read the same from both ends: one relation, and a link either way is the same link. */
    boolean symmetric() {
        return outward.equals(inward);
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
