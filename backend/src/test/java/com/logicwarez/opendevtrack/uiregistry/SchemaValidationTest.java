/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Adapted from the Quadrava reference registry (reference/registry, commit 9fa1916), contracts/registry.yaml 0.6.0.
 */
package com.logicwarez.opendevtrack.uiregistry;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicwarez.opendevtrack.uiregistry.Model.Issue;
import com.logicwarez.opendevtrack.uiregistry.Model.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Validation against the schemas of the pinned Quadrava version, read from the classpath. */
class SchemaValidationTest {

    private final SchemaValidation validation = new SchemaValidation();
    private static final Path UI = Path.of("..", "ui");

    @Test
    void everyDocumentOfTheUiProjectIsValid() throws IOException {
        int checked = 0;
        for (String folder : List.of("screens", "themes", "datasources")) {
            Kind kind = switch (folder) {
                case "screens" -> Kind.SCREEN;
                case "themes" -> Kind.THEME;
                default -> Kind.DATASOURCES;
            };
            if (!Files.isDirectory(UI.resolve(folder))) {
                continue;
            }
            try (Stream<Path> files = Files.list(UI.resolve(folder))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    assertThat(validation.validate(kind, Files.readString(file, StandardCharsets.UTF_8))).as(file.toString()).isEmpty();
                    checked++;
                }
            }
        }
        assertThat(validation.validate(Kind.NAVIGATION, Files.readString(UI.resolve("navigation.json"), StandardCharsets.UTF_8))).isEmpty();
        // The catalogues are flat maps in the project and messages documents in the registry (tools/publish-ui.mjs).
        for (String locale : List.of("en", "it")) {
            String texts = Files.readString(UI.resolve("i18n").resolve(locale + ".json"), StandardCharsets.UTF_8);
            String document = "{\"schemaVersion\":\"1.0\",\"messages\":\"" + locale + "\",\"texts\":" + texts + "}";
            assertThat(validation.validate(Kind.MESSAGES, document)).as(locale).isEmpty();
        }
        assertThat(checked).isGreaterThanOrEqualTo(4);
    }

    @Test
    void validDocumentsOfEveryKindPass() {
        assertThat(validation.validate(Kind.SCREEN, """
                {"schemaVersion":"1.0","screen":"project-list","components":[{"type":"text","text":"Projects"}]}""")).isEmpty();
        assertThat(validation.validate(Kind.NAVIGATION, """
                {"schemaVersion":"1.0","navigation":"main","areas":[{"id":"projects","label":"Projects","items":[
                  {"id":"list","label":"List","screen":"project-list"}]}]}""")).isEmpty();
        assertThat(validation.validate(Kind.MESSAGES, """
                {"schemaVersion":"1.0","messages":"en","texts":{"projects.title":"Projects"}}""")).isEmpty();
    }

    @Test
    void theDataSourcesUseTheDefinitionOfTheScreenSchema() {
        assertThat(validation.validate(Kind.DATASOURCES, """
                {"schemaVersion":"1.0","datasources":"projects","sources":{"list":{"type":"rest","method":"GET","url":"/api/projects"}}}""")).isEmpty();
        // An incomplete data source is rejected: the reference to screen.schema.json was resolved from the classpath.
        assertThat(validation.validate(Kind.DATASOURCES, """
                {"schemaVersion":"1.0","datasources":"x","sources":{"list":{"url":"/api"}}}""")).isNotEmpty();
    }

    @Test
    void invalidCatalogueKeysAreRejected() {
        assertThat(validation.validate(Kind.MESSAGES, "{\"schemaVersion\":\"1.0\",\"messages\":\"it\",\"texts\":{\"a b\":\"x\"}}")).isNotEmpty();
    }

    @Test
    void reportsPositionAndReason() {
        List<Issue> issues = validation.validate(Kind.SCREEN, """
                {"schemaVersion":"1.0","screen":"x","components":[{"type":"grid","columns":13,"components":[]}]}""");
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> assertThat(i.path()).isEqualTo("/components/0/columns"));
        assertThat(validation.validate(Kind.THEME, "not json")).extracting(Issue::path).containsExactly("/");
    }
}
