/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import static com.logicwarez.opendevtrack.shared.ConfigurationChanged.values;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.platform.AdminLog;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.platform.SecretBox;
import com.logicwarez.opendevtrack.platform.Problems.FieldError;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.ConfigurationChanged;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The webhooks of a project (ADR-21, B7 and B13), for an admin of the project ({@code odt.project.edit}): at most
 * {@link #MAX_PER_PROJECT}, each with a name, an address, the events it wants and a secret generated here and shown
 * once (when created and when rotated). «Send a test» queues a {@code ping}; the last {@link #DELIVERIES} deliveries
 * are listed and each can be delivered again. A webhook of another project, or of a project of another tenant,
 * answers 404.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class WebhookController {

    static final int MAX_PER_PROJECT = 10;
    static final int DELIVERIES = 50;
    static final int MAX_NAME = 100;

    private static final String BASE = "/api/projects/{key}/webhooks";
    private static final String EDIT = "odt.project.edit";
    private static final String NOT_FOUND = "Project not found";
    private static final String NO_WEBHOOK = "Webhook not found";
    private static final String NOT_ADMIN = "Only an admin of the project manages its webhooks";

    private final WebhookRepository webhooks;
    private final DeliveryRepository deliveries;
    private final Events events;
    private final Addresses addresses;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final AdminLog log;
    private final SecretBox secrets;

    WebhookController(WebhookRepository webhooks, DeliveryRepository deliveries, Events events, Addresses addresses,
                      Projects projects, ProjectAccess access, CurrentUser user, AdminLog log, SecretBox secrets) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.events = events;
        this.addresses = addresses;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.log = log;
        this.secrets = secrets;
    }

    /**
     * A webhook, with what its last delivery did; {@code secret} only in the answer that creates or rotates it, the one
     * time it is shown.
     */
    record WebhookView(UUID id, String name, String url, List<String> events, boolean active, String lastStatus,
                       Integer lastCode, Instant lastAt, Instant failingSince,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String secret, long version,
                       @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A new webhook or a change: the events among those of ADR-21 (B8), or {@code *} for all. */
    record WebhookRequest(String name, String url, List<String> events, Boolean active, Long version) {
    }

    record DeliveryView(UUID id, String event, String status, int attempts, Integer lastCode, String lastError,
                        Instant lastAt, Instant nextAt, Instant createdAt) {
    }

    private record Scope(ProjectRef project, WebhookEntity webhook) {
    }

    @ApiDoc(value = "The webhooks of a project", answer = WebhookView.class, list = true, project = EDIT)
    @GetMapping(BASE)
    ResponseEntity<?> list(@PathVariable String key) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!canEdit(project.get())) {
            return Problems.forbidden(NOT_ADMIN);
        }
        return ResponseEntity.ok(webhooks.findByProjectIdOrderByCreatedAtAscIdAsc(project.get().id()).stream()
                .map(w -> view(w, null)).toList());
    }

    @ApiDoc(value = "A webhook of a project", answer = WebhookView.class, project = EDIT)
    @GetMapping(BASE + "/{id}")
    ResponseEntity<?> get(@PathVariable String key, @PathVariable UUID id) {
        return scope(key, id).<ResponseEntity<?>>map(s -> ResponseEntity.ok(view(s.webhook(), null)))
                .orElseGet(() -> missing(key, id));
    }

    /**
     * What the audit log keeps of a webhook: name, events, on or off, and of its address only the scheme and the host,
     * since the path or the query of some addresses is a secret (the incoming webhooks of chat services).
     */
    static Map<String, Object> logged(WebhookEntity webhook) {
        return values("name", webhook.name(), "url", origin(webhook.url()), "events", List.copyOf(webhook.events()),
                "active", webhook.active());
    }

    /** {@code https://hooks.example.com/…}: the scheme, the host and the port of an address, the rest hidden. */
    static String origin(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "…";
            }
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            boolean more = (uri.getRawPath() != null && uri.getRawPath().length() > 1) || uri.getRawQuery() != null;
            return more ? origin + "/…" : origin;
        } catch (IllegalArgumentException e) {
            return "…";
        }
    }

    @ApiDoc(value = "Adds a webhook; the answer has its secret, the only time it is shown",
            answer = WebhookView.class, status = 201, project = EDIT)
    @PostMapping(BASE)
    @Transactional
    ResponseEntity<?> add(@PathVariable String key, @RequestBody WebhookRequest request) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!canEdit(project.get())) {
            return Problems.forbidden(NOT_ADMIN);
        }
        Optional<ResponseEntity<ProblemDetail>> wrong = check(request);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        String name = request.name().strip();
        if (webhooks.countByProjectId(project.get().id()) >= MAX_PER_PROJECT) {
            return Problems.conflict("A project has at most " + MAX_PER_PROJECT + " webhooks");
        }
        if (webhooks.existsByProjectIdAndNameIgnoreCase(project.get().id(), name)) {
            return Problems.conflict("The project has a webhook with this name");
        }
        String secret = Signature.newSecret();
        WebhookEntity webhook = webhooks.saveAndFlush(new WebhookEntity(project.get().tenantId(), project.get().id(),
                name, request.url().strip(), events(request.events()), !Boolean.FALSE.equals(request.active()), secrets.seal(secret),
                user.username(), Instant.now()));
        var after = logged(webhook);
        after.put("secret", ConfigurationChanged.ROTATED);
        log.project(webhook.projectId(), "webhook.created", webhook.name(), null, after);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(webhook, secret));
    }

    @ApiDoc(value = "Changes a webhook: name, address, events, on or off", answer = WebhookView.class, project = EDIT)
    @PutMapping(BASE + "/{id}")
    @Transactional
    ResponseEntity<?> change(@PathVariable String key, @PathVariable UUID id, @RequestBody WebhookRequest request) {
        Optional<Scope> scope = scope(key, id);
        if (scope.isEmpty()) {
            return missing(key, id);
        }
        WebhookEntity webhook = scope.get().webhook();
        if (request.version() == null || request.version() != webhook.version()) {
            return Problems.changedMeanwhile();
        }
        Optional<ResponseEntity<ProblemDetail>> wrong = check(request);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        String name = request.name().strip();
        if (!name.equalsIgnoreCase(webhook.name())
                && webhooks.existsByProjectIdAndNameIgnoreCase(webhook.projectId(), name)) {
            return Problems.conflict("The project has a webhook with this name");
        }
        var before = logged(webhook);
        webhook.edit(name, request.url().strip(), events(request.events()),
                request.active() == null ? webhook.active() : request.active(), Instant.now());
        webhooks.saveAndFlush(webhook);
        log.project(webhook.projectId(), "webhook.changed", webhook.name(), before, logged(webhook));
        return ResponseEntity.ok(view(webhook, null));
    }

    @ApiDoc(value = "Removes a webhook and its deliveries", status = 204, project = EDIT)
    @DeleteMapping(BASE + "/{id}")
    @Transactional
    ResponseEntity<?> remove(@PathVariable String key, @PathVariable UUID id) {
        Optional<Scope> scope = scope(key, id);
        if (scope.isEmpty()) {
            return missing(key, id);
        }
        webhooks.delete(scope.get().webhook());
        log.project(scope.get().webhook().projectId(), "webhook.removed", scope.get().webhook().name(),
                logged(scope.get().webhook()), null);
        return ResponseEntity.noContent().build();
    }

    @ApiDoc(value = "Gives a webhook a new secret, shown once; the previous one also signs for 24 hours",
            answer = WebhookView.class, project = EDIT)
    @PostMapping(BASE + "/{id}/secret")
    @Transactional
    ResponseEntity<?> rotate(@PathVariable String key, @PathVariable UUID id) {
        Optional<Scope> scope = scope(key, id);
        if (scope.isEmpty()) {
            return missing(key, id);
        }
        String secret = Signature.newSecret();
        scope.get().webhook().rotate(secrets.seal(secret), Instant.now());
        webhooks.saveAndFlush(scope.get().webhook());
        log.project(scope.get().webhook().projectId(), "webhook.secret_rotated", scope.get().webhook().name(), null,
                values("secret", ConfigurationChanged.ROTATED));
        return ResponseEntity.ok(view(scope.get().webhook(), secret));
    }

    @ApiDoc(value = "Sends a test: a ping event to this webhook, even when it is off", answer = DeliveryView.class,
            status = 201, project = EDIT)
    @PostMapping(BASE + "/{id}/ping")
    @Transactional
    ResponseEntity<?> ping(@PathVariable String key, @PathVariable UUID id) {
        Optional<Scope> scope = scope(key, id);
        if (scope.isEmpty()) {
            return missing(key, id);
        }
        DeliveryEntity ping = events.ping(scope.get().webhook(), scope.get().project(), user.username(), Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(ping));
    }

    @ApiDoc(value = "The last 50 deliveries of a webhook, newest first", answer = DeliveryView.class, list = true,
            project = EDIT)
    @GetMapping(BASE + "/{id}/deliveries")
    ResponseEntity<?> deliveries(@PathVariable String key, @PathVariable UUID id) {
        Optional<Scope> scope = scope(key, id);
        if (scope.isEmpty()) {
            return missing(key, id);
        }
        return ResponseEntity.ok(deliveries.findByWebhookIdOrderByCreatedAtDescIdDesc(id, Limit.of(DELIVERIES)).stream()
                .map(WebhookController::view).toList());
    }

    @ApiDoc(value = "Delivers again: the same delivery, with its id and body, from the first attempt",
            answer = DeliveryView.class, project = EDIT)
    @PostMapping(BASE + "/{id}/deliveries/{delivery}/again")
    @Transactional
    ResponseEntity<?> again(@PathVariable String key, @PathVariable UUID id, @PathVariable UUID delivery) {
        Optional<Scope> scope = scope(key, id);
        if (scope.isEmpty()) {
            return missing(key, id);
        }
        Optional<DeliveryEntity> found = deliveries.findByIdAndWebhookId(delivery, id);
        if (found.isEmpty()) {
            return Problems.notFound("Delivery not found");
        }
        if (DeliveryEntity.PENDING.equals(found.get().status())) {
            return Problems.conflict("The delivery is still being tried");
        }
        found.get().again(Instant.now());
        return ResponseEntity.ok(view(found.get()));
    }

    private Optional<ResponseEntity<ProblemDetail>> check(WebhookRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (request.name() == null || request.name().isBlank()) {
            errors.add(new FieldError("/name", "required"));
        } else if (request.name().strip().length() > MAX_NAME) {
            errors.add(new FieldError("/name", "maxLength"));
        }
        if (request.url() == null || request.url().isBlank()) {
            errors.add(new FieldError("/url", "required"));
        } else {
            addresses.refuse(request.url().strip()).ifPresent(code -> errors.add(new FieldError("/url", code)));
        }
        if (request.events() == null || request.events().isEmpty()) {
            errors.add(new FieldError("/events", "required"));
        } else if (!request.events().stream().allMatch(e -> Events.ALL.equals(e) || Events.TYPES.contains(e))) {
            errors.add(new FieldError("/events", "event"));
        }
        return errors.isEmpty() ? Optional.empty() : Optional.of(Problems.invalidFields(
                "The webhook is not valid: the address must be https and public, the events among "
                        + String.join(", ", Events.TYPES) + " or *", errors));
    }

    /** {@code *} alone when it is among them; the others in the order of ADR-21. */
    private static List<String> events(List<String> wanted) {
        if (wanted.contains(Events.ALL)) {
            return List.of(Events.ALL);
        }
        return new ArrayList<>(new LinkedHashSet<>(Events.TYPES.stream().filter(wanted::contains).toList()));
    }

    /** The webhook, when the project is visible, the user an admin of it and the webhook one of its own. */
    private Optional<Scope> scope(String key, UUID id) {
        return projects.find(key).filter(this::canEdit)
                .flatMap(p -> webhooks.findByIdAndProjectId(id, p.id()).map(w -> new Scope(p, w)));
    }

    /** Why {@link #scope} found nothing: no project (404), not an admin (403), no such webhook (404). */
    private ResponseEntity<?> missing(String key, UUID id) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        return canEdit(project.get()) ? Problems.notFound(NO_WEBHOOK) : Problems.forbidden(NOT_ADMIN);
    }

    private boolean canEdit(ProjectRef project) {
        return access.permissions(project.id(), user.username()).contains(EDIT);
    }

    private WebhookView view(WebhookEntity w, String secret) {
        Optional<DeliveryEntity> last = deliveries.findFirstByWebhookIdAndLastAtNotNullOrderByLastAtDesc(w.id());
        return new WebhookView(w.id(), w.name(), w.url(), w.events(), w.active(),
                last.map(DeliveryEntity::status).orElse(null), last.map(DeliveryEntity::lastCode).orElse(null),
                last.map(DeliveryEntity::lastAt).orElse(null), w.failingSince(), secret, w.version(),
                Map.of("edit", true, "remove", true));
    }

    private static DeliveryView view(DeliveryEntity d) {
        return new DeliveryView(d.id(), d.event(), d.status(), d.attempts(), d.lastCode(), d.lastError(), d.lastAt(),
                d.nextAt(), d.createdAt());
    }
}
