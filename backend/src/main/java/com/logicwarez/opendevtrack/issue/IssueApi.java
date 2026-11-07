/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Requests and answers of {@link IssueController}. */
final class IssueApi {

    private IssueApi() {
    }

    /** A row of the issue list; {@code overdue} when past its due date and not done (ADR-19). */
    record IssueRow(String key, long number, String type, String summary, String status, String priority,
                    String assignee, LocalDate dueOn, String overdue, List<String> labels, Instant updatedAt) {
    }

    /** A label already used in a project, for the suggestions of the tags field of Quadrava (request Q5). */
    record LabelRow(String name) {
    }

    record IssueView(String key, String project, long number, String type, String summary, String description,
                     String status, String priority, String assignee, String reporter, LocalDate dueOn,
                     String reviewer, List<String> labels, Map<String, Object> custom, Instant createdAt, Instant updatedAt, long version, boolean watching,
                     @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A transition that can run now: its code, the status it leads to and the version of the issue to send back. */
    record TransitionView(String code, String to, long version) {
    }

    record TransitionRequest(String transition, Long version) {
    }

    /** Fields of a new issue or of a change; {@code version} only for a change. */
    record IssueRequest(String type, String summary, String description, String priority, String assignee,
                        String dueOn, String reviewer, List<String> labels, Map<String, Object> custom, Long version) {
    }
}
