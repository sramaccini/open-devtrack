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
import static com.logicwarez.opendevtrack.search.Docs.SPRINT_ID;
import static com.logicwarez.opendevtrack.search.Docs.STATUS;
import static com.logicwarez.opendevtrack.search.Docs.SUMMARY;
import static com.logicwarez.opendevtrack.search.Docs.TENANT_ID;
import static com.logicwarez.opendevtrack.search.Docs.TYPE;
import static com.logicwarez.opendevtrack.search.Docs.UPDATED_AT;
import static com.logicwarez.opendevtrack.search.Docs.fold;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.min;
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
import java.util.HashSet;
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
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
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

    /**
     * The columns of the results: what the table shows, filters, sorts and groups by. Besides these, {@code sprint}: the
     * name of the sprint of the issue, or «Backlog» (ADR-26), which depends on the sprints the user sees.
     */
    static final Map<String, Field<?>> COLUMNS = Map.ofEntries(
            Map.entry("key", ISSUE_KEY), Map.entry("project", PROJECT_KEY), Map.entry("type", TYPE),
            Map.entry("status", STATUS), Map.entry("category", CATEGORY), Map.entry("priority", PRIORITY),
            Map.entry("assignee", ASSIGNEE), Map.entry("reporter", REPORTER), Map.entry("summary", SUMMARY),
            Map.entry("reviewer", REVIEWER), Map.entry("due", DUE_ON), Map.entry("updated", UPDATED_AT),
            Map.entry("created", CREATED_AT), Map.entry("points", POINTS));

    static final Set<String> GROUPS = Set.of("project", "type", "status", "category", "priority", "assignee", "sprint");

    static final String SPRINT = "sprint";
    static final String PLAN = "odt.sprint.manage";
    static final String RANK = "odt.issue.edit";

    private final DSLContext sql;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Planning planning;
    private final ZoneId zone;
    private final Map<String, String> statusNames;
    private final ResourceBundleMessageSource texts = new ResourceBundleMessageSource();

    Searches(DSLContext sql, Projects projects, ProjectAccess access, CurrentUser user, Planning planning,
             @Value("${odt.reporting.zone:UTC}") String zone) {
        this.sql = sql;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.planning = planning;
        this.zone = ZoneId.of(zone);
        this.statusNames = statusNames();
        texts.setBasename("search/texts");
        texts.setDefaultEncoding("UTF-8");
        texts.setFallbackToSystemLocale(false);
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
        Context context = context(visible);
        Compiled compiled = OdqlCompiler.compile(request.query(), context);
        Columns columns = new Columns(context.sprints(), backlog());
        Condition where = TENANT_ID.eq(user.tenantId()).and(PROJECT_ID.in(visible.stream().map(ProjectRef::id).toList()))
                .and(compiled.where()).and(filters(request.filters(), columns));
        List<String> groupBy = request.groupBy() == null ? List.of() : request.groupBy();
        List<GroupStep> path = request.groupPath() == null ? List.of() : request.groupPath();
        for (String g : groupBy) {
            if (!GROUPS.contains(g)) {
                throw new BadRequest("Cannot group by " + g);
            }
        }
        for (GroupStep step : path) {
            where = where.and(step.value() == null ? columns.get(step.field()).isNull()
                    : columns.get(step.field()).cast(String.class).eq(step.value()));
        }
        if (request.distinct() != null) {
            Field<?> column = columns.get(request.distinct());
            return Map.of("values", sql.selectDistinct(column).from(DOC).where(where).orderBy(column)
                    .limit(MAX_DISTINCT).fetch(0).stream().filter(v -> v != null).map(String::valueOf).toList());
        }
        int size = Math.clamp(request.size() == null ? 25 : request.size(), 1, MAX_SIZE);
        int page = Math.max(request.page() == null ? 0 : request.page(), 0);
        if (path.size() < groupBy.size()) {
            Field<?> group = columns.get(groupBy.get(path.size()));
            // The sprints in their order (active, planned, completed, then the backlog), the other groups by value.
            SortField<?> groupOrder = group == columns.sprint ? min(columns.sprintOrder).asc() : group.asc().nullsLast();
            int total = sql.fetchCount(sql.selectDistinct(group).from(DOC).where(where));
            List<Map<String, Object>> groups = new ArrayList<>();
            for (Record r : sql.select(group, count()).from(DOC).where(where).groupBy(group)
                    .orderBy(groupOrder).limit(size).offset(page * size).fetch()) {
                Object value = r.get(0);
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("value", value == null ? null : String.valueOf(value));
                g.put("count", r.get(1, Integer.class));
                groups.add(g);
            }
            return Map.of("groups", groups, "total", total);
        }
        int total = sql.fetchCount(DOC, where);
        List<SortField<?>> order = new ArrayList<>(sort(request.sort(), compiled.order(), columns));
        order.add(ISSUE_ID.asc());
        LocalDate today = today();
        List<Record> records = new ArrayList<>(sql.select(ISSUE_KEY, PROJECT_KEY, TYPE, STATUS, CATEGORY, PRIORITY,
                        ASSIGNEE, REVIEWER, REPORTER, SUMMARY, DUE_ON, UPDATED_AT, CREATED_AT, POINTS, SPRINT_ID)
                .from(DOC).where(where).orderBy(order).limit(size).offset(page * size).fetch());
        Map<String, Map<String, Boolean>> perm = perm(records, visible);
        List<Map<String, Object>> items = records.stream().map(r -> row(r, today, columns, perm)).toList();
        return Map.of("items", items, "total", total);
    }

    /** The table's sort when it has one, otherwise the ORDER BY of the query, otherwise the most recently updated. */
    private static List<SortField<?>> sort(Sort sort, List<SortField<?>> fromQuery, Columns columns) {
        if (sort != null && sort.field() != null) {
            Field<?> column = columns.get(sort.field());
            if (column == PRIORITY) {
                column = PRIORITY_ORDER;
            } else if (column == columns.sprint) {
                column = columns.sprintOrder;
            }
            if (column == ISSUE_KEY) {
                return "desc".equalsIgnoreCase(sort.direction()) ? List.of(PROJECT_KEY.desc(), NUMBER.desc())
                        : List.of(PROJECT_KEY.asc(), NUMBER.asc());
            }
            return List.of("desc".equalsIgnoreCase(sort.direction()) ? column.desc().nullsLast() : column.asc().nullsLast());
        }
        return fromQuery.isEmpty() ? List.of(UPDATED_AT.desc()) : fromQuery;
    }

    private Condition filters(List<Filter> filters, Columns columns) {
        Condition all = org.jooq.impl.DSL.trueCondition();
        if (filters == null) {
            return all;
        }
        for (Filter f : filters) {
            Field<?> column = columns.get(f.field());
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

    /**
     * The columns of one request: those of {@link #COLUMNS} and {@code sprint}, the name of the sprint of each issue
     * among the sprints the user sees, «Backlog» for none, with {@code sprintOrder} to sort it: the active sprints, the
     * planned ones from the oldest, the completed ones from the most recent, then the backlog. The values are written
     * in the SQL (not bound), so that a group by the sprint is the same expression in SELECT and GROUP BY.
     */
    static final class Columns {

        final Field<String> sprint;
        final Field<Integer> sprintOrder;
        final Map<UUID, Planning.Sprint> sprints = new HashMap<>();
        final String backlog;

        Columns(List<Planning.Sprint> visible, String backlog) {
            this.backlog = backlog;
            List<Planning.Sprint> ordered = new ArrayList<>();
            visible.stream().filter(s -> "active".equals(s.state())).forEach(ordered::add);
            visible.reversed().stream().filter(s -> "planned".equals(s.state())).forEach(ordered::add);
            visible.stream().filter(s -> "completed".equals(s.state())).forEach(ordered::add);
            ordered.forEach(s -> sprints.put(s.id(), s));
            if (ordered.isEmpty()) {
                sprint = inline(backlog);
                sprintOrder = inline(0);
                return;
            }
            var name = DSL.case_(SPRINT_ID).when(inline(ordered.getFirst().id()), inline(ordered.getFirst().name()));
            var position = DSL.case_(SPRINT_ID).when(inline(ordered.getFirst().id()), inline(0));
            for (int i = 1; i < ordered.size(); i++) {
                name = name.when(inline(ordered.get(i).id()), inline(ordered.get(i).name()));
                position = position.when(inline(ordered.get(i).id()), inline(i));
            }
            sprint = name.otherwise(inline(backlog));
            sprintOrder = position.otherwise(inline(ordered.size()));
        }

        Field<?> get(String name) {
            Field<?> column = SPRINT.equals(name) ? sprint : name == null ? null : COLUMNS.get(name);
            if (column == null) {
                throw new BadRequest("Unknown column " + name);
            }
            return column;
        }
    }

    /** «Backlog» in the language of the request. */
    private String backlog() {
        try {
            return texts.getMessage("backlog", null, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return "Backlog";
        }
    }

    /**
     * What the user may do on the rows, by project (ADR-26): {@code plan} moves an issue to a sprint or the backlog,
     * {@code rank} changes its place in the order of the project.
     */
    private Map<String, Map<String, Boolean>> perm(List<Record> records, List<ProjectRef> visible) {
        Set<String> keys = new HashSet<>();
        records.forEach(r -> keys.add(r.get(PROJECT_KEY)));
        Map<String, Map<String, Boolean>> perm = new HashMap<>();
        for (ProjectRef p : visible) {
            if (keys.contains(p.key())) {
                Set<String> given = access.permissions(p.id(), user.username());
                perm.put(p.key(), Map.of("plan", given.contains(PLAN), "rank", given.contains(RANK)));
            }
        }
        return perm;
    }

    private static Map<String, Object> row(Record r, LocalDate today, Columns columns,
                                           Map<String, Map<String, Boolean>> perm) {
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
        Planning.Sprint sprint = columns.sprints.get(r.get(SPRINT_ID));
        row.put("sprint", sprint == null ? columns.backlog : sprint.name());
        row.put("sprintState", sprint == null ? null : sprint.state());
        row.put("_perm", perm.getOrDefault(r.get(PROJECT_KEY), Map.of("plan", false, "rank", false)));
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
