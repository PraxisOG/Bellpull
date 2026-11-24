package org.praxisog.hermes

/** A server failure can retry a sentence locally only before its first audible frame. */
internal class ConversationVoiceOutput(private val server: VoiceOutput, private val device: VoiceOutput,
    private val reportSwitch: () -> Unit) : VoiceOutput {
    private class Utterance(val text: String, val started: () -> Unit, val complete: () -> Unit) { var audible = false }
    private var current = server
    private var pending: Utterance? = null
    private var ready = false
    private var preparing = false
    private var onPrepared: ((String) -> Unit)? = null
    private var failure: (String) -> Unit = {}
    private var generation = 0L
    private var closed = false
    override val routeNotice get() = if (current === device) "Using on-device read-aloud" else null

    override fun prepare(onReady: (String) -> Unit, onError: (String) -> Unit) {
        if (closed) return
        stop()
        onPrepared = onReady
        failure = onError
        prepareCurrent()
    }
    private fun prepareCurrent() {
        val attempt = ++generation
        val source = current
        ready = false
        preparing = true
        source.prepare({ name ->
            if (!closed && attempt == generation && source === current) {
                preparing = false
                ready = true
                val notify = onPrepared
                onPrepared = null
                if (notify != null) notify(name) else pending?.let(::play)
            }
        }, { problem ->
            if (!closed && attempt == generation && source === current) {
                preparing = false
                ready = false
                if (source === server) {
                    current = device
                    server.stop()
                    reportSwitch()
                    if (pending?.audible == true) {
                        pending = null
                        failure("Server speech stopped during playback. Resume to use on-device read-aloud.")
                    } else prepareCurrent()
                } else { pending = null; failure(problem) }
            }
        })
    }
    override fun speak(text: String, started: () -> Unit, complete: () -> Unit) {
        if (closed) return
        val utterance = Utterance(text, started, complete)
        pending = utterance
        if (ready) play(utterance) else if (!preparing) prepareCurrent()
    }
    private fun play(utterance: Utterance) {
        val source = current
        source.speak(utterance.text, {
            if (pending === utterance && source === current) { utterance.audible = true; utterance.started() }
        }, {
            if (pending === utterance && source === current) { pending = null; utterance.complete() }
        })
    }
    override fun prefetch(text: String) { if (ready && !closed) current.prefetch(text) }
    override fun chime() { if (!closed) current.chime() }
    override fun retryPreparation() = current.retryPreparation()
    override fun stop() {
        pending = null
        if (preparing) { preparing = false; generation++; onPrepared = null }
        current.stop()
    }
    override fun close() { closed = true; generation++; pending = null; server.close(); device.close() }
}
