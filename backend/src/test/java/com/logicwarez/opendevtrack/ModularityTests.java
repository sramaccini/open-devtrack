/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** The boundaries of the modules: the build fails when a module uses the internals of another or a cycle appears. */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(OpenDevTrackApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void everyModuleOfThePlanExists() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
            .containsExactlyInAnyOrder("identity", "access", "project", "workflow", "issue", "collaboration", "agile",
                "search", "reporting", "notification", "audit", "uiregistry", "platform", "shared");
    }

    /** C4 and module canvases in target/spring-modulith-docs, published with the documentation. */
    @Test
    void writesTheDocumentation() {
        new Documenter(MODULES).writeDocumentation();
    }
}
