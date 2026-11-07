/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.identity.TenantUser;
import com.logicwarez.opendevtrack.identity.TenantUsers;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Limit;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the mails of the outbox (ADR-18): each instant message alone, the daily ones of a user in one digest. A mail
 * that fails is tried again after a growing delay, at most {@link #MAX_ATTEMPTS} times, then given up and logged. Each
 * mail is sent in a transaction that holds its messages, so two instances never send the same one.
 */
@Component
class MailOutbox {

    static final int MAX_ATTEMPTS = 5;
    static final int BATCH = 500;

    private static final Logger log = LoggerFactory.getLogger(MailOutbox.class);

    private final MessageRepository messages;
    private final MessageTexts texts;
    private final Preferences preferences;
    private final MailSettings settings;
    private final ObjectProvider<JavaMailSender> sender;
    private final Projects projects;
    private final ProjectAccess access;
    private final TenantUsers users;
    private final Issues issues;
    private final TransactionTemplate transactions;

    MailOutbox(MessageRepository messages, MessageTexts texts, Preferences preferences, MailSettings settings,
               ObjectProvider<JavaMailSender> sender, Projects projects, ProjectAccess access, TenantUsers users,
               Issues issues, TransactionTemplate transactions) {
        this.messages = messages;
        this.texts = texts;
        this.preferences = preferences;
        this.settings = settings;
        this.sender = sender;
        this.projects = projects;
        this.access = access;
        this.users = users;
        this.issues = issues;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${odt.mail.outbox-delay:PT1M}")
    void scheduled() {
        if (settings.enabled() && sender.getIfAvailable() != null) {
            send(Instant.now());
        }
    }

    /** Sends what is due at {@code now}; the number of mails sent. */
    int send(Instant now) {
        // One unit per mail: an instant message alone, the daily messages of a recipient together.
        Map<String, List<UUID>> units = new LinkedHashMap<>();
        for (MessageEntity m : messages.due(now, Limit.of(BATCH))) {
            String unit = Preferences.DAILY.equals(m.emailMode()) ? "daily:" + m.projectId() + ":" + m.recipient()
                    : "instant:" + m.id();
            units.computeIfAbsent(unit, u -> new ArrayList<>()).add(m.id());
        }
        // A digest covers all the projects of a tenant: units of the same recipient and tenant are merged.
        Map<String, List<UUID>> mails = new LinkedHashMap<>();
        units.forEach((unit, ids) -> {
            String key = unit;
            if (unit.startsWith("daily:")) {
                String[] parts = unit.split(":", 3);
                key = "daily:" + projects.byId(UUID.fromString(parts[1])).map(ProjectRef::tenantId).orElse("") + ":" + parts[2];
            }
            mails.computeIfAbsent(key, k -> new ArrayList<>()).addAll(ids);
        });
        int sent = 0;
        for (List<UUID> ids : mails.values()) {
            Boolean done = transactions.execute(status -> mail(ids, now));
            if (Boolean.TRUE.equals(done)) {
                sent++;
            }
        }
        return sent;
    }

    /** One mail of the given messages, those still due and not held by another sender. */
    private boolean mail(List<UUID> ids, Instant now) {
        List<MessageEntity> held = messages.findAllById(messages.claim(ids));
        List<MessageEntity> due = new ArrayList<>();
        String tenant = null;
        for (MessageEntity m : held) {
            Optional<ProjectRef> project = projects.byId(m.projectId());
            boolean stillMember = project.isPresent()
                    && !access.roles(List.of(m.projectId()), m.recipient()).isEmpty();
            // A message read in the application before its digest is left out of it.
            boolean readBeforeDigest = Preferences.DAILY.equals(m.emailMode()) && m.readAt() != null;
            if (!stillMember || readBeforeDigest) {
                m.unmail();
            } else {
                due.add(m);
                tenant = project.get().tenantId();
            }
        }
        if (due.isEmpty()) {
            return false;
        }
        String recipient = due.getFirst().recipient();
        Optional<String> address = users.find(tenant, recipient).map(TenantUser::email).filter(e -> !e.isBlank());
        if (address.isEmpty()) {
            log.warn("No mail for {}: the user has no email address in tenant {}", recipient, tenant);
            due.forEach(m -> m.failed(now, 1));
            return false;
        }
        Locale locale = Locale.forLanguageTag(preferences.language(tenant, recipient));
        SimpleMailMessage mail = Preferences.DAILY.equals(due.getFirst().emailMode())
                ? digest(due, locale) : instant(due.getFirst(), locale);
        mail.setFrom(settings.from());
        mail.setTo(address.get());
        try {
            sender.getObject().send(mail);
        } catch (MailException e) {
            due.forEach(m -> m.failed(now, MAX_ATTEMPTS));
            if (due.getFirst().emailAttempts() >= MAX_ATTEMPTS) {
                log.warn("Mail to {} given up after {} attempts: {}", recipient, MAX_ATTEMPTS, e.getMessage());
            } else {
                log.info("Mail to {} failed, tried again later: {}", recipient, e.getMessage());
            }
            return false;
        }
        due.forEach(m -> m.emailed(now));
        return true;
    }

    private SimpleMailMessage instant(MessageEntity m, Locale locale) {
        String projectKey = projectKey(m);
        String summary = issues.summary(m.projectId(), m.issueKey()).orElse("");
        MessageTexts.Text text = texts.of(m, locale);
        StringBuilder body = new StringBuilder(text.title()).append("\n");
        if (text.text() != null && !text.text().isBlank()) {
            body.append("\n").append(text.text()).append("\n");
        }
        body.append("\n").append(texts.mail("open", locale, m.issueKey(), summary, settings.issueUrl(projectKey, m.issueKey())))
                .append("\n");
        footer(body, locale);
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setSubject(texts.mail("subject", locale, m.issueKey(), summary, "", 0));
        mail.setText(body.toString());
        return mail;
    }

    /** The messages grouped by issue, in the order they came. */
    private SimpleMailMessage digest(List<MessageEntity> due, Locale locale) {
        Map<String, List<MessageEntity>> byIssue = new LinkedHashMap<>();
        due.forEach(m -> byIssue.computeIfAbsent(m.projectId() + ":" + m.issueKey(), k -> new ArrayList<>()).add(m));
        StringBuilder body = new StringBuilder(texts.mail("digest.intro", locale)).append("\n");
        for (List<MessageEntity> ofIssue : byIssue.values()) {
            MessageEntity first = ofIssue.getFirst();
            String summary = issues.summary(first.projectId(), first.issueKey()).orElse("");
            body.append("\n").append(first.issueKey()).append(" ").append(summary).append("\n");
            for (MessageEntity m : ofIssue) {
                MessageTexts.Text text = texts.of(m, locale);
                body.append("- ").append(text.title()).append("\n");
                if (text.text() != null && !text.text().isBlank()) {
                    body.append("  ").append(text.text().replace("\n", "\n  ")).append("\n");
                }
            }
            body.append(texts.mail("open", locale, first.issueKey(), summary,
                    settings.issueUrl(projectKey(first), first.issueKey()))).append("\n");
        }
        footer(body, locale);
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setSubject(texts.mail("digest.subject", locale, "", "", "", due.size()));
        mail.setText(body.toString());
        return mail;
    }

    private void footer(StringBuilder body, Locale locale) {
        body.append("\n-- \n").append(texts.mail("footer", locale, "", "", settings.settingsUrl(), 0)).append("\n");
    }

    private String projectKey(MessageEntity m) {
        return projects.byId(m.projectId()).map(ProjectRef::key).orElse("");
    }
}
