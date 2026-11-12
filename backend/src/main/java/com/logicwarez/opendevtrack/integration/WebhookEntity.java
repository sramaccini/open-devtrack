/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A webhook of a project (ADR-21, B7): where to post which events, and the secret that signs them. */
@Entity
@Table(schema = "odt_integration", name = "webhook")
class WebhookEntity {

    /** After a rotation the previous secret also signs for this long (B10). */
    static final Duration OVERLAP = Duration.ofHours(24);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String tenantId;
    private UUID projectId;
    private String name;
    private String url;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> events;

    private String secret;
    private String previousSecret;
    private Instant previousUntil;
    private boolean active;
    private Instant failingSince;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;

    protected WebhookEntity() {
    }

    WebhookEntity(String tenantId, UUID projectId, String name, String url, List<String> events, boolean active,
                  String secret, String createdBy, Instant now) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.name = name;
        this.url = url;
        this.events = new ArrayList<>(events);
        this.active = active;
        this.secret = secret;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void edit(String name, String url, List<String> events, boolean active, Instant now) {
        this.name = name;
        this.url = url;
        this.events = new ArrayList<>(events);
        if (active && !this.active) {
            // Turned on again by hand: the failures before do not count
            this.failingSince = null;
        }
        this.active = active;
        this.updatedAt = now;
    }

    /** A new secret; the one before keeps signing for {@link #OVERLAP}, so the receiver can switch without a gap. */
    void rotate(String secret, Instant now) {
        this.previousSecret = this.secret;
        this.previousUntil = now.plus(OVERLAP);
        this.secret = secret;
        this.updatedAt = now;
    }

    /** The secrets that sign at {@code now}: the current one, and the previous one during the overlap. */
    List<String> signingSecrets(Instant now) {
        List<String> secrets = new ArrayList<>(List.of(secret));
        if (previousSecret != null && previousUntil != null && now.isBefore(previousUntil)) {
            secrets.add(previousSecret);
        }
        return secrets;
    }

    boolean wants(String event) {
        return events.contains(Events.ALL) || events.contains(event);
    }

    /** A delivery got a 2xx: the run of failures, if any, is over. */
    void delivered() {
        this.failingSince = null;
    }

    /**
     * A delivery was given up: the run of failures starts with the event of the first one given up. Returns true when
     * it has lasted {@code limit} and the webhook is turned off now.
     */
    boolean givenUp(Instant eventAt, Instant now, Duration limit) {
        if (failingSince == null) {
            failingSince = eventAt;
        }
        if (active && !now.isBefore(failingSince.plus(limit))) {
            active = false;
            updatedAt = now;
            return true;
        }
        return false;
    }

    UUID id() {
        return id;
    }

    String tenantId() {
        return tenantId;
    }

    UUID projectId() {
        return projectId;
    }

    String name() {
        return name;
    }

    String url() {
        return url;
    }

    List<String> events() {
        return events;
    }

    boolean active() {
        return active;
    }

    Instant failingSince() {
        return failingSince;
    }

    Instant createdAt() {
        return createdAt;
    }

    long version() {
        return version;
    }
}
