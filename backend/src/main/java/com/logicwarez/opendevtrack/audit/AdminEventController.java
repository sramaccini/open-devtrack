/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log of the administration (ADR-22): the log of the tenant for its admin, filtered by project, user, kind
 * and days, and the log of one project for the admins of the project. The newest first, at most 500 rows.
 */
@RestController
@Transactional(readOnly = true)
class AdminEventController {

    private static final int MAX_ROWS = 500;
    private static final String NOT_FOUND = "Project not found";

    private final AdminEventRepository events;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;

    AdminEventController(AdminEventRepository events, Projects projects, ProjectAccess access, CurrentUser user) {
        this.events = events;
        this.projects = projects;
        this.access = access;
        this.user = user;
    }

    /**
     * A change to the configuration. {@code changes} puts the values before and after in words; {@code before} and
     * {@code after} keep them as they are.
     */
    record AdminEventView(UUID id, Instant at, String actor, String action, String project, String target,
            String changes, Map<String, Object> before, Map<String, Object> after) {
    }

    /**
     * {@code from} and {@code to} are days (2026-11-13), both included, in UTC; an {@code action} ending with a dot
     * (for example {@code member.}) keeps all the actions of that kind.
     */
    @ApiDoc(value = "The audit log of the administration of the tenant, newest first", answer = AdminEventView.class,
            list = true)
    @GetMapping("/api/admin-events")
    @PreAuthorize("hasAuthority('odt.project.admin')")
    ResponseEntity<?> tenant(@RequestParam(required = false) String project, @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String tenant = user.tenantId();
        Specification<AdminEventEntity> where = (e, q, cb) -> cb.equal(e.get("tenantId"), tenant);
        if (!blank(project)) {
            Optional<ProjectRef> found = projects.find(project.strip());
            if (found.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
            UUID id = found.get().id();
            where = where.and((e, q, cb) -> cb.equal(e.get("projectId"), id));
        }
        if (!blank(actor)) {
            String name = actor.strip();
            where = where.and((e, q, cb) -> cb.equal(e.get("actor"), name));
        }
        if (!blank(action)) {
            String kind = action.strip();
            where = where.and(kind.endsWith(".")
                    ? (e, q, cb) -> cb.like(e.get("action"), kind + "%")
                    : (e, q, cb) -> cb.equal(e.get("action"), kind));
        }
        Optional<Instant> since;
        Optional<Instant> until;
        try {
            since = day(from, 0);
            until = day(to, 1);
        } catch (DateTimeParseException e) {
            return Problems.badRequest("A day is written as 2026-11-13");
        }
        if (since.isPresent()) {
            Instant at = since.get();
            where = where.and((e, q, cb) -> cb.greaterThanOrEqualTo(e.get("occurredAt"), at));
        }
        if (until.isPresent()) {
            Instant at = until.get();
            where = where.and((e, q, cb) -> cb.lessThan(e.get("occurredAt"), at));
        }
        return ResponseEntity.ok(views(where));
    }

    @ApiDoc(value = "The audit log of the administration of a project, newest first", answer = AdminEventView.class,
            list = true, project = "odt.project.edit")
    @GetMapping("/api/projects/{key}/admin-events")
    ResponseEntity<?> project(@PathVariable String key) {
        Optional<ProjectRef> found = projects.find(key);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!access.permissions(found.get().id(), user.username()).contains("odt.project.edit")) {
            return Problems.forbidden("Only an admin of the project sees its log");
        }
        UUID id = found.get().id();
        return ResponseEntity.ok(views((e, q, cb) -> cb.equal(e.get("projectId"), id)));
    }

    private List<AdminEventView> views(Specification<AdminEventEntity> where) {
        List<AdminEventEntity> rows = events.findBy(where, query -> query
                .sortBy(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))).limit(MAX_ROWS).all());
        Map<UUID, String> keys = projects.all().stream().collect(Collectors.toMap(ProjectRef::id, ProjectRef::key));
        return rows.stream().map(e -> new AdminEventView(e.id(), e.occurredAt(), e.actor(), e.action(),
                e.projectId() == null ? null : keys.get(e.projectId()), e.target(), changes(e.before(), e.after()),
                e.before(), e.after())).toList();
    }

    /**
     * {@code role: developer → admin; name: Team}: the values that changed, and those of something new or removed but
     * the empty ones. The order is that of the JSON of PostgreSQL ({@code jsonb} keeps its keys in its own order).
     */
    static String changes(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> was = before == null ? Map.of() : before;
        Map<String, Object> is = after == null ? Map.of() : after;
        Set<String> names = new LinkedHashSet<>(was.keySet());
        names.addAll(is.keySet());
        List<String> parts = new ArrayList<>();
        for (String name : names) {
            boolean old = was.containsKey(name);
            boolean now = is.containsKey(name);
            Object from = was.get(name);
            Object to = is.get(name);
            if (old && now ? Objects.equals(from, to) : (now ? to : from) == null) {
                continue;
            }
            parts.add(old && now ? name + ": " + text(from) + " → " + text(to) : name + ": " + text(now ? to : from));
        }
        return String.join("; ", parts);
    }

    private static String text(Object value) {
        if (value == null) {
            return "—";
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AdminEventController::text).collect(Collectors.joining(", ", "[", "]"));
        }
        return value.toString();
    }

    private static Optional<Instant> day(String text, int plusDays) {
        if (blank(text)) {
            return Optional.empty();
        }
        String day = text.strip();
        return Optional.of(LocalDate.parse(day.length() > 10 ? day.substring(0, 10) : day).plusDays(plusDays)
                .atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
