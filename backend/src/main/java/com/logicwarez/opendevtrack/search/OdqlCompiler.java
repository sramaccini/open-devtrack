/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static com.logicwarez.opendevtrack.search.Docs.ASSIGNEE;
import static com.logicwarez.opendevtrack.search.Docs.DUE_ON;
import static com.logicwarez.opendevtrack.search.Docs.CATEGORY;
import static com.logicwarez.opendevtrack.search.Docs.CREATED_AT;
import static com.logicwarez.opendevtrack.search.Docs.CUSTOM;
import static com.logicwarez.opendevtrack.search.Docs.ISSUE_KEY;
import static com.logicwarez.opendevtrack.search.Docs.LABELS;
import static com.logicwarez.opendevtrack.search.Docs.NUMBER;
import static com.logicwarez.opendevtrack.search.Docs.POINTS;
import static com.logicwarez.opendevtrack.search.Docs.PRIORITY_ORDER;
import static com.logicwarez.opendevtrack.search.Docs.PROJECT_KEY;
import static com.logicwarez.opendevtrack.search.Docs.RANK;
import static com.logicwarez.opendevtrack.search.Docs.REPORTER;
import static com.logicwarez.opendevtrack.search.Docs.REVIEWER;
import static com.logicwarez.opendevtrack.search.Docs.RESOLVED_AT;
import static com.logicwarez.opendevtrack.search.Docs.SPRINT_ID;
import static com.logicwarez.opendevtrack.search.Docs.STATUS;
import static com.logicwarez.opendevtrack.search.Docs.SUMMARY;
import static com.logicwarez.opendevtrack.search.Docs.TSV;
import static com.logicwarez.opendevtrack.search.Docs.TYPE;
import static com.logicwarez.opendevtrack.search.Docs.UPDATED_AT;
import static com.logicwarez.opendevtrack.search.Docs.fold;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.trueCondition;
import static org.jooq.impl.DSL.val;

import com.logicwarez.opendevtrack.agile.Planning;
import com.logicwarez.opendevtrack.search.Odql.And;
import com.logicwarez.opendevtrack.search.Odql.Clause;
import com.logicwarez.opendevtrack.search.Odql.Expr;
import com.logicwarez.opendevtrack.search.Odql.Not;
import com.logicwarez.opendevtrack.search.Odql.OdqlException;
import com.logicwarez.opendevtrack.search.Odql.Or;
import com.logicwarez.opendevtrack.search.Odql.Order;
import com.logicwarez.opendevtrack.search.Odql.Query;
import com.logicwarez.opendevtrack.search.Odql.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.SQLDataType;

/**
 * Turns an ODQL query into a jOOQ condition and order over {@link Docs} (ADR-16). Every value is bound, never written
 * into the SQL; the fields and the operators are a closed list, so a query cannot reach anything else.
 */
final class OdqlCompiler {

    /** The fields of the language, in the order the suggestions show them. */
    static final List<String> FIELDS = List.of("project", "key", "type", "status", "category", "priority", "assignee",
            "reviewer", "reporter", "labels", "summary", "text", "created", "updated", "resolved", "due", "sprint", "points",
            "rank");

    static final List<String> TYPES = List.of("task", "bug", "story", "epic");
    static final List<String> PRIORITIES = List.of("lowest", "low", "medium", "high", "highest");
    static final List<String> CATEGORIES = List.of("todo", "in_progress", "done");
    static final List<String> FUNCTIONS = List.of("currentUser()", "openSprints()");

    private static final Pattern RELATIVE = Pattern.compile("^([+-]?)(\\d{1,4})([dwmy])$");
    private static final Pattern CUSTOM_KEY = Pattern.compile("^custom\\.([A-Za-z][A-Za-z0-9_]{0,49})$");
    private static final Pattern NUMBER_VALUE = Pattern.compile("^-?\\d{1,15}(\\.\\d{1,6})?$");

    /**
     * What a query needs beyond its text: the user of {@code currentUser()}, the day of relative dates, the sprints of
     * the projects the user sees, and the names of the statuses ({@code "To do"} → {@code todo}).
     */
    record Context(String username, LocalDate today, ZoneId zone, List<Planning.Sprint> sprints,
                   Map<String, String> statusNames) {
    }

    /** The condition (true for a query without one) and the order (empty for the default one). */
    record Compiled(Condition where, List<SortField<?>> order) {
    }

    private final Context context;

    private OdqlCompiler(Context context) {
        this.context = context;
    }

    static Compiled compile(Query query, Context context) {
        OdqlCompiler compiler = new OdqlCompiler(context);
        Condition where = query.where() == null ? trueCondition() : compiler.expr(query.where());
        List<SortField<?>> order = new ArrayList<>();
        for (Order o : query.order()) {
            Field<?> field = compiler.orderField(o);
            order.add(o.descending() ? field.desc().nullsLast() : field.asc().nullsLast());
            if (field == PROJECT_KEY) {
                order.add(o.descending() ? NUMBER.desc() : NUMBER.asc());
            }
        }
        return new Compiled(where, List.copyOf(order));
    }

    static Compiled compile(String text, Context context) {
        return compile(Odql.parse(text), context);
    }

    private Condition expr(Expr expr) {
        return switch (expr) {
            case And a -> expr(a.left()).and(expr(a.right()));
            case Or o -> expr(o.left()).or(expr(o.right()));
            case Not n -> not(expr(n.expr()));
            case Clause c -> clause(c);
        };
    }

    private Condition clause(Clause c) {
        String name = c.field().toLowerCase(Locale.ROOT);
        Matcher custom = CUSTOM_KEY.matcher(c.field());
        if (custom.matches()) {
            return custom(c, custom.group(1));
        }
        return switch (name) {
            case "project" -> text(c, PROJECT_KEY, v -> v.toUpperCase(Locale.ROOT));
            case "key" -> text(c, ISSUE_KEY, v -> v.toUpperCase(Locale.ROOT));
            case "type" -> text(c, TYPE, v -> oneOf(c, v, TYPES, "type"));
            case "category" -> text(c, CATEGORY, v -> oneOf(c, v, CATEGORIES, "category"));
            case "status" -> text(c, STATUS, this::status);
            case "priority" -> priority(c);
            case "assignee" -> text(c, ASSIGNEE, v -> v);
            case "reviewer" -> text(c, REVIEWER, v -> v);
            case "reporter" -> text(c, REPORTER, v -> v);
            case "labels" -> labels(c);
            case "summary" -> summary(c);
            case "text" -> fullText(c);
            case "created" -> date(c, CREATED_AT);
            case "updated" -> date(c, UPDATED_AT);
            case "resolved" -> date(c, RESOLVED_AT);
            case "due" -> day(c, DUE_ON);
            case "sprint" -> sprint(c);
            case "points" -> number(c, POINTS);
            case "rank" -> throw error(c.at(), "rank can only be used in ORDER BY", null);
            default -> throw error(c.at(), "Unknown field '" + c.field() + "'", String.join(" ", FIELDS));
        };
    }

    // Fields

    /** A text field: = != IN NOT IN IS EMPTY, values normalised by {@code value}; functions only currentUser(). */
    private Condition text(Clause c, Field<String> field, Function<String, String> value) {
        return switch (c.op()) {
            case "=" -> field.eq(value.apply(word(c, c.values().getFirst())));
            case "!=" -> field.ne(value.apply(word(c, c.values().getFirst())));
            case "in" -> field.in(words(c).stream().map(value).toList());
            case "notin" -> field.notIn(words(c).stream().map(value).toList());
            case "empty" -> field.isNull();
            case "notempty" -> field.isNotNull();
            default -> throw notAllowed(c, "= != IN NOT IN IS EMPTY");
        };
    }

    private Condition priority(Clause c) {
        Function<Value, Integer> order = v -> PRIORITIES.indexOf(oneOf(c, word(c, v), PRIORITIES, "priority")) + 1;
        return switch (c.op()) {
            case "<" -> PRIORITY_ORDER.lt(order.apply(c.values().getFirst()).shortValue());
            case "<=" -> PRIORITY_ORDER.le(order.apply(c.values().getFirst()).shortValue());
            case ">" -> PRIORITY_ORDER.gt(order.apply(c.values().getFirst()).shortValue());
            case ">=" -> PRIORITY_ORDER.ge(order.apply(c.values().getFirst()).shortValue());
            case "empty", "notempty" -> throw notAllowed(c, "= != < <= > >= IN NOT IN");
            default -> text(c, Docs.PRIORITY, v -> oneOf(c, v, PRIORITIES, "priority"));
        };
    }

    private Condition labels(Clause c) {
        return switch (c.op()) {
            case "=" -> LABELS.contains(new String[] {word(c, c.values().getFirst())});
            case "!=" -> not(LABELS.contains(new String[] {word(c, c.values().getFirst())}));
            case "in" -> condition("{0} && {1}", LABELS, val(words(c).toArray(String[]::new), LABELS));
            case "notin" -> not(condition("{0} && {1}", LABELS, val(words(c).toArray(String[]::new), LABELS)));
            case "empty" -> field("cardinality({0})", Integer.class, LABELS).eq(0);
            case "notempty" -> field("cardinality({0})", Integer.class, LABELS).gt(0);
            default -> throw notAllowed(c, "= != IN NOT IN IS EMPTY");
        };
    }

    private Condition summary(Clause c) {
        return switch (c.op()) {
            case "~" -> contains(SUMMARY, word(c, c.values().getFirst()));
            case "!~" -> not(contains(SUMMARY, word(c, c.values().getFirst())));
            case "=" -> SUMMARY.eq(word(c, c.values().getFirst()));
            case "!=" -> SUMMARY.ne(word(c, c.values().getFirst()));
            default -> throw notAllowed(c, "~ !~ = !=");
        };
    }

    private static Condition contains(Field<String> field, String text) {
        String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return fold(field).like(fold(val(pattern)), '\\');
    }

    /** {@code text ~ "two words"}: every word, as the start of a word of the summary, description or labels. */
    private Condition fullText(Clause c) {
        if (!c.op().equals("~") && !c.op().equals("!~")) {
            throw notAllowed(c, "~ !~");
        }
        List<String> words = new ArrayList<>();
        for (String w : word(c, c.values().getFirst()).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) {
                words.add(w + ":*");
            }
        }
        if (words.isEmpty()) {
            throw error(c.values().getFirst().at(), "Expected some words to search", "a text");
        }
        Condition matches = condition("{0} @@ to_tsquery('simple', odt_search.fold({1}))", TSV,
                val(String.join(" & ", words)));
        return c.op().equals("~") ? matches : not(matches);
    }

    private Condition date(Clause c, Field<OffsetDateTime> field) {
        if (c.op().equals("empty")) {
            return field.isNull();
        }
        if (c.op().equals("notempty")) {
            return field.isNotNull();
        }
        if (c.op().equals("in") || c.op().equals("notin") || c.op().contains("~")) {
            throw notAllowed(c, "= != < <= > >= IS EMPTY");
        }
        LocalDate day = day(c.values().getFirst());
        OffsetDateTime start = day.atStartOfDay(context.zone()).toOffsetDateTime();
        OffsetDateTime end = day.plusDays(1).atStartOfDay(context.zone()).toOffsetDateTime();
        return switch (c.op()) {
            case "=" -> field.ge(start).and(field.lt(end));
            case "!=" -> field.lt(start).or(field.ge(end));
            case "<" -> field.lt(start);
            case "<=" -> field.lt(end);
            case ">" -> field.ge(end);
            case ">=" -> field.ge(start);
            default -> throw notAllowed(c, "= != < <= > >= IS EMPTY");
        };
    }

    /** A day without a time ({@code due}, ADR-19): the same values and operators as the other dates. */
    private Condition day(Clause c, Field<LocalDate> field) {
        if (c.op().equals("empty")) {
            return field.isNull();
        }
        if (c.op().equals("notempty")) {
            return field.isNotNull();
        }
        if (c.op().equals("in") || c.op().equals("notin") || c.op().contains("~")) {
            throw notAllowed(c, "= != < <= > >= IS EMPTY");
        }
        LocalDate day = day(c.values().getFirst());
        return switch (c.op()) {
            case "=" -> field.eq(day);
            case "!=" -> field.ne(day);
            case "<" -> field.lt(day);
            case "<=" -> field.le(day);
            case ">" -> field.gt(day);
            case ">=" -> field.ge(day);
            default -> throw notAllowed(c, "= != < <= > >= IS EMPTY");
        };
    }

    private Condition number(Clause c, Field<Integer> field) {
        return switch (c.op()) {
            case "=" -> field.eq(integer(c.values().getFirst()));
            case "!=" -> field.ne(integer(c.values().getFirst()));
            case "<" -> field.lt(integer(c.values().getFirst()));
            case "<=" -> field.le(integer(c.values().getFirst()));
            case ">" -> field.gt(integer(c.values().getFirst()));
            case ">=" -> field.ge(integer(c.values().getFirst()));
            case "in" -> field.in(c.values().stream().map(this::integer).toList());
            case "notin" -> field.notIn(c.values().stream().map(this::integer).toList());
            case "empty" -> field.isNull();
            case "notempty" -> field.isNotNull();
            default -> throw notAllowed(c, "= != < <= > >= IN NOT IN IS EMPTY");
        };
    }

    /** A sprint by name (or identifier) among those of the projects the user sees; {@code openSprints()}. */
    private Condition sprint(Clause c) {
        return switch (c.op()) {
            case "=" -> SPRINT_ID.in(sprints(c, List.of(c.values().getFirst())));
            case "!=" -> SPRINT_ID.notIn(sprints(c, List.of(c.values().getFirst())));
            case "in" -> SPRINT_ID.in(sprints(c, c.values()));
            case "notin" -> SPRINT_ID.notIn(sprints(c, c.values()));
            case "empty" -> SPRINT_ID.isNull();
            case "notempty" -> SPRINT_ID.isNotNull();
            default -> throw notAllowed(c, "= != IN NOT IN IS EMPTY");
        };
    }

    private Set<UUID> sprints(Clause c, List<Value> values) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Value v : values) {
            if (v.function()) {
                if (!v.text().equalsIgnoreCase("openSprints")) {
                    throw error(v.at(), "Unknown function " + v.text() + "() for sprint", "openSprints()");
                }
                context.sprints().stream().filter(s -> !s.state().equals("completed")).forEach(s -> ids.add(s.id()));
                continue;
            }
            List<UUID> found = context.sprints().stream()
                    .filter(s -> s.name().equalsIgnoreCase(v.text()) || s.id().toString().equals(v.text()))
                    .map(Planning.Sprint::id).toList();
            if (found.isEmpty()) {
                throw error(v.at(), "No sprint is called '" + v.text() + "'", "a sprint");
            }
            ids.addAll(found);
        }
        // An empty IN would be invalid SQL: a list that finds nothing.
        if (ids.isEmpty()) {
            ids.add(new UUID(0, 0));
        }
        return ids;
    }

    /** {@code custom.<key>}: the value of a custom field (ADR-11), as text or as a number. */
    private Condition custom(Clause c, String key) {
        Field<String> text = field("({0} ->> {1})", SQLDataType.VARCHAR, CUSTOM, inline(key));
        return switch (c.op()) {
            case "=" -> customEquals(key, word(c, c.values().getFirst()));
            case "!=" -> not(customEquals(key, word(c, c.values().getFirst())));
            case "in" -> words(c).stream().map(v -> customEquals(key, v)).reduce(falseCondition(), Condition::or);
            case "notin" -> not(words(c).stream().map(v -> customEquals(key, v)).reduce(falseCondition(), Condition::or));
            case "~" -> contains(text, word(c, c.values().getFirst()));
            case "!~" -> not(contains(text, word(c, c.values().getFirst())));
            case "empty" -> field("coalesce({0}, '')", SQLDataType.VARCHAR, text).eq("");
            case "notempty" -> field("coalesce({0}, '')", SQLDataType.VARCHAR, text).ne("");
            default -> customCompare(c, key, text);
        };
    }

    private static Condition customEquals(String key, String value) {
        Condition asText = condition("{0} @> jsonb_build_object({1}, {2}::text)", CUSTOM, val(key), val(value));
        if (NUMBER_VALUE.matcher(value).matches()) {
            return asText.or(condition("{0} @> jsonb_build_object({1}, {2}::numeric)", CUSTOM, val(key),
                    val(new BigDecimal(value))));
        }
        return asText;
    }

    /** {@code < <= > >=}: numbers as numbers, anything else (dates) as text. */
    private Condition customCompare(Clause c, String key, Field<String> text) {
        String value = word(c, c.values().getFirst());
        String op = c.op();
        if (NUMBER_VALUE.matcher(value).matches()) {
            Field<Object> json = field("({0} -> {1})", Object.class, CUSTOM, inline(key));
            return condition("jsonb_typeof({0}) = 'number'", json)
                    .and(condition("{0} " + op + " to_jsonb({1}::numeric)", json, val(new BigDecimal(value))));
        }
        return switch (op) {
            case "<" -> text.lt(value);
            case "<=" -> text.le(value);
            case ">" -> text.gt(value);
            case ">=" -> text.ge(value);
            default -> throw notAllowed(c, "= != < <= > >= ~ !~ IN NOT IN IS EMPTY");
        };
    }

    // Order

    private Field<?> orderField(Order o) {
        Matcher custom = CUSTOM_KEY.matcher(o.field());
        if (custom.matches()) {
            return field("({0} ->> {1})", SQLDataType.VARCHAR, CUSTOM, inline(custom.group(1)));
        }
        return switch (o.field().toLowerCase(Locale.ROOT)) {
            case "project", "key" -> PROJECT_KEY;
            case "type" -> TYPE;
            case "status" -> STATUS;
            case "category" -> CATEGORY;
            case "priority" -> PRIORITY_ORDER;
            case "assignee" -> ASSIGNEE;
            case "reviewer" -> REVIEWER;
            case "reporter" -> REPORTER;
            case "summary" -> SUMMARY;
            case "created" -> CREATED_AT;
            case "updated" -> UPDATED_AT;
            case "resolved" -> RESOLVED_AT;
            case "due" -> DUE_ON;
            case "points" -> POINTS;
            case "rank" -> RANK;
            default -> throw error(o.at(), "Cannot order by '" + o.field() + "'",
                    "project key type status category priority assignee reviewer reporter summary created updated "
                            + "resolved due points rank");
        };
    }

    // Values

    /** The text of a value; {@code currentUser()} is the user of the session, other functions are errors here. */
    private String word(Clause c, Value v) {
        if (v.function()) {
            if (v.text().equalsIgnoreCase("currentUser")) {
                return context.username();
            }
            throw error(v.at(), "Unknown function " + v.text() + "() for " + c.field(), "currentUser()");
        }
        return v.text();
    }

    private List<String> words(Clause c) {
        return c.values().stream().map(v -> word(c, v)).toList();
    }

    private static String oneOf(Clause c, String value, Collection<String> allowed, String what) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!allowed.contains(lower)) {
            throw error(c.values().isEmpty() ? c.at() : c.values().getFirst().at(),
                    "Unknown " + what + " '" + value + "'", String.join(" ", allowed));
        }
        return lower;
    }

    /** A status by its code, or by its name in English or Italian ({@code "In progress"} → {@code in_progress}). */
    private String status(String value) {
        String key = value.trim().toLowerCase(Locale.ROOT);
        return context.statusNames().getOrDefault(key, key.replace(' ', '_'));
    }

    private Integer integer(Value v) {
        try {
            return Integer.valueOf(v.text());
        } catch (NumberFormatException e) {
            throw error(v.at(), "Expected a whole number, not '" + v.text() + "'", "a number");
        }
    }

    /** {@code 2026-11-07}, {@code today}, or relative to today: {@code -14d}, {@code -2w}, {@code -1m}, {@code -1y}. */
    private LocalDate day(Value v) {
        String text = v.text().toLowerCase(Locale.ROOT);
        if (text.equals("today")) {
            return context.today();
        }
        Matcher relative = RELATIVE.matcher(text);
        if (relative.matches()) {
            long amount = Long.parseLong(relative.group(2)) * (relative.group(1).equals("-") ? -1 : 1);
            return switch (relative.group(3)) {
                case "d" -> context.today().plusDays(amount);
                case "w" -> context.today().plusWeeks(amount);
                case "m" -> context.today().plusMonths(amount);
                default -> context.today().plusYears(amount);
            };
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw error(v.at(), "Expected a date (2026-11-07, today, -14d), not '" + v.text() + "'", "a date");
        }
    }

    private static OdqlException notAllowed(Clause c, String allowed) {
        return error(c.at(), "The operator " + switch (c.op()) {
            case "in" -> "IN";
            case "notin" -> "NOT IN";
            case "empty" -> "IS EMPTY";
            case "notempty" -> "IS NOT EMPTY";
            default -> c.op();
        } + " cannot be used with " + c.field(), allowed);
    }

    private static OdqlException error(int at, String message, String expected) {
        return new OdqlException(at, message, expected);
    }
}
