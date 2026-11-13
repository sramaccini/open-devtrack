/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import com.logicwarez.opendevtrack.shared.ConfigurationChanged;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit log of the administration (ADR-22) from the events of the modules, after their commit and in a
 * transaction of its own, and deletes once a night what is older than {@code odt.audit.retention} (2 years).
 */
@Component
class AdminEvents {

    private static final Logger LOG = LoggerFactory.getLogger(AdminEvents.class);

    private final AdminEventRepository events;
    private final Duration retention;

    AdminEvents(AdminEventRepository events, @Value("${odt.audit.retention:P730D}") Duration retention) {
        this.events = events;
        this.retention = retention;
    }

    @ApplicationModuleListener
    void on(ConfigurationChanged event) {
        events.save(new AdminEventEntity(event.tenantId(), event.projectId(), event.action(), event.target(), event.by(),
                event.at(), event.before(), event.after()));
    }

    @Scheduled(cron = "${odt.audit.retention-cron:0 40 3 * * *}")
    @Transactional
    void deleteOld() {
        int deleted = events.deleteOlderThan(Instant.now().minus(retention));
        if (deleted > 0) {
            LOG.info("Deleted {} administration events older than {} days", deleted, retention.toDays());
        }
    }
}
