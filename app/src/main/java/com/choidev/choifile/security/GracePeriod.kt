package com.choidev.choifile.security

/** Choices offered in settings for the re-authentication grace period, in seconds. */
val GRACE_CHOICES = listOf(0, 5, 10, 30, 60)
const val DEFAULT_GRACE_SECONDS = 10

/**
 * Pure timer for "leave the app and come back within a few seconds without re-authenticating".
 * Shared by the app lock and the protected folders so both follow the same setting.
 *
 * [onBackground] records when the app left the foreground; [onForeground] answers whether the absence was
 * long enough that authentication must be asked again. A grace of 0 means every absence counts.
 * Absences caused by our own authentication prompt (the device-credential screen is a separate activity)
 * must not be reported; see [suspended].
 */
class GracePeriod(
    var graceMs: Long = DEFAULT_GRACE_SECONDS * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var leftAt: Long? = null

    /** While true (an authentication prompt is on screen), background/foreground events are ignored. */
    var suspended = false

    @Synchronized fun onBackground() {
        if (suspended) return
        if (leftAt == null) leftAt = clock()
    }

    /** True when the app was away at least [graceMs]; clears the record either way. */
    @Synchronized fun onForeground(): Boolean {
        if (suspended) return false
        val at = leftAt ?: return false
        leftAt = null
        val away = clock() - at
        return away < 0 || away >= graceMs
    }

    /** Forgets a pending absence, e.g. after the user authenticated anyway. */
    @Synchronized fun reset() { leftAt = null }
}
