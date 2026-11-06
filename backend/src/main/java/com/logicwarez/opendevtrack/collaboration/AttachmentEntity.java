/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** The metadata of an attachment (ADR-14); its bytes are in the store it names, under {@link #key()}. */
@Entity
@Table(schema = "odt_collaboration", name = "attachment")
class AttachmentEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID issueId;
    private UUID projectId;
    private String filename;
    private String mediaType;
    private long size;
    private String sha256;
    private String store;
    private String uploadedBy;
    private Instant uploadedAt;

    protected AttachmentEntity() {
    }

    AttachmentEntity(UUID issueId, UUID projectId, String filename, String mediaType, long size, String store,
                     String uploadedBy, Instant uploadedAt) {
        this.issueId = issueId;
        this.projectId = projectId;
        this.filename = filename;
        this.mediaType = mediaType;
        this.size = size;
        this.sha256 = "";
        this.store = store;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    /** The key of the bytes in the store: two UUIDs, never the name of the file. */
    String key() {
        return key(projectId, id);
    }

    static String key(UUID projectId, UUID id) {
        return projectId + "/" + id;
    }

    void received(String sha256) {
        this.sha256 = sha256;
    }

    UUID id() {
        return id;
    }

    UUID issueId() {
        return issueId;
    }

    UUID projectId() {
        return projectId;
    }

    String filename() {
        return filename;
    }

    String mediaType() {
        return mediaType;
    }

    long size() {
        return size;
    }

    String sha256() {
        return sha256;
    }

    String store() {
        return store;
    }

    String uploadedBy() {
        return uploadedBy;
    }

    Instant uploadedAt() {
        return uploadedAt;
    }
}
