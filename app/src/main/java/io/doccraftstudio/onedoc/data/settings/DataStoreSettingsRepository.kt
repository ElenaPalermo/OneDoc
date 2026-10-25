package io.doccraftstudio.onedoc.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.doccraftstudio.onedoc.domain.model.DocumentCategory
import io.doccraftstudio.onedoc.domain.model.LibraryPreferences
import io.doccraftstudio.onedoc.domain.model.ReaderSettings
import io.doccraftstudio.onedoc.domain.model.ReaderTheme
import io.doccraftstudio.onedoc.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val readerSettings: Flow<ReaderSettings> =
        dataStore.data.map { it.toReaderSettings() }.distinctUntilChanged()

    override val libraryPreferences: Flow<LibraryPreferences> =
        dataStore.data.map { it.toLibraryPreferences() }.distinctUntilChanged()

    override suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { prefs -> prefs.write(transform(prefs.toReaderSettings())) }
    }

    override suspend fun updateLibraryPreferences(transform: (LibraryPreferences) -> LibraryPreferences) {
        dataStore.edit { prefs -> prefs.write(transform(prefs.toLibraryPreferences())) }
    }

    private fun Preferences.toReaderSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            theme = ReaderTheme.entries.firstOrNull { it.name == this[Keys.READER_THEME] } ?: defaults.theme,
            font = enumOrDefault(this[Keys.READER_FONT], defaults.font),
            fontSizeSp = this[Keys.FONT_SIZE] ?: defaults.fontSizeSp,
            lineSpacing = this[Keys.LINE_SPACING] ?: defaults.lineSpacing,
            freezeHeaderRow = this[Keys.FREEZE_HEADER] ?: defaults.freezeHeaderRow,
        )
    }

    private fun Preferences.toLibraryPreferences(): LibraryPreferences {
        val defaults = LibraryPreferences()
        return LibraryPreferences(
            viewMode = enumOrDefault(this[Keys.VIEW_MODE], defaults.viewMode),
            sortField = enumOrDefault(this[Keys.SORT_FIELD], defaults.sortField),
            sortDescending = this[Keys.SORT_DESCENDING] ?: defaults.sortDescending,
            categoryFilter = this[Keys.CATEGORY_FILTER]
                ?.mapNotNull { name -> DocumentCategory.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: defaults.categoryFilter,
        )
    }

    private fun MutablePreferences.write(settings: ReaderSettings) {
        val theme = settings.theme
        if (theme == null) remove(Keys.READER_THEME) else this[Keys.READER_THEME] = theme.name
        this[Keys.READER_FONT] = settings.font.name
        this[Keys.FONT_SIZE] = settings.fontSizeSp
        this[Keys.LINE_SPACING] = settings.lineSpacing
        this[Keys.FREEZE_HEADER] = settings.freezeHeaderRow
    }

    private fun MutablePreferences.write(preferences: LibraryPreferences) {
        this[Keys.VIEW_MODE] = preferences.viewMode.name
        this[Keys.SORT_FIELD] = preferences.sortField.name
        this[Keys.SORT_DESCENDING] = preferences.sortDescending
        this[Keys.CATEGORY_FILTER] = preferences.categoryFilter.map { it.name }.toSet()
    }

    /** Неизвестное значение (например, после переименования enum) не должно ронять приложение. */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private object Keys {
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_FONT = stringPreferencesKey("reader_font")
        val FONT_SIZE = floatPreferencesKey("reader_font_size")
        val LINE_SPACING = floatPreferencesKey("reader_line_spacing")
        val FREEZE_HEADER = booleanPreferencesKey("sheet_freeze_header")
        val VIEW_MODE = stringPreferencesKey("library_view_mode")
        val SORT_FIELD = stringPreferencesKey("library_sort_field")
        val SORT_DESCENDING = booleanPreferencesKey("library_sort_descending")
        val CATEGORY_FILTER = stringSetPreferencesKey("library_category_filter")
    }
}

