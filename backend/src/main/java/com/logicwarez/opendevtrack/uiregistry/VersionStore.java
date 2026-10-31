/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Adapted from the Quadrava reference registry (reference/registry, commit 9fa1916), contracts/registry.yaml 0.6.0.
 */
package com.logicwarez.opendevtrack.uiregistry;

import com.logicwarez.opendevtrack.uiregistry.Model.DocumentKey;
import com.logicwarez.opendevtrack.uiregistry.Model.DocumentVersion;
import com.logicwarez.opendevtrack.uiregistry.Model.Kind;
import com.logicwarez.opendevtrack.uiregistry.Model.State;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Version store on PostgreSQL (schema {@code odt_uiregistry}). Every document (a screen, the navigation, a theme) has its
 * own immutable versions, identified by the hash of the content, and its own publications: publishing means pointing an
 * environment at a version.
 */
@Component
@Transactional(readOnly = true)
class VersionStore {

    /** Outcome of a review state change. */
    sealed interface ReviewOutcome {
        record Done(DocumentVersion version) implements ReviewOutcome {}

        record NotFound() implements ReviewOutcome {}

        record NotAllowed(String reason) implements ReviewOutcome {}
    }

    private final VersionRepository versions;
    private final PublicationRepository publications;

    VersionStore(VersionRepository versions, PublicationRepository publications) {
        this.versions = versions;
        this.publications = publications;
    }

    /** Saves a version; the same content saved again gives back the version that already has it. */
    @Transactional
    DocumentVersion save(DocumentKey key, String content, String author) {
        String hash = sha256(content);
        return find(key, hash).orElseGet(() -> versions.save(
                new VersionEntity(key, hash, content, author, State.DRAFT.value(), Instant.now()))).toVersion();
    }

    /** Latest saved version. */
    Optional<DocumentVersion> latest(DocumentKey key) {
        return versions.findFirstByAppIdAndKindAndDocumentIdOrderByCreatedAtDescIdDesc(key.appId(), key.kind().value(), key.documentId())
                .map(VersionEntity::toVersion);
    }

    /** Versions of a document, newest first. */
    List<DocumentVersion> versions(DocumentKey key) {
        return versions.findByAppIdAndKindAndDocumentIdOrderByCreatedAtDescIdDesc(key.appId(), key.kind().value(), key.documentId())
                .stream().map(VersionEntity::toVersion).toList();
    }

    Optional<DocumentVersion> version(DocumentKey key, String hash) {
        return find(key, hash).map(VersionEntity::toVersion);
    }

    Optional<String> content(DocumentKey key, String hash) {
        return find(key, hash).map(VersionEntity::content);
    }

    /**
     * Changes the review state of a version: {@code request} from draft or rejected to in review; {@code approve} and
     * {@code reject} from in review. Checks on who may do it are up to the caller.
     */
    @Transactional
    ReviewOutcome review(DocumentKey key, String hash, String action, String reviewer, String note) {
        Optional<VersionEntity> found = find(key, hash);
        if (found.isEmpty()) {
            return new ReviewOutcome.NotFound();
        }
        DocumentVersion version = found.get().toVersion();
        String state = version.state();
        State next = switch (action) {
            case "request" -> state.equals(State.DRAFT.value()) || state.equals(State.REJECTED.value()) ? State.IN_REVIEW : null;
            case "approve" -> state.equals(State.IN_REVIEW.value()) ? State.APPROVED : null;
            case "reject" -> state.equals(State.IN_REVIEW.value()) ? State.REJECTED : null;
            default -> null;
        };
        if (next == null) {
            return new ReviewOutcome.NotAllowed("Action «" + action + "» not allowed for a version in state «" + state + "»");
        }
        DocumentVersion updated = version.withReview(next, reviewer, note);
        found.get().review(updated);
        return new ReviewOutcome.Done(updated);
    }

    /** Points an environment at an existing version of the document; false if the version does not exist. */
    @Transactional
    boolean publish(DocumentKey key, String env, String hash) {
        if (find(key, hash).isEmpty()) {
            return false;
        }
        PublicationEntity publication = publications
                .findByAppIdAndKindAndDocumentIdAndEnvironment(key.appId(), key.kind().value(), key.documentId(), env)
                .orElseGet(() -> new PublicationEntity(key, env));
        publication.point(hash);
        publications.save(publication);
        return true;
    }

    Optional<String> publishedHash(DocumentKey key, String env) {
        return publications.findByAppIdAndKindAndDocumentIdAndEnvironment(key.appId(), key.kind().value(), key.documentId(), env)
                .map(PublicationEntity::hash);
    }

    /** Environment → published hash. */
    Map<String, String> publishedEnvironments(DocumentKey key) {
        return publications.findByAppIdAndKindAndDocumentId(key.appId(), key.kind().value(), key.documentId()).stream()
                .collect(Collectors.toUnmodifiableMap(PublicationEntity::environment, PublicationEntity::hash));
    }

    /** Documents of an application. */
    List<DocumentKey> documents(String appId) {
        return versions.documents(appId).stream()
                .flatMap(row -> Kind.parse((String) row[0]).map(kind -> new DocumentKey(appId, kind, (String) row[1])).stream())
                .sorted(Comparator.comparing(DocumentKey::documentId))
                .toList();
    }

    List<String> appIds() {
        return versions.appIds();
    }

    private Optional<VersionEntity> find(DocumentKey key, String hash) {
        return versions.findByAppIdAndKindAndDocumentIdAndHash(key.appId(), key.kind().value(), key.documentId(), hash);
    }

    static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
