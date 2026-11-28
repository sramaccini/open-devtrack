/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An issue with every field, as the index of the module search reads it (ADR-16): type and priority as in the API,
 * custom fields by their key, the keys it had before it moved to another project (ADR-27) and the key of its parent
 * (ADR-28), its links from both ends (ADR-29), its estimates and time spent in minutes (ADR-30) and its team (ADR-31).
 */
public record IssueDocument(UUID id, String tenantId, UUID projectId, String projectKey, String key, long number,
                            String type, String summary, String description, String status, String priority,
                            int priorityOrder, String assignee, String reporter, LocalDate dueOn, LocalDate startOn,
                            String reviewer,
                            List<String> labels, Map<String, Object> custom, Instant createdAt, Instant updatedAt,
                            boolean flagged, List<String> movedKeys, String parentKey,
                            List<IssueDocument.Link> links, Integer originalEstimate, Integer remainingEstimate,
                            int timeSpent, UUID teamId) {

    /** A link of the issue: the code of its type, whether the issue is its {@code from} end, and the other issue. */
    public record Link(String type, boolean outward, UUID other) {
    }
}
