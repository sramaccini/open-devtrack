/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRow;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueView;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
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
 * Issues of the projects (E2), by the role of the user in the project (module access):
 *
 * <ul>
 *   <li>{@code odt.issue.browse} (viewer): list and read them;</li>
 *   <li>{@code odt.issue.create}, {@code odt.issue.edit} (developer): create them, change their fields;</li>
 *   <li>{@code odt.issue.delete} (admin): delete them.</li>
 * </ul>
 * The status changes only through the transitions of the workflows (E3). An issue of a project of another tenant
 * answers 404, as one that does not exist (ADR-09).
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class IssueController {

    private static final Pattern ISSUE_KEY = Pattern.compile("^([A-Z][A-Z0-9]{1,9})-([1-9][0-9]{0,17})$");
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,49}$");
    private static final int MAX_LABELS = 20;
    private static final int MAX_DESCRIPTION = 32_000;
    private static final int MAX_LIST = 500;
    private static final String NOT_FOUND = "Issue not found";
    private static final String NOT_A_MEMBER = "Only the members of the project see its issues";

    private final IssueRepository issues;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;

    IssueController(IssueRepository issues, Projects projects, ProjectAccess access, CurrentUser user) {
        this.issues = issues;
        this.projects = projects;
        this.access = access;
        this.user = user;
    }

    /** Issues of the project, newest first; {@code assignee=me} for those of the user. */
    @GetMapping("/api/projects/{key}/issues")
    ResponseEntity<?> list(@PathVariable String key, @RequestParam(required = false) String status,
                           @RequestParam(required = false) String assignee, @RequestParam(required = false) String q) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        if (!permissions(project.get()).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        String who = "me".equals(assignee) ? user.username() : blankToNull(assignee);
        String text = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        List<IssueRow> rows = issues.search(project.get().id(), blankToNull(status), who, text, Limit.of(MAX_LIST)).stream()
                .map(i -> new IssueRow(key(project.get(), i), i.number(), i.type().value(), i.summary(), i.status(),
                        i.priority().value(), i.assignee(), i.updatedAt()))
                .toList();
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/api/projects/{key}/issues")
    @Transactional
    ResponseEntity<?> create(@PathVariable String key, @RequestBody IssueRequest request) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        Set<String> permissions = permissions(project.get());
        if (!permissions.contains("odt.issue.create")) {
            return Problems.forbidden("Only a developer or an admin of the project creates issues");
        }
        Fields fields;
        try {
            fields = fields(project.get(), request);
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        }
        Instant now = Instant.now();
        IssueEntity issue = new IssueEntity(user.tenantId(), project.get().id(), projects.nextIssueNumber(project.get().id()),
                user.username(), now);
        fields.applyTo(issue, now);
        issues.saveAndFlush(issue);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(project.get(), issue, permissions));
    }

    @GetMapping("/api/issues/{issueKey}")
    ResponseEntity<?> get(@PathVariable String issueKey) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get().project());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        return ResponseEntity.ok(view(found.get().project(), found.get().issue(), permissions));
    }

    /** Changes the fields, from the version read with the issue, or 412 if someone saved it in the meantime. */
    @PutMapping("/api/issues/{issueKey}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable String issueKey, @RequestBody IssueRequest request) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectRef project = found.get().project();
        IssueEntity issue = found.get().issue();
        Set<String> permissions = permissions(project);
        if (!permissions.contains("odt.issue.edit")) {
            return Problems.forbidden("Only a developer or an admin of the project edits issues");
        }
        Fields fields;
        try {
            fields = fields(project, request);
        } catch (InvalidField e) {
            return Problems.badRequest(e.getMessage());
        }
        if (request.version() == null || request.version() != issue.version()) {
            return Problems.changedMeanwhile();
        }
        fields.applyTo(issue, Instant.now());
        try {
            issues.saveAndFlush(issue);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        return ResponseEntity.ok(view(project, issue, permissions));
    }

    @DeleteMapping("/api/issues/{issueKey}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable String issueKey) {
        Optional<Found> found = find(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(found.get().project()).contains("odt.issue.delete")) {
            return Problems.forbidden("Only an admin of the project deletes issues");
        }
        issues.delete(found.get().issue());
        return ResponseEntity.noContent().build();
    }

    private record Found(ProjectRef project, IssueEntity issue) {
    }

    private Optional<Found> find(String issueKey) {
        Matcher m = ISSUE_KEY.matcher(issueKey);
        if (!m.matches()) {
            return Optional.empty();
        }
        return projects.find(m.group(1)).flatMap(p -> issues.findByProjectIdAndNumber(p.id(), Long.parseLong(m.group(2)))
                .map(i -> new Found(p, i)));
    }

    private Set<String> permissions(ProjectRef project) {
        return access.permissions(project.id(), user.username());
    }

    /** The fields of a request, checked; type and priority default to task and medium. */
    private record Fields(IssueType type, String summary, String description, Priority priority, String assignee,
                          List<String> labels) {

        void applyTo(IssueEntity issue, Instant now) {
            issue.edit(type, summary, description, priority, assignee, labels, now);
        }
    }

    private static final class InvalidField extends Exception {
        InvalidField(String message) {
            super(message);
        }
    }

    private Fields fields(ProjectRef project, IssueRequest request) throws InvalidField {
        IssueType type = request.type() == null ? IssueType.TASK : IssueType.parse(request.type())
                .orElseThrow(() -> new InvalidField("The type is one of " + String.join(", ", IssueType.names())));
        Priority priority = request.priority() == null ? Priority.MEDIUM : Priority.parse(request.priority())
                .orElseThrow(() -> new InvalidField("The priority is one of " + String.join(", ", Priority.names())));
        if (request.summary() == null || request.summary().isBlank() || request.summary().strip().length() > 255) {
            throw new InvalidField("The summary is required, at most 255 characters");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION) {
            throw new InvalidField("The description is at most " + MAX_DESCRIPTION + " characters");
        }
        String assignee = blankToNull(request.assignee());
        if (assignee != null && access.role(project.id(), assignee).isEmpty()) {
            throw new InvalidField("The assignee must be a member of the project");
        }
        return new Fields(type, request.summary().strip(), blankToNull(request.description()), priority, assignee,
                labels(request.labels()));
    }

    /** Labels in lower case, without repetitions, in the order given. */
    private static List<String> labels(List<String> given) throws InvalidField {
        if (given == null) {
            return List.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (String label : given) {
            String l = label == null ? "" : label.strip().toLowerCase(Locale.ROOT);
            if (!LABEL.matcher(l).matches()) {
                throw new InvalidField("A label is 1 to 50 letters, digits, dots, dashes or underscores, without spaces");
            }
            labels.add(l);
        }
        if (labels.size() > MAX_LABELS) {
            throw new InvalidField("At most " + MAX_LABELS + " labels");
        }
        return new ArrayList<>(labels);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String key(ProjectRef project, IssueEntity issue) {
        return project.key() + "-" + issue.number();
    }

    private IssueView view(ProjectRef project, IssueEntity i, Set<String> permissions) {
        Map<String, Boolean> perm = Map.of(
                "edit", permissions.contains("odt.issue.edit"),
                "delete", permissions.contains("odt.issue.delete"),
                "transition", permissions.contains("odt.issue.transition"),
                "comment", permissions.contains("odt.issue.comment"));
        return new IssueView(key(project, i), project.key(), i.number(), i.type().value(), i.summary(), i.description(),
                i.status(), i.priority().value(), i.assignee(), i.reporter(), i.labels(), i.createdAt(), i.updatedAt(),
                i.version(), perm);
    }
}
