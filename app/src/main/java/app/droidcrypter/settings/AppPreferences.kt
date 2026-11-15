package app.droidcrypter.settings

import android.content.Context
import android.content.SharedPreferences
import app.droidcrypter.browse.SortConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

data class SavedContainer(
    val uriString: String,
    val id: String = AppPreferences.containerIdForUri(uriString),
)

data class AppSettings(
    val containers: List<SavedContainer> = emptyList(),
    val lockOnScreenOff: Boolean = true,
    val showStars: Boolean = true,
    val sortConfig: SortConfig = SortConfig.DEFAULT,
)

/**
 * Stores ONLY container URIs, lock-on-screen-off (default ON), show-stars (default ON),
 * and sort order (default NAME:ASCENDING) — section 4.1.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        val rawUris = prefs.getString(KEY_CONTAINER_URIS, "") ?: ""
        val uris = rawUris.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { SavedContainer(it) }
        val lockOnScreenOff = prefs.getBoolean(KEY_LOCK_ON_SCREEN_OFF, true)
        val showStars = prefs.getBoolean(KEY_SHOW_STARS, true)
        val sortConfig = SortConfig.decode(prefs.getString(KEY_SORT_ORDER, SortConfig.DEFAULT.encode()))
        return AppSettings(
            containers = uris,
            lockOnScreenOff = lockOnScreenOff,
            showStars = showStars,
            sortConfig = sortConfig,
        )
    }

    @Synchronized
    fun addContainerUri(uriString: String): SavedContainer {
        val trimmed = uriString.trim()
        require(trimmed.isNotEmpty())
        val current = _settings.value.containers.map { it.uriString }.toMutableList()
        if (trimmed !in current) {
            current.add(trimmed)
            prefs.edit().putString(KEY_CONTAINER_URIS, current.joinToString("\n")).apply()
            _settings.value = load()
        }
        return SavedContainer(trimmed)
    }

    @Synchronized
    fun removeContainerUri(uriString: String) {
        val current = _settings.value.containers.map { it.uriString }.filter { it != uriString }
        prefs.edit().putString(KEY_CONTAINER_URIS, current.joinToString("\n")).apply()
        _settings.value = load()
    }

    @Synchronized
    fun setLockOnScreenOff(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_ON_SCREEN_OFF, enabled).apply()
        _settings.value = load()
    }

    @Synchronized
    fun setShowStars(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_STARS, enabled).apply()
        _settings.value = load()
    }

    @Synchronized
    fun setSortConfig(config: SortConfig) {
        prefs.edit().putString(KEY_SORT_ORDER, config.encode()).apply()
        _settings.value = load()
    }

    companion object {
        private const val PREFS_NAME = "droidcrypter_prefs"
        private const val KEY_CONTAINER_URIS = "container_uris"
        private const val KEY_LOCK_ON_SCREEN_OFF = "lock_on_screen_off"
        private const val KEY_SHOW_STARS = "show_stars"
        private const val KEY_SORT_ORDER = "sort_order"

        /**
         * Stable container ID = lowercase hex of the first 12 bytes of SHA-256 of the URI string (24 hex chars).
         */
        fun containerIdForUri(uriString: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uriString.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(24)
            for (i in 0 until 12) {
                val b = digest[i].toInt() and 0xFF
                sb.append("0123456789abcdef"[b ushr 4])
                sb.append("0123456789abcdef"[b and 0x0F])
            }
            return sb.toString()
        }
    }
}

