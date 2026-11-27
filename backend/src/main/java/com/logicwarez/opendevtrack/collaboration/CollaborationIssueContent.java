/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.logicwarez.opendevtrack.shared.IssueContent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The comments and the attachments of an issue, for its export (the content of the files is not in it). */
@Component
class CollaborationIssueContent implements IssueContent {

    private final JdbcTemplate jdbc;

    CollaborationIssueContent(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> content(UUID issueId) {
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        parts.put("comments", jdbc.query("""
                SELECT author, body, created_at, updated_at FROM odt_collaboration.comment
                WHERE issue_id = ? ORDER BY created_at, id""", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("author", rs.getString("author"));
            row.put("body", rs.getString("body"));
            row.put("createdAt", instant(rs, "created_at"));
            row.put("updatedAt", instant(rs, "updated_at"));
            return row;
        }, issueId));
        parts.put("attachments", jdbc.query("""
                SELECT filename, media_type, size, uploaded_by, uploaded_at FROM odt_collaboration.attachment
                WHERE issue_id = ? ORDER BY uploaded_at, id""", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("filename", rs.getString("filename"));
            row.put("mediaType", rs.getString("media_type"));
            row.put("size", rs.getLong("size"));
            row.put("uploadedBy", rs.getString("uploaded_by"));
            row.put("uploadedAt", instant(rs, "uploaded_at"));
            return row;
        }, issueId));
        return parts;
    }

    private static Object instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
