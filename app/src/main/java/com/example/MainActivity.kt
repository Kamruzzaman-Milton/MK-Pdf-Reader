package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
import com.example.ui.library.LibraryBrowserScreen
import com.example.ui.library.LibrarySettingsSheet
import com.example.ui.library.LibraryViewModel
import com.example.ui.reader.PdfReaderScreen
import com.example.ui.reader.PdfReaderViewModel
import com.example.ui.theme.MyApplicationTheme

import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.example.data.pdf.PdfDocumentStorage
import com.example.data.pdf.PdfOutlineParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Resilient document picker contract.
 * Tries ACTION_OPEN_DOCUMENT first with persistable read flags, and falls back
 * to ACTION_GET_CONTENT when Android's default DocumentsUI is unavailable.
 */
class OpenPdfDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val openDocIntent = super.createIntent(context, input).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        val resolve = openDocIntent.resolveActivity(context.packageManager)
        return if (resolve != null) {
            openDocIntent
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(Intent.EXTRA_MIME_TYPES, input)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val readerViewModel: PdfReaderViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private var isReaderVisible: Boolean = false
    private var pendingIntentUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch(Dispatchers.IO) {
            PdfOutlineParser.purgeEmptyOutlineCaches(applicationContext)
            PdfDocumentStorage.purgeExcessStorageAndCaches(applicationContext)
        }

        // Check if launched via ACTION_VIEW or ACTION_SEND
        pendingIntentUri = extractPdfUriFromIntent(intent)

        setContent {
            val appSettings by homeViewModel.appSettings.collectAsStateWithLifecycle()
            MyApplicationTheme(
                appThemeMode = appSettings.appThemeMode,
                appAccent = appSettings.appAccent
            ) {
                MainAppContent(
                    homeViewModel = homeViewModel,
                    readerViewModel = readerViewModel,
                    libraryViewModel = libraryViewModel,
                    initialPdfUri = pendingIntentUri,
                    onIntentUriConsumed = { pendingIntentUri = null },
                    onKeepScreenAwakeChange = { keepAwake ->
                        if (keepAwake) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    },
                    onReaderVisibilityChanged = { visible ->
                        isReaderVisible = visible
                    }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isReaderVisible && readerViewModel.appSettings.value.volumeKeyNavigation) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    readerViewModel.stepPage(1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    readerViewModel.stepPage(-1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isReaderVisible && readerViewModel.appSettings.value.volumeKeyNavigation) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = extractPdfUriFromIntent(intent)
        if (uri != null) {
            pendingIntentUri = uri
        }
    }

    private fun extractPdfUriFromIntent(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.also { uri ->
                    PdfDocumentStorage.takePersistablePermissionSafely(this, uri)
                }
            }
            Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                uri?.also { PdfDocumentStorage.takePersistablePermissionSafely(this, it) }
            }
            else -> null
        }
    }
}

@Composable
fun MainAppContent(
    homeViewModel: HomeViewModel,
    readerViewModel: PdfReaderViewModel,
    libraryViewModel: LibraryViewModel,
    initialPdfUri: Uri? = null,
    onIntentUriConsumed: () -> Unit = {},
    onKeepScreenAwakeChange: (Boolean) -> Unit = {},
    onReaderVisibilityChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentPdfUriString by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var libraryLocation by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var showLibrarySettings by remember { mutableStateOf(false) }

    val readerState by readerViewModel.uiState.collectAsStateWithLifecycle()
    val libraryRoots by libraryViewModel.roots.collectAsStateWithLifecycle()
    val libraryScanState by libraryViewModel.scanState.collectAsStateWithLifecycle()
    val libraryListing by libraryViewModel.listing.collectAsStateWithLifecycle()
    val librarySettings by libraryViewModel.appSettings.collectAsStateWithLifecycle()
    val selectedLibraryKeys by libraryViewModel.selectedKeys.collectAsStateWithLifecycle()
    val recentPdfs by homeViewModel.recentPdfs.collectAsStateWithLifecycle()

    LaunchedEffect(libraryLocation) {
        libraryViewModel.setLocation(libraryLocation)
    }

    LaunchedEffect(currentPdfUriString) {
        onReaderVisibilityChanged(currentPdfUriString != null)
    }

    // Manage screen keep-awake flag
    LaunchedEffect(currentPdfUriString, readerState.isScreenWakeActive) {
        val shouldKeepAwake = currentPdfUriString != null && readerState.isScreenWakeActive
        onKeepScreenAwakeChange(shouldKeepAwake)
    }

    DisposableEffect(Unit) {
        onDispose {
            onReaderVisibilityChanged(false)
            onKeepScreenAwakeChange(false)
        }
    }

    val openPdfUri: (Uri) -> Unit = { uri ->
        PdfDocumentStorage.takePersistablePermissionSafely(context, uri)
        scope.launch {
            val resolved = homeViewModel.resolveIdentityUri(uri)
            currentPdfUriString = resolved.toString()
            readerViewModel.loadPdf(resolved)
        }
    }

    // Load initial or new-intent PDF if provided via Intent
    LaunchedEffect(initialPdfUri) {
        if (initialPdfUri != null) {
            PdfDocumentStorage.takePersistablePermissionSafely(context, initialPdfUri)
            val resolved = homeViewModel.resolveIdentityUri(initialPdfUri)
            currentPdfUriString = resolved.toString()
            readerViewModel.loadPdf(resolved)
            onIntentUriConsumed()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = OpenPdfDocumentContract()
    ) { uri: Uri? ->
        if (uri != null) {
            openPdfUri(uri)
        }
    }

    val navigateBackToRecent: () -> Unit = {
        try {
            readerViewModel.recordClose()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error closing reader", e)
        }
        currentPdfUriString = null
        onKeepScreenAwakeChange(false)
    }

    val navigateUpLibrary: () -> Unit = {
        libraryLocation = libraryLocation?.let { navigateUpLibraryLocation(it) }
        libraryViewModel.setLocation(libraryLocation)
    }

    // Ensure system back gesture when browsing library moves to parent folder or returns to Home
    BackHandler(enabled = libraryLocation != null && currentPdfUriString == null) {
        navigateUpLibrary()
    }

    // Ensure system back gesture when reading a PDF returns to previous screen (Browser or Home) instead of exiting app
    BackHandler(enabled = currentPdfUriString != null) {
        navigateBackToRecent()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        val activeUriString = currentPdfUriString
        if (activeUriString != null) {
            PdfReaderScreen(
                viewModel = readerViewModel,
                onNavigateBack = navigateBackToRecent
            )
        } else if (libraryLocation != null) {
            LibraryBrowserScreen(
                listing = libraryListing,
                scanState = libraryScanState,
                appSettings = librarySettings,
                recents = recentPdfs,
                selectedKeys = selectedLibraryKeys,
                onNavigateLocation = { newLoc ->
                    libraryLocation = newLoc
                    libraryViewModel.setLocation(newLoc)
                },
                onNavigateUp = navigateUpLibrary,
                onOpenPdf = openPdfUri,
                onSelectSort = { sort -> libraryViewModel.setBrowserSort(sort) },
                onToggleFoldersFirst = { foldersFirst -> libraryViewModel.setBrowserFoldersFirst(foldersFirst) },
                onRescanRoot = { rootId -> libraryViewModel.rescan(rootId) },
                onOpenLibrarySettings = { showLibrarySettings = true },
                onToggleSelect = { pdf -> libraryViewModel.toggleSelection(pdf) },
                onSelectAll = { pdfs -> libraryViewModel.selectAll(pdfs) },
                onClearSelection = { libraryViewModel.clearSelection() },
                onAddToRecents = { pdfs -> libraryViewModel.addToRecents(pdfs) },
                onToggleFavorite = { pdf, matchedRecentUri ->
                    libraryViewModel.toggleFavorite(pdf, matchedRecentUri)
                },
                onFavoriteSelected = { pdfs, matchedUrisByKey ->
                    libraryViewModel.favoriteSelected(pdfs, matchedUrisByKey)
                },
                onDeletePdfs = { pdfs, onComplete ->
                    libraryViewModel.deletePdfs(pdfs, onComplete)
                }
            )
        } else {
            HomeScreen(
                viewModel = homeViewModel,
                onOpenPdfUri = openPdfUri,
                onLaunchFilePicker = {
                    try {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/pdf",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    } catch (e: Exception) {
                        try {
                            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/pdf"
                            }
                            context.startActivity(Intent.createChooser(fallbackIntent, "Select PDF"))
                        } catch (ignored: Exception) {}
                    }
                },
                libraryRoots = libraryRoots,
                libraryScanState = libraryScanState,
                onOpenLibraryFolder = { loc ->
                    libraryLocation = loc
                    libraryViewModel.setLocation(loc)
                },
                onOpenLibrarySettings = { showLibrarySettings = true }
            )
        }
    }

    if (showLibrarySettings) {
        LibrarySettingsSheet(
            roots = libraryRoots,
            scanState = libraryScanState,
            autoRescanEnabled = librarySettings.libraryAutoRescan,
            onAddRoot = { treeUri -> libraryViewModel.addRoot(treeUri) },
            onRescanRoot = { id -> libraryViewModel.rescan(id) },
            onRescanAll = { libraryViewModel.rescan(null) },
            onRemoveRoot = { id ->
                if (libraryLocation?.startsWith("$id|") == true) {
                    libraryLocation = null
                    libraryViewModel.setLocation(null)
                }
                libraryViewModel.removeRoot(id)
            },
            onToggleAutoRescan = { enabled -> libraryViewModel.setLibraryAutoRescan(enabled) },
            onDismiss = { showLibrarySettings = false }
        )
    }
}

private fun navigateUpLibraryLocation(location: String): String? {
    val pipeIdx = location.indexOf('|')
    if (pipeIdx < 0) return null
    val rootId = location.substring(0, pipeIdx)
    val relDir = location.substring(pipeIdx + 1).trim('/')
    if (relDir.isEmpty()) return null
    val slashIdx = relDir.lastIndexOf('/')
    return if (slashIdx >= 0) {
        "$rootId|${relDir.substring(0, slashIdx)}"
    } else {
        "$rootId|"
    }
}
