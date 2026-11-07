/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An issue with every field, as the index of the module search reads it (ADR-16): type and priority as in the API,
 * custom fields by their key.
 */
public record IssueDocument(UUID id, String tenantId, UUID projectId, String projectKey, String key, long number,
                            String type, String summary, String description, String status, String priority,
                            int priorityOrder, String assignee, String reporter, List<String> labels,
                            Map<String, Object> custom, Instant createdAt, Instant updatedAt) {
}
