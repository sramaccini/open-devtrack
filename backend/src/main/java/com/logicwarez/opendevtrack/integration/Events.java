/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import com.logicwarez.opendevtrack.agile.SprintEvents.SprintCompleted;
import com.logicwarez.opendevtrack.agile.SprintEvents.SprintStarted;
import com.logicwarez.opendevtrack.collaboration.AttachmentEvents.AttachmentAdded;
import com.logicwarez.opendevtrack.collaboration.AttachmentEvents.AttachmentRemoved;
import com.logicwarez.opendevtrack.collaboration.CommentEvents.CommentPosted;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueChanged;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueCreated;
import com.logicwarez.opendevtrack.issue.IssueEvents.IssueDeleted;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The events a webhook may want (ADR-21, B8) and their payload (B9): from the events of the other modules, after their
 * commit, one delivery in the outbox for each active webhook of the project that wants it.
 *
 * <pre>{"id", "type", "time", "tenant", "project", "actor", "data": {"key", "url", …}}</pre>
 *
 * The payload is small and stable: no copy of the resource (the receiver reads it through the API), never the text of
 * a comment.
 */
@Component
class Events {

    static final String ALL = "*";
    static final String ISSUE_CREATED = "issue.created";
    static final String ISSUE_CHANGED = "issue.changed";
    static final String ISSUE_DELETED = "issue.deleted";
    static final String COMMENT_POSTED = "comment.posted";
    static final String ATTACHMENT_ADDED = "attachment.added";
    static final String ATTACHMENT_REMOVED = "attachment.removed";
    static final String SPRINT_STARTED = "sprint.started";
    static final String SPRINT_COMPLETED = "sprint.completed";
    /** «Send a test»: sent to that webhook only, whatever it wants. */
    static final String PING = "ping";

    static final List<String> TYPES = List.of(ISSUE_CREATED, ISSUE_CHANGED, ISSUE_DELETED, COMMENT_POSTED,
            ATTACHMENT_ADDED, ATTACHMENT_REMOVED, SPRINT_STARTED, SPRINT_COMPLETED);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebhookRepository webhooks;
    private final DeliveryRepository deliveries;
    private final Projects projects;
    private final String api;

    Events(WebhookRepository webhooks, DeliveryRepository deliveries, Projects projects,
           @Value("${odt.public-url}") String publicUrl) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.projects = projects;
        this.api = publicUrl.replaceAll("/+$", "") + "/api";
    }

    @ApplicationModuleListener
    void on(IssueCreated e) {
        queue(e.projectId(), ISSUE_CREATED, e.by(), e.at(), issue(e.key(), e.fields()));
    }

    @ApplicationModuleListener
    void on(IssueChanged e) {
        queue(e.projectId(), ISSUE_CHANGED, e.by(), e.at(), issue(e.key(), e.changes()));
    }

    @ApplicationModuleListener
    void on(IssueDeleted e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", e.key());
        queue(e.projectId(), ISSUE_DELETED, e.by(), e.at(), data);
    }

    @ApplicationModuleListener
    void on(CommentPosted e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", e.issueKey());
        data.put("url", api + "/issues/" + e.issueKey() + "/comments");
        data.put("comment", e.commentId().toString());
        data.put("edited", e.edited());
        queue(e.projectId(), COMMENT_POSTED, e.by(), e.at(), data);
    }

    @ApplicationModuleListener
    void on(AttachmentAdded e) {
        queue(e.projectId(), ATTACHMENT_ADDED, e.by(), e.at(), attachment(e.issueKey(), e.attachmentId(), e.filename()));
    }

    @ApplicationModuleListener
    void on(AttachmentRemoved e) {
        queue(e.projectId(), ATTACHMENT_REMOVED, e.by(), e.at(), attachment(e.issueKey(), e.attachmentId(), e.filename()));
    }

    @ApplicationModuleListener
    void on(SprintStarted e) {
        Map<String, Object> data = sprint(e.projectId(), e.sprintId(), e.name());
        data.put("startsOn", e.startsOn() == null ? null : e.startsOn().toString());
        data.put("endsOn", e.endsOn() == null ? null : e.endsOn().toString());
        data.put("committedPoints", e.committedPoints());
        queue(e.projectId(), SPRINT_STARTED, e.by(), e.at(), data);
    }

    @ApplicationModuleListener
    void on(SprintCompleted e) {
        Map<String, Object> data = sprint(e.projectId(), e.sprintId(), e.name());
        data.put("done", e.done());
        data.put("carried", e.carried());
        data.put("donePoints", e.donePoints());
        data.put("carriedPoints", e.carriedPoints());
        queue(e.projectId(), SPRINT_COMPLETED, e.by(), e.at(), data);
    }

    /** «Send a test» of one webhook: a delivery of {@code ping}, due now. */
    DeliveryEntity ping(WebhookEntity webhook, ProjectRef project, String actor, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("webhook", webhook.name());
        UUID id = UUID.randomUUID();
        return deliveries.save(new DeliveryEntity(id, webhook.id(), PING,
                payload(id, PING, now, project, actor, data), now));
    }

    private void queue(UUID projectId, String type, String actor, Instant at, Map<String, Object> data) {
        Optional<ProjectRef> project = projects.byId(projectId);
        if (project.isEmpty()) {
            return;
        }
        List<DeliveryEntity> queued = new ArrayList<>();
        for (WebhookEntity webhook : webhooks.findByProjectIdAndActiveTrue(projectId)) {
            if (webhook.wants(type)) {
                // One id per delivery: the id of the payload and the webhook-id of every attempt
                UUID id = UUID.randomUUID();
                queued.add(new DeliveryEntity(id, webhook.id(), type, payload(id, type, at, project.get(), actor, data), at));
            }
        }
        deliveries.saveAll(queued);
    }

    private static String payload(UUID id, String type, Instant at, ProjectRef project, String actor,
                                  Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("type", type);
        body.put("time", at.toString());
        body.put("tenant", project.tenantId());
        body.put("project", project.key());
        body.put("actor", actor);
        body.put("data", data);
        return JSON.writeValueAsString(body);
    }

    private Map<String, Object> issue(String key, List<FieldChange> changes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("url", api + "/issues/" + key);
        List<Map<String, Object>> list = new ArrayList<>();
        for (FieldChange c : changes) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("field", c.field());
            change.put("from", c.from());
            change.put("to", c.to());
            list.add(change);
        }
        data.put("changes", list);
        return data;
    }

    private Map<String, Object> attachment(String key, UUID attachment, String filename) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("url", api + "/issues/" + key + "/attachments");
        data.put("attachment", attachment.toString());
        data.put("filename", filename);
        return data;
    }

    private Map<String, Object> sprint(UUID projectId, UUID sprint, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sprint", sprint.toString());
        data.put("name", name);
        data.put("url", api + "/projects/" + projects.byId(projectId).map(ProjectRef::key).orElse("") + "/sprints");
        return data;
    }
}
