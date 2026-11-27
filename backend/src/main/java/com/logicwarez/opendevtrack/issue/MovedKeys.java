/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.IssueKeys;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The keys an issue had before it moved to another project (ADR-27, {@code odt_issue.moved_key}): the source project
 * and the number there. Every lookup by key goes through {@link Issues#findEntity}, which looks here when the project
 * has no issue with that number.
 */
@Component
class MovedKeys implements IssueKeys {

    static final Pattern ISSUE_KEY = Pattern.compile("^([A-Z][A-Z0-9]{1,9})-([1-9][0-9]{0,17})$");

    private final JdbcClient jdbc;
    private final Projects projects;

    MovedKeys(JdbcClient jdbc, Projects projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    /** The number {@code number} of the project now leads to the issue. */
    void add(String tenantId, UUID projectId, long number, UUID issueId, Instant at) {
        jdbc.sql("INSERT INTO odt_issue.moved_key (tenant_id, project_id, number, issue_id, moved_at) VALUES (?, ?, ?, ?, ?)")
                .params(tenantId, projectId, number, issueId, Timestamp.from(at)).update();
    }

    /** The issue that had this number in the project before it moved. */
    Optional<UUID> issueOf(UUID projectId, long number) {
        return jdbc.sql("SELECT issue_id FROM odt_issue.moved_key WHERE project_id = ? AND number = ?")
                .params(projectId, number).query(UUID.class).optional();
    }

    /** The old keys of the issue, the oldest first. */
    List<String> of(UUID issueId) {
        record Old(UUID projectId, long number) {
        }
        return jdbc.sql("SELECT project_id, number FROM odt_issue.moved_key WHERE issue_id = ? ORDER BY moved_at, number")
                .param(issueId).query(Old.class).list().stream()
                .flatMap(o -> projects.byId(o.projectId()).map(p -> p.key() + "-" + o.number()).stream())
                .toList();
    }

    @Override
    public Optional<String> projectKeyOf(String issueKey) {
        Matcher m = ISSUE_KEY.matcher(issueKey);
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<ProjectRef> project = projects.find(m.group(1));
        if (project.isEmpty()) {
            return Optional.empty();
        }
        long number = Long.parseLong(m.group(2));
        Optional<UUID> here = jdbc.sql("SELECT project_id FROM odt_issue.issue WHERE project_id = ? AND number = ?")
                .params(project.get().id(), number).query(UUID.class).optional();
        if (here.isPresent()) {
            return Optional.of(project.get().key());
        }
        return issueOf(project.get().id(), number)
                .flatMap(id -> jdbc.sql("SELECT project_id FROM odt_issue.issue WHERE id = ?").param(id)
                        .query(UUID.class).optional())
                .flatMap(projects::get)
                .map(ProjectRef::key);
    }
}
