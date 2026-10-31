/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Adapted from the Quadrava reference registry (reference/registry, commit 9fa1916), contracts/registry.yaml 0.6.0.
 */
package com.logicwarez.opendevtrack.uiregistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicwarez.opendevtrack.uiregistry.Model.Issue;
import com.logicwarez.opendevtrack.uiregistry.Model.Kind;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Validates the documents against the schema of their kind (screen, datasources, navigation, theme, messages). The schemas
 * are those of the pinned Quadrava version, in {@code classpath:quadrava/schemas} ({@code backend/tools/sync-quadrava-schemas.sh}).
 * References between schemas ({@code datasources} uses the data source of {@code screen}) are read from the classpath,
 * never from the network; their base is the one of the {@code $id} of the schemas, which changed between versions.
 *
 * <p>CEL expressions are not checked here (Studio and the runtime do it, fail-closed).
 */
@Component
class SchemaValidation {

    /** Maximum number of errors returned: beyond that the document needs reworking anyway. */
    static final int MAX_ISSUES = 20;

    static final String LOCATION = "/quadrava/schemas/";

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Kind, JsonSchema> schemas = new EnumMap<>(Kind.class);

    SchemaValidation() {
        String base = schemaBase();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(base, "classpath:" + LOCATION.substring(1))));
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().locale(Locale.ENGLISH).build();
        for (Kind kind : Kind.values()) {
            try (InputStream in = open(kind.value())) {
                schemas.put(kind, factory.getSchema(in, config));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Problems of the document against the schema of its kind, sorted by position; empty if it is valid. */
    List<Issue> validate(Kind kind, String content) {
        JsonNode document;
        try {
            document = json.readTree(content);
        } catch (IOException e) {
            return List.of(new Issue("/", "The content is not valid JSON"));
        }
        return schemas.get(kind).validate(document).stream()
                .map(SchemaValidation::issue)
                .distinct()
                .sorted(Comparator.comparing(Issue::path))
                .limit(MAX_ISSUES)
                .toList();
    }

    /** Base of the {@code $id} of the schemas, from the screen schema: {@code https://…/schemas/}. */
    private String schemaBase() {
        try (InputStream in = open(Kind.SCREEN.value())) {
            String id = json.readTree(in).path("$id").asText();
            int slash = id.lastIndexOf('/');
            if (slash < 0) {
                throw new IllegalStateException("The screen schema has no $id");
            }
            return id.substring(0, slash + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private InputStream open(String kind) {
        InputStream in = getClass().getResourceAsStream(LOCATION + kind + ".schema.json");
        if (in == null) {
            throw new IllegalStateException("Schema missing from the classpath: " + kind);
        }
        return in;
    }

    private static Issue issue(ValidationMessage message) {
        String location = message.getInstanceLocation() == null ? "" : message.getInstanceLocation().toString();
        // Path as a JSON Pointer, as in Studio ($.components[0].type → /components/0/type).
        String path = location.replaceFirst("^\\$", "").replaceAll("\\[(\\d+)]", "/$1").replace('.', '/');
        // The message starts with the position, already in the path field.
        String text = message.getMessage();
        if (!location.isEmpty() && text.startsWith(location + ": ")) {
            text = text.substring(location.length() + 2);
        }
        return new Issue(path.isEmpty() ? "/" : path, text);
    }
}
