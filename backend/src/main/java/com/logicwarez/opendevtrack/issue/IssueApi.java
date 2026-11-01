/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Requests and answers of {@link IssueController}. */
final class IssueApi {

    private IssueApi() {
    }

    /** A row of the issue list. */
    record IssueRow(String key, long number, String type, String summary, String status, String priority,
                    String assignee, Instant updatedAt) {
    }

    record IssueView(String key, String project, long number, String type, String summary, String description,
                     String status, String priority, String assignee, String reporter, List<String> labels,
                     Instant createdAt, Instant updatedAt, long version, boolean watching,
                     @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A transition that can run now: its code, the status it leads to and the version of the issue to send back. */
    record TransitionView(String code, String to, long version) {
    }

    record TransitionRequest(String transition, Long version) {
    }

    /** Fields of a new issue or of a change; {@code version} only for a change. */
    record IssueRequest(String type, String summary, String description, String priority, String assignee,
                        List<String> labels, Long version) {
    }
}
