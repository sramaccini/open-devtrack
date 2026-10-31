/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Adapted from the Quadrava reference registry (reference/registry, commit 9fa1916), contracts/registry.yaml 0.6.0.
 */
package com.logicwarez.opendevtrack.uiregistry;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Types of the contract contracts/registry.yaml. */
public final class Model {
    private Model() {}

    /**
     * Identifiers of applications and documents: kebab-case. They also end up in the paths of the file store, so
     * nothing else is allowed (no {@code ..}, slashes or special characters).
     */
    public static final Pattern IDENTIFIER = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** Document kind: it decides the validation schema and the property that holds the identifier. */
    public enum Kind {
        SCREEN("screen"),
        NAVIGATION("navigation"),
        THEME("theme"),
        /** Data sources shared by the screens of the application (the screens refer to them with {@code ref}). */
        DATASOURCES("datasources"),
        /** Translation catalogue of one language: the identifier is the language (it, en, pt-br). */
        MESSAGES("messages");

        private final String idProperty;

        Kind(String idProperty) {
            this.idProperty = idProperty;
        }

        /** Property of the content with the identifier of the document (`screen`, `navigation`, `theme`, `messages`). */
        public String idProperty() {
            return idProperty;
        }

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> parse(String value) {
            for (Kind kind : values()) {
                if (kind.value().equals(value)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** Review state of a version. */
    public enum State {
        DRAFT("draft"),
        IN_REVIEW("in-review"),
        APPROVED("approved"),
        REJECTED("rejected");

        private final String value;

        State(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** A document of an application. */
    public record DocumentKey(String appId, Kind kind, String documentId) {}

    public record AppSummary(String id, String name, int documentCount) {}

    /** A version: its content is immutable; only the review state changes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentVersion(String hash, Instant createdAt, String author, String state,
                                  String reviewedBy, Instant reviewedAt, String reviewNote) {

        public DocumentVersion(String hash, Instant createdAt, String author, String state) {
            this(hash, createdAt, author, state, null, null, null);
        }

        DocumentVersion withReview(State next, String reviewer, String note) {
            boolean decision = next == State.APPROVED || next == State.REJECTED;
            return new DocumentVersion(hash, createdAt, author, next.value(),
                    decision ? reviewer : null, decision ? Instant.now() : null, decision ? note : null);
        }
    }

    public record DocumentSummary(String kind, String id, DocumentVersion latest, Map<String, String> publishedEnvironments) {}

    public record PublishRequest(String hash) {}

    /** Review action: {@code request} (draft or rejected → in review), {@code approve}, {@code reject}. */
    public record ReviewRequest(String action, String note) {}

    /** A validation problem: position in the document (JSON Pointer) and message. */
    public record Issue(String path, String message) {}

    /** A reference to another screen: identifier and position in the document (JSON Pointer). */
    public record ScreenReference(String screen, String path) {}

    /** A reference of a screen to a data sources document: identifier and position in the screen (JSON Pointer). */
    public record DataSourcesReference(String datasources, String path) {}

    /**
     * References of a version and those missing in the environment ({@code env} null = existence check). The data sources
     * documents referred to are listed for screens only.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReferenceReport(String env, List<ScreenReference> references, List<ScreenReference> missing,
                                  List<DataSourcesReference> dataSources, List<DataSourcesReference> missingDataSources) {}
}
