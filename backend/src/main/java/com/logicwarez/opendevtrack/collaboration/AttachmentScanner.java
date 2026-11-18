/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * An antivirus that looks at an attachment before it is stored (ASVS 12.4.2, ADR-24 point 8). Optional: without one,
 * files are stored unscanned. The one of OpenDevTrack is {@link ClamdScanner}.
 */
interface AttachmentScanner {

    /**
     * The name of what was found in the content, empty when it is clean.
     *
     * @throws IOException when the antivirus cannot be reached or does not answer as expected
     */
    Optional<String> scan(InputStream content) throws IOException;
}
