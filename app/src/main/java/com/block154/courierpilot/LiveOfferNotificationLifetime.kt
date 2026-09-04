package com.block154.courierpilot

/**
 * Process-local lifetime of structurally verified courier offer notifications. The live advisor
 * uses this only as positive evidence during weak Wolt Compose gaps; strong lifecycle evidence still wins.
 */
internal object LiveOfferNotificationLifetime {
    private data class Key(val packageName: String, val notificationKey: String)
    private val active = linkedSetOf<Key>()

    @Synchronized
    fun markActive(packageName: String, notificationKey: String) {
        if (packageName.isBlank() || notificationKey.isBlank() || notificationKey.startsWith("screen:")) return
        active += Key(packageName, notificationKey)
    }

    @Synchronized
    fun markRemoved(packageName: String, notificationKey: String) {
        active -= Key(packageName, notificationKey)
    }

    @Synchronized
    fun isActive(packageName: String, notificationKey: String): Boolean =
        packageName.isNotBlank() && notificationKey.isNotBlank() &&
            Key(packageName, notificationKey) in active

    @Synchronized
    fun replaceActive(entries: Collection<Pair<String, String>>) {
        active.clear()
        entries.forEach { (packageName, notificationKey) ->
            if (packageName.isNotBlank() && notificationKey.isNotBlank() && !notificationKey.startsWith("screen:")) {
                active += Key(packageName, notificationKey)
            }
        }
    }

    @Synchronized
    internal fun clearForTest() = active.clear()
}
