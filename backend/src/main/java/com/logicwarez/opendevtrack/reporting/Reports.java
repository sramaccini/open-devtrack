/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.reporting;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.percentileCont;

import com.logicwarez.opendevtrack.reporting.Tables.Result;
import com.logicwarez.opendevtrack.reporting.Tables.Snapshot;
import com.logicwarez.opendevtrack.reporting.Tables.State;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.SQLDataType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports of a project (ADR-17), all from the rows of {@code odt_reporting}: nothing is computed from the tables of
 * other modules when a report is opened. A day with no snapshot takes the one before (the nightly job had not run).
 */
@Service
@Transactional(readOnly = true)
class Reports {

    static final int DAYS = 30;
    static final int SPRINTS = 6;

    private final DSLContext sql;
    private final ZoneId zone;

    Reports(DSLContext sql, @Value("${odt.reporting.zone:UTC}") String zone) {
        this.sql = sql;
        this.zone = ZoneId.of(zone);
    }

    LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Open, in progress, resolved in the last 30 days, the median days from start to resolution of those. */
    Map<String, Object> summary(UUID projectId) {
        LocalDate since = today().minusDays(DAYS - 1);
        Record counts = sql.select(
                        count().filterWhere(State.CATEGORY.ne("done")),
                        count().filterWhere(State.CATEGORY.eq("in_progress")),
                        count().filterWhere(State.RESOLVED_ON.ge(since)))
                .from(State.TABLE).where(State.PROJECT_ID.eq(projectId)).fetchOne();
        Field<Integer> days = field("{0} - {1}", SQLDataType.INTEGER, State.RESOLVED_ON, State.STARTED_ON);
        BigDecimal median = sql.select(percentileCont(0.5).withinGroupOrderBy(days))
                .from(State.TABLE)
                .where(State.PROJECT_ID.eq(projectId)).and(State.RESOLVED_ON.ge(since)).and(State.STARTED_ON.isNotNull())
                .fetchOne(0, BigDecimal.class);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("open", counts.get(0, Integer.class));
        summary.put("inProgress", counts.get(1, Integer.class));
        summary.put("resolved", counts.get(2, Integer.class));
        summary.put("cycleTime", median == null ? null : median.setScale(1, RoundingMode.HALF_UP));
        Optional<Record> sprint = active(projectId);
        summary.put("sprint", sprint.map(r -> r.get(Result.NAME)).orElse(null));
        return summary;
    }

    /** The sprints of the project that have started, the most recent first: for the choice of the burndown. */
    List<Map<String, Object>> sprints(UUID projectId) {
        return sql.select(Result.SPRINT_ID, Result.NAME, Result.STARTS_ON, Result.ENDS_ON, Result.COMPLETED_AT)
                .from(Result.TABLE).where(Result.PROJECT_ID.eq(projectId))
                .orderBy(Result.STARTS_ON.desc(), Result.NAME.desc())
                .fetch(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.get(Result.SPRINT_ID).toString());
                    row.put("name", r.get(Result.NAME));
                    row.put("startsOn", r.get(Result.STARTS_ON));
                    row.put("endsOn", r.get(Result.ENDS_ON));
                    row.put("state", r.get(Result.COMPLETED_AT) == null ? "active" : "completed");
                    return row;
                });
    }

    /**
     * The remaining points of the sprint day by day, up to today or to its completion, and the ideal line from the
     * points committed at the start to zero on its last day. The active sprint when none is given, else the last one.
     */
    List<Map<String, Object>> burndown(UUID projectId, UUID sprintId) {
        Optional<Record> sprint = sprintId == null
                ? active(projectId).or(() -> sql.select(Result.ALL).from(Result.TABLE)
                        .where(Result.PROJECT_ID.eq(projectId)).orderBy(Result.COMPLETED_AT.desc().nullsLast())
                        .limit(1).fetchOptional().map(r -> (Record) r))
                : sql.select(Result.ALL).from(Result.TABLE)
                        .where(Result.PROJECT_ID.eq(projectId)).and(Result.SPRINT_ID.eq(sprintId))
                        .fetchOptional().map(r -> (Record) r);
        if (sprint.isEmpty()) {
            return List.of();
        }
        Record s = sprint.get();
        LocalDate start = s.get(Result.STARTS_ON);
        LocalDate end = s.get(Result.ENDS_ON);
        LocalDate last = s.get(Result.COMPLETED_AT) == null ? today()
                : s.get(Result.COMPLETED_AT).atZoneSameInstant(zone).toLocalDate();
        Map<LocalDate, Integer> remaining = new HashMap<>();
        sql.select(Snapshot.DAY, Snapshot.REMAINING_POINTS).from(Snapshot.TABLE)
                .where(Snapshot.PROJECT_ID.eq(projectId)).and(Snapshot.SPRINT_ID.eq(s.get(Result.SPRINT_ID)))
                .forEach(r -> remaining.put(r.get(Snapshot.DAY), r.get(Snapshot.REMAINING_POINTS)));
        int committed = s.get(Result.COMMITTED_POINTS);
        long span = Math.max(ChronoUnit.DAYS.between(start, end), 1);
        List<Map<String, Object>> days = new ArrayList<>();
        Integer known = committed;
        for (LocalDate day = start; !day.isAfter(end.isAfter(last) ? end : last); day = day.plusDays(1)) {
            if (remaining.containsKey(day)) {
                known = remaining.get(day);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day);
            row.put("remaining", day.isAfter(last) ? null : known);
            long passed = ChronoUnit.DAYS.between(start, day);
            row.put("ideal", day.isAfter(end) ? null
                    : BigDecimal.valueOf(committed * (1.0 - (double) passed / span)).setScale(1, RoundingMode.HALF_UP));
            days.add(row);
        }
        return days;
    }

    /** Committed and done points of the last completed sprints, the oldest first. */
    List<Map<String, Object>> velocity(UUID projectId) {
        List<Map<String, Object>> rows = sql.select(Result.NAME, Result.COMMITTED_POINTS, Result.DONE_POINTS)
                .from(Result.TABLE)
                .where(Result.PROJECT_ID.eq(projectId)).and(Result.COMPLETED_AT.isNotNull())
                .orderBy(Result.COMPLETED_AT.desc()).limit(SPRINTS)
                .fetch(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sprint", r.get(Result.NAME));
                    row.put("committed", r.get(Result.COMMITTED_POINTS));
                    row.put("done", r.get(Result.DONE_POINTS));
                    return row;
                });
        List<Map<String, Object>> oldestFirst = new ArrayList<>(rows);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    /** The issues of the project per status category, day by day, the last 30 days. */
    List<Map<String, Object>> flow(UUID projectId) {
        LocalDate first = today().minusDays(DAYS - 1);
        Map<LocalDate, Record> byDay = new HashMap<>();
        Record before = sql.select(Snapshot.ALL).from(Snapshot.TABLE)
                .where(Snapshot.PROJECT_ID.eq(projectId)).and(Snapshot.SPRINT_ID.isNull()).and(Snapshot.DAY.lt(first))
                .orderBy(Snapshot.DAY.desc()).limit(1).fetchOne();
        sql.select(Snapshot.ALL).from(Snapshot.TABLE)
                .where(Snapshot.PROJECT_ID.eq(projectId)).and(Snapshot.SPRINT_ID.isNull()).and(Snapshot.DAY.ge(first))
                .forEach(r -> byDay.put(r.get(Snapshot.DAY), r));
        List<Map<String, Object>> days = new ArrayList<>();
        Record known = before;
        for (LocalDate day = first; !day.isAfter(today()); day = day.plusDays(1)) {
            if (byDay.containsKey(day)) {
                known = byDay.get(day);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day);
            row.put("todo", known == null ? 0 : known.get(Snapshot.TODO));
            row.put("inProgress", known == null ? 0 : known.get(Snapshot.IN_PROGRESS));
            row.put("done", known == null ? 0 : known.get(Snapshot.DONE));
            days.add(row);
        }
        return days;
    }

    /** Issues created and resolved per day, the last 30 days. */
    List<Map<String, Object>> createdResolved(UUID projectId) {
        LocalDate first = today().minusDays(DAYS - 1);
        Map<LocalDate, Integer> created = new HashMap<>();
        Map<LocalDate, Integer> resolved = new HashMap<>();
        sql.select(State.CREATED_ON, count()).from(State.TABLE)
                .where(State.PROJECT_ID.eq(projectId)).and(State.CREATED_ON.ge(first)).groupBy(State.CREATED_ON)
                .forEach(r -> created.put(r.value1(), r.value2()));
        sql.select(State.RESOLVED_ON, count()).from(State.TABLE)
                .where(State.PROJECT_ID.eq(projectId)).and(State.RESOLVED_ON.ge(first)).groupBy(State.RESOLVED_ON)
                .forEach(r -> resolved.put(r.value1(), r.value2()));
        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(today()); day = day.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day);
            row.put("created", created.getOrDefault(day, 0));
            row.put("resolved", resolved.getOrDefault(day, 0));
            days.add(row);
        }
        return days;
    }

    private Optional<Record> active(UUID projectId) {
        return sql.select(Result.ALL).from(Result.TABLE)
                .where(Result.PROJECT_ID.eq(projectId)).and(Result.COMPLETED_AT.isNull())
                .fetchOptional().map(r -> (Record) r);
    }
}
