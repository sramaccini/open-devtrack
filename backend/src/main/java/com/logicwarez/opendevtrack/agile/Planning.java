/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The plan of the issues and the sprints, for the other modules (search, reporting). Without the session: the callers
 * pass projects they have already checked.
 */
@Service
@Transactional(readOnly = true)
public class Planning {

    private final PlanRepository plans;
    private final SprintRepository sprints;

    Planning(PlanRepository plans, SprintRepository sprints) {
        this.plans = plans;
        this.sprints = sprints;
    }

    /** Where an issue is in the order of its project, the sprint not completed it is in (or none), its points. */
    public record IssuePlan(UUID issueId, String rank, UUID sprintId, Integer points) {
    }

    /** A sprint: {@code state} is {@code planned}, {@code active} or {@code completed}. */
    public record Sprint(UUID id, UUID projectId, String name, String state) {
    }

    public Optional<IssuePlan> of(UUID issueId) {
        return plans.findById(issueId).map(p -> new IssuePlan(p.issueId(), p.rank(), p.sprintId(), p.points()));
    }

    /** The sprints of the projects, the most recent first. */
    public List<Sprint> sprints(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return sprints.findByProjectIdInOrderByCreatedAtDesc(projectIds).stream()
                .map(s -> new Sprint(s.id(), s.projectId(), s.name(), s.state())).toList();
    }
}
