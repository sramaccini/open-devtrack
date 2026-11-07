/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static com.logicwarez.opendevtrack.search.Docs.ASSIGNEE;
import static com.logicwarez.opendevtrack.search.Docs.CATEGORY;
import static com.logicwarez.opendevtrack.search.Docs.CREATED_AT;
import static com.logicwarez.opendevtrack.search.Docs.CUSTOM;
import static com.logicwarez.opendevtrack.search.Docs.DOC;
import static com.logicwarez.opendevtrack.search.Docs.ISSUE_ID;
import static com.logicwarez.opendevtrack.search.Docs.ISSUE_KEY;
import static com.logicwarez.opendevtrack.search.Docs.LABELS;
import static com.logicwarez.opendevtrack.search.Docs.NUMBER;
import static com.logicwarez.opendevtrack.search.Docs.POINTS;
import static com.logicwarez.opendevtrack.search.Docs.PRIORITY;
import static com.logicwarez.opendevtrack.search.Docs.PRIORITY_ORDER;
import static com.logicwarez.opendevtrack.search.Docs.PROJECT_ID;
import static com.logicwarez.opendevtrack.search.Docs.PROJECT_KEY;
import static com.logicwarez.opendevtrack.search.Docs.RANK;
import static com.logicwarez.opendevtrack.search.Docs.REPORTER;
import static com.logicwarez.opendevtrack.search.Docs.RESOLVED_AT;
import static com.logicwarez.opendevtrack.search.Docs.SPRINT_ID;
import static com.logicwarez.opendevtrack.search.Docs.STATUS;
import static com.logicwarez.opendevtrack.search.Docs.SUMMARY;
import static com.logicwarez.opendevtrack.search.Docs.TENANT_ID;
import static com.logicwarez.opendevtrack.search.Docs.TSV;
import static com.logicwarez.opendevtrack.search.Docs.TYPE;
import static com.logicwarez.opendevtrack.search.Docs.UPDATED_AT;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.logicwarez.opendevtrack.agile.PlanEvents.IssuePlanned;
import com.logicwarez.opendevtrack.agile.Planning;
import com.logicwarez.opendevtrack.agile.Planning.IssuePlan;
import com.logicwarez.opendevtrack.issue.IssueDocument;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.SQLDataType;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code odt_search.issue_doc} (ADR-16). Each event only says which issue changed: the index reads the issue, its
 * plan and the category of its status again and writes the whole row, so events handled out of order or twice leave
 * the same row. Listeners run after the commit, each in its own transaction, from the Event Publication Registry; the
 * rebuilds of one issue take turns on an advisory lock.
 */
@Component
class IssueIndex {

    /** The resolved_at of the row already there, in ON CONFLICT DO UPDATE (unqualified it is ambiguous with excluded). */
    private static final Field<OffsetDateTime> STORED_RESOLVED_AT = field(name("odt_search", "issue_doc", "resolved_at"),
            SQLDataType.TIMESTAMPWITHTIMEZONE);

    private final DSLContext sql;
    private final Issues issues;
    private final Planning planning;
    private final Workflows workflows;
    private final JsonMapper json;

    IssueIndex(DSLContext sql, Issues issues, Planning planning, Workflows workflows, JsonMapper json) {
        this.json = json;
        this.sql = sql;
        this.issues = issues;
        this.planning = planning;
        this.workflows = workflows;
    }

    @ApplicationModuleListener
    void on(IssueCreated event) {
        index(event.issueId());
    }

    @ApplicationModuleListener
    void on(IssueChanged event) {
        index(event.issueId());
    }

    @ApplicationModuleListener
    void on(IssuePlanned event) {
        index(event.issueId());
    }

    @ApplicationModuleListener
    void on(IssueDeleted event) {
        sql.deleteFrom(DOC).where(ISSUE_ID.eq(event.issueId())).execute();
    }

    /** Writes the row of the issue as it is now, or removes it when the issue is gone. */
    @Transactional
    void index(UUID issueId) {
        // Listeners run concurrently: without the lock a rebuild that read the issue before a change could commit after
        // the rebuild of that change and leave the older row. The lock is taken before reading, until the commit.
        sql.execute("select pg_advisory_xact_lock(hashtextextended(?, 0))", issueId.toString());
        Optional<IssueDocument> found = issues.document(issueId);
        if (found.isEmpty()) {
            sql.deleteFrom(DOC).where(ISSUE_ID.eq(issueId)).execute();
            return;
        }
        IssueDocument issue = found.get();
        Optional<IssuePlan> plan = planning.of(issueId);
        String category = workflows.statuses(issue.projectId()).stream()
                .filter(s -> s.code().equals(issue.status())).map(Workflows.Status::category).findFirst().orElse("todo");
        OffsetDateTime updated = issue.updatedAt().atOffset(ZoneOffset.UTC);
        boolean done = category.equals("done");
        sql.insertInto(DOC)
                .set(ISSUE_ID, issue.id())
                .set(TENANT_ID, issue.tenantId())
                .set(PROJECT_ID, issue.projectId())
                .set(PROJECT_KEY, issue.projectKey())
                .set(ISSUE_KEY, issue.key())
                .set(NUMBER, issue.number())
                .set(TYPE, issue.type())
                .set(STATUS, issue.status())
                .set(CATEGORY, category)
                .set(PRIORITY, issue.priority())
                .set(PRIORITY_ORDER, (short) issue.priorityOrder())
                .set(ASSIGNEE, issue.assignee())
                .set(REPORTER, issue.reporter())
                .set(LABELS, issue.labels().toArray(String[]::new))
                .set(SUMMARY, issue.summary())
                .set(CUSTOM, JSONB.valueOf(json.writeValueAsString(issue.custom())))
                .set(CREATED_AT, issue.createdAt().atOffset(ZoneOffset.UTC))
                .set(UPDATED_AT, updated)
                .set(RESOLVED_AT, done ? updated : null)
                .set(SPRINT_ID, plan.map(IssuePlan::sprintId).orElse(null))
                .set(POINTS, plan.map(IssuePlan::points).orElse(null))
                .set(RANK, plan.map(IssuePlan::rank).orElse(null))
                .set(TSV, field("odt_search.text_vector({0}, {1}, {2})", SQLDataType.VARCHAR,
                        val(issue.summary()), val(issue.description()),
                        val(issue.labels().toArray(String[]::new), LABELS)))
                .onConflict(ISSUE_ID)
                .doUpdate()
                .set(PROJECT_ID, issue.projectId())
                .set(PROJECT_KEY, issue.projectKey())
                .set(ISSUE_KEY, issue.key())
                .set(TYPE, issue.type())
                .set(STATUS, issue.status())
                .set(CATEGORY, category)
                .set(PRIORITY, issue.priority())
                .set(PRIORITY_ORDER, (short) issue.priorityOrder())
                .set(ASSIGNEE, issue.assignee())
                .set(LABELS, issue.labels().toArray(String[]::new))
                .set(SUMMARY, issue.summary())
                .set(CUSTOM, JSONB.valueOf(json.writeValueAsString(issue.custom())))
                .set(UPDATED_AT, updated)
                // The day it entered a done status: kept while it stays done, cleared when it is reopened.
                .set(RESOLVED_AT, done ? when(STORED_RESOLVED_AT.isNull(), updated).otherwise(STORED_RESOLVED_AT)
                        : castNull(RESOLVED_AT))
                .set(SPRINT_ID, plan.map(IssuePlan::sprintId).orElse(null))
                .set(POINTS, plan.map(IssuePlan::points).orElse(null))
                .set(RANK, plan.map(IssuePlan::rank).orElse(null))
                .set(TSV, field("odt_search.text_vector({0}, {1}, {2})", SQLDataType.VARCHAR,
                        val(issue.summary()), val(issue.description()),
                        val(issue.labels().toArray(String[]::new), LABELS)))
                .execute();
    }
}
