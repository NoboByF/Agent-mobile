package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LiveAudioEngine(
    context: Context,
    private val onPcmCaptured: (ByteArray) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var audioTrack: AudioTrack? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private val playbackQueue = LinkedBlockingQueue<ByteArray>(240)
    private val isRunning = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val isSpeakerOn = AtomicBoolean(true)

    @Volatile
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    @Volatile
    private var lastMicUiPushMs = 0L

    @Volatile
    private var lastAiUiPushMs = 0L

    @Volatile
    private var lastAiPlaybackActiveMs = 0L

    @Volatile
    private var speechHangoverFrames = 0

    @SuppressLint("MissingPermission")
    fun start(speakerphone: Boolean) {
        if (!isRunning.compareAndSet(false, true)) return
        isSpeakerOn.set(speakerphone)
        isMuted.set(false)
        speechHangoverFrames = 0
        playbackQueue.clear()

        configureAudioRouting(speakerphone)
        initAudioRecord()
        initAudioTrack()

        startCaptureLoop()
        startPlaybackLoop()
        PilotStateHub.log(
            LogLevel.AUDIO,
            "AudioEngine",
            "Full-duplex 16kHz/24kHz audio started (speaker=$speakerphone, 50ms DSP frames)."
        )
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        if (muted) {
            PilotStateHub.updateAudioLevels(mic = 0f)
        }
        PilotStateHub.log(LogLevel.AUDIO, "AudioEngine", if (muted) "Mic muted." else "Mic unmuted.")
    }

    fun setSpeakerphone(enabled: Boolean) {
        isSpeakerOn.set(enabled)
        if (isRunning.get()) {
            applySpeakerRouting(enabled)
        }
    }

    fun enqueueAudioOutput(pcm24kBytes: ByteArray) {
        if (!isRunning.get() || pcm24kBytes.isEmpty()) return
        lastAiPlaybackActiveMs = System.currentTimeMillis()
        if (!playbackQueue.offer(pcm24kBytes)) {
            playbackQueue.poll()
            playbackQueue.offer(pcm24kBytes)
        }
    }

    fun interruptPlayback() {
        playbackQueue.clear()
        lastAiPlaybackActiveMs = 0L
        PilotStateHub.updateAudioLevels(ai = 0f)
        val track = audioTrack ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
                track.play()
            }
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        captureJob?.cancel()
        playbackJob?.cancel()
        captureJob = null
        playbackJob = null
        playbackQueue.clear()
        lastAiPlaybackActiveMs = 0L
        PilotStateHub.updateAudioLevels(mic = 0f, ai = 0f)

        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null

        restoreAudioRouting()
        PilotStateHub.log(LogLevel.AUDIO, "AudioEngine", "Audio engine stopped & released.")
    }

    private fun configureAudioRouting(speakerphone: Boolean) {
        runCatching {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            applySpeakerRouting(speakerphone)
        }
    }

    @Suppress("DEPRECATION")
    private fun applySpeakerRouting(enabled: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                    }
                } else {
                    val earpieceOrBt = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    if (earpieceOrBt != null) {
                        audioManager.setCommunicationDevice(earpieceOrBt)
                    } else {
                        audioManager.clearCommunicationDevice()
                    }
                }
            } else {
                audioManager.isSpeakerphoneOn = enabled
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioRouting() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = previousAudioMode
        }
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(INPUT_CHUNK_BYTES * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            PilotStateHub.log(LogLevel.ERROR, "AudioEngine", "AudioRecord failed to initialize.")
            record.release()
            return
        }

        val sessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = runCatching {
                AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }

        PilotStateHub.log(
            LogLevel.AUDIO,
            "AudioEngine",
            "DSP attached: AEC=${echoCanceler?.enabled == true}, NS=${noiseSuppressor?.enabled == true}, AGC=${gainControl?.enabled == true}"
        )

        record.startRecording()
        audioRecord = record
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(OUTPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            PilotStateHub.log(LogLevel.ERROR, "AudioEngine", "AudioTrack failed to initialize.")
            runCatching { track.release() }
            return
        }

        track.play()
        audioTrack = track
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch {
            val buffer = ByteArray(INPUT_CHUNK_BYTES)
            while (isActive && isRunning.get()) {
                val record = audioRecord ?: break
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    if (!isMuted.get()) {
                        val copy = buffer.copyOf(read)
                        val now = System.currentTimeMillis()
                        val rawRms = computeRawRms(copy, read)
                        val aiSpeaking = (now - lastAiPlaybackActiveMs) < AI_ECHO_TAIL_MS && isSpeakerOn.get()

                        val processed = applyVoiceClarityDsp(copy, read, rawRms, aiSpeaking)
                        onPcmCaptured(processed)

                        if (now - lastMicUiPushMs >= 70L) {
                            lastMicUiPushMs = now
                            val displayLevel = if (aiSpeaking && rawRms < ECHO_DUCK_RMS) {
                                0.04f
                            } else {
                                (rawRms / 8500.0).toFloat().coerceIn(0f, 1f)
                            }
                            PilotStateHub.updateAudioLevels(mic = displayLevel)
                        }
                    } else {
                        PilotStateHub.updateAudioLevels(mic = 0f)
                    }
                } else if (read < 0) {
                    PilotStateHub.log(LogLevel.ERROR, "AudioEngine", "AudioRecord read error code=$read")
                    break
                }
            }
        }
    }

    /**
     * Prevents two biggest real-world causes of wrong-language / garbled Bengali transcription:
     * 1) Speakerphone echo bleed while AI is speaking (softly ducked unless user speaks loudly to barge in).
     * 2) Low ambient fan/room hiss hallucinating random foreign words (soft noise gate with smooth hangover).
     * 3) Gentle clarity gain on real human voice frames so soft Bengali consonants stay crisp.
     */
    private fun applyVoiceClarityDsp(
        pcmBytes: ByteArray,
        length: Int,
        rms: Double,
        aiSpeakingOnSpeaker: Boolean
    ): ByteArray {
        val gain: Float = when {
            aiSpeakingOnSpeaker && rms < ECHO_DUCK_RMS -> {
                // Suppress residual speaker echo so Gemini doesn't mishear its own voice as another language
                speechHangoverFrames = 0
                0.08f
            }
            rms >= SPEECH_OPEN_RMS -> {
                // Real human speech detected — keep gate open for 8 frames (400ms) for trailing syllables
                speechHangoverFrames = 8
                // Boost quiet voices gently without clipping loud voices
                if (rms < 3200.0) 1.32f else 1.05f
            }
            speechHangoverFrames > 0 -> {
                speechHangoverFrames--
                1.08f
            }
            rms < NOISE_FLOOR_RMS -> {
                // Softly attenuate room fan/hiss noise so server VAD gets clean silence between words
                0.15f
            }
            else -> 0.85f
        }

        if (gain == 1.0f) return pcmBytes
        var i = 0
        while (i + 1 < length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val scaled = (sample * gain).roundToInt().coerceIn(-32760, 32760)
            pcmBytes[i] = (scaled and 0xFF).toByte()
            pcmBytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        return pcmBytes
    }

    private fun startPlaybackLoop() {
        playbackJob = scope.launch {
            while (isActive && isRunning.get()) {
                val chunk = runCatching {
                    playbackQueue.poll(200L, TimeUnit.MILLISECONDS)
                }.getOrNull() ?: continue
                val track = audioTrack ?: continue
                if (isRunning.get() && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val now = System.currentTimeMillis()
                    lastAiPlaybackActiveMs = now
                    if (now - lastAiUiPushMs >= 70L) {
                        lastAiUiPushMs = now
                        PilotStateHub.updateAudioLevels(ai = (computeRawRms(chunk, chunk.size) / 9500.0).toFloat().coerceIn(0f, 1f))
                    }
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    lastAiPlaybackActiveMs = System.currentTimeMillis()
                    if (playbackQueue.isEmpty()) {
                        PilotStateHub.updateAudioLevels(ai = 0f)
                    }
                }
            }
        }
    }

    private fun computeRawRms(pcmBytes: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSq += sample * sample
            samples++
            i += 4
        }
        return if (samples == 0) 0.0 else sqrt(sumSq / samples)
    }

    companion object {
        private const val INPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_SAMPLE_RATE = 24000
        private const val INPUT_CHUNK_BYTES = 1600 // 50ms crisp PCM frames at 16kHz 16-bit mono
        private const val NOISE_FLOOR_RMS = 230.0
        private const val SPEECH_OPEN_RMS = 420.0
        private const val ECHO_DUCK_RMS = 2600.0
        private const val AI_ECHO_TAIL_MS = 260L
    }
}
