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
import java.util.concurrent.atomic.AtomicBoolean

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
    private var audioTrack: AudioTrack? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private val playbackQueue = LinkedBlockingQueue<ByteArray>(240)
    private val isRunning = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val isSpeakerOn = AtomicBoolean(true)

    @Volatile
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    @SuppressLint("MissingPermission")
    fun start(speakerphone: Boolean) {
        if (!isRunning.compareAndSet(false, true)) return
        isSpeakerOn.set(speakerphone)
        isMuted.set(false)
        playbackQueue.clear()

        configureAudioRouting(speakerphone)
        initAudioRecord()
        initAudioTrack()

        startCaptureLoop()
        startPlaybackLoop()
        PilotStateHub.log(
            LogLevel.AUDIO,
            "AudioEngine",
            "Full-duplex 16kHz/24kHz audio started (speaker=$speakerphone)."
        )
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
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
        if (!playbackQueue.offer(pcm24kBytes)) {
            playbackQueue.poll()
            playbackQueue.offer(pcm24kBytes)
        }
    }

    /**
     * Instant barge-in interruption: clears queued audio chunks and flushes AudioTrack immediately.
     */
    fun interruptPlayback() {
        playbackQueue.clear()
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

        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null

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

        PilotStateHub.log(
            LogLevel.AUDIO,
            "AudioEngine",
            "DSP attached: AEC=${echoCanceler?.enabled == true}, NS=${noiseSuppressor?.enabled == true}"
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

        track.play()
        audioTrack = track
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch {
            val buffer = ByteArray(INPUT_CHUNK_BYTES)
            while (isActive && isRunning.get()) {
                val record = audioRecord ?: break
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0 && !isMuted.get()) {
                    onPcmCaptured(buffer.copyOf(read))
                }
            }
        }
    }

    private fun startPlaybackLoop() {
        playbackJob = scope.launch {
            while (isActive && isRunning.get()) {
                val chunk = runCatching { playbackQueue.take() }.getOrNull() ?: break
                val track = audioTrack ?: continue
                if (isRunning.get() && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                }
            }
        }
    }

    companion object {
        private const val INPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_SAMPLE_RATE = 24000
        // ~100ms at 16kHz 16-bit mono = 3200 bytes
        private const val INPUT_CHUNK_BYTES = 3200
    }
}
