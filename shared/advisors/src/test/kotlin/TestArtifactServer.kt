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
package org.eclipse.apoapsis.ortserver.shared.advisors

import com.sun.net.httpserver.HttpServer

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A local HTTP server that serves artifacts from memory and counts requests. */
class TestArtifactServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val artifacts = ConcurrentHashMap<String, ByteArray>()

    val requestCount = AtomicInteger()

    /** If set, requests must carry exactly this `Authorization` header value, otherwise they get a 401. */
    @Volatile
    var requiredAuthorization: String? = null

    init {
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()

            val body = artifacts[exchange.requestURI.path]
            val authorized = requiredAuthorization?.let { exchange.requestHeaders.getFirst("Authorization") == it }
                ?: true

            when {
                !authorized -> exchange.sendResponseHeaders(401, -1)

                body == null -> exchange.sendResponseHeaders(404, -1)

                else -> {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }

            exchange.close()
        }

        server.start()
    }

    /** Serve [content] at [path] and return the full URL. */
    fun serve(path: String, content: ByteArray): String {
        artifacts[path] = content
        return url(path)
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    override fun close() = server.stop(0)
}
