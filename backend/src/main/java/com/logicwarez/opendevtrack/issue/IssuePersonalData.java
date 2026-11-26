/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user in the issues (ADR-24, O13): reporter, assignee, reviewer and the custom fields of type user become the
 * pseudonym, with a new version so that a form opened before is refused; the links they made keep the pseudonym; their
 * watches and votes are deleted.
 */
@Component
class IssuePersonalData implements PersonalData {

    private final JdbcTemplate jdbc;

    IssuePersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        parts.put("issues", jdbc.queryForList("""
                SELECT id, project_id, number, type, summary, status, priority, reporter, assignee, reviewer, created_at,
                       updated_at
                FROM odt_issue.issue
                WHERE tenant_id = ? AND (reporter = ? OR assignee = ? OR reviewer = ?)
                ORDER BY project_id, number""", s.tenantId(), s.username(), s.username(), s.username()));
        parts.put("watches", jdbc.queryForList("""
                SELECT w.issue_id, w.created_at
                FROM odt_issue.watcher w JOIN odt_issue.issue i ON i.id = w.issue_id
                WHERE i.tenant_id = ? AND w.username = ?
                ORDER BY w.created_at""", s.tenantId(), s.username()));
        parts.put("votes", jdbc.queryForList("""
                SELECT v.issue_id, v.created_at
                FROM odt_issue.vote v JOIN odt_issue.issue i ON i.id = v.issue_id
                WHERE i.tenant_id = ? AND v.username = ?
                ORDER BY v.created_at""", s.tenantId(), s.username()));
        parts.put("links", jdbc.queryForList("""
                SELECT l.from_id, l.to_id, l.link_type, l.created_at
                FROM odt_issue.issue_link l JOIN odt_issue.issue i ON i.id = l.from_id
                WHERE i.tenant_id = ? AND l.created_by = ?
                ORDER BY l.created_at""", s.tenantId(), s.username()));
        return parts;
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        for (String column : List.of("reporter", "assignee", "reviewer")) {
            jdbc.update("UPDATE odt_issue.issue SET " + column + " = ?, version = version + 1 WHERE tenant_id = ? AND "
                    + column + " = ?", pseudonym, s.tenantId(), s.username());
        }
        // One field of type user per issue at each pass: an issue with the user in two fields takes two passes
        int changed;
        do {
            changed = jdbc.update("""
                    UPDATE odt_issue.issue i
                    SET custom = jsonb_set(i.custom, ARRAY[f.key], to_jsonb(?::text)), version = i.version + 1
                    FROM odt_issue.field_definition f
                    WHERE f.project_id = i.project_id AND f.type = 'USER' AND i.tenant_id = ?
                      AND i.custom ->> f.key = ?""", pseudonym, s.tenantId(), s.username());
        } while (changed > 0);
        jdbc.update("""
                UPDATE odt_issue.issue_link l SET created_by = ?
                FROM odt_issue.issue i
                WHERE i.id = l.from_id AND i.tenant_id = ? AND l.created_by = ?""", pseudonym, s.tenantId(), s.username());
        jdbc.update("""
                DELETE FROM odt_issue.watcher w USING odt_issue.issue i
                WHERE i.id = w.issue_id AND i.tenant_id = ? AND w.username = ?""", s.tenantId(), s.username());
        jdbc.update("""
                DELETE FROM odt_issue.vote v USING odt_issue.issue i
                WHERE i.id = v.issue_id AND i.tenant_id = ? AND v.username = ?""", s.tenantId(), s.username());
    }
}
