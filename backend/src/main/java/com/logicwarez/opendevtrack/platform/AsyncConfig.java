/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** The listeners of the modules ({@code @ApplicationModuleListener}) run after the commit, on the task executor. */
@Configuration
@EnableAsync
class AsyncConfig {
}
