/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.uiregistry;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** An environment pointing at a version of a document. */
@Entity
@Table(schema = "odt_uiregistry", name = "publication")
class PublicationEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String appId;
    private String kind;
    private String documentId;
    private String environment;
    private String hash;
    private Instant publishedAt;

    protected PublicationEntity() {
    }

    PublicationEntity(Model.DocumentKey key, String environment) {
        this.appId = key.appId();
        this.kind = key.kind().value();
        this.documentId = key.documentId();
        this.environment = environment;
    }

    String environment() {
        return environment;
    }

    String hash() {
        return hash;
    }

    void point(String hash) {
        this.hash = hash;
        this.publishedAt = Instant.now();
    }
}
