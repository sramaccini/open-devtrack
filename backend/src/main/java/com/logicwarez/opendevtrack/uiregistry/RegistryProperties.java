/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.uiregistry;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the registry ({@code odt.uiregistry.*}).
 *
 * @param protectedEnvironments environments where only approved versions are published (usually testing and production)
 */
@ConfigurationProperties("odt.uiregistry")
record RegistryProperties(List<String> protectedEnvironments) {

    RegistryProperties {
        protectedEnvironments = protectedEnvironments == null ? List.of("testing", "production") : List.copyOf(protectedEnvironments);
    }
}
