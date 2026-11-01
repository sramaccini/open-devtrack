/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A custom field of a project (ADR-11); its key and type never change, so the values saved stay valid. */
@Entity
@Table(schema = "odt_issue", name = "field_definition")
class FieldDefinitionEntity {

    /** An option of a select or multiselect: the code saved in the issues and the label shown. */
    record Option(String value, String label) {
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID projectId;
    private String key;
    private String label;

    @Enumerated(EnumType.STRING)
    private FieldType type;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Option> options;

    private String requiredIf;
    private int position;
    private boolean archived;

    @Version
    @Column(name = "version")
    private long version;

    protected FieldDefinitionEntity() {
    }

    FieldDefinitionEntity(UUID projectId, String key, FieldType type, int position) {
        this.projectId = projectId;
        this.key = key;
        this.type = type;
        this.position = position;
    }

    void edit(String label, List<Option> options, String requiredIf, boolean archived) {
        this.label = label;
        this.options = options;
        this.requiredIf = requiredIf;
        this.archived = archived;
    }

    String key() {
        return key;
    }

    String label() {
        return label;
    }

    FieldType type() {
        return type;
    }

    List<Option> options() {
        return options == null ? List.of() : options;
    }

    String requiredIf() {
        return requiredIf;
    }

    boolean archived() {
        return archived;
    }

    long version() {
        return version;
    }
}
