/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A board of a project (ADR-15): {@code scrum} shows the issues of the active sprint, {@code kanban} every issue; the
 * lanes are by {@code none}, {@code assignee}, {@code priority} or {@code type}. Without columns of its own it has one
 * column per status of the workflow, so it follows the workflow as it changes. An ODQL filter (ADR-16) keeps only the
 * issues it finds.
 */
@Entity
@Table(schema = "odt_agile", name = "board")
class BoardEntity {

    static final List<String> KINDS = List.of("kanban", "scrum");
    static final List<String> LANES = List.of("none", "assignee", "priority", "type");

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID projectId;
    private String name;
    private String kind;
    private String lane;
    private String filter;
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "odt_agile", name = "board_column", joinColumns = @JoinColumn(name = "board_id"))
    @OrderColumn(name = "position")
    private List<BoardColumn> columns = new ArrayList<>();

    @Version
    @Column(name = "version")
    private long version;

    /** A column: its name, the statuses whose issues it holds, the most cards it should hold (none: no limit). */
    @Embeddable
    record BoardColumn(String name, @JdbcTypeCode(SqlTypes.ARRAY) List<String> statuses, Integer wipLimit) {
    }

    protected BoardEntity() {
    }

    BoardEntity(UUID projectId, String name, String kind, String lane, List<BoardColumn> columns, String filter,
                Instant createdAt) {
        this.projectId = projectId;
        this.createdAt = createdAt;
        edit(name, kind, lane, columns, filter);
    }

    void edit(String name, String kind, String lane, List<BoardColumn> columns, String filter) {
        this.name = name;
        this.kind = kind;
        this.lane = lane;
        this.filter = filter;
        this.columns.clear();
        this.columns.addAll(columns);
    }

    UUID id() {
        return id;
    }

    UUID projectId() {
        return projectId;
    }

    String name() {
        return name;
    }

    String kind() {
        return kind;
    }

    String lane() {
        return lane;
    }

    /** The ODQL filter, or null. */
    String filter() {
        return filter;
    }

    List<BoardColumn> columns() {
        return List.copyOf(columns);
    }

    long version() {
        return version;
    }
}
