/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.privacy;

import com.logicwarez.authz.rbac.admin.AdminProblem;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The export of a user's data, for themselves and for an administrator of the tenant, and their removal from the
 * tenant (ADR-24, O13). The export is a JSON file to download.
 */
@RestController
class PrivacyController {

    private static final String ADMIN = "hasAuthority('authz.rbac.manage')";

    private final Privacy privacy;
    private final CurrentUser user;

    PrivacyController(Privacy privacy, CurrentUser user) {
        this.privacy = privacy;
        this.user = user;
    }

    /** {@code confirm}: the user name again, typed by the administrator. */
    record RemoveUserRequest(String confirm) {
    }

    /** The pseudonym that now stands for the user in the data of the tenant. */
    record Removed(String pseudonym) {
    }

    @ApiDoc("Everything OpenDevTrack keeps about the signed-in user in this tenant, as a JSON file")
    @GetMapping("/api/me/export")
    ResponseEntity<Map<String, Object>> mine() {
        return file(user.username());
    }

    @ApiDoc("Everything OpenDevTrack keeps about a user of the tenant, as a JSON file")
    @GetMapping("/api/admin/users/{username}/export")
    @PreAuthorize(ADMIN)
    ResponseEntity<Map<String, Object>> ofUser(@PathVariable String username) {
        return file(username);
    }

    @ApiDoc(value = "Removes a user from the tenant: their name in the work of the projects becomes a pseudonym",
            answer = Removed.class)
    @PostMapping("/api/admin/users/{username}/remove")
    @PreAuthorize(ADMIN)
    ResponseEntity<?> remove(@PathVariable String username, @RequestBody RemoveUserRequest request) {
        if (username.equals(user.username())) {
            return Problems.forbidden("Administrators cannot remove themselves");
        }
        if (request.confirm() == null || !request.confirm().equals(username)) {
            return Problems.invalidFields("The confirmation is not the user name",
                    List.of(new Problems.FieldError("/confirm", "confirm")));
        }
        try {
            return ResponseEntity.ok(new Removed(privacy.remove(username)));
        } catch (AdminProblem problem) {
            // authz-library refuses, for example, a user with global roles to the administrator of a tenant
            return Problems.of(problem.status(), "The user cannot be removed: " + problem.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> file(String username) {
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("opendevtrack-" + username + ".json", StandardCharsets.UTF_8).build().toString())
                .body(privacy.export(username));
    }
}
