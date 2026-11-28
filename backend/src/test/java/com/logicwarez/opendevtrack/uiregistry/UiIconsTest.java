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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every button and action of the screens has an icon of the set of Quadrava (ui/README.md, «Icons»; Q27). */
class UiIconsTest {

    private static final Path SCREENS = Path.of("..", "ui", "screens");
    /**
     * The icons Quadrava offers for actions (catalogue, § 2.32): those of 0.19.0, flag and like of 0.25.0 (Q41), share
     * of 0.29.0 (Q45). From 0.29.0 any Ant Design name works too; the screens keep to the short names, and an unknown
     * name shows no icon. clock-circle, of Ant Design, logs work (ADR-30): the set has no clock.
     */
    private static final Set<String> ICONS = Set.of("save", "edit", "delete", "plus", "check", "close", "link", "eye",
            "eye-invisible", "user-add", "back", "move", "play", "reload", "download", "upload", "external", "copy",
            "search", "filter", "settings", "flag", "like", "share", "clock-circle");
    private static final List<String> ACTIONS = List.of("actions", "rowActions", "bulkActions");

    @Test
    void everyButtonAndActionHasAnIcon() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        List<String> without = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(SCREENS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                List<JsonNode> buttons = new ArrayList<>();
                collect(json.readTree(Files.readString(file, StandardCharsets.UTF_8)), buttons);
                for (JsonNode button : buttons) {
                    checked++;
                    if (!ICONS.contains(button.path("icon").asString(""))) {
                        without.add(file.getFileName() + ": " + button.path("label").asString(""));
                    }
                }
            }
        }
        assertThat(checked).isPositive();
        assertThat(without).isEmpty();
    }

    private static void collect(JsonNode node, List<JsonNode> buttons) {
        if (node.isArray()) {
            node.forEach(child -> collect(child, buttons));
        } else if (node.isObject()) {
            boolean button = "button".equals(node.path("type").asString(""));
            if (button && node.has("label")) {
                buttons.add(node);
            }
            for (String key : ACTIONS) {
                if (!button && node.path(key).isArray()) {
                    node.path(key).forEach(action -> {
                        if (action.has("label")) {
                            buttons.add(action);
                        }
                    });
                }
            }
            node.values().forEach(child -> collect(child, buttons));
        }
    }
}
