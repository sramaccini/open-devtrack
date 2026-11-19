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
 * The user in the access to the projects (ADR-24, O13): their memberships of projects and groups are deleted; where
 * they granted a role, added a member, made a group or changed a scheme, the name becomes the pseudonym.
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
        jdbc.update("UPDATE odt_access.permission_scheme SET updated_by = ? WHERE tenant_id = ? AND updated_by = ?",
                pseudonym, s.tenantId(), s.username());
    }
}
