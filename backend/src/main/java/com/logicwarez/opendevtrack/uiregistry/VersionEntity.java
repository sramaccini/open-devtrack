/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.uiregistry;

import com.logicwarez.opendevtrack.uiregistry.Model.DocumentVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A saved version of a document: the content never changes, only the review state. */
@Entity
@Table(schema = "odt_uiregistry", name = "document_version")
class VersionEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String appId;
    private String kind;
    private String documentId;
    private String hash;
    private String content;
    private String author;
    private String state;
    private String reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;
    private Instant createdAt;

    @Version
    @Column(name = "version")
    private long lock;

    protected VersionEntity() {
    }

    VersionEntity(Model.DocumentKey key, String hash, String content, String author, String state, Instant createdAt) {
        this.appId = key.appId();
        this.kind = key.kind().value();
        this.documentId = key.documentId();
        this.hash = hash;
        this.content = content;
        this.author = author;
        this.state = state;
        this.createdAt = createdAt;
    }

    String hash() {
        return hash;
    }

    String content() {
        return content;
    }

    DocumentVersion toVersion() {
        return new DocumentVersion(hash, createdAt, author, state, reviewedBy, reviewedAt, reviewNote);
    }

    void review(DocumentVersion reviewed) {
        this.state = reviewed.state();
        this.reviewedBy = reviewed.reviewedBy();
        this.reviewedAt = reviewed.reviewedAt();
        this.reviewNote = reviewed.reviewNote();
    }
}
