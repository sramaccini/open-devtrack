/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Adapted from the Quadrava reference registry (reference/registry, commit 9fa1916), contracts/registry.yaml 0.6.0.
 */
package com.logicwarez.opendevtrack.uiregistry;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicwarez.opendevtrack.uiregistry.Model.DocumentKey;
import com.logicwarez.opendevtrack.uiregistry.Model.Kind;
import org.junit.jupiter.api.Test;

class CheckContentTest {

    private final DocumentKey list = new DocumentKey("opendevtrack", Kind.SCREEN, "project-list");
    private final DocumentKey menu = new DocumentKey("opendevtrack", Kind.NAVIGATION, "main");

    @Test
    void theContentMustHaveTheIdentifierOfThePath() {
        assertThat(RegistryController.checkContent(list, "{\"schemaVersion\":\"1.0\",\"screen\":\"project-list\"}")).isEmpty();
        assertThat(RegistryController.checkContent(list, "{\"schemaVersion\":\"1.0\",\"screen\":\"other\"}")).isPresent();
        assertThat(RegistryController.checkContent(menu, "{\"navigation\":\"main\"}")).isPresent();
        assertThat(RegistryController.checkContent(menu, "[1, 2]")).isPresent();
    }
}
