package app.monote.mobile

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.monote.mobile.core.storage.LibraryDirectories
import app.monote.mobile.core.storage.LibraryPaths
import app.monote.mobile.data.catalog.CatalogMirror
import app.monote.mobile.data.catalog.CatalogRepository
import app.monote.mobile.data.catalog.LibraryIndexer
import app.monote.mobile.data.catalog.MoNoteDatabase
import app.monote.mobile.feature.editor.RecoveryStore
import app.monote.mobile.feature.importing.FolderImportCoordinator
import app.monote.mobile.feature.importing.ImportCoordinator
import app.monote.mobile.feature.library.DirectoryMetadataRepository
import app.monote.mobile.feature.library.LibraryService
import app.monote.mobile.feature.library.MoveRecoveryRepository
import app.monote.mobile.feature.library.PendingRestoreTrustStore
import app.monote.mobile.feature.library.StorageInspector
import app.monote.mobile.feature.library.TrashRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val libraryLock = Any()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        applicationContext.preferencesDataStoreFile(SETTINGS_FILE)
    }

    @Suppress("DEPRECATION")
    val libraryRoot: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            .resolve(LIBRARY_DIRECTORY)

    @Volatile
    private var initializedLibraryServices: LibraryServices? = null

    fun libraryServices(): LibraryServices {
        if (!Environment.isExternalStorageManager()) {
            throw SecurityException("External storage management permission is required")
        }
        initializedLibraryServices?.let { return it }
        return synchronized(libraryLock) {
            initializedLibraryServices ?: createLibraryServices().also {
                initializedLibraryServices = it
            }
        }
    }

    private fun createLibraryServices(): LibraryServices {
        if (!Environment.isExternalStorageManager()) {
            throw SecurityException("External storage management permission was revoked")
        }
        val paths = LibraryPaths(libraryRoot).ensureCreated()
        val database = Room.databaseBuilder(
            applicationContext,
            MoNoteDatabase::class.java,
            DATABASE_FILE,
        ).build()
        val mirror = CatalogMirror(paths.system)
        val catalog = CatalogRepository(database.catalogDao(), mirror)
        val indexer = LibraryIndexer(catalog)
        val directoryMetadataRepository = DirectoryMetadataRepository(paths)
        val moveRecoveryRepository = MoveRecoveryRepository(paths)
        val requestRescan: () -> Unit = {
            applicationScope.launch { indexer.scan(paths.root) }
        }
        val libraryService = LibraryService(
            paths,
            catalog,
            directoryMetadataRepository,
            moveRecoveryRepository,
            requestRescan = requestRescan,
        )
        val trashRepository = TrashRepository(
            paths,
            catalog,
            PendingRestoreTrustStore(applicationContext.noBackupFilesDir.resolve("pending_restore_trust")),
            directoryMetadataRepository = directoryMetadataRepository,
            requestRescan = requestRescan,
        )
        applicationScope.launch {
            try {
                trashRepository.reconcileStartup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Startup trash metadata reconciliation failed; later operations will retry", failure)
                // Later trash reads and cleanup actions retry the same reconciliation.
            }
        }
        return LibraryServices(
            paths = paths,
            database = database,
            catalogMirror = mirror,
            catalogRepository = catalog,
            libraryIndexer = indexer,
            importCoordinator = ImportCoordinator(),
            folderImportCoordinator = FolderImportCoordinator(),
            libraryService = libraryService,
            trashRepository = trashRepository,
            directoryMetadataRepository = directoryMetadataRepository,
            moveRecoveryRepository = moveRecoveryRepository,
            storageInspector = StorageInspector(paths, applicationContext.cacheDir.resolve("renderer")),
            recoveryStore = RecoveryStore(paths.recovery),
        )
    }

    private companion object {
        const val LIBRARY_DIRECTORY = "MoNote"
        const val DATABASE_FILE = "monote.db"
        const val SETTINGS_FILE = "settings.preferences_pb"
        const val TAG = "MoNoteAppContainer"
    }
}

data class LibraryServices(
    val paths: LibraryDirectories,
    val database: MoNoteDatabase,
    val catalogMirror: CatalogMirror,
    val catalogRepository: CatalogRepository,
    val libraryIndexer: LibraryIndexer,
    val importCoordinator: ImportCoordinator,
    val folderImportCoordinator: FolderImportCoordinator,
    val libraryService: LibraryService,
    val trashRepository: TrashRepository,
    val directoryMetadataRepository: DirectoryMetadataRepository,
    val moveRecoveryRepository: MoveRecoveryRepository,
    val storageInspector: StorageInspector,
    val recoveryStore: RecoveryStore,
)
