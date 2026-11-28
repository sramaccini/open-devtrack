/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The teams of the tenants (ADR-31) for the other modules: the Team of an issue in {@code issue}, ODQL and the rows of
 * the search in {@code search}. A team gives no permission, so reading one needs none.
 */
@Component
@Transactional(readOnly = true)
public class Teams {

    private final TeamRepository teams;

    Teams(TeamRepository teams) {
        this.teams = teams;
    }

    /** A team: its identifier and its name. */
    public record Team(UUID id, String name) {
    }

    /** The team of the tenant with this name, in any case. */
    public Optional<Team> named(String tenantId, String name) {
        return name == null ? Optional.empty() : teams.findNamed(tenantId, name.strip()).map(Teams::team);
    }

    /** The team of the tenant with this identifier. */
    public Optional<Team> find(String tenantId, UUID id) {
        return id == null ? Optional.empty() : teams.findByIdAndTenantId(id, tenantId).map(Teams::team);
    }

    /** The names of these teams, by identifier; those gone are missing. */
    public Map<UUID, String> names(Collection<UUID> ids) {
        return ids.isEmpty() ? Map.of() : teams.findByIdIn(ids).stream()
                .collect(Collectors.toMap(TeamEntity::id, TeamEntity::name));
    }

    /** The teams of the tenant, by name. */
    public List<Team> of(String tenantId) {
        return teams.findByTenantIdOrderByNameAsc(tenantId).stream().map(Teams::team).toList();
    }

    private static Team team(TeamEntity t) {
        return new Team(t.id(), t.name());
    }
}
