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

/** A user in a group. */
@Entity
@Table(schema = "odt_access", name = "group_member")
@IdClass(GroupMemberEntity.Key.class)
class GroupMemberEntity {

    record Key(UUID groupId, String username) implements Serializable {
    }

    @Id
    private UUID groupId;

    @Id
    private String username;

    private String addedBy;
    private Instant addedAt;

    protected GroupMemberEntity() {
    }

    GroupMemberEntity(UUID groupId, String username, String addedBy, Instant addedAt) {
        this.groupId = groupId;
        this.username = username;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    UUID groupId() {
        return groupId;
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
