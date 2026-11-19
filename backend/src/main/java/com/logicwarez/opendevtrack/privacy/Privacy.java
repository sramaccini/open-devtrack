/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.privacy;

import static com.logicwarez.opendevtrack.shared.ConfigurationChanged.values;

import com.logicwarez.authz.rbac.admin.AdminApi.RoleAssignmentRequest;
import com.logicwarez.authz.rbac.admin.AdminScope;
import com.logicwarez.authz.rbac.admin.RbacAdminService;
import com.logicwarez.opendevtrack.identity.TenantUsers;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.platform.AdminLog;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.PersonalData;
import com.logicwarez.opendevtrack.shared.PersonalData.Subject;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The export and the removal of a user of the tenant of the session (ADR-24, O13): each module that keeps user names
 * gives its part ({@link PersonalData}); this one adds the profile, makes the pseudonyms and removes the user from the
 * tenant in authz-library, in the same transaction as the rest.
 */
@Service
class Privacy {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<PersonalData> modules;
    private final Projects projects;
    private final Issues issues;
    private final TenantUsers users;
    private final RbacAdminService rbac;
    private final CurrentUser user;
    private final AdminLog log;
    private final JdbcTemplate jdbc;

    Privacy(List<PersonalData> modules, Projects projects, Issues issues, TenantUsers users, RbacAdminService rbac,
            CurrentUser user, AdminLog log, JdbcTemplate jdbc) {
        this.modules = modules;
        this.projects = projects;
        this.issues = issues;
        this.users = users;
        this.rbac = rbac;
        this.user = user;
        this.log = log;
        this.jdbc = jdbc;
    }

    /**
     * Everything about the user in the tenant: the profile (null for a user no longer in the tenant) and the parts of
     * the modules, the dates as instants and the JSON values as JSON. The export is in the audit log of the tenant.
     */
    @Transactional
    Map<String, Object> export(String username) {
        Subject subject = subject(username);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("username", username);
        export.put("tenant", subject.tenantId());
        export.put("exportedAt", Instant.now());
        export.put("profile", users.find(subject.tenantId(), username).orElse(null));
        for (PersonalData module : modules) {
            module.export(subject).forEach((part, rows) -> export.put(part, rows.stream().map(Privacy::plain).toList()));
        }
        log.tenant("user.exported", username, null, null);
        return export;
    }

    /**
     * Removes the user from the tenant, all in one transaction: out of their roles in authz-library first (which
     * refuses the administrator themselves and a user with global roles), then their name becomes the pseudonym in the
     * audit of authz-library, which also deletes the user when they have no role left in any tenant (A10), and in the
     * data of every module. A name authz-library does not know (a user removed from it before, whose name is still in
     * the data) is only pseudonymised. Returns the pseudonym.
     */
    @Transactional
    String remove(String username) {
        AdminScope scope = AdminScope.of(user.session());
        if (rbac.findUser(scope, username).isPresent()) {
            rbac.replaceUserRoles(scope, user.tenantId(), username, new RoleAssignmentRequest(List.of()));
        }
        Subject subject = subject(username);
        String pseudonym = "former-user-" + jdbc.queryForObject("""
                INSERT INTO odt_privacy.pseudonym_counter (tenant_id, last) VALUES (?, 1)
                ON CONFLICT (tenant_id) DO UPDATE SET last = odt_privacy.pseudonym_counter.last + 1
                RETURNING last""", Long.class, subject.tenantId());
        rbac.forgetUser(scope, subject.tenantId(), username, pseudonym);
        modules.forEach(module -> module.forget(subject, pseudonym));
        // The log names the pseudonym only: the name of the user is what has just been removed
        log.tenant("user.removed", pseudonym, null, values("pseudonym", pseudonym));
        return pseudonym;
    }

    private Subject subject(String username) {
        List<UUID> projectIds = projects.all().stream().map(ProjectRef::id).toList();
        List<UUID> issueIds = new ArrayList<>();
        projectIds.forEach(id -> issueIds.addAll(issues.idsOf(id)));
        return new Subject(user.tenantId(), username, projectIds, issueIds);
    }

    /** A row of JDBC as JSON: timestamps as instants, jsonb as its JSON, arrays as lists. */
    private static Map<String, Object> plain(Map<String, Object> row) {
        Map<String, Object> plain = new LinkedHashMap<>();
        row.forEach((column, value) -> plain.put(camel(column), plain(value)));
        return plain;
    }

    private static Object plain(Object value) {
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value != null && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
            String json = value.toString();
            return json == null ? null : JSON.readTree(json);
        }
        if (value instanceof java.sql.Array array) {
            try {
                return List.of((Object[]) array.getArray());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("An array of the export cannot be read", e);
            }
        }
        return value;
    }

    /** {@code created_at} → {@code createdAt}, as the rest of the API. */
    private static String camel(String column) {
        StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return name.toString();
    }
}
