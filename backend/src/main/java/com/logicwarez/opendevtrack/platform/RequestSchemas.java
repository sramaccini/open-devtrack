/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * The JSON body of every request of {@code /api} is checked against the schema of its operation in the published
 * OpenAPI definition before a controller reads it (ASVS 13.2.2, ADR-24 point 9): a body that does not match gets 400
 * with the errors as the Problem contract of Quadrava has them ({@code errors[].path}, a JSON Pointer, and
 * {@code code}, the keyword that failed).
 *
 * <p>The schemas are those of {@code docs/openapi.json}, in {@code classpath:openapi/requests.json}: the request bodies
 * by {@code "<METHOD> <path>"} and the components they use. {@code OpenApiContractTest} writes and checks both, so they
 * are the same definition. A {@code null} is a field not given, as for the records of the controllers; fields that the
 * schema does not name are let through, because the forms of the screens send the whole record they read (ADR-24,
 * note of 18 November 2026).
 */
@RestControllerAdvice
class RequestSchemas extends RequestBodyAdviceAdapter {

    static final String LOCATION = "/openapi/requests.json";

    /** Errors returned at most: beyond that the body is wrong as a whole anyway. */
    static final int MAX_ERRORS = 20;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, JsonSchema> schemas = new HashMap<>();

    RequestSchemas() {
        JsonNode definition;
        try (InputStream in = getClass().getResourceAsStream(LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("Request schemas missing from the classpath: " + LOCATION);
            }
            definition = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().locale(Locale.ENGLISH)
                .formatAssertionsEnabled(true).build();
        definition.path("requests").properties().forEach(request -> {
            // Each schema is a document of its own with the components, where its references point
            ObjectNode schema = ((ObjectNode) request.getValue()).deepCopy();
            schema.set("components", definition.path("components"));
            schemas.put(request.getKey(), factory.getSchema(schema, config));
        });
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        JsonSchema schema = schemaOf(current());
        if (schema == null) {
            return message;
        }
        byte[] body = message.getBody().readAllBytes();
        if (body.length > 0) {
            JsonNode document;
            try {
                document = json.readTree(body);
            } catch (IOException e) {
                // Not JSON: the converter answers it as it does today
                document = null;
            }
            if (document != null) {
                List<Problems.FieldError> errors = errors(schema, withoutNulls(document));
                if (!errors.isEmpty()) {
                    throw new InvalidBody(errors);
                }
            }
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }

    @ExceptionHandler(InvalidBody.class)
    ResponseEntity<ProblemDetail> invalid(InvalidBody e) {
        return Problems.invalidFields("The body does not match the API definition", e.errors);
    }

    /** The schema of the operation of the request, by method and path pattern; none outside the definition. */
    private JsonSchema schemaOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return null;
        }
        // The definition names a variable without its regular expression: {key:[A-Z]+} → {key}
        String path = pattern.toString().replaceAll("\\{([^}:]+):[^}]*}", "{$1}");
        return schemas.get(request.getMethod() + " " + path);
    }

    private static HttpServletRequest current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    private static JsonNode withoutNulls(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isNull()) {
                    fields.remove();
                } else {
                    field.setValue(withoutNulls(field.getValue()));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).deepCopy();
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, withoutNulls(copy.get(i)));
            }
            return copy;
        }
        return node;
    }

    static List<Problems.FieldError> errors(JsonSchema schema, JsonNode document) {
        List<Problems.FieldError> errors = new ArrayList<>();
        for (ValidationMessage message : schema.validate(document)) {
            String path = message.getInstanceLocation() == null ? "" : pointer(message.getInstanceLocation().toString());
            // A missing field is named by the object that lacks it
            if ("required".equals(message.getType()) && message.getProperty() != null) {
                path = path + "/" + message.getProperty();
            }
            Problems.FieldError error = new Problems.FieldError(path.isEmpty() ? "/" : path, message.getType());
            if (!errors.contains(error)) {
                errors.add(error);
            }
        }
        return errors.stream().sorted(Comparator.comparing(Problems.FieldError::path)).limit(MAX_ERRORS).toList();
    }

    /** {@code $.links[0].type} → {@code /links/0/type}, as in the registry. */
    private static String pointer(String location) {
        return location.replaceFirst("^\\$", "").replaceAll("\\[(\\d+)]", "/$1").replace('.', '/');
    }

    static final class InvalidBody extends RuntimeException {

        private final transient List<Problems.FieldError> errors;

        InvalidBody(List<Problems.FieldError> errors) {
            super("The body does not match the API definition", null, false, false);
            this.errors = errors;
        }
    }
}
