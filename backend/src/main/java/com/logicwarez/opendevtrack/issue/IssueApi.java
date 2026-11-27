/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Requests and answers of {@link IssueController}. */
final class IssueApi {

    private IssueApi() {
    }

    /** A row of the issue list; {@code overdue} when past its due date and not done (ADR-19). */
    record IssueRow(String key, long number, String type, String summary, String status, String priority,
                    String assignee, LocalDate dueOn, String overdue, List<String> labels, Instant updatedAt,
                    boolean flagged) {
    }

    /** A label already used in a project, for the suggestions of the tags field of Quadrava (request Q5). */
    record LabelRow(String name) {
    }

    record IssueView(String key, String project, long number, String type, String summary, String description,
                     String status, String priority, String assignee, String reporter, LocalDate dueOn,
                     String reviewer, List<String> labels, Map<String, Object> custom, Instant createdAt, Instant updatedAt, long version, boolean watching,
                     boolean flagged, long votes, boolean voted,
                     @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /**
     * A transition that can run now: its code, the status it leads to and its category (todo, in_progress, done), and the
     * version of the issue to send back.
     */
    record TransitionView(String code, String to, String category, long version) {
    }

    record TransitionRequest(String transition, Long version) {
    }

    /**
     * The workflow an issue follows, to draw it: its name, the status of the issue and its category, the statuses in
     * order with their category, and every transition ({@code from} null when it starts from any status), without their
     * guards.
     */
    record IssueWorkflow(String name, String status, String category, List<IssueWorkflowStatus> statuses,
                         List<IssueWorkflowTransition> transitions) {
    }

    record IssueWorkflowStatus(String code, String category) {
    }

    record IssueWorkflowTransition(String code, String from, String to) {
    }

    /**
     * Fields of a change of some of them, with the version read: an absent field keeps its value, null clears it (not
     * the type, the priority or the summary), and the custom fields given leave the others as they are. A class, not a
     * record, to know which fields were sent: Jackson calls the setter of each field present, null included.
     */
    static final class IssuePatch {
        private final Set<String> given = new HashSet<>();
        private String type;
        private String summary;
        private String description;
        private String priority;
        private String assignee;
        private String dueOn;
        private String reviewer;
        private String reporter;
        private List<String> labels;
        private Map<String, Object> custom;
        private Long version;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
            given.add("type");
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
            given.add("summary");
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
            given.add("description");
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(String priority) {
            this.priority = priority;
            given.add("priority");
        }

        public String getAssignee() {
            return assignee;
        }

        public void setAssignee(String assignee) {
            this.assignee = assignee;
            given.add("assignee");
        }

        public String getDueOn() {
            return dueOn;
        }

        public void setDueOn(String dueOn) {
            this.dueOn = dueOn;
            given.add("dueOn");
        }

        public String getReviewer() {
            return reviewer;
        }

        public void setReviewer(String reviewer) {
            this.reviewer = reviewer;
            given.add("reviewer");
        }

        public String getReporter() {
            return reporter;
        }

        public void setReporter(String reporter) {
            this.reporter = reporter;
            given.add("reporter");
        }

        public List<String> getLabels() {
            return labels;
        }

        public void setLabels(List<String> labels) {
            this.labels = labels;
            given.add("labels");
        }

        public Map<String, Object> getCustom() {
            return custom;
        }

        public void setCustom(Map<String, Object> custom) {
            this.custom = custom;
            given.add("custom");
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
            given.add("version");
        }

        /** Whether the body named the field, even as null. */
        boolean has(String field) {
            return given.contains(field);
        }
    }

    /** Who to share an issue with (members of its project) and an optional message for them. */
    record ShareRequest(List<String> users, String message) {
    }

    /**
     * A move to another project (ADR-27): the key of the target, the type there (the same without it), the status in
     * the workflow of the target (the same code, else the same category, else the first status, without it), the version
     * read, the values the target cannot keep that may be dropped ({@code fields.drop}, custom fields by key), and
     * {@code dryRun} to learn what would be dropped or cleared without moving.
     */
    record ProjectMoveRequest(String project, String type, String status, Long version, ProjectMoveFields fields, Boolean dryRun) {
    }

    record ProjectMoveFields(List<String> drop) {
    }

    /**
     * What a move did, or would do with {@code dryRun}: the key now (or the one it would get), the one before, the
     * custom fields dropped, the people cleared ({@code assignee}, {@code reviewer}), all of them in {@code lostText} in
     * the language of the user, and the issue after the move (null for a dry run).
     */
    record ProjectMoveAnswer(String key, String movedFrom, List<String> dropped, List<String> cleared, String lostText,
                      IssueView issue) {
    }

    /** The summary of a clone; without it «CLONE - » and the summary of the original. */
    record CloneRequest(String summary) {
    }

    /** Fields of a new issue or of a change; {@code version} only for a change. */
    record IssueRequest(String type, String summary, String description, String priority, String assignee,
                        String dueOn, String reviewer, List<String> labels, Map<String, Object> custom, Long version) {
    }
}
