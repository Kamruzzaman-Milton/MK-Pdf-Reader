package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
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
 * Automatically falls back to ACTION_GET_CONTENT when Android's default DocumentsUI
 * is disabled and the user is using third-party file managers like MiXplorer.
 */
class OpenPdfDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val openDocIntent = super.createIntent(context, input)
        val resolve = openDocIntent.resolveActivity(context.packageManager)
        return if (resolve != null) {
            openDocIntent
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(Intent.EXTRA_MIME_TYPES, input)
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val readerViewModel: PdfReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch(Dispatchers.IO) {
            PdfOutlineParser.purgeEmptyOutlineCaches(applicationContext)
            PdfDocumentStorage.purgeExcessStorageAndCaches(applicationContext)
        }

        // Check if launched via ACTION_VIEW or ACTION_SEND
        val initialUri = extractPdfUriFromIntent(intent)

        setContent {
            val appSettings by homeViewModel.appSettings.collectAsStateWithLifecycle()
            MyApplicationTheme(appThemeMode = appSettings.appThemeMode) {
                MainAppContent(
                    homeViewModel = homeViewModel,
                    readerViewModel = readerViewModel,
                    initialPdfUri = initialUri,
                    onKeepScreenAwakeChange = { keepAwake ->
                        if (keepAwake) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = extractPdfUriFromIntent(intent)
        if (uri != null) {
            readerViewModel.loadPdf(uri)
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
    initialPdfUri: Uri? = null,
    onKeepScreenAwakeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var currentPdfUriString by rememberSaveable {
        mutableStateOf(initialPdfUri?.toString())
    }

    val readerState by readerViewModel.uiState.collectAsStateWithLifecycle()

    // Manage screen keep-awake flag
    LaunchedEffect(currentPdfUriString, readerState.isScreenWakeActive) {
        val shouldKeepAwake = currentPdfUriString != null && readerState.isScreenWakeActive
        onKeepScreenAwakeChange(shouldKeepAwake)
    }

    DisposableEffect(Unit) {
        onDispose {
            onKeepScreenAwakeChange(false)
        }
    }

    // Load initial PDF if provided via Intent
    LaunchedEffect(initialPdfUri) {
        if (initialPdfUri != null) {
            PdfDocumentStorage.takePersistablePermissionSafely(context, initialPdfUri)
            currentPdfUriString = initialPdfUri.toString()
            readerViewModel.loadPdf(initialPdfUri)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = OpenPdfDocumentContract()
    ) { uri: Uri? ->
        if (uri != null) {
            PdfDocumentStorage.takePersistablePermissionSafely(context, uri)
            currentPdfUriString = uri.toString()
            readerViewModel.loadPdf(uri)
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

    // Ensure system back gesture when reading a PDF returns to Recent menu instead of exiting app
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
        } else {
            HomeScreen(
                viewModel = homeViewModel,
                onOpenPdfUri = { uri ->
                    PdfDocumentStorage.takePersistablePermissionSafely(context, uri)
                    currentPdfUriString = uri.toString()
                    readerViewModel.loadPdf(uri)
                },
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
                }
            )
        }
    }
}
