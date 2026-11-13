/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import com.logicwarez.opendevtrack.platform.SecretBox;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * At start, writes again under the current key the secrets of the webhooks stored in clear (before ADR-23) or under a
 * previous key (after a rotation of {@code ODT_SECRET_KEY}). Plain SQL on the two columns: the rows are not otherwise
 * touched, so their version does not change.
 */
@Component
class SecretsAtRest implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsAtRest.class);

    private final JdbcTemplate jdbc;
    private final SecretBox box;

    SecretsAtRest(JdbcTemplate jdbc, SecretBox box) {
        this.jdbc = jdbc;
        this.box = box;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, secret, previous_secret FROM odt_integration.webhook FOR UPDATE");
        int resealed = 0;
        for (Map<String, Object> row : rows) {
            String secret = (String) row.get("secret");
            String previous = (String) row.get("previous_secret");
            if (box.stale(secret) || box.stale(previous)) {
                jdbc.update("UPDATE odt_integration.webhook SET secret = ?, previous_secret = ? WHERE id = ?",
                        box.reseal(secret).orElse(secret), box.reseal(previous).orElse(previous), (UUID) row.get("id"));
                resealed++;
            }
        }
        if (resealed > 0) {
            LOG.info("Encrypted again the secrets of {} webhooks under the current key", resealed);
        }
    }
}
