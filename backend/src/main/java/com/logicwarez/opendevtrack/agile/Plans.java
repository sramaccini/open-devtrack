/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import com.logicwarez.opendevtrack.agile.PlanEvents.IssuePlanned;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import com.logicwarez.opendevtrack.project.ProjectCreated;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The plan of the issues (ADR-15): the one order of a project, the sprint of each issue and what the active sprint
 * held. It listens inside the transactions of the module issue and of the module project, not after them: a new issue
 * is on the boards at once, and a new project has its board.
 */
@Service
@Transactional
class Plans {

    static final String DEFAULT_BOARD = "Board";

    private final PlanRepository plans;
    private final SprintRepository sprints;
    private final SprintIssueRepository history;
    private final BoardRepository boards;
    private final ApplicationEventPublisher events;

    Plans(PlanRepository plans, SprintRepository sprints, SprintIssueRepository history, BoardRepository boards,
          ApplicationEventPublisher events) {
        this.events = events;
        this.plans = plans;
        this.sprints = sprints;
        this.history = history;
        this.boards = boards;
    }

    /** A new project gets a kanban board with one column per status of its workflow. */
    @EventListener
    void on(ProjectCreated event) {
        boards.save(new BoardEntity(event.projectId(), DEFAULT_BOARD, "kanban", "none", List.of(), null, event.at()));
    }

    /** A new issue goes to the bottom of the order, in the backlog. */
    @EventListener
    void on(IssueCreated event) {
        plan(event.issueId(), event.projectId());
    }

    @EventListener
    void on(IssueDeleted event) {
        plans.findById(event.issueId()).ifPresent(p -> {
            sprint(p, null, event.at());
            plans.delete(p);
        });
    }

    /** The plan of the issue; one at the bottom of the order for an issue without one. */
    PlanEntity plan(UUID issueId, UUID projectId) {
        return plans.findById(issueId).orElseGet(() -> {
            plans.lock(projectId);
            PlanEntity created = plans.save(new PlanEntity(issueId, projectId, Rank.after(plans.last(projectId).orElse(null))));
            planned(created);
            return created;
        });
    }

    /**
     * Puts the issue just after {@code before}, or just before {@code after} when there is no {@code before}: the cards
     * that will be around it on a board or in the backlog. With neither, it keeps its place.
     */
    void place(PlanEntity plan, Optional<PlanEntity> before, Optional<PlanEntity> after) {
        if (before.isEmpty() && after.isEmpty()) {
            return;
        }
        plans.lock(plan.projectId());
        String rank;
        if (before.isPresent()) {
            String low = before.get().rank();
            rank = Rank.between(low, plans.next(plan.projectId(), low, plan.issueId()).orElse(null));
        } else {
            String high = after.get().rank();
            rank = Rank.between(plans.previous(plan.projectId(), high, plan.issueId()).orElse(null), high);
        }
        plan.rank(rank);
        plans.saveAndFlush(plan);
        planned(plan);
    }

    /** Moves the issue into a sprint (none: the backlog), and keeps what the active sprint holds. */
    void sprint(PlanEntity plan, SprintEntity sprint, Instant now) {
        UUID to = sprint == null ? null : sprint.id();
        if (java.util.Objects.equals(plan.sprintId(), to)) {
            return;
        }
        if (plan.sprintId() != null) {
            history.findBySprintIdAndIssueIdAndRemovedAtIsNull(plan.sprintId(), plan.issueId())
                    .ifPresent(h -> h.out(SprintIssueEntity.REMOVED, now));
        }
        if (sprint != null && sprint.is(SprintEntity.ACTIVE)) {
            history.save(new SprintIssueEntity(sprint.id(), plan.issueId(), plan.points(), now));
        }
        plan.sprint(to);
        plans.save(plan);
        planned(plan);
    }

    /** Tells the other modules that the plan of the issue changed (ADR-16). */
    void planned(PlanEntity plan) {
        events.publishEvent(new IssuePlanned(plan.issueId(), plan.projectId()));
    }

    /** The sprints not completed of the project: the active one first, then the planned ones, the oldest first. */
    List<SprintEntity> open(UUID projectId) {
        return sprints.findByProjectIdAndStateInOrderByCreatedAtAscIdAsc(projectId,
                        List.of(SprintEntity.ACTIVE, SprintEntity.PLANNED)).stream()
                .sorted((a, b) -> Boolean.compare(b.is(SprintEntity.ACTIVE), a.is(SprintEntity.ACTIVE))).toList();
    }

    /** The active sprint of the project, if there is one. */
    Optional<SprintEntity> active(UUID projectId) {
        return sprints.findByProjectIdAndState(projectId, SprintEntity.ACTIVE);
    }
}
