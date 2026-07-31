package app.monote.mobile

import android.app.Application

class MoNoteApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
}
