/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.search.Odql.OdqlException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search with ODQL, its suggestions and the saved filters (ADR-16). Anyone signed in may search: what a query finds is
 * limited to the projects the user may browse.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class SearchController {

    private static final int MAX_NAME = 100;

    private final Searches searches;
    private final Words words;
    private final FilterRepository filters;
    private final IssueIndex index;
    private final Issues issues;
    private final Projects projects;
    private final CurrentUser user;

    SearchController(Searches searches, Words words, FilterRepository filters, IssueIndex index, Issues issues,
                     Projects projects, CurrentUser user) {
        this.searches = searches;
        this.words = words;
        this.filters = filters;
        this.index = index;
        this.issues = issues;
        this.projects = projects;
        this.user = user;
    }

    /** The request of the {@code serverDataTable} with the query; groups, rows or the values of a column. */
    @ApiDoc(value = "Runs an ODQL query: a page of issues, or the groups asked", answer = Map.class)
    @PostMapping("/api/search")
    ResponseEntity<?> search(@RequestBody Searches.SearchRequest request) {
        try {
            return ResponseEntity.ok(searches.run(request));
        } catch (OdqlException e) {
            return wrongQuery(e);
        } catch (Searches.BadRequest e) {
            return Problems.badRequest(e.getMessage());
        }
    }

    /** Whether a query is right, for the field of the screen: the answer of a remote check of Quadrava. */
    @ApiDoc(value = "Whether an ODQL query is right, and where it is wrong", answer = Map.class, internal = true)
    @GetMapping("/api/search/check")
    Map<String, Object> check(@RequestParam(name = "q", defaultValue = "") String query) {
        // The answer of a remote check of Quadrava: the field shows validation.odql with the position and the reason.
        try {
            searches.compile(query, searches.visible());
            return Map.of("valid", true);
        } catch (OdqlException e) {
            return Map.of("valid", false, "code", "odql",
                    "params", Map.of("position", e.position(), "reason", e.getMessage()));
        }
    }

    @ApiDoc(value = "The words that may follow in an ODQL query",
            answer = Words.Word.class, list = true, internal = true)
    @GetMapping("/api/search/words")
    List<Words.Word> words(@RequestParam(name = "q", defaultValue = "") String query,
                           @RequestParam(defaultValue = "") String word) {
        return words.suggest(query, word);
    }

    /** Rebuilds the index of every issue of the tenant: for its admin, after a restore or a fault. */
    @ApiDoc(value = "Rebuilds the search index of the tenant: its admin, after a restore", answer = Map.class)
    @PostMapping("/api/search/reindex")
    @Transactional(readOnly = false)
    ResponseEntity<?> reindex() {
        if (!user.can(ProjectAccess.TENANT_ADMIN)) {
            return Problems.forbidden("Only the admin of the tenant rebuilds the index");
        }
        int count = 0;
        for (ProjectRef project : projects.all()) {
            for (UUID id : issues.idsOf(project.id())) {
                index.index(id);
                count++;
            }
        }
        return ResponseEntity.ok(Map.of("issues", count));
    }

    // The personal dashboard (ADR-17): counts of queries on the index, for the projects the user sees

    static final int MAX_DASHBOARD_FILTERS = 10;

    /**
     * Open issues assigned to and reported by the user, open critical bugs, issues resolved since Monday; the user's
     * open issues past their due date and the issues in progress they review (ADR-19).
     */
    @ApiDoc(value = "The counts of the personal dashboard", answer = Map.class, internal = true)
    @GetMapping("/api/me/summary")
    Map<String, Object> mine() {
        List<ProjectRef> visible = searches.visible();
        LocalDate monday = searches.today().with(DayOfWeek.MONDAY);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("assigned", searches.found("assignee = currentUser() AND category != done", visible));
        summary.put("reported", searches.found("reporter = currentUser() AND category != done", visible));
        summary.put("critical", searches.found("type = bug AND priority = highest AND category != done", visible));
        summary.put("resolved", searches.found("resolved >= " + monday, visible));
        summary.put("overdue", searches.found("assignee = currentUser() AND due < today AND category != done", visible));
        summary.put("reviewing", searches.found("reviewer = currentUser() AND category = in_progress", visible));
        return summary;
    }

    /**
     * The filters of the user marked for the dashboard, with how many issues each finds now (null when its query no
     * longer compiles, for example after a sprint was renamed).
     */
    @ApiDoc(value = "The filters of the personal dashboard, with what each finds",
            answer = Map.class, list = true, internal = true)
    @GetMapping("/api/me/filters")
    List<Map<String, Object>> dashboardFilters() {
        List<ProjectRef> visible = searches.visible();
        return filters.visible(user.tenantId(), user.username()).stream()
                .filter(f -> f.onDashboard() && f.owner().equals(user.username()))
                .limit(MAX_DASHBOARD_FILTERS)
                .map(f -> {
                    Map<String, Object> view = view(f);
                    Integer count;
                    try {
                        count = searches.found(f.query(), visible);
                    } catch (OdqlException e) {
                        count = null;
                    }
                    view.put("count", count);
                    return view;
                })
                .toList();
    }

    // Saved filters

    record FilterRequest(String name, String query, Boolean shared, Boolean onDashboard, Long version) {
    }

    @ApiDoc(value = "The filters of the user and those shared in the tenant", answer = Map.class, list = true)
    @GetMapping("/api/filters")
    List<Map<String, Object>> filters() {
        return filters.visible(user.tenantId(), user.username()).stream().map(this::view).toList();
    }

    @ApiDoc(value = "Saves a filter", answer = Map.class, status = 201)
    @PostMapping("/api/filters")
    @Transactional
    ResponseEntity<?> create(@RequestBody FilterRequest request) {
        Optional<ResponseEntity<?>> wrong = check(request);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        FilterEntity filter = new FilterEntity(user.tenantId(), user.username(), Instant.now());
        filter.edit(request.name().strip(), request.query().strip(), Boolean.TRUE.equals(request.shared()),
                Boolean.TRUE.equals(request.onDashboard()), Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(filters.saveAndFlush(filter)));
    }

    @ApiDoc(value = "Changes a filter: its owner", answer = Map.class)
    @PutMapping("/api/filters/{id}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody FilterRequest request) {
        Optional<FilterEntity> found = own(id);
        if (found.isEmpty()) {
            return Problems.notFound("Filter not found");
        }
        Optional<ResponseEntity<?>> wrong = check(request);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        FilterEntity filter = found.get();
        if (request.version() == null || request.version() != filter.version()) {
            return Problems.changedMeanwhile();
        }
        filter.edit(request.name().strip(), request.query().strip(), Boolean.TRUE.equals(request.shared()),
                Boolean.TRUE.equals(request.onDashboard()), Instant.now());
        try {
            return ResponseEntity.ok(view(filters.saveAndFlush(filter)));
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
    }

    @ApiDoc(value = "Removes a filter: its owner", status = 204)
    @DeleteMapping("/api/filters/{id}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable UUID id) {
        Optional<FilterEntity> found = own(id);
        if (found.isEmpty()) {
            return Problems.notFound("Filter not found");
        }
        filters.delete(found.get());
        return ResponseEntity.noContent().build();
    }

    /** A filter of the user in the tenant of the session: another user's, even shared, is not found here. */
    private Optional<FilterEntity> own(UUID id) {
        return filters.findById(id).filter(f -> f.tenantId().equals(user.tenantId()) && f.owner().equals(user.username()));
    }

    private Optional<ResponseEntity<?>> check(FilterRequest request) {
        if (request.name() == null || request.name().isBlank() || request.name().strip().length() > MAX_NAME) {
            return Optional.of(Problems.badRequest("A filter needs a name of at most " + MAX_NAME + " characters"));
        }
        if (request.query() == null || request.query().isBlank()) {
            return Optional.of(Problems.badRequest("A filter needs a query"));
        }
        try {
            searches.compile(request.query(), searches.visible());
        } catch (OdqlException e) {
            return Optional.of(wrongQuery(e));
        }
        return Optional.empty();
    }

    private Map<String, Object> view(FilterEntity f) {
        boolean mine = f.owner().equals(user.username());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", f.id());
        view.put("name", f.name());
        view.put("query", f.query());
        view.put("owner", f.owner());
        view.put("shared", f.shared());
        view.put("onDashboard", f.onDashboard());
        view.put("updatedAt", f.updatedAt());
        view.put("version", f.version());
        view.put("_perm", Map.of("edit", mine));
        return view;
    }

    /** 400 with the position (from 1) and what was expected there, for the screen to show. */
    private static ResponseEntity<ProblemDetail> wrongQuery(OdqlException e) {
        ResponseEntity<ProblemDetail> response = Problems.badRequest(e.getMessage());
        response.getBody().setProperty("position", e.position());
        if (e.expected() != null) {
            response.getBody().setProperty("expected", e.expected());
        }
        return response;
    }
}
