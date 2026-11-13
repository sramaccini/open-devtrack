/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicwarez.opendevtrack.TestInfrastructure;
import com.logicwarez.opendevtrack.platform.SecretBox;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The secrets stored in clear before ADR-23 are encrypted at start; those already encrypted stay as they are. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
class SecretsAtRestTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SecretsAtRest secretsAtRest;

    @Autowired
    SecretBox box;

    @Test
    void theSecretsInClearAreEncryptedAtStart() {
        jdbc.execute("TRUNCATE odt_integration.webhook CASCADE");
        UUID clear = UUID.randomUUID();
        UUID sealed = UUID.randomUUID();
        String already = box.seal("whsec_sealed");
        insert(clear, "whsec_clear", "whsec_before");
        insert(sealed, already, null);

        secretsAtRest.run(null);

        String secret = jdbc.queryForObject("SELECT secret FROM odt_integration.webhook WHERE id = ?", String.class, clear);
        String previous = jdbc.queryForObject("SELECT previous_secret FROM odt_integration.webhook WHERE id = ?",
                String.class, clear);
        assertThat(secret).startsWith("enc:1:");
        assertThat(box.open(secret)).isEqualTo("whsec_clear");
        assertThat(box.open(previous)).isEqualTo("whsec_before");
        assertThat(jdbc.queryForObject("SELECT secret FROM odt_integration.webhook WHERE id = ?", String.class, sealed))
                .isEqualTo(already);
        assertThat(jdbc.queryForObject("SELECT version FROM odt_integration.webhook WHERE id = ?", Long.class, clear))
                .isZero();
    }

    private void insert(UUID id, String secret, String previous) {
        jdbc.update("INSERT INTO odt_integration.webhook (id, tenant_id, project_id, name, url, events, secret,"
                + " previous_secret, previous_until, created_by, created_at, updated_at, version)"
                + " VALUES (?, 'ALPHA', ?, ?, 'https://example.org/hook', '{*}', ?, ?, now(), 'mario', now(), now(), 0)",
                id, UUID.randomUUID(), "Hook " + id, secret, previous);
    }
}
