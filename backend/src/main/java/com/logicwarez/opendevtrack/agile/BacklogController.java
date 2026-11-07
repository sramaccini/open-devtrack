/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import com.logicwarez.opendevtrack.agile.AgileApi.CardRow;
import com.logicwarez.opendevtrack.agile.AgileApi.ColumnRow;
import com.logicwarez.opendevtrack.agile.AgileApi.MoveRequest;
import com.logicwarez.opendevtrack.agile.AgileApi.PlanRequest;
import com.logicwarez.opendevtrack.agile.AgileApi.PlanView;
import com.logicwarez.opendevtrack.agile.Scopes.Scope;
import com.logicwarez.opendevtrack.issue.IssueRef;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The backlog of a project (ADR-15): a kanban whose columns are the sprints not completed, the active one first, and
 * the backlog last. A card dropped on another column goes into that sprint, or back to the backlog
 * ({@code odt.sprint.manage}); dropped between two cards it changes place in the one order of the project
 * ({@code odt.issue.edit}). The backlog holds the issues not done and in no sprint. Here also the plan of one issue:
 * its sprint and its story points.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class BacklogController {

    static final String BACKLOG = "backlog";

    private final Scopes scopes;
    private final Plans plans;
    private final Cards cards;
    private final Issues issues;
    private final PlanRepository planRepository;
    private final SprintRepository sprints;
    private final Texts texts;

    BacklogController(Scopes scopes, Plans plans, Cards cards, Issues issues, PlanRepository planRepository,
                      SprintRepository sprints, Texts texts) {
        this.scopes = scopes;
        this.plans = plans;
        this.cards = cards;
        this.issues = issues;
        this.planRepository = planRepository;
        this.sprints = sprints;
        this.texts = texts;
    }

    @GetMapping("/api/projects/{key}/backlog/columns")
    ResponseEntity<?> columns(@PathVariable String key) {
        Optional<Scope> scope = member(key);
        if (scope.isEmpty()) {
            return notVisible(key);
        }
        List<ColumnRow> rows = new ArrayList<>();
        for (SprintEntity s : plans.open(scope.get().projectId())) {
            rows.add(new ColumnRow(s.id().toString(), s.name(), s.is(SprintEntity.ACTIVE) ? "primary" : null, null));
        }
        rows.add(new ColumnRow(BACKLOG, texts.backlog(), null, null));
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/api/projects/{key}/backlog/cards")
    ResponseEntity<?> cards(@PathVariable String key) {
        Optional<Scope> scope = member(key);
        if (scope.isEmpty()) {
            return notVisible(key);
        }
        Set<UUID> open = plans.open(scope.get().projectId()).stream().map(SprintEntity::id).collect(Collectors.toSet());
        Set<String> done = cards.done(scope.get().projectId());
        Map<String, Boolean> perm = Map.of("move", scope.get().can(Scopes.SPRINTS) || scope.get().can(Scopes.RANK));
        List<CardRow> rows = cards.ranked(scope.get().project()).stream()
                .filter(p -> p.sprintId() != null ? open.contains(p.sprintId()) : !done.contains(p.issue().status()))
                .map(p -> p.card(p.sprintId() != null ? p.sprintId().toString() : BACKLOG, perm)).toList();
        return ResponseEntity.ok(rows);
    }

    /** A card dropped on a sprint or on the backlog, between two cards. */
    @PostMapping("/api/projects/{key}/backlog/cards/{issueKey}/move")
    @Transactional
    ResponseEntity<?> move(@PathVariable String key, @PathVariable String issueKey, @RequestBody MoveRequest request) {
        Optional<Scope> scope = member(key);
        if (scope.isEmpty()) {
            return notVisible(key);
        }
        Optional<IssueRef> issue = issue(issueKey, scope.get());
        if (issue.isEmpty()) {
            return Problems.notFound("Issue not found");
        }
        Map<String, SprintEntity> open = plans.open(scope.get().projectId()).stream()
                .collect(Collectors.toMap(s -> s.id().toString(), Function.identity()));
        String column = request.column() == null ? "" : request.column();
        if (!column.equals(BACKLOG) && !open.containsKey(column)) {
            return Problems.badRequest("No sprint not completed with this id in the project");
        }
        PlanEntity plan = plans.plan(issue.get().id(), scope.get().projectId());
        SprintEntity to = open.get(column);
        boolean replanned = !Objects.equals(plan.sprintId(), to == null ? null : to.id());
        boolean placed = present(request.beforeId()) || present(request.afterId());
        if (replanned && !scope.get().can(Scopes.SPRINTS)) {
            return Problems.forbidden("Only a developer or an admin of the project plans the sprints");
        }
        if (placed && !scope.get().can(Scopes.RANK)) {
            return Problems.forbidden("Only a developer or an admin of the project changes the order of the issues");
        }
        if (replanned) {
            plans.sprint(plan, to, Instant.now());
        }
        if (placed) {
            plans.place(plan, neighbour(request.beforeId(), scope.get()), neighbour(request.afterId(), scope.get()));
        }
        return ResponseEntity.ok(Map.of("column", column));
    }

    /** The plan of an issue: its story points and its sprint not completed. */
    @GetMapping("/api/issues/{issueKey}/plan")
    ResponseEntity<?> plan(@PathVariable String issueKey) {
        Optional<IssueRef> issue = issues.find(issueKey);
        Optional<Scope> scope = issue.flatMap(i -> scopes.of(i.projectId()));
        if (scope.isEmpty()) {
            return Problems.notFound("Issue not found");
        }
        if (!scope.get().can(Scopes.BROWSE)) {
            return Problems.forbidden("Only the members of the project see its issues");
        }
        Optional<PlanEntity> plan = planRepository.findById(issue.get().id());
        return ResponseEntity.ok(view(scope.get(), plan.orElse(null)));
    }

    @PutMapping("/api/issues/{issueKey}/plan")
    @Transactional
    ResponseEntity<?> points(@PathVariable String issueKey, @RequestBody PlanRequest request) {
        Optional<IssueRef> issue = issues.find(issueKey);
        Optional<Scope> scope = issue.flatMap(i -> scopes.of(i.projectId()));
        if (scope.isEmpty()) {
            return Problems.notFound("Issue not found");
        }
        if (!scope.get().can(Scopes.RANK)) {
            return Problems.forbidden("Only a developer or an admin of the project estimates issues");
        }
        if (request.points() != null && (request.points() < 0 || request.points() > 999)) {
            return Problems.badRequest("The story points are from 0 to 999");
        }
        PlanEntity plan = plans.plan(issue.get().id(), scope.get().projectId());
        plan.points(request.points());
        planRepository.save(plan);
        plans.planned(plan);
        return ResponseEntity.ok(view(scope.get(), plan));
    }

    private PlanView view(Scope scope, PlanEntity plan) {
        Optional<SprintEntity> sprint = plan == null || plan.sprintId() == null ? Optional.empty()
                : sprints.findById(plan.sprintId());
        return new PlanView(plan == null ? null : plan.points(), sprint.map(SprintEntity::name).orElse(null),
                sprint.map(SprintEntity::state).orElse(null), Map.of("edit", scope.can(Scopes.RANK)));
    }

    private Optional<Scope> member(String key) {
        return scopes.of(key).filter(s -> s.can(Scopes.BROWSE));
    }

    private ResponseEntity<?> notVisible(String key) {
        return scopes.of(key).isEmpty() ? Problems.notFound(Scopes.NOT_FOUND) : Problems.forbidden(Scopes.NOT_A_MEMBER);
    }

    private Optional<IssueRef> issue(String issueKey, Scope scope) {
        return issues.find(issueKey).filter(i -> i.projectId().equals(scope.projectId()));
    }

    private static boolean present(String issueKey) {
        return issueKey != null && !issueKey.isBlank() && !issueKey.equals("null");
    }

    private Optional<PlanEntity> neighbour(String issueKey, Scope scope) {
        if (!present(issueKey)) {
            return Optional.empty();
        }
        return issue(issueKey, scope).map(i -> plans.plan(i.id(), scope.projectId()));
    }
}
