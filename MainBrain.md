# MK PDF Reader — Complete Technical Architecture & Source-of-Truth (`MainBrain.md`)

Target: `Kamruzzaman-Milton/MK-Pdf-Reader` (Android / Kotlin / Jetpack Compose / Material 3 / Room).
Path prefixes: `J/` = `app/src/main/java/com/example/`, `R/` = `app/src/main/res/`.

## 1. Identity, Build Configuration, Manifest & Dependencies

### 1.1 Application Identity & SDK Targets
- `R/values/strings.xml`: `app_name`: `"MK PDF Reader"`
- `settings.gradle.kts`: Root Project Name: `"MK PDF Reader"`
- `app/build.gradle.kts`: Namespace `com.example`, Application ID `com.aistudio.mkpdfreader.app`, Version Code/Name `1` / `"1.0"`, Compile/Target SDK `35` / `35`, Min SDK `24`, Java `JavaVersion.VERSION_11`, Test Runner `androidx.test.runner.AndroidJUnitRunner`
- `metadata.json`: `name`: `"MK PDF Reader"`, `majorCapabilities`: `["MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API"]`

### 1.2 Gradle Toolchain & Build Properties
- `gradle/libs.versions.toml`: AGP `9.1.1` (`com.android.application`), Kotlin `2.2.10` (`org.jetbrains.kotlin.plugin.compose`), KSP `2.3.5` (`com.google.devtools.ksp`)
- Repositories (`settings.gradle.kts`): `google()`, `mavenCentral()`, `https://jitpack.io` (hosts `barteksc:pdfium-android`)
- Plugins (`app/build.gradle.kts`): `android.application`, `kotlin.compose`, `google.devtools.ksp`, `roborazzi` (`1.59.0`), `secrets` (`2.0.1`), `google.services` (`4.5.0`, `MissingGoogleServicesStrategy.WARN`)
- Signing, Shrinking & Packaging (`app/build.gradle.kts`, `app/proguard-rules.pro`):
  - `release` signing: `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` env vars, fallback `${rootDir}/debug.keystore`; `debugConfig`: `${rootDir}/debug.keystore`, password `"android"`, alias `"androiddebugkey"`
  - `release` & `debug` `buildTypes`: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt` + `proguard-rules.pro` (`isCrunchPngs = false` in `release`)
  - `defaultConfig`: `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }`; `android`: `androidResources { localeFilters += listOf("en") }`, `packaging.resources.excludes` for `/META-INF/{AL2.0,LGPL2.1}`, `DebugProbesKt.bin`, `kotlin-tooling-metadata.json`
  - `app/proguard-rules.pro`: keeps `com.shockwave.**` (classes & classmembers), `com.shockwave.pdfium.**` (JNI reflection in `PdfOutlineParser`), native methods, `SourceFile,LineNumberTable`
- Persistent Debug Keystore Sync (`app/signing/`, `app/build.gradle.kts`, `app/signing/README.md`): `app/signing/persistent-debug.keystore.base64` pins Base64 debug keystore across GitHub exports & Google AI Studio imports. Configuration-cache-safe task `:app:syncPersistentKeystore` (`SyncPersistentKeystoreTask`) runs before `preBuild`, `validateSigningDebug`, `packageDebug` to sync `${rootDir}/debug.keystore` and `${rootDir}/debug.keystore.base64` from `app/signing/persistent-debug.keystore.base64` (fallback `${rootDir}/debug.keystore.base64` if unset)
- Secrets Plugin (`app/build.gradle.kts`): `propertiesFileName = ".env"`, `defaultPropertiesFileName = ".env.example"`, `ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")`; `buildFeatures { compose = true; buildConfig = true }`
- JVM, Gradle & Git Hygiene (`gradle.properties`, `.gitignore`, `app/.gitignore`): `-Xmx4g -Dfile.encoding=UTF-8`, `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `org.gradle.workers.max=4`, `android.nonTransitiveRClass=true`, `kotlin.compiler.execution.strategy=in-process`, `googleServices.missing.passthrough=true`. `.gitignore` & `app/.gitignore` exclude `.gradle/`, `**/build/`, `.cxx/`, `.idea/`, `local.properties`, binary keystores; explicitly keep `debug.keystore.base64`, `app/signing/`, `.env.example`, `gradle/`

### 1.3 AndroidManifest (`app/src/main/AndroidManifest.xml`)
- Permissions: `android.permission.READ_EXTERNAL_STORAGE` (`android:maxSdkVersion="32"`), `android.permission.MANAGE_EXTERNAL_STORAGE` (`tools:ignore="ScopedStorage"`). No `INTERNET` permission (100% offline).
- Application: `allowBackup="true"`, `dataExtractionRules="@xml/data_extraction_rules"`, `fullBackupContent="@xml/backup_rules"`, `icon="@mipmap/ic_launcher"`, `roundIcon="@mipmap/ic_launcher_round"`, `supportsRtl="true"`, `requestLegacyExternalStorage="true"`, `theme="@style/Theme.MyApplication"` (XML parent `android:Theme.DeviceDefault.NoActionBar` in `R/values/themes.xml`)
- Single Activity (`com.example.MainActivity`): `exported="true"`, `configChanges="orientation|screenSize|screenLayout|keyboardHidden"` (prevents recreation on orientation swaps, keeping `PdfRendererEngine` & Compose state alive)
- Intent Filters: (1) `MAIN` / `LAUNCHER`; (2) `ACTION_VIEW`: `DEFAULT`, `BROWSABLE`, `mimeType="application/pdf"`, schemes `content`, `file`; (3) `ACTION_SEND`: `DEFAULT`, `mimeType="application/pdf"`

### 1.4 Dependency Matrix (`app/build.gradle.kts` & `gradle/libs.versions.toml`)
| Dependency | Version / Ref | Runtime Status |
|---|---|---|
| `androidx.compose.bom` / `material3` / `ui` / `material-icons-core` / `material-icons-extended` | BOM `2024.09.00` | Active — Compose UI & Material Icons |
| `androidx.activity:activity-compose` | `1.10.1` | Active — `setContent`, `enableEdgeToEdge`, `BackHandler`, `rememberLauncherForActivityResult` |
| `androidx.core:core-ktx` | `1.15.0` | Active — Core KTX |
| `androidx.lifecycle:lifecycle-runtime-ktx` / `runtime-compose` / `viewmodel-compose` | `2.8.7` | Active — `AndroidViewModel`, `collectAsStateWithLifecycle`, `LocalLifecycleOwner` |
| `androidx.room:room-runtime` / `room-ktx` / `room-compiler` (KSP) | `2.7.0` | Active — `AppDatabase`, `PdfRecentDao`, `PdfBookmarkDao` |
| `kotlinx-coroutines-android` / `kotlinx-coroutines-core` | `1.10.2` | Active — async rendering, prefetch, disk I/O, keep-awake |
| `com.github.barteksc:pdfium-android` (excludes `com.android.support`) | `1.9.0` | Active (Fallback) — native TOC fallback in `PdfOutlineParser` |
| `accompanist-permissions`, `camera-*`, `datastore-preferences`, `navigation-compose`, `coil-compose`, `play-services-location`, `firebase-firestore`, `firebase-auth`, `credentials`, `googleid` | Commented out | Disabled in `app/build.gradle.kts` |
| `junit` (`4.13.2`), `robolectric` (`4.16.1`), `roborazzi` (`1.59.0`), `kotlinx-coroutines-test`, `androidx.compose.ui:ui-test-junit4` | Test only | Active in `src/test/` |

## 2. Codebase File Map

### 2.1 Kotlin Source Files (`J/`)
- `MainActivity.kt` (`OpenPdfDocumentContract`, `MainActivity`, `MainAppContent`, `navigateUpLibraryLocation`): entry, edge-to-edge, startup cache purge, `VIEW`/`SEND` URI routing, `resolveIdentityUri` funnel, root screen switcher, `LibrarySettingsSheet` host, picker, `FLAG_KEEP_SCREEN_ON`, `BackHandler`, volume-key nav (see 3.1, 3.2).
- `data/backup/BackupManager.kt` (`BackupExportSummary`, `BackupImportSummary`, `BackupManager`): portable ZIP export/import, zip-slip whitelist, merge restore, `computeStorageUsedBytes` (see 6.6).
- `data/db/AppDatabase.kt` (`AppDatabase`): Room `@Database` (`mk_pdf_reader.db`, `version = 4`, `exportSchema = false`, `.fallbackToDestructiveMigration()`, `.allowMainThreadQueries()`); exposes `pdfRecentDao()`, `pdfBookmarkDao()`.
- `data/db/PdfRecentItem.kt` (`PdfRecentItem`): Room `@Entity(tableName = "recent_pdfs")` (`pdf_path` PK, `uriString`, `localFilePath`, `thumbnailPath`, `fileName`, `fileSize`, `pageCount`, `lastReadPage`, `scrollOffsetFraction`, `readingMode`).
- `data/db/PdfRecentDao.kt` (`PdfRecentDao`): Flow/sync queries, path/URI/localPath lookups, `insertOrUpdate`, `updateReadingProgress`, `deleteRecent`, `clearAll`.
- `data/db/PdfBookmarkEntity.kt` (`PdfBookmarkEntity`): Room `@Entity(tableName = "pdf_bookmarks")` (see 6.3).
- `data/db/PdfBookmarkDao.kt` (`PdfBookmarkDao`): `Flow<List<PdfBookmarkEntity>>`, `Flow<List<Int>>`, `getAll()`, single-page query, `insertBookmark`, `updateTitle`, `deleteBookmarkByPage`, `deleteBookmark`, `deleteAllBookmarksForUri`.
- `data/library/LibraryModels.kt` (`LibraryRootStatus`, `LibraryRoot`, `LibraryPdf`, `ScanState`, `FolderEntry`, `DirNode`, `LibraryTree`): folder library & tree models (see 6.5).
- `data/library/LibraryStorage.kt` (`LibraryStorage`): `AtomicFile` + `org.json` persistence in `filesDir/library/` (see 6.5).
- `data/library/LibraryScanner.kt` (`ScanResult`, `LibraryScanner`): cancellable iterative FILE/SAF PDF scanner (see 6.5).
- `data/library/LibraryRepository.kt` (`LibraryRepository`): singleton (`getInstance(context)`/`applicationContext`) managing `roots`/`scanState`/`tree`, `Mutex` scans, `removeEntries`, `mergeImportedRoots`, `searchByName`, `findByNameAndSize` (see 6.5).
- `data/library/LibraryThumbnailStore.kt` (`LibraryThumbnailStore`): lazy cover thumbnails via raw `PdfRenderer` + `Semaphore(1)` + memory/disk LRU (see 6.5).
- `data/pdf/PdfDocumentSource.kt`: `PdfDocumentInfo(uri, fileName, fileSize, pageCount, isSample)`, `ReadingMode { VERTICAL, HORIZONTAL }`.
- `data/pdf/PdfRendererEngine.kt` (`PdfRendererEngine`): `Mutex`-locked `PdfRenderer`, supersampling, `ColorMatrix` tones, heap `LruCache`, `gridThumbCache`, prefetch, cover thumbnails (see 4).
- `data/pdf/PdfOutlineParser.kt` (`PdfOutlineItem`, `PdfOutlineParser`): TOC cascade (L1, L2, sample, `RawPdfOutlineReader`, Pdfium), `isPlausible`, `diagnostics`/`lastDiagnostic`, `SAMPLE_GUIDE_OUTLINE`, `clearAllOutlineCaches` (see 5.1).
- `data/pdf/RawPdfOutlineReader.kt` (`RawPdfOutlineReader`): pure-Kotlin PDF xref/object/outline/dest reader over `FileChannel`, `Inflater`, `java.nio.charset` (see 5.1).
- `data/pdf/OutlineHelper.kt` (`OutlineIndex`, `OutlineHelper`): `buildIndex`, `flatten`, `getCurrentChapter`, `getRootChapter`, `getNextChapter` (see 5.2).
- `data/pdf/PdfDocumentStorage.kt` (`PdfDocumentStorage`): zero-copy `openFileDescriptor` (`openInputStream`), persistable permissions, `findMediaStoreUriForPath`, `getDocumentKey`, `purgeExcessStorageAndCaches` (see 6.1).
- `data/pdf/FilePathResolver.kt` (`FilePathResolver`): resolves `file://`, `content://`, SAF tree URIs (`resolveTreeUriToPath`, `resolveExternalDocIdToPath`), MiXplorer/public folders to filesystem paths (see 6.1).
- `data/pdf/PdfDirectoryStorage.kt` (`PdfDirectoryStorage`, `PdfDirectoryEntry`): authoritative `AtomicFile` JSON store in `filesDir/pdf_directories/` (see 6.2).
- `data/pdf/RecentPdfJsonStorage.kt` (`RecentPdfJsonStorage`, `RecentPdfJsonEntry`): sync `AtomicFile` JSON mirror at `filesDir/recent_books.json` (see 6.2).
- `data/pdf/SamplePdfGenerator.kt` (`SamplePdfGenerator`): generates 8-page A4 `MK_PDF_Reader_Guide.pdf` (see 6.4).
- `data/repository/PdfRepository.kt` (`PdfRepository`): coordinates storage layers, Room DAOs, `FilePathResolver`, `PdfDocumentStorage`, `LibraryRepository`, `PdfOutlineParser` (`applicationContext` only); shares `recentPdfs` flow, startup sync, `resolveIdentityUri`, `tryRelink`, metadata/thumbnails/progress, bookmark CRUD (`deleteAllBookmarksForUri`) (see 6.2, 6.3).
- `data/settings/AppSettings.kt` (14 enums: `RenderSharpness`, `PrefetchMode`, `PageColorTone`, `PageSpacing`, `KeepAwakeOption`, `ReaderOrientation`, `AppThemeMode`, `RecentViewMode`, `DoubleTapZoomMode`, `MarginTrim`, `AppAccent`, `LibrarySort`, `LibraryFilter`, `BrowserSort`; `AppSettings`, `SettingsRepository`): `mk_pdf_settings` `SharedPreferences`, `StateFlow<AppSettings>`, `addFavoriteKey`, `removeFavoriteKey`, `reloadFromPrefs` (see 7.1).
- `data/settings/ReadingStats.kt` (`ReadingStats`): `mk_pdf_stats` `SharedPreferences` singleton: `doc_<docKey>`, `day_<epochDay>`, `totalFor`, `todayMs`, `streakDays`, `formatDuration` (see 7.2).
- `ui/home/HomeViewModel.kt` (`HomeViewModel`): `recentPdfs`, `visibleRecents`, `librarySearchResults` (`combine(_homeSearchQuery, libraryRepository.tree)`), `storageUsedBytes`, `isBackupBusy`, `appSettings`, `isGeneratingSample`, startup `tryRelink` & one-shot `warmUpOutlines()`, Home/backup/cache/demo actions (see 3.2, 8.1).
- `ui/home/HomeScreen.kt` (`HomeScreen`, `LibraryFolderStrip`, `StorageAccessBanner`, `ContinueReadingSection`, `RecentHeaderRow`, `LibraryFilterSortRow`, `PdfCoverThumbnail` [`internal`], `RecentPdfGridCard`, `RecentPdfCard`, `HomeSettingsSheet`, `AccentColorRow`, `ViewModeSelectorCard`, `ThemeOptionCard`, `NoSearchResultsView`, `NoFilterResultsView`, `EmptyRecentsView`, `InLibrarySearchHeader`, `HomeLibrarySearchRow`): Home UI (see 8.1).
- `ui/library/LibraryViewModel.kt` (`BrowserItem`, `BrowserScrollPosition`, `BrowserListing`, `LibraryViewModel`): `roots`, `scanState`, `tree`, `appSettings`, `selectedKeys`, `isSearchActive`, `searchQuery`, per-folder `folderScrollPositions` & `searchScrollPosition` (`getScrollPosition`/`saveScrollPosition`, `getSearchScrollPosition`/`saveSearchScrollPosition`, `setLocation` cleans exited child/root scroll positions on navigating up), `listing` (`naturalCompare` digit-chunk comparator), 2s-delayed stale auto-rescan (6h), `toggleSelection`, `selectAll`, `clearSelection`, `addToRecents`, `toggleFavorite`, `favoriteSelected`, `deletePdfs` (see 8.1b).
- `ui/library/LibraryBrowserScreen.kt` (`LibraryBrowserScreen`, `DetailRow`, `BrowserFolderRow`, `BrowserPdfRow`): library folder browser (see 8.1b).
- `ui/library/LibrarySettingsSheet.kt` (`LibrarySettingsSheet`, `LibraryRootManageCard`): library root management sheet (see 8.1b).
- `ui/reader/PdfReaderViewModel.kt` (`PdfReaderUiState`, `PdfReaderViewModel`): `PdfRendererEngine` lifecycle (`tryRelink` + retry), parallel outline/`OutlineIndex` + `reloadOutline()`, page/mode restore, bookmarks + `renameBookmark`, prefetch, keep-awake, 10s `ReadingStats` ticker (`setForeground`), zoom/pan, brightness, orientation, HUD, `navCommands`/`stepPage`, `isAutoScrolling`, sheet/dialog flags (see 3.2, 7.3, 8.2).
- `ui/reader/PdfReaderScreen.kt` (`resolveCurrentSystemBrightness` [`internal`], `PdfReaderScreen`, `ReaderViewport`, `ReaderTopBar`, `ReaderBottomBar`): reader UI & gestures (see 8.2).
- `ui/reader/PageOverviewSheet.kt` (`PageOverviewSheet`, `PageOverviewCell`): adaptive thumbnail grid dialog (see 8.3).
- `ui/reader/IndexSheet.kt` (`IndexSheet`, `SearchResultRow`, `FlatOutlineItemRow`): searchable TOC dialog (see 8.3).
- `ui/reader/BookmarksSheet.kt` (`BookmarksSheet`): bookmarks modal sheet + rename dialog (see 8.3).
- `ui/reader/SettingsSheet.kt` (`SettingsSheet`, `SectionHeader`): 7-section reader settings sheet (see 8.3).
- `ui/reader/JumpToPageDialog.kt` (`JumpToPageDialog`): page jump dialog (see 8.3).
- `ui/reader/DocumentInfoDialog.kt` (`DocumentInfoDialog`, `InfoRow`): metadata & reading-time dialog (see 8.3).
- `ui/components/PdfPageView.kt` (`computeTrimmedAspectRatio`, `PdfPageView`): trimmed aspect ratio, supersampled dims, sync L1 check / async `renderPage` on `Dispatchers.IO`, draw-time margin crop via `Canvas.drawImage(..., FilterQuality.High)`, clickable bookmarkable page badge (see 4.2).
- `ui/theme/Color.kt`: Scarlet/Crimson palette (`PrimaryRed`, `PrimaryRedDark`, `SecondaryRed`, `AccentCoral`, `AccentCoralLight`), light/dark neutrals, canvas constants (`CanvasLight`, `CanvasDark`, `CanvasSepia`, `TextSepia`).
- `ui/theme/Theme.kt` (`AmoledDarkColorScheme`, `DarkColorScheme`, `LightColorScheme`, `applyAccentToColorScheme`, `MyApplicationTheme`): theme + accent switching (see 7.4).
- `ui/theme/Type.kt` (`Typography`): Material 3 `Typography` with default `bodyLarge`.

### 2.2 Resources, Tests & Root Files
- `R/`: `values/{strings,colors,themes}.xml`; `drawable/{ic_launcher_background,ic_launcher_foreground}.xml`; `mipmap-anydpi-v26/ic_launcher{,_round}.xml`; `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.webp`; `xml/{backup_rules,data_extraction_rules}.xml`
- Assets: `assets/icon.svg` (root reference; duplicate `app/src/main/assets/icon.svg` removed)
- Tests (`app/src/`): `test/java/com/example/ExampleUnitTest.kt` (`2 + 2 == 4`); `test/java/com/example/ExampleRobolectricTest.kt` (`R.string.app_name == "MK PDF Reader"`, `@Config(sdk = [36])`); `test/java/com/example/GreetingScreenshotTest.kt` (Roborazzi screenshot of `JumpToPageDialog(currentPage=5, totalPages=42)` -> `src/test/screenshots/greeting.png`); `androidTest/java/com/example/ExampleInstrumentedTest.kt` (`BuildConfig.APPLICATION_ID`)

## 3. Architecture & Internal Workflows

### 3.1 Architectural Pattern
- Pattern: Single-Activity Jetpack Compose MVVM + UDF.
- DI: Manual construction in `AndroidViewModel`s (`HomeViewModel`, `PdfReaderViewModel`, `LibraryViewModel` instantiate `AppDatabase.getDatabase(application)`, `PdfRepository(...)`, `LibraryRepository.getInstance(application)`, `SettingsRepository.getInstance(application)`); scoped to `MainActivity` via `by viewModels()` and passed to composables.
- State Holders:
  - `SettingsRepository.settings: StateFlow<AppSettings>` (singleton)
  - `HomeViewModel`: `recentPdfs: StateFlow<List<PdfRecentItem>>`, `visibleRecents: StateFlow<List<PdfRecentItem>>` (`combine(recentPdfs, appSettings)`), `isGeneratingSample: StateFlow<Boolean>`
  - `LibraryViewModel`: `roots`, `scanState`, `tree`, `appSettings`, `selectedKeys`, `isSearchActive`, `searchQuery`, `listing: StateFlow<BrowserListing>` (`combine(tree, settings, location)`), plus per-folder & search `BrowserScrollPosition` cache
  - `PdfReaderViewModel`: `uiState: StateFlow<PdfReaderUiState>`, `engine: PdfRendererEngine?`
- Navigation (state-driven in `MainAppContent`, no Navigation Compose):
  - `currentPdfUriString: String?` (`rememberSaveable`), `libraryLocation: String?` (`"rootId|relDir"`, `rememberSaveable`).
  - Priority: `PdfReaderScreen` (`currentPdfUriString != null`) -> `LibraryBrowserScreen` (`libraryLocation != null`) -> `HomeScreen`.
  - Closing reader clears `currentPdfUriString` without touching `libraryLocation` and restores the folder/search `LazyListState` from `LibraryViewModel`.
  - `BackHandler(libraryLocation != null && currentPdfUriString == null)`: navigates up to parent `relDir` or `libraryLocation = null` at root (`navigateUpLibraryLocation`).
  - `BackHandler(currentPdfUriString != null)` -> `navigateBackToRecent()` (records close, clears `currentPdfUriString`, clears `FLAG_KEEP_SCREEN_ON`). Inside `PdfReaderScreen`, nested `BackHandler` dismisses: fullscreen -> `PageOverviewSheet` -> `BookmarksSheet` -> `IndexSheet` -> `SettingsSheet` -> `JumpToPageDialog` -> `DocumentInfoDialog` -> `onNavigateBack()`.
  - `LibrarySettingsSheet` hosted once in `MainAppContent` (`showLibrarySettings`), opened from `HomeScreen`, `HomeSettingsSheet`, `LibraryBrowserScreen`.

### 3.2 Startup & Lifecycle Workflows
1. `MainActivity.onCreate` & Open Routing:
   - `enableEdgeToEdge()`; `lifecycleScope` (`Dispatchers.IO`) runs `PdfOutlineParser.purgeEmptyOutlineCaches(applicationContext)` + `PdfDocumentStorage.purgeExcessStorageAndCaches(applicationContext)`.
   - `extractPdfUriFromIntent(intent)` sets `pendingIntentUri` (`ACTION_VIEW`: `intent.data`; `ACTION_SEND`: `Intent.EXTRA_STREAM` with `TIRAMISU` typed `getParcelableExtra` branch) and calls `PdfDocumentStorage.takePersistablePermissionSafely(this, uri)`. `onNewIntent` updates `pendingIntentUri` for warm intents.
   - `OpenPdfDocumentContract`: `ACTION_OPEN_DOCUMENT` (`CATEGORY_OPENABLE`, `application/pdf`, `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_PERSISTABLE_URI_PERMISSION`) with `ACTION_GET_CONTENT` fallback; callback calls `takePersistablePermissionSafely`.
   - Every open (picker, `VIEW`/`SEND`, Home tap, library tap) calls `homeViewModel.resolveIdentityUri(uri)` before setting `currentPdfUriString` and `readerViewModel.loadPdf(resolved)` (prevents duplicate recents / split bookmarks).
   - Collects `homeViewModel.appSettings` -> `MyApplicationTheme(appThemeMode, appAccent)`.
2. `PdfReaderViewModel.loadPdf(uri, isSample)`:
   - Sets `PdfOutlineParser.activeDocumentUri = uri.toString()`, cancels prior `outlineJob`, sets `isLoading = true`, `isLoadingOutline = true`.
   - Runs sync L1/L2 cache check (`PdfOutlineParser.loadCachedOutline`), immediately launches `outlineJob` in parallel with `PdfRendererEngine.initialize()` (sharing `pageCount` & `initialPage` via `CompletableDeferred`). On outline resolve: builds `OutlineIndex` on `Dispatchers.Default`, publishes `outlineItems`, `outlineIndex`, `currentChapterTitle`, `outlineDiagnostic`, `isLoadingOutline = false`, `hasOutlineLoaded = true` in one state update.
   - Closes previous `PdfRendererEngine`, creates `PdfRendererEngine(application, uri)`, calls `initialize()` on `Dispatchers.IO`. If `!initialized || pageCount == 0`, calls `repository.tryRelink(uri)` once and retries `initialize()` once before erroring.
   - Failure (`!initialized || pageCount == 0`): cancels `outlineJob`, checks `Environment.isExternalStorageManager()` (Android 11+/R+), sets actionable `errorMessage`.
   - Success: completes `pageCountDeferred`, resolves `PdfDocumentInfo` via `repository.resolveDocumentInfo`, reads `repository.getRecentByUri` for `lastReadPage` (`initialPageDeferred`) & `ReadingMode` (`VERTICAL` fallback), emits loaded `PdfReaderUiState`. Starts `bookmarksJob` (`repository.getBookmarksForUri(uri.toString())`), resets `keepAwakeJob` (`resetKeepAwakeTimer`), calls `triggerPrefetch(initialPage)`, `newEngine.renderThumbnail(0)`, `repository.recordDocumentOpened(...)`, and starts 10s `ReadingStats` ticker.
   - `recordClose()`: cancels `outlineJob`, clears `PdfOutlineParser.activeDocumentUri`.
3. Page Navigation (`onPageChanged` / `jumpToPage`):
   - Updates `currentPage`, `currentChapterTitle` via `OutlineHelper.getCurrentChapter(page, outlineIndex)` (O(1), never re-flattening), `isCurrentPageBookmarked`, `cacheUsageBytes`.
   - `jumpToPage` also resets `zoomScale = 1.0f`, `panOffsetX = 0f`, `panOffsetY = 0f`.
   - Calls `saveReadingProgress(pageIndex)` (async `repository.updateReadingProgress`), `recordUserActivity()`, `triggerPrefetch(pageIndex)`.
4. Home Resume, Refresh, Relink & Outline Warm-Up (`HomeScreen`, `HomeViewModel`):
   - `HomeViewModel.init`: calls `repository.syncRecentsOnStartup()`, runs sequential `relinkBrokenRecents()` on `Dispatchers.IO` (never deletes failed entries), launches one-shot per-process `warmUpOutlines()` (companion `Boolean` guard: waits 3s, loops sequentially on `Dispatchers.IO.limitedParallelism(1)` over recents missing `toc2_` file skipping `activeDocumentUri`, calls `PdfOutlineParser.parseOutline`, ignores failures silently without touching UI state).
   - `DisposableEffect(lifecycleOwner)` on `ON_RESUME`: `viewModel.refreshRecents()` (`PdfRepository.reloadRecentsFromDirectory()`) + updates `hasFullStorageAccess` (`Environment.isExternalStorageManager()`).

## 4. PDF Page Rendering Engine (`PdfRendererEngine` & `PdfPageView`)

### 4.1 Core Engine & Concurrency (`J/data/pdf/PdfRendererEngine.kt`)
- Wraps Android `android.graphics.pdf.PdfRenderer` over read-only `ParcelFileDescriptor` from `PdfDocumentStorage.openFileDescriptor(context, uri)`.
- Thread-safety: Android `PdfRenderer` allows only one open `PdfRenderer.Page` and single-threaded rendering at a time. `initialize()` and `renderPage()` acquire `renderMutex.withLock`. `getPageAspectRatio(pageIndex)` opens page inside `synchronized(this)` returning `page.height.toFloat() / page.width.toFloat()` (default `1.414f` A4 on invalid index/error).

### 4.2 Supersampled Rasterization & Viewport Scaling Math
1. Viewport Request & Draw-Time Margin Trim (`J/ui/components/PdfPageView.kt`):
   - Reads `LocalDensity.current`, `aspectRatio = engine.getPageAspectRatio(pageIndex)`.
   - Multiplier: `ULTRA_HD` -> `1.5f`, `HIGH_CLARITY` -> `1.25f`, `BALANCED` -> `1.0f`.
   - `baseWidthPx = ((density.density * 480) * multiplier).toInt().coerceIn(1200, 2400)`; `baseHeightPx = (baseWidthPx * aspectRatio).toInt()`.
   - `displayedAspectRatio = computeTrimmedAspectRatio(aspectRatio, marginTrim) = pageRatio * (1 - 2v) / (1 - 2h)` where `v = h / 2` (`MarginTrim`: `OFF = 0f`, `LIGHT = 0.04f`, `MEDIUM = 0.08f`, `STRONG = 0.12f`); cropped at draw time via `Canvas.drawImage(imageBitmap, srcOffset, srcSize, dstSize, FilterQuality.High)` so engine & cache keys stay unchanged.
2. Engine Supersampling & Clamping (`PdfRendererEngine.renderPage`):
   - `baseScale = min(targetWidth / originalWidth, targetHeight / originalHeight)` (or `targetWidth / originalWidth` if `targetHeight <= 0`, or `2.0f`).
   - Limits by `RenderSharpness`:

| `RenderSharpness` | `scaleMultiplier` | `maxScale` | `maxDimension` (px) |
|---|---|---|---|
| `BALANCED` | `2.2f` | `3.2f` | `2600` |
| `HIGH_CLARITY` (Default) | `3.2f` | `4.5f` | `3600` |
| `ULTRA_HD` | `4.8f` | `6.0f` | `4600` |

   - `effectiveScale = (baseScale * sharpness.scaleMultiplier).coerceIn(1.8f, maxScale)`.
   - `finalWidth = (originalWidth * effectiveScale).roundToInt().coerceAtLeast(300)`, `finalHeight = (originalHeight * effectiveScale).roundToInt().coerceAtLeast(300)`. If either > `maxDimension`, downscale both proportionally by `maxDimension.toFloat() / max(finalWidth, finalHeight)`.
   - Allocates `Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)` filled `Color.WHITE`, `Matrix` `(finalWidth / originalWidth, finalHeight / originalHeight)`, calls `page.render(bitmap, null, matrix, renderMode)`: `highQualityPrintMode == true` (Default) -> `PdfRenderer.Page.RENDER_MODE_FOR_PRINT`, `false` -> `PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY`.

### 4.3 Color Tone Post-Processing (`ColorMatrix`)
When `colorTone != PageColorTone.ORIGINAL`, allocates second `ARGB_8888` bitmap and draws with `Paint(ANTI_ALIAS_FLAG or FILTER_BITMAP_FLAG)` + 4x5 `ColorMatrixColorFilter`:
- `ORIGINAL`: no post-processing. Page `#FFFFFF`, Canvas `#E9ECEF`.
- `SEPIA` (Page `#FBF0D9`, Canvas `#EDE3D2`):
  ```text
  [0.37335, 0.73055, 0.17955, 0, 25 | 0.32108, 0.63112, 0.15456, 0, 15 | 0.23120, 0.45390, 0.11135, 0, 0 | 0, 0, 0, 1, 0]
  ```
- `INVERTED_DARK` (Page `#141414`, Canvas `#0D0D0D`):
  ```text
  [-0.88, 0, 0, 0, 235 | 0, -0.88, 0, 0, 235 | 0, 0, -0.88, 0, 235 | 0, 0, 0, 1, 0]
  ```
- `HIGH_CONTRAST` (contrast `1.35f`, translate `(-0.5f * 1.35f + 0.5f) * 255f = -44.625f`; Page `#FFFFFF`, Canvas `#DCDCDC`):
  ```text
  [1.35, 0, 0, 0, -44.625 | 0, 1.35, 0, 0, -44.625 | 0, 0, 1.35, 0, -44.625 | 0, 0, 0, 1, 0]
  ```

### 4.4 Memory Cache, OOM Protection, Prefetching & Thumbnails
- LRU Cache: `maxMemoryCacheBytes = (Runtime.getRuntime().maxMemory().toInt() / 5).coerceIn(48 * 1024 * 1024, 256 * 1024 * 1024)` (48–256 MB, 20% VM heap); `sizeOf` = `bitmap.byteCount`.
- Key: `"${pageIndex}_${targetWidth}_${targetHeight}_${sharpness.name}_${colorTone.name}"` (`highQualityPrintMode` absent -> toggling calls `engine.clearCache()` in `PdfReaderViewModel.updateHighQualityPrintMode`).
- `getCachedPage`: sync lookup in `PdfPageView` `remember` prevents spinner flicker.
- OOM Recovery: `OutOfMemoryError` -> `memoryCache.evictAll()`, `System.gc()`, returns `null`.
- Prefetching (`prefetchAdjacentPages`): cancels prior `prefetchJob`, runs on `Dispatchers.IO`, queues `[+1, -1, +2, -2, ...]` up to `PrefetchMode.pageRadius` (`0`, `1`, `2`); `PdfReaderViewModel.triggerPrefetch` passes `targetWidth = 1200, targetHeight = 1600`.
- Thumbnails:
  - Cover (`renderThumbnail`): `renderPage(0, 260, 360, BALANCED, ORIGINAL, highQualityPrintMode = false)`; `PdfRepository.recordDocumentOpened` saves PNG (quality `85`) at `filesDir/thumbs/thumb_<docKey>.png`.
  - Grid overview (`renderGridThumbnail(pageIndex)`): separate 24 MB `gridThumbCache: LruCache<Int, Bitmap>` (evicted in `clearCache()` & `closeInternal()`), rasterizes 200 px wide bitmap via `RENDER_MODE_FOR_DISPLAY` inside `renderMutex.withLock`, no color-tone filters.

## 5. Table of Contents / Index Loading & Processing

### 5.1 TOC Extraction Pipeline (`PdfOutlineParser.kt` & `RawPdfOutlineReader.kt`)
`PdfOutlineParser` extracts `PdfOutlineItem(id, title, pageIndex, level, children)` (`pageIndex` 0-indexed). All engine calls use `try/catch(Throwable)` (catching `UnsatisfiedLinkError`/`NoSuchMethodError`), validate via `isPlausible(list)` (`false` if empty, or flattened `> 5` items with `> 80%` at `pageIndex == 0`), and log one diagnostic line per failure in `diagnostics: ConcurrentHashMap<String, String>` (`lastDiagnostic(uri)`). Only plausible outlines are returned/cached. `parseOutline(context, uri, totalPages)` runs on `Dispatchers.IO` (never throws, returns `emptyList()` on failure) in strict cascade:
1. L1 Memory Cache: `ConcurrentHashMap<String, List<PdfOutlineItem>>` keyed by `uri.toString()` (`loadCachedOutline`).
2. L2 Disk Cache: `filesDir/toc_cache/toc2_<SHA256-32-hex>.json` (`getCacheFile`). Compact JSON keys: `"i"`=`id`, `"t"`=`title`, `"p"`=`pageIndex`, `"l"`=`level`, `"c"`=`children` (`JSONArray`, omitted if empty). Atomic write via `.tmp` rename (never caches empty/implausible); `JSONException`/read error deletes corrupt file & misses. Startup `purgeEmptyOutlineCaches` deletes non-`toc2_` files, `.tmp` files, and `toc2_*` `< 10` bytes or containing `"[]"`.
3. Sample-Guide Constant: `SAMPLE_GUIDE_OUTLINE` (8 level-0 items, ids `"sample_0"`..`"sample_7"`) if `uri.toString()` contains `"MK_PDF_Reader_Guide"`.
4. Pure-Kotlin `RawPdfOutlineReader` (Primary, no timeout): `RawPdfOutlineReader.read(pfd, totalPages)` reads via `FileInputStream(pfd.fileDescriptor).channel` without closing `pfd` or loading whole file; returns `null` on failure, empty outline, or `/Encrypt` trailer:
   - Tokenizer: dicts, arrays, `Ref(num)`, `Name` (`#xx` decoded, without `/`), `PdfString(bytes)` (nested parens, `\n \r \t \b \f \( \) \\`, octal `\ddd`, line continuation, hex strings), `Long`/`Double`, bools, `null`, comments, `"N G R"`.
   - Xref: scans last 2048 bytes for last `"startxref"`, iteratively follows `/XRefStm` then `/Prev` with visited guard (newest entry wins; `/Root` from newest trailer); supports classic `xref`+`trailer` & `/Type /XRef` streams (`/W`, `/Index`, `FlateDecode` `Inflater`, PNG predictor undo `/Predictor >= 10` using `/Columns`).
   - Object Loader (`getObject`): Type 1 reads 16 KB window (doubling to 8 MB on truncated tokens), parses `"N G obj"`, resolves `/Length` (or `endstream`), inflates `FlateDecode`. Type 2 decompresses `/ObjStm` once (8-entry LRU), parses `/N` header pairs, reads at `/First + offset`.
   - Outline & Destinations: iterative DFS `Root -> /Outlines -> /First` (`/First` children, `/Next` siblings, visited set, max depth 30, cap 100,000, IDs `native_toc_l<lvl>_i<idx>_p<page>`). Titles (`FE FF` UTF-16BE, `FF FE` UTF-16LE, `EF BB BF` UTF-8, else ISO-8859-1) + `cleanTitle`. Resolves `/Dest` or `/A` (`/S /GoTo`, `/D`), array first `Ref`, or named dests (`Root /Dests` dict, else `Root /Names /Dests` name tree via byte-wise `/Limits` & `/Names` pairs).
   - Lazy Page Map: built once on first page ref via iterative `/Pages` `/Kids` walk up to `totalPages`; unresolved dests reuse previous/parent `pageIndex`, clamped `0 until totalPages` (unclamped if `totalPages <= 0` during parallel pre-init).
5. Native C/C++ Pdfium Fallback (`parseWithNativePdfium`, `withTimeoutOrNull(10_000L)`, timeout logs `"Pdfium: timeout"` -> `null`):
   - `com.github.barteksc:pdfium-android:1.9.0` (`PdfiumCore`, `PdfDocument`). Opens `pfd` via `PdfDocumentStorage.openFileDescriptor`; retries `pdfiumCore.newDocument(pfd, "")` if needed; closes `PdfDocument` & `pfd` in `finally`.
   - Sub-path 1 (Iterative JNI Reflection `extractBookmarksIterativeNative`): reflects `PdfDocument.mNativeDocPtr` & `PdfiumCore` private JNI methods (`nativeGetFirstChildBookmark`, `nativeGetSiblingBookmark`, `nativeGetBookmarkTitle`, `nativeGetBookmarkDestIndex`); `while` loop for siblings (prevents `StackOverflowError` on 4,000+ chapters), recurses children `level <= 25` with `HashSet<Long>` cycle detector per level.
   - Sub-path 2 (Recursive Fallback): `pdfiumCore.getTableOfContents(pdfDocument)` -> `PdfDocument.Bookmark` + `cleanTitle` + regex `(?:page|p\.?|pg\.?)\s*(\d+)|\b(\d+)\s*$`.

### 5.2 Active Chapter Tracking & `OutlineIndex` (`OutlineHelper.kt`)
- `OutlineIndex(flat, parentIndex, lowerTitles, chapterForPage)` built once on `Dispatchers.Default` via `OutlineHelper.buildIndex(outline, totalPages)`. `chapterForPage` computes `maxIdxAtPage[pageIndex] = max(existing, i)` + running max across pages.
- Overloads taking `OutlineIndex`: `getCurrentChapter` (O(1) via `chapterForPage`), `getRootChapter` (walk `parentIndex`), `getNextChapter` (linear scan on `flat`). Preserves iterative `List<PdfOutlineItem>` signatures (`flatten`, `getCurrentChapter`, `getRootChapter`, `getNextChapter`). Live in `ReaderTopBar` subtitle (`"$currentChapter • $currentPage / $totalPages"`).

## 6. Document Access, URI Resolution & Persistence

### 6.1 Zero-Copy Document Access (`PdfDocumentStorage.kt` & `FilePathResolver.kt`)
Never copies opened PDFs into app storage. `PdfDocumentStorage.openFileDescriptor(context, uri)` tries 6 strategies:
1. Direct `file://`: `File(uri.path)` if `exists() && canRead()`.
2. Direct `content://`: `takePersistablePermissionSafely` (`FLAG_GRANT_READ_URI_PERMISSION`, fallback `READ or WRITE`) + `contentResolver.openFileDescriptor(uri, "r")`.
3. Decoded `content://`: `Uri.decode(uri.toString())` for URL-encoded third-party paths.
4. Saved `mediaUri` & Direct Path: `PdfDirectoryStorage.findDirectoryEntry(context, uri)?.mediaUri`; else `ParcelFileDescriptor.open` on `dirEntry.path` if `exists() && canRead()`.
5. Path & MediaStore Recovery (`FilePathResolver.resolvePath` & `resolveTreeUriToPath`):
   - Resolves `file://`, `DocumentsContract` (`com.android.externalstorage.documents` primary/raw/SD, `com.android.providers.downloads.documents` raw/numeric ID, `com.android.providers.media.documents`), MediaStore `_data` column, MiXplorer `/ext_storage/` -> `/storage/emulated/0/`, `/root/`, embedded `/storage/emulated/`, `DIRECTORY_DOWNLOADS`/`DIRECTORY_DOCUMENTS` filename matches. `resolveTreeUriToPath(treeUri)` maps `com.android.externalstorage.documents` tree docIds (`primary:A/B` -> `/storage/emulated/0/A/B`, `XXXX-XXXX:A` -> `/storage/XXXX-XXXX/A`; `null` for other authorities).
   - Uses resolved path for: (5a) direct `ParcelFileDescriptor.open`, (5b) `findMediaStoreUriForPath` querying `MediaStore.Files` & `MediaStore.Downloads` by `DATA`, `DISPLAY_NAME + SIZE`, or `DISPLAY_NAME` (caches `mediaUri` back to `PdfDirectoryStorage`), (5c) public `Downloads`/`Documents` fallback.
6. Internal Sample Guide: `filesDir/MK_PDF_Reader_Guide.pdf` if URI contains `"MK_PDF_Reader_Guide"`.
- Document Key (`getDocumentKey(uri)`): decodes URI, trims, lowercases (`Locale.ROOT`), first 32 hex chars of SHA-256 (fallback `"doc_${abs(hashCode())}"`). Used for `dir_<key>.json`, `toc2_<key>.json`, `thumb_<key>.png`, `lib_thumbs/<key>.jpg`.
- Startup Purge (`purgeExcessStorageAndCaches`): deletes legacy `filesDir/books/`, `filesDir/saved_pdfs/`, and `cacheDir` files prefixed `toc_`, `temp_view_`, `tmp_`. Never touches `pdf_directories/`, `toc_cache/`, `thumbs/`, `lib_thumbs/`, `library/`, `recent_books.json`.

### 6.2 Tri-Layer Recent PDFs Persistence
| Layer | Location | Format & Role |
|---|---|---|
| Layer 1 (Authoritative) | `filesDir/pdf_directories/` (`dir_<docKey>.json` + `directory_index.json`) | `PdfDirectoryStorage`: ~1 KB `AtomicFile` JSON (`docKey`, `name`, `uri`, `path`, `directoryPath`, `time`, `size`, `pageCount`, `lastReadPage`, `scroll`, `mode`, `thumb`, `mediaUri`). Never auto-deleted. |
| Layer 2 (JSON Mirror) | `filesDir/recent_books.json` | `RecentPdfJsonStorage`: sync `AtomicFile` JSON array (`name`, `path`, `time`, `uri`, `page`, `pageCount`, `scroll`, `size`, `mode`, `thumb`). |
| Layer 3 (SQLite ORM) | `mk_pdf_reader.db` (`recent_pdfs`) | `PdfRecentItem`/`PdfRecentDao`: PK `pdf_path`, indexed `uriString`, `localFilePath`. |

- Startup Sync (`PdfRepository.init` -> `syncRecentsOnStartup`): if `pdf_directories/` non-empty -> upsert to Room; else if `recent_books.json` non-empty -> migrate to `pdf_directories/` & Room; else seed `pdf_directories/` from Room. Emits descending-`time` `MutableStateFlow<List<PdfRecentItem>>`.
- Write Path (`addRecent`/`recordDocumentOpened`): resolves/persists absolute `path` (preserving prior `path` & `mediaUri` on transient failure) across all 3 layers, then calls `reloadRecentsFromDirectory()`.
- Identity & Relink: `resolveIdentityUri(uri)` maps incoming URI with matching resolved `path` to stored `uriString` (attaching new `content://` as `mediaUri` if absent). `tryRelink(uri)` checks if openable; if not, queries `LibraryRepository.findByNameAndSize(fileName, fileSize)` and updates ONLY `path` (and `mediaUri`) keeping `uriString` & `docKey` intact. Never deletes on open failure.
- Delete (`deleteRecent`/`clearAllRecents`): removes from Room, `PdfDirectoryStorage`, `RecentPdfJsonStorage`, deletes legacy/temp via `PdfDocumentStorage.deletePersistentFile`, deletes TOC cache via `PdfOutlineParser.deleteOutlineCache` (single delete).

### 6.3 Bookmarks Database Schema (`AppDatabase` v4)
- Table `pdf_bookmarks` (`PdfBookmarkEntity`): `id: Long` (PK, `autoGenerate = true`), `uriString: String`, `pageIndex: Int` (0-indexed), `pageNumber: Int` (`pageIndex + 1`), `title: String?` (default `"Page ${pageIndex + 1}"`), `createdAt: Long` (`System.currentTimeMillis()`); unique index `(uriString, pageIndex)` (`OnConflictStrategy.REPLACE`).
- Ops (`PdfBookmarkDao`, `PdfRepository`): `getBookmarksForUri(uriString)` (`pageIndex ASC`), `getBookmarkedPageIndices(uriString)`, `toggleBookmark(uriString, pageIndex, title)` (uses `currentChapterTitle` if non-blank else `"Page ${pageIndex + 1}"`, returns `Boolean`), `updateBookmarkTitle(id, title)` (`updateTitle`), `deleteBookmark`, `deleteBookmarkByPage`.

### 6.4 Sample PDF Generator (`SamplePdfGenerator.kt`)
Creates `filesDir/MK_PDF_Reader_Guide.pdf` (8 pages, A4 `595×842` pt) if missing or `<= 1024` bytes. Draws vector text, banner, highlight boxes, zoom calibration grid, dual-mode diagram via `android.graphics.pdf.PdfDocument`, writes via `FileOutputStream` + `document.writeTo(out)`, closes both in `finally`. TOC via `PdfOutlineParser.SAMPLE_GUIDE_OUTLINE`.

### 6.5 Folder-Based PDF Library (`com.example.data.library`)
- Models (`LibraryModels.kt`): `LibraryRoot(id, name, absPath, treeUri, addedAt, lastScanAt, pdfCount, status)` (`LibraryRootStatus { OK, NEEDS_ACCESS }`; `id` = 12 hex chars SHA-256 of `absPath ?: treeUri`); `LibraryPdf(name, relDir, path, uri, size, modified)` (`relDir` `/`-separated, `""` at root); `ScanState` (`Idle` / `Scanning(rootName, found)`); `FolderEntry(name, relDir, pdfCountRecursive)`; `DirNode(folders, files)`; `LibraryTree(nodes: Map<"rootId|relDir", DirNode>)`.
- Persistence (`LibraryStorage.kt`): `filesDir/library/roots.json` + `index_<rootId>.json`, `AtomicFile` + `org.json` compact keys (`n, d, p, u, s, m`), no bitmaps/pages, corrupt reads return empty lists.
- Scanner (`LibraryScanner.kt`): `Dispatchers.IO`, explicit `ArrayDeque` (max depth 25), `ensureActive()` per dir, skips `.`-prefixed dirs & top-level `Android`, matches `.pdf` (`Locale.ROOT`) or `application/pdf` MIME, throttles progress every 25 files (unsorted). FILE mode (`File.listFiles()`, `Uri.fromFile`) if `absPath` readable; else SAF mode (`persistedUriPermissions`, `DocumentsContract.buildChildDocumentsUriUsingTree`/`buildDocumentUriUsingTree`, `use {}`, path from docId); else `ScanResult.NeedsAccess` preserving prior index.
- Repository (`LibraryRepository.kt`): singleton (`getInstance(context)`/`applicationContext`), `roots`/`scanState`/`tree` `StateFlow`s. `addRoot(treeUri)` takes persistable `READ or WRITE` (fallback `READ`), resolves `absPath`, deduplicates (rejects folders inside existing roots; replaces existing roots inside new parent), saves & scans. `removeRoot(id)` deletes root index only; `removeEntries(entries)` updates indexes & rebuilds tree after permanent file deletions; `mergeImportedRoots` adds unseen `id`s; `rescan(id?)` & `rescanAllIfStale(maxAgeMs)` use `Mutex`, atomic replace on completion, keep prior index on cancel/fail. Builds `LibraryTree` (ancestors of PDFs only), `HashMap` for `findByNameAndSize`, and lowercase `searchEntries` for `searchByName(query, limit = 20)` on `Dispatchers.Default`.
- Lean Thumbnails (`LibraryThumbnailStore.kt`): never instantiates `PdfRendererEngine`. Checks `filesDir/thumbs/thumb_<docKey>.png` then `filesDir/lib_thumbs/<docKey>.jpg`. Lazy `Dispatchers.IO` inside `Semaphore(1)` + `withTimeoutOrNull(8_000)` using raw `PdfRenderer` on `openFileDescriptor` (200 px wide, aspect height max 300 px, white bg, `RENDER_MODE_FOR_DISPLAY`, JPEG quality 80), closes/recycles in `finally`, caches failures per session. Backed by 8 MB `LruCache<String, ImageBitmap>` and 40 MB disk LRU on `lib_thumbs/` (`lastModified`); `clearLibThumbCache` deletes `lib_thumbs/` only.

### 6.6 Portable ZIP Backup & Android Auto-Backup (`BackupManager.kt`, `R/xml/{backup_rules,data_extraction_rules}.xml`)
- Export (`exportBackup`): streams `MKPDFReader_backup_<yyyyMMdd>.zip` to `CreateDocument("application/zip")` containing `manifest.json` (`format = 1`, `appVersion`, `createdAt`, `counts`), type-tagged `settings.json` (`mk_pdf_settings`), type-tagged `stats.json` (`mk_pdf_stats`), `library_roots.json`, `recents/` (`dir_<docKey>.json` + `directory_index.json`), `bookmarks.json` (`PdfBookmarkDao.getAll()`), optional `thumbs/thumb_<docKey>.png` (never `lib_thumbs` or `toc_cache`).
- Import (`importBackup`): parses & validates whole `OpenDocument` ZIP in memory before mutating, enforcing strict whitelist (`manifest.json`, `settings.json`, `stats.json`, `library_roots.json`, `bookmarks.json`, `recents/directory_index.json`, `recents/dir_<32hex>.json`, optional `thumbs/thumb_<32hex>.png`) and byte limits (zip-slip safe). Merge-only (newer `time` wins per recent, `REPLACE` bookmarks on `(uriString, pageIndex)`, preserves unbacked settings & unions `favorite_keys`, `maxOf` stats, unions roots by `id`), then calls `reloadRecentsFromDirectory()`, `SettingsRepository.reloadFromPrefs()`, and triggers library rescan.
- Storage Readout: `computeStorageUsedBytes(context)` sums `thumbs`, `lib_thumbs`, `toc_cache`, `pdf_directories`, `library` on `Dispatchers.IO`.
- Auto-Backup / Device Transfer (`backup_rules.xml`, `data_extraction_rules.xml`): includes `files/pdf_directories`, `files/library/roots.json`, `files/thumbs`, `files/recent_books.json`, prefs `mk_pdf_settings.xml` & `mk_pdf_stats.xml`, `mk_pdf_reader.db`; excludes `files/toc_cache`, `files/lib_thumbs` (and `library/index_*.json` via root-only include).

## 7. Settings, Themes & Default Configurations

### 7.1 `SettingsRepository` (`J/data/settings/AppSettings.kt`)
Backed by singleton `SharedPreferences("mk_pdf_settings", Context.MODE_PRIVATE)` + `StateFlow<AppSettings>`.

| Field (`AppSettings`) | Prefs Key | Type / Enum | Default | Options & Values | UI Location |
|---|---|---|---|---|---|
| `sharpness` | `"render_sharpness"` | `RenderSharpness` | `HIGH_CLARITY` (`3.2f`) | `BALANCED` (`2.2f`), `HIGH_CLARITY` (`3.2f`), `ULTRA_HD` (`4.8f`) | `SettingsSheet` §1 |
| `highQualityPrintMode` | `"high_quality_print_mode"` | `Boolean` | `true` | `true` (`RENDER_MODE_FOR_PRINT`), `false` (`RENDER_MODE_FOR_DISPLAY`) | `SettingsSheet` §1 ("Subpixel Antialiasing") |
| `prefetchMode` | `"prefetch_mode"` | `PrefetchMode` | `AGGRESSIVE` (`2`) | `OFF` (`0`), `STANDARD` (`1`), `AGGRESSIVE` (`2`) | `SettingsSheet` §2 |
| `colorTone` | `"color_tone"` | `PageColorTone` | `ORIGINAL` | `ORIGINAL` ("White"), `SEPIA` ("Sepia"), `INVERTED_DARK` ("Night"), `HIGH_CONTRAST` ("Mono") | `SettingsSheet` §3 |
| `pageSpacing` | `"page_spacing"` | `PageSpacing` | `STANDARD` (`14.dp`) | `COMPACT` (`6.dp`/"Tight"), `STANDARD` (`14.dp`/"Normal"), `SPACIOUS` (`24.dp`/"Wide") | `SettingsSheet` §3 |
| `showPageBadges` | `"show_page_badges"` | `Boolean` | `true` | `true`, `false` | `SettingsSheet` §3 ("Page Number Badges") |
| `orientation` | `"reader_orientation"` | `ReaderOrientation` | `PORTRAIT` | `PORTRAIT`, `LANDSCAPE` | `SettingsSheet` §4 & `ReaderBottomBar` |
| `keepAwakeOption` | `"keep_awake_option"` | `KeepAwakeOption` | `MINUTES_15` (`900_000L` ms) | `OFF` (`0L`), `MINUTES_5` (`300_000L`), `MINUTES_15` (`900_000L`), `MINUTES_30` (`1_800_000L`), `ALWAYS_ON` (`-1L`), `SYSTEM_DEFAULT` (`0L`) | `SettingsSheet` §4 (chips: `Off`, `5m`, `15m`, `Always`) |
| `doubleTapZoomMode` | `"double_tap_zoom_mode"` | `DoubleTapZoomMode` | `SMART_COLUMN` (`0f` -> `2.08f`) | `SMART_COLUMN` (`0f`), `COMPACT_1_8X` (`1.85f`), `STANDARD_2_1X` (`2.08f`), `LARGE_2_3X` (`2.30f`), `MAX_2_6X` (`2.60f`) | `SettingsSheet` §5 |
| `doubleTapZoomLevel` | `"double_tap_zoom_level"` | `Float` | `2.08f` | Persisted float (`updateDoubleTapZoomLevel`; `toggleDoubleTapZoom` uses `doubleTapZoomMode`) | Repo API only |
| `appThemeMode` | `"app_theme_mode"` | `AppThemeMode` | `SYSTEM` | `SYSTEM`, `LIGHT`, `DARK`, `AMOLED` | `HomeSettingsSheet` §2 |
| `recentViewMode` | `"recent_view_mode"` | `RecentViewMode` | `GRID` | `GRID`, `LIST` | `HomeScreen` TopAppBar & `HomeSettingsSheet` §1 |
| `marginTrim` | `"margin_trim"` | `MarginTrim` | `OFF` (`0f`) | `OFF` (`0f`), `LIGHT` (`0.04f`), `MEDIUM` (`0.08f`), `STRONG` (`0.12f`); vertical = `h / 2` | `SettingsSheet` §7 |
| `warmFilterPercent` | `"warm_filter_percent"` | `Int` | `0` | `0..60` (`#FF8A00` alpha `percent / 100f * 0.45f`) | `SettingsSheet` §7 |
| `tapZoneNavigation` | `"tap_zone_navigation"` | `Boolean` | `false` | `true`, `false` | `SettingsSheet` §7 |
| `volumeKeyNavigation` | `"volume_key_navigation"` | `Boolean` | `false` | `true`, `false` | `SettingsSheet` §7 |
| `autoScrollSpeed` | `"auto_scroll_speed"` | `Int` | `3` | `1..20` (`speed * 3.5f.dp.toPx()` / sec) | `SettingsSheet` §7 & `auto_scroll_pill` |
| `appAccent` | `"app_accent"` | `AppAccent` | `SCARLET` | `SCARLET`, `OCEAN` (`#1E88E5`/`#64B5F6`), `EMERALD` (`#2E7D32`/`#66BB6A`), `VIOLET` (`#7B1FA2`/`#CE93D8`), `AMBER` (`#EF6C00`/`#FFB74D`), `DYNAMIC` (API 31+) | `HomeSettingsSheet` §3 (`ACCENT COLOR`) |
| `librarySort` | `"library_sort"` | `LibrarySort` | `RECENT` | `RECENT`, `NAME`, `SIZE`, `PROGRESS` | `LibraryFilterSortRow` (`library_sort_btn`) |
| `libraryFilter` | `"library_filter"` | `LibraryFilter` | `ALL` | `ALL`, `IN_PROGRESS`, `FINISHED`, `FAVORITES` | `LibraryFilterSortRow` (`library_filter_<name>`) |
| `favoriteKeys` | `"favorite_keys"` | `Set<String>` | `emptySet()` | `PdfDocumentStorage.getDocumentKey(uri)` set (copied on read/write) | Grid/List cards (`favorite_grid_<hash>`/`favorite_<hash>`) |
| `browserSort` | `"browser_sort"` | `BrowserSort` | `NAME_ASC` | `NAME_ASC`, `NAME_DESC`, `DATE_NEW`, `DATE_OLD`, `SIZE_LARGE`, `SIZE_SMALL` | `LibraryBrowserScreen` sort menu |
| `browserFoldersFirst` | `"browser_folders_first"` | `Boolean` | `true` | `true`, `false` | `LibraryBrowserScreen` sort menu (`"Folders first"`) |
| `libraryAutoRescan` | `"library_auto_rescan"` | `Boolean` | `true` | `true`, `false` (2s-delayed `rescanAllIfStale(6h)` in `LibraryViewModel.init`) | `LibrarySettingsSheet` (`"Auto-rescan on app start"`) |

### 7.2 Reading Time Tracking (`J/data/settings/ReadingStats.kt`)
- `SharedPreferences("mk_pdf_stats")` stores `doc_<docKey>` and `day_<epochDay>` (`LocalDate.now().toEpochDay()` API 26+, Calendar civil-day API 24–25).
- 10s `Dispatchers.IO` ticker started in `PdfReaderViewModel.loadPdf` (cancelled in `recordClose()`/`onCleared()`): adds `10_000L` ms when `isReaderForeground` (`ON_RESUME`/`ON_PAUSE` via `setForeground`) and `lastUserActivityMs < 3` min ago.
- `streakDays(context)`: consecutive days ending today with `>= 60_000L` ms.

### 7.3 Transient Reader Defaults (`PdfReaderUiState` in `PdfReaderViewModel.kt`)
- `isLoading = true`, `currentPage = 0`, `totalPages = 0`, `readingMode = ReadingMode.VERTICAL` (overridden by saved `PdfRecentItem.readingMode`), `isFullscreen = false`, `isAutoScrolling = false` (forced `false` on `HORIZONTAL` mode, sheet/dialog open, doc end, close; continues across manual scrolling), `showPageOverview = false`, `zoomScale = 1.0f`, `panOffsetX = 0f`, `panOffsetY = 0f`, `brightnessLevel = -1f` (Auto; custom `0.01f..1.0f`), `extraDimLevel = 0f` (`0f..0.80f`, reset on close), `indexScrollIndex = -1`, `indexScrollOffset = 0`, `indexExpandedStates = emptyMap()` (default node expansion `true` in `IndexSheet`).
- Pinch bounds: `1.0f..4.0f` (`updateZoomAndPan`); double-tap resets to `1.0f` if `zoomScale > 1.15f`.
- `SMART_COLUMN` double-tap (`targetScale = 2.08f`): `tapX < viewWidth * 0.5f` targets `0.28f * viewWidth`, else `0.72f * viewWidth`. `maxAllowedPanX = ((viewWidth * (targetScale - 1f)) / 2f - safeMarginPx).coerceAtLeast(0f)` with `safeMarginPx = 16f * (viewWidth / 360f).coerceIn(1.0f, 3.0f)`.

### 7.4 Material 3 Color Schemes & Accents (`J/ui/theme/{Color,Theme}.kt`)
- `LightColorScheme`: `primary = #D32F2F`, `onPrimary = #FFFFFF`, `primaryContainer = #FFDAD6`, `onPrimaryContainer = #410002`, `secondary = #9A0007`, `secondaryContainer = #FFEBEE`, `background = #F8F9FA`, `surface = #FFFFFF`, `surfaceVariant = #ECEFF1`, `onSurface = #1E293B`, `onSurfaceVariant = #64748B`.
- `DarkColorScheme`: `primary = #FF6659`, `onPrimary = #000000`, `primaryContainer = #5C0003`, `onPrimaryContainer = #FFDAD6`, `secondary = #FF867F`, `secondaryContainer = #4A1015`, `background = #121212`, `surface = #1E1E1E`, `surfaceVariant = #2C2C2C`, `onSurface = #F1F5F9`, `onSurfaceVariant = #94A3B8`.
- `AmoledDarkColorScheme`: `primary = #FF5252`, `primaryContainer = #38080B`, `background = #000000`, `surface = #000000`, `surfaceVariant = #101010`, `surfaceContainerLowest = #000000`, `surfaceContainerLow = #070707`, `surfaceContainer = #0F0F0F`, `surfaceContainerHigh = #161616`, `surfaceContainerHighest = #202020`, `outline = #2A2A2A`, `outlineVariant = #1A1A1A`.
- `applyAccentToColorScheme` (in `MyApplicationTheme`): `SCARLET` untouched; `OCEAN`/`EMERALD`/`VIOLET`/`AMBER` override `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `secondary`, `secondaryContainer` (+ `onSecondary`/`onSecondaryContainer`); `DYNAMIC` uses `dynamicLightColorScheme`/`dynamicDarkColorScheme` on API 31+ (preserving AMOLED `background`, `surface`, `surfaceContainer*`).

### 7.4 Known Code-vs-Docs Discrepancies
- `SamplePdfGenerator.kt` Page 5 & 6 text mentions "5-Minute" wake lock and "1/8th max heap, capped at 64MB"; actual code defaults to `KeepAwakeOption.MINUTES_15` and `1/5th` heap (`48 MB..256 MB`).
- `SettingsSheet` offers `Off`, `5m`, `15m`, `Always` chips for `KeepAwakeOption`; `MINUTES_30` exists in `AppSettings.kt` without a UI chip.

## 8. Interface Structure (Screens, Menus, Options & `testTag`s)

### 8.1 Screen 1: `HomeScreen` (`J/ui/home/HomeScreen.kt`)
1. Top App Bar (Standard):
   - Left: red rounded-square PDF icon + Title `"MK PDF Reader"` + Subtitle `"Fast & Ultra-Sharp PDF Reader"`.
   - Right: View toggle (`RecentViewMode.GRID` ↔ `LIST`), Settings (`HomeSettingsSheet`), Overflow `DropdownMenu` (`"Open Quick Demo Guide"`, `"Clear Recents"` if `recentPdfs.isNotEmpty()` -> confirm `AlertDialog`).
2. Top App Bar (Inline Search, `isSearchActive == true`): Back arrow (`"Close Search"`, clears query & exits), transparent `TextField` (placeholder `"Search recent PDFs..."`, trailing Clear `X`), `BackHandler` exits search.
3. Body (`LazyVerticalGrid(2 columns)` in `GRID`, `LazyColumn` in `LIST`):
   - `LibraryFolderStrip` (1st body item above `ContinueReadingSection`, hidden while `isSearchActive`): `"Library"` + `"Manage"` `TextButton`. 0 roots: dashed card `"Add a folder to build your library"`. >=1 roots: `LazyRow` of `150×88dp` cards (folder icon, name, `"N PDFs"`, scan spinner or `NEEDS_ACCESS` warning; tap opens `LibraryBrowserScreen` at `"rootId|"`) + dashed `"+ Add folder"` card.
   - `StorageAccessBanner` (Android 11+ if `!Environment.isExternalStorageManager()` & not dismissed): `"Enable"` (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`), `"X"` dismisses.
   - `ContinueReadingSection` (non-empty & `!isSearchActive`, most recent PDF): left `PdfCoverThumbnail`, filename, `"Page X of Y • N% • Z pages left"`, `LinearProgressIndicator`, filled `"Resume"` button; under-card `"Today X min • Y-day streak"` (hidden if both 0).
   - `RecentHeaderRow`: `"Recent Documents"` + `"X items"` (or `"Search Results"` + `"X found"`).
   - `LibraryFilterSortRow`: horizontal `LazyRow` of `FilterChip`s (`ALL`, `IN_PROGRESS`, `FINISHED`, `FAVORITES`) + sort `IconButton` -> `DropdownMenu` (`RECENT`, `NAME`, `SIZE`, `PROGRESS`).
   - Empty / No-Match (`EmptyRecentsView`, `NoSearchResultsView`, `NoFilterResultsView`): PDF circle badge + `"Open Quick Demo Guide"`, search mismatch text, or empty filter message + `"Show All"`.
   - `RecentPdfGridCard`: `190.dp` `PdfCoverThumbnail`, bottom-right `"p. X/Y"` badge, bottom `LinearProgressIndicator`, 2-line filename, relative time, formatted size, favorite star, delete icon.
   - `RecentPdfCard`: `68×94.dp` `PdfCoverThumbnail` (`"p.X"` badge), filename, `"Page X of Y • <timeAgo>"`, size, `LinearProgressIndicator`, favorite star, delete icon.
   - `InLibrarySearchHeader` & `HomeLibrarySearchRow`: when `isSearchActive && searchQuery.trim().length >= 2`, appends `"In your library"` with up to 20 matching library PDFs (`LibraryThumbnailStore` cover, filename, `relDir`, size); tap opens via `resolveIdentityUri`.
4. Bottom Dual FABs: Bottom-left (`56.dp` circle) toggles inline search; Bottom-right (`60.dp` circle) launches `OpenPdfDocumentContract` (`application/pdf`, `application/octet-stream`, `*/*`, `ACTION_GET_CONTENT` fallback).
5. `HomeSettingsSheet` (`"Display & Settings"`, scrollable):
   - §1 `RECENT VIEW LAYOUT`: 2 `ViewModeSelectorCard`s (`Grid View` / `List View`).
   - §2 `APP THEME`: 4 `ThemeOptionCard` radio rows (`AMOLED Dark` with `"OLED Black"` badge, `Dark Theme`, `Light Theme`, `System Default`).
   - §3 `ACCENT COLOR`: `Row` of `40.dp` circular swatches (`SCARLET`, `OCEAN`, `EMERALD`, `VIOLET`, `AMBER`, `DYNAMIC` API 31+; selected shows check icon).
   - §4 `LIBRARY FOLDERS`: `"Manage Library Folders"` row -> closes sheet, opens `LibrarySettingsSheet`.
   - §5 `BACKUP & DATA`: `"Export Backup"` (dialog with `"Include thumbnails"` checkbox default `true` -> `CreateDocument("application/zip")`), `"Import Backup"` (`OpenDocument`), live `"Storage used"` readout, `"Clear thumbnail cache"` (`lib_thumbs/` only), `"Clear TOC cache"` (`toc_cache/`).
   - §6 Quick Tools: `"Demo Guide"` + `"Clear Recents"` (if recents non-empty).
6. Dialogs: Clear Recents confirm, Export Backup options, Backup progress (`isBackupBusy`), Backup result/error summary.

### 8.1b Library Browser & Settings (`J/ui/library/{LibraryBrowserScreen,LibrarySettingsSheet}.kt`)
- `LibraryBrowserScreen`:
  - `TopAppBar`: Normal (Back, folder title, Search, Sort dropdown with 6 `BrowserSort` options + `"Folders first"` checkbox, Refresh rescan); Search (ViewModel-persisted `isSearchActive`/`searchQuery`, 150ms debounced `Dispatchers.Default` over `allRootFiles` with `relDir` subtitle, preserved `LazyListState`); Selection (Close, `"N selected"`, Select All, Add to Recents, Favorite, Delete). `BackHandler` exits selection -> search -> parent folder.
  - `ScanState.Scanning` banner: `LinearProgressIndicator` + `"Scanning… N PDFs found"`; `SnackbarHost`.
  - Auto-scrolling `LazyRow` breadcrumb (tap jumps to ancestor).
  - `NEEDS_ACCESS` empty state / banner (`"Grant access"` -> `LibrarySettingsSheet`); empty folder state (`"Rescan"`).
  - Single `LazyColumn` (keys `"d:"+relDir`, `"f:"+path`, `contentType`, `key(currentLocationKey)` `rememberLazyListState` seeded from & persisted to `LibraryViewModel` via `snapshotFlow` + `DisposableEffect`): `BrowserFolderRow` (`"N PDFs"` recursive, chevron) and `BrowserPdfRow` (`combinedClickable` long-press enters selection with leading checkbox, `LibraryThumbnailStore` cover, size + date, O(1) recent `"p.X/Y"` progress bar, `⋮` menu: `Open`, `Add to Recents`, `Favorite`/`Unfavorite`, `Details` dialog with `ReadingStats` time, `Copy path`, permanent `Delete` confirmation -> `File.delete()`/`DocumentsContract.deleteDocument` + metadata/cache cleanup). Opening PDF uses `resolveIdentityUri` and preserves `libraryLocation`, search state, and exact scroll position; navigating up restores parent scroll position and clears exited child positions.
- `LibrarySettingsSheet`: `"Add folder"` (`OpenDocumentTree`), `"Rescan all"`, per-root cards (`absPath ?: "via system permission"`, PDF count, last scanned age, `Active`/`Needs access` chip, Rescan, Remove confirmation [never deletes files]), `"Auto-rescan on app start"` switch, Android 11+ All Files Access row (refreshes on `ON_RESUME`).

### 8.2 Screen 2: `PdfReaderScreen` (`J/ui/reader/PdfReaderScreen.kt`)
1. Loading: centered `CircularProgressIndicator` + `"Rendering crisp document pages..."`.
2. Error & Recovery: error icon, `errorMessage`, `"Grant Storage Access"` (Android 11+, auto-retries on `ON_RESUME`), `"Browse / Re-locate File"` (`OpenDocument`), `"Return to Library"`.
3. Active Reader (`engine != null && totalPages > 0`):
   - `ReaderViewport`: canvas tinted by `colorTone` (`#E9ECEF`, `#EDE3D2`, `#0D0D0D`, `#DCDCDC`). Left-edge vertical drag (`44.dp` strip, `PointerEventPass.Initial`, single-pointer vertical slop) initializes from `resolveCurrentSystemBrightness` (`screen_brightness_float` + `config_screenBrightnessSettingMaximum`/OEM extended integer scale fallback `1023..65535`, locking initial brightness on drag start) when in Auto (`-1f`), adjusts unified brightness (`0.01f..1.0f`) gradually without jumping, and smoothly transitions below 1% into Extra Dim (`0f..0.80f`) — drag up increases brightness, drag down decreases — displaying centered `edge_brightness_hud`. Tap: if `tapZoneNavigation == true`, left 25% `stepPage(-1)`, right 25% `stepPage(1)`, middle 50% `toggleFullscreen()`; if `false`, any tap toggles fullscreen. Double-tap -> `toggleDoubleTapZoom(...)`. Pinch/Drag -> `updateZoomAndPan(...)` via `graphicsLayer`. Collects `viewModel.navCommands` (`MutableSharedFlow<Int>`) from tap zones & volume keys (`VOLUME_DOWN` +1, `VOLUME_UP` -1): `animateScrollBy(delta * viewportHeight * 0.85f)` (`VERTICAL`) or `animateScrollToPage((currentPage + delta).coerceIn(0, totalPages - 1))` (`HORIZONTAL`). Renders `LazyColumn` (`VERTICAL`, `withFrameNanos` auto-scroll `speed * 3.5f.dp.toPx()`/sec, ~1/s `recordUserActivity()`, pauses cleanly while `listState.isScrollInProgress` and catches `CancellationException` on touch so manual scrolling never disables auto-scroll) or `HorizontalPager` (`HORIZONTAL`). Pages are `PdfPageView` (draw-time `marginTrim`, optional clickable bottom-right bookmarkable badge turning primary + Bookmark icon). Overlays above pages, below bars: `warmFilterPercent > 0` (`Color(0xFFFF8A00)` at alpha `percent / 100f * 0.45f`), then `extraDimLevel > 0f` (`Color.Black` at alpha `extraDimLevel.coerceIn(0f, 0.80f)`).
   - `ReaderTopBar` (slide+fade when `!isFullscreen`): Back, Title (`fileName`) + Subtitle (`"<currentChapter> • X / Y"` or `"X / Y pages"`), Reset Zoom (if `zoomScale > 1.1f`), Settings (`SettingsSheet`), Document Info (`DocumentInfoDialog`), Fullscreen, Overflow `DropdownMenu` (`"Page Overview"`, `"Start Auto-Scroll"`/`"Stop Auto-Scroll"` enabled only in `VERTICAL`).
   - Bottom Overlay (`Column`: `auto_scroll_pill` + `ReaderBottomBar`):
     - `auto_scroll_pill` (animated when `isAutoScrolling`, stays visible in fullscreen with `navigationBarsPadding`): `-`, `"${autoScrollSpeed}x"`, `+` (1..20), `"Stop"`.
     - Page Seek Slider: `"1"` — `Slider` (up to 100 steps) — `"$totalPages"` (scrub live-updates pill, release commits `jumpToPage`).
     - Expandable Brightness Bar (`AnimatedVisibility(showBrightnessBar)`): Row 1: icon + `Slider` (`0.01f..1.0f`, seeded from `resolveCurrentSystemBrightness` when Auto) + `"Auto"`/`"XX%"` toggle (`window.attributes.screenBrightness`, `BRIGHTNESS_OVERRIDE_NONE` when `-1f`, reset on dispose); Row 2: Extra Dim icon + `Slider` (`0f..0.80f`) + `"Extra Dim"`/`"Dim XX%"` toggle (`0f` ↔ `0.50f`).
     - 6-Control Toolbar: (1) Reading Mode (`"Vertical"` ↔ `"Pages"`), (2) Orientation (`"Portrait"` ↔ `"Landscape"`, `SCREEN_ORIENTATION_*` + HUD), (3) Page Indicator Pill (`"X / Y"` -> `JumpToPageDialog`), (4) Bookmark Pill (tap toggles current page, long-press opens `BookmarksSheet`, shows count badge if `bookmarkCount > 0`), (5) Index (`IndexSheet`), (6) Brightness bar toggle.
   - Center HUD Feedback Pill: auto-dismisses (`1.6–1.8s`) for orientation & bookmark confirmations.
   - Exit Fullscreen FAB: top-right `40.dp` `FilledTonalIconButton` when `isFullscreen`.

### 8.3 Reader Modal Sheets & Dialogs
- `IndexSheet` (`J/ui/reader/IndexSheet.kt`): Fullscreen `Dialog` using `OutlineIndex`:
  - `TopAppBar`: Back, Title + subtitle (`"Indexing document structure in background..."` / `"X of Y chapters found"` / `"Y chapters found"` / `"Table of Contents"`), Close.
  - Search bar: 150 ms debounced `produceState` on `Dispatchers.Default` (`snapshotFlow` + `debounce`), matches `lowerTitles` & page forms (`query`, `p. X`, `page X`), max 300 results; `SearchResultRow` builds lazy breadcrumbs via `parentIndex`, bold `#1976D2` match highlight, `"Page X"` badge.
  - Sub-header (non-search & outline exists): `"CONTENTS"`, `"Expand All"`, `"Collapse All"` (single-assignment map).
  - Body: loading spinner, no-matches (`"Clear search"`), empty state (`"No index available"`, `"This PDF may not contain embedded bookmarks."`, monospace low-alpha `lastDiagnostic(uri)`, `"Retry indexing"` calling `reloadOutline()`), or `LazyColumn` computing visible rows in 1 linear pass (`BooleanArray` + `parentIndex`, `key = { flat[it].id }`, `contentType`), `FlatOutlineItemRow`, `chapterForPage` first-open auto-scroll & saved scroll/expansion.
- `BookmarksSheet` (`J/ui/reader/BookmarksSheet.kt`): `ModalBottomSheet`:
  - Header (`"Bookmarks"`, `"X saved page(s)"`, Close), Quick Bookmark card (`"Add Bookmark"`/`"Bookmarked"`), empty state (`"No bookmarks yet"`) or `LazyColumn` of rows (1-based badge, title defaulting to active chapter title if non-blank, `MMM d, yyyy • h:mm a` timestamp, tap-to-jump, Edit rename `AlertDialog` with single-line `OutlinedTextField`, delete).
- `PageOverviewSheet` (`J/ui/reader/PageOverviewSheet.kt`): Fullscreen `Dialog`:
  - `TopAppBar`: Back, Title + `"Page Overview • $totalPages pages"`, Close.
  - `LazyVerticalGrid(GridCells.Adaptive(100.dp))` (scrolled initially to `currentPage`), async 200 px `renderGridThumbnail`, primary border on `currentPage`, bookmark badge, 1-based footer label, tap-to-jump + dismiss.
- `SettingsSheet` (`J/ui/reader/SettingsSheet.kt`): `ModalBottomSheet` (scrollable, 7 sections):
  1. Text Clarity & Sharpness: 3 `OutlinedCard`s (`BALANCED`, `HIGH_CLARITY`, `ULTRA_HD`) + `Subpixel Antialiasing` `Switch`.
  2. Fluid Scrolling & Performance: 3 `FilterChip`s (`OFF`, `STANDARD`, `AGGRESSIVE`).
  3. Page Color Tone & Themes: 4 swatch cards (`ORIGINAL`, `SEPIA`, `INVERTED_DARK`, `HIGH_CONTRAST`), `Page Spacing` chips (`Tight`, `Normal`, `Wide`), `Page Number Badges` `Switch`.
  4. Orientation & Screen Wake: `ReaderOrientation` chips (`Portrait`, `Landscape`) + `Keep Screen On` chips (`Off`, `5m`, `15m`, `Always`).
  5. Double-Tap Smart Zoom: 5 cards (`SMART_COLUMN`, `COMPACT_1_8X`, `STANDARD_2_1X`, `LARGE_2_3X`, `MAX_2_6X`).
  6. Memory & Cache Diagnostics: `"X.X MB / Y MB allocated"` + `"Flush Cache"`.
  7. Reading Comfort: `MarginTrim` `FilterChip`s (`Off`, `Light`, `Medium`, `Strong`), `Warm Eye-Care Filter` `Slider` (`0..60%`), `Tap-Zone Navigation` `Switch`, `Volume Key Navigation` `Switch`, `Auto-Scroll Speed` `Slider` (`1..20x`, 18 steps).
- `JumpToPageDialog` (`J/ui/reader/JumpToPageDialog.kt`): `AlertDialog` with `-`, numeric `OutlinedTextField` (`ImeAction.Go`), `+`, quick-step chips (`1`, `-10`, `+10`, `$totalPages` — `-10`/`+10` hidden if `totalPages < 10`), `"Go"`, `"Cancel"`.
- `DocumentInfoDialog` (`J/ui/reader/DocumentInfoDialog.kt`): `AlertDialog` (`File Name`, `Total Pages`, `File Size`, `Source` [`Demo Showcase PDF` vs `Local Storage`], `Time Spent`), `"Close"`.

### 8.4 `testTag` Inventory
- HomeScreen: `library_manage_btn`, `library_strip`, `library_root_<id>`, `recent_search_input`, `in_library_search_header`, `home_library_search_item_<hash>`, `home_view_mode_toggle_btn`, `home_settings_btn`, `home_more_menu_btn`, `continue_reading_card`, `library_filter_<name>`, `library_sort_btn`, `recent_pdf_grid_card_<hash>`, `favorite_grid_<hash>`, `delete_grid_recent_btn_<hash>`, `recent_pdf_card_<hash>`, `favorite_<hash>`, `delete_recent_btn_<hash>`, `accent_<name>`, `backup_export_btn`, `backup_import_btn`, `backup_storage_used_text`, `clear_lib_thumbs_btn`, `clear_toc_cache_btn`, `backup_export_confirm_btn`, `backup_result_ok_btn`, `home_search_pdf_fab`, `home_add_pdf_fab`, `empty_open_demo_btn`
- LibraryBrowserScreen & LibrarySettingsSheet: `library_selection_close_btn`, `library_selection_select_all_btn`, `library_selection_add_recents_btn`, `library_selection_favorite_btn`, `library_selection_delete_btn`, `breadcrumb_<index>`, `library_browser_list`, `library_folder_<hash>`, `library_pdf_<hash>`, `library_pdf_menu_<hash>`, `library_settings_sheet`, `library_add_folder_btn`, `library_rescan_all_btn`, `library_root_card_<id>`
- PdfReaderScreen: `pdf_loading_indicator`, `pdf_error_grant_storage_btn`, `pdf_error_relocate_btn`, `pdf_error_back_btn`, `exit_fullscreen_btn`, `vertical_pdf_list`, `horizontal_pdf_pager`, `pdf_page_<idx>`, `page_number_badge_<1-idx>`, `edge_brightness_hud`, `reader_back_btn`, `reset_zoom_btn`, `top_settings_btn`, `document_info_btn`, `reader_fullscreen_btn`, `reader_more_btn`, `menu_page_overview`, `menu_auto_scroll`, `auto_scroll_pill`, `auto_scroll_slower_btn`, `auto_scroll_faster_btn`, `auto_scroll_stop_btn`, `page_seeking_slider`, `brightness_slider`, `brightness_auto_toggle`, `extra_dim_slider`, `extra_dim_toggle`, `toggle_reading_mode_btn`, `toggle_orientation_btn`, `page_indicator_pill`, `bottom_bookmark_btn`, `bottom_index_btn`, `bottom_brightness_btn`
- Sheets & Dialogs: `overview_back_btn`, `overview_close_btn`, `page_overview_grid`, `overview_page_<idx>`, `index_back_btn`, `index_close_btn`, `index_search_input`, `clear_index_search_btn`, `expand_all_btn`, `collapse_all_btn`, `index_loading_indicator`, `index_search_results_list`, `search_item_<id>`, `index_outline_list`, `outline_item_<id>`, `toggle_expand_<id>`, `index_retry_btn`, `close_bookmarks_btn`, `sheet_toggle_current_bookmark_btn`, `bookmarks_list`, `bookmark_item_<idx>`, `edit_bookmark_<idx>`, `delete_bookmark_<idx>`, `close_settings_btn`, `sharpness_<name>`, `switch_print_mode`, `prefetch_<name>`, `tone_<name>`, `switch_show_badges`, `keep_awake_<label>`, `zoom_mode_<name>`, `flush_cache_btn`, `margin_trim_<name>`, `warm_filter_slider`, `switch_tap_zone_nav`, `switch_volume_key_nav`, `auto_scroll_speed_slider`, `jump_decrement_btn`, `page_number_input`, `jump_increment_btn`, `jump_confirm_btn`, `jump_cancel_btn`, `dismiss_info_btn`

## 9. Feature-to-File Quick Lookup Index

| Feature / Subsystem | Primary File(s) | Supporting File(s) |
|---|---|---|
| Entry, `VIEW`/`SEND` Intents, Picker | `MainActivity.kt` | `PdfDocumentStorage.kt`, `PdfRepository.kt`, `AndroidManifest.xml` |
| Library Roots, Scanner, Thumbnails & Relink | `data/library/{LibraryRepository,LibraryScanner,LibraryThumbnailStore}.kt` | `LibraryModels.kt`, `LibraryStorage.kt`, `FilePathResolver.kt`, `PdfRepository.kt` |
| Library Browser, Selection, Strip & Settings | `ui/library/{LibraryBrowserScreen,LibrarySettingsSheet,LibraryViewModel}.kt` | `HomeScreen.kt`, `MainActivity.kt`, `AppSettings.kt`, `LibraryRepository.kt`, `LibraryThumbnailStore.kt`, `ReadingStats.kt` |
| ZIP Backup, Storage & Auto-Backup Rules | `data/backup/BackupManager.kt`, `R/xml/{backup_rules,data_extraction_rules}.xml` | `HomeScreen.kt`, `HomeViewModel.kt`, `PdfBookmarkDao.kt`, `PdfOutlineParser.kt`, `LibraryThumbnailStore.kt` |
| Recents Grid/List, Search, Filter/Sort, Favorites | `ui/home/{HomeScreen,HomeViewModel}.kt` | `PdfRepository.kt`, `PdfDirectoryStorage.kt`, `RecentPdfJsonStorage.kt`, `PdfRecentDao.kt`, `AppSettings.kt`, `ReadingStats.kt`, `LibraryRepository.kt` |
| Zero-Copy URI / Path / MediaStore Opening | `data/pdf/{PdfDocumentStorage,FilePathResolver}.kt` | `PdfDirectoryStorage.kt`, `AppDatabase.kt` |
| Native Rendering, Supersampling, Tones & Cache | `data/pdf/PdfRendererEngine.kt` | `ui/components/PdfPageView.kt`, `AppSettings.kt` |
| TOC Extraction (Raw + Pdfium + Cache) | `data/pdf/{RawPdfOutlineReader,PdfOutlineParser,OutlineHelper}.kt` | `ui/reader/{IndexSheet,PdfReaderViewModel}.kt` |
| Dual Reading Modes & Gestures | `ui/reader/{PdfReaderScreen,PdfReaderViewModel}.kt` | `PdfDocumentSource.kt`, `PdfPageView.kt` |
| Smart Column Zoom & Pinch/Pan | `ui/reader/PdfReaderViewModel.kt` (`toggleDoubleTapZoom`, `updateZoomAndPan`) | `PdfReaderScreen.kt`, `AppSettings.kt` |
| Bookmarks (Toggle, Rename & Sheet) | `ui/reader/BookmarksSheet.kt`, `data/db/PdfBookmarkDao.kt` | `PdfBookmarkEntity.kt`, `PdfRepository.kt`, `PdfPageView.kt` |
| Reader Settings, Wake, Orientation, Brightness | `ui/reader/SettingsSheet.kt`, `data/settings/AppSettings.kt` | `PdfReaderViewModel.kt`, `PdfReaderScreen.kt`, `MainActivity.kt` |
| Reading Time & Daily Streak | `data/settings/ReadingStats.kt`, `ui/reader/PdfReaderViewModel.kt` | `PdfReaderScreen.kt`, `DocumentInfoDialog.kt`, `HomeScreen.kt` |
| Page Jump, Metadata & Page Overview Grid | `ui/reader/{JumpToPageDialog,DocumentInfoDialog,PageOverviewSheet}.kt` | `PdfReaderScreen.kt`, `PdfRepository.kt`, `PdfRendererEngine.kt` |
| Comfort (Trim, Warm, Shortcuts, Auto-Scroll) | `ui/reader/{SettingsSheet,PdfReaderScreen}.kt`, `ui/components/PdfPageView.kt` | `PdfReaderViewModel.kt`, `AppSettings.kt`, `MainActivity.kt` |
| App Themes & Accents | `ui/theme/{Theme,Color}.kt` | `AppSettings.kt`, `HomeScreen.kt`, `MainActivity.kt` |
| Demo PDF Generation | `data/pdf/SamplePdfGenerator.kt` | `HomeViewModel.kt`, `PdfDocumentStorage.kt` |
