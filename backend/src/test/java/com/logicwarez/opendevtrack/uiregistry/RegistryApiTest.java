/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Adapted from the Quadrava reference registry (reference/registry, commit 9fa1916), contracts/registry.yaml 0.6.0.
 */
package com.logicwarez.opendevtrack.uiregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicwarez.opendevtrack.TestInfrastructure;
import jakarta.servlet.Filter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The registry.yaml 0.6 contract over HTTP, on PostgreSQL: validation, concurrent changes, review, protected
 * environments, cross-document references. The callers are tools with a bearer token; their permissions come from the
 * RBAC catalogue of authz-library ({@code uiregistry/users.sql}): mario and luisa publish, paola approves, carla does
 * both, ugo is only a member.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/uiregistry/users.sql")
class RegistryApiTest {

    private static final String BASE = "/registry/apps/demo/documents/screen/invoice-list";
    private static final String VALID = """
            {"schemaVersion":"1.0","screen":"invoice-list","components":[{"type":"text","text":"Invoices"}]}""";

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    /** The token is the user name: what is under test is what the registry does with the user, not the signature. */
    @MockitoBean
    JwtDecoder toolTokens;

    MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_uiregistry.document_version, odt_uiregistry.publication");
        given(toolTokens.decode(anyString())).willAnswer(call -> {
            String name = call.getArgument(0);
            return Jwt.withTokenValue(name).header("alg", "none").subject(name)
                    .claim("preferred_username", name).claim("azp", "opendevtrack-tools").build();
        });
    }

    private static RequestPostProcessor user(String name) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + name);
            return request;
        };
    }

    private String save(String path, String content, String who) throws Exception {
        String body = mvc.perform(post(path + "/versions").with(user(who)).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return (String) json.readValue(body, Map.class).get("hash");
    }

    private void review(String path, String hash, String action, String who, int expected) throws Exception {
        mvc.perform(post(path + "/versions/" + hash + "/review").with(user(who)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\"}"))
                .andExpect(status().is(expected));
    }

    private void publish(String path, String env, String hash, int expected) throws Exception {
        mvc.perform(put(path + "/published").param("env", env).with(user("mario"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hash\":\"" + hash + "\"}"))
                .andExpect(status().is(expected));
    }

    @Test
    void withoutATokenThereIsNoAccess() throws Exception {
        mvc.perform(get("/registry/apps")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMemberReadsWhatIsPublishedButNotTheVersions() throws Exception {
        String hash = save(BASE, VALID, "mario");
        publish(BASE, "development", hash, 204);
        mvc.perform(get(BASE + "/published").param("env", "development").with(user("ugo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screen").value("invoice-list"));
        mvc.perform(get(BASE + "/versions").with(user("ugo"))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/versions").with(user("paola")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDocumentNotMatchingTheSchemaIsRejectedWithDetails() throws Exception {
        String invalid = """
                {"schemaVersion":"1.0","screen":"invoice-list","components":[{"type":"table"},{"type":"text"}]}""";
        mvc.perform(post(BASE + "/versions").with(user("mario")).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The document does not match the schema"))
                .andExpect(jsonPath("$.errors[0].path").value(org.hamcrest.Matchers.startsWith("/components/")));
        // The author is the user name of the token.
        mvc.perform(post(BASE + "/versions").with(user("mario")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author").value("mario"))
                .andExpect(jsonPath("$.state").value("draft"));
    }

    @Test
    void invalidIdentifiersAreRejected() throws Exception {
        mvc.perform(post("/registry/apps/Demo_1/documents/screen/x/versions").with(user("mario")).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSameContentIsTheSameVersion() throws Exception {
        String first = save(BASE, VALID, "mario");
        String again = save(BASE, VALID, "luisa");
        assertThat(again).isEqualTo(first);
        mvc.perform(get(BASE + "/versions").with(user("mario")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].author").value("mario"));
    }

    @Test
    void withIfMatchTheSaveSucceedsOnlyOnTheLatestVersion() throws Exception {
        String first = save(BASE, VALID, "mario");
        String second = VALID.replace("Invoices", "Issued invoices");
        // Luisa saves starting from the first version: fine.
        mvc.perform(post(BASE + "/versions").with(user("luisa")).header("If-Match", "\"" + first + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isCreated());
        // Mario saves again starting from the first one: someone saved in the meantime.
        mvc.perform(post(BASE + "/versions").with(user("mario")).header("If-Match", "\"" + first + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID.replace("Invoices", "Received invoices")))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.latestAuthor").value("luisa"));
    }

    @Test
    void fourEyesReviewAndProtectedEnvironments() throws Exception {
        String hash = save(BASE, VALID, "carla");
        // In development anything is published; in production only an approved version.
        publish(BASE, "development", hash, 204);
        publish(BASE, "production", hash, 409);

        // Only an author requests the review.
        review(BASE, hash, "request", "paola", 403);
        review(BASE, hash, "request", "carla", 200);
        // An author does not approve; nor does the author of the version, even with the permission to approve.
        review(BASE, hash, "approve", "mario", 403);
        review(BASE, hash, "approve", "carla", 403);
        review(BASE, hash, "approve", "paola", 200);
        review(BASE, hash, "approve", "paola", 409);

        publish(BASE, "production", hash, 204);
        String versions = mvc.perform(get(BASE + "/versions").with(user("mario")))
                .andExpect(jsonPath("$[0].state").value("approved"))
                .andExpect(jsonPath("$[0].reviewedBy").value("paola"))
                .andReturn().getResponse().getContentAsString();
        assertThat(versions).contains("reviewedAt");
    }

    @Test
    void aRejectedVersionGoesBackToReview() throws Exception {
        String hash = save(BASE, VALID, "mario");
        // A draft is not approved: first the review is requested.
        review(BASE, hash, "approve", "paola", 409);
        review(BASE, hash, "request", "mario", 200);
        mvc.perform(post(BASE + "/versions/" + hash + "/review").with(user("paola")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"reject\",\"note\":\"The status filter is missing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("rejected"))
                .andExpect(jsonPath("$.reviewNote").value("The status filter is missing"));
        review(BASE, hash, "request", "mario", 200);
        review(BASE, hash, "approve", "paola", 200);
        review(BASE, "missing", "request", "mario", 404);
    }

    @Test
    void publishingAgainIsTheRollback() throws Exception {
        String v1 = save(BASE, VALID, "mario");
        String v2 = save(BASE, VALID.replace("Invoices", "All invoices"), "mario");
        publish(BASE, "development", v2, 204);
        publish(BASE, "development", v1, 204);
        mvc.perform(get(BASE + "/published").param("env", "development").with(user("mario")))
                .andExpect(jsonPath("$.components[0].text").value("Invoices"));
        // A version of one document is not published as a version of another.
        publish("/registry/apps/demo/documents/screen/other", "development", v1, 400);
        mvc.perform(get("/registry/apps").with(user("mario"))).andExpect(jsonPath("$[0].id").value("demo"));
    }

    @Test
    void aNavigationIsPublishedInProductionOnlyWithItsScreens() throws Exception {
        String nav = "/registry/apps/demo/documents/navigation/main";
        String navigation = """
                {"schemaVersion":"1.0","navigation":"main","areas":[{"id":"invoices","label":"Invoices","items":[
                  {"id":"list","label":"List","screen":"invoice-list"},{"id":"new","label":"New","screen":"invoice-new"}]}]}""";
        String navHash = save(nav, navigation, "mario");
        String screenHash = save(BASE, VALID, "mario");

        // Without an environment: the screen that does not exist in the registry is missing.
        mvc.perform(get(nav + "/versions/" + navHash + "/references").with(user("mario")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.env").doesNotExist())
                .andExpect(jsonPath("$.references.length()").value(2))
                .andExpect(jsonPath("$.missing.length()").value(1))
                .andExpect(jsonPath("$.missing[0].screen").value("invoice-new"))
                .andExpect(jsonPath("$.missing[0].path").value("/areas/0/items/1/screen"));
        // In development neither is published, but the navigation is published anyway.
        mvc.perform(get(nav + "/versions/" + navHash + "/references").param("env", "development").with(user("mario")))
                .andExpect(jsonPath("$.env").value("development"))
                .andExpect(jsonPath("$.missing.length()").value(2));
        publish(nav, "development", navHash, 204);

        // In production, approved, it is rejected until the screens are there.
        review(nav, navHash, "request", "mario", 200);
        review(nav, navHash, "approve", "paola", 200);
        review(BASE, screenHash, "request", "mario", 200);
        review(BASE, screenHash, "approve", "paola", 200);
        publish(BASE, "production", screenHash, 204);
        mvc.perform(put(nav + "/published").param("env", "production").with(user("mario"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hash\":\"" + navHash + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missingReferences.length()").value(1))
                .andExpect(jsonPath("$.missingReferences[0].screen").value("invoice-new"));

        // Version not found or invalid environment.
        mvc.perform(get(nav + "/versions/missing/references").with(user("mario"))).andExpect(status().isNotFound());
        mvc.perform(get(nav + "/versions/" + navHash + "/references").param("env", "Prod!").with(user("mario"))).andExpect(status().isBadRequest());
    }

    @Test
    void aScreenIsPublishedInProductionOnlyWithItsDataSources() throws Exception {
        String sources = "/registry/apps/demo/documents/datasources/invoices";
        String document = """
                {"schemaVersion":"1.0","datasources":"invoices","sources":{"list":{"type":"rest","method":"GET","url":"/api/invoices"}}}""";
        String screen = """
                {"schemaVersion":"1.0","screen":"invoice-list","dataSources":{"invoices":{"ref":"invoices/list"}},
                 "components":[{"type":"table","source":"invoices","columns":[{"field":"number"}]}]}""";
        String screenHash = save(BASE, screen, "mario");
        mvc.perform(get(BASE + "/versions/" + screenHash + "/references").with(user("mario")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSources.length()").value(1))
                .andExpect(jsonPath("$.dataSources[0].datasources").value("invoices"))
                .andExpect(jsonPath("$.dataSources[0].path").value("/dataSources/invoices/ref"))
                .andExpect(jsonPath("$.missingDataSources.length()").value(1));
        review(BASE, screenHash, "request", "mario", 200);
        review(BASE, screenHash, "approve", "paola", 200);
        mvc.perform(put(BASE + "/published").param("env", "production").with(user("mario"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hash\":\"" + screenHash + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missingDataSources[0].datasources").value("invoices"));

        // Once the data sources are approved and published there, the screen is published too.
        String sourcesHash = save(sources, document, "mario");
        review(sources, sourcesHash, "request", "mario", 200);
        review(sources, sourcesHash, "approve", "paola", 200);
        publish(sources, "production", sourcesHash, 204);
        publish(BASE, "production", screenHash, 204);
        mvc.perform(get(sources + "/published").param("env", "production").with(user("mario")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.list.url").value("/api/invoices"));
    }
}
