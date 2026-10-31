/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProjectRoleTest {

    private static final Path CATALOGUE = Path.of("src/main/resources/db/authz/odt/R__odt_permission_catalogue.sql");

    @Test
    void everyPermissionOfARoleIsInTheCatalogueOfAuthzLibrary() throws IOException {
        Matcher codes = Pattern.compile("'(odt\\.[a-z0-9]+\\.[a-z0-9]+)'").matcher(Files.readString(CATALOGUE, StandardCharsets.UTF_8));
        Set<String> catalogue = codes.results().map(m -> m.group(1)).collect(Collectors.toSet());
        for (ProjectRole role : ProjectRole.values()) {
            assertThat(catalogue).as(role.value()).containsAll(role.permissions());
        }
    }

    @Test
    void eachRoleHoldsThePermissionsOfTheOneBefore() {
        assertThat(ProjectRole.DEVELOPER.permissions()).containsAll(ProjectRole.VIEWER.permissions());
        assertThat(ProjectRole.ADMIN.permissions()).containsAll(ProjectRole.DEVELOPER.permissions());
        assertThat(Arrays.stream(ProjectRole.values()).map(ProjectRole::value)).containsExactly("viewer", "developer", "admin");
        assertThat(ProjectRole.parse("owner")).isEmpty();
    }
}
