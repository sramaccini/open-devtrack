/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.TestInfrastructure;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The email channel of ADR-18, with a mail server set and its sender replaced: the preferences of each user, the mails
 * sent at once and the daily digest, the retries. The outbox is run by hand at chosen times; its schedule is far away.
 * Users of {@code project/users.sql}: mario admin of ODT, luisa developer and reporter of ODT-1, paola viewer.
 */
@SpringBootTest(properties = {"spring.mail.host=mail.invalid", "odt.mail.outbox-delay=PT24H",
        "odt.public-url=https://odt.example.org/", "odt.mail.digest-at=07:00", "odt.reporting.zone=UTC"})
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class NotificationMailTest {

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

    @Autowired
    MailOutbox outbox;

    @MockitoBean
    JavaMailSender sender;

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

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change, "
                + "odt_collaboration.comment, odt_notification.message, odt_notification.preference CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Login fails\",\"type\":\"bug\"}"))
                .andExpect(status().isCreated());
    }

    /** The listeners run after the commit, on another thread: wait until the expected rows are there. */
    private static void await(IntSupplier count, int expected) throws InterruptedException {
        for (int i = 0; i < 100 && count.getAsInt() != expected; i++) {
            Thread.sleep(50);
        }
        assertThat(count.getAsInt()).isEqualTo(expected);
    }

    private int messages() {
        return jdbc.queryForObject("SELECT count(*) FROM odt_notification.message", Integer.class);
    }

    private List<SimpleMailMessage> sent(int expected) {
        ArgumentCaptor<SimpleMailMessage> mails = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(expected)).send(mails.capture());
        return mails.getAllValues();
    }

    @Test
    void mentionsAreMailedAtOnceInTheLanguageOfTheUser() throws Exception {
        // mario last used the application in Italian: his mails are in Italian.
        mvc.perform(as(get("/api/notifications"), mario).header("Accept-Language", "it")).andExpect(status().isOk());
        mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("{\"body\":\"@mario look\"}"))
                .andExpect(status().isCreated());
        await(this::messages, 1);

        assertThat(outbox.send(Instant.now())).isEqualTo(1);
        SimpleMailMessage mail = sent(1).getFirst();
        assertThat(mail.getTo()).containsExactly("mario@example.org");
        assertThat(mail.getFrom()).isEqualTo("OpenDevTrack <noreply@localhost>");
        assertThat(mail.getSubject()).isEqualTo("[OpenDevTrack] ODT-1 Login fails");
        assertThat(mail.getText())
                .contains("luisa ti ha menzionato in ODT-1")
                .contains("@mario look")
                .contains("https://odt.example.org/projects/issue-detail?key=ODT&issue=ODT-1")
                .contains("impostazioni delle notifiche: https://odt.example.org/?settings=notification-settings\n");
        // Sent once: the message stays in the bell, its mail is done.
        assertThat(outbox.send(Instant.now().plusSeconds(3600))).isZero();
        mvc.perform(as(get("/api/notifications"), mario)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void commentsAndStatusChangesWaitForTheDailyDigest() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/transitions"), mario).content("{\"transition\":\"start\",\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/issues/ODT-1/comments"), mario).content("{\"body\":\"On it\"}"))
                .andExpect(status().isCreated());
        await(this::messages, 2);
        Instant due = jdbc.queryForObject("SELECT min(email_due_at) FROM odt_notification.message", Instant.class);
        assertThat(due.atZone(java.time.ZoneOffset.UTC).getHour()).isEqualTo(7);
        assertThat(due).isAfter(Instant.now()).isBefore(Instant.now().plus(Duration.ofDays(1)));

        // Nothing before the digest, then one mail with both.
        assertThat(outbox.send(Instant.now())).isZero();
        verify(sender, never()).send(any(SimpleMailMessage.class));
        assertThat(outbox.send(due)).isEqualTo(1);
        SimpleMailMessage digest = sent(1).getFirst();
        assertThat(digest.getTo()).containsExactly("luisa@example.org");
        assertThat(digest.getSubject()).isEqualTo("[OpenDevTrack] Your daily summary: 2 updates");
        assertThat(digest.getText())
                .contains("ODT-1 Login fails")
                .contains("- mario moved ODT-1 to In progress")
                .contains("- mario commented on ODT-1")
                .contains("  On it");

        // A message read in the application before its digest is not mailed.
        mvc.perform(as(post("/api/issues/ODT-1/comments"), mario).content("{\"body\":\"Done\"}"))
                .andExpect(status().isCreated());
        await(this::messages, 3);
        String list = mvc.perform(as(get("/api/notifications"), luisa)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(list, "$[0].id");
        mvc.perform(as(post("/api/notifications/read"), luisa).content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isNoContent());
        assertThat(outbox.send(due.plus(Duration.ofDays(1)))).isZero();
        sent(1);
    }

    @Test
    void preferencesChooseTheChannels() throws Exception {
        mvc.perform(as(get("/api/notifications/preferences"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("luisa@example.org"))
                .andExpect(jsonPath("$.mailEnabled").value(true))
                .andExpect(jsonPath("$.channels.mentionedApp").value(true))
                .andExpect(jsonPath("$.channels.mentionedEmail").value("instant"))
                .andExpect(jsonPath("$.channels.commentedEmail").value("daily"));
        mvc.perform(as(get("/api/notifications/preferences/fields"), luisa).header("Accept-Language", "it"))
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].key").value("mentionedApp"))
                .andExpect(jsonPath("$[0].type").value("switch"))
                .andExpect(jsonPath("$[1].key").value("mentionedEmail"))
                .andExpect(jsonPath("$[1].options.length()").value(3));
        mvc.perform(as(put("/api/notifications/preferences"), luisa).content("{\"channels\":{\"mentionedEmail\":\"weekly\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(put("/api/notifications/preferences"), luisa).content("{\"channels\":{\"mentionedApp\":\"yes\"}}"))
                .andExpect(status().isBadRequest());

        // Mentions by email only: the message is not in the bell, the mail is sent.
        mvc.perform(as(put("/api/notifications/preferences"), luisa).content("""
                        {"channels":{"mentionedApp":false,"commentedApp":false,"commentedEmail":"off"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.mentionedApp").value(false))
                .andExpect(jsonPath("$.channels.mentionedEmail").value("instant"))
                .andExpect(jsonPath("$.channels.commentedEmail").value("off"));
        mvc.perform(as(post("/api/issues/ODT-1/comments"), mario).content("{\"body\":\"@luisa please check\"}"))
                .andExpect(status().isCreated());
        await(this::messages, 1);
        mvc.perform(as(get("/api/notifications"), luisa)).andExpect(jsonPath("$.length()").value(0));
        assertThat(outbox.send(Instant.now())).isEqualTo(1);
        assertThat(sent(1).getFirst().getText()).contains("mario mentioned you in ODT-1");

        // Comments neither in the bell nor by email: no message at all.
        mvc.perform(as(post("/api/issues/ODT-1/comments"), mario).content("{\"body\":\"No mention\"}"))
                .andExpect(status().isCreated());
        Thread.sleep(300);
        assertThat(messages()).isEqualTo(1);
    }

    @Test
    void aFailedMailIsTriedAgainThenGivenUp() throws Exception {
        doThrow(new MailSendException("connection refused")).when(sender).send(any(SimpleMailMessage.class));
        mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("{\"body\":\"@mario look\"}"))
                .andExpect(status().isCreated());
        await(this::messages, 1);

        Instant at = Instant.now();
        assertThat(outbox.send(at)).isZero();
        // Not again at once: the next attempt waits.
        assertThat(outbox.send(at.plusSeconds(10))).isZero();
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
        for (int attempt = 2; attempt <= MailOutbox.MAX_ATTEMPTS; attempt++) {
            at = at.plus(Duration.ofHours(2));
            outbox.send(at);
        }
        verify(sender, times(MailOutbox.MAX_ATTEMPTS)).send(any(SimpleMailMessage.class));
        assertThat(jdbc.queryForObject("SELECT email_attempts FROM odt_notification.message", Integer.class))
                .isEqualTo(MailOutbox.MAX_ATTEMPTS);
        assertThat(jdbc.queryForObject("SELECT email_due_at IS NULL FROM odt_notification.message", Boolean.class)).isTrue();
        assertThat(outbox.send(at.plus(Duration.ofDays(30)))).isZero();
    }
}
