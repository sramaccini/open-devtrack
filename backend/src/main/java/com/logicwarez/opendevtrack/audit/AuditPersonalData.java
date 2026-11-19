/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user in the histories (ADR-24, O13): in the changes of the issues the author and the values of the fields that
 * name a user become the pseudonym; in the audit log of the administration the actor, the target and the values
 * before and after, where a value is the user name. The rows stay, so the histories stay whole.
 */
@Component
class AuditPersonalData implements PersonalData {

    /** The fields of a change whose values are user names: the custom fields only when the value is the name. */
    private static final String USER_FIELDS = "(field IN ('assignee', 'reviewer', 'reporter') OR field LIKE 'custom.%')";

    private final JdbcTemplate jdbc;

    AuditPersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        parts.put("changes", jdbc.queryForList("""
                SELECT issue_id, field, old_value, new_value, changed_at FROM odt_audit.issue_change
                WHERE author = ? AND issue_id = ANY(?::uuid[]) ORDER BY changed_at""", s.username(), s.issueIds()));
        parts.put("administration", jdbc.queryForList("""
                SELECT project_id, action, target, before, after, occurred_at FROM odt_audit.admin_event
                WHERE tenant_id = ? AND actor = ? ORDER BY occurred_at""", s.tenantId(), s.username()));
        return parts;
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        String issues = s.issueIds();
        jdbc.update("UPDATE odt_audit.issue_change SET author = ? WHERE author = ? AND issue_id = ANY(?::uuid[])",
                pseudonym, s.username(), issues);
        jdbc.update("UPDATE odt_audit.issue_change SET old_value = ? WHERE " + USER_FIELDS
                + " AND old_value = ? AND issue_id = ANY(?::uuid[])", pseudonym, s.username(), issues);
        jdbc.update("UPDATE odt_audit.issue_change SET new_value = ? WHERE " + USER_FIELDS
                + " AND new_value = ? AND issue_id = ANY(?::uuid[])", pseudonym, s.username(), issues);

        jdbc.update("UPDATE odt_audit.admin_event SET actor = ? WHERE tenant_id = ? AND actor = ?",
                pseudonym, s.tenantId(), s.username());
        jdbc.update("UPDATE odt_audit.admin_event SET target = ? WHERE tenant_id = ? AND target = ?",
                pseudonym, s.tenantId(), s.username());
        // A value of the JSON equal to the name, as a JSON string: "luisa" and not "luisa.bianchi"
        for (String column : List.of("before", "after")) {
            jdbc.update("UPDATE odt_audit.admin_event SET " + column + " = replace(" + column
                    + "::text, to_jsonb(?::text)::text, to_jsonb(?::text)::text)::jsonb WHERE tenant_id = ? AND position("
                    + "to_jsonb(?::text)::text IN " + column + "::text) > 0",
                    s.username(), pseudonym, s.tenantId(), s.username());
        }
    }
}
