/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * The attachments on the store database, the default (ADR-14), and the rules of the API: who adds, reads and removes
 * them, what a download looks like, the names and types kept, the size limit and the changelog.
 */
@TestPropertySource(properties = "odt.attachments.max-size=2KB")
class AttachmentApiTest extends AttachmentTestBase {

    @Override
    String expectedStore() {
        return "database";
    }

    private List<String> history() {
        return jdbc.queryForList("SELECT coalesce(old_value, '') || '>' || coalesce(new_value, '') FROM odt_audit.issue_change "
                + "WHERE field = 'attachments' ORDER BY changed_at, id", String.class);
    }

    @Test
    void developersAttachEveryoneWhoSeesTheIssueDownloads() throws Exception {
        byte[] content = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        String id = upload(luisa, "export.csv", "text/csv", content);

        mvc.perform(as(get("/api/issues/ODT-1/attachments"), paola))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].filename").value("export.csv"))
                .andExpect(jsonPath("$[0].mediaType").value("text/csv"))
                .andExpect(jsonPath("$[0].size").value(content.length))
                .andExpect(jsonPath("$[0].sha256").value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))))
                .andExpect(jsonPath("$[0].uploadedBy").value("luisa"))
                .andExpect(jsonPath("$[0]._perm.delete").value(false));
        mvc.perform(as(get("/api/issues/ODT-1/attachments"), luisa)).andExpect(jsonPath("$[0]._perm.delete").value(true));

        // Always a download, never shown inline.
        mvc.perform(as(get("/api/issues/ODT-1/attachments/" + id + "/content"), paola))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"export.csv\"; filename*=UTF-8''export.csv"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "text/csv"))
                .andExpect(content().bytes(content));

        // A viewer reads but does not attach; another tenant does not see the issue.
        mvc.perform(as(multipart("/api/issues/ODT-1/attachments")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain", new byte[] {1})), paola))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/issues/ODT-1/attachments/" + id + "/content"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andExpect(jsonPath("$._perm.attach").value(true));
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(jsonPath("$._perm.attach").value(false));
    }

    @Test
    void namesAndTypesAreKeptSafe() throws Exception {
        String html = upload(luisa, "..\\..\\etc/evil.html", "text/html", "<script>1</script>".getBytes(StandardCharsets.UTF_8));
        String odd = upload(luisa, "", "not a type", new byte[] {0, 1, 2});
        mvc.perform(as(get("/api/issues/ODT-1/attachments"), luisa))
                .andExpect(jsonPath("$[0].filename").value("evil.html"))
                .andExpect(jsonPath("$[1].filename").value("file"))
                .andExpect(jsonPath("$[1].mediaType").value("application/octet-stream"));
        mvc.perform(as(get("/api/issues/ODT-1/attachments/" + html + "/content"), luisa))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"evil.html\"; filename*=UTF-8''evil.html"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertThat(key(odd)).doesNotContain("file").matches("[0-9a-f-]{36}/[0-9a-f-]{36}");
        assertThat(AttachmentController.filename("a".repeat(300) + ".pdf")).hasSize(255).endsWith(".pdf");
    }

    @Test
    void tooLargeIsRefused() throws Exception {
        mvc.perform(as(multipart("/api/issues/ODT-1/attachments")
                        .file(new MockMultipartFile("file", "big.bin", "application/octet-stream", new byte[3 * 1024])), luisa))
                .andExpect(status().isContentTooLarge());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_collaboration.attachment", Integer.class)).isZero();
    }

    @Test
    void theUploaderOrAnAdminRemovesAndTheChangelogTellsIt() throws Exception {
        String id = upload(luisa, "screenshot.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        mvc.perform(as(delete("/api/issues/ODT-1/attachments/" + id), paola)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/issues/ODT-1/attachments/" + id), mario)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-1/attachments/" + id + "/content"), luisa)).andExpect(status().isNotFound());
        await(() -> history().size() == 2);
        assertThat(history()).containsExactly(">screenshot.png", "screenshot.png>");
    }
}
