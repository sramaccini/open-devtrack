/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Requests and answers of the boards, the backlog and the sprints (ADR-15). */
final class AgileApi {

    private AgileApi() {
    }

    /** A board in the list of the boards of the user, or of a project; {@code columns} in the text of the dialog. */
    record BoardRow(String id, String project, String projectName, String name, String kind, String lane, String columns,
                    String filter, long version, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A board for its screen: the active sprint for a scrum board, the ODQL filter of its cards. */
    record BoardView(String id, String project, String projectName, String name, String kind, String lane, String sprint,
                     LocalDate sprintEndsOn, String filter, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /**
     * {@code columns}: one per line, {@code Name: status, status | limit}; empty for one column per status.
     * {@code filter}: an ODQL query, only the issues it finds are cards (ADR-16); empty for every issue.
     */
    record BoardRequest(String name, String kind, String lane, String columns, String filter, Long version) {
    }

    /** A column of the kanban of Quadrava ({@code columnsFrom}). */
    record ColumnRow(String value, String label, String tone, Integer limit) {
    }

    /** A card of a board or of the backlog; {@code column} is the value of its column. */
    record CardRow(String key, String summary, String type, String priority, String assignee, String status,
                   List<String> labels, Integer points, String column, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A card dropped on {@code column}, between the cards {@code beforeId} and {@code afterId} (issue keys). */
    record MoveRequest(String column, String beforeId, String afterId) {
    }

    record SprintRow(String id, String name, String goal, String state, LocalDate startsOn, LocalDate endsOn,
                     Instant startedAt, Instant completedAt, int issues, int points, long version,
                     @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    record SprintRequest(String name, String goal, LocalDate startsOn, LocalDate endsOn, Long version) {
    }

    /**
     * Where the issues not done of a completed sprint go: {@code backlog}, {@code next} (the first planned sprint, else
     * the backlog) or the id of a planned sprint.
     */
    record CompleteRequest(String moveTo) {
    }

    record PlanView(Integer points, String sprint, String sprintState, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    record PlanRequest(Integer points) {
    }
}
