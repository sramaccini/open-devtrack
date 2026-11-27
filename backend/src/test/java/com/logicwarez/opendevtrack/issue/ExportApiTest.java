/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.TestInfrastructure;
import jakarta.servlet.Filter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The export of an issue (users in {@code project/users.sql}): mario is the admin of ODT, luisa a developer, paola a
 * viewer; ugo is in another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class ExportApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    private static final List<String> MEMBER = List.of("odt.project.browse", "odt.user.browse");
    private static final List<String> CREATOR = List.of("odt.project.browse", "odt.user.browse", "odt.project.create");

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    private final SessionAuthentication mario = session("mario", "ALPHA", CREATOR);
    private final SessionAuthentication luisa = session("luisa", "ALPHA", MEMBER);
    private final SessionAuthentication paola = session("paola", "ALPHA", MEMBER);
    private final SessionAuthentication ugo = session("ugo", "BETA", CREATOR);

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue CASCADE");
        jdbc.execute("TRUNCATE odt_collaboration.comment, odt_audit.issue_change CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("""
                        {"type":"bug","summary":"Login fails & <stops>","description":"First line\\nsame paragraph\\n\\nSecond   one",
                         "priority":"high","assignee":"luisa","labels":["auth"]}"""))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Session store\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/issues/ODT-1/links"), luisa).content("{\"relation\":\"blocks\",\"issue\":\"ODT-2\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("{\"body\":\"Seen with **Chrome**\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(patch("/api/issues/ODT-1"), luisa).content("{\"priority\":\"highest\",\"version\":0}"))
                .andExpect(status().isOk());
        TestInfrastructure.awaitListeners(jdbc);
    }

    @Test
    void theJsonHasTheWholeIssue() throws Exception {
        mvc.perform(as(get("/api/issues/ODT-1/export"), paola))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ODT-1.json")))
                .andExpect(jsonPath("$.schema").value(1))
                .andExpect(jsonPath("$.exportedBy").value("paola"))
                .andExpect(jsonPath("$.issue.key").value("ODT-1"))
                .andExpect(jsonPath("$.issue.priority").value("highest"))
                .andExpect(jsonPath("$.issue.labels[0]").value("auth"))
                .andExpect(jsonPath("$.links[0].relation").value("blocks"))
                .andExpect(jsonPath("$.links[0].key").value("ODT-2"))
                .andExpect(jsonPath("$.watchers[0]").value("luisa"))
                .andExpect(jsonPath("$.votes").value(0))
                .andExpect(jsonPath("$.comments[0].author").value("luisa"))
                .andExpect(jsonPath("$.comments[0].body").value("Seen with **Chrome**"))
                .andExpect(jsonPath("$.attachments.length()").value(0))
                .andExpect(jsonPath("$.history[?(@.field == 'priority')].to").value(org.hamcrest.Matchers.hasItem("highest")));
    }

    @Test
    void aWordDocumentWithTheFieldsAndTheComments() throws Exception {
        byte[] file = mvc.perform(as(get("/api/issues/ODT-1/export?format=docx"), luisa))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", IssueDocuments.DOCX))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ODT-1.docx")))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, Entry> parts = unzip(file);
        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "word/document.xml");
        parts.values().forEach(ExportApiTest::wellFormed);
        String document = parts.get("word/document.xml").text();
        assertThat(document).contains("ODT-1 Login fails &amp; &lt;stops&gt;", "Highest", "Seen with **Chrome**",
                "First line</w:t><w:br/><w:t xml:space=\"preserve\">same paragraph", "blocks", "ODT-2 Session store",
                "Exported from OpenDevTrack");
    }

    @Test
    void anOpenDocumentInTheLanguageAsked() throws Exception {
        byte[] file = mvc.perform(as(get("/api/issues/ODT-1/export?format=odt"), luisa).header("Accept-Language", "it"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", IssueDocuments.ODT))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, Entry> parts = unzip(file);
        // OpenDocument: the type first, not compressed.
        Entry first = parts.values().iterator().next();
        assertThat(first.name()).isEqualTo("mimetype");
        assertThat(first.method()).isEqualTo(ZipEntry.STORED);
        assertThat(first.text()).isEqualTo(IssueDocuments.ODT);
        assertThat(parts).containsKeys("META-INF/manifest.xml", "content.xml", "meta.xml");
        parts.values().stream().filter(p -> p.name().endsWith(".xml")).forEach(ExportApiTest::wellFormed);
        String content = parts.get("content.xml").text();
        assertThat(content).contains("Commenti", "Priorità", "Massima", "blocca", "Seen with **Chrome**",
                "Second <text:s text:c=\"2\"/>one", "<text:line-break/>", "Esportata da OpenDevTrack");
    }

    @Test
    void onlyWhoSeesTheIssueExportsIt() throws Exception {
        mvc.perform(as(get("/api/issues/ODT-1/export?format=pdf"), luisa)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/issues/ODT-1/export"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-9/export"), luisa)).andExpect(status().isNotFound());
    }

    private record Entry(String name, int method, byte[] bytes) {
        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Entry> unzip(byte[] file) throws IOException {
        Map<String, Entry> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file), StandardCharsets.UTF_8)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                parts.put(e.getName(), new Entry(e.getName(), e.getMethod(), zip.readAllBytes()));
            }
        }
        return parts;
    }

    private static void wellFormed(Entry part) {
        if (!part.name().endsWith(".xml") && !part.name().endsWith(".rels")) {
            return;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(part.bytes()));
        } catch (Exception e) {
            throw new AssertionError(part.name() + " is not well formed: " + e.getMessage(), e);
        }
    }
}
