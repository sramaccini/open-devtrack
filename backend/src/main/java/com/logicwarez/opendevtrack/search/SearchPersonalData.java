/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user in the search (ADR-24, O13): the read model of the issues gets the pseudonym where the issues have it (the
 * people and the custom values equal to the name), and the saved filters of the user are deleted.
 */
@Component
class SearchPersonalData implements PersonalData {

    private final JdbcTemplate jdbc;

    SearchPersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        return Map.of("filters", jdbc.queryForList("""
                SELECT name, query, created_at, updated_at FROM odt_search.filter
                WHERE tenant_id = ? AND owner = ? ORDER BY name""", s.tenantId(), s.username()));
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        for (String column : List.of("reporter", "assignee", "reviewer")) {
            jdbc.update("UPDATE odt_search.issue_doc SET " + column + " = ? WHERE tenant_id = ? AND " + column + " = ?",
                    pseudonym, s.tenantId(), s.username());
        }
        jdbc.update("""
                UPDATE odt_search.issue_doc
                SET custom = (SELECT jsonb_object_agg(e.key, CASE WHEN e.value = to_jsonb(?::text)
                                                                  THEN to_jsonb(?::text) ELSE e.value END)
                              FROM jsonb_each(custom) e)
                WHERE tenant_id = ?
                  AND EXISTS (SELECT 1 FROM jsonb_each(custom) e WHERE e.value = to_jsonb(?::text))""",
                s.username(), pseudonym, s.tenantId(), s.username());
        jdbc.update("DELETE FROM odt_search.filter WHERE tenant_id = ? AND owner = ?", s.tenantId(), s.username());
    }
}
