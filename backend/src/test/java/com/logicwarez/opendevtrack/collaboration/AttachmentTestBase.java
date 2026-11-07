/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.TestInfrastructure;
import com.logicwarez.opendevtrack.collaboration.storage.AttachmentStore;
import jakarta.servlet.Filter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * What every store of the attachments must do through the API (ADR-14): keep the bytes, give them back, remove them
 * after the commit and with their issue, and let the sweep remove the bytes without metadata. Users of
 * {@code project/users.sql}: mario admin of ODT, luisa developer, paola viewer.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
abstract class AttachmentTestBase {

    static final String ORIGIN = "http://localhost:8090";
    static final List<String> MEMBER = List.of("odt.project.browse", "odt.user.browse");
    static final List<String> CREATOR = List.of("odt.project.browse", "odt.user.browse", "odt.project.create");

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AttachmentStore store;

    @Autowired
    AttachmentBytes bytes;

    MockMvc mvc;

    static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    static <T extends AbstractMockHttpServletRequestBuilder<?>> T as(T request, SessionAuthentication who) {
        request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN);
        return request;
    }

    final SessionAuthentication mario = session("mario", "ALPHA", CREATOR);
    final SessionAuthentication luisa = session("luisa", "ALPHA", MEMBER);
    final SessionAuthentication paola = session("paola", "ALPHA", MEMBER);
    final SessionAuthentication ugo = session("ugo", "BETA", CREATOR);

    /** The name of the store the subclass runs on. */
    abstract String expectedStore();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change, "
                + "odt_collaboration.comment, odt_collaboration.attachment, odt_collaboration.attachment_content CASCADE");
        mvc.perform(as(post("/api/projects"), mario).contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}")).andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"luisa\",\"role\":\"developer\"}")).andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"paola\",\"role\":\"viewer\"}")).andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"Login fails\",\"type\":\"bug\"}")).andExpect(status().isCreated());
    }

    /** Uploads a file to ODT-1 as that user and gives back the identifier of the attachment. */
    String upload(SessionAuthentication who, String filename, String mediaType, byte[] content) throws Exception {
        String created = mvc.perform(as(multipart("/api/issues/ODT-1/attachments")
                        .file(new MockMultipartFile("file", filename, mediaType, content)), who))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    String key(String attachmentId) {
        UUID project = jdbc.queryForObject("SELECT project_id FROM odt_collaboration.attachment WHERE id = ?::uuid",
                UUID.class, attachmentId);
        return project + "/" + attachmentId;
    }

    boolean kept(String key) {
        try (InputStream in = store.get(key)) {
            return in != null;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The listeners and the removals after the commit may run on another thread: wait for the condition. */
    static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void theStoreKeepsGivesBackAndRemovesTheBytes() throws Exception {
        assertThat(store.name()).isEqualTo(expectedStore());
        byte[] content = "Steps to reproduce: log in twice.\n".getBytes(StandardCharsets.UTF_8);
        String id = upload(luisa, "steps.txt", "text/plain", content);
        String key = key(id);
        assertThat(kept(key)).isTrue();
        assertThat(jdbc.queryForObject("SELECT store FROM odt_collaboration.attachment WHERE id = ?::uuid", String.class, id))
                .isEqualTo(expectedStore());

        mvc.perform(as(get("/api/issues/ODT-1/attachments/" + id + "/content"), paola))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content));

        mvc.perform(as(delete("/api/issues/ODT-1/attachments/" + id), luisa)).andExpect(status().isNoContent());
        await(() -> !kept(key));
    }

    @Test
    void theBytesGoWithTheirIssue() throws Exception {
        String key = key(upload(luisa, "trace.log", "text/plain", "at Login.check".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(as(delete("/api/issues/ODT-1"), mario)).andExpect(status().isNoContent());
        await(() -> jdbc.queryForObject("SELECT count(*) FROM odt_collaboration.attachment", Integer.class) == 0);
        await(() -> !kept(key));
    }

    @Test
    void theSweepRemovesOldBytesWithoutMetadata() throws Exception {
        String kept = key(upload(luisa, "kept.txt", "text/plain", "kept".getBytes(StandardCharsets.UTF_8)));
        String orphan = UUID.randomUUID() + "/" + UUID.randomUUID();
        byte[] left = "left".getBytes(StandardCharsets.UTF_8);
        store.put(orphan, new ByteArrayInputStream(left), left.length);

        // As if a day had passed: both are old enough, only the one without metadata goes.
        bytes.sweep(Instant.now().plusSeconds(60));

        assertThat(kept(orphan)).isFalse();
        assertThat(kept(kept)).isTrue();
    }
}
