/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A sprint of a project (ADR-15): planned, active (at most one per project), completed. */
@Entity
@Table(schema = "odt_agile", name = "sprint")
class SprintEntity {

    static final String PLANNED = "planned";
    static final String ACTIVE = "active";
    static final String COMPLETED = "completed";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID projectId;
    private String name;
    private String goal;
    private String state;
    private LocalDate startsOn;
    private LocalDate endsOn;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected SprintEntity() {
    }

    SprintEntity(UUID projectId, String name, String goal, LocalDate startsOn, LocalDate endsOn, Instant createdAt) {
        this.projectId = projectId;
        this.state = PLANNED;
        this.createdAt = createdAt;
        edit(name, goal, startsOn, endsOn);
    }

    void edit(String name, String goal, LocalDate startsOn, LocalDate endsOn) {
        this.name = name;
        this.goal = goal;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    void start(LocalDate startsOn, LocalDate endsOn, Instant now) {
        this.state = ACTIVE;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.startedAt = now;
    }

    void complete(Instant now) {
        this.state = COMPLETED;
        this.completedAt = now;
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

    String goal() {
        return goal;
    }

    String state() {
        return state;
    }

    boolean is(String state) {
        return this.state.equals(state);
    }

    LocalDate startsOn() {
        return startsOn;
    }

    LocalDate endsOn() {
        return endsOn;
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant completedAt() {
        return completedAt;
    }

    long version() {
        return version;
    }
}
