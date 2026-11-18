/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;

/**
 * The declared type of an attachment checked against its first bytes (ASVS 12.2.1, ADR-24 point 8): a small table of
 * signatures for the types that matter, no library. A type the table does not know, or a file whose content does not
 * match, is kept as {@code application/octet-stream}; the file is stored anyway and is always a download.
 */
final class ContentTypes {

    /** Bytes read from the start of a file: enough for every signature, and for a look at a text. */
    static final int HEAD = 8192;

    private enum Kind { PDF, PNG, JPEG, GIF, WEBP, ZIP, OLE, GZIP, TEXT }

    private static final byte[] PDF = ascii("%PDF-");
    private static final byte[] PNG = HexFormat.of().parseHex("89504e470d0a1a0a");
    private static final byte[] JPEG = HexFormat.of().parseHex("ffd8ff");
    private static final byte[] GIF87 = ascii("GIF87a");
    private static final byte[] GIF89 = ascii("GIF89a");
    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WEBP = ascii("WEBP");
    private static final byte[] ZIP = HexFormat.of().parseHex("504b0304");
    private static final byte[] ZIP_EMPTY = HexFormat.of().parseHex("504b0506");
    private static final byte[] OLE = HexFormat.of().parseHex("d0cf11e0a1b11ae1");
    private static final byte[] GZIP = HexFormat.of().parseHex("1f8b");

    /** The types of the Office formats, documents of OpenDocument and other archives that are ZIP inside. */
    private static final List<String> ZIP_PREFIXES = List.of("application/vnd.openxmlformats-officedocument.",
            "application/vnd.oasis.opendocument.");

    private ContentTypes() {
    }

    /** The declared type when the first bytes of the file confirm it, otherwise {@code application/octet-stream}. */
    static String checked(String declared, byte[] head) {
        Kind expected = expected(declared);
        return expected != null && matches(expected, head) ? declared : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static Kind expected(String declared) {
        String type = declared.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) {
            type = type.substring(0, semicolon).strip();
        }
        if (type.startsWith("text/") || type.equals("application/json") || type.equals("application/xml")
                || type.endsWith("+json") || type.endsWith("+xml") && !type.equals("image/svg+xml")) {
            return Kind.TEXT;
        }
        if (ZIP_PREFIXES.stream().anyMatch(type::startsWith)) {
            return Kind.ZIP;
        }
        return switch (type) {
            case "application/pdf" -> Kind.PDF;
            case "image/png" -> Kind.PNG;
            case "image/jpeg" -> Kind.JPEG;
            case "image/gif" -> Kind.GIF;
            case "image/webp" -> Kind.WEBP;
            case "application/zip", "application/x-zip-compressed", "application/epub+zip" -> Kind.ZIP;
            case "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint" -> Kind.OLE;
            case "application/gzip", "application/x-gzip" -> Kind.GZIP;
            default -> null;
        };
    }

    private static boolean matches(Kind kind, byte[] head) {
        return switch (kind) {
            case PDF -> starts(head, PDF);
            case PNG -> starts(head, PNG);
            case JPEG -> starts(head, JPEG);
            case GIF -> starts(head, GIF87) || starts(head, GIF89);
            case WEBP -> starts(head, RIFF) && head.length >= 12
                    && Arrays.equals(head, 8, 12, WEBP, 0, WEBP.length);
            case ZIP -> starts(head, ZIP) || starts(head, ZIP_EMPTY);
            case OLE -> starts(head, OLE);
            case GZIP -> starts(head, GZIP);
            case TEXT -> text(head);
        };
    }

    /** A text has no NUL and no control characters other than tab, line feed, form feed, carriage return and escape. */
    private static boolean text(byte[] head) {
        for (byte b : head) {
            if (b >= 0 && b < 0x20 && b != '\t' && b != '\n' && b != '\f' && b != '\r' && b != 0x1b || b == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean starts(byte[] head, byte[] signature) {
        return head.length >= signature.length && Arrays.equals(head, 0, signature.length, signature, 0,
                signature.length);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
