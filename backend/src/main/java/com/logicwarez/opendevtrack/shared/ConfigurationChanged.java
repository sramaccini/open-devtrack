/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A change to the configuration of a tenant or of a project (ADR-22), published by the module that made it and written
 * to the audit log of the administration after the commit.
 *
 * @param tenantId the tenant
 * @param projectId the project, or null for a change of the tenant (a group, a permission scheme)
 * @param action what changed, for example {@code member.role_changed}
 * @param target what it changed, in words: a user name, the name of a group, of a board, of a webhook
 * @param by the user who changed it
 * @param at when
 * @param before the values before, empty for something new; never a secret
 * @param after the values after, empty for something removed; never a secret
 */
public record ConfigurationChanged(String tenantId, UUID projectId, String action, String target, String by, Instant at,
        Map<String, Object> before, Map<String, Object> after) {

    /** The value written instead of a secret that changed. */
    public static final String ROTATED = "rotated";

    public ConfigurationChanged {
        before = before == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = after == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(after));
    }

    /** Names and values in turn, nulls allowed: {@code values("name", "Team", "description", null)}. */
    public static Map<String, Object> values(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Names and values in turn");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return values;
    }
}
