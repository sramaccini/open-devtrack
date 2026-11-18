/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The attachments scanned by clamd, the daemon of ClamAV, over TCP with its {@code INSTREAM} command: the content in
 * chunks, each after its length, then an empty chunk; clamd answers {@code stream: OK} or
 * {@code stream: <name> FOUND}. No library. On when {@code odt.attachments.scan.clamd.host} is set; the
 * {@code StreamMaxLength} of clamd must allow {@code odt.attachments.max-size}, or every large file is refused.
 */
@Component
@ConditionalOnProperty("odt.attachments.scan.clamd.host")
class ClamdScanner implements AttachmentScanner {

    private static final int CHUNK = 64 * 1024;
    private static final int MAX_ANSWER = 1024;

    private final String host;
    private final int port;
    private final Duration timeout;

    ClamdScanner(@Value("${odt.attachments.scan.clamd.host}") String host,
                 @Value("${odt.attachments.scan.clamd.port:3310}") int port,
                 @Value("${odt.attachments.scan.clamd.timeout:30s}") Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public Optional<String> scan(InputStream content) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[CHUNK];
            for (int read = content.read(chunk); read >= 0; read = content.read(chunk)) {
                if (read > 0) {
                    out.writeInt(read);
                    out.write(chunk, 0, read);
                }
            }
            out.writeInt(0);
            out.flush();
            return verdict(answer(socket.getInputStream()));
        }
    }

    /** The answer up to its NUL (the {@code z} of the command asks for it). */
    private static String answer(InputStream in) throws IOException {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        for (int b = in.read(); b > 0; b = in.read()) {
            if (answer.size() >= MAX_ANSWER) {
                throw new IOException("clamd answered more than expected");
            }
            answer.write(b);
        }
        return answer.toString(StandardCharsets.US_ASCII).strip();
    }

    static Optional<String> verdict(String answer) throws IOException {
        if (answer.equals("stream: OK")) {
            return Optional.empty();
        }
        if (answer.startsWith("stream: ") && answer.endsWith(" FOUND")) {
            return Optional.of(answer.substring("stream: ".length(), answer.length() - " FOUND".length()));
        }
        // An error of clamd, for example "INSTREAM size limit exceeded. ERROR"
        throw new IOException("clamd: " + answer);
    }
}
