/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Events of the module issue, published inside the transaction of the change; listeners run after its commit. */
public final class IssueEvents {

    private IssueEvents() {
    }

    /** A field and its values before and after, as text ({@code null} when empty). */
    public record FieldChange(String field, String from, String to) {
    }

    public record IssueCreated(UUID issueId, UUID projectId, String key, String by, Instant at, List<FieldChange> fields) {
    }

    /** Only the fields that changed; never empty. */
    public record IssueChanged(UUID issueId, UUID projectId, String key, String by, Instant at, List<FieldChange> changes) {
    }

    public record IssueDeleted(UUID issueId, UUID projectId, String key, String by, Instant at) {
    }
}
