/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.collaboration.IssueComments;
import com.logicwarez.opendevtrack.collaboration.IssueWorkLogs;
import com.logicwarez.opendevtrack.issue.IssueRef;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The activity of an issue, as in Jira: its comments and the changes of its fields in one list, oldest first, for the
 * activity feed of Quadrava ({@code kind} says which is which).
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class ActivityController {

    static final String COMMENT = "comment";
    static final String CHANGE = "change";
    static final String WORKLOG = "worklog";
    private static final int MAX_CHANGES = 500;

    private final Issues issues;
    private final IssueChangeRepository changes;
    private final IssueComments comments;
    private final IssueWorkLogs workLogs;
    private final ProjectAccess access;
    private final CurrentUser user;

    ActivityController(Issues issues, IssueChangeRepository changes, IssueComments comments, IssueWorkLogs workLogs,
                       ProjectAccess access, CurrentUser user) {
        this.workLogs = workLogs;
        this.issues = issues;
        this.changes = changes;
        this.comments = comments;
        this.access = access;
        this.user = user;
    }

    /**
     * A comment ({@code text}, {@code edited}, {@code version}, {@code _perm} with {@code edit} and {@code delete}), work
     * logged ({@code minutes}, {@code started}, {@code text}, {@code version}, {@code _perm}) or the change of a field
     * ({@code field}, {@code from}, {@code to}).
     */
    record Activity(String kind, UUID id, String author, Instant at, String text, boolean edited, Long version,
                    String field, String from, String to, Integer minutes, Instant started,
                    @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    @ApiDoc(value = "The comments and the changes of an issue in one list, oldest first",
            answer = Activity.class, list = true, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/activity")
    ResponseEntity<?> activity(@PathVariable String issueKey) {
        Optional<IssueRef> issue = issues.find(issueKey);
        if (issue.isEmpty()) {
            return Problems.notFound("Issue not found");
        }
        Set<String> permissions = access.permissions(issue.get().projectId(), user.username());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden("Only the members of the project see its issues");
        }
        List<Activity> all = new ArrayList<>();
        for (IssueComments.Comment c : comments.of(issue.get().id(), permissions)) {
            all.add(new Activity(COMMENT, c.id(), c.author(), c.createdAt(), c.body(), c.edited(), c.version(),
                    null, null, null, null, null, c.perm()));
        }
        // Work logged sits where it was logged, as in Jira; started is when the work began.
        for (IssueWorkLogs.WorkLog w : workLogs.of(issue.get().id(), permissions)) {
            all.add(new Activity(WORKLOG, w.id(), w.author(), w.createdAt(), w.comment(), false, w.version(),
                    null, null, null, w.minutes(), w.started(), w.perm()));
        }
        // The newest changes when there are many. The creation, and the values the issue was created with (written at
        // the same instant), are in the details of the issue, not changes.
        List<IssueChangeEntity> rows = changes.findByIssueIdOrderByChangedAtDescIdAsc(issue.get().id(), Limit.of(MAX_CHANGES));
        Instant created = rows.stream().filter(c -> Changelog.CREATED.equals(c.field())).map(IssueChangeEntity::changedAt)
                .findFirst().orElse(null);
        for (IssueChangeEntity c : rows) {
            if (!c.changedAt().equals(created)) {
                all.add(new Activity(CHANGE, null, c.author(), c.changedAt(), null, false, null,
                        c.field(), c.from(), c.to(), null, null, Map.of()));
            }
        }
        all.sort(Comparator.comparing(Activity::at));
        return ResponseEntity.ok(all);
    }
}
