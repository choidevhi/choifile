package com.choidev.choifile

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.ArchiveLocation
import com.choidev.choifile.remote.RemoteLocation
import com.choidev.choifile.search.IndexWorker
import com.choidev.choifile.security.Authenticator
import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.ui.ConnectionsScreen
import com.choidev.choifile.ui.HostKeyDialog
import com.choidev.choifile.ui.AnalysisScreen
import com.choidev.choifile.ui.DuplicatesScreen
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
import com.choidev.choifile.util.hasAllFilesAccess
import com.choidev.choifile.util.kindOf
import com.choidev.choifile.util.openWithOtherApp
import java.io.File

private enum class Screen { BROWSER, SETTINGS, VAULT, SEARCH, CONNECTIONS, OPERATIONS, ANALYSIS, DUPLICATES, RULES, SERVER }

/** Biometric prompts need a FragmentActivity (ComponentActivity is not enough). */
class MainActivity : FragmentActivity() {
    private val viewModel: BrowserViewModel by viewModels()
    private lateinit var authenticator: Authenticator

    // Deliberately not saved across process death: these must never restore into an unlocked state.
    private var appLocked by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.BROWSER)
    private var tempViewing by mutableStateOf<String?>(null)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authenticator = Authenticator(this)
        appLocked = viewModel.prefs.appLock
        viewModel.viewCacheDir().deleteRecursively() // leftovers of decrypted copies after a crash or kill
        IndexWorker.schedule(this)
        enableEdgeToEdge()
        setContent {
            ChoiTheme {
                CompositionLocalProvider(LocalAuthenticator provides authenticator) { Root() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val context = LocalContext.current
        val requestAuth = rememberRequestAuth()
        var granted by remember { mutableStateOf(hasAllFilesAccess(context)) }
        var viewing by rememberSaveable { mutableStateOf<String?>(null) }
        var appLockOn by remember { mutableStateOf(viewModel.prefs.appLock) }
        var relock by remember { mutableIntStateOf(viewModel.prefs.relockSeconds) }
        val protectedPaths by viewModel.protectedPaths.collectAsStateWithLifecycle()
        val hostKeyRequest by viewModel.hostKeyRequest.collectAsStateWithLifecycle()

        // Local files open in the viewer or another app; archives open as folders; files on servers or inside archives open from a temporary copy.
        val openFile: (FileItem) -> Unit = { item ->
            val file = File(item.path)
            when {
                ArchiveLocation.isArchive(item.path) || RemoteLocation.isRemote(item.path) -> viewModel.openVirtualFile(item)
                ArchiveFormat.detect(item.name) != null -> viewModel.openDir(ArchiveLocation.make(item.path))
                kindOf(file) == FileKind.OTHER -> openWithOtherApp(context, file)
                else -> viewing = item.path
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
        when {
            appLocked -> LockedView(R.string.auth_subtitle_app) {
                requestAuth(R.string.auth_subtitle_app, false) { appLocked = false }
            }
            !granted -> PermissionScreen {
                legacyLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
            temp != null -> ViewerScreen(File(temp), temporary = true) { closeTemp() }
            view != null && !viewModel.isLocked(view) -> ViewerScreen(File(view)) { viewing = null }
            screen == Screen.SETTINGS -> SettingsScreen(
                appLock = appLockOn,
                onAppLock = { on ->
                    // Turning it on is strict: it must be possible to authenticate, or we would lock the user out.
                    requestAuth(R.string.auth_subtitle_change, on) {
                        viewModel.prefs.appLock = on
                        appLockOn = on
                    }
                },
                relockSeconds = relock,
                onRelock = { relock = it; viewModel.setRelockSeconds(it) },
                protectedPaths = protectedPaths,
                rootPath = viewModel.rootPath,
                onUnprotect = { p -> requestAuth(R.string.auth_subtitle_change, false) { viewModel.setProtected(p, false) } },
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
                onOpenTool = { screen = when (it) {
                    ToolScreen.OPERATIONS -> Screen.OPERATIONS
                    ToolScreen.ANALYSIS -> Screen.ANALYSIS
                    ToolScreen.DUPLICATES -> Screen.DUPLICATES
                    ToolScreen.RULES -> Screen.RULES
                    ToolScreen.SERVER -> Screen.SERVER
                } },
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
        // Short grace period so the share sheet or a permission screen does not force a re-prompt.
        if (stoppedAt != 0L && viewModel.prefs.appLock && SystemClock.elapsedRealtime() - stoppedAt > APP_LOCK_GRACE_MS) {
            appLocked = true
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        viewModel.onBackground()
        if (screen == Screen.VAULT) screen = Screen.BROWSER // the hidden list always needs a fresh authentication
        if (tempViewing != null) closeTemp()                 // decrypted copies never outlive the foreground
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) viewModel.viewCacheDir().deleteRecursively()
    }

    private companion object {
        const val APP_LOCK_GRACE_MS = 10_000L
    }
}
