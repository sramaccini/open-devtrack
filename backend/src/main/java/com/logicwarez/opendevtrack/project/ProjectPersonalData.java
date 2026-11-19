/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user in the projects (ADR-24, O13): as their creator or lead the name becomes the pseudonym; a project whose lead
 * is a pseudonym waits for its administrators to name a new one.
 */
@Component
class ProjectPersonalData implements PersonalData {

    private final JdbcTemplate jdbc;

    ProjectPersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        return Map.of("projects", jdbc.queryForList("""
                SELECT id, project_key, name, lead, created_by, created_at FROM odt_project.project
                WHERE tenant_id = ? AND (lead = ? OR created_by = ?) ORDER BY project_key""",
                s.tenantId(), s.username(), s.username()));
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        for (String column : List.of("lead", "created_by")) {
            jdbc.update("UPDATE odt_project.project SET " + column + " = ? WHERE tenant_id = ? AND " + column + " = ?",
                    pseudonym, s.tenantId(), s.username());
        }
    }
}
