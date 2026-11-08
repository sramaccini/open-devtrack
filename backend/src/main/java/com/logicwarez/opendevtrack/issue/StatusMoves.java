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
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The issues of a status removed from a workflow, or of a type that moves to another workflow, move to another status
 * (ADR-20, A.3 and B.5), in the transaction of the change: one {@link IssueChanged} of the status per issue, so the
 * history, the search and the reports follow.
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
    public List<String> types() {
        return IssueType.names();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(UUID projectId, Collection<String> types, String status) {
        return types.isEmpty() ? 0 : issues.countByProjectIdAndTypeInAndStatus(projectId, of(types), status);
    }

    @Override
    @Transactional
    public int move(UUID projectId, Collection<String> types, String from, String to) {
        if (types.isEmpty() || from.equals(to)) {
            return 0;
        }
        ProjectRef project = projects.get(projectId).orElseThrow();
        List<IssueEntity> found = issues.findByProjectIdAndTypeInAndStatus(projectId, of(types), from);
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

    private static List<IssueType> of(Collection<String> types) {
        return types.stream().map(t -> IssueType.parse(t).orElseThrow()).toList();
    }
}
