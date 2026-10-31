/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueRef;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The changelog of an issue, for whoever sees the issue ({@code odt.issue.browse} in its project). */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class HistoryController {

    private static final int MAX_ROWS = 500;

    private final Issues issues;
    private final IssueChangeRepository changes;
    private final ProjectAccess access;
    private final CurrentUser user;

    HistoryController(Issues issues, IssueChangeRepository changes, ProjectAccess access, CurrentUser user) {
        this.issues = issues;
        this.changes = changes;
        this.access = access;
        this.user = user;
    }

    record Change(Instant at, String author, String field, String from, String to) {
    }

    /** Newest first: one row per field changed, and one {@code created} row with the key. */
    @GetMapping("/api/issues/{issueKey}/history")
    ResponseEntity<?> history(@PathVariable String issueKey) {
        Optional<IssueRef> issue = issues.find(issueKey);
        if (issue.isEmpty()) {
            return Problems.notFound("Issue not found");
        }
        if (!access.permissions(issue.get().projectId(), user.username()).contains("odt.issue.browse")) {
            return Problems.forbidden("Only the members of the project see its issues");
        }
        return ResponseEntity.ok(changes.findByIssueIdOrderByChangedAtDescIdAsc(issue.get().id(), Limit.of(MAX_ROWS)).stream()
                .map(c -> new Change(c.changedAt(), c.author(), c.field(), c.from(), c.to()))
                .toList());
    }
}
