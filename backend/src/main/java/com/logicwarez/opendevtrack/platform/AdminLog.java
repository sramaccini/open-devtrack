/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import com.logicwarez.opendevtrack.shared.ConfigurationChanged;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the changes to the configuration (ADR-22) with the user and the tenant of the session; the module audit
 * writes them after the commit. Called inside the transaction of the change, so a change rolled back logs nothing.
 */
@Component
public class AdminLog {

    private final CurrentUser user;
    private final ApplicationEventPublisher events;

    AdminLog(CurrentUser user, ApplicationEventPublisher events) {
        this.user = user;
        this.events = events;
    }

    /** A change of the tenant, outside any project. */
    public void tenant(String action, String target, Map<String, Object> before, Map<String, Object> after) {
        project(null, action, target, before, after);
    }

    /** A change of a project; nothing is published when the values before and after are the same. */
    public void project(UUID projectId, String action, String target, Map<String, Object> before, Map<String, Object> after) {
        if (before != null && !before.isEmpty() && Objects.equals(before, after)) {
            return;
        }
        events.publishEvent(new ConfigurationChanged(user.tenantId(), projectId, action, target, user.username(),
                Instant.now(), before, after));
    }
}
