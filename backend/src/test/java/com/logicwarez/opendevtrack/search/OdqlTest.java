/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicwarez.opendevtrack.agile.Planning;
import com.logicwarez.opendevtrack.search.Odql.And;
import com.logicwarez.opendevtrack.search.Odql.Clause;
import com.logicwarez.opendevtrack.search.Odql.Not;
import com.logicwarez.opendevtrack.search.Odql.OdqlException;
import com.logicwarez.opendevtrack.search.Odql.Or;
import com.logicwarez.opendevtrack.search.Odql.Query;
import com.logicwarez.opendevtrack.search.OdqlCompiler.Compiled;
import com.logicwarez.opendevtrack.search.OdqlCompiler.Context;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/** The ODQL parser and compiler (ADR-16), without a database: the SQL they make, with the values inlined to read it. */
class OdqlTest {

    private static final UUID ACTIVE = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID PLANNED = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID DONE = UUID.fromString("00000000-0000-7000-8000-000000000003");

    private static final Context CONTEXT = new Context("mario", LocalDate.of(2026, 11, 7), ZoneId.of("UTC"),
            List.of(new Planning.Sprint(ACTIVE, UUID.randomUUID(), "Sprint 2", "active"),
                    new Planning.Sprint(PLANNED, UUID.randomUUID(), "Sprint 3", "planned"),
                    new Planning.Sprint(DONE, UUID.randomUUID(), "Sprint 1", "completed")),
            Map.of("to do", "todo", "in progress", "in_progress", "in corso", "in_progress"));

    private static String sql(String query) {
        Compiled compiled = OdqlCompiler.compile(query, CONTEXT);
        return DSL.using(SQLDialect.POSTGRES).renderInlined(compiled.where());
    }

    private static String order(String query) {
        return DSL.using(SQLDialect.POSTGRES).renderInlined(DSL.select().from(Docs.DOC)
                .orderBy(OdqlCompiler.compile(query, CONTEXT).order()));
    }

    private static OdqlException error(String query) {
        try {
            OdqlCompiler.compile(query, CONTEXT);
        } catch (OdqlException e) {
            return e;
        }
        throw new AssertionError("No error for " + query);
    }

    @Test
    void parsesPrecedenceParenthesesAndOrder() {
        Query query = Odql.parse("type = bug OR NOT (status = todo AND labels IN (a, \"b c\")) ORDER BY priority DESC, key");
        assertThat(query.where()).isInstanceOf(Or.class);
        Or or = (Or) query.where();
        assertThat(((Clause) or.left()).field()).isEqualTo("type");
        And and = (And) ((Not) or.right()).expr();
        assertThat(((Clause) and.right()).values()).extracting(Odql.Value::text).containsExactly("a", "b c");
        assertThat(query.order()).extracting(Odql.Order::field).containsExactly("priority", "key");
        assertThat(query.order().getFirst().descending()).isTrue();
        assertThat(Odql.parse("").where()).isNull();
        assertThat(Odql.parse("order by updated").where()).isNull();
        // Keywords in any case; AND binds tighter than OR.
        Query mixed = Odql.parse("a = 1 or b = 2 And c = 3");
        assertThat(((Or) mixed.where()).right()).isInstanceOf(And.class);
    }

    @Test
    void syntaxErrorsSayWhereAndWhat() {
        assertThat(error("status =").position()).isEqualTo(9);
        assertThat(error("status =").getMessage()).isEqualTo("Expected a value at the end");
        assertThat(error("status todo").getMessage()).isEqualTo("Expected an operator, not 'todo'");
        assertThat(error("status IN todo").getMessage()).isEqualTo("Expected ( and a list of values after IN");
        assertThat(error("(type = bug").getMessage()).isEqualTo("Expected )");
        assertThat(error("summary ~ \"open").position()).isEqualTo(11);
        assertThat(error("type = bug status = todo").getMessage()).isEqualTo("Unexpected 'status'");
        assertThat(error("type = bug ORDER updated").getMessage()).isEqualTo("Expected BY");
        assertThat(error("type ! bug").getMessage()).isEqualTo("Expected != or !~");
        assertThat(error("type = bug; drop").getMessage()).isEqualTo("Unexpected character ';'");
        assertThatThrownBy(() -> Odql.parse("a".repeat(Odql.MAX_LENGTH + 1))).isInstanceOf(OdqlException.class);
    }

    @Test
    void fieldsOperatorsAndValuesAreChecked() {
        assertThat(error("colour = red").getMessage()).isEqualTo("Unknown field 'colour'");
        assertThat(error("priority = urgent").getMessage()).isEqualTo("Unknown priority 'urgent'");
        assertThat(error("type ~ bug").getMessage()).isEqualTo("The operator ~ cannot be used with type");
        assertThat(error("text = word").getMessage()).isEqualTo("The operator = cannot be used with text");
        assertThat(error("points > many").getMessage()).isEqualTo("Expected a whole number, not 'many'");
        assertThat(error("created > yesterday").getMessage()).startsWith("Expected a date");
        assertThat(error("sprint = \"Sprint 9\"").getMessage()).isEqualTo("No sprint is called 'Sprint 9'");
        assertThat(error("assignee = me()").getMessage()).isEqualTo("Unknown function me() for assignee");
        assertThat(error("rank = 1").getMessage()).isEqualTo("rank can only be used in ORDER BY");
        assertThat(error("type = bug ORDER BY text").getMessage()).isEqualTo("Cannot order by 'text'");
    }

    @Test
    void valuesAreNormalisedAndBound() {
        assertThat(sql("project = web AND key = web-12")).contains("\"project_key\" = 'WEB'", "\"issue_key\" = 'WEB-12'");
        assertThat(sql("type IN (Bug, story)")).contains("\"type\" in ('bug', 'story')");
        assertThat(sql("status = \"In progress\" OR status = \"in corso\" OR status = todo"))
                .contains("\"status\" = 'in_progress'", "\"status\" = 'todo'");
        assertThat(sql("assignee = currentUser()")).contains("\"assignee\" = 'mario'");
        assertThat(sql("assignee IS EMPTY")).contains("\"assignee\" is null");
        assertThat(sql("priority >= high")).contains("\"priority_order\" >= 4");
        // A value never becomes SQL: quotes stay inside a bound text.
        assertThat(sql("summary = \"x' OR 1=1 --\"")).contains("\"summary\" = 'x'' OR 1=1 --'");
    }

    @Test
    void textLabelsDatesSprintsAndCustomFields() {
        assertThat(sql("text ~ \"caffè, perché!\"")).contains("to_tsquery('simple', odt_search.fold('caffè:* & perché:*'))");
        assertThat(sql("summary ~ \"50%_off\"")).contains("like odt_search.fold('%50\\%\\_off%') escape '\\'");
        assertThat(sql("labels = backend")).contains("\"labels\" @> cast('{\"backend\"}' as text[])");
        assertThat(sql("labels IS EMPTY")).contains("cardinality(\"labels\") = 0");
        assertThat(sql("created >= -7d")).contains("\"created_at\" >= timestamp with time zone '2026-10-31 00:00:00+00:00'");
        assertThat(sql("updated = 2026-11-01")).contains("'2026-11-01 00:00:00+00:00'", "'2026-11-02 00:00:00+00:00'");
        assertThat(sql("resolved <= today")).contains("\"resolved_at\" < timestamp with time zone '2026-11-08 00:00:00+00:00'");
        assertThat(sql("sprint IN openSprints()")).contains(ACTIVE.toString(), PLANNED.toString()).doesNotContain(DONE.toString());
        assertThat(sql("sprint = \"sprint 1\"")).contains(DONE.toString());
        assertThat(sql("sprint IS EMPTY")).contains("\"sprint_id\" is null");
        assertThat(sql("custom.customer = ACME")).contains("\"custom\" @> jsonb_build_object('customer', 'ACME'::text)");
        assertThat(sql("custom.severity > 2")).contains("jsonb_typeof", "to_jsonb(2::numeric)");
        assertThat(sql("custom.severity = 2")).contains("::numeric");
    }

    @Test
    void orderByFieldsWithTheKeyByNumber() {
        assertThat(order("ORDER BY key DESC")).contains("\"project_key\" desc nulls last", "\"number\" desc");
        assertThat(order("type = bug ORDER BY priority, rank")).contains("\"priority_order\" asc nulls last", "\"rank\" asc");
        assertThat(OdqlCompiler.compile("type = bug", CONTEXT).order()).isEmpty();
    }
}
