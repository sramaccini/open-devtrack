/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import com.logicwarez.opendevtrack.shared.PersonalData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user in the notifications (ADR-24, O13): their messages, with the mails not sent yet, and their preferences are
 * deleted; in the messages to the others the actor becomes the pseudonym.
 */
@Component
class NotificationPersonalData implements PersonalData {

    private final JdbcTemplate jdbc;

    NotificationPersonalData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> export(Subject s) {
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        parts.put("messages", jdbc.queryForList("""
                SELECT kind, issue_key, actor, params, created_at, read_at, emailed_at FROM odt_notification.message
                WHERE recipient = ? AND project_id = ANY(?::uuid[]) ORDER BY created_at""",
                s.username(), s.projectIds()));
        parts.put("preferences", jdbc.queryForList("""
                SELECT kind, in_app, email, language FROM odt_notification.preference
                WHERE tenant_id = ? AND username = ? ORDER BY kind""", s.tenantId(), s.username()));
        return parts;
    }

    @Override
    public void forget(Subject s, String pseudonym) {
        jdbc.update("DELETE FROM odt_notification.message WHERE recipient = ? AND project_id = ANY(?::uuid[])",
                s.username(), s.projectIds());
        jdbc.update("DELETE FROM odt_notification.preference WHERE tenant_id = ? AND username = ?",
                s.tenantId(), s.username());
        jdbc.update("UPDATE odt_notification.message SET actor = ? WHERE actor = ? AND project_id = ANY(?::uuid[])",
                pseudonym, s.username(), s.projectIds());
    }
}
