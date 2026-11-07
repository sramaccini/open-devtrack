/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import com.logicwarez.opendevtrack.collaboration.CommentEvents.CommentPosted;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import com.logicwarez.opendevtrack.issue.Watchers;
import com.logicwarez.opendevtrack.notification.Preferences.Preference;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Writes the messages of ADR-13 from the events of issue and collaboration, after their commit and in a transaction of
 * their own: mentions, assignments, and comments and status changes of the issues a user watches. A user gets at most
 * one message per change, the most direct one, and never for what they did themselves.
 */
@Component
class Messages {

    private final MessageRepository messages;
    private final Watchers watchers;
    private final Projects projects;
    private final Preferences preferences;
    private final MailSettings mail;

    Messages(MessageRepository messages, Watchers watchers, Projects projects, Preferences preferences, MailSettings mail) {
        this.messages = messages;
        this.watchers = watchers;
        this.projects = projects;
        this.preferences = preferences;
        this.mail = mail;
    }

    @ApplicationModuleListener
    void on(IssueCreated event) {
        Recipients to = new Recipients(event.by());
        changed(event.fields(), "assignee").ifPresent(a -> to.add(a.to(), MessageKind.ASSIGNED, Map.of()));
        save(to, event.projectId(), event.key(), event.at());
    }

    @ApplicationModuleListener
    void on(IssueChanged event) {
        Recipients to = new Recipients(event.by());
        changed(event.changes(), "assignee").ifPresent(a -> to.add(a.to(), MessageKind.ASSIGNED, Map.of()));
        changed(event.changes(), "status").ifPresent(s -> {
            Map<String, String> params = values("from", s.from(), "to", s.to());
            watchers.of(event.issueId()).forEach(w -> to.add(w, MessageKind.TRANSITIONED, params));
        });
        save(to, event.projectId(), event.key(), event.at());
    }

    @ApplicationModuleListener
    void on(CommentPosted event) {
        Recipients to = new Recipients(event.by());
        Map<String, String> params = values("excerpt", event.excerpt(), null, null);
        event.mentioned().forEach(m -> to.add(m, MessageKind.MENTIONED, params));
        if (!event.edited()) {
            watchers.of(event.issueId()).forEach(w -> to.add(w, MessageKind.COMMENTED, params));
        }
        save(to, event.projectId(), event.issueKey(), event.at());
    }

    @ApplicationModuleListener
    void on(IssueDeleted event) {
        messages.deleteByIssue(event.projectId(), event.key());
    }

    /**
     * One message per recipient, as their preferences say (ADR-18): none when they want it neither in the application
     * nor by email; hidden from the bell when they want only the mail; in the outbox when email is on for the kind.
     */
    private void save(Recipients to, UUID projectId, String issueKey, Instant at) {
        Optional<String> tenant = projects.byId(projectId).map(ProjectRef::tenantId);
        if (tenant.isEmpty()) {
            return;
        }
        List<MessageEntity> saved = new ArrayList<>();
        to.byUser.forEach((username, message) -> {
            Preference wanted = preferences.of(tenant.get(), username).get(message.kind());
            String email = mail.enabled() ? wanted.email() : Preferences.OFF;
            if (!wanted.inApp() && email.equals(Preferences.OFF)) {
                return;
            }
            MessageEntity m = new MessageEntity(projectId, username, message.kind(), issueKey, to.actor, message.params(), at);
            if (!wanted.inApp()) {
                m.hide();
            }
            if (!email.equals(Preferences.OFF)) {
                m.mail(email, mail.due(email, at));
            }
            saved.add(m);
        });
        messages.saveAll(saved);
    }

    private static Optional<FieldChange> changed(List<FieldChange> changes, String field) {
        return changes.stream().filter(c -> c.field().equals(field)).findFirst();
    }

    private static Map<String, String> values(String k1, String v1, String k2, String v2) {
        Map<String, String> values = new LinkedHashMap<>();
        if (v1 != null) {
            values.put(k1, v1);
        }
        if (k2 != null && v2 != null) {
            values.put(k2, v2);
        }
        return values;
    }

    private record Message(MessageKind kind, Map<String, String> params) {
    }

    /** Who gets which message for one change: the first, most direct kind wins; never the actor. */
    private static final class Recipients {
        private final String actor;
        private final Map<String, Message> byUser = new LinkedHashMap<>();

        Recipients(String actor) {
            this.actor = actor;
        }

        void add(String username, MessageKind kind, Map<String, String> params) {
            if (username == null || username.equals(actor)) {
                return;
            }
            byUser.merge(username, new Message(kind, params), (a, b) -> a.kind().ordinal() <= b.kind().ordinal() ? a : b);
        }
    }
}
