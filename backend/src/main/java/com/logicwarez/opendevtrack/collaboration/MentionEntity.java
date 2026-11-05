/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/** A member of the project mentioned in a comment (ADR-13). */
@Entity
@Table(schema = "odt_collaboration", name = "mention")
@IdClass(MentionEntity.Key.class)
class MentionEntity {

    @Id
    private UUID commentId;

    @Id
    private String username;

    protected MentionEntity() {
    }

    MentionEntity(UUID commentId, String username) {
        this.commentId = commentId;
        this.username = username;
    }

    String username() {
        return username;
    }

    record Key(UUID commentId, String username) implements Serializable {
    }
}
