package com.king250.sougou.patch

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.emojiPatchDataStore by preferencesDataStore(
    name = "emoji_patch_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "emoji_patch_settings"))
    },
)

class AppSettingsStore(context: Context) {
    private val dataStore = context.emojiPatchDataStore

    val configuration: Flow<PatchConfiguration> = dataStore.data.map { preferences ->
        runCatching {
            PatchConfiguration(
                unicodeFolder = preferences[KEY_UNICODE_FOLDER] ?: DEFAULT.unicodeFolder,
                emojiVersion = preferences[KEY_EMOJI_VERSION] ?: DEFAULT.emojiVersion,
                includeNonFullyQualified = preferences[KEY_INCLUDE_NON_FULLY_QUALIFIED]
                    ?: DEFAULT.includeNonFullyQualified,
                includeUnsupportedSystemEmoji = preferences[KEY_INCLUDE_UNSUPPORTED]
                    ?: DEFAULT.includeUnsupportedSystemEmoji,
                notoDownloadSource = preferences[KEY_NOTO_SOURCE]
                    ?.let { stored -> NotoDownloadSource.entries.firstOrNull { it.name == stored } }
                    ?: DEFAULT.notoDownloadSource,
                groupOrder = preferences[KEY_GROUP_ORDER]
                    ?.split(',')
                    ?.mapNotNull(String::toIntOrNull)
                    ?.let(::normalizeOrder)
                    ?: DEFAULT.groupOrder,
            )
        }.getOrDefault(DEFAULT)
    }

    suspend fun save(configuration: PatchConfiguration) {
        dataStore.edit { preferences ->
            preferences[KEY_UNICODE_FOLDER] = configuration.unicodeFolder
            preferences[KEY_EMOJI_VERSION] = configuration.emojiVersion
            preferences[KEY_INCLUDE_NON_FULLY_QUALIFIED] =
                configuration.includeNonFullyQualified
            preferences[KEY_INCLUDE_UNSUPPORTED] =
                configuration.includeUnsupportedSystemEmoji
            preferences[KEY_NOTO_SOURCE] = configuration.notoDownloadSource.name
            preferences[KEY_GROUP_ORDER] = configuration.groupOrder.joinToString(",")
        }
    }

    suspend fun hasExplicitVersion(): Boolean =
        dataStore.data.map { it[KEY_VERSION_SELECTED] ?: false }.first()

    suspend fun markVersionSelected(explicit: Boolean) {
        dataStore.edit { it[KEY_VERSION_SELECTED] = explicit }
    }

    suspend fun cachedVersions(): List<UnicodeVersionOption> =
        dataStore.data.map { preferences ->
            preferences[KEY_CACHED_VERSIONS]
                ?.split('|')
                ?.mapNotNull { encoded ->
                    val values = encoded.split(':', limit = 2)
                    if (values.size == 2) UnicodeVersionOption(values[0], values[1]) else null
                }
                .orEmpty()
        }.first()

    suspend fun cacheVersions(versions: List<UnicodeVersionOption>) {
        dataStore.edit { preferences ->
            preferences[KEY_CACHED_VERSIONS] =
                versions.joinToString("|") { "${it.unicodeFolder}:${it.emojiVersion}" }
        }
    }

    private companion object {
        val DEFAULT = PatchConfiguration()

        /**
         * Accepts a stored order that covers exactly the installable groups
         * (e.g. saved before Sogou's generated combination package was added
         * to ordering) and appends the missing orderable ids, preserving the
         * user's previous arrangement.
         */
        private fun normalizeOrder(stored: List<Int>): List<Int>? {
            val validIds = EmojiCatalog.orderGroups.map(EmojiGroup::packageId)
            if (stored.size == validIds.size && stored.toSet() == validIds.toSet()) {
                return stored
            }
            val installableIds = EmojiCatalog.groups.map(EmojiGroup::packageId)
            if (stored.size == installableIds.size &&
                stored.toSet() == installableIds.toSet()
            ) {
                return stored + (validIds - stored.toSet())
            }
            return null
        }

        val KEY_UNICODE_FOLDER = stringPreferencesKey("unicode_folder")
        val KEY_EMOJI_VERSION = stringPreferencesKey("emoji_version")
        val KEY_INCLUDE_NON_FULLY_QUALIFIED =
            booleanPreferencesKey("include_non_fully_qualified")
        val KEY_INCLUDE_UNSUPPORTED = booleanPreferencesKey("include_unsupported")
        val KEY_NOTO_SOURCE = stringPreferencesKey("noto_source")
        val KEY_GROUP_ORDER = stringPreferencesKey("group_order")
        val KEY_VERSION_SELECTED = booleanPreferencesKey("version_selected")
        val KEY_CACHED_VERSIONS = stringPreferencesKey("cached_versions")
    }
}
