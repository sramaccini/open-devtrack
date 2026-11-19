/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user in the webhooks (ADR-24, O13): as the creator of a webhook, and as the actor in the bodies of the
 * deliveries still kept, the name becomes the pseudonym. The export lists the webhooks they made, without secrets.
 */
@Component
class IntegrationPersonalData implements PersonalData {

    private final JdbcTemplate jdbc;

    IntegrationPersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        return Map.of("webhooks", jdbc.queryForList("""
                SELECT id, project_id, name, created_at FROM odt_integration.webhook
                WHERE tenant_id = ? AND created_by = ? ORDER BY created_at""", s.tenantId(), s.username()));
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        jdbc.update("UPDATE odt_integration.webhook SET created_by = ? WHERE tenant_id = ? AND created_by = ?",
                pseudonym, s.tenantId(), s.username());
        // The name as a JSON string of the body, "luisa" and not "luisa.bianchi"
        jdbc.update("""
                UPDATE odt_integration.delivery d
                SET payload = replace(d.payload, to_jsonb(?::text)::text, to_jsonb(?::text)::text)
                FROM odt_integration.webhook w
                WHERE w.id = d.webhook_id AND w.tenant_id = ? AND position(to_jsonb(?::text)::text IN d.payload) > 0""",
                s.username(), pseudonym, s.tenantId(), s.username());
    }
}
