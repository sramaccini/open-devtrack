/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One event for one webhook, in the outbox (ADR-21, B11): posted until a 2xx, else again after each of
 * {@link #RETRIES}, then given up. Its id is the {@code webhook-id} of every attempt and the {@code id} of the payload,
 * so a receiver drops what it already has.
 */
@Entity
@Table(schema = "odt_integration", name = "delivery")
class DeliveryEntity {

    static final String PENDING = "pending";
    static final String DELIVERED = "delivered";
    static final String FAILED = "failed";

    /** The waits after the first attempt and each retry: seven attempts in all, over about 21 hours. */
    static final List<Duration> RETRIES = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(12));

    private static final int MAX_ERROR = 300;

    @Id
    private UUID id;

    private UUID webhookId;
    private String event;
    private String payload;
    private String status;
    private int attempts;
    private Instant nextAt;
    private Integer lastCode;
    private String lastError;
    private Instant lastAt;
    private Instant createdAt;

    protected DeliveryEntity() {
    }

    DeliveryEntity(UUID id, UUID webhookId, String event, String payload, Instant createdAt) {
        this.id = id;
        this.webhookId = webhookId;
        this.event = event;
        this.payload = payload;
        this.status = PENDING;
        this.nextAt = createdAt;
        this.createdAt = createdAt;
    }

    void delivered(int code, Instant now) {
        attempts++;
        status = DELIVERED;
        nextAt = null;
        lastCode = code;
        lastError = null;
        lastAt = now;
    }

    /** An attempt that failed; true when it was the last one and the delivery is given up. */
    boolean failed(Integer code, String error, Instant now) {
        attempts++;
        lastCode = code;
        lastError = error == null ? null : error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error;
        lastAt = now;
        if (attempts > RETRIES.size()) {
            status = FAILED;
            nextAt = null;
            return true;
        }
        nextAt = now.plus(RETRIES.get(attempts - 1));
        return false;
    }

    /** Not sent: the webhook is off or gone. */
    void dropped(String reason, Instant now) {
        status = FAILED;
        nextAt = null;
        lastError = reason;
        lastAt = now;
    }

    /** «Deliver again» (B13): the same delivery, with its id and body, from the first attempt. */
    void again(Instant now) {
        status = PENDING;
        attempts = 0;
        nextAt = now;
    }

    UUID id() {
        return id;
    }

    UUID webhookId() {
        return webhookId;
    }

    String event() {
        return event;
    }

    String payload() {
        return payload;
    }

    String status() {
        return status;
    }

    int attempts() {
        return attempts;
    }

    Instant nextAt() {
        return nextAt;
    }

    Integer lastCode() {
        return lastCode;
    }

    String lastError() {
        return lastError;
    }

    Instant lastAt() {
        return lastAt;
    }

    Instant createdAt() {
        return createdAt;
    }
}
