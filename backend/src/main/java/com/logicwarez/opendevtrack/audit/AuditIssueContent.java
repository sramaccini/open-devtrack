/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import com.logicwarez.opendevtrack.shared.IssueContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The history of an issue, for its export: each change of a field, oldest first. */
@Component
class AuditIssueContent implements IssueContent {

    private final JdbcTemplate jdbc;

    AuditIssueContent(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> content(UUID issueId) {
        return Map.of("history", jdbc.query("""
                SELECT author, changed_at, field, old_value, new_value FROM odt_audit.issue_change
                WHERE issue_id = ? ORDER BY changed_at, id""", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("author", rs.getString("author"));
            row.put("changedAt", rs.getTimestamp("changed_at").toInstant());
            row.put("field", rs.getString("field"));
            row.put("from", rs.getString("old_value"));
            row.put("to", rs.getString("new_value"));
            return row;
        }, issueId));
    }
}
