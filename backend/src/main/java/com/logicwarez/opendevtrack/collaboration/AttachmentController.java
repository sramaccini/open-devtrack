/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.collaboration.AttachmentEvents.AttachmentAdded;
import com.logicwarez.opendevtrack.collaboration.AttachmentEvents.AttachmentRemoved;
import com.logicwarez.opendevtrack.issue.IssueRef;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachments of the issues (ADR-14). Whoever sees the issue lists and downloads them; {@code odt.issue.attach}
 * (developer) adds them as the {@code file} part of a multipart request; the uploader removes their own, an admin of
 * the project ({@code odt.issue.delete}) also those of others. A download is always a download
 * ({@code Content-Disposition: attachment}, {@code nosniff}): a file is never shown inside the pages.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class AttachmentController {

    static final int MAX_PER_ISSUE = 100;
    private static final int MAX_NAME = 255;
    private static final String ISSUE_NOT_FOUND = "Issue not found";
    private static final String NOT_FOUND = "Attachment not found";
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentController.class);

    private final Issues issues;
    private final AttachmentRepository attachments;
    private final AttachmentBytes bytes;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;
    private final DataSize maxSize;

    AttachmentController(Issues issues, AttachmentRepository attachments, AttachmentBytes bytes, ProjectAccess access,
                         CurrentUser user, ApplicationEventPublisher events,
                         @Value("${odt.attachments.max-size:25MB}") DataSize maxSize) {
        this.issues = issues;
        this.attachments = attachments;
        this.bytes = bytes;
        this.access = access;
        this.user = user;
        this.events = events;
        this.maxSize = maxSize;
    }

    record AttachmentView(UUID id, String filename, String mediaType, long size, String sha256, String uploadedBy,
                          Instant uploadedAt, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    @ApiDoc(value = "The attachments of an issue",
            answer = AttachmentView.class, list = true, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/attachments")
    ResponseEntity<?> list(@PathVariable String issueKey) {
        Optional<IssueRef> issue = issues.find(issueKey);
        if (issue.isEmpty()) {
            return Problems.notFound(ISSUE_NOT_FOUND);
        }
        Set<String> permissions = permissions(issue.get());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden("Only the members of the project see its issues");
        }
        return ResponseEntity.ok(attachments.findByIssueIdOrderByUploadedAtAscIdAsc(issue.get().id()).stream()
                .map(a -> view(a, permissions)).toList());
    }

    @ApiDoc(value = "Attaches a file to an issue (multipart, part file)",
            answer = AttachmentView.class, status = 201, project = "odt.issue.attach")
    @PostMapping(path = "/api/issues/{issueKey}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    ResponseEntity<?> add(@PathVariable String issueKey, @RequestParam("file") MultipartFile file) throws IOException {
        Optional<IssueRef> issue = issues.find(issueKey);
        if (issue.isEmpty()) {
            return Problems.notFound(ISSUE_NOT_FOUND);
        }
        Set<String> permissions = permissions(issue.get());
        if (!permissions.contains("odt.issue.attach")) {
            return Problems.forbidden("Only a developer or an admin of the project attaches files");
        }
        if (file.getSize() > maxSize.toBytes()) {
            return Problems.of(HttpStatus.CONTENT_TOO_LARGE, "A file is at most " + maxSize.toMegabytes() + " MB");
        }
        if (attachments.countByIssueId(issue.get().id()) >= MAX_PER_ISSUE) {
            return Problems.conflict("An issue has at most " + MAX_PER_ISSUE + " attachments");
        }
        Instant now = Instant.now();
        AttachmentEntity attachment = attachments.saveAndFlush(new AttachmentEntity(issue.get().id(),
                issue.get().projectId(), filename(file.getOriginalFilename()), mediaType(file.getContentType()),
                file.getSize(), bytes.store(), user.username(), now));
        try (InputStream content = file.getInputStream()) {
            bytes.keep(attachment, content);
        }
        events.publishEvent(new AttachmentAdded(attachment.id(), issue.get().id(), issue.get().projectId(),
                issue.get().key(), attachment.filename(), user.username(), now));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(attachment, permissions));
    }

    @ApiDoc(value = "The content of an attachment, with its media type", project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/attachments/{id}/content")
    ResponseEntity<?> content(@PathVariable String issueKey, @PathVariable UUID id) throws IOException {
        Optional<Found> found = find(issueKey, id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        AttachmentEntity attachment = found.get().attachment();
        InputStream content;
        try {
            content = bytes.read(attachment);
        } catch (NoSuchFileException e) {
            LOG.error("The bytes of the attachment {} are missing from the store {}", attachment.id(), attachment.store());
            return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "The file of the attachment is missing");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.parseMediaType(attachment.mediaType()))
                .contentLength(attachment.size())
                .body(new InputStreamResource(content));
    }

    @ApiDoc(value = "Removes an attachment: whoever attached it, or an admin of the project",
            status = 204, project = {"odt.issue.attach", "odt.issue.delete"})
    @DeleteMapping("/api/issues/{issueKey}/attachments/{id}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable String issueKey, @PathVariable UUID id) {
        Optional<Found> found = find(issueKey, id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        AttachmentEntity attachment = found.get().attachment();
        if (!canDelete(attachment, found.get().permissions())) {
            return Problems.forbidden("Only the uploader or an admin of the project removes an attachment");
        }
        attachments.delete(attachment);
        bytes.removeAfterCommit(attachment);
        IssueRef issue = found.get().issue();
        events.publishEvent(new AttachmentRemoved(attachment.id(), issue.id(), issue.projectId(), issue.key(),
                attachment.filename(), user.username(), Instant.now()));
        return ResponseEntity.noContent().build();
    }

    private record Found(AttachmentEntity attachment, IssueRef issue, Set<String> permissions) {
    }

    /** The attachment of that issue, seen by the user; another issue's attachment is not found. */
    private Optional<Found> find(String issueKey, UUID id) {
        return issues.find(issueKey).flatMap(issue -> {
            Set<String> permissions = permissions(issue);
            if (!permissions.contains("odt.issue.browse")) {
                return Optional.empty();
            }
            return attachments.findById(id).filter(a -> a.issueId().equals(issue.id()))
                    .map(a -> new Found(a, issue, permissions));
        });
    }

    private Set<String> permissions(IssueRef issue) {
        return access.permissions(issue.projectId(), user.username());
    }

    private boolean canDelete(AttachmentEntity attachment, Set<String> permissions) {
        return permissions.contains("odt.issue.delete")
                || permissions.contains("odt.issue.attach") && attachment.uploadedBy().equals(user.username());
    }

    /** The last segment of the name sent, without control characters, at most 255 characters. */
    static String filename(String sent) {
        String name = sent == null ? "" : sent;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "file";
        }
        if (name.length() <= MAX_NAME) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
        return name.substring(0, MAX_NAME - extension.length()) + extension;
    }

    /** The type sent if it is a valid media type, otherwise {@code application/octet-stream}. */
    static String mediaType(String sent) {
        if (sent == null || sent.isBlank() || sent.length() > MAX_NAME) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            MediaType type = MediaType.parseMediaType(sent);
            return type.isConcrete() ? type.toString() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private AttachmentView view(AttachmentEntity a, Set<String> permissions) {
        return new AttachmentView(a.id(), a.filename(), a.mediaType(), a.size(), a.sha256(), a.uploadedBy(),
                a.uploadedAt(), Map.of("delete", canDelete(a, permissions)));
    }
}
