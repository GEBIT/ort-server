/*
 * Copyright (C) 2026 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import org.apache.ivy.util.AbstractMessageLogger
import org.apache.ivy.util.Message

import org.slf4j.LoggerFactory

/** A message logger that routes Ivy's output to SLF4J so that nothing is written to stdout. */
internal class IvyMessageLogger : AbstractMessageLogger() {
    private val logger = LoggerFactory.getLogger("Ivy")

    override fun log(msg: String?, level: Int) {
        when (level) {
            Message.MSG_ERR -> logger.error(msg)
            Message.MSG_WARN -> logger.warn(msg)
            Message.MSG_INFO -> logger.info(msg)
            else -> logger.debug(msg)
        }
    }

    override fun rawlog(msg: String?, level: Int) = log(msg, level)

    override fun doProgress() = Unit

    override fun doEndProgress(msg: String?) = Unit
}
