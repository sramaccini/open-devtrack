/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.reporting;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.sum;

import com.logicwarez.opendevtrack.agile.PlanEvents.IssuePlanned;
import com.logicwarez.opendevtrack.agile.Planning;
import com.logicwarez.opendevtrack.agile.SprintEvents.SprintCompleted;
import com.logicwarez.opendevtrack.agile.SprintEvents.SprintStarted;
import com.logicwarez.opendevtrack.issue.IssueDocument;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.reporting.Tables.Result;
import com.logicwarez.opendevtrack.reporting.Tables.Snapshot;
import com.logicwarez.opendevtrack.reporting.Tables.State;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code odt_reporting} (ADR-17) from the events of {@code issue} and {@code agile}, after the commit. An issue
 * event rewrites the row of the issue, read again through the public APIs, and the snapshot of the day of its project
 * and of the active sprint; a job writes the snapshot of every project each night, so a day with no event has its row.
 * Writers of one issue, and of the snapshots of one project, take turns on advisory locks.
 */
@Component
class ReportModel {

    private static final Logger log = LoggerFactory.getLogger(ReportModel.class);

    private final DSLContext sql;
    private final Issues issues;
    private final Planning planning;
    private final Workflows workflows;
    private final TransactionTemplate transactions;
    private final ZoneId zone;

    ReportModel(DSLContext sql, Issues issues, Planning planning, Workflows workflows, TransactionTemplate transactions,
                @Value("${odt.reporting.zone:UTC}") String zone) {
        this.sql = sql;
        this.issues = issues;
        this.planning = planning;
        this.workflows = workflows;
        this.transactions = transactions;
        this.zone = ZoneId.of(zone);
    }

    @ApplicationModuleListener
    void on(IssueCreated event) {
        issue(event.issueId(), event.projectId());
    }

    @ApplicationModuleListener
    void on(IssueChanged event) {
        issue(event.issueId(), event.projectId());
    }

    @ApplicationModuleListener
    void on(IssuePlanned event) {
        issue(event.issueId(), event.projectId());
    }

    @ApplicationModuleListener
    void on(IssueDeleted event) {
        lock("issue:" + event.issueId());
        sql.deleteFrom(State.TABLE).where(State.ISSUE_ID.eq(event.issueId())).execute();
        snapshot(event.projectId());
    }

    @ApplicationModuleListener
    void on(SprintStarted event) {
        lock("project:" + event.projectId());
        sql.deleteFrom(Result.TABLE).where(Result.SPRINT_ID.eq(event.sprintId())).execute();
        sql.insertInto(Result.TABLE)
                .set(Result.SPRINT_ID, event.sprintId())
                .set(Result.PROJECT_ID, event.projectId())
                .set(Result.NAME, event.name())
                .set(Result.STARTS_ON, event.startsOn())
                .set(Result.ENDS_ON, event.endsOn())
                .set(Result.COMMITTED_POINTS, event.committedPoints())
                .execute();
        snapshot(event.projectId());
    }

    /**
     * The last day of the sprint is written from the event: its issues done leave the sprint at the completion, so
     * counting the issues still in it would give nothing left. Then the sprint is no longer active.
     */
    @ApplicationModuleListener
    void on(SprintCompleted event) {
        lock("project:" + event.projectId());
        sql.update(Result.TABLE)
                .set(Result.DONE_POINTS, event.donePoints())
                .set(Result.CARRIED_POINTS, event.carriedPoints())
                .set(Result.COMPLETED_AT, event.at().atOffset(ZoneOffset.UTC))
                .where(Result.SPRINT_ID.eq(event.sprintId()))
                .execute();
        write(event.projectId(), event.sprintId(), today(), event.carried(), 0, event.done(), event.carriedPoints());
    }

    /** Each night the snapshot of every project with issues, each in its own transaction. */
    @Scheduled(cron = "${odt.reporting.snapshot-cron:0 5 0 * * *}", zone = "${odt.reporting.zone:UTC}")
    void nightly() {
        List<UUID> projects = sql.selectDistinct(State.PROJECT_ID).from(State.TABLE).fetch(State.PROJECT_ID);
        for (UUID project : projects) {
            try {
                transactions.executeWithoutResult(status -> snapshot(project));
            } catch (RuntimeException e) {
                log.warn("Snapshot of project {} not written: {}", project, e.getMessage());
            }
        }
    }

    /** Writes the row of the issue as it is now and the snapshot of its project, in the transaction of the caller. */
    void issue(UUID issueId, UUID projectId) {
        lock("issue:" + issueId);
        Optional<IssueDocument> found = issues.document(issueId);
        Record2<java.time.LocalDate, java.time.LocalDate> before = sql.select(State.STARTED_ON, State.RESOLVED_ON)
                .from(State.TABLE).where(State.ISSUE_ID.eq(issueId)).fetchOne();
        sql.deleteFrom(State.TABLE).where(State.ISSUE_ID.eq(issueId)).execute();
        if (found.isPresent()) {
            IssueDocument issue = found.get();
            Optional<Planning.IssuePlan> plan = planning.of(issueId);
            String category = workflows.statuses(issue.projectId()).stream()
                    .filter(s -> s.code().equals(issue.status())).map(Workflows.Status::category).findFirst().orElse("todo");
            LocalDate day = issue.updatedAt().atZone(zone).toLocalDate();
            LocalDate started = before == null ? null : before.value1();
            if (started == null && category.equals("in_progress")) {
                started = day;
            }
            LocalDate resolved = before == null ? null : before.value2();
            resolved = category.equals("done") ? (resolved == null ? day : resolved) : null;
            sql.insertInto(State.TABLE)
                    .set(State.ISSUE_ID, issueId)
                    .set(State.TENANT_ID, issue.tenantId())
                    .set(State.PROJECT_ID, issue.projectId())
                    .set(State.TYPE, issue.type())
                    .set(State.PRIORITY, issue.priority())
                    .set(State.STATUS, issue.status())
                    .set(State.CATEGORY, category)
                    .set(State.ASSIGNEE, issue.assignee())
                    .set(State.POINTS, plan.map(Planning.IssuePlan::points).orElse(null))
                    .set(State.SPRINT_ID, plan.map(Planning.IssuePlan::sprintId).orElse(null))
                    .set(State.CREATED_ON, issue.createdAt().atZone(zone).toLocalDate())
                    .set(State.STARTED_ON, started)
                    .set(State.RESOLVED_ON, resolved)
                    .execute();
        }
        snapshot(projectId);
    }

    /** The snapshot of today of the project and of its active sprint, from the rows of the issues. */
    void snapshot(UUID projectId) {
        lock("project:" + projectId);
        LocalDate today = today();
        tally(projectId, null, State.PROJECT_ID.eq(projectId), today);
        sql.select(Result.SPRINT_ID).from(Result.TABLE)
                .where(Result.PROJECT_ID.eq(projectId)).and(Result.COMPLETED_AT.isNull())
                .fetchOptional(Result.SPRINT_ID)
                .ifPresent(sprint -> tally(projectId, sprint, State.SPRINT_ID.eq(sprint), today));
    }

    private void tally(UUID projectId, UUID sprintId, Condition issuesOf, LocalDate day) {
        int todo = 0;
        int inProgress = 0;
        int done = 0;
        int remaining = 0;
        for (Record r : sql.select(State.CATEGORY, count(), sum(State.POINTS)).from(State.TABLE).where(issuesOf)
                .groupBy(State.CATEGORY).fetch()) {
            int n = r.get(1, Integer.class);
            Integer points = r.get(2, Integer.class);
            switch (r.get(State.CATEGORY)) {
                case "done" -> done = n;
                case "in_progress" -> inProgress = n;
                default -> todo = n;
            }
            if (!"done".equals(r.get(State.CATEGORY)) && points != null) {
                remaining += points;
            }
        }
        write(projectId, sprintId, day, todo, inProgress, done, remaining);
    }

    private void write(UUID projectId, UUID sprintId, LocalDate day, int todo, int inProgress, int done, int remaining) {
        sql.deleteFrom(Snapshot.TABLE)
                .where(Snapshot.PROJECT_ID.eq(projectId))
                .and(sprintId == null ? Snapshot.SPRINT_ID.isNull() : Snapshot.SPRINT_ID.eq(sprintId))
                .and(Snapshot.DAY.eq(day))
                .execute();
        sql.insertInto(Snapshot.TABLE)
                .set(Snapshot.PROJECT_ID, projectId)
                .set(Snapshot.SPRINT_ID, sprintId)
                .set(Snapshot.DAY, day)
                .set(Snapshot.TODO, todo)
                .set(Snapshot.IN_PROGRESS, inProgress)
                .set(Snapshot.DONE, done)
                .set(Snapshot.REMAINING_POINTS, remaining)
                .execute();
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Until the commit: listeners run concurrently, and a rebuild must not read before another one commits. */
    private void lock(String key) {
        sql.execute("select pg_advisory_xact_lock(hashtextextended(?, 0))", "reporting:" + key);
    }
}
