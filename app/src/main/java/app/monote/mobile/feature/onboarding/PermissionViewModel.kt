package app.monote.mobile.feature.onboarding

import android.os.Environment
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionViewModel : ViewModel() {
    private val _granted = MutableStateFlow(Environment.isExternalStorageManager())
    val granted = _granted.asStateFlow()

    fun refresh() {
        _granted.value = Environment.isExternalStorageManager()
    }
}
