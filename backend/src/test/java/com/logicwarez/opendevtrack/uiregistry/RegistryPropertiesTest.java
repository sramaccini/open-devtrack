/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.uiregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** No environment is protected unless the configuration names it (ADR-12). */
class RegistryPropertiesTest {

    @Test
    void noProtectedEnvironmentsByDefault() {
        assertThat(new RegistryProperties(null).protectedEnvironments()).isEmpty();
        assertThat(new RegistryProperties(List.of("production")).protectedEnvironments()).containsExactly("production");
    }
}
