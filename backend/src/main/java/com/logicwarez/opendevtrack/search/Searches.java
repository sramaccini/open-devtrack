/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static com.logicwarez.opendevtrack.search.Docs.ASSIGNEE;
import static com.logicwarez.opendevtrack.search.Docs.CATEGORY;
import static com.logicwarez.opendevtrack.search.Docs.CREATED_AT;
import static com.logicwarez.opendevtrack.search.Docs.DUE_ON;
import static com.logicwarez.opendevtrack.search.Docs.DOC;
import static com.logicwarez.opendevtrack.search.Docs.ISSUE_ID;
import static com.logicwarez.opendevtrack.search.Docs.ISSUE_KEY;
import static com.logicwarez.opendevtrack.search.Docs.NUMBER;
import static com.logicwarez.opendevtrack.search.Docs.POINTS;
import static com.logicwarez.opendevtrack.search.Docs.PRIORITY;
import static com.logicwarez.opendevtrack.search.Docs.PRIORITY_ORDER;
import static com.logicwarez.opendevtrack.search.Docs.PROJECT_ID;
import static com.logicwarez.opendevtrack.search.Docs.PROJECT_KEY;
import static com.logicwarez.opendevtrack.search.Docs.REPORTER;
import static com.logicwarez.opendevtrack.search.Docs.REVIEWER;
import static com.logicwarez.opendevtrack.search.Docs.STATUS;
import static com.logicwarez.opendevtrack.search.Docs.SUMMARY;
import static com.logicwarez.opendevtrack.search.Docs.TENANT_ID;
import static com.logicwarez.opendevtrack.search.Docs.TYPE;
import static com.logicwarez.opendevtrack.search.Docs.UPDATED_AT;
import static com.logicwarez.opendevtrack.search.Docs.fold;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.val;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.agile.Planning;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.search.OdqlCompiler.Compiled;
import com.logicwarez.opendevtrack.search.OdqlCompiler.Context;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs ODQL for the user of the session (ADR-16): over the projects of the tenant that the user may browse only, with
 * the header filters, the sorting and the groups of a {@code serverDataTable} on top of the query.
 */
@Service
// A wrong query is an answer, not a failure: it must not mark the transaction of the caller for rollback.
@Transactional(readOnly = true, noRollbackFor = {Odql.OdqlException.class, Searches.BadRequest.class})
class Searches {

    static final int MAX_SIZE = 100;
    static final int MAX_DISTINCT = 200;

    /** The columns of the results: what the table shows, filters, sorts and groups by. */
    static final Map<String, Field<?>> COLUMNS = Map.ofEntries(
            Map.entry("key", ISSUE_KEY), Map.entry("project", PROJECT_KEY), Map.entry("type", TYPE),
            Map.entry("status", STATUS), Map.entry("category", CATEGORY), Map.entry("priority", PRIORITY),
            Map.entry("assignee", ASSIGNEE), Map.entry("reporter", REPORTER), Map.entry("summary", SUMMARY),
            Map.entry("reviewer", REVIEWER), Map.entry("due", DUE_ON), Map.entry("updated", UPDATED_AT),
            Map.entry("created", CREATED_AT), Map.entry("points", POINTS));

    static final Set<String> GROUPS = Set.of("project", "type", "status", "category", "priority", "assignee");

    private final DSLContext sql;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Planning planning;
    private final ZoneId zone;
    private final Map<String, String> statusNames;

    Searches(DSLContext sql, Projects projects, ProjectAccess access, CurrentUser user, Planning planning,
             @Value("${odt.reporting.zone:UTC}") String zone) {
        this.sql = sql;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.planning = planning;
        this.zone = ZoneId.of(zone);
        this.statusNames = statusNames();
    }

    /** The request of a {@code serverDataTable} (catalogue § 2.19 of Quadrava) with the query. */
    record SearchRequest(String query, Integer page, Integer size, Sort sort, List<Filter> filters, List<String> groupBy,
                   List<GroupStep> groupPath, String distinct) {
    }

    record Sort(String field, String direction) {
    }

    record Filter(String field, String kind, String text, List<String> values, String from, String to) {
    }

    record GroupStep(String field, String value) {
    }

    /** A wrong request of the table (not the query): an unknown column, a group that cannot be. */
    static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    /** The projects of the tenant the user may browse: all for the admin of the tenant, else those they are members of. */
    List<ProjectRef> visible() {
        List<ProjectRef> all = projects.all();
        if (user.can(ProjectAccess.TENANT_ADMIN)) {
            return all;
        }
        Set<UUID> member = access.roles(all.stream().map(ProjectRef::id).toList(), user.username()).keySet();
        return all.stream().filter(p -> member.contains(p.id())).toList();
    }

    Context context(List<ProjectRef> visible) {
        return new Context(user.username(), LocalDate.now(zone), zone,
                planning.sprints(visible.stream().map(ProjectRef::id).toList()), statusNames);
    }

    /** The query compiled for the user, with the projects they see; throws {@link Odql.OdqlException}. */
    Compiled compile(String query, List<ProjectRef> visible) {
        return OdqlCompiler.compile(query, context(visible));
    }

    /** How many issues the query finds in the projects the user sees; throws {@link Odql.OdqlException}. */
    int found(String query, List<ProjectRef> visible) {
        Compiled compiled = compile(query, visible);
        return sql.fetchCount(DOC, TENANT_ID.eq(user.tenantId())
                .and(PROJECT_ID.in(visible.stream().map(ProjectRef::id).toList())).and(compiled.where()));
    }

    /** Today in the zone of the instance. */
    LocalDate today() {
        return LocalDate.now(zone);
    }

    /** What the table asked for: groups of the next level, rows of a page, or the values of a column. */
    Map<String, Object> run(SearchRequest request) {
        List<ProjectRef> visible = visible();
        Compiled compiled = compile(request.query(), visible);
        Condition where = TENANT_ID.eq(user.tenantId()).and(PROJECT_ID.in(visible.stream().map(ProjectRef::id).toList()))
                .and(compiled.where()).and(filters(request.filters()));
        List<String> groupBy = request.groupBy() == null ? List.of() : request.groupBy();
        List<GroupStep> path = request.groupPath() == null ? List.of() : request.groupPath();
        for (String g : groupBy) {
            if (!GROUPS.contains(g)) {
                throw new BadRequest("Cannot group by " + g);
            }
        }
        for (GroupStep step : path) {
            where = where.and(step.value() == null ? column(step.field()).isNull()
                    : column(step.field()).cast(String.class).eq(step.value()));
        }
        if (request.distinct() != null) {
            Field<?> column = column(request.distinct());
            return Map.of("values", sql.selectDistinct(column).from(DOC).where(where).orderBy(column)
                    .limit(MAX_DISTINCT).fetch(0).stream().filter(v -> v != null).map(String::valueOf).toList());
        }
        int size = Math.clamp(request.size() == null ? 25 : request.size(), 1, MAX_SIZE);
        int page = Math.max(request.page() == null ? 0 : request.page(), 0);
        if (path.size() < groupBy.size()) {
            Field<?> group = column(groupBy.get(path.size()));
            int total = sql.fetchCount(sql.selectDistinct(group).from(DOC).where(where));
            List<Map<String, Object>> groups = new ArrayList<>();
            for (Record r : sql.select(group, count()).from(DOC).where(where).groupBy(group)
                    .orderBy(group.asc().nullsLast()).limit(size).offset(page * size).fetch()) {
                Object value = r.get(0);
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("value", value == null ? null : String.valueOf(value));
                g.put("count", r.get(1, Integer.class));
                groups.add(g);
            }
            return Map.of("groups", groups, "total", total);
        }
        int total = sql.fetchCount(DOC, where);
        List<SortField<?>> order = new ArrayList<>(sort(request.sort(), compiled.order()));
        order.add(ISSUE_ID.asc());
        LocalDate today = today();
        List<Map<String, Object>> items = sql.select(ISSUE_KEY, PROJECT_KEY, TYPE, STATUS, CATEGORY, PRIORITY, ASSIGNEE,
                        REVIEWER, REPORTER, SUMMARY, DUE_ON, UPDATED_AT, CREATED_AT, POINTS)
                .from(DOC).where(where).orderBy(order).limit(size).offset(page * size).fetch()
                .map(r -> row(r, today));
        return Map.of("items", items, "total", total);
    }

    /** The table's sort when it has one, otherwise the ORDER BY of the query, otherwise the most recently updated. */
    private static List<SortField<?>> sort(Sort sort, List<SortField<?>> fromQuery) {
        if (sort != null && sort.field() != null) {
            Field<?> column = column(sort.field());
            if (column == PRIORITY) {
                column = PRIORITY_ORDER;
            }
            if (column == ISSUE_KEY) {
                return "desc".equalsIgnoreCase(sort.direction()) ? List.of(PROJECT_KEY.desc(), NUMBER.desc())
                        : List.of(PROJECT_KEY.asc(), NUMBER.asc());
            }
            return List.of("desc".equalsIgnoreCase(sort.direction()) ? column.desc().nullsLast() : column.asc().nullsLast());
        }
        return fromQuery.isEmpty() ? List.of(UPDATED_AT.desc()) : fromQuery;
    }

    private Condition filters(List<Filter> filters) {
        Condition all = org.jooq.impl.DSL.trueCondition();
        if (filters == null) {
            return all;
        }
        for (Filter f : filters) {
            Field<?> column = column(f.field());
            all = all.and(switch (f.kind() == null ? "" : f.kind()) {
                case "text" -> f.text() == null || f.text().isBlank() ? org.jooq.impl.DSL.trueCondition()
                        : fold(column.cast(String.class)).like(fold(val("%" + f.text().replace("\\", "\\\\")
                                .replace("%", "\\%").replace("_", "\\_") + "%")), '\\');
                case "select" -> f.values() == null || f.values().isEmpty() ? org.jooq.impl.DSL.trueCondition()
                        : column.cast(String.class).in(f.values());
                case "number" -> range(column, f.from(), f.to(), false);
                case "date" -> range(column, f.from(), f.to(), true);
                default -> throw new BadRequest("Unknown filter kind " + f.kind());
            });
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private Condition range(Field<?> column, String from, String to, boolean date) {
        Condition c = org.jooq.impl.DSL.trueCondition();
        try {
            if (date) {
                Field<OffsetDateTime> d = (Field<OffsetDateTime>) column;
                if (from != null && !from.isBlank()) {
                    c = c.and(d.ge(LocalDate.parse(from).atStartOfDay(zone).toOffsetDateTime()));
                }
                if (to != null && !to.isBlank()) {
                    c = c.and(d.lt(LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
                }
            } else {
                Field<Integer> n = (Field<Integer>) column;
                if (from != null && !from.isBlank()) {
                    c = c.and(n.ge(Integer.valueOf(from)));
                }
                if (to != null && !to.isBlank()) {
                    c = c.and(n.le(Integer.valueOf(to)));
                }
            }
        } catch (RuntimeException e) {
            throw new BadRequest("Wrong range " + from + " – " + to);
        }
        return c;
    }

    private static Field<?> column(String name) {
        Field<?> column = name == null ? null : COLUMNS.get(name);
        if (column == null) {
            throw new BadRequest("Unknown column " + name);
        }
        return column;
    }

    private static Map<String, Object> row(Record r, LocalDate today) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", r.get(ISSUE_KEY));
        row.put("project", r.get(PROJECT_KEY));
        row.put("type", r.get(TYPE));
        row.put("status", r.get(STATUS));
        row.put("priority", r.get(PRIORITY));
        row.put("assignee", r.get(ASSIGNEE));
        row.put("reviewer", r.get(REVIEWER));
        row.put("reporter", r.get(REPORTER));
        row.put("summary", r.get(SUMMARY));
        LocalDate due = r.get(DUE_ON);
        row.put("due", due);
        // Past its due date and not done (ADR-19): a value for the badges of the column, which colour by value.
        row.put("overdue", due != null && due.isBefore(today) && !"done".equals(r.get(CATEGORY)) ? "overdue" : null);
        row.put("updated", r.get(UPDATED_AT));
        row.put("created", r.get(CREATED_AT));
        row.put("points", r.get(POINTS));
        return row;
    }

    /** The names of the built-in statuses in every language, lower case, to their codes. */
    private static Map<String, String> statusNames() {
        ResourceBundleMessageSource texts = new ResourceBundleMessageSource();
        texts.setBasename("search/texts");
        texts.setDefaultEncoding("UTF-8");
        texts.setFallbackToSystemLocale(false);
        Map<String, String> names = new HashMap<>();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.ITALIAN)) {
            for (String code : List.of("todo", "in_progress", "done")) {
                names.put(texts.getMessage("status." + code, null, code, locale).toLowerCase(Locale.ROOT), code);
            }
        }
        return Map.copyOf(names);
    }
}
