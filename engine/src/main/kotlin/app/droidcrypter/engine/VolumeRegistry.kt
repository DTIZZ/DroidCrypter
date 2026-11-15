package app.droidcrypter.engine

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide, in-RAM registry of unlocked [MountedVolume]s with change listeners and
 * before-close hooks (run on the locking thread just before a volume closes, while it can still be
 * written; hook failures never block the lock). If the process dies, everything is locked.
 */
object VolumeRegistry {
    private val volumes = LinkedHashMap<String, MountedVolume>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val changeListeners = CopyOnWriteArrayList<(List<MountedVolume>) -> Unit>()
    private val beforeCloseHooks = CopyOnWriteArrayList<(MountedVolume) -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun addChangeListener(listener: (List<MountedVolume>) -> Unit) {
        changeListeners.addIfAbsent(listener)
    }

    fun removeChangeListener(listener: (List<MountedVolume>) -> Unit) {
        changeListeners.remove(listener)
    }

    fun addBeforeCloseHook(hook: (MountedVolume) -> Unit) {
        beforeCloseHooks.addIfAbsent(hook)
    }

    fun removeBeforeCloseHook(hook: (MountedVolume) -> Unit) {
        beforeCloseHooks.remove(hook)
    }

    fun mount(volume: MountedVolume) {
        synchronized(volumes) {
            volumes[volume.id] = volume
        }
        notifyListeners()
    }

    fun get(id: String): MountedVolume? = synchronized(volumes) { volumes[id] }

    /** Returns all unlocked volumes sorted case-insensitively by [MountedVolume.label]. */
    fun all(): List<MountedVolume> = synchronized(volumes) {
        volumes.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun unmount(id: String): Boolean {
        val vol = synchronized(volumes) { volumes.remove(id) } ?: return false
        runBeforeCloseAndClose(vol)
        notifyListeners()
        return true
    }

    fun unmountAll() {
        val removed = synchronized(volumes) {
            val list = volumes.values.toList()
            volumes.clear()
            list
        }
        if (removed.isEmpty()) return
        for (v in removed) {
            runBeforeCloseAndClose(v)
        }
        notifyListeners()
    }

    private fun runBeforeCloseAndClose(volume: MountedVolume) {
        for (hook in beforeCloseHooks) {
            try {
                hook(volume)
            } catch (_: Throwable) {
                // Hook failures must never block locking.
            }
        }
        try {
            volume.close()
        } catch (_: Throwable) {
        }
    }

    private fun notifyListeners() {
        for (l in listeners) {
            try {
                l()
            } catch (_: Throwable) {
            }
        }
        val snapshot = all()
        for (cl in changeListeners) {
            try {
                cl(snapshot)
            } catch (_: Throwable) {
            }
        }
    }
}

