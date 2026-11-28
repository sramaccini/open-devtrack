/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** A user in a team. */
@Entity
@Table(schema = "odt_access", name = "team_member")
@IdClass(TeamMemberEntity.Key.class)
class TeamMemberEntity {

    record Key(UUID teamId, String username) implements Serializable {
    }

    @Id
    private UUID teamId;

    @Id
    private String username;

    private String addedBy;
    private Instant addedAt;

    protected TeamMemberEntity() {
    }

    TeamMemberEntity(UUID teamId, String username, String addedBy, Instant addedAt) {
        this.teamId = teamId;
        this.username = username;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    UUID teamId() {
        return teamId;
    }

    String username() {
        return username;
    }

    String addedBy() {
        return addedBy;
    }

    Instant addedAt() {
        return addedAt;
    }
}
