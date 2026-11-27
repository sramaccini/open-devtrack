/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A plain document (a title, headings, paragraphs, tables of text) written as Office Open XML ({@code .docx}, Word)
 * or as OpenDocument Text ({@code .odt}, LibreOffice): both are zip files of a few XML parts, written here without a
 * library. Only what the export of an issue needs: no images, no styles beyond bold and sizes.
 */
final class IssueDocuments {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String ODT = "application/vnd.oasis.opendocument.text";

    /** A part of the document. */
    sealed interface Block permits Heading, Paragraph, Table {
    }

    /** A title (level 0) or a heading (1, 2). */
    record Heading(int level, String text) implements Block {
    }

    /** A paragraph; its line breaks are kept, and a {@code lead} in bold comes before the text. */
    record Paragraph(String lead, String text) implements Block {
    }

    /** A table of text with a header row in bold. */
    record Table(List<String> header, List<List<String>> rows) implements Block {
    }

    private IssueDocuments() {
    }

    // ---------------------------------------------------------------- Office Open XML

    static byte[] docx(String title, List<Block> blocks) {
        StringBuilder body = new StringBuilder();
        for (Block block : blocks) {
            switch (block) {
                case Heading h -> body.append("<w:p><w:pPr><w:spacing w:before=\"").append(h.level() == 0 ? 0 : 240)
                        .append("\" w:after=\"120\"/></w:pPr>")
                        .append(wordRun(h.text(), true, h.level() == 0 ? 36 : h.level() == 1 ? 28 : 24)).append("</w:p>");
                case Paragraph p -> {
                    body.append("<w:p>");
                    if (p.lead() != null) {
                        body.append(wordRun(p.lead(), true, 0));
                    }
                    if (p.text() != null) {
                        body.append(wordRun(p.text(), false, 0));
                    }
                    body.append("</w:p>");
                }
                case Table t -> {
                    body.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>");
                    for (String side : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
                        body.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>");
                    }
                    body.append("</w:tblBorders></w:tblPr><w:tblGrid>");
                    t.header().forEach(c -> body.append("<w:gridCol/>"));
                    body.append("</w:tblGrid>");
                    body.append(wordRow(t.header(), true));
                    t.rows().forEach(r -> body.append(wordRow(r, false)));
                    body.append("</w:tbl><w:p/>");
                }
            }
        }
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>"""
                + body + """
                <w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" \
                w:left="1134" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr></w:body></w:document>""";
        String core = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" \
                xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>""" + xml(title) + """
                </dc:title><dc:creator>OpenDevTrack</dc:creator></cp:coreProperties>""";
        Zip zip = new Zip();
        zip.add("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/word/document.xml" \
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>\
                <Override PartName="/docProps/core.xml" \
                ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""", true);
        zip.add("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" \
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
                Target="word/document.xml"/>\
                <Relationship Id="rId2" \
                Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" \
                Target="docProps/core.xml"/></Relationships>""", true);
        zip.add("docProps/core.xml", core, true);
        zip.add("word/document.xml", document, true);
        return zip.bytes();
    }

    private static String wordRow(List<String> cells, boolean bold) {
        StringBuilder row = new StringBuilder("<w:tr>");
        for (String cell : cells) {
            row.append("<w:tc><w:p>").append(wordRun(cell, bold, 0)).append("</w:p></w:tc>");
        }
        return row.append("</w:tr>").toString();
    }

    /** A run of text, its line breaks as breaks; {@code size} in half points, 0 for the default. */
    private static String wordRun(String text, boolean bold, int size) {
        StringBuilder run = new StringBuilder("<w:r>");
        if (bold || size > 0) {
            run.append("<w:rPr>");
            if (bold) {
                run.append("<w:b/>");
            }
            if (size > 0) {
                run.append("<w:sz w:val=\"").append(size).append("\"/>");
            }
            run.append("</w:rPr>");
        }
        String[] lines = (text == null ? "" : text).split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                run.append("<w:br/>");
            }
            run.append("<w:t xml:space=\"preserve\">").append(xml(lines[i])).append("</w:t>");
        }
        return run.append("</w:r>").toString();
    }

    // ---------------------------------------------------------------- OpenDocument

    static byte[] odt(String title, List<Block> blocks) {
        StringBuilder body = new StringBuilder();
        StringBuilder styles = new StringBuilder();
        int tables = 0;
        for (Block block : blocks) {
            switch (block) {
                case Heading h -> body.append("<text:h text:style-name=\"H").append(h.level())
                        .append("\" text:outline-level=\"").append(Math.max(1, h.level())).append("\">")
                        .append(odfText(h.text())).append("</text:h>");
                case Paragraph p -> {
                    body.append("<text:p text:style-name=\"P\">");
                    if (p.lead() != null) {
                        body.append("<text:span text:style-name=\"B\">").append(odfText(p.lead())).append("</text:span>");
                    }
                    if (p.text() != null) {
                        body.append(odfText(p.text()));
                    }
                    body.append("</text:p>");
                }
                case Table t -> {
                    tables++;
                    body.append("<table:table table:name=\"Table").append(tables).append("\" table:style-name=\"T\">");
                    for (int c = 0; c < t.header().size(); c++) {
                        body.append("<table:table-column table:style-name=\"T").append(tables).append('C').append(c)
                                .append("\"/>");
                    }
                    styles.append(columnStyles(tables, t.header().size()));
                    body.append(odfRow(t.header(), true));
                    t.rows().forEach(r -> body.append(odfRow(r, false)));
                    body.append("</table:table><text:p text:style-name=\"P\"/>");
                }
            }
        }
        String ns = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                + "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" "
                + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
                + "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" "
                + "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" "
                + "xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\" office:version=\"1.3\"";
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-content " + ns + ">"
                + "<office:font-face-decls><style:font-face style:name=\"Sans\" svg:font-family=\"Arial\" "
                + "style:font-family-generic=\"swiss\"/></office:font-face-decls>"
                + "<office:automatic-styles>"
                + "<style:style style:name=\"H0\" style:family=\"paragraph\"><style:paragraph-properties fo:margin-bottom=\"0.2cm\"/>"
                + "<style:text-properties style:font-name=\"Sans\" fo:font-size=\"18pt\" fo:font-weight=\"bold\"/></style:style>"
                + "<style:style style:name=\"H1\" style:family=\"paragraph\"><style:paragraph-properties fo:margin-top=\"0.4cm\" fo:margin-bottom=\"0.2cm\"/>"
                + "<style:text-properties style:font-name=\"Sans\" fo:font-size=\"14pt\" fo:font-weight=\"bold\"/></style:style>"
                + "<style:style style:name=\"H2\" style:family=\"paragraph\"><style:paragraph-properties fo:margin-top=\"0.3cm\" fo:margin-bottom=\"0.1cm\"/>"
                + "<style:text-properties style:font-name=\"Sans\" fo:font-size=\"12pt\" fo:font-weight=\"bold\"/></style:style>"
                + "<style:style style:name=\"P\" style:family=\"paragraph\"><style:paragraph-properties fo:margin-bottom=\"0.2cm\"/>"
                + "<style:text-properties style:font-name=\"Sans\" fo:font-size=\"10.5pt\"/></style:style>"
                + "<style:style style:name=\"PB\" style:family=\"paragraph\">"
                + "<style:text-properties style:font-name=\"Sans\" fo:font-size=\"10.5pt\" fo:font-weight=\"bold\"/></style:style>"
                + styles
                + "<style:style style:name=\"B\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\"/></style:style>"
                + "<style:style style:name=\"T\" style:family=\"table\"><style:table-properties style:width=\"17cm\" table:align=\"margins\"/></style:style>"
                + "<style:style style:name=\"C\" style:family=\"table-cell\"><style:table-cell-properties fo:padding=\"0.1cm\" fo:border=\"0.5pt solid #bfbfbf\"/></style:style>"
                + "</office:automatic-styles><office:body><office:text>" + body
                + "</office:text></office:body></office:document-content>";
        String meta = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-meta " + ns + "><office:meta>"
                + "<dc:title>" + xml(title) + "</dc:title><meta:generator>OpenDevTrack</meta:generator>"
                + "</office:meta></office:document-meta>";
        Zip zip = new Zip();
        // The type first and not compressed, as OpenDocument asks.
        zip.add("mimetype", ODT, false);
        zip.add("META-INF/manifest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" \
                manifest:version="1.3">\
                <manifest:file-entry manifest:full-path="/" manifest:version="1.3" \
                manifest:media-type="application/vnd.oasis.opendocument.text"/>\
                <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>\
                <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>\
                </manifest:manifest>""", true);
        zip.add("content.xml", content, true);
        zip.add("meta.xml", meta, true);
        return zip.bytes();
    }

    /** The widths of the columns of a table: the first (the field, the relation) narrow, the others wide. */
    private static String columnStyles(int table, int columns) {
        int[] widths = columns == 2 ? new int[] {3, 7} : columns == 3 ? new int[] {2, 5, 2} : null;
        StringBuilder out = new StringBuilder();
        for (int c = 0; c < columns; c++) {
            out.append("<style:style style:name=\"T").append(table).append('C').append(c)
                    .append("\" style:family=\"table-column\"><style:table-column-properties style:rel-column-width=\"")
                    .append(widths == null ? 1000 : widths[c] * 1000).append("*\"/></style:style>");
        }
        return out.toString();
    }

    private static String odfRow(List<String> cells, boolean bold) {
        StringBuilder row = new StringBuilder("<table:table-row>");
        for (String cell : cells) {
            row.append("<table:table-cell table:style-name=\"C\" office:value-type=\"string\"><text:p text:style-name=\"")
                    .append(bold ? "PB" : "P").append("\">").append(odfText(cell)).append("</text:p></table:table-cell>");
        }
        return row.append("</table:table-row>").toString();
    }

    /** Text with its line breaks; runs of spaces kept as ODF asks. */
    private static String odfText(String text) {
        StringBuilder out = new StringBuilder();
        String[] lines = (text == null ? "" : text).split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append("<text:line-break/>");
            }
            String line = xml(lines[i]);
            StringBuilder spaced = new StringBuilder();
            int spaces = 0;
            for (char c : line.toCharArray()) {
                if (c == ' ') {
                    spaces++;
                    continue;
                }
                spaced.append(spaces(spaces, spaced.isEmpty()));
                spaces = 0;
                spaced.append(c == '\t' ? "<text:tab/>" : String.valueOf(c));
            }
            spaced.append(spaces(spaces, spaced.isEmpty()));
            out.append(spaced);
        }
        return out.toString();
    }

    private static String spaces(int count, boolean leading) {
        if (count == 0) {
            return "";
        }
        if (count == 1 && !leading) {
            return " ";
        }
        return (leading ? "" : " ") + "<text:s text:c=\"" + (leading ? count : count - 1) + "\"/>";
    }

    // ---------------------------------------------------------------- both

    /** Text for XML: the five entities, and without the characters XML 1.0 cannot hold. */
    static String xml(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c == 0x9 || c == 0xA || c == 0xD || c >= 0x20 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD
                            || c >= 0x10000 && c <= 0x10FFFF) {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }

    /** A zip in memory, its entries in the order added. */
    private static final class Zip {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8);

        void add(String name, String text, boolean compressed) {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            ZipEntry entry = new ZipEntry(name);
            if (!compressed) {
                CRC32 crc = new CRC32();
                crc.update(data);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                entry.setCrc(crc.getValue());
            }
            try {
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        byte[] bytes() {
            try {
                zip.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return bytes.toByteArray();
        }
    }
}
