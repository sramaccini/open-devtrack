/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

/**
 * The OpenAPI 3.1 definition of {@code /api} (ADR-21, A2), served at {@code /v3/api-docs} and kept in
 * {@code docs/openapi.json} ({@code OpenApiContractTest}). From the code, for each path:
 *
 * <ul>
 *   <li>its group: the module of its controller ({@code issue}, {@code agile}, …);</li>
 *   <li>its id: {@code <controller>.<method>} ({@code issue.update}), unique;</li>
 *   <li>what it does and the body of its answer: {@link ApiDoc};</li>
 *   <li>its permissions: {@code x-permissions} from {@code @PreAuthorize} (global, of the session),
 *   {@code x-project-permissions} from {@link ApiDoc#project()} (of the role in the project);</li>
 *   <li>{@code x-internal: true} for those that serve only a screen;</li>
 *   <li>the Problems (RFC 9457) it may answer: 401 and 403 always, 404 with a path variable, 400 with a body or a
 *   query parameter, 412 when the body carries a {@code version}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String VERSION = "1.0";

    private static final Pattern PERMISSION = Pattern.compile("odt\\.[a-z]+\\.[a-z]+");
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final String PROBLEM = "Problem";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String JSON = "application/json";

    /** The schemas of the answers of {@link ApiDoc}, added to the components at the end. */
    private final Map<String, Schema> answers = new ConcurrentHashMap<>();

    @Bean
    OpenAPI openDevTrackApi(@Value("${server.servlet.session.cookie.name}") String sessionCookie) {
        return new OpenAPI()
                .info(new Info().title("OpenDevTrack API").version(VERSION)
                        .description("""
                                The REST API of OpenDevTrack: the one its screens use. Guide: docs/api.md.

                                Clients send a Keycloak access token in `Authorization: Bearer` (their own, or that \
                                of a service account) and may choose the tenant with `X-Tenant`; the screens use the \
                                session cookie. Within 1.x no public path or field is removed or renamed; paths \
                                marked `x-internal` serve only a screen and may change.""")
                        .license(new License().name("MPL-2.0").url("https://mozilla.org/MPL/2.0/")))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")
                                .description("A Keycloak access token of a client allowed by the installation"))
                        .addSecuritySchemes("session", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name(sessionCookie)
                                .description("The session of the screens (authz-library); writes also need "
                                        + "X-XSRF-TOKEN")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"))
                .addSecurityItem(new SecurityRequirement().addList("session"));
    }

    @Bean
    OperationCustomizer apiDocs() {
        return (operation, handler) -> {
            Class<?> controller = handler.getBeanType();
            operation.setTags(List.of(module(controller)));
            operation.setOperationId(name(controller) + "." + handler.getMethod().getName());
            List<String> global = permissions(handler);
            if (!global.isEmpty()) {
                operation.addExtension("x-permissions", global);
            }
            ApiDoc doc = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), ApiDoc.class);
            if (doc == null) {
                return operation;
            }
            operation.setSummary(doc.value());
            if (doc.project().length > 0) {
                operation.addExtension("x-project-permissions", List.of(doc.project()));
            }
            if (doc.internal()) {
                operation.addExtension("x-internal", true);
            }
            ApiResponses responses = new ApiResponses();
            ApiResponse success = new ApiResponse().description(switch (doc.status()) {
                case 201 -> "Created";
                case 204 -> "Done";
                default -> "OK";
            });
            if (doc.answer() != Void.class) {
                Schema<?> body = schema(doc.answer());
                if (doc.list()) {
                    body = new JsonSchema().types(Set.of("array")).items(body);
                }
                success.content(new Content().addMediaType(JSON, new MediaType().schema(body)));
            }
            responses.addApiResponse(Integer.toString(doc.status()), success);
            operation.setResponses(responses);
            return operation;
        };
    }

    @Bean
    OpenApiCustomizer problems() {
        return api -> {
            Components components = api.getComponents();
            answers.forEach(components::addSchemas);
            components.addSchemas(PROBLEM, problemSchema());
            Map<String, ApiResponse> shared = new LinkedHashMap<>();
            shared.put("BadRequest", problem("The request is not valid: the title says why, errors which fields"));
            shared.put("Unauthorized", problem("No valid session or token"));
            shared.put("Forbidden", problem("The user may not do it"));
            shared.put("NotFound", problem("Not found, or in a project the user does not see (ADR-09)"));
            shared.put("ChangedMeanwhile", problem("The version sent is not the current one: read it again"));
            shared.forEach(components::addResponses);
            if (api.getPaths() == null) {
                return;
            }
            Map<String, List<String>> ids = new HashMap<>();
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                ApiResponses responses = operation.getResponses();
                List<Parameter> parameters = operation.getParameters() == null ? List.of() : operation.getParameters();
                boolean body = operation.getRequestBody() != null;
                if (body || parameters.stream().anyMatch(p -> "query".equals(p.getIn()))) {
                    responses.addApiResponse("400", ref("BadRequest"));
                }
                responses.addApiResponse("401", ref("Unauthorized"));
                responses.addApiResponse("403", ref("Forbidden"));
                if (path.contains("{")) {
                    responses.addApiResponse("404", ref("NotFound"));
                }
                if (body && versioned(operation, components)) {
                    responses.addApiResponse("412", ref("ChangedMeanwhile"));
                }
                ids.computeIfAbsent(operation.getOperationId(), id -> new ArrayList<>()).add(path);
            }));
            unique(api, ids);
        };
    }

    /**
     * The same method on two paths (the statuses of the default workflow and of one by id): the shorter path keeps the
     * id, the other gets {@code By<variable>} from the path variable it adds ({@code workflow.statusesByWorkflow}).
     */
    private static void unique(OpenAPI api, Map<String, List<String>> ids) {
        ids.forEach((id, paths) -> {
            if (paths.size() < 2) {
                return;
            }
            List<String> sorted = paths.stream().sorted(Comparator.comparingInt(String::length)
                    .thenComparing(Comparator.naturalOrder())).toList();
            Set<String> base = variables(sorted.getFirst());
            for (String path : sorted.subList(1, sorted.size())) {
                String added = variables(path).stream().filter(v -> !base.contains(v)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Two paths for " + id + ": " + paths));
                String name = id + "By" + Character.toUpperCase(added.charAt(0)) + added.substring(1);
                api.getPaths().get(path).readOperations().stream().filter(o -> id.equals(o.getOperationId()))
                        .forEach(o -> o.setOperationId(name));
            }
        });
    }

    private static Set<String> variables(String path) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(path);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private Schema<?> schema(Class<?> type) {
        ResolvedSchema resolved = ModelConverters.getInstance(true)
                .resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
        if (resolved.referencedSchemas != null) {
            resolved.referencedSchemas.forEach((name, schema) -> {
                Schema<?> before = answers.putIfAbsent(name, schema);
                if (before != null && !before.equals(schema)) {
                    throw new IllegalStateException("Two answers named " + name + ": rename one of them");
                }
            });
        }
        return resolved.schema;
    }

    private static boolean versioned(Operation operation, Components components) {
        MediaType media = operation.getRequestBody().getContent() == null ? null
                : operation.getRequestBody().getContent().get(JSON);
        if (media == null || media.getSchema() == null || media.getSchema().get$ref() == null) {
            return false;
        }
        String ref = media.getSchema().get$ref();
        Schema<?> schema = components.getSchemas().get(ref.substring(ref.lastIndexOf('/') + 1));
        return schema != null && schema.getProperties() != null && schema.getProperties().containsKey("version");
    }

    private static List<String> permissions(HandlerMethod handler) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        Set<String> found = new TreeSet<>();
        if (rule != null) {
            Matcher m = PERMISSION.matcher(rule.value());
            while (m.find()) {
                found.add(m.group());
            }
        }
        return new ArrayList<>(found);
    }

    /** {@code com.logicwarez.opendevtrack.issue.LinkController} → {@code issue}. */
    private static String module(Class<?> controller) {
        String name = controller.getPackageName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** {@code LinkController} → {@code link}. */
    private static String name(Class<?> controller) {
        String simple = controller.getSimpleName().replace("Controller", "");
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static ApiResponse ref(String name) {
        return new ApiResponse().$ref("#/components/responses/" + name);
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType(PROBLEM_JSON,
                new MediaType().schema(new JsonSchema().$ref("#/components/schemas/" + PROBLEM))));
    }

    private static Schema<?> problemSchema() {
        Map<String, Schema> fields = new LinkedHashMap<>();
        fields.put("type", text("A URI that names the kind of problem; about:blank for most"));
        fields.put("title", text("What is wrong, in English"));
        fields.put("status", new JsonSchema().types(Set.of("integer")).description("The HTTP status"));
        fields.put("detail", text("More on this occurrence, when there is"));
        fields.put("instance", text("The path of the request"));
        Map<String, Schema> error = new LinkedHashMap<>();
        error.put("path", text("A JSON Pointer into the body sent, for example /custom/customer"));
        error.put("code", text("What is wrong with it, for example required"));
        fields.put("errors", new JsonSchema().types(Set.of("array")).description("The fields that are not valid")
                .items(new JsonSchema().types(Set.of("object")).properties(error)));
        return new JsonSchema().types(Set.of("object")).description("An error answer, RFC 9457").properties(fields);
    }

    private static Schema<?> text(String description) {
        return new JsonSchema().types(Set.of("string")).description(description);
    }
}
