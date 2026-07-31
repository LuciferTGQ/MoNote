package app.monote.mobile

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.monote.mobile.core.storage.LibraryDirectories
import app.monote.mobile.core.storage.LibraryPaths
import app.monote.mobile.data.catalog.CatalogMirror
import app.monote.mobile.data.catalog.CatalogRepository
import app.monote.mobile.data.catalog.MoNoteDatabase
import app.monote.mobile.feature.editor.RecoveryStore
import app.monote.mobile.feature.importing.ImportCoordinator
import app.monote.mobile.feature.library.LibraryService
import java.io.File

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val libraryLock = Any()

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
        return LibraryServices(
            paths = paths,
            database = database,
            catalogMirror = mirror,
            catalogRepository = catalog,
            importCoordinator = ImportCoordinator(),
            libraryService = LibraryService(paths, catalog),
            recoveryStore = RecoveryStore(paths.recovery),
        )
    }

    private companion object {
        const val LIBRARY_DIRECTORY = "MoNote"
        const val DATABASE_FILE = "monote.db"
        const val SETTINGS_FILE = "settings.preferences_pb"
    }
}

data class LibraryServices(
    val paths: LibraryDirectories,
    val database: MoNoteDatabase,
    val catalogMirror: CatalogMirror,
    val catalogRepository: CatalogRepository,
    val importCoordinator: ImportCoordinator,
    val libraryService: LibraryService,
    val recoveryStore: RecoveryStore,
)
