/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import com.logicwarez.opendevtrack.agile.AgileApi.CardRow;
import com.logicwarez.opendevtrack.issue.IssueSummary;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The issues of a project with their plans, in the one order of the project (ADR-15). */
@Component
class Cards {

    private final Issues issues;
    private final PlanRepository plans;
    private final Workflows workflows;

    Cards(Issues issues, PlanRepository plans, Workflows workflows) {
        this.issues = issues;
        this.plans = plans;
        this.workflows = workflows;
    }

    /** An issue and its plan; {@code plan} is null only for an issue the order does not hold yet. */
    record Planned(IssueSummary issue, PlanEntity plan) {

        UUID sprintId() {
            return plan == null ? null : plan.sprintId();
        }

        Integer points() {
            return plan == null ? null : plan.points();
        }

        CardRow card(String column, Map<String, Boolean> perm) {
            return new CardRow(issue.key(), issue.summary(), issue.type(), issue.priority(), issue.assignee(), issue.status(),
                    issue.labels(), points(), column, perm);
        }
    }

    /** Every issue of the project, in order; those without a plan at the bottom, by number. */
    List<Planned> ranked(ProjectRef project) {
        Map<UUID, PlanEntity> byIssue = plans.findByProjectId(project.id()).stream()
                .collect(Collectors.toMap(PlanEntity::issueId, Function.identity()));
        return issues.ofProject(project).stream().map(i -> new Planned(i, byIssue.get(i.id())))
                .sorted(Comparator.comparing((Planned p) -> p.plan() == null ? null : p.plan().rank(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(p -> p.issue().number()))
                .toList();
    }

    /** The statuses of the workflow of the project in the category done. */
    Set<String> done(UUID projectId) {
        return workflows.statuses(projectId).stream().filter(s -> s.category().equals("done"))
                .map(Workflows.Status::code).collect(Collectors.toSet());
    }
}
