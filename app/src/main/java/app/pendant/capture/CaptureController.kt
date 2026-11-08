package app.pendant.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the settings screen shows of the capture service connection. */
object CaptureController {

    sealed interface Status {
        data object Off : Status
        data object Connecting : Status
        /** Last answer received; [mode] is what the service assigned, null when it assigned nothing. */
        data class Connected(val mode: String?) : Status
        /** No good answer yet; the phone records on its own once the lease runs out. */
        data class Failing(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status

    fun post(status: Status) {
        _status.value = status
    }
}
