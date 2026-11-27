/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The choices of the «Move» dialog of an issue (ADR-27), for whoever may move it: the projects it can move to (those
 * of the tenant where the user creates issues, not its own) and the statuses of the workflow of a target for a type,
 * the one the move would choose by itself first.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class MoveController {

    private static final String NOT_FOUND = "Issue not found";
    private static final String NOT_A_MOVER = "Only an admin of the project moves its issues to another project";

    private final Issues finder;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Workflows workflows;
    private final ResourceBundleMessageSource texts = new ResourceBundleMessageSource();

    MoveController(Issues finder, Projects projects, ProjectAccess access, CurrentUser user, Workflows workflows) {
        this.finder = finder;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.workflows = workflows;
        texts.setBasename("issue/export");
        texts.setDefaultEncoding("UTF-8");
        texts.setFallbackToSystemLocale(false);
    }

    record MoveTarget(String key, String name) {
    }

    /** A status of the target; {@code name} in the language of the user for the built-in ones, else the code. */
    record MoveStatus(String code, String name, String category) {
    }

    @ApiDoc(value = "The projects an issue can move to: those where the user creates issues, not its own",
            answer = MoveTarget.class, list = true, project = "odt.issue.move", internal = true)
    @GetMapping("/api/issues/{issueKey}/move/targets")
    ResponseEntity<?> targets(@PathVariable String issueKey, @RequestParam(required = false) String q) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectRef source = found.get().project();
        if (!access.permissions(source.id(), user.username()).contains(IssueMover.MOVE)) {
            return Problems.forbidden(NOT_A_MOVER);
        }
        String text = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return ResponseEntity.ok(projects.all().stream()
                .filter(p -> !p.id().equals(source.id()))
                .filter(p -> text.isEmpty() || p.key().toLowerCase(Locale.ROOT).contains(text)
                        || p.name().toLowerCase(Locale.ROOT).contains(text))
                .filter(p -> access.permissions(p.id(), user.username()).containsAll(Set.of("odt.issue.browse",
                        "odt.issue.create")))
                .map(p -> new MoveTarget(p.key(), p.name()))
                .toList());
    }

    @ApiDoc(value = "The statuses an issue can take in a target project for a type, the one chosen by default first",
            answer = MoveStatus.class, list = true, project = "odt.issue.move", internal = true)
    @GetMapping("/api/issues/{issueKey}/move/statuses")
    ResponseEntity<?> statuses(@PathVariable String issueKey, @RequestParam(required = false) String project,
                               @RequestParam(required = false) String type) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectRef source = found.get().project();
        if (!access.permissions(source.id(), user.username()).contains(IssueMover.MOVE)) {
            return Problems.forbidden(NOT_A_MOVER);
        }
        Optional<ProjectRef> target = project == null || project.isBlank() ? Optional.empty()
                : projects.find(project.strip().toUpperCase(Locale.ROOT))
                        .filter(p -> access.permissions(p.id(), user.username()).contains("odt.issue.browse"));
        if (target.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        IssueEntity issue = found.get().issue();
        String typeValue = type == null || type.isBlank() ? issue.type().value()
                : IssueType.parse(type).map(IssueType::value).orElse(issue.type().value());
        String chosen = workflows.statusFor(source.id(), issue.status(), target.get().id(), typeValue);
        Locale locale = LocaleContextHolder.getLocale();
        List<MoveStatus> all = new ArrayList<>();
        for (Workflows.Status s : workflows.statuses(target.get().id(), typeValue)) {
            MoveStatus status = new MoveStatus(s.code(), texts.getMessage("status." + s.code(), null, s.code(), locale),
                    s.category());
            if (s.code().equals(chosen)) {
                all.addFirst(status);
            } else {
                all.add(status);
            }
        }
        return ResponseEntity.ok(all);
    }
}
