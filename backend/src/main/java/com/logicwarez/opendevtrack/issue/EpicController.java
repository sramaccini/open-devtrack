/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.EpicAnswer;
import com.logicwarez.opendevtrack.issue.IssueApi.EpicRequest;
import com.logicwarez.opendevtrack.issue.IssueApi.EpicRow;
import com.logicwarez.opendevtrack.issue.IssueEditor.InvalidField;
import com.logicwarez.opendevtrack.issue.IssueEditor.Parent;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The epics of a project for the panel of the backlog (ADR-28), as in Jira: the epics not done with how many of their
 * issues are done, and «Set epic» on one issue or on several together. Setting the epic is a change of the field
 * {@code parent} of each issue, with the rules of {@link IssueEditor} and its event; like the flag it carries no version,
 * since it changes that field alone.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class EpicController {

    private static final int MAX_EPICS = 200;
    private static final int MAX_ISSUES = 100;

    private final IssueRepository issues;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Workflows workflows;
    private final IssueEditor editor;
    private final ApplicationEventPublisher events;

    EpicController(IssueRepository issues, Projects projects, ProjectAccess access, CurrentUser user,
                   Workflows workflows, IssueEditor editor, ApplicationEventPublisher events) {
        this.issues = issues;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.workflows = workflows;
        this.editor = editor;
        this.events = events;
    }

    /**
     * The epics of the project not done, in the order of their numbers, whose key or summary has {@code q}; with
     * {@code done} and {@code total} of their issues.
     */
    @ApiDoc(value = "The epics of a project that are not done, with the progress of their issues",
            answer = EpicRow.class, list = true, project = "odt.issue.browse")
    @GetMapping("/api/projects/{key}/epics")
    ResponseEntity<?> epics(@PathVariable String key, @RequestParam(required = false) String q) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        if (!access.permissions(project.get().id(), user.username()).contains("odt.issue.browse")) {
            return Problems.forbidden("Only the members of the project see its issues");
        }
        Map<String, String> categories = new HashMap<>();
        workflows.statuses(project.get().id()).forEach(s -> categories.put(s.code(), s.category()));
        String word = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        List<EpicRow> rows = new ArrayList<>();
        for (IssueEntity epic : issues.findByProjectIdOrderByNumber(project.get().id(), Limit.of(MAX_EPICS * 10))) {
            String epicKey = IssueController.key(project.get(), epic);
            String category = categories.getOrDefault(epic.status(), "todo");
            if (epic.type() != IssueType.EPIC || "done".equals(category)
                    || !(word.isEmpty() || epicKey.toLowerCase(Locale.ROOT).contains(word)
                    || epic.summary().toLowerCase(Locale.ROOT).contains(word))) {
                continue;
            }
            List<IssueEntity> children = issues.findByParentIdOrderByNumber(epic.id());
            int done = (int) children.stream()
                    .filter(c -> "done".equals(categories.getOrDefault(c.status(), "todo"))).count();
            rows.add(new EpicRow(epicKey, epic.summary(), epicKey + " " + epic.summary(), epic.status(), category,
                    done, children.size(), done + "/" + children.size()));
            if (rows.size() == MAX_EPICS) {
                break;
            }
        }
        return ResponseEntity.ok(rows);
    }

    /**
     * Puts the issues (stories, tasks and bugs of the project, at most 100) in the epic, or out of any with no
     * {@code epic}; all or none. Each issue that changes gets its event, as a change of its field.
     */
    @ApiDoc(value = "Puts issues of a project in an epic, or out of their epic", answer = EpicAnswer.class,
            project = "odt.issue.edit")
    @PostMapping("/api/projects/{key}/epic")
    @Transactional
    ResponseEntity<?> setEpic(@PathVariable String key, @RequestBody EpicRequest request) {
        Optional<ProjectRef> found = projects.find(key);
        if (found.isEmpty()) {
            return Problems.notFound("Project not found");
        }
        ProjectRef project = found.get();
        if (!access.permissions(project.id(), user.username()).contains("odt.issue.edit")) {
            return Problems.forbidden("Only a developer or an admin of the project edits issues");
        }
        if (request == null || request.issues() == null || request.issues().isEmpty()) {
            return Problems.badRequest("The issues are required");
        }
        if (request.issues().size() > MAX_ISSUES) {
            return Problems.badRequest("At most " + MAX_ISSUES + " issues at a time");
        }
        String asked = request.epic() == null ? "" : request.epic();
        Instant now = Instant.now();
        List<IssueEntity> changed = new ArrayList<>();
        List<FieldChange> changes = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (String issueKey : new LinkedHashSet<>(request.issues())) {
            Optional<IssueEntity> issue = inProject(project, issueKey);
            if (issue.isEmpty()) {
                return Problems.badRequest("No issue " + issueKey + " in " + project.key());
            }
            IssueEntity i = issue.get();
            if (i.type().level() != 0) {
                return Problems.badRequest(IssueController.key(project, i) + ": only a story, task or bug goes in an epic");
            }
            Parent before = editor.parentOf(project, i);
            Parent after;
            try {
                after = editor.parent(project, i, i.type(), asked, before);
            } catch (InvalidField e) {
                return Problems.badRequest(e.getMessage());
            }
            if (!Objects.equals(before.key(), after.key())) {
                changed.add(i);
                changes.add(new FieldChange("parent", before.key(), after.key()));
                ids.add(after.id());
            }
        }
        // Changed only once every issue is checked: a refusal returns without an exception, and the session would
        // still write an entity changed before it.
        for (int n = 0; n < changed.size(); n++) {
            changed.get(n).parentTo(ids.get(n), now);
        }
        issues.saveAllAndFlush(changed);
        for (int n = 0; n < changed.size(); n++) {
            IssueEntity i = changed.get(n);
            events.publishEvent(new IssueChanged(i.id(), project.id(), IssueController.key(project, i), user.username(),
                    now, List.of(changes.get(n))));
        }
        return ResponseEntity.ok(new EpicAnswer(changed.size()));
    }

    /** The issue with this key in the project (its key now; the backlog shows no other). */
    private Optional<IssueEntity> inProject(ProjectRef project, String issueKey) {
        Matcher m = MovedKeys.ISSUE_KEY.matcher(issueKey == null ? "" : issueKey.strip().toUpperCase(Locale.ROOT));
        if (!m.matches() || !m.group(1).equals(project.key())) {
            return Optional.empty();
        }
        return issues.findByProjectIdAndNumber(project.id(), Long.parseLong(m.group(2)));
    }
}
