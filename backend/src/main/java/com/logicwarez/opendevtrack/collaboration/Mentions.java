/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.access.ProjectMember;
import com.logicwarez.opendevtrack.issue.Watchers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The mentions of a comment (ADR-13): {@code @username} of a member of the project, at the start of the text or after a
 * space, whoever wrote the comment. Other {@code @} are text. The people mentioned start watching the issue.
 */
@Component
class Mentions {

    /** {@code @} at the start or after a space, then what a username may hold; a final dot ends the sentence. */
    private static final Pattern MENTION = Pattern.compile("(?<!\\S)@([A-Za-z0-9][A-Za-z0-9._@-]*[A-Za-z0-9_-]|[A-Za-z0-9])");

    private final MentionRepository mentions;
    private final ProjectAccess access;
    private final Watchers watchers;

    Mentions(MentionRepository mentions, ProjectAccess access, Watchers watchers) {
        this.mentions = mentions;
        this.access = access;
        this.watchers = watchers;
    }

    /** The members of the project named in the text, in the order they come, as they are written in the project. */
    List<String> find(UUID projectId, String text) {
        Map<String, String> members = access.members(projectId).stream().map(ProjectMember::username)
                .collect(Collectors.toMap(u -> u.toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        Set<String> found = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            String member = members.get(m.group(1).toLowerCase(Locale.ROOT));
            if (member != null) {
                found.add(member);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Keeps the mentions of the comment as its text says now and returns those that are new, without its author; they
     * start watching the issue.
     */
    List<String> save(CommentEntity comment, UUID projectId, Instant at) {
        List<String> now = find(projectId, comment.body());
        Set<String> before = mentions.findByCommentId(comment.id()).stream().map(MentionEntity::username)
                .collect(Collectors.toSet());
        mentions.deleteAll(mentions.findByCommentId(comment.id()).stream().filter(e -> !now.contains(e.username())).toList());
        List<String> added = new ArrayList<>();
        for (String username : now) {
            if (!before.contains(username)) {
                mentions.save(new MentionEntity(comment.id(), username));
                if (!username.equals(comment.author())) {
                    added.add(username);
                    watchers.watch(comment.issueId(), username, at);
                }
            }
        }
        return added;
    }
}
