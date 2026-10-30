package zw.co.accisafe.rdc.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import zw.co.accisafe.rdc.data.AuthRepository
import zw.co.accisafe.rdc.data.apiCall
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.ApiService
import javax.inject.Inject

/** Loading / data / error state for a screen. */
data class Ui<T>(val loading: Boolean = true, val data: T? = null, val error: String? = null)

/** ViewModel that loads one value from the API and runs actions with a shared busy/message state. */
abstract class RemoteViewModel<T>(protected val client: ApiClient) : ViewModel() {
    protected val api: ApiService get() = client.api
    private val _state = MutableStateFlow(Ui<T>())
    val state: StateFlow<Ui<T>> = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    protected abstract suspend fun fetch(): T

    fun reload() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        apiCall { fetch() }
            .onSuccess { _state.value = Ui(loading = false, data = it) }
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
    }

    /** Runs an action, shows [success] or the error, and reloads on success. */
    protected fun act(success: String?, onDone: (() -> Unit)? = null, block: suspend () -> Unit) = viewModelScope.launch {
        if (_busy.value) return@launch // prevents duplicate submissions from repeated taps
        _busy.value = true
        apiCall { block() }
            .onSuccess { _message.value = success; onDone?.invoke(); reload() }
            .onFailure { _message.value = it.message }
        _busy.value = false
    }

    fun clearMessage() { _message.value = null }
}

@HiltViewModel
class SessionViewModel @Inject constructor(private val auth: AuthRepository, client: ApiClient) : ViewModel() {
    val me = auth.me
    val signedOut = client.signedOut
    val unread = MutableStateFlow(0)

    init {
        viewModelScope.launch { auth.refreshProfile() }
    }

    fun logout() = viewModelScope.launch { auth.logout() }
}
