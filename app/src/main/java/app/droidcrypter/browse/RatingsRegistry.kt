package app.droidcrypter.browse

import app.droidcrypter.engine.MountedVolume
import app.droidcrypter.engine.VolumeRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of one [RatingsStore] per unlocked [MountedVolume.id], ensuring
 * the in-app browser and [app.droidcrypter.provider.VaultDocumentsProvider] share ratings state.
 */
object RatingsRegistry {
    private val stores = ConcurrentHashMap<String, RatingsStore>()

    init {
        VolumeRegistry.addChangeListener { volumes ->
            val activeIds = volumes.map { it.id }.toSet()
            val iter = stores.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key !in activeIds) {
                    iter.remove()
                    try {
                        entry.value.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    fun getOrCreate(volume: MountedVolume): RatingsStore =
        stores.computeIfAbsent(volume.id) { RatingsStore(volume) }

    fun get(volumeId: String): RatingsStore? = stores[volumeId]
}

