/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.uiregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Every text of ui/ has its key in the English and the Italian catalogue (user-facing texts in both, AGENTS.md). */
class UiCataloguesTest {

    private static final Path UI = Path.of("..", "ui");
    private static final Pattern KEY = Pattern.compile("t\\('([a-z0-9_.]+)'");

    @Test
    void everyKeyUsedIsInBothCatalogues() throws IOException {
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(UI)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json") && !f.startsWith(UI.resolve("i18n"))).toList()) {
                Matcher keys = KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                keys.results().forEach(m -> used.add(m.group(1)));
            }
        }
        assertThat(used).isNotEmpty();
        JsonMapper json = JsonMapper.builder().build();
        for (String locale : new String[] {"en", "it"}) {
            @SuppressWarnings("unchecked")
            Map<String, Object> catalogue = json.readValue(Files.readString(UI.resolve("i18n").resolve(locale + ".json"), StandardCharsets.UTF_8), Map.class);
            assertThat(catalogue.keySet()).as(locale).containsAll(used);
        }
    }
}
