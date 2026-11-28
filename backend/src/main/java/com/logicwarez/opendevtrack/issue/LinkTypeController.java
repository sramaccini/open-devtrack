/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.LinkTypes.Choice;
import com.logicwarez.opendevtrack.platform.AdminLog;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The link types of the tenant (ADR-29): every user of the projects reads them (the screens need them to add a link),
 * the admin of the tenant ({@value ProjectAccess#TENANT_ADMIN}) adds them, changes their descriptions and removes
 * them. The code of a type never changes. A type in use is removed by moving its links to another type or by deleting
 * them, each issue with its change of {@code links}; {@code clones}, which «Clone» writes, is never removed.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class LinkTypeController {

    private static final String TYPES = "/api/link-types";
    private static final String NOT_FOUND = "Link type not found";
    private static final String ADMIN = "hasAuthority('" + ProjectAccess.TENANT_ADMIN + "')";
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{1,29}$");
    private static final int MAX_DESCRIPTION = 60;
    private static final int MAX_TYPES = 50;

    private final LinkTypeRepository repository;
    private final LinkTypes types;
    private final IssueLinkRepository links;
    private final IssueRepository issues;
    private final Projects projects;
    private final CurrentUser user;
    private final AdminLog log;
    private final ApplicationEventPublisher events;

    LinkTypeController(LinkTypeRepository repository, LinkTypes types, IssueLinkRepository links, IssueRepository issues,
                       Projects projects, CurrentUser user, AdminLog log, ApplicationEventPublisher events) {
        this.repository = repository;
        this.types = types;
        this.links = links;
        this.issues = issues;
        this.projects = projects;
        this.user = user;
        this.log = log;
        this.events = events;
    }

    /** A type: its code, how each end reads it, how many links it has and what the user may do with it. */
    record LinkTypeView(String code, String outward, String inward, String outwardLabel, String inwardLabel,
                        boolean system, long links, String updatedBy, Instant updatedAt, long version,
                        @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A new type: its code and the description of each end (the same for a type read alike from both). */
    record LinkTypeRequest(String code, String outward, String inward) {
    }

    /** New descriptions of a type, with the version read. */
    record LinkTypeChange(String outward, String inward, Long version) {
    }

    @ApiDoc(value = "The link types of the workspace, the system ones first; except leaves one out, as the choice "
            + "of where to move the links of a type removed", answer = LinkTypeView.class, list = true)
    @GetMapping(TYPES)
    List<LinkTypeView> list(@RequestParam(required = false) String except) {
        return types.of(user.tenantId()).stream()
                .filter(t -> !t.code().equals(except))
                .map(this::view).toList();
    }

    @ApiDoc(value = "The relations an issue may have with another, one per end of each link type, matching q",
            answer = Choice.class, list = true, internal = true)
    @GetMapping(TYPES + "/relations")
    List<Choice> relations(@RequestParam(required = false) String q) {
        String word = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return types.choices(user.tenantId()).stream()
                .filter(c -> word.isEmpty() || c.label().toLowerCase(Locale.ROOT).contains(word)
                        || c.relation().contains(word))
                .toList();
    }

    @ApiDoc(value = "Adds a link type", answer = LinkTypeView.class, status = 201)
    @PostMapping(TYPES)
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> add(@RequestBody LinkTypeRequest request) {
        String code = request.code() == null ? "" : request.code().strip();
        if (!CODE.matcher(code).matches()) {
            return Problems.badRequest("The code is 2 to 30 lowercase letters, digits or _, starting with a letter");
        }
        Optional<ResponseEntity<?>> wrong = wrongText(request.outward(), request.inward());
        if (wrong.isPresent()) {
            return wrong.get();
        }
        List<LinkTypeEntity> all = types.of(user.tenantId());
        if (all.size() >= MAX_TYPES) {
            return Problems.badRequest("At most " + MAX_TYPES + " link types");
        }
        if (types.parse(user.tenantId(), code).isPresent()) {
            return Problems.conflict("A link type or a relation has this code");
        }
        LinkTypeEntity added = repository.saveAndFlush(new LinkTypeEntity(user.tenantId(), code,
                request.outward().strip(), request.inward().strip(), false, user.username(), Instant.now()));
        log.tenant("linktype.created", code, null, logged(added));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(added));
    }

    @ApiDoc(value = "Changes the descriptions of a link type", answer = LinkTypeView.class)
    @PatchMapping(TYPES + "/{code}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> change(@PathVariable String code, @RequestBody LinkTypeChange request) {
        Optional<LinkTypeEntity> found = types.find(user.tenantId(), code);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Optional<ResponseEntity<?>> wrong = wrongText(request.outward(), request.inward());
        if (wrong.isPresent()) {
            return wrong.get();
        }
        if (request.version() == null || request.version() != found.get().version()) {
            return Problems.changedMeanwhile();
        }
        LinkTypeEntity type = found.get();
        boolean wasSymmetric = type.symmetric();
        if (wasSymmetric != request.outward().strip().equals(request.inward().strip())
                && links.countByTenantAndType(user.tenantId(), code) > 0) {
            return Problems.conflict("A link type in use stays read alike from both ends, or not");
        }
        var before = logged(type);
        type.describe(request.outward().strip(), request.inward().strip(), user.username(), Instant.now());
        LinkTypeEntity saved = repository.saveAndFlush(type);
        log.tenant("linktype.changed", code, before, logged(saved));
        return ResponseEntity.ok(view(saved));
    }

    /**
     * Removes a type: one in use needs {@code replaceWith}, the code of the type its links move to, or
     * {@code deleteLinks=true}; without them 409, which names how many links it has.
     */
    @ApiDoc(value = "Removes a link type, moving its links to another type or deleting them", status = 204)
    @DeleteMapping(TYPES + "/{code}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> remove(@PathVariable String code, @RequestParam(required = false) String replaceWith,
                             @RequestParam(defaultValue = "false") boolean deleteLinks) {
        String tenant = user.tenantId();
        Optional<LinkTypeEntity> found = types.find(tenant, code);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        LinkTypeEntity type = found.get();
        if (LinkTypeDefaults.CLONES.equals(code)) {
            return Problems.conflict("The link type clones is written by «Clone» and stays");
        }
        boolean replace = replaceWith != null && !replaceWith.isBlank();
        if (replace && deleteLinks) {
            return Problems.badRequest("Either replaceWith or deleteLinks");
        }
        Optional<LinkTypeEntity> target = replace ? types.find(tenant, replaceWith.strip()) : Optional.empty();
        if (replace && (target.isEmpty() || target.get().code().equals(code))) {
            return Problems.badRequest("replaceWith is the code of another link type");
        }
        List<IssueLinkEntity> used = links.findByTenantAndType(tenant, code);
        if (!used.isEmpty() && !replace && !deleteLinks) {
            return Problems.conflict("The link type has " + used.size() + " links: move them to another type or "
                    + "delete them");
        }
        Instant now = Instant.now();
        Map<UUID, String> keys = keys(used);
        List<FieldChange[]> changes = new ArrayList<>();
        for (IssueLinkEntity link : used) {
            String before = LinkTypes.relation(type, true);
            String beforeInward = LinkTypes.relation(type, false);
            String after = null;
            String afterInward = null;
            if (target.isPresent() && !exists(target.get(), link)) {
                link.retype(target.get().code());
                after = LinkTypes.relation(target.get(), true);
                afterInward = LinkTypes.relation(target.get(), false);
            } else {
                links.delete(link);
            }
            String toKey = keys.get(link.toId());
            String fromKey = keys.get(link.fromId());
            changes.add(new FieldChange[] {
                new FieldChange("links", before + " " + toKey, after == null ? null : after + " " + toKey),
                new FieldChange("links", beforeInward + " " + fromKey, afterInward == null ? null : afterInward + " " + fromKey)});
        }
        links.flush();
        repository.delete(type);
        repository.flush();
        for (int n = 0; n < used.size(); n++) {
            IssueLinkEntity link = used.get(n);
            changed(link.fromId(), keys.get(link.fromId()), now, changes.get(n)[0]);
            changed(link.toId(), keys.get(link.toId()), now, changes.get(n)[1]);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("links", used.size());
        after.put("replaceWith", target.map(LinkTypeEntity::code).orElse(null));
        log.tenant("linktype.removed", code, logged(type), after);
        return ResponseEntity.noContent().build();
    }

    /** The other type already joins the two issues this way: the link would be a copy of it, and goes. */
    private boolean exists(LinkTypeEntity target, IssueLinkEntity link) {
        return links.existsByFromIdAndToIdAndType(link.fromId(), link.toId(), target.code())
                || target.symmetric() && links.existsByFromIdAndToIdAndType(link.toId(), link.fromId(), target.code());
    }

    /** The keys of the issues at both ends of the links. */
    private Map<UUID, String> keys(List<IssueLinkEntity> used) {
        List<UUID> ids = used.stream().flatMap(l -> Stream.of(l.fromId(), l.toId())).distinct().toList();
        Map<UUID, Optional<ProjectRef>> byProject = new HashMap<>();
        return issues.findByIdIn(ids).stream().collect(Collectors.toMap(IssueEntity::id, i -> byProject
                .computeIfAbsent(i.projectId(), projects::get)
                .map(p -> IssueController.key(p, i)).orElse(String.valueOf(i.number()))));
    }

    private void changed(UUID issueId, String key, Instant now, FieldChange change) {
        issues.findById(issueId).ifPresent(i -> events.publishEvent(
                new IssueChanged(issueId, i.projectId(), key, user.username(), now, List.of(change))));
    }

    private LinkTypeView view(LinkTypeEntity type) {
        long count = links.countByTenantAndType(type.tenantId(), type.code());
        boolean admin = user.can(ProjectAccess.TENANT_ADMIN);
        return new LinkTypeView(type.code(), type.outward(), type.inward(), types.label(type, true),
                types.label(type, false), type.system(), count, type.updatedBy(), type.updatedAt(), type.version(),
                Map.of("edit", admin, "remove", admin && !LinkTypeDefaults.CLONES.equals(type.code())));
    }

    private static Optional<ResponseEntity<?>> wrongText(String outward, String inward) {
        for (String text : new String[] {outward, inward}) {
            if (text == null || text.isBlank()) {
                return Optional.of(Problems.badRequest("The descriptions of both ends are required"));
            }
            if (text.strip().length() > MAX_DESCRIPTION) {
                return Optional.of(Problems.badRequest("A description is at most " + MAX_DESCRIPTION + " characters"));
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> logged(LinkTypeEntity type) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("outward", type.outward());
        values.put("inward", type.inward());
        return values;
    }
}
