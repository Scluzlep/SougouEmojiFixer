package com.king250.sougou.patch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

class UnicodeVersionRepository {
    suspend fun fetchStableVersions(): List<UnicodeVersionOption> {
        val client = HttpClient(CIO) {
            expectSuccess = true
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 20_000
                requestTimeoutMillis = 30_000
            }
        }
        return try {
            val publicIndex = client.get(EmojiCatalog.UNICODE_PUBLIC_URL) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.body<String>()
            val latestManifest = client.get(EmojiCatalog.UNICODE_LATEST_EMOJI_TEST_URL) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.body<String>()
            parseStableVersions(publicIndex, EmojiCatalog.emojiVersion(latestManifest))
        } finally {
            client.close()
        }
    }

    companion object {
        private const val USER_AGENT = "SogouInputEmojiFixer/1.0"
        private val FOLDER_LINK = Regex("href=[\"'](\\d+\\.\\d+\\.\\d+)/[\"']")

        fun parseStableVersions(
            publicIndex: String,
            latestEmojiVersion: String,
        ): List<UnicodeVersionOption> {
            val latest = Version.parse(latestEmojiVersion)
            return FOLDER_LINK.findAll(publicIndex)
                .map { it.groupValues[1] }
                .distinct()
                .mapNotNull { folder ->
                    val version = Version.parseOrNull(folder) ?: return@mapNotNull null
                    if (version.major < 11 || version > latest) return@mapNotNull null
                    UnicodeVersionOption(
                        unicodeFolder = folder,
                        emojiVersion = "${version.major}.${version.minor}",
                    )
                }
                .sortedWith(
                    compareByDescending<UnicodeVersionOption> {
                        Version.parse(it.unicodeFolder)
                    },
                )
                .toList()
        }

        private data class Version(
            val major: Int,
            val minor: Int,
            val patch: Int,
        ) : Comparable<Version> {
            override fun compareTo(other: Version): Int =
                compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

            companion object {
                fun parse(value: String): Version = parseOrNull(value)
                    ?: error("Invalid version: $value")

                fun parseOrNull(value: String): Version? {
                    val components = value.split('.')
                    if (components.size !in 2..3) return null
                    val numbers = components.map { it.toIntOrNull() ?: return null }
                    return Version(numbers[0], numbers[1], numbers.getOrElse(2) { 0 })
                }
            }
        }
    }
}
