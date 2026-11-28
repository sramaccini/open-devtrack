/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueDocuments.Block;
import com.logicwarez.opendevtrack.issue.IssueDocuments.Heading;
import com.logicwarez.opendevtrack.issue.IssueDocuments.Paragraph;
import com.logicwarez.opendevtrack.issue.IssueDocuments.Table;
import com.logicwarez.opendevtrack.issue.Issues.Found;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.IssueContent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The export of one issue, for whoever sees it, as Jira's «Export» of an issue: {@code json} (everything: the fields,
 * the links, the watchers, the comments, the attachments listed and the history, to read with other tools), or a
 * document to read and send, {@code docx} (Word) or {@code odt} (OpenDocument, LibreOffice), with the fields, the
 * description, the links, the attachments listed and the comments. The texts of the documents are in English, or in
 * Italian when the request asks for it; the description and the comments are their Markdown as written. The other
 * modules give their part through {@link IssueContent}.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class ExportController {

    /** The version of the shape of the JSON export, raised when a field changes meaning or goes. */
    static final int SCHEMA = 1;
    /** Times with their zone (that of the reports, {@code odt.reporting.zone}): a document is read away from the page. */
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);

    private final Issues finder;
    private final IssueRepository issues;
    private final IssueLinkRepository links;
    private final Projects projects;
    private final ProjectAccess access;
    private final Watchers watchers;
    private final VoteRepository votes;
    private final CustomFields customFields;
    private final List<IssueContent> modules;
    private final CurrentUser user;
    private final ZoneId zone;
    private final LinkTypes linkTypes;
    private final ResourceBundleMessageSource texts = new ResourceBundleMessageSource();

    ExportController(Issues finder, IssueRepository issues, IssueLinkRepository links, Projects projects,
                     ProjectAccess access, Watchers watchers, VoteRepository votes, CustomFields customFields,
                     List<IssueContent> modules, LinkTypes linkTypes,
                     CurrentUser user, @Value("${odt.reporting.zone:UTC}") String zone) {
        this.finder = finder;
        this.linkTypes = linkTypes;
        this.issues = issues;
        this.links = links;
        this.projects = projects;
        this.access = access;
        this.watchers = watchers;
        this.votes = votes;
        this.customFields = customFields;
        this.modules = modules;
        this.user = user;
        this.zone = ZoneId.of(zone);
        texts.setBasename("issue/export");
        texts.setDefaultEncoding("UTF-8");
        texts.setFallbackToSystemLocale(false);
    }

    @ApiDoc(value = "Exports an issue as a file: json, docx (Word) or odt (OpenDocument)", project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/export")
    ResponseEntity<?> export(@PathVariable String issueKey, @RequestParam(defaultValue = "json") String format) {
        if (!List.of("json", "docx", "odt").contains(format)) {
            return Problems.badRequest("The format is one of json, docx, odt");
        }
        Optional<Found> found = finder.findEntity(issueKey);
        if (found.isEmpty() || !access.permissions(found.get().project().id(), user.username())
                .contains("odt.issue.browse")) {
            return Problems.notFound("Issue not found");
        }
        ProjectRef project = found.get().project();
        IssueEntity issue = found.get().issue();
        String key = IssueController.key(project, issue);
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        modules.forEach(m -> parts.putAll(m.content(issue.id())));
        List<Map<String, Object>> linked = links(issue);
        Map<String, Object> parent = parent(project, issue);
        List<Map<String, Object>> children = issues.findByParentIdOrderByNumber(issue.id()).stream()
                .map(c -> row(project, c)).toList();
        Map<String, String> labels = customFields.all(project.id()).stream()
                .collect(Collectors.toMap(FieldDefinitionEntity::key, FieldDefinitionEntity::label, (a, b) -> a));
        Instant now = Instant.now();
        return switch (format) {
            case "json" -> file(key + ".json", MediaType.APPLICATION_JSON,
                    json(project, issue, key, labels, linked, parent, children, parts, now));
            case "docx" -> file(key + ".docx", MediaType.parseMediaType(IssueDocuments.DOCX),
                    IssueDocuments.docx(key + " " + issue.summary(), document(project, issue, key, labels, linked, parent, children, parts, now)));
            default -> file(key + ".odt", MediaType.parseMediaType(IssueDocuments.ODT),
                    IssueDocuments.odt(key + " " + issue.summary(), document(project, issue, key, labels, linked, parent, children, parts, now)));
        };
    }

    /** The parent of the issue (ADR-28) as the export gives it: key, summary, type and status; null without one. */
    private Map<String, Object> parent(ProjectRef project, IssueEntity issue) {
        return issue.parentId() == null ? null : issues.findById(issue.parentId()).map(p -> row(project, p)).orElse(null);
    }

    private static Map<String, Object> row(ProjectRef project, IssueEntity i) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", IssueController.key(project, i));
        row.put("summary", i.summary());
        row.put("type", i.type().value());
        row.put("status", i.status());
        return row;
    }

    private Map<String, Object> json(ProjectRef project, IssueEntity i, String key, Map<String, String> labels,
                                     List<Map<String, Object>> linked, Map<String, Object> parent,
                                     List<Map<String, Object>> children, Map<String, List<Map<String, Object>>> parts,
                                     Instant now) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("key", key);
        fields.put("project", project.key());
        fields.put("type", i.type().value());
        fields.put("summary", i.summary());
        fields.put("status", i.status());
        fields.put("priority", i.priority().value());
        fields.put("assignee", i.assignee());
        fields.put("reporter", i.reporter());
        fields.put("reviewer", i.reviewer());
        fields.put("dueOn", i.dueOn() == null ? null : i.dueOn().toString());
        fields.put("startOn", i.startOn() == null ? null : i.startOn().toString());
        fields.put("labels", i.labels());
        fields.put("parent", parent);
        fields.put("flagged", i.flagged());
        fields.put("description", i.description());
        fields.put("createdAt", i.createdAt());
        fields.put("updatedAt", i.updatedAt());
        Map<String, Object> custom = new LinkedHashMap<>();
        i.custom().forEach((k, v) -> {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("label", labels.getOrDefault(k, k));
            field.put("value", v);
            custom.put(k, field);
        });
        fields.put("custom", custom);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("schema", SCHEMA);
        export.put("exportedAt", now);
        export.put("exportedBy", user.username());
        export.put("issue", fields);
        export.put("links", linked);
        export.put("children", children);
        export.put("watchers", watchers.of(i.id()));
        export.put("votes", votes.countByIssueId(i.id()));
        for (String part : List.of("comments", "attachments", "history")) {
            export.put(part, parts.getOrDefault(part, List.of()));
        }
        return export;
    }

    private List<Block> document(ProjectRef project, IssueEntity i, String key, Map<String, String> labels,
                                 List<Map<String, Object>> linked, Map<String, Object> parent,
                                 List<Map<String, Object>> children, Map<String, List<Map<String, Object>>> parts,
                                 Instant now) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Heading(0, key + " " + i.summary()));
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(text("field.project"), project.key() + " · " + project.name()));
        rows.add(List.of(text("field.type"), text("type." + i.type().value(), i.type().value())));
        rows.add(List.of(text("field.status"), text("status." + i.status(), i.status())));
        if (parent != null) {
            rows.add(List.of(text("field.parent"), parent.get("key") + " " + parent.get("summary")));
        }
        rows.add(List.of(text("field.priority"), text("priority." + i.priority().value(), i.priority().value())));
        rows.add(List.of(text("field.assignee"), orNone(i.assignee())));
        rows.add(List.of(text("field.reporter"), i.reporter()));
        rows.add(List.of(text("field.reviewer"), orNone(i.reviewer())));
        rows.add(List.of(text("field.start"), i.startOn() == null ? text("none") : i.startOn().toString()));
        rows.add(List.of(text("field.due"), i.dueOn() == null ? text("none") : i.dueOn().toString()));
        rows.add(List.of(text("field.labels"), i.labels().isEmpty() ? text("none") : String.join(", ", i.labels())));
        rows.add(List.of(text("field.flagged"), text(i.flagged() ? "yes" : "no")));
        rows.add(List.of(text("field.votes"), String.valueOf(votes.countByIssueId(i.id()))));
        List<String> watching = watchers.of(i.id());
        rows.add(List.of(text("field.watchers"), watching.isEmpty() ? text("none") : String.join(", ", watching)));
        rows.add(List.of(text("field.created"), when(i.createdAt())));
        rows.add(List.of(text("field.updated"), when(i.updatedAt())));
        i.custom().forEach((k, v) -> {
            String value = CustomFields.text(v);
            rows.add(List.of(labels.getOrDefault(k, k), value == null || value.isBlank() ? text("none") : value));
        });
        blocks.add(new Table(List.of(text("field"), text("value")), rows));

        blocks.add(new Heading(1, text("description")));
        paragraphs(blocks, i.description());

        if (!linked.isEmpty()) {
            blocks.add(new Heading(1, text("links")));
            blocks.add(new Table(List.of(text("link.relation"), text("link.issue"), text("field.status")),
                    linked.stream().map(l -> List.of(
                            (String) l.get("label"),
                            l.get("key") + " " + l.get("summary"),
                            text("status." + l.get("status"), (String) l.get("status")))).toList()));
        }
        if (!children.isEmpty()) {
            blocks.add(new Heading(1, text(i.type() == IssueType.EPIC ? "children.epic" : "children")));
            blocks.add(new Table(List.of(text("link.issue"), text("field.type"), text("field.status")),
                    children.stream().map(c -> List.of(c.get("key") + " " + c.get("summary"),
                            text("type." + c.get("type"), (String) c.get("type")),
                            text("status." + c.get("status"), (String) c.get("status")))).toList()));
        }
        List<Map<String, Object>> attachments = parts.getOrDefault("attachments", List.of());
        if (!attachments.isEmpty()) {
            blocks.add(new Heading(1, text("attachments")));
            blocks.add(new Table(List.of(text("attachment.file"), text("attachment.size"), text("attachment.by")),
                    attachments.stream().map(a -> List.of((String) a.get("filename"), size((Long) a.get("size")),
                            a.get("uploadedBy") + ", " + when((Instant) a.get("uploadedAt")))).toList()));
        }
        List<Map<String, Object>> comments = parts.getOrDefault("comments", List.of());
        blocks.add(new Heading(1, text("comments")));
        if (comments.isEmpty()) {
            blocks.add(new Paragraph(null, text("none")));
        }
        for (Map<String, Object> c : comments) {
            blocks.add(new Heading(2, c.get("author") + " · " + when((Instant) c.get("createdAt"))));
            paragraphs(blocks, (String) c.get("body"));
        }
        blocks.add(new Paragraph(null, texts.getMessage("exported", new Object[] {when(now), user.username()},
                LocaleContextHolder.getLocale())));
        return blocks;
    }

    /** The links of the issue to issues the user sees, as the tab Links shows them. */
    private List<Map<String, Object>> links(IssueEntity issue) {
        UUID id = issue.id();
        List<IssueLinkEntity> all = links.findByIssue(id);
        Map<String, LinkTypeEntity> types = linkTypes.of(issue.tenantId()).stream()
                .collect(Collectors.toMap(LinkTypeEntity::code, Function.identity()));
        Map<UUID, IssueEntity> others = issues.findByIdIn(all.stream().map(l -> l.other(id)).distinct().toList()).stream()
                .collect(Collectors.toMap(IssueEntity::id, Function.identity()));
        Map<UUID, Optional<ProjectRef>> visible = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IssueLinkEntity l : all) {
            IssueEntity other = others.get(l.other(id));
            LinkTypeEntity type = types.get(l.type());
            if (other == null || type == null) {
                continue;
            }
            visible.computeIfAbsent(other.projectId(), p -> projects.get(p)
                    .filter(r -> access.permissions(r.id(), user.username()).contains("odt.issue.browse")))
                    .ifPresent(p -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        boolean outward = l.fromId().equals(id);
                        row.put("relation", LinkTypes.relation(type, outward));
                        row.put("label", linkTypes.label(type, outward));
                        row.put("key", IssueController.key(p, other));
                        row.put("summary", other.summary());
                        row.put("status", other.status());
                        rows.add(row);
                    });
        }
        return rows;
    }

    /** Paragraphs of a text, split at the blank lines; the text «None» when there is none. */
    private void paragraphs(List<Block> blocks, String text) {
        if (text == null || text.isBlank()) {
            blocks.add(new Paragraph(null, text("none")));
            return;
        }
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            blocks.add(new Paragraph(null, paragraph));
        }
    }

    private String when(Instant instant) {
        return WHEN.format(instant.atZone(zone));
    }

    private String orNone(String value) {
        return value == null ? text("none") : value;
    }

    private static String size(Long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private String text(String code) {
        return texts.getMessage(code, null, LocaleContextHolder.getLocale());
    }

    /** The text of the code, or {@code fallback} (a status of a custom workflow, for one). */
    private String text(String code, String fallback) {
        try {
            return text(code);
        } catch (NoSuchMessageException e) {
            return fallback;
        }
    }

    private static ResponseEntity<?> file(String name, MediaType type, Object body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .contentType(type)
                .body(body);
    }
}
