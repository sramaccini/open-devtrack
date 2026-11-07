/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL and Redis in containers, the same versions as e2e/authz, for the tests that start the application. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:8.2").withExposedPorts(6379);
    }

    /**
     * Waits until the listeners that run after the commit (history, search, reports, messages) have finished what the
     * last test started, so that cleaning the tables does not meet them: their publications leave {@code
     * odt.event_publication} when they complete. At most 10 seconds; a publication that failed stays and is not waited
     * for longer.
     */
    public static void awaitListeners(JdbcTemplate jdbc) {
        for (int i = 0; i < 200; i++) {
            Integer open = jdbc.queryForObject("SELECT count(*) FROM odt.event_publication WHERE completion_date IS NULL",
                    Integer.class);
            if (open == null || open == 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
