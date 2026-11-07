/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import com.logicwarez.opendevtrack.agile.AgileApi.CompleteRequest;
import com.logicwarez.opendevtrack.agile.AgileApi.SprintRequest;
import com.logicwarez.opendevtrack.agile.AgileApi.SprintRow;
import com.logicwarez.opendevtrack.agile.Scopes.Scope;
import com.logicwarez.opendevtrack.agile.SprintEvents.SprintCompleted;
import com.logicwarez.opendevtrack.agile.SprintEvents.SprintStarted;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
 * The sprints of a project (ADR-15), seen by its members and managed with {@code odt.sprint.manage} (developer and
 * admin): created planned, started one at a time, completed with the issues not done going to the backlog or to a
 * planned sprint. A planned sprint can be removed, and its issues go back to the backlog; a completed one stays, for
 * the reports.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class SprintController {

    private static final String NOT_FOUND = "Sprint not found";
    private static final String NOT_A_PLANNER = "Only a developer or an admin of the project manages its sprints";
    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_OPEN = 9;

    private final SprintRepository sprints;
    private final PlanRepository planRepository;
    private final SprintIssueRepository history;
    private final Plans plans;
    private final Cards cards;
    private final Scopes scopes;
    private final ApplicationEventPublisher events;

    SprintController(SprintRepository sprints, PlanRepository planRepository, SprintIssueRepository history, Plans plans,
                     Cards cards, Scopes scopes, ApplicationEventPublisher events) {
        this.sprints = sprints;
        this.planRepository = planRepository;
        this.history = history;
        this.plans = plans;
        this.cards = cards;
        this.scopes = scopes;
        this.events = events;
    }

    /** Every sprint of the project, the newest first, with the issues and the points it holds now. */
    @GetMapping("/api/projects/{key}/sprints")
    ResponseEntity<?> list(@PathVariable String key) {
        Optional<Scope> scope = scopes.of(key);
        if (scope.isEmpty()) {
            return Problems.notFound(Scopes.NOT_FOUND);
        }
        if (!scope.get().can(Scopes.BROWSE)) {
            return Problems.forbidden(Scopes.NOT_A_MEMBER);
        }
        List<PlanEntity> planned = planRepository.findByProjectId(scope.get().projectId());
        return ResponseEntity.ok(sprints.findByProjectIdOrderByCreatedAtDescIdDesc(scope.get().projectId()).stream()
                .map(s -> row(scope.get(), s, planned)).toList());
    }

    @PostMapping("/api/projects/{key}/sprints")
    @Transactional
    ResponseEntity<?> create(@PathVariable String key, @RequestBody SprintRequest request) {
        Optional<Scope> scope = scopes.of(key);
        if (scope.isEmpty()) {
            return Problems.notFound(Scopes.NOT_FOUND);
        }
        if (!scope.get().can(Scopes.SPRINTS)) {
            return Problems.forbidden(NOT_A_PLANNER);
        }
        if (plans.open(scope.get().projectId()).size() >= MAX_OPEN) {
            return Problems.conflict("A project has at most " + MAX_OPEN + " sprints not completed");
        }
        String name = request.name() == null || request.name().isBlank()
                ? scope.get().project().key() + " Sprint " + (sprints.countByProjectId(scope.get().projectId()) + 1)
                : request.name().strip();
        ResponseEntity<?> invalid = invalid(name, request);
        if (invalid != null) {
            return invalid;
        }
        SprintEntity sprint = sprints.saveAndFlush(new SprintEntity(scope.get().projectId(), name, goal(request),
                request.startsOn(), request.endsOn(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(row(scope.get(), sprint, List.of()));
    }

    /** Changes the name, the goal and the dates of a sprint not completed, from the version read with it. */
    @PutMapping("/api/sprints/{id}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody SprintRequest request) {
        Optional<Found> found = managed(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!found.get().scope().can(Scopes.SPRINTS)) {
            return Problems.forbidden(NOT_A_PLANNER);
        }
        SprintEntity sprint = found.get().sprint();
        if (sprint.is(SprintEntity.COMPLETED)) {
            return Problems.conflict("A completed sprint does not change");
        }
        String name = request.name() == null ? "" : request.name().strip();
        ResponseEntity<?> invalid = invalid(name, request);
        if (invalid != null) {
            return invalid;
        }
        if (request.version() == null || request.version() != sprint.version()) {
            return Problems.changedMeanwhile();
        }
        sprint.edit(name, goal(request), request.startsOn(), request.endsOn());
        try {
            sprints.saveAndFlush(sprint);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        return ResponseEntity.ok(row(found.get().scope(), sprint, planRepository.findBySprintId(sprint.id())));
    }

    /** Starts a planned sprint, from today for two weeks unless the request gives the dates; 409 with another active. */
    @PostMapping("/api/sprints/{id}/start")
    @Transactional
    ResponseEntity<?> start(@PathVariable UUID id, @RequestBody(required = false) SprintRequest request) {
        Optional<Found> found = managed(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Scope scope = found.get().scope();
        if (!scope.can(Scopes.SPRINTS)) {
            return Problems.forbidden(NOT_A_PLANNER);
        }
        SprintEntity sprint = found.get().sprint();
        if (!sprint.is(SprintEntity.PLANNED)) {
            return Problems.conflict("Only a planned sprint starts");
        }
        if (plans.active(scope.projectId()).isPresent()) {
            return Problems.conflict("The project already has an active sprint: complete it first");
        }
        LocalDate startsOn = firstOf(request == null ? null : request.startsOn(), sprint.startsOn(), LocalDate.now());
        LocalDate endsOn = firstOf(request == null ? null : request.endsOn(), sprint.endsOn(), startsOn.plusDays(DEFAULT_DAYS));
        if (endsOn.isBefore(startsOn)) {
            return Problems.badRequest("The sprint ends on or after the day it starts");
        }
        Instant now = Instant.now();
        sprint.start(startsOn, endsOn, now);
        sprints.saveAndFlush(sprint);
        int committed = 0;
        for (PlanEntity plan : planRepository.findBySprintId(sprint.id())) {
            history.save(new SprintIssueEntity(sprint.id(), plan.issueId(), plan.points(), now));
            committed += plan.points() == null ? 0 : plan.points();
        }
        events.publishEvent(new SprintStarted(sprint.id(), scope.projectId(), sprint.name(), startsOn, endsOn, committed,
                scopes.username(), now));
        return ResponseEntity.ok(row(scope, sprint, planRepository.findBySprintId(sprint.id())));
    }

    /**
     * Completes the active sprint: its issues done stay with it, the others go to {@code moveTo}, the backlog or a
     * planned sprint of the project.
     */
    @PostMapping("/api/sprints/{id}/complete")
    @Transactional
    ResponseEntity<?> complete(@PathVariable UUID id, @RequestBody(required = false) CompleteRequest request) {
        Optional<Found> found = managed(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Scope scope = found.get().scope();
        if (!scope.can(Scopes.SPRINTS)) {
            return Problems.forbidden(NOT_A_PLANNER);
        }
        SprintEntity sprint = found.get().sprint();
        if (!sprint.is(SprintEntity.ACTIVE)) {
            return Problems.conflict("Only the active sprint is completed");
        }
        String moveTo = request == null || request.moveTo() == null || request.moveTo().isBlank() ? "backlog" : request.moveTo();
        SprintEntity next = null;
        if (moveTo.equals("next")) {
            // The first planned sprint, or the backlog when there is none.
            next = plans.open(scope.projectId()).stream().filter(s -> s.is(SprintEntity.PLANNED)).findFirst().orElse(null);
        } else if (!moveTo.equals("backlog")) {
            Optional<SprintEntity> target = sprint(moveTo).filter(s -> s.projectId().equals(scope.projectId())
                    && s.is(SprintEntity.PLANNED));
            if (target.isEmpty()) {
                return Problems.badRequest("The issues not done go to the backlog or to a planned sprint of the project");
            }
            next = target.get();
        }
        Set<String> done = cards.done(scope.projectId());
        Map<UUID, String> statusOf = cards.ranked(scope.project()).stream()
                .collect(Collectors.toMap(p -> p.issue().id(), p -> p.issue().status()));
        Instant now = Instant.now();
        List<PlanEntity> planned = planRepository.findBySprintId(sprint.id());
        Map<UUID, Integer> pointsOf = new HashMap<>();
        planned.forEach(p -> pointsOf.put(p.issueId(), p.points()));
        int finished = 0;
        int carried = 0;
        int donePoints = 0;
        int carriedPoints = 0;
        for (SprintIssueEntity held : history.findBySprintIdAndRemovedAtIsNull(sprint.id())) {
            boolean isDone = done.contains(statusOf.get(held.issueId()));
            held.out(isDone ? SprintIssueEntity.DONE : SprintIssueEntity.CARRIED, now);
            Integer points = pointsOf.containsKey(held.issueId()) ? pointsOf.get(held.issueId()) : held.points();
            if (isDone) {
                finished++;
                donePoints += points == null ? 0 : points;
            } else {
                carried++;
                carriedPoints += points == null ? 0 : points;
            }
        }
        for (PlanEntity plan : planned) {
            // The done ones leave the open sprints too: the sprint completed keeps them in its history.
            plan.sprint(done.contains(statusOf.get(plan.issueId())) || next == null ? null : next.id());
            plans.planned(plan);
        }
        sprint.complete(now);
        sprints.saveAndFlush(sprint);
        events.publishEvent(new SprintCompleted(sprint.id(), scope.projectId(), sprint.name(), finished, carried,
                donePoints, carriedPoints, scopes.username(), now));
        return ResponseEntity.ok(row(scope, sprint, List.of()));
    }

    /** Removes a planned sprint; its issues go back to the backlog. */
    @DeleteMapping("/api/sprints/{id}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable UUID id) {
        Optional<Found> found = managed(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!found.get().scope().can(Scopes.SPRINTS)) {
            return Problems.forbidden(NOT_A_PLANNER);
        }
        SprintEntity sprint = found.get().sprint();
        if (!sprint.is(SprintEntity.PLANNED)) {
            return Problems.conflict("Only a planned sprint is removed");
        }
        planRepository.findBySprintId(sprint.id()).forEach(p -> {
            p.sprint(null);
            plans.planned(p);
        });
        planRepository.flush();
        sprints.delete(sprint);
        return ResponseEntity.noContent().build();
    }

    private record Found(Scope scope, SprintEntity sprint) {
    }

    private Optional<SprintEntity> sprint(String id) {
        try {
            return sprints.findById(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The sprint, for a member of its project; 404 for the others too, as for a sprint of another tenant. */
    private Optional<Found> managed(UUID id) {
        return sprints.findById(id).flatMap(s -> scopes.of(s.projectId()).map(scope -> new Found(scope, s)))
                .filter(f -> f.scope().can(Scopes.BROWSE));
    }

    private static ResponseEntity<?> invalid(String name, SprintRequest request) {
        if (name.isEmpty() || name.length() > 100) {
            return Problems.badRequest("The name is required, at most 100 characters");
        }
        if (request.goal() != null && request.goal().length() > 1000) {
            return Problems.badRequest("The goal is at most 1000 characters");
        }
        if (request.startsOn() != null && request.endsOn() != null && request.endsOn().isBefore(request.startsOn())) {
            return Problems.badRequest("The sprint ends on or after the day it starts");
        }
        return null;
    }

    private static String goal(SprintRequest request) {
        return request.goal() == null || request.goal().isBlank() ? null : request.goal().strip();
    }

    private static LocalDate firstOf(LocalDate... dates) {
        for (LocalDate d : dates) {
            if (d != null) {
                return d;
            }
        }
        throw new IllegalArgumentException("No date");
    }

    private static SprintRow row(Scope scope, SprintEntity s, List<PlanEntity> planned) {
        List<PlanEntity> in = planned.stream().filter(p -> s.id().equals(p.sprintId())).toList();
        int points = in.stream().map(PlanEntity::points).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        boolean manage = scope.can(Scopes.SPRINTS);
        return new SprintRow(s.id().toString(), s.name(), s.goal(), s.state(), s.startsOn(), s.endsOn(), s.startedAt(),
                s.completedAt(), in.size(), points, s.version(), Map.of(
                        "edit", manage && !s.is(SprintEntity.COMPLETED),
                        "start", manage && s.is(SprintEntity.PLANNED),
                        "complete", manage && s.is(SprintEntity.ACTIVE),
                        "delete", manage && s.is(SprintEntity.PLANNED)));
    }
}
