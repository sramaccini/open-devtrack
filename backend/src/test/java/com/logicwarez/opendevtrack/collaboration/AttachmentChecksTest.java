/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import static com.logicwarez.opendevtrack.collaboration.AttachmentTestBase.ORIGIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.opendevtrack.TestInfrastructure;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The checks of an attachment before it is stored (ADR-24 point 8, ASVS 12.1.3, 12.2.1, 12.4.2): the quota of the
 * project, the type confirmed by the content, and the antivirus, here a small clamd that speaks {@code INSTREAM} and
 * finds anything that holds {@code EICAR}, or answers nothing when it is down.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class AttachmentChecksTest {

    private static final AtomicBoolean DOWN = new AtomicBoolean();
    private static final ServerSocket CLAMD = clamd();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("odt.attachments.project-quota", () -> "1KB");
        registry.add("odt.attachments.scan.clamd.host", () -> "127.0.0.1");
        registry.add("odt.attachments.scan.clamd.port", CLAMD::getLocalPort);
        registry.add("odt.attachments.scan.clamd.timeout", () -> "5s");
    }

    @AfterAll
    static void stop() throws IOException {
        CLAMD.close();
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry metrics;

    MockMvc mvc;

    private final SessionAuthentication mario = AttachmentTestBase.session("mario", "ALPHA", AttachmentTestBase.CREATOR);
    private final SessionAuthentication luisa = AttachmentTestBase.session("luisa", "ALPHA", AttachmentTestBase.MEMBER);

    @BeforeEach
    void setUp() throws Exception {
        DOWN.set(false);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change, "
                + "odt_collaboration.attachment, odt_collaboration.attachment_content CASCADE");
        mvc.perform(json(post("/api/projects"), mario, "{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/projects/ODT/members"), mario, "{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/projects/ODT/issues"), luisa, "{\"summary\":\"Login fails\",\"type\":\"bug\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void theDeclaredTypeIsKeptOnlyWhenTheContentConfirmsIt() throws Exception {
        byte[] png = HexFormat.of().parseHex("89504e470d0a1a0a0000000d49484452");
        upload("shot.png", "image/png", png).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("image/png"));
        upload("notes.txt", "text/plain; charset=UTF-8", "line\n".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mediaType").value("text/plain;charset=UTF-8"));
        // A script that says it is a picture, a binary that says it is a text, a type the table does not know
        upload("photo.jpg", "image/jpeg", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mediaType").value("application/octet-stream"));
        upload("a.txt", "text/plain", new byte[] {'M', 'Z', 0, 0}).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("application/octet-stream"));
        upload("logo.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("application/octet-stream"));
    }

    @Test
    void theQuotaOfTheProjectIsKept() throws Exception {
        upload("a.bin", "application/octet-stream", new byte[600]).andExpect(status().isCreated());
        upload("b.bin", "application/octet-stream", new byte[600]).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.quota").value(1024));
        upload("c.bin", "application/octet-stream", new byte[400]).andExpect(status().isCreated());
    }

    @Test
    void anInfectedFileIsRefusedAndNotStored() throws Exception {
        upload("eicar.com", "application/octet-stream", "X5O!P%@AP EICAR test".getBytes(StandardCharsets.US_ASCII))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_collaboration.attachment", Integer.class)).isZero();
        assertThat(metrics.counter("odt.attachments.refused", "reason", "infected").count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void withoutTheAntivirusTheFileIsRefused() throws Exception {
        DOWN.set(true);
        upload("a.txt", "text/plain", "fine".getBytes(StandardCharsets.UTF_8)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_collaboration.attachment", Integer.class)).isZero();
    }

    @Test
    void anErrorOfClamdIsNoVerdict() {
        assertThat(catchThrowable(
                () -> ClamdScanner.verdict("INSTREAM size limit exceeded. ERROR"))).isInstanceOf(IOException.class);
    }

    private ResultActions upload(String name, String type, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/issues/ODT-1/attachments").file(new MockMultipartFile("file", name, type, content))
                .with(authentication(luisa)).with(csrf()).header("Origin", ORIGIN));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, SessionAuthentication who,
            String body) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** A clamd of the test on a free port: reads the INSTREAM chunks and answers as clamd does. */
    private static ServerSocket clamd() {
        try {
            ServerSocket server = new ServerSocket(0);
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        if (DOWN.get()) {
                            continue;
                        }
                        DataInputStream in = new DataInputStream(client.getInputStream());
                        in.readNBytes("zINSTREAM\0".length());
                        ByteArrayOutputStream content = new ByteArrayOutputStream();
                        for (int length = in.readInt(); length > 0; length = in.readInt()) {
                            content.write(in.readNBytes(length));
                        }
                        String answer = content.toString(StandardCharsets.US_ASCII).contains("EICAR")
                                ? "stream: Eicar-Test-Signature FOUND\0" : "stream: OK\0";
                        client.getOutputStream().write(answer.getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException e) {
                        // closed at the end, or a client gone
                    }
                }
            });
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
