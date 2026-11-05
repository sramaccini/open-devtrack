/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Messages are kept 90 days, read or not (ADR-13); deleted once a day. */
@Component
class MessageRetention {

    static final Duration KEPT = Duration.ofDays(90);
    private static final Logger LOG = LoggerFactory.getLogger(MessageRetention.class);

    private final MessageRepository messages;

    MessageRetention(MessageRepository messages) {
        this.messages = messages;
    }

    @Scheduled(cron = "${odt.notification.retention-cron:0 30 3 * * *}")
    @Transactional
    void deleteOld() {
        int deleted = messages.deleteOlderThan(Instant.now().minus(KEPT));
        if (deleted > 0) {
            LOG.info("Deleted {} messages older than {} days", deleted, KEPT.toDays());
        }
    }
}
