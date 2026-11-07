/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;

/**
 * The table {@code odt_search.issue_doc} for jOOQ, declared by hand (ADR-16: no generated classes). The names are
 * those of the migration V16.
 */
final class Docs {

    static final Table<?> DOC = table(name("odt_search", "issue_doc"));

    static final Field<UUID> ISSUE_ID = field(name("issue_id"), SQLDataType.UUID);
    static final Field<String> TENANT_ID = field(name("tenant_id"), SQLDataType.VARCHAR);
    static final Field<UUID> PROJECT_ID = field(name("project_id"), SQLDataType.UUID);
    static final Field<String> PROJECT_KEY = field(name("project_key"), SQLDataType.VARCHAR);
    static final Field<String> ISSUE_KEY = field(name("issue_key"), SQLDataType.VARCHAR);
    static final Field<Long> NUMBER = field(name("number"), SQLDataType.BIGINT);
    static final Field<String> TYPE = field(name("type"), SQLDataType.VARCHAR);
    static final Field<String> STATUS = field(name("status"), SQLDataType.VARCHAR);
    static final Field<String> CATEGORY = field(name("category"), SQLDataType.VARCHAR);
    static final Field<String> PRIORITY = field(name("priority"), SQLDataType.VARCHAR);
    static final Field<Short> PRIORITY_ORDER = field(name("priority_order"), SQLDataType.SMALLINT);
    static final Field<String> ASSIGNEE = field(name("assignee"), SQLDataType.VARCHAR);
    static final Field<String> REPORTER = field(name("reporter"), SQLDataType.VARCHAR);
    static final Field<String> REVIEWER = field(name("reviewer"), SQLDataType.VARCHAR);
    static final Field<LocalDate> DUE_ON = field(name("due_on"), SQLDataType.LOCALDATE);
    static final Field<String[]> LABELS = field(name("labels"), SQLDataType.CLOB.array());
    static final Field<String> SUMMARY = field(name("summary"), SQLDataType.VARCHAR);
    static final Field<JSONB> CUSTOM = field(name("custom"), SQLDataType.JSONB);
    static final Field<OffsetDateTime> CREATED_AT = field(name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    static final Field<OffsetDateTime> UPDATED_AT = field(name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    static final Field<OffsetDateTime> RESOLVED_AT = field(name("resolved_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    static final Field<UUID> SPRINT_ID = field(name("sprint_id"), SQLDataType.UUID);
    static final Field<Integer> POINTS = field(name("points"), SQLDataType.INTEGER);
    static final Field<String> RANK = field(name("rank"), SQLDataType.VARCHAR);
    /** A tsvector; String only for jOOQ, the value is always written by {@code odt_search.text_vector}. */
    static final Field<String> TSV = field(name("tsv"), SQLDataType.VARCHAR);

    private Docs() {
    }

    /** {@code odt_search.fold(value)}: lower case, without accents, as the index of the summaries. */
    static Field<String> fold(Field<String> value) {
        return field("odt_search.fold({0})", SQLDataType.VARCHAR, value);
    }
}
