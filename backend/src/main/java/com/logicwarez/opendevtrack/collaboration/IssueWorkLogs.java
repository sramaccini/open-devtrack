/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.logicwarez.opendevtrack.platform.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The work log of an issue as the user sees it (ADR-30), with what they may do on each: for the API of the work log
 * and for the activity of an issue in the module audit. The caller has checked that the user sees the issue.
 */
@Component
public class IssueWorkLogs {

    static final String ADD = "odt.worklog.add";
    static final String MANAGE = "odt.worklog.manage";

    private final WorkLogRepository workLogs;
    private final CurrentUser user;

    IssueWorkLogs(WorkLogRepository workLogs, CurrentUser user) {
        this.workLogs = workLogs;
        this.user = user;
    }

    /** Work logged, with {@code edit} and {@code delete} for the user. */
    public record WorkLog(UUID id, String author, Instant started, int minutes, String comment, Instant createdAt,
                          Instant updatedAt, long version, Map<String, Boolean> perm) {
    }

    /** By when the work started; {@code permissions} are those of the user in the project of the issue. */
    public List<WorkLog> of(UUID issueId, Set<String> permissions) {
        return workLogs.findByIssueIdOrderByStartedAtAscIdAsc(issueId).stream().map(w -> view(w, permissions)).toList();
    }

    WorkLog view(WorkLogEntity w, Set<String> permissions) {
        boolean change = canChange(w, permissions);
        return new WorkLog(w.id(), w.author(), w.startedAt(), w.minutes(), w.comment(), w.createdAt(), w.updatedAt(),
                w.version(), Map.of("edit", change, "delete", change));
    }

    /** Its author while they may log work, or whoever manages the work logs of the project. */
    boolean canChange(WorkLogEntity w, Set<String> permissions) {
        return permissions.contains(MANAGE) || permissions.contains(ADD) && w.author().equals(user.username());
    }
}
