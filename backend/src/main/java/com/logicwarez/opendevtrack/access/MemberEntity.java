/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** The role of a user in a project. */
@Entity
@Table(schema = "odt_access", name = "project_member")
@IdClass(MemberEntity.Key.class)
class MemberEntity {

    record Key(UUID projectId, String username) implements Serializable {
    }

    @Id
    private UUID projectId;

    @Id
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private ProjectRole role;

    private String grantedBy;
    private Instant grantedAt;

    protected MemberEntity() {
    }

    MemberEntity(UUID projectId, String username) {
        this.projectId = projectId;
        this.username = username;
    }

    void grant(ProjectRole role, String by, Instant at) {
        this.role = role;
        this.grantedBy = by;
        this.grantedAt = at;
    }

    UUID projectId() {
        return projectId;
    }

    ProjectRole role() {
        return role;
    }

    ProjectMember toMember() {
        return new ProjectMember(username, role, grantedBy, grantedAt);
    }
}
