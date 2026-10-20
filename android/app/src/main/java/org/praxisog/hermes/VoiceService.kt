package org.praxisog.hermes

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import kotlinx.coroutines.*

/** Starts only from an explicit foreground UI action; never restarts a microphone after death. */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = BellpullRuntime.get(applicationContext)
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var media: MediaSession? = null
    private var started = false
    private var ownsAudio = false
    private var audioCycle = 0L
    private var acquiring = false
    private var oldMode = AudioManager.MODE_NORMAL
    private var requestedRoute: AudioDeviceInfo? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRenewal: Job? = null
    private var routePending = false
    private var routeTimeout: Job? = null
    private var telecomCall: TelecomVoiceCall? = null
    private var usingTelecom = false
    @get:androidx.annotation.RequiresApi(31)
    private val routeChanged by lazy {
        AudioManager.OnCommunicationDeviceChangedListener {
            if (Build.VERSION.SDK_INT >= 31 && audio.communicationDevice?.id == requestedRoute?.id) {
                routePending = false; routeTimeout?.cancel(); routeTimeout = null
            }
            if (Build.VERSION.SDK_INT >= 31 && started && ownsAudio && !acquiring && runtime.voice?.state != VoiceState.PAUSED && requestedRoute != null &&
                !routePending && audio.communicationDevice?.id != requestedRoute?.id)
                pauseAudio("route_changed", "The communication route changed. Check your route and resume voice.")
        }
    }
    @get:androidx.annotation.RequiresApi(31)
    private val modeChanged by lazy {
        AudioManager.OnModeChangedListener {
            if (started && ownsAudio && !acquiring && runtime.voice?.state != VoiceState.PAUSED && audio.mode != AudioManager.MODE_IN_COMMUNICATION)
                pauseAudio("mode_changed", "Another app or call changed the audio mode. Resume voice when ready.")
        }
    }
    private val routes = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // Android sends the initial device inventory through this same callback.
            // Additions never imply loss of the selected communication route.
            diagnose("devices_added:${addedDevices.size}")
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (started && ownsAudio && removedDevices.any { it.id == requestedRoute?.id }) pauseAudio("device_removed", "Audio device disconnected. Check your route and resume voice.")
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (started && ownsAudio) pauseAudio("becoming_noisy", "Headphones disconnected. Resume after choosing an audio route.") }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); audio = getSystemService(AudioManager::class.java)
        audio.registerAudioDeviceCallback(routes, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 31) {
            audio.addOnCommunicationDeviceChangedListener(mainExecutor, routeChanged)
            audio.addOnModeChangedListener(mainExecutor, modeChanged)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { runtime.voice?.close(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == PAUSE) { if (started) runtime.voice?.pause() else stopSelf(); return START_NOT_STICKY }
        if (intent?.action == RESUME) {
            if (!started) stopSelf() else if (runtime.voice?.state == VoiceState.PAUSED) runtime.voice?.resume()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val store = runtime.store
        val id = runtime.voiceRequestedSession
        val profile = runtime.profileId
        if (store == null || id == null || store.openSessionId != id || profile == null ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        val config = VoiceSettings(this, profile).read()
        usingTelecom = runtime.voiceRequestedMode == VoiceMode.CALL &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELECOM)
        if (runtime.voiceDictationToDraft && !runtime.voiceButtonHeld) { stopSelf(); return START_NOT_STICKY }
        runtime.voiceProblem(config)?.let { problem ->
            runtime.voiceStartError = problem
            stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice session", NotificationManager.IMPORTANCE_LOW))
        fun action(name: String, request: Int) = PendingIntent.getService(this, request, Intent(this, VoiceService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 51, Intent(this, MainActivity::class.java).setAction(OPEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun notification(text: String, paused: Boolean = false): Notification {
            val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if ((runtime.voice?.mode ?: runtime.voiceRequestedMode) == VoiceMode.CALL) "Bellpull call mode" else "Bellpull voice")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume voice" else "Pause microphone", action(if (paused) RESUME else PAUSE, 52)).build())
            if (usingTelecom && Build.VERSION.SDK_INT >= 31) builder.setStyle(Notification.CallStyle.forOngoingCall(
                Person.Builder().setName("Bellpull").setImportant(true).build(), action(STOP, 53)))
            else builder.addAction(Notification.Action.Builder(null, "End voice", action(STOP, 53)).build())
            return builder.build()
        }
        try {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                (if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (usingTelecom) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0)
            startForeground(2405, notification("Starting microphone and speech"), serviceTypes)
            oldMode = audio.mode
            if (!usingTelecom) {
                check(oldMode == AudioManager.MODE_NORMAL)
                check(acquireAudio())
            }
            val savedVoice = VoiceSettings(this, profile)
            val voice = VoiceSession(id, profile, scope, StoreVoiceConversation(store, id), config,
                runtime.voiceOutputs.output(config), AndroidVoiceCapture(scope), transcriber = {
                    if (config.transcribeViaServer) {
                        val connection = runtime.speechConnection()
                        StreamingServerTranscriber(connection,
                            ServerVoiceTranscriber(ProfileSpeechTransport(connection),
                                allowLocalFallback = config.allowLocalTranscriptionFallback) {
                                runtime.speechLab.fallbackTranscriber()
                            }, local = if (config.allowLocalTranscriptionFallback) ({ runtime.speechLab.fallbackTranscriber() }) else null)
                    }
                    else if (config.recognition == VoiceRecognition.LOCAL) runtime.speechLab.voiceTranscriber()
                    else HttpVoiceTranscriber(config)
                }, onClosed = { stopSelf() }, beforeResume = {
                    acquireAudio()
                }, onExplicitRetry = { runtime.speechLab.runtime.allowRetry() },
                onPlayback = { text, interrupted -> savedVoice.savePlayback(id, text, interrupted) },
                priorInterruptedPlayback = savedVoice.playback(id)?.takeIf { savedVoice.interrupted(id) },
                onTranscript = { savedVoice.saveTranscript(id, it); if (config.transcribeViaServer || config.recognition == VoiceRecognition.LOCAL) runtime.speechLab.recognitionFinished() },
                initialTranscript = savedVoice.pending(id),
                onSubmission = savedVoice::saveRequest, priorRequestId = savedVoice.pendingRequest(id),
                onDictation = { text ->
                    check(runtime.profileId == profile && store.openSessionId == id)
                    store.updateDraft(insertVoiceDictation(store.draft, text))
                })
            runtime.voice = voice; runtime.voiceStartError = null
            started = true
            media = MediaSession(this, "Bellpull voice").also { session ->
                session.setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { if (voice.state == VoiceState.PAUSED) voice.resume() }
                    override fun onPause() = voice.pause()
                    override fun onStop() = voice.close()
                })
                session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f).build()); session.isActive = true
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                voice.showNotice("Android is hiding the voice notification. Return here to end voice, or use Android's active-app controls.")
            if (usingTelecom) {
                telecomCall = TelecomVoiceCall(this,
                    onReady = { acquireWakeLock(); voice.start(VoiceMode.CALL) },
                    onActive = { acquireWakeLock(); if (voice.state == VoiceState.PAUSED) voice.resume() },
                    onHeld = { voice.pause("Call on hold. Resume from Bellpull or your call controls.") },
                    onMuted = voice::updateMicrophoneMute,
                    onEnded = { voice.close() },
                    onError = { message ->
                        if (voice.state == VoiceState.STARTING) {
                            runtime.voiceStartError = message; voice.close()
                        } else voice.pause(message)
                    }).also { it.start() }
            } else {
                voice.start(runtime.voiceRequestedMode)
                if (runtime.voiceDictationToDraft && runtime.voiceButtonHeld) voice.beginPushToTalk(toDraft = true)
            }
            scope.launch {
                androidx.compose.runtime.snapshotFlow { voice.state to voice.mode }.collect { (state, _) ->
                    val paused = state == VoiceState.PAUSED
                    if (paused) {
                        if (usingTelecom) { telecomCall?.setHeld(true); releaseWakeLock() }
                        else releaseAudio()
                    }
                    media?.setPlaybackState(PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                        .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, 0, if (paused) 0f else 1f).build())
                    manager.notify(2405, notification(state.label, paused))
                }
            }
        } catch (_: Exception) {
            runtime.voiceStartError = "Voice could not start. Check microphone permission and try again with Bellpull open."
            stopSelf()
        }
        return START_NOT_STICKY
    }
    /** Never reclaim a call's mode or restart capture without explicit Resume. */
    private fun acquireAudio(): Boolean {
        if (usingTelecom) {
            if (telecomCall?.active == true) { acquireWakeLock(); return true }
            telecomCall?.setHeld(false)
            return false
        }
        if (ownsAudio) return audio.mode == AudioManager.MODE_IN_COMMUNICATION
        if (audio.mode != AudioManager.MODE_NORMAL) return false
        val cycle = ++audioCycle
        acquiring = true
        return try {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ change ->
                    if (cycle != audioCycle || !ownsAudio || !started) return@setOnAudioFocusChangeListener
                    diagnose("focus:$change")
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) runtime.voice?.recoverAudio()
                            else pauseAudio("focus_gain_mode", "A call changed the audio mode. Resume when ready.")
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runtime.voice?.suspendForAudio()
                        AudioManager.AUDIOFOCUS_LOSS -> pauseAudio("focus_loss", "Another app needs audio. Resume voice when ready.")
                    }
                }, Handler(Looper.getMainLooper())).build()
            check(audio.requestAudioFocus(checkNotNull(focus)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            ownsAudio = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            check(selectRoute())
            // The user-started foreground session must continue capturing and
            // receiving speech while the screen is off, even between phrases.
            acquireWakeLock()
            true
        } catch (_: Exception) { releaseAudio(); false }
        finally { acquiring = false }
    }
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bellpull:voice").also {
                    it.setReferenceCounted(false)
                    it.acquire(10 * 60_000L)
                }
            wakeRenewal = scope.launch {
                while (isActive) {
                    delay(5 * 60_000L)
                    if (!ownsAudio && telecomCall?.active != true) break
                    wakeLock?.acquire(10 * 60_000L)
                }
            }
    }
    private fun releaseWakeLock() {
        wakeRenewal?.cancel(); wakeRenewal = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }
    private fun releaseAudio() {
        // Fence the old focus listener before abandoning focus or changing our own route.
        audioCycle++
        val owned = ownsAudio
        ownsAudio = false; requestedRoute = null
        routePending = false; routeTimeout?.cancel(); routeTimeout = null
        releaseWakeLock()
        focus?.let(audio::abandonAudioFocusRequest); focus = null
        if (owned && Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        if (owned && audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = oldMode
    }
    private fun pauseAudio(event: String, message: String) { diagnose(event); runtime.voice?.pause(message) }
    private fun diagnose(event: String) {
        val actual = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice else null
        android.util.Log.i("BellpullVoiceAudio", "time=${SystemClock.elapsedRealtime()} event=$event cycle=$audioCycle " +
            "selected=${requestedRoute?.type}:${requestedRoute?.id} actual=${actual?.type}:${actual?.id} " +
            "mode=${audio.mode} owned=$ownsAudio ${runtime.voice?.playbackDiagnostics().orEmpty()}")
    }
    private fun selectRoute(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val device = devices.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET) }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (device == null) return false
            requestedRoute = device
            routePending = audio.communicationDevice?.id != device.id
            if (!audio.setCommunicationDevice(device)) return false
            // Bluetooth route selection is asynchronous. An intermediate device
            // callback is not loss of a route that has not connected yet.
            routeTimeout?.cancel()
            if (routePending) routeTimeout = scope.launch {
                delay(30_000)
                routePending = false
                if (started && ownsAudio && audio.communicationDevice?.id != requestedRoute?.id)
                    pauseAudio("route_timeout", "The call audio route did not connect. Check Bluetooth and resume voice.")
            }
        }
        return true
    }
    override fun onDestroy() {
        started = false
        telecomCall?.close(); telecomCall = null
        runtime.voice?.close(); runtime.voice = null; runtime.voiceRequestedSession = null
        runtime.voiceDictationToDraft = false; runtime.voiceButtonHeld = false
        media?.release(); audio.unregisterAudioDeviceCallback(routes); unregisterReceiver(noisy)
        if (Build.VERSION.SDK_INT >= 31) {
            audio.removeOnCommunicationDeviceChangedListener(routeChanged)
            audio.removeOnModeChangedListener(modeChanged)
        }
        releaseAudio()
        scope.cancel(); super.onDestroy()
    }
    companion object {
        const val STOP = "org.praxisog.hermes.STOP_VOICE"
        const val RESUME = "org.praxisog.hermes.RESUME_VOICE"
        const val PAUSE = "org.praxisog.hermes.PAUSE_VOICE"
        const val OPEN = "org.praxisog.hermes.OPEN_VOICE"
        private const val CHANNEL = "bellpull_voice"
    }
}
