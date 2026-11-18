# MK PDF Reader — Complete Technical Architecture & Source-of-Truth (`mainbrain.md`)

> **Target Codebase:** `Kamruzzaman-Milton/MK-Pdf-Reader` (Android / Kotlin / Jetpack Compose / Material 3)  
> **Primary Purpose:** Comprehensive, self-contained technical specification of every module, file, feature, engine, UI hierarchy, state flow, persistence schema, and default configuration in the codebase.

---

## 1. Identity, Build Configuration, Manifest & Dependencies

### 1.1 Application Identity & SDK Targets
| Property | Value | Source File |
|---|---|---|
| **App Name (`app_name`)** | `MK PDF Reader` | `app/src/main/res/values/strings.xml` |
| **Root Project Name** | `MK PDF Reader` | `settings.gradle.kts` |
| **Namespace** | `com.example` | `app/build.gradle.kts` |
| **Application ID** | `com.aistudio.mkpdfreader.app` | `app/build.gradle.kts` |
| **Version Code / Name** | `1` / `"1.0"` | `app/build.gradle.kts` |
| **Compile SDK / Target SDK** | `35` / `35` | `app/build.gradle.kts` |
| **Min SDK** | `24` | `app/build.gradle.kts` |
| **Java Compatibility** | `JavaVersion.VERSION_11` | `app/build.gradle.kts` |
| **Test Runner** | `androidx.test.runner.AndroidJUnitRunner` | `app/build.gradle.kts` |
| **Platform Metadata (`metadata.json`)** | `name`: `"MK PDF Reader"`, `majorCapabilities`: `["MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API"]` | `metadata.json` |

### 1.2 Gradle Toolchain & Build Properties
- **AGP:** `9.1.1` (`com.android.application`) — `gradle/libs.versions.toml`
- **Kotlin:** `2.2.10` (`org.jetbrains.kotlin.plugin.compose`) — `gradle/libs.versions.toml`
- **KSP:** `2.3.5` (`com.google.devtools.ksp`) — `gradle/libs.versions.toml`
- **Repositories (`settings.gradle.kts`):** `google()`, `mavenCentral()`, `https://jitpack.io` (hosts `barteksc:pdfium-android`).
- **Plugins in `app/build.gradle.kts`:** `android.application`, `kotlin.compose`, `google.devtools.ksp`, `roborazzi` (`1.59.0`), `secrets` (`2.0.1`), `google.services` (`4.5.0`, configured with `MissingGoogleServicesStrategy.WARN`).
- **Signing, Shrinking & Packaging (`app/build.gradle.kts` & `app/proguard-rules.pro`):** `release` (uses `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` env vars if present, falling back to `${rootDir}/debug.keystore`), `debugConfig` (`${rootDir}/debug.keystore`, password `"android"`, alias `"androiddebugkey"`). Both `release` and `debug` `buildTypes` enable `isMinifyEnabled = true` and `isShrinkResources = true` with `proguard-android-optimize.txt` + `proguard-rules.pro` (`isCrunchPngs = false` in `release`). `defaultConfig` sets `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }`; `android` sets `androidResources { localeFilters += listOf("en") }` and `packaging.resources.excludes` for `/META-INF/{AL2.0,LGPL2.1}`, `DebugProbesKt.bin`, `kotlin-tooling-metadata.json`. `app/proguard-rules.pro` keeps `com.shockwave.**` (classes & classmembers), `com.shockwave.pdfium.**` (for JNI reflection in `PdfOutlineParser`), native methods, and `SourceFile,LineNumberTable`.
- **Persistent Debug Keystore Sync (`app/signing/` & `app/build.gradle.kts`):** `app/signing/persistent-debug.keystore.base64` pins the Base64-encoded debug signing keystore across GitHub exports and Google AI Studio account imports. Configuration-cache-safe task `:app:syncPersistentKeystore` (`SyncPersistentKeystoreTask`) runs before `preBuild`, `validateSigningDebug`, and `packageDebug` to sync `${rootDir}/debug.keystore` and `${rootDir}/debug.keystore.base64` from `app/signing/persistent-debug.keystore.base64` (falling back to `${rootDir}/debug.keystore.base64` if unset). See `app/signing/README.md`.
- **Secrets Plugin (`app/build.gradle.kts`):** `propertiesFileName = ".env"`, `defaultPropertiesFileName = ".env.example"`, `ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")`. `buildFeatures { compose = true; buildConfig = true }`.
- **JVM, Gradle & Git Hygiene (`gradle.properties`, `.gitignore`, `app/.gitignore`):** `-Xmx4g -Dfile.encoding=UTF-8`, `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `org.gradle.workers.max=4`, `android.nonTransitiveRClass=true`, `kotlin.compiler.execution.strategy=in-process`, `googleServices.missing.passthrough=true`. Root `.gitignore` and `app/.gitignore` exclude transient `.gradle/`, `**/build/`, `.cxx/`, `.idea/`, `local.properties`, and binary keystores while explicitly keeping `debug.keystore.base64`, `app/signing/`, `.env.example`, and `gradle/`.

### 1.3 AndroidManifest Configuration (`app/src/main/AndroidManifest.xml`)
- **Declared Permissions:**
  - `android.permission.READ_EXTERNAL_STORAGE` (`android:maxSdkVersion="32"`)
  - `android.permission.MANAGE_EXTERNAL_STORAGE` (`tools:ignore="ScopedStorage"`)
  - *Note:* No `INTERNET` permission is declared; the application operates 100% offline.
- **Application Attributes:** `android:allowBackup="true"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:fullBackupContent="@xml/backup_rules"`, `android:icon="@mipmap/ic_launcher"`, `android:roundIcon="@mipmap/ic_launcher_round"`, `android:supportsRtl="true"`, `android:requestLegacyExternalStorage="true"`, `android:theme="@style/Theme.MyApplication"` (XML parent: `android:Theme.DeviceDefault.NoActionBar` in `res/values/themes.xml`).
- **Single Activity (`com.example.MainActivity`):**
  - `android:exported="true"`
  - `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` (prevents Activity recreation on manual/system orientation swaps, keeping `PdfRendererEngine` and Compose state alive).
  - **Intent Filter 1 (`MAIN` / `LAUNCHER`):** Standard app launcher entry.
  - **Intent Filter 2 (`ACTION_VIEW`):** `DEFAULT`, `BROWSABLE`, `mimeType="application/pdf"`, schemes `content` and `file`.
  - **Intent Filter 3 (`ACTION_SEND`):** `DEFAULT`, `mimeType="application/pdf"`.

### 1.4 Dependency Matrix (`app/build.gradle.kts` & `gradle/libs.versions.toml`)
| Dependency | Version / Ref | Runtime Usage Status |
|---|---|---|
| `androidx.compose.bom` / `material3` / `ui` / `material-icons-core` / `material-icons-extended` | BOM `2024.09.00` | **Active** — All Compose UI & Material Icons |
| `androidx.activity:activity-compose` | `1.10.1` | **Active** — `setContent`, `enableEdgeToEdge`, `BackHandler`, `rememberLauncherForActivityResult` |
| `androidx.core:core-ktx` | `1.15.0` | **Active** — Core KTX utilities |
| `androidx.lifecycle:lifecycle-runtime-ktx` / `runtime-compose` / `viewmodel-compose` | `2.8.7` | **Active** — `AndroidViewModel`, `collectAsStateWithLifecycle`, `LocalLifecycleOwner` |
| `androidx.room:room-runtime` / `room-ktx` / `room-compiler` (KSP) | `2.7.0` | **Active** — `AppDatabase`, `PdfRecentDao`, `PdfBookmarkDao` |
| `kotlinx-coroutines-android` / `kotlinx-coroutines-core` | `1.10.2` | **Active** — Async PDF rendering, prefetch, disk I/O, keep-awake timers |
| `com.github.barteksc:pdfium-android` (excludes `com.android.support`) | `1.9.0` | **Active (Fallback)** — Native C/C++ fallback outline/TOC parser in `PdfOutlineParser` when `RawPdfOutlineReader` fails |
| `accompanist-permissions`, `camera-*`, `datastore-preferences`, `navigation-compose`, `coil-compose`, `play-services-location`, `firebase-firestore`, `firebase-auth`, `credentials`, `googleid` | Commented out | **Disabled** — Commented out in `app/build.gradle.kts` |
| `junit` (`4.13.2`), `robolectric` (`4.16.1`), `roborazzi` (`1.59.0`), `kotlinx-coroutines-test`, `androidx.compose.ui:ui-test-junit4` | Test only | **Active in `src/test/`** |

---

## 2. Complete Codebase File Map

### 2.1 Kotlin Source Files (`app/src/main/java/com/example/`)
| File Path | Package / Types | Primary Responsibility |
|---|---|---|
| `MainActivity.kt` | `OpenPdfDocumentContract`, `MainActivity`, `MainAppContent`, `navigateUpLibraryLocation` | Entry point, edge-to-edge setup, startup cache purge, `VIEW`/`SEND` Intent URI extraction (`pendingIntentUri` for both cold start and `onNewIntent`), routing every PDF open through `resolveIdentityUri` before setting `currentPdfUriString`, root screen switcher (`PdfReaderScreen` -> `LibraryBrowserScreen` -> `HomeScreen`), `LibrarySettingsSheet` hosting, `LibraryBrowserScreen` selection/action callback wiring, `ACTION_OPEN_DOCUMENT` picker with persistable read flags and `ACTION_GET_CONTENT` fallback, `FLAG_KEEP_SCREEN_ON` window control, system `BackHandler` for reader and library up-navigation, hardware volume-key page navigation (`onKeyDown`/`onKeyUp`). |
| `data/backup/BackupManager.kt` | `BackupExportSummary`, `BackupImportSummary`, `BackupManager` | Portable ZIP backup export/import (`MKPDFReader_backup_<yyyyMMdd>.zip`) with manifest, type-tagged settings/stats JSON, library roots, recents descriptors, Room bookmarks, and optional recents thumbnails; whitelist path validation (zip-slip safe), in-memory archive parsing prior to merge-only restore, and `computeStorageUsedBytes` cache/data readout. |
| `data/db/AppDatabase.kt` | `AppDatabase` | Room `@Database` (`mk_pdf_reader.db`, `version = 4`, `exportSchema = false`, `.fallbackToDestructiveMigration()`, `.allowMainThreadQueries()`). Exposes `pdfRecentDao()` and `pdfBookmarkDao()`. |
| `data/db/PdfRecentItem.kt` | `PdfRecentItem` | Room `@Entity(tableName = "recent_pdfs")` representing a recently opened PDF, its reading progress (`lastReadPage`, `scrollOffsetFraction`, `readingMode`), metadata (`fileName`, `fileSize`, `pageCount`), and paths (`pdf_path` PK, `uriString`, `localFilePath`, `thumbnailPath`). |
| `data/db/PdfRecentDao.kt` | `PdfRecentDao` | Room DAO for `recent_pdfs`: Flow & sync queries, path/URI/localPath lookups, `insertOrUpdate`, `updateReadingProgress`, `deleteRecent`, `clearAll`. |
| `data/db/PdfBookmarkEntity.kt` | `PdfBookmarkEntity` | Room `@Entity(tableName = "pdf_bookmarks")` with unique composite index on `(uriString, pageIndex)`, `id` autogenerate PK, 0-indexed `pageIndex`, 1-indexed `pageNumber`, optional `title`, `createdAt`. |
| `data/db/PdfBookmarkDao.kt` | `PdfBookmarkDao` | Room DAO for `pdf_bookmarks`: reactive `Flow<List<PdfBookmarkEntity>>`, `Flow<List<Int>>`, `getAll()`, single-page query, `insertBookmark`, `updateTitle`, `deleteBookmarkByPage`, `deleteBookmark`, `deleteAllBookmarksForUri`. |
| `data/library/LibraryModels.kt` | `LibraryRootStatus`, `LibraryRoot`, `LibraryPdf`, `ScanState`, `FolderEntry`, `DirNode`, `LibraryTree` | Models for folder-based PDF library roots (12-char SHA-256 `id`), scanned PDF entries (`relDir` relative `/`-separated folder), scan progress state, and prebuilt `"rootId|relDir"` `LibraryTree` lookup map. |
| `data/library/LibraryStorage.kt` | `LibraryStorage` | Defensive `AtomicFile` + `org.json` persistence under `filesDir/library/` (`roots.json` + compact-key `index_<rootId>.json` using `n,d,p,u,s,m`). |
| `data/library/LibraryScanner.kt` | `ScanResult`, `LibraryScanner` | Cancellable, iterative (`ArrayDeque`, max depth 25) PDF directory scanner on `Dispatchers.IO` supporting FILE mode (`File.listFiles`) and SAF mode (`DocumentsContract` tree queries), skipping `.` dirs and top-level `Android`, throttling progress every 25 files. |
| `data/library/LibraryRepository.kt` | `LibraryRepository` | Singleton (`getInstance(context)` with `applicationContext`) managing library `roots`, `scanState`, and `tree` `StateFlow`s, root deduplication/containment, `Mutex`-serialized scans, `Dispatchers.Default` tree/lookup construction, `removeEntries`, `mergeImportedRoots`, prebuilt lowercase-name `searchByName`, and `HashMap` `findByNameAndSize`. |
| `data/library/LibraryThumbnailStore.kt` | `LibraryThumbnailStore` | Lean lazy library thumbnail manager using raw `PdfRenderer` (never `PdfRendererEngine`) gated by `Semaphore(1)` and 8s timeout; checks recents `thumbs/thumb_<docKey>.png` then `filesDir/lib_thumbs/<docKey>.jpg` (200×max300 JPEG q80), cached in an 8 MB `LruCache<String, ImageBitmap>` with a 40 MB disk LRU cap and session failure set. |
| `data/pdf/PdfDocumentSource.kt` | `PdfDocumentInfo`, `ReadingMode` | Core models: `PdfDocumentInfo(uri, fileName, fileSize, pageCount, isSample)` and `ReadingMode { VERTICAL, HORIZONTAL }`. |
| `data/pdf/PdfRendererEngine.kt` | `PdfRendererEngine` | Native Android `android.graphics.pdf.PdfRenderer` wrapper. Thread-safe rendering via `Mutex`, supersampled bitmap scaling, `ColorMatrix` eye-care color filters, heap-proportional `LruCache`, dedicated 24 MB `gridThumbCache` (`renderGridThumbnail`), background page prefetching, and cover thumbnail rendering. |
| `data/pdf/PdfOutlineParser.kt` | `PdfOutlineItem`, `PdfOutlineParser` | PDF Table of Contents (TOC) extractor + 2-tier cache (L1 `ConcurrentHashMap`, L2 `filesDir/toc_cache/toc2_<key>.json`) + `isPlausible` validation + `diagnostics`/`lastDiagnostic` + `SAMPLE_GUIDE_OUTLINE` + `clearAllOutlineCaches`. Cascade: L1 -> L2 -> sample guide -> `RawPdfOutlineReader` -> Pdfium (iterative JNI reflection, then recursive fallback). |
| `data/pdf/RawPdfOutlineReader.kt` | `RawPdfOutlineReader` | Pure-Kotlin, zero-native-dependency PDF outline/TOC reader over `FileChannel`, `Inflater`, and `java.nio.charset`. Supports PDF tokenization, classic xref & compressed xref streams (with PNG predictor undo), object streams (8-entry LRU), iterative DFS outline walk (depth <= 30, cap 100k), named destinations (`/Dests` dict & `/Names`/`/Dests` name tree), and lazy iterative `/Pages` map. |
| `data/pdf/OutlineHelper.kt` | `OutlineIndex`, `OutlineHelper` | Precomputed immutable `OutlineIndex` (`flat`, `parentIndex`, `lowerTitles`, `chapterForPage`) + `buildIndex` + O(1)/`parentIndex` overloads and tree helpers (`getCurrentChapter`, `getRootChapter`, `getNextChapter`, `flatten`). |
| `data/pdf/PdfDocumentStorage.kt` | `PdfDocumentStorage` | Zero-copy document descriptor opener (`openFileDescriptor` multi-strategy recovery including direct `dirEntry.path` check, `openInputStream`), persistable URI permission acquirer, `MediaStore` lookup (`findMediaStoreUriForPath`), SHA-256 `getDocumentKey`, legacy storage purger (`purgeExcessStorageAndCaches`). |
| `data/pdf/FilePathResolver.kt` | `FilePathResolver` | Resolves `file://`, `content://`, and SAF tree URIs (`resolveTreeUriToPath`, `resolveExternalDocIdToPath`, ExternalStorage, Downloads, MediaProvider, `_data` column, MiXplorer `/ext_storage/` & `/root/`, public Downloads/Documents fallback) to absolute filesystem paths. |
| `data/pdf/PdfDirectoryStorage.kt` | `PdfDirectoryStorage`, `PdfDirectoryEntry` | Authoritative persistent JSON-per-file + master index store under `context.filesDir/pdf_directories/` (`dir_<docKey>.json` + `directory_index.json`) using `AtomicFile`. Never auto-purged. |
| `data/pdf/RecentPdfJsonStorage.kt` | `RecentPdfJsonStorage`, `RecentPdfJsonEntry` | Secondary synchronous crash-safe JSON mirror at `context.filesDir/recent_books.json` using `AtomicFile`. |
| `data/pdf/SamplePdfGenerator.kt` | `SamplePdfGenerator` | Generates an 8-page A4 `MK_PDF_Reader_Guide.pdf` in `context.filesDir` using `android.graphics.pdf.PdfDocument`. |
| `data/repository/PdfRepository.kt` | `PdfRepository` | Coordinates `PdfDirectoryStorage`, `RecentPdfJsonStorage`, `PdfRecentDao`, `PdfBookmarkDao`, `FilePathResolver`, `PdfDocumentStorage`, `LibraryRepository`, and `PdfOutlineParser` (holding `applicationContext` only). Shares process-wide `recentPdfs` flow, manages startup sync, always-resolved absolute path persistence, `resolveIdentityUri`, `tryRelink`, metadata resolution, thumbnail PNG saving, progress updates, and bookmark CRUD (`deleteAllBookmarksForUri`). |
| `data/settings/AppSettings.kt` | `RenderSharpness`, `PrefetchMode`, `PageColorTone`, `PageSpacing`, `KeepAwakeOption`, `ReaderOrientation`, `AppThemeMode`, `RecentViewMode`, `DoubleTapZoomMode`, `MarginTrim`, `AppAccent`, `LibrarySort`, `LibraryFilter`, `BrowserSort`, `AppSettings`, `SettingsRepository` | Defines all 14 setting enums, `AppSettings` data class with defaults (comfort, accent, recents sort/filter/favorites, library browser sort/folders-first/auto-rescan), and `SettingsRepository` singleton backed by `SharedPreferences("mk_pdf_settings")` and `StateFlow<AppSettings>` (`addFavoriteKey`, `removeFavoriteKey`, `reloadFromPrefs`). |
| `data/settings/ReadingStats.kt` | `ReadingStats` | Singleton object backed by `SharedPreferences("mk_pdf_stats")` tracking per-document reading time (`doc_<docKey>`), per-day totals (`day_<epochDay>`), `totalFor`, `todayMs`, `streakDays` (>= 60,000 ms/day), and `formatDuration`. |
| `ui/home/HomeViewModel.kt` | `HomeViewModel` | `AndroidViewModel` exposing `recentPdfs`, `visibleRecents`, `librarySearchResults` (`combine(_homeSearchQuery, libraryRepository.tree)`), `storageUsedBytes`, `isBackupBusy`, `appSettings`, `isGeneratingSample`, startup sequential `tryRelink` pass, `resolveIdentityUri`, one-shot process-wide `warmUpOutlines()`, plus actions for view mode toggle, theme/accent selection, library sort/filter, favorite toggle, single/bulk recent deletion, backup export/import, cache clearing, recents refresh, and demo PDF generation. |
| `ui/home/HomeScreen.kt` | `HomeScreen`, `LibraryFolderStrip`, `StorageAccessBanner`, `ContinueReadingSection`, `RecentHeaderRow`, `LibraryFilterSortRow`, `PdfCoverThumbnail` (`internal`), `RecentPdfGridCard`, `RecentPdfCard`, `HomeSettingsSheet`, `AccentColorRow`, `ViewModeSelectorCard`, `ThemeOptionCard`, `NoSearchResultsView`, `NoFilterResultsView`, `EmptyRecentsView`, `InLibrarySearchHeader`, `HomeLibrarySearchRow` | Library/Home UI: top bar + inline search bar (with appended `"In your library"` up-to-20 results section when query `>= 2` chars), overflow menu, top `LibraryFolderStrip` root-folder cards (hidden in search), optional All-Files-Access banner, Continue Reading hero card + daily/streak summary, filter chips + sort dropdown, 2-column Grid / List views with favorite star & delete buttons, dual bottom FABs, Display & Settings bottom sheet (with Accent Color swatches, Library Folders entry, and Backup & Data section), backup export/import launchers & dialogs, and Clear Recents confirmation dialog. |
| `ui/library/LibraryViewModel.kt` | `BrowserItem`, `BrowserListing`, `LibraryViewModel` | `AndroidViewModel` backed by `LibraryRepository`, `PdfRepository`, and `SettingsRepository`, exposing `roots`, `scanState`, `tree`, `appSettings`, `selectedKeys`, and `listing` (`combine(tree, settings, location)` on `Dispatchers.Default` with natural digit-chunk comparator `naturalCompare`), plus a 2-second delayed stale-root auto-rescan (6 hours) when `libraryAutoRescan` is enabled and file actions (`toggleSelection`, `selectAll`, `clearSelection`, `addToRecents`, `toggleFavorite`, `favoriteSelected`, `deletePdfs`). |
| `ui/library/LibraryBrowserScreen.kt` | `LibraryBrowserScreen`, `DetailRow`, `BrowserFolderRow`, `BrowserPdfRow` | In-app folder browser for a selected `LibraryRoot`: `TopAppBar` (normal, search, and selection modes), auto-scrolling breadcrumb `LazyRow`, scan progress bar, `SnackbarHost`, `NEEDS_ACCESS` recovery card/banner, lazy `LibraryThumbnailStore` cover thumbnails, per-row `⋮` menu (`Open`, `Add to Recents`, `Favorite`/`Unfavorite`, `Details` dialog with `ReadingStats`, `Copy path`, `Delete`), permanent delete confirmation dialog, and single `LazyColumn` with stable keys (`d:`/`f:`), `contentType`, and recent reading progress enrichment. |
| `ui/library/LibrarySettingsSheet.kt` | `LibrarySettingsSheet`, `LibraryRootManageCard` | `ModalBottomSheet` for managing library folders (`OpenDocumentTree` add, rescan all/root, remove root confirmation without deleting files, `libraryAutoRescan` toggle, and Android 11+ All-Files-Access status/prompt). |
| `ui/reader/PdfReaderViewModel.kt` | `PdfReaderUiState`, `PdfReaderViewModel` | `AndroidViewModel` managing `PdfRendererEngine` lifecycle (with `tryRelink` + single retry before error), parallel outline extraction + `OutlineIndex` building + `reloadOutline()`, page/mode restoration, bookmark Flow observation & `renameBookmark`, prefetch triggering, keep-awake timer coroutine, 10-second `ReadingStats` ticker (`setForeground`), double-tap smart column zoom math, pinch/pan state, brightness state, orientation, HUD feedback, `navCommands` / `stepPage`, auto-scroll state (`isAutoScrolling`), and reader dialogs/sheets flags. |
| `ui/reader/PdfReaderScreen.kt` | `PdfReaderScreen`, `ReaderViewport`, `ReaderTopBar`, `ReaderBottomBar` | Full PDF reading UI: lifecycle `ON_RESUME`/`ON_PAUSE` foreground tracking, loading/error states (with Storage Access & Relocate recovery), `ReaderViewport` (`LazyColumn` vertical / `HorizontalPager` horizontal) with tap-zone/double-tap/pinch-pan gestures, `navCommands` collector, frame-paced auto-scroll loop, warm eye-care filter overlay, animated `ReaderTopBar`, floating `auto_scroll_pill`, `ReaderBottomBar`, HUD pill, exit fullscreen FAB, and modal sheet/dialog hosting. |
| `ui/reader/PageOverviewSheet.kt` | `PageOverviewSheet`, `PageOverviewCell` | Fullscreen `Dialog` presenting an adaptive grid (`GridCells.Adaptive(100.dp)`) of 200 px page thumbnails loaded asynchronously via `renderGridThumbnail`, current-page primary highlight border, bookmark indicator, and tap-to-jump navigation. |
| `ui/reader/IndexSheet.kt` | `IndexSheet`, `SearchResultRow`, `FlatOutlineItemRow` | Fullscreen `Dialog` consuming `OutlineIndex`: single linear-pass visible-row calculation, 150ms debounced `Dispatchers.Default` search (capped at 300 results) with lazy on-screen breadcrumbs, single-assignment Expand/Collapse All, saved scroll/expansion state, `chapterForPage` auto-scroll, and empty state with diagnostic + `index_retry_btn`. |
| `ui/reader/BookmarksSheet.kt` | `BookmarksSheet` | `ModalBottomSheet` displaying current-page bookmark toggle card, empty-state hint, sorted list of saved `PdfBookmarkEntity` entries with jump-on-tap, inline edit/rename dialog (`edit_bookmark_<pageIndex>`), and delete buttons. |
| `ui/reader/SettingsSheet.kt` | `SettingsSheet`, `SectionHeader` | `ModalBottomSheet` containing 7 reader configuration sections: Text Clarity & Sharpness (+ Subpixel Antialiasing), Fluid Scrolling & Performance, Page Color Tone & Themes (+ Page Spacing & Badges), Orientation & Screen Wake, Double-Tap Smart Zoom, Memory & Cache Diagnostics, and Reading Comfort (Margin Trimming, Warm Filter, Tap-Zone & Volume Key Navigation, Auto-Scroll Speed). |
| `ui/reader/JumpToPageDialog.kt` | `JumpToPageDialog` | `AlertDialog` for jumping to a 1-indexed page via `-`/`+` steppers, numeric `OutlinedTextField`, and quick-step chips (`1`, `-10`, `+10`, `totalPages`). |
| `ui/reader/DocumentInfoDialog.kt` | `DocumentInfoDialog`, `InfoRow` | `AlertDialog` displaying `File Name`, `Total Pages`, `File Size`, `Source` (`Demo Showcase PDF` vs `Local Storage`), and `Time Spent` (`ReadingStats.formatDuration`). |
| `ui/components/PdfPageView.kt` | `computeTrimmedAspectRatio`, `PdfPageView` | Composable page surface: computes trimmed display aspect ratio (`MarginTrim`), supersampled target dimensions, checks synchronous L1 bitmap cache, triggers async `engine.renderPage` on `Dispatchers.IO`, crops margins at draw time via `Canvas.drawImage(..., FilterQuality.High)` without mutating cached bitmaps, and draws optional clickable bookmarkable page-number badge. |
| `ui/theme/Color.kt` | Color constants | Scarlet/Crimson brand palette (`PrimaryRed`, `PrimaryRedDark`, `SecondaryRed`, `AccentCoral`, `AccentCoralLight`), light/dark neutrals, and canvas constants (`CanvasLight`, `CanvasDark`, `CanvasSepia`, `TextSepia`). |
| `ui/theme/Theme.kt` | `AmoledDarkColorScheme`, `DarkColorScheme`, `LightColorScheme`, `applyAccentToColorScheme`, `MyApplicationTheme` | Material 3 theme switcher supporting `SYSTEM`, `LIGHT`, `DARK`, and pure `#000000` `AMOLED` schemes plus `AppAccent` transformation (`SCARLET`, `OCEAN`, `EMERALD`, `VIOLET`, `AMBER`, `DYNAMIC` on API 31+). |
| `ui/theme/Type.kt` | `Typography` | Material 3 `Typography` with default `bodyLarge` configuration. |

### 2.2 Resources, Tests & Root Files
- **Resources (`app/src/main/res/`):**
  - `values/strings.xml`, `values/colors.xml`, `values/themes.xml`
  - `drawable/ic_launcher_background.xml`, `drawable/ic_launcher_foreground.xml`
  - `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` + `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.webp`
  - `xml/backup_rules.xml`, `xml/data_extraction_rules.xml`
- **Assets:** `assets/icon.svg` (root reference asset; duplicate `app/src/main/assets/icon.svg` removed)
- **Tests:**
  - `app/src/test/java/com/example/ExampleUnitTest.kt` (trivial `2 + 2 == 4`)
  - `app/src/test/java/com/example/ExampleRobolectricTest.kt` (verifies `R.string.app_name == "MK PDF Reader"` at `@Config(sdk = [36])`)
  - `app/src/test/java/com/example/GreetingScreenshotTest.kt` (Roborazzi native graphics screenshot test of `JumpToPageDialog(currentPage=5, totalPages=42)` output to `src/test/screenshots/greeting.png`)
  - `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt` (checks `BuildConfig.APPLICATION_ID`)

---

## 3. Overall Application Architecture & Internal Workflows

### 3.1 Architectural Pattern
- **Pattern:** Single-Activity Jetpack Compose MVVM with Unidirectional Data Flow (UDF).
- **DI Strategy:** Manual construction inside `AndroidViewModel` subclasses (`HomeViewModel`, `PdfReaderViewModel`, and `LibraryViewModel` instantiate `AppDatabase.getDatabase(application)`, `PdfRepository(...)`, `LibraryRepository.getInstance(application)`, and `SettingsRepository.getInstance(application)`). All three ViewModels are scoped to `MainActivity` via `by viewModels()` and passed as parameters to composables.
- **State Holders:**
  - `SettingsRepository.settings: StateFlow<AppSettings>` (shared singleton across ViewModels and `MainActivity`).
  - `HomeViewModel.recentPdfs: StateFlow<List<PdfRecentItem>>`, `visibleRecents: StateFlow<List<PdfRecentItem>>` (`combine(recentPdfs, appSettings)`), and `isGeneratingSample: StateFlow<Boolean>`.
  - `LibraryViewModel.roots`, `scanState`, `tree`, `appSettings`, and `listing: StateFlow<BrowserListing>` (`combine(tree, settings, location)`).
  - `PdfReaderViewModel.uiState: StateFlow<PdfReaderUiState>` and `engine: PdfRendererEngine?`.
- **Navigation:** State-driven conditional rendering inside `MainAppContent` (`MainActivity.kt`) rather than `Navigation Compose`:
  - `var currentPdfUriString by rememberSaveable { mutableStateOf<String?>(null) }`
  - `var libraryLocation by rememberSaveable { mutableStateOf<String?>(null) }` (encoded as `"rootId|relDir"`)
  - Render priority: `PdfReaderScreen` if `currentPdfUriString != null`, else `LibraryBrowserScreen` if `libraryLocation != null`, else `HomeScreen`.
  - Closing the reader clears `currentPdfUriString` without modifying `libraryLocation`, returning the user to the same library folder if opened from the browser.
  - `BackHandler(enabled = libraryLocation != null && currentPdfUriString == null)` navigates up to parent `relDir` or sets `libraryLocation = null` at root level; `BackHandler(enabled = currentPdfUriString != null)` calls `navigateBackToRecent()` (records close, clears `currentPdfUriString`, clears `FLAG_KEEP_SCREEN_ON`). Inside `PdfReaderScreen`, a nested `BackHandler` first dismisses fullscreen → `PageOverviewSheet` → `BookmarksSheet` → `IndexSheet` → `SettingsSheet` → `JumpToPageDialog` → `DocumentInfoDialog` before calling `onNavigateBack()`.
  - `LibrarySettingsSheet` is hosted once inside `MainAppContent` (`showLibrarySettings`) and reachable from `HomeScreen`, `HomeSettingsSheet`, and `LibraryBrowserScreen`.

### 3.2 Startup & Lifecycle Workflows
1. **`MainActivity.onCreate` & Document Open Routing:**
   - Calls `enableEdgeToEdge()`.
   - Launches background cleanup on `lifecycleScope` (`Dispatchers.IO`):
     - `PdfOutlineParser.purgeEmptyOutlineCaches(applicationContext)`
     - `PdfDocumentStorage.purgeExcessStorageAndCaches(applicationContext)`
   - Extracts `pendingIntentUri` via `extractPdfUriFromIntent(intent)` (`ACTION_VIEW` reads `intent.data`; `ACTION_SEND` reads `Intent.EXTRA_STREAM` with `Build.VERSION_CODES.TIRAMISU` typed `getParcelableExtra` branch) and calls `PdfDocumentStorage.takePersistablePermissionSafely(this, uri)`. `onNewIntent` updates `pendingIntentUri` so warm `VIEW`/`SEND` intents navigate into the reader cleanly.
   - `OpenPdfDocumentContract` creates `ACTION_OPEN_DOCUMENT` (`CATEGORY_OPENABLE`, `application/pdf`, `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_PERSISTABLE_URI_PERMISSION`) with `ACTION_GET_CONTENT` fallback; the result callback immediately calls `takePersistablePermissionSafely`.
   - Every open (picker result, `VIEW`/`SEND` intent, and Home tap) routes through `homeViewModel.resolveIdentityUri(uri)` before setting `currentPdfUriString` and calling `readerViewModel.loadPdf(resolved)`, preventing duplicate recents or split bookmark sets for the same file path.
   - Collects `homeViewModel.appSettings` to wrap `MainAppContent` in `MyApplicationTheme(appThemeMode = appSettings.appThemeMode, appAccent = appSettings.appAccent)`.
2. **Opening a PDF (`loadPdf(uri, isSample)` in `PdfReaderViewModel`):**
   - Sets `PdfOutlineParser.activeDocumentUri = uri.toString()`, cancels any prior `outlineJob`, and sets `isLoading = true`, `isLoadingOutline = true`.
   - Performs synchronous L1/L2 cache check (`PdfOutlineParser.loadCachedOutline`) and immediately launches `outlineJob` at the very start of `loadPdf` in parallel with `PdfRendererEngine.initialize()` (using `CompletableDeferred` for `pageCount` and `initialPage`). When the outline resolves, builds `OutlineIndex` on `Dispatchers.Default` and publishes `outlineItems`, `outlineIndex`, `currentChapterTitle`, `outlineDiagnostic`, `isLoadingOutline = false`, and `hasOutlineLoaded = true` in a single state update.
   - Closes previous `PdfRendererEngine`, instantiates `PdfRendererEngine(application, uri)`, and runs `initialize()` on `Dispatchers.IO`. If opening fails (`!initialized || pageCount == 0`), calls `repository.tryRelink(uri)` once and retries `PdfRendererEngine.initialize()` once before reporting an error.
   - On failure (`!initialized || pageCount == 0`): cancels `outlineJob`, checks `Environment.isExternalStorageManager()` (Android 11+/R+), and sets actionable `errorMessage`.
   - On success:
     - Completes `pageCountDeferred`, resolves `PdfDocumentInfo` via `repository.resolveDocumentInfo`, looks up `repository.getRecentByUri` to restore `lastReadPage` (`initialPageDeferred`) and `ReadingMode` (`VERTICAL` fallback), and emits loaded `PdfReaderUiState`.
     - Starts `bookmarksJob` collecting `repository.getBookmarksForUri(uri.toString())`.
     - Starts/resets `keepAwakeJob` (`resetKeepAwakeTimer`), calls `triggerPrefetch(initialPage)`, renders cover thumbnail `newEngine.renderThumbnail(0)`, calls `repository.recordDocumentOpened(...)`, and starts the 10s `ReadingStats` ticker.
   - Closing (`recordClose()`): cancels `outlineJob` and clears `PdfOutlineParser.activeDocumentUri`.
3. **Page Navigation (`onPageChanged` / `jumpToPage`):**
   - Updates `currentPage`, `currentChapterTitle` via `OutlineHelper.getCurrentChapter(page, outlineIndex)` (O(1) lookup, never re-flattening the tree), `isCurrentPageBookmarked`, and `cacheUsageBytes`.
   - `jumpToPage` additionally resets `zoomScale = 1.0f`, `panOffsetX = 0f`, `panOffsetY = 0f`.
   - Calls `saveReadingProgress(pageIndex)` (async `repository.updateReadingProgress`), `recordUserActivity()` (resets keep-awake countdown), and `triggerPrefetch(pageIndex)`.
4. **Home Screen Resume, Refresh, Startup Relink & Outline Warm-Up (`HomeScreen` & `HomeViewModel`):**
   - `HomeViewModel.init` calls `repository.syncRecentsOnStartup()`, runs `relinkBrokenRecents()` sequentially on `Dispatchers.IO` (calling `repository.tryRelink` for each recent without ever deleting failed entries), and launches one-shot per-process `warmUpOutlines()` (guarded by a companion `Boolean` flag): waits 3 seconds, loops sequentially on `Dispatchers.IO.limitedParallelism(1)` over recents missing a `toc2_` file (skipping `activeDocumentUri`), calls `PdfOutlineParser.parseOutline`, and ignores failures silently without touching UI state.
   - `DisposableEffect(lifecycleOwner)` listens for `Lifecycle.Event.ON_RESUME`, calls `viewModel.refreshRecents()` (`PdfRepository.reloadRecentsFromDirectory()`), and updates `hasFullStorageAccess` (`Environment.isExternalStorageManager()`).

---

## 4. PDF Page Rendering Engine (`PdfRendererEngine` & `PdfPageView`)

### 4.1 Core Engine & Concurrency Model
- **Implementation File:** `app/src/main/java/com/example/data/pdf/PdfRendererEngine.kt`
- **Underlying Native Engine:** Android Framework `android.graphics.pdf.PdfRenderer` backed by an open read-only `ParcelFileDescriptor` obtained from `PdfDocumentStorage.openFileDescriptor(context, uri)`.
- **Thread-Safety:**
  - Android's `PdfRenderer` permits only one open `PdfRenderer.Page` and single-threaded rendering at a time.
  - `initialize()` and `renderPage()` acquire a coroutine `Mutex` (`renderMutex.withLock`).
  - `getPageAspectRatio(pageIndex)` opens the page briefly inside `synchronized(this)` to compute `page.height.toFloat() / page.width.toFloat()` (defaulting to `1.414f` A4 aspect ratio on invalid index/error).

### 4.2 Supersampled Rasterization & Viewport Scaling Math
Two stages cooperate to compute the final rasterized `Bitmap` dimensions:

1. **Composable Viewport Request & Draw-Time Margin Trimming (`app/src/main/java/com/example/ui/components/PdfPageView.kt`):**
   - Reads `LocalDensity.current` and `aspectRatio = engine.getPageAspectRatio(pageIndex)`.
   - `multiplier` by `RenderSharpness`:
     - `ULTRA_HD` → `1.5f`
     - `HIGH_CLARITY` → `1.25f`
     - `BALANCED` → `1.0f`
   - `baseWidthPx = ((density.density * 480) * multiplier).toInt().coerceIn(1200, 2400)`
   - `baseHeightPx = (baseWidthPx * aspectRatio).toInt()`
   - Computes `displayedAspectRatio = computeTrimmedAspectRatio(aspectRatio, marginTrim) = pageRatio * (1 - 2v) / (1 - 2h)` where `v = h / 2` (`MarginTrim`: `OFF = 0f`, `LIGHT = 0.04f`, `MEDIUM = 0.08f`, `STRONG = 0.12f`) and draws via `Canvas.drawImage(imageBitmap, srcOffset, srcSize, dstSize, FilterQuality.High)` so the engine and cache keys remain unchanged.

2. **Engine Supersampling & Clamping (`PdfRendererEngine.renderPage`):**
   - `baseScale = min(targetWidth / originalWidth, targetHeight / originalHeight)` (or `targetWidth / originalWidth` if `targetHeight <= 0`, or `2.0f`).
   - `maxScale` and `maxDimension` limits per `RenderSharpness`:
     | `RenderSharpness` | `scaleMultiplier` | `maxScale` | `maxDimension` (px) |
     |---|---|---|---|
     | `BALANCED` | `2.2f` | `3.2f` | `2600` |
     | `HIGH_CLARITY` *(Default)* | `3.2f` | `4.5f` | `3600` |
     | `ULTRA_HD` | `4.8f` | `6.0f` | `4600` |
   - `effectiveScale = (baseScale * sharpness.scaleMultiplier).coerceIn(1.8f, maxScale)`
   - `finalWidth = (originalWidth * effectiveScale).roundToInt().coerceAtLeast(300)`
   - `finalHeight = (originalHeight * effectiveScale).roundToInt().coerceAtLeast(300)`
   - If either dimension exceeds `maxDimension`, both are proportionally downscaled by `maxDimension.toFloat() / max(finalWidth, finalHeight)`.
   - Allocates `Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)` filled with `Color.WHITE`.
   - Applies a `Matrix` scaling `(finalWidth / originalWidth, finalHeight / originalHeight)`.
   - Calls `page.render(bitmap, null, matrix, renderMode)` where:
     - `highQualityPrintMode == true` *(Default)* → `PdfRenderer.Page.RENDER_MODE_FOR_PRINT`
     - `highQualityPrintMode == false` → `PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY`

### 4.3 Color Tone Post-Processing (`ColorMatrix`)
When `colorTone != PageColorTone.ORIGINAL`, `PdfRendererEngine` allocates a second `ARGB_8888` bitmap and draws the rendered page through a `Paint(ANTI_ALIAS_FLAG or FILTER_BITMAP_FLAG)` with a 4×5 `ColorMatrixColorFilter`:
- **`PageColorTone.ORIGINAL`:** Zero post-processing (returns raw rendered bitmap). Page surface: `#FFFFFF`; Reader canvas: `#E9ECEF`.
- **`PageColorTone.SEPIA`:**
  ```text
  [ 0.37335, 0.73055, 0.17955, 0, 25 ]
  [ 0.32108, 0.63112, 0.15456, 0, 15 ]
  [ 0.23120, 0.45390, 0.11135, 0,  0 ]
  [ 0,       0,       0,       1,  0 ]
  ```
  Page surface: `#FBF0D9`; Reader canvas: `#EDE3D2`.
- **`PageColorTone.INVERTED_DARK`:**
  ```text
  [ -0.88,  0,     0,     0, 235 ]
  [  0,    -0.88,  0,     0, 235 ]
  [  0,     0,    -0.88,  0, 235 ]
  [  0,     0,     0,     1,   0 ]
  ```
  Page surface: `#141414`; Reader canvas: `#0D0D0D`.
- **`PageColorTone.HIGH_CONTRAST`:** Contrast `1.35f`, translate `(-0.5f * 1.35f + 0.5f) * 255f = -44.625f`:
  ```text
  [ 1.35, 0,    0,    0, -44.625 ]
  [ 0,    1.35, 0,    0, -44.625 ]
  [ 0,    0,    1.35, 0, -44.625 ]
  [ 0,    0,    0,    1,   0     ]
  ```
  Page surface: `#FFFFFF`; Reader canvas: `#DCDCDC`.

### 4.4 Memory Cache, OOM Protection, Prefetching & Thumbnails
- **LRU Cache Sizing:** `maxMemoryCacheBytes = (Runtime.getRuntime().maxMemory().toInt() / 5).coerceIn(48 * 1024 * 1024, 256 * 1024 * 1024)` (48 MB minimum, 256 MB maximum, 20% of VM heap). `sizeOf` measures `bitmap.byteCount`.
- **Cache Key Format:** `"${pageIndex}_${targetWidth}_${targetHeight}_${sharpness.name}_${colorTone.name}"`. *(Note: `highQualityPrintMode` is not in the key, so toggling it calls `engine.clearCache()` in `PdfReaderViewModel.updateHighQualityPrintMode`).*
- **Synchronous Cache Lookup (`getCachedPage`):** Called during `PdfPageView` composition (`remember`) to prevent loading spinner flicker on fast scrolls.
- **OOM Recovery:** Catching `OutOfMemoryError` triggers `memoryCache.evictAll()`, `System.gc()`, and returns `null`.
- **Prefetching (`prefetchAdjacentPages`):** Cancels any active `prefetchJob`, launches on `Dispatchers.IO`, queues pages `[+1, -1, +2, -2, ...]` up to `PrefetchMode.pageRadius` (`0`, `1`, or `2`). Note: `PdfReaderViewModel.triggerPrefetch` passes `targetWidth = 1200, targetHeight = 1600`.
- **Thumbnail Rendering (`renderThumbnail` & `renderGridThumbnail`):**
  - Cover thumbnail (`renderThumbnail`): calls `renderPage(pageIndex = 0, targetWidth = 260, targetHeight = 360, sharpness = BALANCED, colorTone = ORIGINAL, highQualityPrintMode = false)`. `PdfRepository.recordDocumentOpened` compresses it to PNG (`quality = 85`) at `context.filesDir/thumbs/thumb_<docKey>.png`.
  - Grid overview thumbnail (`renderGridThumbnail(pageIndex)`): backed by a separate 24 MB `gridThumbCache: LruCache<Int, Bitmap>` (evicted in `clearCache()` and `closeInternal()`), rasterizes a 200 px wide bitmap directly via `PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY` inside `renderMutex.withLock` without color-tone filters.

---

## 5. Table of Contents / Index Loading & Processing Engines

### 5.1 TOC Extraction Pipeline (`PdfOutlineParser.kt` & `RawPdfOutlineReader.kt`)
`PdfOutlineParser` is a singleton object responsible for extracting hierarchical `PdfOutlineItem(id, title, pageIndex, level, children)` trees where `pageIndex` is 0-indexed. All engine calls are wrapped in `try/catch(Throwable)` (catching `UnsatisfiedLinkError`/`NoSuchMethodError`), validated by `isPlausible(list)` (`false` if empty, or if the flattened tree has `> 5` items and `> 80%` have `pageIndex == 0`), and every engine records one short diagnostic line on failure into `diagnostics: ConcurrentHashMap<String, String>` (readable via `lastDiagnostic(uri)`). Only plausible results are returned or cached. `parseOutline(context, uri, totalPages)` executes on `Dispatchers.IO` (never throws, returns `emptyList()` on failure) in strict cascade order:

1. **L1 In-Memory Cache:** `ConcurrentHashMap<String, List<PdfOutlineItem>>` keyed by `uri.toString()` (`loadCachedOutline`). Sub-millisecond hit across rotations/re-opens in the same process.
2. **L2 Persistent Disk Cache:** `context.filesDir/toc_cache/toc2_<SHA256-32-hex>.json` (`getCacheFile`). Uses compact JSON keys:
   - `"i"` = `id` (`String`)
   - `"t"` = `title` (`String`)
   - `"p"` = `pageIndex` (`Int`)
   - `"l"` = `level` (`Int`)
   - `"c"` = `children` (`JSONArray`, omitted if empty)
   - Written atomically via a temporary `.tmp` file renamed over the target file (never caches empty or implausible outlines); reading treats any `JSONException` (or read failure) as a cache miss and deletes the corrupt file.
   - On startup (`MainActivity`), `purgeEmptyOutlineCaches` deletes every file in `toc_cache` not starting with `toc2_`, `.tmp` files, and `toc2_*` files `< 10` bytes or containing `"[]"`.
3. **Sample-Guide Constant:** Immediately returns `SAMPLE_GUIDE_OUTLINE` (8 level-0 items, ids `"sample_0"`..`"sample_7"`) if `uri.toString()` contains `"MK_PDF_Reader_Guide"`.
4. **Pure-Kotlin `RawPdfOutlineReader` (Primary Engine, no timeout):** `RawPdfOutlineReader.read(pfd, totalPages)` reads directly via `FileInputStream(pfd.fileDescriptor).channel` without closing `pfd` or loading the full file into memory. Returns `null` on failure, empty outline, or `/Encrypt` trailer:
   - *Tokenizer:* Produces dictionaries, arrays, `Ref(num)`, `Name` (with `#xx` decoding, stored without `/`), `PdfString(bytes)` (nested parentheses, escapes `\n \r \t \b \f \( \) \\`, octal `\ddd`, line continuation, hex strings), `Long`/`Double` numbers, booleans, `null`, comments, and `"N G R"` references.
   - *Xref Reader:* Scans last 2048 bytes for the last `"startxref"`, iteratively follows `/XRefStm` then `/Prev` with a visited-offset guard (newest entry wins; `/Root` from newest trailer). Supports classic `xref` + `trailer` and `/Type /XRef` streams (`/W`, `/Index`, `FlateDecode` `Inflater`, PNG predictor undo for `/Predictor >= 10` using `/Columns`).
   - *Object Loader (`getObject`):* Type 1 reads a 16 KB window (doubling up to 8 MB on truncated tokens), parses `"N G obj"`, resolves `/Length` (or searches `endstream`), inflates `FlateDecode`. Type 2 decompresses `/ObjStm` once (8-entry LRU cache), parses `/N` header pairs, and reads objects at `/First + offset`.
   - *Outline Walk & Destinations:* Iterative DFS over `Root -> /Outlines -> /First` (`children` via `/First`, siblings via `/Next`, visited set, max depth 30, cap 100,000 items, IDs matching `native_toc_l<lvl>_i<idx>_p<page>`). Decodes titles (`FE FF` UTF-16BE, `FF FE` UTF-16LE, `EF BB BF` UTF-8, else ISO-8859-1) + `cleanTitle`. Resolves `/Dest` or `/A` (`/S /GoTo`, `/D`), array first-element `Ref`, or named destinations (`Root /Dests` dict, else `Root /Names /Dests` name tree via byte-wise `/Limits` and `/Names` pairs).
   - *Lazy Page Map:* Built once on first page ref resolution via iterative `/Pages` `/Kids` walk up to `totalPages`; unresolved destinations reuse the previous item's (or parent's) `pageIndex`, clamped to `0 until totalPages` (or unclamped when `totalPages <= 0` during parallel pre-init parsing).
5. **Native C/C++ Pdfium Engine Fallback (`parseWithNativePdfium`, capped by `withTimeoutOrNull(10_000)`):**
   - *Timeout:* Wrapped in `withTimeoutOrNull(10_000L)`; on timeout records `"Pdfium: timeout"` and returns `null`.
   - *Library:* `com.github.barteksc:pdfium-android:1.9.0` (`PdfiumCore`, `PdfDocument`).
   - *Opening:* Opens `ParcelFileDescriptor` via `PdfDocumentStorage.openFileDescriptor`; retries with empty password `pdfiumCore.newDocument(pfd, "")` if standard open throws; closes `PdfDocument` and `ParcelFileDescriptor` in `finally`.
   - *Sub-path 1 — Iterative JNI Reflection (`extractBookmarksIterativeNative`):*
     - Reflects `PdfDocument.mNativeDocPtr` and `PdfiumCore` private JNI methods: `nativeGetFirstChildBookmark`, `nativeGetSiblingBookmark`, `nativeGetBookmarkTitle`, `nativeGetBookmarkDestIndex`.
     - Iterates siblings with a `while` loop (preventing `StackOverflowError` on 4,000+ chapter textbooks), recursing down children up to `level <= 25`, with a `HashSet<Long>` cycle detector per level.
   - *Sub-path 2 — Standard Recursive Fallback:* Calls `pdfiumCore.getTableOfContents(pdfDocument)` and converts `PdfDocument.Bookmark` trees with `cleanTitle` and page-number regex fallback `(?:page|p\.?|pg\.?)\s*(\d+)|\b(\d+)\s*$`.

### 5.2 Active Chapter Tracking & `OutlineIndex` (`OutlineHelper.kt`)
- `OutlineIndex(flat, parentIndex, lowerTitles, chapterForPage)` is built once per outline on `Dispatchers.Default` via `OutlineHelper.buildIndex(outline, totalPages)`. `chapterForPage` uses `maxIdxAtPage[pageIndex] = max(existing, i)` followed by a running maximum across pages.
- Overloads taking `OutlineIndex`: `getCurrentChapter` (O(1) via `chapterForPage`), `getRootChapter` (short walk up `parentIndex`), and `getNextChapter` (linear scan over `flat` without re-flattening).
- Existing `List<PdfOutlineItem>` signatures (`flatten`, `getCurrentChapter`, `getRootChapter`, `getNextChapter`) are preserved and implemented iteratively.
- Displayed live in `ReaderTopBar` subtitle (`"$currentChapter • $currentPage / $totalPages"`).

---

## 6. Document Access, URI Resolution & Persistence Layers

### 6.1 Zero-Copy Document Access (`PdfDocumentStorage.kt` & `FilePathResolver.kt`)
MK PDF Reader **never copies opened PDFs into app storage** (preventing multi-GB bloat). Instead, `PdfDocumentStorage.openFileDescriptor(context, uri)` resolves a read-only `ParcelFileDescriptor` through 6 ordered strategies:
1. **Direct `file://`:** Opens `File(uri.path)` directly if `exists() && canRead()`.
2. **Direct `content://`:** Calls `takePersistablePermissionSafely` (`FLAG_GRANT_READ_URI_PERMISSION`, fallback `READ or WRITE`) and `context.contentResolver.openFileDescriptor(uri, "r")`.
3. **Decoded `content://`:** Runs `Uri.decode(uri.toString())` to fix URL-encoded path issues from third-party file managers.
4. **Saved `mediaUri` & Direct Entry Path Lookup:** Queries `PdfDirectoryStorage.findDirectoryEntry(context, uri)?.mediaUri`; if unavailable or unopenable, opens `dirEntry.path` directly via `ParcelFileDescriptor.open` when `File.exists() && File.canRead()`.
5. **Path & MediaStore Recovery (`FilePathResolver.resolvePath` & `resolveTreeUriToPath`):**
   - `FilePathResolver` resolves `file://`, `DocumentsContract` (`com.android.externalstorage.documents` primary/raw/SD, `com.android.providers.downloads.documents` raw/numeric ID, `com.android.providers.media.documents`), MediaStore `_data` column, and third-party patterns (MiXplorer `/ext_storage/` → `/storage/emulated/0/`, `/root/`, embedded `/storage/emulated/`, and `DIRECTORY_DOWNLOADS`/`DIRECTORY_DOCUMENTS` filename matches). `resolveTreeUriToPath(treeUri)` maps `com.android.externalstorage.documents` tree docIds (`primary:A/B` → `/storage/emulated/0/A/B`, `XXXX-XXXX:A` → `/storage/XXXX-XXXX/A`; `null` for other authorities).
   - Uses resolved path for: (5a) direct `ParcelFileDescriptor.open`, (5b) `findMediaStoreUriForPath` querying `MediaStore.Files` and `MediaStore.Downloads` by `DATA`, `DISPLAY_NAME + SIZE`, or `DISPLAY_NAME` (and caches the discovered `mediaUri` back to `PdfDirectoryStorage`), and (5c) public `Downloads` / `Documents` fallback.
6. **Internal Sample Guide:** Checks `context.filesDir/MK_PDF_Reader_Guide.pdf` if URI contains `"MK_PDF_Reader_Guide"`.

**Deterministic Document Key (`getDocumentKey(uri)`):** Decodes URI, trims, lowercases (`Locale.ROOT`), computes SHA-256 and takes the first 32 hex characters (fallback `"doc_${abs(hashCode())}"`). Used for `dir_<key>.json`, `toc2_<key>.json`, `thumb_<key>.png`, and `lib_thumbs/<key>.jpg`.

**Startup Purge (`purgeExcessStorageAndCaches`):** Deletes legacy `filesDir/books/`, legacy `filesDir/saved_pdfs/`, and `cacheDir` files prefixed with `toc_`, `temp_view_`, or `tmp_`. Never touches `filesDir/pdf_directories/`, `filesDir/toc_cache/`, `filesDir/thumbs/`, `filesDir/lib_thumbs/`, `filesDir/library/`, or `filesDir/recent_books.json`.

### 6.2 Tri-Layer Recent PDFs Persistence (`PdfRepository` + `PdfDirectoryStorage` + `RecentPdfJsonStorage` + Room)
| Layer | Location | Format & Role |
|---|---|---|
| **Layer 1 (Authoritative)** | `filesDir/pdf_directories/` (`dir_<docKey>.json` + `directory_index.json`) | `PdfDirectoryStorage`. Tiny ~1 KB `AtomicFile` JSON descriptors (`docKey`, `name`, `uri`, `path`, `directoryPath`, `time`, `size`, `pageCount`, `lastReadPage`, `scroll`, `mode`, `thumb`, `mediaUri`). Never auto-deleted on transient permission errors. |
| **Layer 2 (JSON Mirror)** | `filesDir/recent_books.json` | `RecentPdfJsonStorage`. Synchronous `AtomicFile` JSON array (`name`, `path`, `time`, `uri`, `page`, `pageCount`, `scroll`, `size`, `mode`, `thumb`). |
| **Layer 3 (SQLite ORM)** | Room DB `mk_pdf_reader.db` (`recent_pdfs` table) | `PdfRecentItem` / `PdfRecentDao`. Primary key `pdf_path`, indexed by `uriString` and `localFilePath`. |

- **Startup Sync (`PdfRepository.init` → `syncRecentsOnStartup`):**
  - If `pdf_directories/` is non-empty, upserts all entries into Room `recent_pdfs`.
  - Else if `recent_books.json` is non-empty, migrates entries into both `pdf_directories/` and Room.
  - Else seeds `pdf_directories/` from existing Room `recent_pdfs` rows.
  - Emits sorted list (descending `time`) via `MutableStateFlow<List<PdfRecentItem>>`.
- **Write Path (`PdfRepository.addRecent` / `recordDocumentOpened`):** Always resolves and persists the absolute filesystem `path` (preserving any prior resolved `path` and `mediaUri` when transient resolution fails) across `PdfDirectoryStorage`, `RecentPdfJsonStorage`, and `PdfRecentDao`, then calls `reloadRecentsFromDirectory()` to update `recentPdfs` Flow immediately.
- **Identity Deduplication & Self-Healing Relink (`resolveIdentityUri` & `tryRelink`):**
  - `resolveIdentityUri(uri)` maps an incoming URI whose resolved absolute path matches an existing recent back to its stored `uriString` (attaching any new `content://` grant as `mediaUri` if absent), avoiding duplicate recents or split bookmarks/favorites.
  - `tryRelink(uri)` checks if a recent's file can be opened; if not, queries `LibraryRepository.findByNameAndSize(fileName, fileSize)` and updates ONLY the entry's `path` (and `mediaUri` if known) while keeping `uriString` and `docKey` intact. Never deletes a recent on open failure.
- **Delete Path (`deleteRecent` / `clearAllRecents`):** Removes from Room, `PdfDirectoryStorage`, `RecentPdfJsonStorage`, deletes legacy/temp files via `PdfDocumentStorage.deletePersistentFile`, and deletes TOC cache via `PdfOutlineParser.deleteOutlineCache` (on single delete).

### 6.3 Bookmarks Database Schema (`AppDatabase` v4)
- **Table `pdf_bookmarks` (`PdfBookmarkEntity`):**
  - `id: Long` (PK, `autoGenerate = true`)
  - `uriString: String`
  - `pageIndex: Int` (0-indexed)
  - `pageNumber: Int` (`pageIndex + 1`)
  - `title: String?` (defaults to `"Page ${pageIndex + 1}"` on insert)
  - `createdAt: Long` (`System.currentTimeMillis()`)
  - **Unique Index:** `Index(value = ["uriString", "pageIndex"], unique = true)` — guarantees at most one bookmark per page per document (`OnConflictStrategy.REPLACE`).
- **Operations (`PdfBookmarkDao` & `PdfRepository`):**
  - `getBookmarksForUri(uriString)` ordered by `pageIndex ASC`
  - `getBookmarkedPageIndices(uriString)`
  - `toggleBookmark(uriString, pageIndex, title)` → inserts (using `currentChapterTitle` if non-blank, otherwise `"Page ${pageIndex + 1}"`) or deletes, returning `Boolean` (`true` = added, `false` = removed)
  - `updateBookmarkTitle(id, title)` (`PdfBookmarkDao.updateTitle`) → updates `title` without schema changes
  - `deleteBookmark(bookmark)` / `deleteBookmarkByPage(uriString, pageIndex)`

### 6.4 Built-in Sample PDF Generator (`SamplePdfGenerator.kt`)
- Creates `context.filesDir/MK_PDF_Reader_Guide.pdf` (8 pages, A4 `595×842` pt) if missing or `<= 1024` bytes.
- Draws vector text, header banner, highlight boxes, zoom calibration grid, and dual-mode diagram via `android.graphics.pdf.PdfDocument`, writes directly to `MK_PDF_Reader_Guide.pdf` via `FileOutputStream` + `document.writeTo(out)` closing both in `finally`. Its 8-item TOC is provided directly by `PdfOutlineParser.SAMPLE_GUIDE_OUTLINE`.

### 6.5 Folder-Based PDF Library Foundation (`com.example.data.library`)
- **Models (`LibraryModels.kt`):**
  - `LibraryRoot(id, name, absPath, treeUri, addedAt, lastScanAt, pdfCount, status)` with `LibraryRootStatus { OK, NEEDS_ACCESS }`; `id` is the first 12 hex characters of SHA-256 of `absPath ?: treeUri`.
  - `LibraryPdf(name, relDir, path, uri, size, modified)` where `relDir` is `/`-separated relative to root (`""` at root level).
  - `ScanState` (`Idle` / `Scanning(rootName, found)`), `FolderEntry(name, relDir, pdfCountRecursive)`, `DirNode(folders, files)`, and `LibraryTree(nodes: Map<"rootId|relDir", DirNode>)`.
- **Persistence (`LibraryStorage.kt`):**
  - Stores `filesDir/library/roots.json` and per-root `filesDir/library/index_<rootId>.json` using `AtomicFile` + `org.json` with compact keys (`n, d, p, u, s, m`) and no bitmaps/page data. Reads are defensive (corrupt files return empty lists).
- **Scanner (`LibraryScanner.kt`):**
  - Runs on `Dispatchers.IO` using an explicit `ArrayDeque` stack (never recursive), calling `ensureActive()` per directory, max depth 25, skipping `.`-prefixed directories and top-level `Android`. Matches `.pdf` (case-insensitive `Locale.ROOT`) or `application/pdf` MIME, throttling progress every 25 files without sorting.
  - Uses FILE mode (`File.listFiles()`, `Uri.fromFile`) when `absPath` is readable, else SAF mode (`contentResolver.persistedUriPermissions`, `DocumentsContract.buildChildDocumentsUriUsingTree` / `buildDocumentUriUsingTree`, cursors wrapped in `use {}`, deriving `path` from docId), else returns `ScanResult.NeedsAccess` preserving any previous index.
- **Repository (`LibraryRepository.kt`):**
  - Singleton (`getInstance(context)` holding `applicationContext`) exposing `roots`, `scanState`, and `tree` `StateFlow`s.
  - `addRoot(treeUri)` takes persistable `READ or WRITE` permission (fallback `READ`), resolves `absPath`, deduplicates (rejecting folders inside existing roots and replacing existing roots contained inside a new parent root), saves, and scans.
  - `removeRoot(id)` deletes only the root's index (never PDFs); `removeEntries(entries)` updates in-memory/on-disk root indexes and rebuilds the tree after permanent file deletions; `mergeImportedRoots(importedRoots)` adds imported roots not already present by `id`; `rescan(id?)` and `rescanAllIfStale(maxAgeMs)` serialize scans with a `Mutex`, atomically replacing the index on completion and keeping the previous index on cancellation/failure.
  - Builds `LibraryTree` (nodes strictly for ancestors of discovered PDFs), a `HashMap` backing `findByNameAndSize(name, size)`, and a prebuilt lowercase-name `searchEntries` list backing `searchByName(query, limit = 20)` once per index change on `Dispatchers.Default`.
- **Lean Thumbnails (`LibraryThumbnailStore.kt`):**
  - Never instantiates `PdfRendererEngine`. Checks recents `filesDir/thumbs/thumb_<docKey>.png` first, then `filesDir/lib_thumbs/<docKey>.jpg`.
  - Renders lazily on `Dispatchers.IO` inside a single-permit `Semaphore(1)` worker with `withTimeoutOrNull(8_000)` using raw `android.graphics.pdf.PdfRenderer` on `PdfDocumentStorage.openFileDescriptor` (200 px wide, aspect-ratio height capped at 300 px, white background, `RENDER_MODE_FOR_DISPLAY`, JPEG quality 80), closing page/renderer/fd and recycling bitmaps in `finally`, recording failures in an in-memory set.
  - Backed by an 8 MB `LruCache<String, ImageBitmap>` and a 40 MB disk cap on `lib_thumbs/` that evicts oldest files by `lastModified`; `clearLibThumbCache` deletes only `lib_thumbs/` (preserving recents `thumbs/`).

### 6.6 Portable ZIP Backup & Android Auto-Backup (`BackupManager.kt`, `backup_rules.xml`, `data_extraction_rules.xml`)
- **Export (`BackupManager.exportBackup`):** Streams a portable ZIP (`MKPDFReader_backup_<yyyyMMdd>.zip`) to a user-selected `CreateDocument("application/zip")` URI containing `manifest.json` (`format = 1`, `appVersion`, `createdAt`, `counts`), type-tagged `settings.json` (`mk_pdf_settings`), type-tagged `stats.json` (`mk_pdf_stats`), `library_roots.json`, `recents/` (`dir_<docKey>.json` + `directory_index.json`), `bookmarks.json` (queried via `PdfBookmarkDao.getAll()`), and optionally recents `thumbs/thumb_<docKey>.png` (never `lib_thumbs` or `toc_cache`).
- **Import (`BackupManager.importBackup`):** Parses and validates the entire `OpenDocument` archive in memory before mutating any state, enforcing strict entry-name whitelists (`manifest.json`, `settings.json`, `stats.json`, `library_roots.json`, `bookmarks.json`, `recents/directory_index.json`, `recents/dir_<32hex>.json`, optional `thumbs/thumb_<32hex>.png`) and byte limits to block zip-slip/corrupt archives. Merges without wiping existing data (newer `time` wins per recent, `REPLACE` bookmarks on unique `(uriString, pageIndex)`, settings preserve keys absent from backup and union `favorite_keys`, stats keep `maxOf` per key, roots union by `id`), then calls `reloadRecentsFromDirectory()`, `SettingsRepository.reloadFromPrefs()`, and triggers a library rescan.
- **Storage Readout:** `computeStorageUsedBytes(context)` sums `thumbs`, `lib_thumbs`, `toc_cache`, `pdf_directories`, and `library` on `Dispatchers.IO`.
- **Android Auto-Backup / Device Transfer Rules (`res/xml/backup_rules.xml` & `res/xml/data_extraction_rules.xml`):** Explicitly includes `files/pdf_directories`, `files/library/roots.json`, `files/thumbs`, `files/recent_books.json`, shared prefs `mk_pdf_settings.xml` & `mk_pdf_stats.xml`, and `mk_pdf_reader.db`, while excluding `files/toc_cache` and `files/lib_thumbs` (leaving `library/index_*.json` excluded by only including `library/roots.json`).

---

## 7. Complete Settings, Themes & Default Configurations

### 7.1 `SettingsRepository` (`app/src/main/java/com/example/data/settings/AppSettings.kt`)
- **Storage:** `context.getSharedPreferences("mk_pdf_settings", Context.MODE_PRIVATE)` (singleton instance, exposed via `StateFlow<AppSettings>`).
- **All Preferences, Keys, Options & Exact Default Values:**

| Setting Field (`AppSettings`) | SharedPreferences Key | Type / Enum | Default Value | Available Options & Values | Where Configured in UI |
|---|---|---|---|---|---|
| `sharpness` | `"render_sharpness"` | `RenderSharpness` | `HIGH_CLARITY` (`3.2f`) | `BALANCED` (`2.2f`), `HIGH_CLARITY` (`3.2f`), `ULTRA_HD` (`4.8f`) | `SettingsSheet` §1 |
| `highQualityPrintMode` | `"high_quality_print_mode"` | `Boolean` | `true` | `true` (`RENDER_MODE_FOR_PRINT`), `false` (`RENDER_MODE_FOR_DISPLAY`) | `SettingsSheet` §1 ("Subpixel Antialiasing") |
| `prefetchMode` | `"prefetch_mode"` | `PrefetchMode` | `AGGRESSIVE` (`2`) | `OFF` (`0`), `STANDARD` (`1`), `AGGRESSIVE` (`2`) | `SettingsSheet` §2 |
| `colorTone` | `"color_tone"` | `PageColorTone` | `ORIGINAL` | `ORIGINAL` ("White"), `SEPIA` ("Sepia"), `INVERTED_DARK` ("Night"), `HIGH_CONTRAST` ("Mono") | `SettingsSheet` §3 |
| `pageSpacing` | `"page_spacing"` | `PageSpacing` | `STANDARD` (`14.dp`) | `COMPACT` (`6.dp` / "Tight"), `STANDARD` (`14.dp` / "Normal"), `SPACIOUS` (`24.dp` / "Wide") | `SettingsSheet` §3 |
| `showPageBadges` | `"show_page_badges"` | `Boolean` | `true` | `true`, `false` | `SettingsSheet` §3 ("Page Number Badges") |
| `orientation` | `"reader_orientation"` | `ReaderOrientation` | `PORTRAIT` | `PORTRAIT`, `LANDSCAPE` | `SettingsSheet` §4 & `ReaderBottomBar` button |
| `keepAwakeOption` | `"keep_awake_option"` | `KeepAwakeOption` | `MINUTES_15` (`900_000L` ms) | `OFF` (`0L`), `MINUTES_5` (`300_000L`), `MINUTES_15` (`900_000L`), `MINUTES_30` (`1_800_000L`), `ALWAYS_ON` (`-1L`), `SYSTEM_DEFAULT` (`0L`) | `SettingsSheet` §4 (chips shown: `Off`, `5m`, `15m`, `Always`) |
| `doubleTapZoomMode` | `"double_tap_zoom_mode"` | `DoubleTapZoomMode` | `SMART_COLUMN` (`0f` → `2.08f` effective) | `SMART_COLUMN` (`0f`), `COMPACT_1_8X` (`1.85f`), `STANDARD_2_1X` (`2.08f`), `LARGE_2_3X` (`2.30f`), `MAX_2_6X` (`2.60f`) | `SettingsSheet` §5 |
| `doubleTapZoomLevel` | `"double_tap_zoom_level"` | `Float` | `2.08f` | Persisted float (`updateDoubleTapZoomLevel` exists in repo; `toggleDoubleTapZoom` uses `doubleTapZoomMode`) | Repository API only |
| `appThemeMode` | `"app_theme_mode"` | `AppThemeMode` | `SYSTEM` | `SYSTEM`, `LIGHT`, `DARK`, `AMOLED` | `HomeSettingsSheet` §2 |
| `recentViewMode` | `"recent_view_mode"` | `RecentViewMode` | `GRID` | `GRID`, `LIST` | `HomeScreen` TopAppBar & `HomeSettingsSheet` §1 |
| `marginTrim` | `"margin_trim"` | `MarginTrim` | `OFF` (`0f`) | `OFF` (`0f`), `LIGHT` (`0.04f`), `MEDIUM` (`0.08f`), `STRONG` (`0.12f`); vertical = `h / 2` | `SettingsSheet` §7 |
| `warmFilterPercent` | `"warm_filter_percent"` | `Int` | `0` | `0..60` (amber `#FF8A00` overlay alpha `percent / 100f * 0.45f`) | `SettingsSheet` §7 |
| `tapZoneNavigation` | `"tap_zone_navigation"` | `Boolean` | `false` | `true`, `false` | `SettingsSheet` §7 |
| `volumeKeyNavigation` | `"volume_key_navigation"` | `Boolean` | `false` | `true`, `false` | `SettingsSheet` §7 |
| `autoScrollSpeed` | `"auto_scroll_speed"` | `Int` | `3` | `1..10` (`speed * 6.dp.toPx()` / sec) | `SettingsSheet` §7 & `auto_scroll_pill` |
| `appAccent` | `"app_accent"` | `AppAccent` | `SCARLET` | `SCARLET`, `OCEAN` (`#1E88E5`/`#64B5F6`), `EMERALD` (`#2E7D32`/`#66BB6A`), `VIOLET` (`#7B1FA2`/`#CE93D8`), `AMBER` (`#EF6C00`/`#FFB74D`), `DYNAMIC` (API 31+) | `HomeSettingsSheet` §3 (`ACCENT COLOR`) |
| `librarySort` | `"library_sort"` | `LibrarySort` | `RECENT` | `RECENT`, `NAME`, `SIZE`, `PROGRESS` | `LibraryFilterSortRow` (`library_sort_btn`) |
| `libraryFilter` | `"library_filter"` | `LibraryFilter` | `ALL` | `ALL`, `IN_PROGRESS`, `FINISHED`, `FAVORITES` | `LibraryFilterSortRow` (`library_filter_<name>`) |
| `favoriteKeys` | `"favorite_keys"` | `Set<String>` | `emptySet()` | Document keys from `PdfDocumentStorage.getDocumentKey(uri)` (copied cleanly on read/write) | Grid/List cards (`favorite_grid_<hash>` / `favorite_<hash>`) |
| `browserSort` | `"browser_sort"` | `BrowserSort` | `NAME_ASC` | `NAME_ASC`, `NAME_DESC`, `DATE_NEW`, `DATE_OLD`, `SIZE_LARGE`, `SIZE_SMALL` | `LibraryBrowserScreen` sort menu |
| `browserFoldersFirst` | `"browser_folders_first"` | `Boolean` | `true` | `true`, `false` | `LibraryBrowserScreen` sort menu (`"Folders first"`) |
| `libraryAutoRescan` | `"library_auto_rescan"` | `Boolean` | `true` | `true`, `false` (triggers 2s-delayed `rescanAllIfStale(6h)` in `LibraryViewModel.init`) | `LibrarySettingsSheet` (`"Auto-rescan on app start"`) |

### 7.2 Reading Time Tracking (`data/settings/ReadingStats.kt`)
- **Storage:** `SharedPreferences("mk_pdf_stats")` storing per-document totals (`doc_<docKey>`) and per-day totals (`day_<epochDay>` via `LocalDate.now().toEpochDay()` on API 26+ or Calendar civil-day math on API 24–25).
- **Ticker:** Started in `PdfReaderViewModel.loadPdf` on `Dispatchers.IO`, cancelled in `recordClose()` / `onCleared()`. Every 10 seconds adds `10_000L` ms off the main thread only when `isReaderForeground` (`ON_RESUME`/`ON_PAUSE` via `setForeground`) is `true` and `lastUserActivityMs` was `< 3` minutes ago.
- **Streak:** `streakDays(context)` counts consecutive days ending today with `>= 60_000L` ms.

### 7.3 Transient Reader Defaults (`PdfReaderUiState` in `PdfReaderViewModel.kt`)
- `isLoading = true`, `currentPage = 0`, `totalPages = 0`, `readingMode = ReadingMode.VERTICAL` (overridden by per-document `PdfRecentItem.readingMode` if saved), `isFullscreen = false`, `isAutoScrolling = false` (forced `false` on `HORIZONTAL` mode, opening any sheet/dialog, user `DragInteraction.Start`, reaching document end, or closing doc), `showPageOverview = false`, `zoomScale = 1.0f`, `panOffsetX = 0f`, `panOffsetY = 0f`, `brightnessLevel = -1f` (Auto/system default; custom range `0.05f..1.0f`), `indexScrollIndex = -1`, `indexScrollOffset = 0`, `indexExpandedStates = emptyMap()` (default expansion per node is `true` in `IndexSheet`).
- **Pinch-to-Zoom Bounds:** Clamped `1.0f..4.0f` (`updateZoomAndPan`); double-tap resets to `1.0f` whenever `zoomScale > 1.15f`.
- **Smart Column Double-Tap Math:** In `SMART_COLUMN` mode (`targetScale = 2.08f`), taps on the left half (`tapX < viewWidth * 0.5f`) target column center `0.28f * viewWidth`, while right-half taps target `0.72f * viewWidth`. Horizontal pan is clamped by `maxAllowedPanX = ((viewWidth * (targetScale - 1f)) / 2f - safeMarginPx).coerceAtLeast(0f)` where `safeMarginPx = 16f * (viewWidth / 360f).coerceIn(1.0f, 3.0f)`.

### 7.4 Material 3 Color Schemes & Accent Transformation (`ui/theme/Color.kt` & `ui/theme/Theme.kt`)
- **`LightColorScheme`:** `primary = #D32F2F`, `onPrimary = #FFFFFF`, `primaryContainer = #FFDAD6`, `onPrimaryContainer = #410002`, `secondary = #9A0007`, `secondaryContainer = #FFEBEE`, `background = #F8F9FA`, `surface = #FFFFFF`, `surfaceVariant = #ECEFF1`, `onSurface = #1E293B`, `onSurfaceVariant = #64748B`.
- **`DarkColorScheme`:** `primary = #FF6659`, `onPrimary = #000000`, `primaryContainer = #5C0003`, `onPrimaryContainer = #FFDAD6`, `secondary = #FF867F`, `secondaryContainer = #4A1015`, `background = #121212`, `surface = #1E1E1E`, `surfaceVariant = #2C2C2C`, `onSurface = #F1F5F9`, `onSurfaceVariant = #94A3B8`.
- **`AmoledDarkColorScheme`:** `primary = #FF5252`, `primaryContainer = #38080B`, `background = #000000`, `surface = #000000`, `surfaceVariant = #101010`, `surfaceContainerLowest = #000000`, `surfaceContainerLow = #070707`, `surfaceContainer = #0F0F0F`, `surfaceContainerHigh = #161616`, `surfaceContainerHighest = #202020`, `outline = #2A2A2A`, `outlineVariant = #1A1A1A`.
- **`applyAccentToColorScheme`:** Applied once inside `MyApplicationTheme`. `SCARLET` returns the base scheme untouched; `OCEAN`/`EMERALD`/`VIOLET`/`AMBER` override `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `secondary`, `secondaryContainer` (and `onSecondary`/`onSecondaryContainer`); `DYNAMIC` uses `dynamicLightColorScheme`/`dynamicDarkColorScheme` on API 31+ (preserving AMOLED `background`, `surface`, and `surfaceContainer*` values).

### 7.4 Known Code-vs-Docs Discrepancies
- `SamplePdfGenerator.kt` Page 5 & Page 6 text describes "5-Minute" wake lock and "1/8th max heap, capped at 64MB", whereas the actual code defaults to `KeepAwakeOption.MINUTES_15` and `1/5th` heap (`48 MB..256 MB`).
- `SettingsSheet` offers `Off`, `5m`, `15m`, `Always` chips for `KeepAwakeOption`; `MINUTES_30` exists in the enum (`AppSettings.kt`) without a UI chip.

---

## 8. Complete Interface Structure (Screens, Sections, Menus & Options)

### 8.1 Screen 1: `HomeScreen` (`app/src/main/java/com/example/ui/home/HomeScreen.kt`)
1. **Top App Bar (Standard Mode):**
   - **Left Brand Block:** Red rounded-square PDF icon badge + Title (`MK PDF Reader`) + Subtitle (`Fast & Ultra-Sharp PDF Reader`).
   - **Right Actions:**
     - **View Mode Toggle (`home_view_mode_toggle_btn`):** Toggles `RecentViewMode.GRID` ↔ `RecentViewMode.LIST`.
     - **Settings Button (`home_settings_btn`):** Opens `HomeSettingsSheet`.
     - **Overflow Menu (`home_more_menu_btn`):** `DropdownMenu` containing:
       - `Open Quick Demo Guide` (generates & opens `MK_PDF_Reader_Guide.pdf`)
       - `Clear Recents` (visible only when `recentPdfs.isNotEmpty()`, opens confirmation `AlertDialog`)
2. **Top App Bar (Inline Search Mode, when `isSearchActive == true`):**
   - **Navigation Icon:** Back arrow (`Close Search`, clears query and exits search mode).
   - **Title:** Transparent `TextField` (`recent_search_input`, placeholder `"Search recent PDFs..."`, trailing Clear `X` button).
   - **System Back:** `BackHandler` exits search mode.
3. **Body Content (`LazyVerticalGrid(2 columns)` in `GRID` mode or `LazyColumn` in `LIST` mode):**
   - **Library Folder Strip (`LibraryFolderStrip`, first body item above `ContinueReadingSection`, hidden while `isSearchActive`):** `"Library"` header + `"Manage"` `TextButton` (`library_manage_btn`). With zero roots, displays a dashed-outline card (`library_strip`) `"Add a folder to build your library"`. With roots, renders a `LazyRow` (`library_strip`) of `150×88dp` root cards (`library_root_<id>`) showing folder icon, name, `"N PDFs"`, scan spinner, or `NEEDS_ACCESS` warning icon (tapping opens `LibraryBrowserScreen` at `"rootId|"`), followed by a dashed `"+ Add folder"` card.
   - **Storage Access Banner (`StorageAccessBanner`):** Shown on Android 11+ when `!Environment.isExternalStorageManager()` and not dismissed; `"Enable"` launches `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, `"X"` dismisses.
   - **Continue Reading Card (`ContinueReadingSection`):** Shown above header when library is non-empty and `!isSearchActive` for the most recently opened PDF: shared `PdfCoverThumbnail` on left, filename, `"Page X of Y • N% • Z pages left"`, `LinearProgressIndicator`, filled `"Resume"` button (`continue_reading_card`), and under-card summary `"Today X min • Y-day streak"` (hidden when both values are zero).
   - **Section Header (`RecentHeaderRow`):** `"Recent Documents"` + `"X items"` (or `"Search Results"` + `"X found"`).
   - **Filter & Sort Row (`LibraryFilterSortRow`):** Horizontally scrollable `LazyRow` of `FilterChip`s (`library_filter_<name>` for `ALL`, `IN_PROGRESS`, `FINISHED`, `FAVORITES`) + sort `IconButton` (`library_sort_btn`) opening a `DropdownMenu` (`RECENT`, `NAME`, `SIZE`, `PROGRESS`).
   - **Empty / No-Match States (`EmptyRecentsView`, `NoSearchResultsView`, `NoFilterResultsView`):** PDF circle badge + `"Open Quick Demo Guide"` (`empty_open_demo_btn`), search mismatch text, or empty filter message with `"Show All"` button.
   - **Grid Item Card (`RecentPdfGridCard`):** `190.dp` tall `PdfCoverThumbnail`, bottom-right `"p. X/Y"` badge, bottom `LinearProgressIndicator`, 2-line filename, relative time, formatted file size, favorite star button (`favorite_grid_<hash>`), and Delete icon button (`delete_grid_recent_btn_<hash>`).
   - **List Item Card (`RecentPdfCard`):** `68×94.dp` `PdfCoverThumbnail` with `"p.X"` badge, filename, `"Page X of Y • <timeAgo>"`, file size, `LinearProgressIndicator`, favorite star button (`favorite_<hash>`), and Delete icon button (`delete_recent_btn_<hash>`).
    - **Grid / List Search Results Extension (`InLibrarySearchHeader` & `HomeLibrarySearchRow`):** While `isSearchActive` is true and `searchQuery.trim().length >= 2`, appends an `"In your library"` section after the recents results displaying up to 20 matching library PDFs (`library_search_results`, lazy cover via `LibraryThumbnailStore`, filename, folder `relDir`, file size); tapping opens via `resolveIdentityUri`.
4. **Bottom Dual Floating Action Buttons:**
   - **Bottom-Left (`home_search_pdf_fab`, `56.dp` circle):** Toggles inline search bar.
   - **Bottom-Right (`home_add_pdf_fab`, `60.dp` circle):** Launches `OpenPdfDocumentContract` (`application/pdf`, `application/octet-stream`, `*/*`) with `ACTION_GET_CONTENT` fallback.
5. **Home Bottom Sheet (`HomeSettingsSheet` — `"Display & Settings"`, scrollable):**
   - **Section 1 — `RECENT VIEW LAYOUT`:** Two side-by-side `ViewModeSelectorCard`s (`Grid View` / `List View`).
   - **Section 2 — `APP THEME`:** Four `ThemeOptionCard` radio rows: `AMOLED Dark` (with primary `"OLED Black"` badge), `Dark Theme`, `Light Theme`, `System Default`.
   - **Section 3 — `ACCENT COLOR`:** `Row` of circular `40.dp` swatches (`accent_<name>` for `SCARLET`, `OCEAN`, `EMERALD`, `VIOLET`, `AMBER`, and `DYNAMIC` on API 31+), selected showing a check icon.
   - **Section 4 — `LIBRARY FOLDERS`:** Clickable `"Manage Library Folders"` row closing `HomeSettingsSheet` and opening `LibrarySettingsSheet`.
   - **Section 5 — `BACKUP & DATA`:** `"Export Backup"` (`backup_export_btn`, opens dialog with `"Include thumbnails"` checkbox default `true` and launches `CreateDocument("application/zip")`), `"Import Backup"` (`backup_import_btn`, launches `OpenDocument`), live `"Storage used"` readout (`backup_storage_used_text`), `"Clear thumbnail cache"` (`clear_lib_thumbs_btn`, clears `lib_thumbs/` only), and `"Clear TOC cache"` (`clear_toc_cache_btn`, clears `toc_cache/`).
   - **Section 6 — Quick Tools:** `"Demo Guide"` button + `"Clear Recents"` button (if recents non-empty).
6. **Dialogs:** `Clear Recents` confirmation dialog, Export Backup options dialog (`backup_export_confirm_btn`), Backup progress indicator dialog (`isBackupBusy`), and Backup result/error summary dialog (`backup_result_ok_btn`).

### 8.1b Library Browser & Settings (`ui/library/LibraryBrowserScreen.kt` & `ui/library/LibrarySettingsSheet.kt`)
- **`LibraryBrowserScreen`:**
  - `TopAppBar`: Normal mode (Back, folder title, Search toggle, Sort dropdown with 6 `BrowserSort` options + `"Folders first"` checkbox, Refresh rescan button); Search mode (150ms debounced `Dispatchers.Default` search across `allRootFiles` with `relDir` subtitle); Selection mode (`library_selection_close_btn`, `"N selected"`, Select All `library_selection_select_all_btn`, Add to Recents `library_selection_add_recents_btn`, Favorite `library_selection_favorite_btn`, Delete `library_selection_delete_btn`). System `BackHandler` exits selection mode before search mode or folder up-navigation.
  - `ScanState.Scanning` banner: `LinearProgressIndicator` + `"Scanning… N PDFs found"` and `SnackbarHost` for feedback/errors.
  - Auto-scrolling `LazyRow` breadcrumb (`breadcrumb_<index>`) jumping to any ancestor level on tap.
  - `NEEDS_ACCESS` empty state / top warning banner with `"Grant access"` opening `LibrarySettingsSheet`; empty folder state with `"Rescan"` button.
  - Single `LazyColumn` (`library_browser_list`) with stable keys (`"d:"+relDir`, `"f:"+path`) and `contentType`: `BrowserFolderRow` (`library_folder_<hash>`, `"N PDFs"` recursive count, chevron) and `BrowserPdfRow` (`library_pdf_<hash>`, `combinedClickable` long-press enters selection mode with leading checkbox, lazy cover via `LibraryThumbnailStore`, size + modified date, O(1) recent lookup for `"p.X/Y"` progress bar, and per-row `⋮` menu `library_pdf_menu_<hash>` with `Open`, `Add to Recents`, `Favorite`/`Unfavorite`, `Details` dialog with `ReadingStats` reading time, `Copy path`, and permanent `Delete` confirmation dialog calling `File.delete()`/`DocumentsContract.deleteDocument` + full metadata/cache cleanup). Opening a PDF uses the `resolveIdentityUri` open funnel and leaves `libraryLocation` intact.
- **`LibrarySettingsSheet` (`library_settings_sheet`):**
  - `"Add folder"` (`OpenDocumentTree`, `library_add_folder_btn`), `"Rescan all"` (`library_rescan_all_btn`), per-root cards (`library_root_card_<id>`) with `absPath ?: "via system permission"`, PDF count, last scanned age, `Active` / `Needs access` status chip, Rescan & Remove (with confirmation dialog guaranteeing PDFs are never deleted), `"Auto-rescan on app start"` switch, and Android 11+ All Files Access row refreshing on `ON_RESUME`.

### 8.2 Screen 2: `PdfReaderScreen` (`app/src/main/java/com/example/ui/reader/PdfReaderScreen.kt`)
1. **State 1 — Loading:** Centered `CircularProgressIndicator` (`pdf_loading_indicator`) + `"Rendering crisp document pages..."`.
2. **State 2 — Error & Recovery:** Error icon, `errorMessage`, `"Grant Storage Access"` button (`pdf_error_grant_storage_btn`, Android 11+ when lacking all-files access, auto-retries on `ON_RESUME`), `"Browse / Re-locate File"` button (`pdf_error_relocate_btn` via `OpenDocument`), `"Return to Library"` button (`pdf_error_back_btn`).
3. **State 3 — Active Reader (`engine != null && totalPages > 0`):**
   - **Canvas (`ReaderViewport`):**
     - Background tinted by `colorTone` (`#E9ECEF`, `#EDE3D2`, `#0D0D0D`, `#DCDCDC`).
     - Tap → when `tapZoneNavigation` is `true`, left 25% emits `stepPage(-1)`, right 25% emits `stepPage(1)`, middle 50% calls `toggleFullscreen()` (when `false`, any tap toggles fullscreen); Double-tap → `toggleDoubleTapZoom(...)`; Pinch/Drag → `updateZoomAndPan(...)` via `graphicsLayer`.
     - Collects `viewModel.navCommands` (`MutableSharedFlow<Int>`) from tap zones and `MainActivity` hardware volume keys (`VOLUME_DOWN` → `+1`, `VOLUME_UP` → `-1`), calling `animateScrollBy(delta * viewportHeight * 0.85f)` in `VERTICAL` mode or `animateScrollToPage((currentPage + delta).coerceIn(0, totalPages - 1))` in `HORIZONTAL` mode.
     - Renders `LazyColumn` (`vertical_pdf_list`) when `ReadingMode.VERTICAL` (with `withFrameNanos` auto-scroll loop advancing `speed * 6.dp.toPx()`/sec, ~1/sec `recordUserActivity()` keep-awake pings, and `DragInteraction.Start` cancellation) or `HorizontalPager` (`horizontal_pdf_pager`) when `ReadingMode.HORIZONTAL`.
     - Each page is a `PdfPageView` (`pdf_page_<idx>`) applying draw-time `marginTrim` with an optional bottom-right page badge (`page_number_badge_<1-idx>`) that turns primary-colored with a Bookmark icon when bookmarked and toggles that page's bookmark on tap.
     - Final child overlay (when `warmFilterPercent > 0`): non-interactive `Box(Modifier.matchParentSize().drawBehind { drawRect(Color(0xFFFF8A00), alpha = warmFilterPercent / 100f * 0.45f) })` above pages and below bars.
   - **Top Bar Overlay (`ReaderTopBar`, animated slide+fade when `!isFullscreen`):**
     - Back button (`reader_back_btn`)
     - Title (`documentInfo.fileName`) + Subtitle (`"<currentChapter> • X / Y"` or `"X / Y pages"`)
     - Reset Zoom (`reset_zoom_btn`, visible when `zoomScale > 1.1f`)
     - Reader Settings (`top_settings_btn` → `SettingsSheet`)
     - Document Info (`document_info_btn` → `DocumentInfoDialog`)
     - Fullscreen (`reader_fullscreen_btn`)
     - Overflow Menu (`reader_more_btn` → `DropdownMenu` with `"Page Overview"` [`menu_page_overview`] and `"Start Auto-Scroll"` / `"Stop Auto-Scroll"` [`menu_auto_scroll`, enabled only in `VERTICAL` mode]).
   - **Bottom Bar Overlay (`Column` containing `auto_scroll_pill` + `ReaderBottomBar`):**
     - **Floating Auto-Scroll Pill (`auto_scroll_pill`, animated when `isAutoScrolling == true`, stays visible in fullscreen with `navigationBarsPadding`):** `-` (`auto_scroll_slower_btn`), `"${autoScrollSpeed}x"`, `+` (`auto_scroll_faster_btn`), `"Stop"` (`auto_scroll_stop_btn`).
     - **Page Seek Slider Row:** `"1"` — `Slider` (`page_seeking_slider`, up to 100 steps) — `"$totalPages"`. Scrubbing live-updates the page pill and commits `jumpToPage` on release.
     - **Expandable Brightness Bar (`AnimatedVisibility(showBrightnessBar)`):** Brightness icon + `Slider` (`brightness_slider`, `0.05f..1.0f`) + `"Auto"` / `"XX%"` toggle pill (`brightness_auto_toggle`). Controls `activity.window.attributes.screenBrightness` (`BRIGHTNESS_OVERRIDE_NONE` when `-1f`, reset on dispose).
     - **6-Control Action Toolbar Row:**
       1. Reading Mode (`toggle_reading_mode_btn`): `"Vertical"` ↔ `"Pages"`
       2. Orientation (`toggle_orientation_btn`): `"Portrait"` ↔ `"Landscape"` (applies `ActivityInfo.SCREEN_ORIENTATION_*` and shows center HUD pill)
       3. Page Indicator Pill (`page_indicator_pill`): `"X / Y"` → opens `JumpToPageDialog`
       4. Bookmark Pill (`bottom_bookmark_btn`): Tap toggles bookmark on current page; Long-press (`combinedClickable.onLongClick`) opens `BookmarksSheet`. Displays bookmark icon + count badge when `bookmarkCount > 0`.
       5. Index Button (`bottom_index_btn`): Opens `IndexSheet`
       6. Brightness Button (`bottom_brightness_btn`): Toggles expandable brightness bar.
   - **Center HUD Feedback Pill:** Auto-dismissing (`1.6–1.8s`) toast pill for orientation switches and bookmark add/remove confirmations.
   - **Exit Fullscreen Floating Button (`exit_fullscreen_btn`):** Top-right `40.dp` `FilledTonalIconButton` visible only when `isFullscreen == true`.

### 8.3 Reader Modal Sheets & Dialogs
- **`IndexSheet` (`ui/reader/IndexSheet.kt`):** Fullscreen `Dialog` consuming `OutlineIndex`:
  - `TopAppBar`: Back (`index_back_btn`), Document Title + subtitle (`"Indexing document structure in background..."` / `"X of Y chapters found"` / `"Y chapters found"` / `"Table of Contents"`), Close (`index_close_btn`).
  - Search bar (`index_search_input`, clear button `clear_index_search_btn`): 150 ms debounced `produceState` on `Dispatchers.Default` (`snapshotFlow` + `debounce`), matching `lowerTitles` and page forms (`query`, `p. X`, `page X`) capped at 300 results; `SearchResultRow` (`search_item_<id>`) builds ancestor breadcrumbs lazily on-screen via `parentIndex`, highlights matched span in bold blue (`#1976D2`), and shows `"Page X"` badge.
  - Sub-header toolbar (when not searching & outline exists): `"CONTENTS"` indicator + `"Expand All"` (`expand_all_btn`) & `"Collapse All"` (`collapse_all_btn`) buttons (single-assignment expansion map replacement).
  - Body states: Loading spinner (`index_loading_indicator`), No search matches (`"Clear search"` button), Honest empty state (`"No index available"`, caption `"This PDF may not contain embedded bookmarks."`, monospace low-alpha `PdfOutlineParser.lastDiagnostic(uri)` line passed via ViewModel, and `"Retry indexing"` `OutlinedButton` [`index_retry_btn`] calling `PdfReaderViewModel.reloadOutline()`), or `LazyColumn` (`index_outline_list`) computing visible rows in one linear pass (`BooleanArray` + `parentIndex`) with `key = { flat[it].id }` and `contentType`, rendering `FlatOutlineItemRow` (`outline_item_<id>`, `toggle_expand_<id>`) with `chapterForPage` first-open auto-scroll and saved scroll/expansion state.
- **`BookmarksSheet` (`ui/reader/BookmarksSheet.kt`):** `ModalBottomSheet`:
  - Header (`"Bookmarks"`, `"X saved page(s)"`, `close_bookmarks_btn`).
  - Quick Bookmark Current Page Card (`sheet_toggle_current_bookmark_btn`: `"Add Bookmark"` / `"Bookmarked"`).
  - Empty State (`"No bookmarks yet"`) or `LazyColumn` (`bookmarks_list`) of `bookmark_item_<pageIndex>` rows showing 1-based page badge, title (defaulting to active chapter title when toggled if non-blank), formatted timestamp (`MMM d, yyyy • h:mm a`), tap-to-jump, Edit button (`edit_bookmark_<pageIndex>` opening rename `AlertDialog` with single-line `OutlinedTextField`), and delete button (`delete_bookmark_<pageIndex>`).
- **`PageOverviewSheet` (`ui/reader/PageOverviewSheet.kt`):** Fullscreen `Dialog`:
  - `TopAppBar`: Back (`overview_back_btn`), Document Title + subtitle (`"Page Overview • $totalPages pages"`), Close (`overview_close_btn`).
  - `LazyVerticalGrid(GridCells.Adaptive(100.dp))` (`page_overview_grid`, scrolled initially to `currentPage`): renders `PageOverviewCell` (`overview_page_<idx>`) loading 200 px thumbnails asynchronously via `engine.renderGridThumbnail`, primary border highlight on `currentPage`, top-right bookmark badge when bookmarked, 1-based footer page label, and tap-to-jump + dismiss.
- **`SettingsSheet` (`ui/reader/SettingsSheet.kt`):** `ModalBottomSheet` (scrollable, `close_settings_btn`) with 7 sections:
  1. *Text Clarity & Sharpness:* 3 `OutlinedCard`s (`sharpness_balanced`, `sharpness_high_clarity`, `sharpness_ultra_hd`) + `Subpixel Antialiasing` `Switch` (`switch_print_mode`).
  2. *Fluid Scrolling & Performance:* 3 `FilterChip`s (`prefetch_off`, `prefetch_standard`, `prefetch_aggressive`).
  3. *Page Color Tone & Themes:* 4 color swatch cards (`tone_original`, `tone_sepia`, `tone_inverted_dark`, `tone_high_contrast`), `Page Spacing` chips (`Tight`, `Normal`, `Wide`), `Page Number Badges` `Switch` (`switch_show_badges`).
  4. *Orientation & Screen Wake:* `ReaderOrientation` chips (`Portrait`, `Landscape`) + `Keep Screen On` chips (`keep_awake_off`, `keep_awake_5m`, `keep_awake_15m`, `keep_awake_always`).
  5. *Double-Tap Smart Zoom:* 5 selectable cards (`zoom_mode_SMART_COLUMN`, `COMPACT_1_8X`, `STANDARD_2_1X`, `LARGE_2_3X`, `MAX_2_6X`).
  6. *Memory & Cache Diagnostics:* Live `"X.X MB / Y MB allocated"` readout + `"Flush Cache"` button (`flush_cache_btn`).
  7. *Reading Comfort:* `MarginTrim` `FilterChip`s (`margin_trim_off`, `margin_trim_light`, `margin_trim_medium`, `margin_trim_strong`), `Warm Eye-Care Filter` `Slider` (`warm_filter_slider`, `0..60%`), `Tap-Zone Navigation` `Switch` (`switch_tap_zone_nav`), `Volume Key Navigation` `Switch` (`switch_volume_key_nav`), and `Auto-Scroll Speed` `Slider` (`auto_scroll_speed_slider`, `1..10x`).
- **`JumpToPageDialog` (`ui/reader/JumpToPageDialog.kt`):** `AlertDialog` with `-` (`jump_decrement_btn`), numeric `OutlinedTextField` (`page_number_input`, `ImeAction.Go`), `+` (`jump_increment_btn`), quick-step chips (`1`, `-10`, `+10`, `$totalPages` — `-10`/`+10` hidden if `totalPages < 10`), `"Go"` (`jump_confirm_btn`), and `"Cancel"` (`jump_cancel_btn`).
- **`DocumentInfoDialog` (`ui/reader/DocumentInfoDialog.kt`):** `AlertDialog` displaying `File Name`, `Total Pages`, `File Size`, `Source`, and `Time Spent`, with `"Close"` (`dismiss_info_btn`).

### 8.4 Complete `testTag` Inventory
- **HomeScreen:** `library_manage_btn`, `library_strip`, `library_root_<id>`, `recent_search_input`, `in_library_search_header`, `home_library_search_item_<hash>`, `home_view_mode_toggle_btn`, `home_settings_btn`, `home_more_menu_btn`, `continue_reading_card`, `library_filter_<name>`, `library_sort_btn`, `recent_pdf_grid_card_<hash>`, `favorite_grid_<hash>`, `delete_grid_recent_btn_<hash>`, `recent_pdf_card_<hash>`, `favorite_<hash>`, `delete_recent_btn_<hash>`, `accent_<name>`, `backup_export_btn`, `backup_import_btn`, `backup_storage_used_text`, `clear_lib_thumbs_btn`, `clear_toc_cache_btn`, `backup_export_confirm_btn`, `backup_result_ok_btn`, `home_search_pdf_fab`, `home_add_pdf_fab`, `empty_open_demo_btn`.
- **LibraryBrowserScreen & LibrarySettingsSheet:** `library_selection_close_btn`, `library_selection_select_all_btn`, `library_selection_add_recents_btn`, `library_selection_favorite_btn`, `library_selection_delete_btn`, `breadcrumb_<index>`, `library_browser_list`, `library_folder_<hash>`, `library_pdf_<hash>`, `library_pdf_menu_<hash>`, `library_settings_sheet`, `library_add_folder_btn`, `library_rescan_all_btn`, `library_root_card_<id>`.
- **PdfReaderScreen:** `pdf_loading_indicator`, `pdf_error_grant_storage_btn`, `pdf_error_relocate_btn`, `pdf_error_back_btn`, `exit_fullscreen_btn`, `vertical_pdf_list`, `horizontal_pdf_pager`, `pdf_page_<idx>`, `page_number_badge_<1-idx>`, `reader_back_btn`, `reset_zoom_btn`, `top_settings_btn`, `document_info_btn`, `reader_fullscreen_btn`, `reader_more_btn`, `menu_page_overview`, `menu_auto_scroll`, `auto_scroll_pill`, `auto_scroll_slower_btn`, `auto_scroll_faster_btn`, `auto_scroll_stop_btn`, `page_seeking_slider`, `brightness_slider`, `brightness_auto_toggle`, `toggle_reading_mode_btn`, `toggle_orientation_btn`, `page_indicator_pill`, `bottom_bookmark_btn`, `bottom_index_btn`, `bottom_brightness_btn`.
- **Sheets & Dialogs:** `overview_back_btn`, `overview_close_btn`, `page_overview_grid`, `overview_page_<idx>`, `index_back_btn`, `index_close_btn`, `index_search_input`, `clear_index_search_btn`, `expand_all_btn`, `collapse_all_btn`, `index_loading_indicator`, `index_search_results_list`, `search_item_<id>`, `index_outline_list`, `outline_item_<id>`, `toggle_expand_<id>`, `index_retry_btn`, `close_bookmarks_btn`, `sheet_toggle_current_bookmark_btn`, `bookmarks_list`, `bookmark_item_<idx>`, `edit_bookmark_<idx>`, `delete_bookmark_<idx>`, `close_settings_btn`, `sharpness_<name>`, `switch_print_mode`, `prefetch_<name>`, `tone_<name>`, `switch_show_badges`, `keep_awake_<label>`, `zoom_mode_<name>`, `flush_cache_btn`, `margin_trim_<name>`, `warm_filter_slider`, `switch_tap_zone_nav`, `switch_volume_key_nav`, `auto_scroll_speed_slider`, `jump_decrement_btn`, `page_number_input`, `jump_increment_btn`, `jump_confirm_btn`, `jump_cancel_btn`, `dismiss_info_btn`.

---

## 9. Feature-to-File Quick Lookup Index

| Feature / Subsystem | Primary File(s) | Supporting File(s) |
|---|---|---|
| **App Entry, Intent Handling (`VIEW`/`SEND`), SAF Picker** | `MainActivity.kt` | `PdfDocumentStorage.kt`, `PdfRepository.kt`, `AndroidManifest.xml` |
| **Folder-Based Library Roots, Scanner, Thumbnails & Relink** | `data/library/LibraryRepository.kt`, `data/library/LibraryScanner.kt`, `data/library/LibraryThumbnailStore.kt` | `LibraryModels.kt`, `LibraryStorage.kt`, `FilePathResolver.kt`, `PdfRepository.kt` |
| **In-App Library Browser, Selection Actions, Folder Strip & Library Settings** | `ui/library/LibraryBrowserScreen.kt`, `ui/library/LibrarySettingsSheet.kt`, `ui/library/LibraryViewModel.kt` | `ui/home/HomeScreen.kt`, `MainActivity.kt`, `AppSettings.kt`, `LibraryRepository.kt`, `LibraryThumbnailStore.kt`, `ReadingStats.kt` |
| **Backup Export / Import, Storage Management & Auto-Backup Rules** | `data/backup/BackupManager.kt`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`, `PdfBookmarkDao.kt`, `PdfOutlineParser.kt`, `LibraryThumbnailStore.kt` |
| **Recent Library Grid/List, Search, Filter/Sort, Favorites & Continue Reading** | `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt` | `PdfRepository.kt`, `PdfDirectoryStorage.kt`, `RecentPdfJsonStorage.kt`, `PdfRecentDao.kt`, `AppSettings.kt`, `ReadingStats.kt`, `LibraryRepository.kt` |
| **Zero-Copy URI / Path / MediaStore File Opening** | `data/pdf/PdfDocumentStorage.kt`, `data/pdf/FilePathResolver.kt` | `PdfDirectoryStorage.kt`, `AppDatabase.kt` |
| **Native PDF Page Rendering, Supersampling, Color Tones & LRU Cache** | `data/pdf/PdfRendererEngine.kt` | `ui/components/PdfPageView.kt`, `data/settings/AppSettings.kt` |
| **Book Outline / TOC Extraction (Raw Reader + Pdfium Fallback + Cache)** | `data/pdf/RawPdfOutlineReader.kt`, `data/pdf/PdfOutlineParser.kt`, `data/pdf/OutlineHelper.kt` | `ui/reader/IndexSheet.kt`, `ui/reader/PdfReaderViewModel.kt` |
| **Dual Reading Modes (`VERTICAL` / `HORIZONTAL`) & Gestures** | `ui/reader/PdfReaderScreen.kt`, `ui/reader/PdfReaderViewModel.kt` | `data/pdf/PdfDocumentSource.kt`, `ui/components/PdfPageView.kt` |
| **Smart Column Double-Tap Zoom & Pinch/Pan** | `ui/reader/PdfReaderViewModel.kt` (`toggleDoubleTapZoom`, `updateZoomAndPan`) | `ui/reader/PdfReaderScreen.kt`, `data/settings/AppSettings.kt` |
| **Bookmarks (Per-Page Toggle, Chapter Titles, Rename & Sheet)** | `ui/reader/BookmarksSheet.kt`, `data/db/PdfBookmarkDao.kt` | `data/db/PdfBookmarkEntity.kt`, `PdfRepository.kt`, `PdfPageView.kt` |
| **Reader Settings, Keep-Awake, Orientation & Brightness** | `ui/reader/SettingsSheet.kt`, `data/settings/AppSettings.kt` | `ui/reader/PdfReaderViewModel.kt`, `ui/reader/PdfReaderScreen.kt`, `MainActivity.kt` |
| **Reading Time & Daily Streak Tracking** | `data/settings/ReadingStats.kt`, `ui/reader/PdfReaderViewModel.kt` | `ui/reader/PdfReaderScreen.kt`, `ui/reader/DocumentInfoDialog.kt`, `ui/home/HomeScreen.kt` |
| **Page Jump, Metadata & Page Overview Grid** | `ui/reader/JumpToPageDialog.kt`, `ui/reader/DocumentInfoDialog.kt`, `ui/reader/PageOverviewSheet.kt` | `ui/reader/PdfReaderScreen.kt`, `PdfRepository.kt`, `PdfRendererEngine.kt` |
| **Reading Comfort (Margin Trim, Warm Filter, Shortcuts, Auto-Scroll)** | `ui/reader/SettingsSheet.kt`, `ui/reader/PdfReaderScreen.kt`, `ui/components/PdfPageView.kt` | `ui/reader/PdfReaderViewModel.kt`, `data/settings/AppSettings.kt`, `MainActivity.kt` |
| **App Themes & Accent Colors (System / Light / Dark / AMOLED + Accents)** | `ui/theme/Theme.kt`, `ui/theme/Color.kt` | `data/settings/AppSettings.kt`, `ui/home/HomeScreen.kt`, `MainActivity.kt` |
| **Demo PDF Generation** | `data/pdf/SamplePdfGenerator.kt` | `ui/home/HomeViewModel.kt`, `PdfDocumentStorage.kt` |
