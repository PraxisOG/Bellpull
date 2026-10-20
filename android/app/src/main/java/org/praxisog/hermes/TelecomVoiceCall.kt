package org.praxisog.hermes

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Telecom owns focus and routing for the entire conversation, including silence.
 * No phone numbers, chat titles, or transcript text enter the system call record.
 * All public methods and callbacks run on Main.
 */
internal class TelecomVoiceCall(
    context: Context,
    private val onReady: () -> Unit,
    private val onActive: () -> Unit,
    private val onHeld: () -> Unit,
    private val onMuted: (Boolean) -> Unit,
    private val onEnded: () -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val manager = CallsManager(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transitions = Mutex()
    private var control: CallControlScope? = null
    private var ended = false
    private var disconnectRequested = false
    private var ready = false
    private var revision = 0L
    private var job: Job? = null
    var active = false
        private set

    fun start() {
        check(job == null)
        job = scope.launch {
            try {
                manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                manager.addCall(
                    CallAttributesCompat("Bellpull", Uri.parse("bellpull:voice"),
                        CallAttributesCompat.DIRECTION_OUTGOING,
                        CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                        CallAttributesCompat.SUPPORTS_SET_INACTIVE),
                    onAnswer = { withContext(Dispatchers.Main.immediate) { activateFromSystem() } },
                    onDisconnect = { withContext(Dispatchers.Main.immediate) {
                        disconnectRequested = true
                        if (!ended) { ended = true; active = false; onEnded() }
                    } },
                    onSetActive = { withContext(Dispatchers.Main.immediate) { activateFromSystem() } },
                    onSetInactive = { withContext(Dispatchers.Main.immediate) {
                        revision++; active = false
                        if (!ended) onHeld()
                    } },
                ) {
                    control = this
                    if (ended) {
                        disconnectRequested = true
                        launch { disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                    } else {
                        launch { isMuted.collect { if (!ended) onMuted(it) } }
                        launch { currentCallEndpoint.collect {
                            // Route changes are expected when the car/headset connects.
                            // Telecom arbitrates the route; never fight it with AudioManager.
                            android.util.Log.i("BellpullVoiceAudio", "telecom_endpoint=${it.type}")
                        } }
                        setHeld(false)
                    }
                }
                if (!ended) { ended = true; active = false; onEnded() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!ended) { ended = true; active = false; onError("Android could not connect call mode. End voice and retry, or choose Hands-free.") }
            } finally {
                withContext(NonCancellable) {
                    if (!disconnectRequested)
                        runCatching { withTimeout(5000) { control?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) } }
                }
                control = null
                scope.cancel()
            }
        }
    }

    private fun activateFromSystem() {
        revision++
        if (ended) return
        active = true
        if (!ready) { ready = true; onReady() } else onActive()
    }

    fun setHeld(held: Boolean) {
        if (ended || (ready && active == !held)) return
        val call = control ?: return
        val token = ++revision
        if (held) active = false
        scope.launch {
            transitions.withLock {
                if (ended || token != revision) return@withLock
                val result = if (held) call.setInactive() else call.setActive()
                if (ended || token != revision) return@withLock
                if (result is CallControlResult.Success) {
                    active = !held
                    if (!held) {
                        if (!ready) { ready = true; onReady() } else onActive()
                    }
                } else onError("Android could not ${if (held) "hold" else "resume"} this voice call. Check other calls and retry.")
            }
        }
    }

    override fun close() {
        if (ended && control == null) { scope.cancel(); return }
        ended = true; active = false; revision++
        val call = control ?: return // A late successful addCall immediately disconnects.
        if (disconnectRequested) return
        disconnectRequested = true
        scope.launch {
            try { withTimeout(5000) { call.disconnect(DisconnectCause(DisconnectCause.LOCAL)) } }
            finally { scope.cancel() }
        }
    }
}
