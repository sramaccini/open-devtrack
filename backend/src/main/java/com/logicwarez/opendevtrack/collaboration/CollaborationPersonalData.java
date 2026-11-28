/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user in comments and attachments (ADR-24, O13): the author and the uploader become the pseudonym, the texts and
 * the files stay; the mentions of the user are deleted. The export has the comments and the metadata of the files.
 */
@Component
class CollaborationPersonalData implements PersonalData {

    private final JdbcTemplate jdbc;

    CollaborationPersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        parts.put("comments", jdbc.queryForList("""
                SELECT id, issue_id, body, created_at, updated_at FROM odt_collaboration.comment
                WHERE author = ? AND issue_id = ANY(?::uuid[]) ORDER BY created_at""", s.username(), s.issueIds()));
        parts.put("worklogs", jdbc.queryForList("""
                SELECT id, issue_id, started_at, minutes, comment, created_at, updated_at FROM odt_collaboration.worklog
                WHERE author = ? AND issue_id = ANY(?::uuid[]) ORDER BY started_at""", s.username(), s.issueIds()));
        parts.put("attachments", jdbc.queryForList("""
                SELECT id, issue_id, filename, media_type, size, sha256, uploaded_at FROM odt_collaboration.attachment
                WHERE uploaded_by = ? AND project_id = ANY(?::uuid[]) ORDER BY uploaded_at""",
                s.username(), s.projectIds()));
        parts.put("mentions", jdbc.queryForList("""
                SELECT c.id AS comment_id, c.issue_id, c.author, c.created_at
                FROM odt_collaboration.mention m JOIN odt_collaboration.comment c ON c.id = m.comment_id
                WHERE m.username = ? AND c.issue_id = ANY(?::uuid[]) ORDER BY c.created_at""",
                s.username(), s.issueIds()));
        return parts;
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        jdbc.update("""
                DELETE FROM odt_collaboration.mention m USING odt_collaboration.comment c
                WHERE c.id = m.comment_id AND m.username = ? AND c.issue_id = ANY(?::uuid[])""",
                s.username(), s.issueIds());
        jdbc.update("""
                UPDATE odt_collaboration.comment SET author = ?, version = version + 1
                WHERE author = ? AND issue_id = ANY(?::uuid[])""", pseudonym, s.username(), s.issueIds());
        jdbc.update("""
                UPDATE odt_collaboration.worklog SET author = ?, version = version + 1
                WHERE author = ? AND issue_id = ANY(?::uuid[])""", pseudonym, s.username(), s.issueIds());
        jdbc.update("""
                UPDATE odt_collaboration.attachment SET uploaded_by = ?
                WHERE uploaded_by = ? AND project_id = ANY(?::uuid[])""", pseudonym, s.username(), s.projectIds());
    }
}
