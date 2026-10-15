package com.choidev.choifile

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.ArchiveLocation
import com.choidev.choifile.remote.RemoteLocation
import com.choidev.choifile.search.IndexWorker
import com.choidev.choifile.security.Authenticator
import com.choidev.choifile.security.GracePeriod
import com.choidev.choifile.security.TxtOpen
import com.choidev.choifile.ebook.EBOOK_EXTENSIONS
import com.choidev.choifile.ebook.opensAsBook
import com.choidev.choifile.ui.TxtOpenDialog
import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.ui.ComicScreen
import com.choidev.choifile.ui.ConnectionsScreen
import com.choidev.choifile.ui.HostKeyDialog
import com.choidev.choifile.ui.AnalysisScreen
import com.choidev.choifile.ui.AudioPlayerScreen
import com.choidev.choifile.playback.AudioPlayback
import com.choidev.choifile.ui.DuplicatesScreen
import com.choidev.choifile.ui.OpenWithSheet
import com.choidev.choifile.ui.OperationsScreen
import com.choidev.choifile.ui.RulesScreen
import com.choidev.choifile.ui.ServerScreen
import com.choidev.choifile.ui.ToolScreen
import com.choidev.choifile.ui.SearchScreen
import com.choidev.choifile.ui.BrowserScreen
import com.choidev.choifile.ui.ChoiTheme
import com.choidev.choifile.ui.LocalAuthenticator
import com.choidev.choifile.ui.LockedView
import com.choidev.choifile.ui.PermissionScreen
import com.choidev.choifile.ui.SettingsScreen
import com.choidev.choifile.ui.VaultScreen
import com.choidev.choifile.ui.ViewerScreen
import com.choidev.choifile.ui.rememberRequestAuth
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.OpenChoice
import com.choidev.choifile.util.extensionKey
import com.choidev.choifile.util.hasAllFilesAccess
import com.choidev.choifile.util.installApk
import com.choidev.choifile.util.isApk
import com.choidev.choifile.util.kindOf
import com.choidev.choifile.util.launchWith
import com.choidev.choifile.util.openWithOtherApp
import java.io.File

private enum class Screen { BROWSER, SETTINGS, VAULT, SEARCH, CONNECTIONS, OPERATIONS, ANALYSIS, DUPLICATES, RULES, SERVER }

/** Biometric prompts need a FragmentActivity (ComponentActivity is not enough). */
class MainActivity : FragmentActivity() {
    /** Set by the e-reader while a book is open: volume keys turn pages (true = volume down = next page). */
    var volumeKeyHandler: ((Boolean) -> Boolean)? = null

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val handler = volumeKeyHandler
        if (handler != null && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            if (handler(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (volumeKeyHandler != null && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    // Below Android 12 the system cannot enter picture-in-picture on its own; a playing video asks for it here.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < 31 && com.choidev.choifile.ui.VideoPip.autoEnter) com.choidev.choifile.ui.VideoPip.enter(this)
    }

    private val viewModel: BrowserViewModel by viewModels()
    private lateinit var authenticator: Authenticator
    private val grace = GracePeriod(clock = SystemClock::elapsedRealtime)

    // Deliberately not saved across process death: these must never restore into an unlocked state.
    private var appLocked by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.BROWSER)
    private var tempViewing by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authenticator = Authenticator(this)
        authenticator.onPromptActive = { grace.suspended = it }
        grace.graceMs = viewModel.prefs.graceSeconds * 1000L
        appLocked = viewModel.prefs.appLock
        viewModel.viewCacheDir().deleteRecursively() // leftovers of decrypted copies after a crash or kill
        IndexWorker.schedule(this)
        // Thumbnails: Coil decodes images and, with the video decoder, a frame of each video. Sized to the cell,
        // cancelled when the cell leaves the screen, cached in the app's own cache (cleared by Settings > 캐시 삭제).
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build()
        }
        enableEdgeToEdge()
        setContent {
            ChoiTheme {
                CompositionLocalProvider(LocalAuthenticator provides authenticator) {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { Root() }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val context = LocalContext.current
        val requestAuth = rememberRequestAuth()
        var granted by remember { mutableStateOf(hasAllFilesAccess(context)) }
        var viewing by rememberSaveable { mutableStateOf<String?>(null) }
        // For a .txt: true opens it in the e-reader, false in the text viewer, null as the setting says.
        var viewingAsBook by rememberSaveable { mutableStateOf<Boolean?>(null) }
        // A .txt waiting for the "text viewer or e-reader?" answer.
        var askTxt by rememberSaveable { mutableStateOf<String?>(null) }
        var comic by rememberSaveable { mutableStateOf<String?>(null) }
        var openWith by rememberSaveable { mutableStateOf<String?>(null) }
        var appLockOn by remember { mutableStateOf(viewModel.prefs.appLock) }
        var relock by remember { mutableIntStateOf(viewModel.prefs.relockSeconds) }
        var graceSec by remember { mutableIntStateOf(viewModel.prefs.graceSeconds) }
        val protectedPaths by viewModel.protectedPaths.collectAsStateWithLifecycle()
        val associations by viewModel.associations.collectAsStateWithLifecycle()
        val hostKeyRequest by viewModel.hostKeyRequest.collectAsStateWithLifecycle()

        fun isComicArchive(name: String) = extensionKey(name) == "cbz"
        fun hasBuiltIn(name: String) = kindOf(File(name)) != FileKind.OTHER || ArchiveFormat.detect(name) != null
        // Music plays in the app-wide player, so it keeps going while the user browses; this shows its screen.
        var nowPlaying by rememberSaveable { mutableStateOf(false) }

        // choifile's own way of opening a local file: viewer, comic reader for .cbz, folder view for archives.
        fun openBuiltIn(path: String, asBook: Boolean? = null) {
            val name = File(path).name
            when {
                extensionKey(name) in EBOOK_EXTENSIONS -> {
                    val book = asBook ?: opensAsBook(viewModel.prefs.txtOpen)
                    if (book == null) askTxt = path else {
                        viewingAsBook = book
                        viewing = path
                    }
                }
                isComicArchive(name) -> comic = path
                ArchiveFormat.detect(name) != null -> viewModel.openDir(ArchiveLocation.make(path))
                kindOf(File(name)) == FileKind.AUDIO -> {
                    AudioPlayback.play(context, File(path))
                    nowPlaying = true
                }
                else -> {
                    viewingAsBook = null
                    viewing = path
                }
            }
        }

        // Local files follow the remembered default for their extension (Settings > 파일 연결); files on servers
        // or inside archives open from a temporary copy in the built-in viewer.
        val openFile: (FileItem) -> Unit = { item ->
            if (ArchiveLocation.isArchive(item.path) || RemoteLocation.isRemote(item.path)) {
                viewModel.openVirtualFile(item)
            } else if (isApk(File(item.path))) {
                installApk(context, File(item.path))
            } else {
                val builtIn = hasBuiltIn(item.name)
                when (val choice = viewModel.associationFor(item.name)) {
                    OpenChoice.BuiltIn -> if (builtIn) openBuiltIn(item.path) else openWith = item.path
                    OpenChoice.Ask -> openWith = item.path
                    is OpenChoice.App -> if (!launchWith(context, File(item.path), choice.packageName, choice.activity)) {
                        // The app is gone or no longer takes this file: forget the default and ask again.
                        viewModel.clearAssociation(extensionKey(item.name))
                        Toast.makeText(context, R.string.ow_app_missing, Toast.LENGTH_SHORT).show()
                        openWith = item.path
                    }
                    null -> if (builtIn) openBuiltIn(item.path) else openWith = item.path
                }
            }
            if (screen != Screen.BROWSER) screen = Screen.BROWSER
        }

        // With app lock on, hide the content in the recents screen and block screenshots.
        SideEffect {
            if (appLockOn) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        // The user returns from the system settings screen, so re-check on every resume.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasAllFilesAccess(context) }
        val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasAllFilesAccess(context)
        }

        val temp = tempViewing
        val view = viewing
        val comicPath = comic
        when {
            appLocked -> LockedView(R.string.auth_subtitle_app) {
                requestAuth(R.string.auth_subtitle_app, false) { appLocked = false; grace.reset() }
            }
            !granted -> PermissionScreen {
                legacyLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
            temp != null -> ViewerScreen(File(temp), temporary = true) { closeTemp() }
            view != null && !viewModel.isLocked(view) -> key(view, viewingAsBook) { ViewerScreen(File(view), asBook = viewingAsBook, onOpenWith = { openWith = it.path }) { viewing = null } }
            comicPath != null && !viewModel.isLocked(comicPath) -> ComicScreen(comicPath) { comic = null }
            nowPlaying -> AudioPlayerScreen { nowPlaying = false }
            screen == Screen.SETTINGS -> SettingsScreen(
                appLock = appLockOn,
                onAppLock = { on ->
                    // Turning it on is strict: it must be possible to authenticate, or we would lock the user out.
                    requestAuth(R.string.auth_subtitle_change, on) {
                        viewModel.prefs.appLock = on
                        appLockOn = on
                    }
                },
                graceSeconds = graceSec,
                onGrace = { s ->
                    graceSec = s
                    viewModel.prefs.graceSeconds = s
                    grace.graceMs = s * 1000L
                },
                relockSeconds = relock,
                onRelock = { relock = it; viewModel.setRelockSeconds(it) },
                protectedPaths = protectedPaths,
                rootPath = viewModel.rootPath,
                onUnprotect = { p -> requestAuth(R.string.auth_subtitle_change, false) { viewModel.setProtected(p, false) } },
                associations = associations,
                onClearAssociation = viewModel::clearAssociation,
                onClearAllAssociations = viewModel::clearAllAssociations,
                onClearCache = { viewModel.clearCache() },
                onBack = { screen = Screen.BROWSER },
            )
            screen == Screen.VAULT -> VaultScreen(viewModel) { screen = Screen.BROWSER }
            screen == Screen.SEARCH -> SearchScreen(
                viewModel, viewModel.rootPath,
                onOpenFolder = { viewModel.openDir(it); screen = Screen.BROWSER },
                onOpenFile = openFile,
                onBack = { screen = Screen.BROWSER },
            )
            screen == Screen.CONNECTIONS -> ConnectionsScreen(viewModel, onOpened = { screen = Screen.BROWSER }, onBack = { screen = Screen.BROWSER })
            screen == Screen.OPERATIONS -> OperationsScreen(viewModel) { screen = Screen.BROWSER }
            screen == Screen.ANALYSIS -> AnalysisScreen(viewModel, onOpenFolder = { viewModel.openDir(it); screen = Screen.BROWSER }, onBack = { screen = Screen.BROWSER })
            screen == Screen.DUPLICATES -> DuplicatesScreen(viewModel) { screen = Screen.BROWSER }
            screen == Screen.RULES -> RulesScreen(viewModel, onOpenLog = { screen = Screen.OPERATIONS }, onBack = { screen = Screen.BROWSER })
            screen == Screen.SERVER -> ServerScreen(viewModel) { screen = Screen.BROWSER }
            else -> BrowserScreen(
                viewModel,
                onOpenFile = openFile,
                onOpenTemp = { tempViewing = it },
                onOpenVault = { requestAuth(R.string.auth_subtitle_hidden, false) { screen = Screen.VAULT } },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenSearch = { screen = Screen.SEARCH },
                onOpenConnections = { screen = Screen.CONNECTIONS },
                onOpenComic = { comic = it },
                onOpenWith = { openWith = it },
                onOpenNowPlaying = { nowPlaying = true },
                onOpenTool = { screen = when (it) {
                    ToolScreen.OPERATIONS -> Screen.OPERATIONS
                    ToolScreen.ANALYSIS -> Screen.ANALYSIS
                    ToolScreen.DUPLICATES -> Screen.DUPLICATES
                    ToolScreen.RULES -> Screen.RULES
                    ToolScreen.SERVER -> Screen.SERVER
                } },
            )
        }

        // "Open with" sheet, above whatever screen asked for it.
        val owPath = openWith
        if (owPath != null && !appLocked) {
            val f = File(owPath)
            val ext = extensionKey(f.name)
            val txt = ext in EBOOK_EXTENSIONS
            OpenWithSheet(
                file = f,
                builtInLabel = when {
                    isComicArchive(f.name) -> R.string.ow_builtin_comic
                    ArchiveFormat.detect(f.name) != null -> R.string.ow_builtin_archive
                    kindOf(f) != FileKind.OTHER -> R.string.ow_builtin
                    else -> null
                },
                current = viewModel.associationFor(f.name),
                onBuiltIn = { remember ->
                    if (remember && ext.isNotEmpty()) viewModel.setAssociation(ext, OpenChoice.BuiltIn)
                    if (remember && txt) viewModel.prefs.txtOpen = TxtOpen.TEXT
                    openWith = null
                    if (viewing != owPath || txt) openBuiltIn(owPath, asBook = if (txt) false else null)
                },
                onBook = if (!txt) null else { remember ->
                    if (remember) {
                        viewModel.setAssociation(ext, OpenChoice.BuiltIn)
                        viewModel.prefs.txtOpen = TxtOpen.BOOK
                    }
                    openWith = null
                    openBuiltIn(owPath, asBook = true)
                },
                bookDefault = txt && viewModel.prefs.txtOpen == TxtOpen.BOOK,
                onApp = { app, remember ->
                    if (remember && ext.isNotEmpty()) viewModel.setAssociation(ext, OpenChoice.App(app.packageName, app.activity, app.label))
                    openWith = null
                    if (!launchWith(context, f, app.packageName, app.activity)) Toast.makeText(context, R.string.msg_no_app, Toast.LENGTH_SHORT).show()
                },
                onAlwaysAsk = {
                    viewModel.setAssociation(ext, OpenChoice.Ask)
                    Toast.makeText(context, context.getString(R.string.ow_set_ask, ext), Toast.LENGTH_SHORT).show()
                },
                onSystemChooser = { openWith = null; openWithOtherApp(context, f) },
                onDismiss = { openWith = null },
            )
        }

        askTxt?.let { path ->
            TxtOpenDialog(
                File(path),
                onPick = { book, pin ->
                    if (pin) {
                        viewModel.prefs.txtOpen = if (book) TxtOpen.BOOK else TxtOpen.TEXT
                        Toast.makeText(context, R.string.txt_pinned, Toast.LENGTH_SHORT).show()
                    }
                    askTxt = null
                    openBuiltIn(path, asBook = book)
                },
                onDismiss = { askTxt = null },
            )
        }

        // Shown above every screen: a connection attempt is waiting for the user's decision.
        hostKeyRequest?.let { HostKeyDialog(it) }
    }

    private fun closeTemp() {
        tempViewing = null
        viewModel.viewCacheDir().deleteRecursively()
    }

    override fun onStart() {
        super.onStart()
        // Within the grace period (settings) the user comes back without authenticating again; this covers the
        // share sheet, "open with" and quick app switches. Our own PIN screen is excluded (see Authenticator).
        val expired = grace.onForeground()
        if (expired && viewModel.prefs.appLock) appLocked = true
        if (expired && screen == Screen.VAULT) screen = Screen.BROWSER // the hidden list needs a fresh authentication
        viewModel.onForeground(expired)
    }

    override fun onStop() {
        super.onStop()
        grace.onBackground()
        if (tempViewing != null) closeTemp() // decrypted copies never outlive the foreground, grace or not
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) viewModel.viewCacheDir().deleteRecursively()
    }
}
