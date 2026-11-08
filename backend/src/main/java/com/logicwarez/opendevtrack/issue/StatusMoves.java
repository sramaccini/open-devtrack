/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.IssueStatuses;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The issues of a status removed from the workflow move to another one (ADR-20, A.3), in the transaction of the removal:
 * one {@link IssueChanged} of the status per issue, so the history, the search and the reports follow.
 */
@Component
class StatusMoves implements IssueStatuses {

    private final IssueRepository issues;
    private final Projects projects;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;

    StatusMoves(IssueRepository issues, Projects projects, CurrentUser user, ApplicationEventPublisher events) {
        this.issues = issues;
        this.projects = projects;
        this.user = user;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public long count(UUID projectId, String status) {
        return issues.countByProjectIdAndStatus(projectId, status);
    }

    @Override
    @Transactional
    public int move(UUID projectId, String from, String to) {
        ProjectRef project = projects.get(projectId).orElseThrow();
        List<IssueEntity> found = issues.findByProjectIdAndStatus(projectId, from);
        Instant now = Instant.now();
        for (IssueEntity issue : found) {
            issue.moveTo(to, now);
            issues.save(issue);
            events.publishEvent(new IssueChanged(issue.id(), projectId, IssueController.key(project, issue),
                    user.username(), now, List.of(new FieldChange("status", from, to))));
        }
        issues.flush();
        return found.size();
    }
}
