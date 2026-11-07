/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static com.logicwarez.opendevtrack.search.Docs.DOC;
import static com.logicwarez.opendevtrack.search.Docs.LABELS;
import static com.logicwarez.opendevtrack.search.Docs.PROJECT_ID;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import com.logicwarez.opendevtrack.identity.TenantUser;
import com.logicwarez.opendevtrack.identity.TenantUsers;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.search.Odql.Kind;
import com.logicwarez.opendevtrack.search.Odql.Token;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The suggestions of the query field (the {@code suggest} of a Quadrava {@code text} field, catalogue § 2.7): from the
 * text before the word at the cursor it works out whether a field, an operator, a value or a keyword comes next, and
 * proposes those that start with the word.
 */
@Service
@Transactional(readOnly = true)
class Words {

    static final int MAX = 20;

    private static final Pattern GLUED = Pattern.compile("^([A-Za-z_.]+)(!=|<=|>=|!~|=|<|>|~)(.*)$");
    private static final List<String> OPERATORS = List.of("= ", "!= ", "IN (", "NOT IN (", "~ ", "!~ ", "< ", "<= ",
            "> ", ">= ", "IS EMPTY ", "IS NOT EMPTY ");

    private final Searches searches;
    private final Workflows workflows;
    private final TenantUsers users;
    private final DSLContext sql;

    Words(Searches searches, Workflows workflows, TenantUsers users, DSLContext sql) {
        this.searches = searches;
        this.workflows = workflows;
        this.users = users;
        this.sql = sql;
    }

    /** One suggestion: the text inserted in place of the word, the text in the list, an example. */
    record Word(String insert, String label, String example) {
    }

    private enum State { START, FIELD, NOT_IN, IN, VALUE, LIST, LIST_NEXT, IS, AFTER, ORDER, ORDER_FIELD, ORDER_NEXT }

    List<Word> suggest(String query, String word) {
        String text = query == null ? "" : query;
        String current = word == null ? "" : word;
        int at = current.isEmpty() ? text.length() : text.lastIndexOf(current);
        String before = text.substring(0, Math.max(at, 0));
        Matcher glued = GLUED.matcher(current);
        if (glued.matches()) {
            String head = glued.group(1) + glued.group(2);
            return values(glued.group(1), glued.group(3)).stream()
                    .map(w -> new Word(head + w.insert(), w.label(), w.example())).limit(MAX).toList();
        }
        List<Token> tokens;
        try {
            tokens = merged(Odql.tokens(before));
        } catch (Odql.OdqlException e) {
            return List.of();
        }
        State state = State.START;
        String field = null;
        for (Token t : tokens) {
            if (t.kind() == Kind.END) {
                break;
            }
            state = switch (state) {
                case START -> {
                    if (t.is("not") || t.symbol("(")) {
                        yield State.START;
                    }
                    field = t.text();
                    yield State.FIELD;
                }
                case FIELD -> t.is("in") ? State.IN : t.is("not") ? State.NOT_IN : t.is("is") ? State.IS : State.VALUE;
                case NOT_IN -> State.IN;
                case IN -> t.symbol("(") ? State.LIST : State.AFTER;
                case LIST -> State.LIST_NEXT;
                case LIST_NEXT -> t.symbol(",") ? State.LIST : State.AFTER;
                case VALUE -> State.AFTER;
                case IS -> t.is("not") ? State.IS : State.AFTER;
                case AFTER -> t.is("and") || t.is("or") ? State.START : t.is("order") ? State.ORDER : State.AFTER;
                case ORDER -> State.ORDER_FIELD;
                case ORDER_FIELD -> State.ORDER_NEXT;
                case ORDER_NEXT -> t.symbol(",") ? State.ORDER_FIELD : State.ORDER_NEXT;
            };
        }
        final String clause = field;
        final State end = state;
        List<Word> words = switch (end) {
            case START -> {
                List<Word> fields = new ArrayList<>(fields());
                fields.add(new Word("NOT ", "NOT", "NOT labels = backend"));
                fields.add(new Word("(", "(", "(status = todo OR status = in_progress)"));
                yield fields;
            }
            case FIELD -> OPERATORS.stream().map(o -> new Word(o, o.trim(), clause + " " + o.trim())).toList();
            case NOT_IN -> List.of(new Word("IN (", "IN (", clause + " NOT IN (…)"));
            case IN -> {
                List<Word> open = new ArrayList<>(List.of(new Word("(", "(", clause + " IN (…)")));
                if ("sprint".equalsIgnoreCase(clause)) {
                    open.add(new Word("openSprints() ", "openSprints()", "sprint IN openSprints()"));
                }
                yield open;
            }
            case VALUE, LIST -> values(clause, current);
            case LIST_NEXT -> List.of(new Word(", ", ",", null), new Word(") ", ")", null));
            case IS -> List.of(new Word("EMPTY ", "EMPTY", null), new Word("NOT EMPTY ", "NOT EMPTY", null));
            case AFTER -> List.of(new Word("AND ", "AND", null), new Word("OR ", "OR", null),
                    new Word("ORDER BY ", "ORDER BY", "ORDER BY updated DESC"));
            case ORDER -> List.of(new Word("BY ", "BY", null));
            case ORDER_FIELD -> fields().stream().filter(w -> !w.label().equals("text") && !w.label().equals("sprint"))
                    .toList();
            case ORDER_NEXT -> List.of(new Word("ASC ", "ASC", null), new Word("DESC ", "DESC", null),
                    new Word(", ", ",", null));
        };
        String prefix = current.toLowerCase(Locale.ROOT);
        return words.stream().filter(w -> end == State.VALUE || end == State.LIST
                        || w.label().toLowerCase(Locale.ROOT).startsWith(prefix))
                .limit(MAX).toList();
    }

    private static List<Word> fields() {
        return OdqlCompiler.FIELDS.stream().map(f -> new Word(f + " ", f, example(f))).toList();
    }

    private static String example(String field) {
        return switch (field) {
            case "project" -> "project = WEB";
            case "key" -> "key = WEB-12";
            case "type" -> "type = bug";
            case "status" -> "status IN (todo, in_progress)";
            case "category" -> "category != done";
            case "priority" -> "priority >= high";
            case "assignee" -> "assignee = currentUser()";
            case "reporter" -> "reporter = currentUser()";
            case "labels" -> "labels = backend";
            case "summary" -> "summary ~ checkout";
            case "text" -> "text ~ \"coupon code\"";
            case "created" -> "created >= -14d";
            case "updated" -> "updated >= -7d";
            case "resolved" -> "resolved >= 2026-11-01";
            case "sprint" -> "sprint IN openSprints()";
            case "points" -> "points >= 5";
            case "rank" -> "ORDER BY rank";
            default -> null;
        };
    }

    /** The values of a field that start with the word: from the data of the projects the user sees. */
    private List<Word> values(String field, String word) {
        String prefix = word == null ? "" : word.toLowerCase(Locale.ROOT);
        Set<String> values = new LinkedHashSet<>();
        String name = field == null ? "" : field.toLowerCase(Locale.ROOT);
        List<ProjectRef> visible = name.isEmpty() ? List.of() : searches.visible();
        switch (name) {
            case "project" -> visible.forEach(p -> values.add(p.key()));
            case "type" -> values.addAll(OdqlCompiler.TYPES);
            case "priority" -> values.addAll(OdqlCompiler.PRIORITIES);
            case "category" -> values.addAll(OdqlCompiler.CATEGORIES);
            case "status" -> visible.forEach(p -> workflows.statuses(p.id()).forEach(s -> values.add(s.code())));
            case "assignee", "reporter" -> {
                values.add("currentUser()");
                users.search(word == null ? "" : word, 10).stream().map(TenantUser::username).forEach(values::add);
            }
            case "labels" -> {
                Field<String> label = field(name("label"), SQLDataType.VARCHAR);
                sql.selectDistinct(label).from(sql.select(field("unnest({0})", SQLDataType.VARCHAR, LABELS).as("label"))
                                .from(DOC).where(PROJECT_ID.in(visible.stream().map(ProjectRef::id).toList())))
                        .where(label.likeIgnoreCase(prefix.replace("%", "") + "%")).orderBy(label).limit(10)
                        .fetch(label).forEach(values::add);
            }
            case "sprint" -> {
                values.add("openSprints()");
                searches.context(visible).sprints().forEach(s -> values.add(s.name()));
            }
            case "created", "updated", "resolved" -> values.addAll(List.of("today", "-7d", "-14d", "-30d"));
            default -> {
                // key, summary, text, points: free values.
            }
        }
        return values.stream().filter(v -> prefix.isEmpty() || v.toLowerCase(Locale.ROOT).startsWith(prefix)
                        || v.toLowerCase(Locale.ROOT).contains(prefix))
                .map(v -> new Word(quoted(v) + " ", v, null)).limit(MAX).toList();
    }

    /** A value as ODQL needs it: quoted when it is not one word. */
    static String quoted(String value) {
        if (value.endsWith("()") || (!value.isEmpty() && value.chars().allMatch(c -> Odql.wordChar((char) c)))) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A function in the tokens ({@code openSprints ( )}) as one word, as the parser reads it. */
    private static List<Token> merged(List<Token> tokens) {
        List<Token> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() == Kind.WORD && i + 2 < tokens.size() && tokens.get(i + 1).symbol("(")
                    && tokens.get(i + 2).symbol(")")) {
                out.add(new Token(Kind.WORD, t.text() + "()", t.at()));
                i += 2;
            } else {
                out.add(t);
            }
        }
        return out;
    }
}
