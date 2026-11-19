/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.reporting;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The user in the read model of the reports (ADR-24, O13): the assignee becomes the pseudonym; nothing to export. */
@Component
class ReportingPersonalData implements PersonalData {

    private final JdbcTemplate jdbc;

    ReportingPersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        return Map.of();
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        jdbc.update("UPDATE odt_reporting.issue_state SET assignee = ? WHERE tenant_id = ? AND assignee = ?",
                pseudonym, s.tenantId(), s.username());
    }
}
