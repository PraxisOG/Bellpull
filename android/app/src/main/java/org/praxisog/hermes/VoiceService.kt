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
    private var oldMode = AudioManager.MODE_NORMAL
    private var requestedRoute: AudioDeviceInfo? = null
    @get:androidx.annotation.RequiresApi(31)
    private val routeChanged by lazy {
        AudioManager.OnCommunicationDeviceChangedListener {
            if (started && runtime.voice?.state != VoiceState.PAUSED && requestedRoute != null &&
                audio.communicationDevice?.id != requestedRoute?.id)
                runtime.voice?.pause("The communication route changed. Check your route and resume voice.")
        }
    }
    @get:androidx.annotation.RequiresApi(31)
    private val modeChanged by lazy {
        AudioManager.OnModeChangedListener {
            if (started && runtime.voice?.state != VoiceState.PAUSED && audio.mode != AudioManager.MODE_IN_COMMUNICATION)
                runtime.voice?.pause("Another app or call changed the audio mode. Resume voice when ready.")
        }
    }
    private val routes = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (started) runtime.voice?.pause("Audio devices changed. Check your route and resume voice.")
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (started && removedDevices.any { it.id == requestedRoute?.id }) runtime.voice?.pause("Audio device disconnected. Check your route and resume voice.")
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { runtime.voice?.pause("Headphones disconnected. Resume after choosing an audio route.") }
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
        fun notification(text: String, paused: Boolean = false) = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bellpull voice").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume voice" else "Pause microphone", action(if (paused) RESUME else PAUSE, 52)).build())
            .addAction(Notification.Action.Builder(null, "End voice", action(STOP, 53)).build()).build()
        try {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(2405, notification("Starting microphone and offline speech"), serviceTypes)
            oldMode = audio.mode
            check(oldMode == AudioManager.MODE_NORMAL)
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { change ->
                    if (change != AudioManager.AUDIOFOCUS_GAIN) runtime.voice?.pause("Another app or call needs audio. Resume voice when ready.")
                }.build()
            check(acquireAudio())
            val savedVoice = VoiceSettings(this, profile)
            val voice = VoiceSession(id, profile, scope, StoreVoiceConversation(store, id), config,
                runtime.voiceOutputs.output(config), AndroidVoiceCapture(scope), transcriber = {
                    if (config.recognition == VoiceRecognition.LOCAL) runtime.speechLab.voiceTranscriber()
                    else HttpVoiceTranscriber(config)
                }, onClosed = { stopSelf() }, beforeResume = {
                    acquireAudio()
                }, onPlayback = { text, interrupted -> savedVoice.savePlayback(id, text, interrupted) },
                priorInterruptedPlayback = savedVoice.playback(id)?.takeIf { savedVoice.interrupted(id) },
                onTranscript = { savedVoice.saveTranscript(id, it); if (config.recognition == VoiceRecognition.LOCAL) runtime.speechLab.recognitionFinished() },
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
            voice.start(runtime.voiceRequestedMode)
            if (runtime.voiceDictationToDraft && runtime.voiceButtonHeld) voice.beginPushToTalk(toDraft = true)
            scope.launch {
                androidx.compose.runtime.snapshotFlow { voice.state }.collect { state ->
                    val paused = state == VoiceState.PAUSED
                    if (paused) releaseAudio()
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
        if (audio.mode != AudioManager.MODE_NORMAL && !(ownsAudio && audio.mode == AudioManager.MODE_IN_COMMUNICATION)) return false
        return runCatching {
            check(focus?.let { audio.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED } == true)
            ownsAudio = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            check(selectRoute())
            true
        }.getOrElse { releaseAudio(); false }
    }
    private fun releaseAudio() {
        focus?.let(audio::abandonAudioFocusRequest)
        requestedRoute = null
        if (ownsAudio && Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        if (ownsAudio && audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = oldMode
        ownsAudio = false
    }
    private fun selectRoute(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val device = devices.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET) }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (device == null || !audio.setCommunicationDevice(device)) return false
            requestedRoute = device
        }
        return true
    }
    override fun onDestroy() {
        started = false
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
