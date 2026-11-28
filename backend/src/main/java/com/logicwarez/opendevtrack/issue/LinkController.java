/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.issue.LinkTypes.Relation;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Links between issues (E2), of the link types of the tenant (ADR-29): {@code blocks} / {@code blocked_by},
 * {@code relates}, {@code duplicates} / {@code duplicated_by} and those its admin adds. Whoever sees an issue reads its links, without the issues of projects they do not see;
 * {@code odt.issue.edit} on the issue adds and removes them, towards an issue of the tenant they see. Each link added
 * or removed is a change of both issues, in their changelog (field {@code links}).
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class LinkController {

    private static final int MAX_LINKS = 100;
    private static final int MAX_CANDIDATES = 20;
    private static final Pattern NUMBER = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9]{1,9}-)?([1-9][0-9]{0,17})$");
    private static final String NOT_FOUND = "Issue not found";
    private static final String NOT_A_MEMBER = "Only the members of the project see its issues";
    private static final String NOT_AN_EDITOR = "Only a developer or an admin of the project links issues";

    private final IssueRepository issues;
    private final IssueLinkRepository links;
    private final Issues finder;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;
    private final LinkTypes types;

    LinkController(IssueRepository issues, IssueLinkRepository links, Issues finder, Projects projects,
                   ProjectAccess access, CurrentUser user, ApplicationEventPublisher events, LinkTypes types) {
        this.issues = issues;
        this.links = links;
        this.finder = finder;
        this.projects = projects;
        this.access = access;
        this.user = user;
        this.events = events;
        this.types = types;
    }

    /**
     * A link as one of its issues reads it: the relation, its type and end ({@code outward} at the {@code from} end),
     * what it reads ({@code label}), and the issue at the other end.
     */
    record LinkView(UUID id, String relation, String type, boolean outward, String label, String key, String project, String summary, String status,
                    @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    record LinkRequest(String relation, String issue) {
    }

    /** An issue to link to, for the lookup of the screen: {@code ODT-3 Login fails}. */
    record Candidate(String key, String label) {
    }

    @ApiDoc(value = "The links of an issue", answer = LinkView.class, list = true, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/links")
    ResponseEntity<?> list(@PathVariable String issueKey) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get().project());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        UUID id = found.get().issue().id();
        Map<String, LinkTypeEntity> byCode = byCode(found.get().issue().tenantId());
        List<IssueLinkEntity> all = links.findByIssue(id);
        Map<UUID, IssueEntity> others = issues.findByIdIn(all.stream().map(l -> l.other(id)).distinct().toList()).stream()
                .collect(Collectors.toMap(IssueEntity::id, Function.identity()));
        Map<UUID, Optional<ProjectRef>> visible = new HashMap<>();
        boolean edit = permissions.contains("odt.issue.edit");
        return ResponseEntity.ok(all.stream()
                .filter(l -> others.containsKey(l.other(id)) && byCode.containsKey(l.type()))
                .flatMap(l -> {
                    IssueEntity other = others.get(l.other(id));
                    return visible.computeIfAbsent(other.projectId(), this::browsable).stream()
                            .map(p -> view(l, byCode.get(l.type()), id, key(p, other), p.key(), other, edit));
                })
                .toList());
    }

    /** Issues of the same project to link to, by key or number ({@code 3}, {@code ODT-3}) or by text of the summary. */
    @ApiDoc(value = "The issues an issue may be linked to, matching q",
            answer = Candidate.class, list = true, project = "odt.issue.browse", internal = true)
    @GetMapping("/api/issues/{issueKey}/links/candidates")
    ResponseEntity<?> candidates(@PathVariable String issueKey, @RequestParam(required = false) String q) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        ProjectRef project = found.get().project();
        if (!permissions(project).contains("odt.issue.browse")) {
            return Problems.forbidden(NOT_A_MEMBER);
        }
        String search = q == null ? "" : q.strip();
        Matcher number = NUMBER.matcher(search);
        Long n = number.matches() ? Long.valueOf(number.group(1)) : null;
        String text = n != null || search.isEmpty() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
        return ResponseEntity.ok(issues.candidates(project.id(), found.get().issue().id(), n, text, Limit.of(MAX_CANDIDATES))
                .stream().map(i -> new Candidate(key(project, i), key(project, i) + " " + i.summary())).toList());
    }

    @ApiDoc(value = "Links two issues", answer = LinkView.class, status = 201, project = "odt.issue.edit")
    @PostMapping("/api/issues/{issueKey}/links")
    @Transactional
    ResponseEntity<?> add(@PathVariable String issueKey, @RequestBody LinkRequest request) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(found.get().project());
        if (!permissions.contains("odt.issue.edit")) {
            return Problems.forbidden(NOT_AN_EDITOR);
        }
        String tenant = found.get().issue().tenantId();
        Optional<Relation> relation = types.parse(tenant, request.relation());
        if (relation.isEmpty()) {
            return Problems.badRequest("The relation is one of " + String.join(", ",
                    types.choices(tenant).stream().map(LinkTypes.Choice::relation).toList()));
        }
        Optional<Found> target = request.issue() == null ? Optional.empty()
                : finder.findEntity(request.issue().strip().toUpperCase(Locale.ROOT))
                        .filter(t -> permissions(t.project()).contains("odt.issue.browse"));
        if (target.isEmpty()) {
            return Problems.badRequest("The linked issue is the key of an issue you see");
        }
        IssueEntity issue = found.get().issue();
        IssueEntity other = target.get().issue();
        if (issue.id().equals(other.id())) {
            return Problems.badRequest("An issue is not linked to itself");
        }
        LinkTypeEntity type = relation.get().type();
        UUID from = relation.get().outward() ? issue.id() : other.id();
        UUID to = relation.get().outward() ? other.id() : issue.id();
        if (links.existsByFromIdAndToIdAndType(from, to, type.code())
                || type.symmetric() && links.existsByFromIdAndToIdAndType(to, from, type.code())) {
            return Problems.conflict("The issues are already linked this way");
        }
        if (links.findByIssue(issue.id()).size() >= MAX_LINKS) {
            return Problems.badRequest("At most " + MAX_LINKS + " links per issue");
        }
        Instant now = Instant.now();
        IssueLinkEntity link = links.saveAndFlush(new IssueLinkEntity(from, to, type.code(), user.username(), now));
        String key = key(found.get().project(), issue);
        String otherKey = key(target.get().project(), other);
        changed(issue, key, now, null, LinkTypes.relation(type, from.equals(issue.id())) + " " + otherKey);
        changed(other, otherKey, now, null, LinkTypes.relation(type, from.equals(other.id())) + " " + key);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(view(link, type, issue.id(), otherKey, target.get().project().key(), other, true));
    }

    @ApiDoc(value = "Removes a link", status = 204, project = "odt.issue.edit")
    @DeleteMapping("/api/issues/{issueKey}/links/{id}")
    @Transactional
    ResponseEntity<?> remove(@PathVariable String issueKey, @PathVariable UUID id) {
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty() || !permissions(found.get().project()).contains("odt.issue.browse")) {
            return Problems.notFound("Link not found");
        }
        IssueEntity issue = found.get().issue();
        Optional<IssueLinkEntity> link = links.findById(id)
                .filter(l -> l.fromId().equals(issue.id()) || l.toId().equals(issue.id()));
        if (link.isEmpty()) {
            return Problems.notFound("Link not found");
        }
        if (!permissions(found.get().project()).contains("odt.issue.edit")) {
            return Problems.forbidden(NOT_AN_EDITOR);
        }
        Optional<LinkTypeEntity> type = types.find(issue.tenantId(), link.get().type());
        links.delete(link.get());
        Instant now = Instant.now();
        String key = key(found.get().project(), issue);
        issues.findById(link.get().other(issue.id())).ifPresent(other -> projects.get(other.projectId()).ifPresent(p -> {
            String otherKey = key(p, other);
            changed(issue, key, now, relation(type, link.get(), issue.id()) + " " + otherKey, null);
            changed(other, otherKey, now, relation(type, link.get(), other.id()) + " " + key, null);
        }));
        return ResponseEntity.noContent().build();
    }

    private LinkView view(IssueLinkEntity link, LinkTypeEntity type, UUID issueId, String otherKey, String project,
                          IssueEntity other, boolean delete) {
        boolean outward = link.fromId().equals(issueId);
        return new LinkView(link.id(), LinkTypes.relation(type, outward), type.code(), outward, types.label(type, outward),
                otherKey, project, other.summary(), other.status(), Map.of("delete", delete));
    }

    private Map<String, LinkTypeEntity> byCode(String tenantId) {
        return types.of(tenantId).stream().collect(Collectors.toMap(LinkTypeEntity::code, Function.identity()));
    }

    /** The relation of the link read from the issue, or its code if its type is gone. */
    private static String relation(Optional<LinkTypeEntity> type, IssueLinkEntity link, UUID issueId) {
        return type.map(t -> LinkTypes.relation(t, link.fromId().equals(issueId))).orElse(link.type());
    }

    private void changed(IssueEntity issue, String key, Instant now, String from, String to) {
        events.publishEvent(new IssueChanged(issue.id(), issue.projectId(), key, user.username(), now,
                List.of(new FieldChange("links", from, to))));
    }

    /** The project, when the user sees its issues. */
    private Optional<ProjectRef> browsable(UUID projectId) {
        return projects.get(projectId).filter(p -> permissions(p).contains("odt.issue.browse"));
    }

    private Set<String> permissions(ProjectRef project) {
        return access.permissions(project.id(), user.username());
    }

    private static String key(ProjectRef project, IssueEntity issue) {
        return project.key() + "-" + issue.number();
    }
}
