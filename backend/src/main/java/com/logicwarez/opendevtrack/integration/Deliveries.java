/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import com.logicwarez.opendevtrack.platform.SecretBox;
import com.logicwarez.opendevtrack.shared.WebhookTurnedOff;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts the deliveries of the outbox (ADR-21, B11), each in a transaction that holds its row, so two instances never
 * send the same one:
 *
 * <ul>
 *   <li>a {@code POST} of the payload with the headers of Standard Webhooks, a timeout of 10 s, no redirect followed;</li>
 *   <li>a 2xx is delivered; anything else is tried again after the waits of {@link DeliveryEntity#RETRIES}, then
 *   given up;</li>
 *   <li>after {@link #TURN_OFF_AFTER} of deliveries given up in a row the webhook is turned off and its project told
 *   ({@link WebhookTurnedOff}, a message to the admins);</li>
 *   <li>the address is checked again before each attempt ({@link Addresses}), since the DNS can change.</li>
 * </ul>
 * Deliveries are kept {@link #KEEP} days.
 */
@Component
class Deliveries {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final Duration TURN_OFF_AFTER = Duration.ofDays(3);
    static final int KEEP = 30;
    static final int BATCH = 50;

    private static final Logger log = LoggerFactory.getLogger(Deliveries.class);

    private final DeliveryRepository deliveries;
    private final WebhookRepository webhooks;
    private final Addresses addresses;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final HttpClient http;

    private final SecretBox secrets;

    Deliveries(DeliveryRepository deliveries, WebhookRepository webhooks, Addresses addresses,
               ApplicationEventPublisher events, TransactionTemplate transactions, SecretBox secrets) {
        this.deliveries = deliveries;
        this.webhooks = webhooks;
        this.addresses = addresses;
        this.events = events;
        this.transactions = transactions;
        this.secrets = secrets;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Scheduled(fixedDelayString = "${odt.webhooks.delay:PT15S}")
    void scheduled() {
        deliver(Instant.now());
    }

    /** Posts what is due at {@code now}; the number of deliveries tried. */
    int deliver(Instant now) {
        int tried = 0;
        for (UUID id : deliveries.due(now, BATCH)) {
            if (Boolean.TRUE.equals(transactions.execute(status -> attempt(id, now)))) {
                tried++;
            }
        }
        return tried;
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void retention() {
        int removed = deliveries.deleteCreatedBefore(Instant.now().minus(KEEP, ChronoUnit.DAYS));
        if (removed > 0) {
            log.info("{} webhook deliveries older than {} days removed", removed, KEEP);
        }
    }

    private boolean attempt(UUID id, Instant now) {
        Optional<DeliveryEntity> claimed = deliveries.claim(id, now);
        if (claimed.isEmpty()) {
            return false;
        }
        DeliveryEntity delivery = claimed.get();
        Optional<WebhookEntity> found = webhooks.findById(delivery.webhookId());
        if (found.isEmpty() || !found.get().active() && !Events.PING.equals(delivery.event())) {
            delivery.dropped("The webhook is off", now);
            return true;
        }
        WebhookEntity webhook = found.get();
        Optional<String> refused = addresses.refuse(webhook.url());
        if (refused.isPresent()) {
            failed(webhook, delivery, null, "The address is not allowed (" + refused.get() + ")", now);
            return true;
        }
        long timestamp = now.getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook.url()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "OpenDevTrack-Webhooks/1.0")
                .header("webhook-id", delivery.id().toString())
                .header("webhook-timestamp", Long.toString(timestamp))
                .header("webhook-signature", Signature.header(webhook.signingSecrets(now, secrets::open), delivery.id().toString(),
                        timestamp, delivery.payload()))
                .POST(HttpRequest.BodyPublishers.ofString(delivery.payload()))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                delivery.delivered(code, now);
                webhook.delivered();
            } else {
                failed(webhook, delivery, code, "HTTP " + code, now);
            }
        } catch (IOException e) {
            failed(webhook, delivery, null, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed(webhook, delivery, null, "Interrupted", now);
        }
        return true;
    }

    private void failed(WebhookEntity webhook, DeliveryEntity delivery, Integer code, String error, Instant now) {
        if (!delivery.failed(code, error, now)) {
            return;
        }
        log.info("Webhook delivery {} to {} given up after {} attempts: {}", delivery.id(), webhook.name(),
                delivery.attempts(), error);
        if (!Events.PING.equals(delivery.event()) && webhook.givenUp(delivery.createdAt(), now, TURN_OFF_AFTER)) {
            log.warn("Webhook {} of project {} turned off: its deliveries failed for {} days", webhook.name(),
                    webhook.projectId(), TURN_OFF_AFTER.toDays());
            events.publishEvent(new WebhookTurnedOff(webhook.projectId(), webhook.id(), webhook.name(), now));
        }
    }
}
