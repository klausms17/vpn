package androidx.lifecycle
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
open class ViewModel { protected open fun onCleared() {} }
open class AndroidViewModel(private val application: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST") fun <T : Application> getApplication(): T = application as T
}
val ViewModel.viewModelScope: CoroutineScope get() = MainScope()
