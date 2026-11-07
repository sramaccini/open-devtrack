/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.reporting;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;

/** The tables of {@code odt_reporting} (ADR-17) for jOOQ, declared by hand as those of {@code search}. */
final class Tables {

    private Tables() {
    }

    /** {@code odt_reporting.issue_state}: each issue as the reports see it. */
    static final class State {
        static final Table<?> TABLE = table(name("odt_reporting", "issue_state"));
        static final Field<UUID> ISSUE_ID = column("issue_id", SQLDataType.UUID);
        static final Field<String> TENANT_ID = column("tenant_id", SQLDataType.VARCHAR);
        static final Field<UUID> PROJECT_ID = column("project_id", SQLDataType.UUID);
        static final Field<String> TYPE = column("type", SQLDataType.VARCHAR);
        static final Field<String> PRIORITY = column("priority", SQLDataType.VARCHAR);
        static final Field<String> STATUS = column("status", SQLDataType.VARCHAR);
        static final Field<String> CATEGORY = column("category", SQLDataType.VARCHAR);
        static final Field<String> ASSIGNEE = column("assignee", SQLDataType.VARCHAR);
        static final Field<Integer> POINTS = column("points", SQLDataType.INTEGER);
        static final Field<UUID> SPRINT_ID = column("sprint_id", SQLDataType.UUID);
        static final Field<LocalDate> CREATED_ON = column("created_on", SQLDataType.LOCALDATE);
        static final Field<LocalDate> STARTED_ON = column("started_on", SQLDataType.LOCALDATE);
        static final Field<LocalDate> RESOLVED_ON = column("resolved_on", SQLDataType.LOCALDATE);

        private static <T> Field<T> column(String column, org.jooq.DataType<T> type) {
            return field(name("odt_reporting", "issue_state", column), type);
        }

        private State() {
        }
    }

    /** {@code odt_reporting.daily_snapshot}: a project (sprint null) or its active sprint at the end of a day. */
    static final class Snapshot {
        static final Table<?> TABLE = table(name("odt_reporting", "daily_snapshot"));
        static final Field<UUID> PROJECT_ID = column("project_id", SQLDataType.UUID);
        static final Field<UUID> SPRINT_ID = column("sprint_id", SQLDataType.UUID);
        static final Field<LocalDate> DAY = column("day", SQLDataType.LOCALDATE);
        static final Field<Integer> TODO = column("todo", SQLDataType.INTEGER);
        static final Field<Integer> IN_PROGRESS = column("in_progress", SQLDataType.INTEGER);
        static final Field<Integer> DONE = column("done", SQLDataType.INTEGER);
        static final Field<Integer> REMAINING_POINTS = column("remaining_points", SQLDataType.INTEGER);
        static final Field<?>[] ALL = {PROJECT_ID, SPRINT_ID, DAY, TODO, IN_PROGRESS, DONE, REMAINING_POINTS};

        private static <T> Field<T> column(String column, org.jooq.DataType<T> type) {
            return field(name("odt_reporting", "daily_snapshot", column), type);
        }

        private Snapshot() {
        }
    }

    /** {@code odt_reporting.sprint_result}: each sprint from its start. */
    static final class Result {
        static final Table<?> TABLE = table(name("odt_reporting", "sprint_result"));
        static final Field<UUID> SPRINT_ID = column("sprint_id", SQLDataType.UUID);
        static final Field<UUID> PROJECT_ID = column("project_id", SQLDataType.UUID);
        static final Field<String> NAME = column("name", SQLDataType.VARCHAR);
        static final Field<LocalDate> STARTS_ON = column("starts_on", SQLDataType.LOCALDATE);
        static final Field<LocalDate> ENDS_ON = column("ends_on", SQLDataType.LOCALDATE);
        static final Field<Integer> COMMITTED_POINTS = column("committed_points", SQLDataType.INTEGER);
        static final Field<Integer> DONE_POINTS = column("done_points", SQLDataType.INTEGER);
        static final Field<Integer> CARRIED_POINTS = column("carried_points", SQLDataType.INTEGER);
        static final Field<OffsetDateTime> COMPLETED_AT = column("completed_at", SQLDataType.TIMESTAMPWITHTIMEZONE);
        static final Field<?>[] ALL = {SPRINT_ID, PROJECT_ID, NAME, STARTS_ON, ENDS_ON, COMMITTED_POINTS, DONE_POINTS,
                CARRIED_POINTS, COMPLETED_AT};

        private static <T> Field<T> column(String column, org.jooq.DataType<T> type) {
            return field(name("odt_reporting", "sprint_result", column), type);
        }

        private Result() {
        }
    }
}
