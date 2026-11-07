/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import com.logicwarez.opendevtrack.agile.AgileApi.BoardRequest;
import com.logicwarez.opendevtrack.agile.AgileApi.BoardRow;
import com.logicwarez.opendevtrack.agile.AgileApi.BoardView;
import com.logicwarez.opendevtrack.agile.AgileApi.CardRow;
import com.logicwarez.opendevtrack.agile.AgileApi.ColumnRow;
import com.logicwarez.opendevtrack.agile.AgileApi.MoveRequest;
import com.logicwarez.opendevtrack.agile.BoardEntity.BoardColumn;
import com.logicwarez.opendevtrack.agile.Cards.Planned;
import com.logicwarez.opendevtrack.agile.Scopes.Scope;
import com.logicwarez.opendevtrack.issue.IssueRef;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.IssueQueries;
import com.logicwarez.opendevtrack.shared.IssueQueries.Matches;
import com.logicwarez.opendevtrack.shared.IssueQueries.Wrong;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The boards of the projects (ADR-06, ADR-15), for the members of each: the generic screen {@code board} reads the
 * columns and the cards of one. A card moved to another column runs the first transition of the workflow that leads
 * to a status of that column and can run now ({@code odt.issue.transition}, and the guard); moved within the column,
 * or between two cards, it changes place in the one order of the project ({@code odt.issue.edit}). The admins of the
 * project ({@code odt.project.edit}) add, change and remove boards; a project keeps at least one. The ODQL filter of a
 * board (ADR-16) is run by the module {@code search} through {@link IssueQueries}, which {@code search} implements:
 * {@code search} depends on {@code agile}, so {@code agile} never depends on it.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class BoardController {

    private static final String NOT_FOUND = "Board not found";
    private static final String UNKNOWN_COLUMN = "No column with this value on the board";
    /** A kanban board leaves out the issues done before this. */
    private static final Duration DONE_SHOWN = Duration.ofDays(14);
    private static final int MAX_BOARDS = 50;

    private final BoardRepository boards;
    private final Scopes scopes;
    private final Projects projects;
    private final Workflows workflows;
    private final Cards cards;
    private final Plans plans;
    private final Issues issues;
    private final Texts texts;
    private final IssueQueries queries;

    BoardController(BoardRepository boards, Scopes scopes, Projects projects, Workflows workflows, Cards cards, Plans plans,
                    Issues issues, Texts texts, IssueQueries queries) {
        this.boards = boards;
        this.scopes = scopes;
        this.projects = projects;
        this.workflows = workflows;
        this.cards = cards;
        this.plans = plans;
        this.issues = issues;
        this.texts = texts;
        this.queries = queries;
    }

    /** The boards of the projects the user is a member of, by project. */
    @GetMapping("/api/boards")
    List<BoardRow> mine() {
        Map<UUID, Scope> visible = projects.all().stream().map(scopes::scope).filter(s -> s.can(Scopes.BROWSE))
                .collect(Collectors.toMap(Scope::projectId, Function.identity()));
        Map<UUID, Integer> order = new HashMap<>();
        projects.all().forEach(p -> order.putIfAbsent(p.id(), order.size()));
        return boards.findByProjectIdInOrderByCreatedAtAscIdAsc(visible.keySet()).stream()
                .sorted(Comparator.comparing((BoardEntity b) -> order.get(b.projectId())))
                .map(b -> row(visible.get(b.projectId()), b)).toList();
    }

    @GetMapping("/api/projects/{key}/boards")
    ResponseEntity<?> list(@PathVariable String key) {
        Optional<Scope> scope = scopes.of(key);
        if (scope.isEmpty()) {
            return Problems.notFound(Scopes.NOT_FOUND);
        }
        if (!scope.get().can(Scopes.BROWSE)) {
            return Problems.forbidden(Scopes.NOT_A_MEMBER);
        }
        return ResponseEntity.ok(boards.findByProjectIdOrderByCreatedAtAscIdAsc(scope.get().projectId()).stream()
                .map(b -> row(scope.get(), b)).toList());
    }

    @PostMapping("/api/projects/{key}/boards")
    @Transactional
    ResponseEntity<?> create(@PathVariable String key, @RequestBody BoardRequest request) {
        Optional<Scope> scope = scopes.of(key);
        if (scope.isEmpty()) {
            return Problems.notFound(Scopes.NOT_FOUND);
        }
        if (!scope.get().can(Scopes.BOARDS)) {
            return Problems.forbidden("Only an admin of the project manages its boards");
        }
        if (boards.countByProjectId(scope.get().projectId()) >= MAX_BOARDS) {
            return Problems.conflict("A project has at most " + MAX_BOARDS + " boards");
        }
        Checked checked = check(scope.get(), request);
        if (checked.problem() != null) {
            return checked.problem();
        }
        BoardEntity board = boards.saveAndFlush(new BoardEntity(scope.get().projectId(), checked.name(), checked.kind(),
                checked.lane(), checked.columns(), checked.filter(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(row(scope.get(), board));
    }

    @PutMapping("/api/boards/{id}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody BoardRequest request) {
        Optional<Found> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Scope scope = found.get().scope();
        if (!scope.can(Scopes.BOARDS)) {
            return Problems.forbidden("Only an admin of the project manages its boards");
        }
        Checked checked = check(scope, request);
        if (checked.problem() != null) {
            return checked.problem();
        }
        BoardEntity board = found.get().board();
        if (request.version() == null || request.version() != board.version()) {
            return Problems.changedMeanwhile();
        }
        board.edit(checked.name(), checked.kind(), checked.lane(), checked.columns(), checked.filter());
        try {
            boards.saveAndFlush(board);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        return ResponseEntity.ok(row(scope, board));
    }

    @DeleteMapping("/api/boards/{id}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable UUID id) {
        Optional<Found> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!found.get().scope().can(Scopes.BOARDS)) {
            return Problems.forbidden("Only an admin of the project manages its boards");
        }
        if (boards.countByProjectId(found.get().scope().projectId()) == 1) {
            return Problems.conflict("A project keeps at least one board");
        }
        boards.delete(found.get().board());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/boards/{id}")
    ResponseEntity<?> get(@PathVariable UUID id) {
        Optional<Found> found = visible(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Scope scope = found.get().scope();
        BoardEntity b = found.get().board();
        Optional<SprintEntity> sprint = b.kind().equals("scrum") ? plans.active(scope.projectId()) : Optional.empty();
        return ResponseEntity.ok(new BoardView(b.id().toString(), scope.project().key(), scope.project().name(), b.name(),
                b.kind(), b.lane(), sprint.map(SprintEntity::name).orElse(null),
                sprint.map(SprintEntity::endsOn).orElse(null), b.filter(),
                Map.of("edit", scope.can(Scopes.BOARDS), "move", canMove(scope))));
    }

    /** The columns, for {@code columnsFrom}: value (the position), header, limit. */
    @GetMapping("/api/boards/{id}/columns")
    ResponseEntity<?> columns(@PathVariable UUID id) {
        Optional<Found> found = visible(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        List<BoardColumn> columns = columns(found.get());
        List<ColumnRow> rows = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            BoardColumn c = columns.get(i);
            rows.add(new ColumnRow(String.valueOf(i), c.name(), null, c.wipLimit()));
        }
        return ResponseEntity.ok(rows);
    }

    /**
     * The cards, in the one order of the project: a scrum board those of the active sprint (none without one), a
     * kanban board every issue but those done for more than 14 days. With lanes by assignee, the cards come by assignee
     * (the lanes of the kanban follow them), those of nobody last. With a filter, only the issues it finds; a filter
     * that is no longer right (409) says where.
     */
    @GetMapping("/api/boards/{id}/cards")
    ResponseEntity<?> cards(@PathVariable UUID id) {
        Optional<Found> found = visible(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Scope scope = found.get().scope();
        BoardEntity board = found.get().board();
        Map<String, String> columnOf = columnOf(columns(found.get()));
        List<Planned> shown;
        if (board.kind().equals("scrum")) {
            Optional<SprintEntity> sprint = plans.active(scope.projectId());
            if (sprint.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
            shown = cards.ranked(scope.project()).stream().filter(p -> sprint.get().id().equals(p.sprintId())).toList();
        } else {
            Set<String> done = cards.done(scope.projectId());
            Instant since = Instant.now().minus(DONE_SHOWN);
            shown = cards.ranked(scope.project()).stream()
                    .filter(p -> !done.contains(p.issue().status()) || p.issue().updatedAt().isAfter(since)).toList();
        }
        if (board.filter() != null) {
            Matches matches = queries.keys(scope.projectId(), board.filter());
            if (matches.wrong() != null) {
                return at(Problems.conflict("The filter of the board is no longer right at position "
                        + matches.wrong().position() + ": " + matches.wrong().reason()), matches.wrong());
            }
            shown = shown.stream().filter(p -> matches.keys().contains(p.issue().key())).toList();
        }
        Map<String, Boolean> perm = Map.of("move", canMove(scope));
        List<CardRow> rows = shown.stream().filter(p -> columnOf.containsKey(p.issue().status()))
                .map(p -> p.card(columnOf.get(p.issue().status()), perm)).toList();
        if (board.lane().equals("assignee")) {
            rows = rows.stream().sorted(Comparator.comparing(CardRow::assignee, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        }
        return ResponseEntity.ok(rows);
    }

    /**
     * A card dropped on a column, between two cards: to another column it runs a transition (409 when none can run now,
     * and the card goes back), then it takes its place in the order of the project.
     */
    @PostMapping("/api/boards/{id}/cards/{issueKey}/move")
    @Transactional
    ResponseEntity<?> move(@PathVariable UUID id, @PathVariable String issueKey, @RequestBody MoveRequest request) {
        Optional<Found> found = visible(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Scope scope = found.get().scope();
        Optional<IssueRef> issue = issues.find(issueKey).filter(i -> i.projectId().equals(scope.projectId()));
        if (issue.isEmpty()) {
            return Problems.notFound("Issue not found");
        }
        List<BoardColumn> columns = columns(found.get());
        Optional<BoardColumn> target = column(columns, request.column());
        if (target.isEmpty()) {
            return Problems.badRequest(UNKNOWN_COLUMN);
        }
        Issues.Move move = issues.moveTo(issueKey, Set.copyOf(target.get().statuses()));
        switch (move.outcome()) {
            case NOT_FOUND -> {
                return Problems.notFound("Issue not found");
            }
            case NOT_PERMITTED -> {
                return Problems.forbidden("Only a developer or an admin of the project moves issues");
            }
            case NO_TRANSITION -> {
                return Problems.conflict("No transition of the workflow leads to this column now");
            }
            case CHANGED_MEANWHILE -> {
                return Problems.changedMeanwhile();
            }
            case MOVED, ALREADY_THERE -> {
                // Below.
            }
        }
        boolean placed = present(request.beforeId()) || present(request.afterId());
        if (placed && scope.can(Scopes.RANK)) {
            PlanEntity plan = plans.plan(issue.get().id(), scope.projectId());
            plans.place(plan, neighbour(request.beforeId(), scope), neighbour(request.afterId(), scope));
        } else if (placed && move.outcome() == Issues.Move.Outcome.ALREADY_THERE) {
            return Problems.forbidden("Only a developer or an admin of the project changes the order of the issues");
        }
        return ResponseEntity.ok(Map.of("status", move.status()));
    }

    private static boolean present(String issueKey) {
        return issueKey != null && !issueKey.isBlank() && !issueKey.equals("null");
    }

    private Optional<PlanEntity> neighbour(String issueKey, Scope scope) {
        if (!present(issueKey)) {
            return Optional.empty();
        }
        return issues.find(issueKey).filter(i -> i.projectId().equals(scope.projectId()))
                .map(i -> plans.plan(i.id(), scope.projectId()));
    }

    private static boolean canMove(Scope scope) {
        return scope.can(Scopes.TRANSITION) || scope.can(Scopes.RANK);
    }

    private record Found(Scope scope, BoardEntity board) {
    }

    private Optional<Found> find(UUID id) {
        return boards.findById(id).flatMap(b -> scopes.of(b.projectId()).map(s -> new Found(s, b)));
    }

    /** The board, for a member of its project; 404 for the others too, as for a board of another tenant. */
    private Optional<Found> visible(UUID id) {
        return find(id).filter(f -> f.scope().can(Scopes.BROWSE));
    }

    /** The columns of the board; without its own, one per status of the workflow (the first ten). */
    private List<BoardColumn> columns(Found found) {
        List<BoardColumn> own = found.board().columns();
        if (!own.isEmpty()) {
            return own;
        }
        return workflows.statuses(found.scope().projectId()).stream().limit(BoardColumns.MAX)
                .map(s -> new BoardColumn(texts.status(s.code()), List.of(s.code()), null)).toList();
    }

    private static Map<String, String> columnOf(List<BoardColumn> columns) {
        Map<String, String> of = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            for (String status : columns.get(i).statuses()) {
                of.put(status, String.valueOf(i));
            }
        }
        return of;
    }

    private static Optional<BoardColumn> column(List<BoardColumn> columns, String value) {
        if (value == null || !value.matches("^[0-9]{1,2}$")) {
            return Optional.empty();
        }
        int i = Integer.parseInt(value);
        return i < columns.size() ? Optional.of(columns.get(i)) : Optional.empty();
    }

    private BoardRow row(Scope scope, BoardEntity b) {
        return new BoardRow(b.id().toString(), scope.project().key(), scope.project().name(), b.name(), b.kind(), b.lane(),
                BoardColumns.format(b.columns()), b.filter(), b.version(), Map.of("edit", scope.can(Scopes.BOARDS)));
    }

    private record Checked(String name, String kind, String lane, List<BoardColumn> columns, String filter,
                           ResponseEntity<?> problem) {

        static Checked invalid(String title) {
            return invalid(Problems.badRequest(title));
        }

        static Checked invalid(ResponseEntity<?> problem) {
            return new Checked(null, null, null, null, null, problem);
        }
    }

    /** A problem about a wrong filter that says where ({@code position}), as the search does. */
    private static ResponseEntity<ProblemDetail> at(ResponseEntity<ProblemDetail> response, Wrong wrong) {
        response.getBody().setProperty("position", wrong.position());
        return response;
    }

    private Checked check(Scope scope, BoardRequest request) {
        if (request.name() == null || request.name().isBlank() || request.name().strip().length() > 100) {
            return Checked.invalid("The name is required, at most 100 characters");
        }
        String kind = request.kind() == null ? "kanban" : request.kind();
        if (!BoardEntity.KINDS.contains(kind)) {
            return Checked.invalid("The kind is one of " + String.join(", ", BoardEntity.KINDS));
        }
        String lane = request.lane() == null ? "none" : request.lane();
        if (!BoardEntity.LANES.contains(lane)) {
            return Checked.invalid("The lanes are by one of " + String.join(", ", BoardEntity.LANES));
        }
        String filter = request.filter() == null || request.filter().isBlank() ? null : request.filter().strip();
        if (filter != null) {
            Optional<Wrong> wrong = queries.check(filter);
            if (wrong.isPresent()) {
                return Checked.invalid(at(Problems.badRequest("The filter is not right at position "
                        + wrong.get().position() + ": " + wrong.get().reason()), wrong.get()));
            }
        }
        List<String> statuses = workflows.statuses(scope.projectId()).stream().map(Workflows.Status::code).toList();
        try {
            return new Checked(request.name().strip(), kind, lane, BoardColumns.parse(request.columns(), statuses), filter,
                    null);
        } catch (BoardColumns.Invalid e) {
            return Checked.invalid(e.getMessage());
        }
    }
}
