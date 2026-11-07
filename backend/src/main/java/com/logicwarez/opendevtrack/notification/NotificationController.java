/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The messages of the user for the bell of the Quadrava shell (ADR-13, Quadrava ADR-22): the last ones in the tenant
 * of the session, of the issues the user still sees, each opening its issue; and marking them read.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class NotificationController {

    static final int MAX_LIST = 50;
    static final int MAX_READ = 200;

    private final MessageRepository messages;
    private final MessageTexts texts;
    private final Projects projects;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Preferences preferences;

    NotificationController(MessageRepository messages, MessageTexts texts, Projects projects, ProjectAccess access,
                           CurrentUser user, Preferences preferences) {
        this.preferences = preferences;
        this.messages = messages;
        this.texts = texts;
        this.projects = projects;
        this.access = access;
        this.user = user;
    }

    /** The shape of a message of Quadrava ADR-22. */
    record MessageView(UUID id, String title, String text, Instant date, boolean read, String screen,
                       Map<String, String> params) {
    }

    record ReadRequest(List<UUID> ids) {
    }

    /** The messages of the bell; the language of the request is remembered for the mails of the user (ADR-18). */
    @GetMapping("/api/notifications")
    @Transactional
    List<MessageView> list(Locale locale) {
        preferences.remember(user.tenantId(), user.username(), locale);
        Map<UUID, Optional<ProjectRef>> visible = new HashMap<>();
        return messages.findByRecipientAndShownTrueOrderByCreatedAtDescIdDesc(user.username(), Limit.of(MAX_LIST)).stream()
                .filter(m -> visible.computeIfAbsent(m.projectId(), this::visibleProject).isPresent())
                .map(m -> view(m, visible.get(m.projectId()).get(), locale))
                .toList();
    }

    @PostMapping("/api/notifications/read")
    @Transactional
    ResponseEntity<?> read(@RequestBody ReadRequest request) {
        if (request.ids() == null || request.ids().isEmpty() || request.ids().size() > MAX_READ) {
            return Problems.badRequest("Between 1 and " + MAX_READ + " messages at a time");
        }
        messages.markRead(user.username(), request.ids(), Instant.now());
        return ResponseEntity.noContent().build();
    }

    /** The project in the tenant of the session, when the user still sees its issues. */
    private Optional<ProjectRef> visibleProject(UUID projectId) {
        return projects.get(projectId)
                .filter(p -> access.permissions(p.id(), user.username()).contains("odt.issue.browse"));
    }

    private MessageView view(MessageEntity m, ProjectRef project, Locale locale) {
        MessageTexts.Text text = texts.of(m, locale);
        return new MessageView(m.id(), text.title(), text.text(), m.createdAt(), m.readAt() != null, "issue-detail",
                Map.of("key", project.key(), "issue", m.issueKey()));
    }
}
