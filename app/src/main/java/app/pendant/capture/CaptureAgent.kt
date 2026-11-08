package app.pendant.capture

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import app.pendant.recording.RecordingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import kotlin.coroutines.resume

/**
 * The phone's capture agent: long-polls the capture service's heartbeat route, reports the
 * `builtin` microphone and applies the assignment to [engine]. Optional; the service
 * creates it only when the user has turned it on.
 *
 * - The lease is counted on `elapsedRealtime`. When it runs out without a good answer
 *   (offline, timeout, any status but 200) the input records again: duplicates, never a gap.
 * - A state or `doing` change, and a network change, sends a heartbeat at once.
 * - [stop] says goodbye (`"inputs": []`) so the others need not wait for the lease.
 */
class CaptureAgent(
    context: Context,
    private val engine: RecordingEngine,
    private val url: String,
    private val token: String,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lease = Lease()
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private var expiry: Job? = null
    private var loop: Job? = null
    @Volatile private var link: String? = null
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun start() {
        if (loop != null) return
        CaptureController.post(CaptureController.Status.Connecting)
        engine.setCaptureManaged(true)
        engine.onInputChanged = { poke.trySend(Unit) }
        link = describeLink(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "no network callback", e)
        }
        loop = scope.launch { run() }
    }

    /** Applies nothing more, tells the service this agent is leaving, and returns. */
    suspend fun stop() {
        if (loop == null) return
        loop?.cancel()
        loop = null
        expiry?.cancel()
        engine.onInputChanged = null
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        engine.setCaptureManaged(false)
        withTimeoutOrNull(GOODBYE_MS) { post(input = null, wait = 0, timeoutMs = GOODBYE_MS.toInt()) }
        CaptureController.post(CaptureController.Status.Off)
        scope.cancel()
    }

    private suspend fun run() {
        while (scope.isActive) {
            val startedAt = SystemClock.elapsedRealtime()
            val request = scope.async(Dispatchers.IO) { post(currentInput(), CaptureProtocol.WAIT_SECONDS, REQUEST_TIMEOUT_MS) }
            // A newer state sends a newer heartbeat, which the service answers first.
            val outcome = select<Result?> {
                request.onAwait { it }
                poke.onReceive { request.cancel(); null }
            }
            when (outcome) {
                null -> continue
                is Result.Answer -> {
                    val now = SystemClock.elapsedRealtime()
                    lease.grant(outcome.answer.mode, outcome.answer.validForSeconds * 1000L, now)
                    scheduleExpiry(outcome.answer.validForSeconds * 1000L)
                    apply()
                    CaptureController.post(CaptureController.Status.Connected(outcome.answer.mode))
                    // The next request follows at once; the floor only protects against a
                    // service that answers without holding.
                    val spent = now - startedAt
                    if (spent < MIN_GAP_MS) wait(MIN_GAP_MS - spent)
                }
                is Result.Failure -> {
                    // Like no answer: the lease holds until it runs out.
                    CaptureController.post(CaptureController.Status.Failing(outcome.reason))
                    wait(RETRY_MS)
                }
            }
        }
    }

    /** Sleeps up to [ms], cut short by a state change. */
    private suspend fun wait(ms: Long) {
        withTimeoutOrNull(ms) { poke.receive() }
    }

    private fun scheduleExpiry(validForMs: Long) {
        expiry?.cancel()
        expiry = scope.launch {
            delay(validForMs)
            apply()
            poke.trySend(Unit)
        }
    }

    private fun apply() {
        engine.setRemoteOff(lease.isOff(SystemClock.elapsedRealtime()))
    }

    private fun currentInput(): CaptureProtocol.Input {
        val facts = engine.inputFacts()
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return CaptureProtocol.Input(
            state = facts.state,
            doing = facts.doing,
            assigned = lease.held(SystemClock.elapsedRealtime()),
            battery = if (level in 0..100) level / 100f else null,
        )
    }

    private sealed interface Result {
        data class Answer(val answer: CaptureProtocol.Answer) : Result
        data class Failure(val reason: String) : Result
    }

    /** One heartbeat; [input] null is the goodbye. Cancelling the coroutine drops the connection. */
    private suspend fun post(input: CaptureProtocol.Input?, wait: Int, timeoutMs: Int): Result {
        val endpoint = CaptureProtocol.endpoint(url, wait) ?: return Result.Failure("no URL")
        val body = CaptureProtocol.requestBody(OffsetDateTime.now(), link, input)
        return suspendCancellableCoroutine { cont ->
            val conn = try {
                URL(endpoint).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                cont.resume(Result.Failure(e.message ?: "bad URL"))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { conn.disconnect() }
            Thread({
                val result = try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = timeoutMs
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        CaptureProtocol.parseAnswer(text)?.let { Result.Answer(it) } ?: Result.Failure("unreadable answer")
                    } else {
                        Result.Failure(if (code == 401) "token rejected" else "HTTP $code")
                    }
                } catch (e: Exception) {
                    Result.Failure(e.message ?: e::class.java.simpleName)
                } finally {
                    conn.disconnect()
                }
                if (cont.isActive) cont.resume(result)
            }, "pendant-capture-http").apply { isDaemon = true }.start()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            poke.trySend(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val now = describeLink(caps)
            if (now != link) {
                link = now
                poke.trySend(Unit)
            }
        }
    }

    private fun describeLink(caps: NetworkCapabilities?): String? = when {
        caps == null -> null
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    private companion object {
        const val TAG = "PendantCapture"
        /** Long poll `wait` + 10 s. */
        const val REQUEST_TIMEOUT_MS = (CaptureProtocol.WAIT_SECONDS + 10) * 1000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val GOODBYE_MS = 2_500L
        const val MIN_GAP_MS = 1_500L
        const val RETRY_MS = 5_000L
    }
}
