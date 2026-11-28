/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import static com.logicwarez.opendevtrack.shared.ConfigurationChanged.values;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.TeamEvents.TeamDeleted;
import com.logicwarez.opendevtrack.identity.TenantUsers;
import com.logicwarez.opendevtrack.platform.AdminLog;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * The teams of the tenant (ADR-31), as in Jira: every user of the projects reads them (an issue names one), the admin
 * of the tenant ({@value ProjectAccess#TENANT_ADMIN}) adds, changes and removes them, and the admin or the lead of a
 * team manages its members. A team gives no permission.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class TeamController {

    private static final String TEAMS = "/api/teams";
    private static final String ADMIN = "hasAuthority('" + ProjectAccess.TENANT_ADMIN + "')";
    private static final String NOT_FOUND = "Team not found";
    private static final int MAX_NAME = 100;
    private static final int MAX_DESCRIPTION = 500;

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final TenantUsers users;
    private final CurrentUser user;
    private final AdminLog log;
    private final ApplicationEventPublisher events;

    TeamController(TeamRepository teams, TeamMemberRepository members, TenantUsers users, CurrentUser user,
                   AdminLog log, ApplicationEventPublisher events) {
        this.teams = teams;
        this.members = members;
        this.users = users;
        this.user = user;
        this.log = log;
        this.events = events;
    }

    /** A team, its lead and how many members it has. */
    record TeamView(UUID id, String name, String description, String lead, long members, long version,
                    @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A new team, or a change: name, description, lead (a user of the workspace), and the version read for a change. */
    record TeamRequest(String name, String description, String lead, Long version) {
    }

    /** A user in a team. */
    record TeamMemberView(String username, String addedBy, Instant addedAt,
                          @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** A user to add to a team. */
    record TeamMemberRequest(String username) {
    }

    @ApiDoc(value = "The teams of the workspace; q keeps those whose name contains it", answer = TeamView.class,
            list = true)
    @GetMapping(TEAMS)
    List<TeamView> list(@RequestParam(required = false) String q) {
        String text = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return teams.findByTenantIdOrderByNameAsc(user.tenantId()).stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).contains(text))
                .map(this::view).toList();
    }

    @ApiDoc(value = "A team", answer = TeamView.class)
    @GetMapping(TEAMS + "/{id}")
    ResponseEntity<?> get(@PathVariable UUID id) {
        return find(id).<ResponseEntity<?>>map(t -> ResponseEntity.ok(view(t)))
                .orElseGet(() -> Problems.notFound(NOT_FOUND));
    }

    @ApiDoc(value = "Adds a team", answer = TeamView.class, status = 201)
    @PostMapping(TEAMS)
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> add(@RequestBody TeamRequest request) {
        Optional<ResponseEntity<?>> wrong = wrong(request, null);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        TeamEntity added = teams.saveAndFlush(new TeamEntity(user.tenantId(), request.name().strip(),
                blankToNull(request.description()), blankToNull(request.lead()), user.username(), Instant.now()));
        log.tenant("team.created", added.name(), null, logged(added));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(added));
    }

    @ApiDoc(value = "Changes the name, the description and the lead of a team", answer = TeamView.class)
    @PutMapping(TEAMS + "/{id}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody TeamRequest request) {
        Optional<TeamEntity> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Optional<ResponseEntity<?>> wrong = wrong(request, id);
        if (wrong.isPresent()) {
            return wrong.get();
        }
        if (request.version() == null || request.version() != found.get().version()) {
            return Problems.changedMeanwhile();
        }
        var before = logged(found.get());
        found.get().edit(request.name().strip(), blankToNull(request.description()), blankToNull(request.lead()));
        log.tenant("team.changed", found.get().name(), before, logged(found.get()));
        return ResponseEntity.ok(view(teams.saveAndFlush(found.get())));
    }

    @ApiDoc(value = "Removes a team: the issues that name it are left without a team", status = 204)
    @DeleteMapping(TEAMS + "/{id}")
    @PreAuthorize(ADMIN)
    @Transactional
    ResponseEntity<?> remove(@PathVariable UUID id) {
        Optional<TeamEntity> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        teams.delete(found.get());
        log.tenant("team.removed", found.get().name(), logged(found.get()), null);
        events.publishEvent(new TeamDeleted(id, user.tenantId(), found.get().name(), user.username(), Instant.now()));
        return ResponseEntity.noContent().build();
    }

    @ApiDoc(value = "The members of a team", answer = TeamMemberView.class, list = true)
    @GetMapping(TEAMS + "/{id}/members")
    ResponseEntity<?> members(@PathVariable UUID id) {
        Optional<TeamEntity> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        boolean manage = manages(found.get());
        return ResponseEntity.ok(members.findByTeamIdOrderByUsername(id).stream()
                .map(m -> new TeamMemberView(m.username(), m.addedBy(), m.addedAt(), Map.of("remove", manage)))
                .toList());
    }

    @ApiDoc(value = "Adds a user of the workspace to a team: the admin or the lead of the team",
            answer = TeamMemberView.class, status = 201)
    @PostMapping(TEAMS + "/{id}/members")
    @Transactional
    ResponseEntity<?> addMember(@PathVariable UUID id, @RequestBody TeamMemberRequest request) {
        Optional<TeamEntity> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!manages(found.get())) {
            return Problems.forbidden("Only the admin of the workspace or the lead of the team manages its members");
        }
        if (request == null || request.username() == null || users.find(request.username().strip()).isEmpty()) {
            return Problems.badRequest("No user with this name in the workspace");
        }
        String username = request.username().strip();
        if (members.existsById(new TeamMemberEntity.Key(id, username))) {
            return Problems.conflict("Already in the team");
        }
        TeamMemberEntity added = members.save(new TeamMemberEntity(id, username, user.username(), Instant.now()));
        log.tenant("team.member_added", found.get().name(), null, values("member", username));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TeamMemberView(added.username(), added.addedBy(), added.addedAt(), Map.of("remove", true)));
    }

    @ApiDoc(value = "Removes a user from a team: the admin or the lead of the team", status = 204)
    @DeleteMapping(TEAMS + "/{id}/members/{username}")
    @Transactional
    ResponseEntity<?> removeMember(@PathVariable UUID id, @PathVariable String username) {
        Optional<TeamEntity> found = find(id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!manages(found.get())) {
            return Problems.forbidden("Only the admin of the workspace or the lead of the team manages its members");
        }
        TeamMemberEntity.Key key = new TeamMemberEntity.Key(id, username);
        if (!members.existsById(key)) {
            return Problems.notFound("Not in the team");
        }
        members.deleteById(key);
        log.tenant("team.member_removed", found.get().name(), values("member", username), null);
        return ResponseEntity.noContent().build();
    }

    private Optional<TeamEntity> find(UUID id) {
        return teams.findByIdAndTenantId(id, user.tenantId());
    }

    private boolean manages(TeamEntity t) {
        return user.can(ProjectAccess.TENANT_ADMIN) || user.username().equals(t.lead());
    }

    private Optional<ResponseEntity<?>> wrong(TeamRequest request, UUID except) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.name().strip().length() > MAX_NAME) {
            return Optional.of(Problems.badRequest("The name is required, at most " + MAX_NAME + " characters"));
        }
        if (request.description() != null && request.description().strip().length() > MAX_DESCRIPTION) {
            return Optional.of(Problems.badRequest("The description is at most " + MAX_DESCRIPTION + " characters"));
        }
        String lead = blankToNull(request.lead());
        if (lead != null && users.find(lead).isEmpty()) {
            return Optional.of(Problems.badRequest("No user with this name in the workspace"));
        }
        if (teams.nameTaken(user.tenantId(), request.name().strip(), except)) {
            return Optional.of(Problems.conflict("A team with this name already exists"));
        }
        return Optional.empty();
    }

    private TeamView view(TeamEntity t) {
        boolean admin = user.can(ProjectAccess.TENANT_ADMIN);
        return new TeamView(t.id(), t.name(), t.description(), t.lead(), members.countByTeamId(t.id()), t.version(),
                Map.of("edit", admin, "remove", admin, "members", manages(t)));
    }

    private static Map<String, Object> logged(TeamEntity t) {
        return values("name", t.name(), "description", t.description(), "lead", t.lead());
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
