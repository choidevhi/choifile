package com.choidev.choifile.security

import android.content.Context
import com.choidev.choifile.storage.SortKey
import com.choidev.choifile.util.VideoEnd
import com.choidev.choifile.util.VideoFit
import com.choidev.choifile.util.VideoLeave

/** How a tapped .txt opens: ask every time (the default), or the choice the user pinned. */
enum class TxtOpen { ASK, TEXT, BOOK }

/** Small SharedPreferences wrapper for everything that must survive a restart. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("choifile_prefs", Context.MODE_PRIVATE)

    var tabs: List<SavedTab>
        get() = TabsCodec.decode(sp.getString("tabs", null))
        set(v) = sp.edit().putString("tabs", TabsCodec.encode(v)).apply()
    var currentTab: Int
        get() = sp.getInt("current_tab", 0)
        set(v) = sp.edit().putInt("current_tab", v).apply()
    var sort: SortKey
        get() = runCatching { SortKey.valueOf(sp.getString("sort", "NAME")!!) }.getOrDefault(SortKey.NAME)
        set(v) = sp.edit().putString("sort", v.name).apply()
    var ascending: Boolean
        get() = sp.getBoolean("ascending", true)
        set(v) = sp.edit().putBoolean("ascending", v).apply()
    var foldersFirst: Boolean
        get() = sp.getBoolean("folders_first", true)
        set(v) = sp.edit().putBoolean("folders_first", v).apply()
    var showHidden: Boolean
        get() = sp.getBoolean("show_hidden", false)
        set(v) = sp.edit().putBoolean("show_hidden", v).apply()

    /** Video player choices that carry over from one video to the next. */
    var videoEnd: VideoEnd
        get() = runCatching { VideoEnd.valueOf(sp.getString("video_end", "NEXT")!!) }.getOrDefault(VideoEnd.NEXT)
        set(v) = sp.edit().putString("video_end", v.name).apply()
    var videoFit: VideoFit
        get() = runCatching { VideoFit.valueOf(sp.getString("video_fit", "FIT")!!) }.getOrDefault(VideoFit.FIT)
        set(v) = sp.edit().putString("video_fit", v.name).apply()
    var videoStep: Int
        get() = sp.getInt("video_step", 10)
        set(v) = sp.edit().putInt("video_step", v).apply()
    /** Subtitle text size as a multiple of the default. */
    var subtitleScale: Float
        get() = sp.getFloat("subtitle_scale", 1f)
        set(v) = sp.edit().putFloat("subtitle_scale", v).apply()
    /** Index into the subtitle colors (white, yellow, cyan). */
    var subtitleColor: Int
        get() = sp.getInt("subtitle_color", 0)
        set(v) = sp.edit().putInt("subtitle_color", v).apply()
    /** Subtitles on a dark box instead of an outline. */
    var subtitleBox: Boolean
        get() = sp.getBoolean("subtitle_box", false)
        set(v) = sp.edit().putBoolean("subtitle_box", v).apply()
    /** What a video does when the app is left; null until the user answered the question on first playback. */
    var videoLeave: VideoLeave?
        get() = sp.getString("video_leave", null)?.let { v -> VideoLeave.entries.firstOrNull { it.name == v } }
        set(v) = sp.edit().putString("video_leave", v?.name).apply()
    /** Decode video with FFmpeg first instead of the device's hardware decoders. */
    var preferSoftware: Boolean
        get() = sp.getBoolean("video_prefer_software", false)
        set(v) = sp.edit().putBoolean("video_prefer_software", v).apply()

    var txtOpen: TxtOpen
        get() = sp.getString("txt_open", null)?.let { v -> TxtOpen.entries.firstOrNull { it.name == v } } ?: TxtOpen.ASK
        set(v) = sp.edit().putString("txt_open", v.name).apply()

    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit().putBoolean("app_lock", v).apply()
    /** Protected-folder unlock duration in seconds; 0 = re-lock immediately. */
    var relockSeconds: Int
        get() = sp.getInt("relock_seconds", 60)
        set(v) = sp.edit().putInt("relock_seconds", v).apply()
    /** Seconds the app may spend in the background before the app lock / protected folders ask again. */
    var graceSeconds: Int
        get() = sp.getInt("grace_seconds", DEFAULT_GRACE_SECONDS)
        set(v) = sp.edit().putInt("grace_seconds", v).apply()

    /** JSON written by AssociationCodec: default app per file extension. */
    var associations: String
        get() = sp.getString("associations", "") ?: ""
        set(v) = sp.edit().putString("associations", v).apply()

    var protectedFolders: Set<String>
        get() = sp.getStringSet("protected_folders", emptySet()).orEmpty().toSet()
        set(v) = sp.edit().putStringSet("protected_folders", v).apply()

    /** File-server settings. The servers themselves never start automatically; only these choices are remembered. */
    var serverRoot: String
        get() = sp.getString("server_root", "") ?: ""
        set(v) = sp.edit().putString("server_root", v).apply()
    var httpPort: Int
        get() = sp.getInt("http_port", 8080)
        set(v) = sp.edit().putInt("http_port", v).apply()
    var ftpPort: Int
        get() = sp.getInt("ftp_port", 2121)
        set(v) = sp.edit().putInt("ftp_port", v).apply()
    var serverUpload: Boolean
        get() = sp.getBoolean("server_upload", true)
        set(v) = sp.edit().putBoolean("server_upload", v).apply()

    /** JSON written by SavedSearchCodec. */
    var savedSearches: String
        get() = sp.getString("saved_searches", "") ?: ""
        set(v) = sp.edit().putString("saved_searches", v).apply()
}
