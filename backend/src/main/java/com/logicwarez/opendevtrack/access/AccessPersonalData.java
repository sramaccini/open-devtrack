/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user in the access to the projects (ADR-24, O13): their memberships of projects, groups and teams are deleted,
 * and a team they lead has no lead (ADR-31); where they granted a role, added a member, made a group or a team or
 * changed a scheme, the name becomes the pseudonym.
 */
@Component
class AccessPersonalData implements PersonalData {

    private final JdbcTemplate jdbc;

    AccessPersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        parts.put("projectRoles", jdbc.queryForList("""
                SELECT project_id, role, granted_by, granted_at FROM odt_access.project_member
                WHERE username = ? AND project_id = ANY(?::uuid[]) ORDER BY granted_at""", s.username(), s.projectIds()));
        parts.put("groups", jdbc.queryForList("""
                SELECT g.name, m.added_by, m.added_at
                FROM odt_access.group_member m JOIN odt_access.user_group g ON g.id = m.group_id
                WHERE g.tenant_id = ? AND m.username = ? ORDER BY g.name""", s.tenantId(), s.username()));
        parts.put("teams", jdbc.queryForList("""
                SELECT t.name, t.lead = ? AS lead, m.added_by, m.added_at
                FROM odt_access.team t LEFT JOIN odt_access.team_member m ON m.team_id = t.id AND m.username = ?
                WHERE t.tenant_id = ? AND (m.username IS NOT NULL OR t.lead = ?) ORDER BY t.name""",
                s.username(), s.username(), s.tenantId(), s.username()));
        return parts;
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        jdbc.update("DELETE FROM odt_access.project_member WHERE username = ? AND project_id = ANY(?::uuid[])",
                s.username(), s.projectIds());
        jdbc.update("""
                DELETE FROM odt_access.group_member m USING odt_access.user_group g
                WHERE g.id = m.group_id AND g.tenant_id = ? AND m.username = ?""", s.tenantId(), s.username());
        jdbc.update("UPDATE odt_access.project_member SET granted_by = ? WHERE granted_by = ? AND project_id = ANY(?::uuid[])",
                pseudonym, s.username(), s.projectIds());
        jdbc.update("UPDATE odt_access.project_group SET granted_by = ? WHERE granted_by = ? AND project_id = ANY(?::uuid[])",
                pseudonym, s.username(), s.projectIds());
        jdbc.update("""
                UPDATE odt_access.group_member m SET added_by = ?
                FROM odt_access.user_group g
                WHERE g.id = m.group_id AND g.tenant_id = ? AND m.added_by = ?""", pseudonym, s.tenantId(), s.username());
        jdbc.update("UPDATE odt_access.user_group SET created_by = ? WHERE tenant_id = ? AND created_by = ?",
                pseudonym, s.tenantId(), s.username());
        jdbc.update("""
                DELETE FROM odt_access.team_member m USING odt_access.team t
                WHERE t.id = m.team_id AND t.tenant_id = ? AND m.username = ?""", s.tenantId(), s.username());
        jdbc.update("UPDATE odt_access.team SET lead = NULL, version = version + 1 WHERE tenant_id = ? AND lead = ?",
                s.tenantId(), s.username());
        jdbc.update("""
                UPDATE odt_access.team_member m SET added_by = ?
                FROM odt_access.team t
                WHERE t.id = m.team_id AND t.tenant_id = ? AND m.added_by = ?""", pseudonym, s.tenantId(), s.username());
        jdbc.update("UPDATE odt_access.team SET created_by = ? WHERE tenant_id = ? AND created_by = ?",
                pseudonym, s.tenantId(), s.username());
        jdbc.update("UPDATE odt_access.permission_scheme SET updated_by = ? WHERE tenant_id = ? AND updated_by = ?",
                pseudonym, s.tenantId(), s.username());
    }
}
