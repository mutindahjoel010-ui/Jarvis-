package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

sealed interface VoiceStatus {
    data object Idle : VoiceStatus
    data class Listening(val partialText: String = "") : VoiceStatus
    data object Speaking : VoiceStatus
    data class Error(val message: String) : VoiceStatus
}

/**
 * Optional and temporary Speech-to-Text + Text-to-Speech service.
 * State is strictly session-only and never persisted across restarts.
 */
class TemporaryVoiceService(private val context: Context) {

    private val _isTemporaryVoiceEnabled = MutableStateFlow(false)
    val isTemporaryVoiceEnabled: StateFlow<Boolean> = _isTemporaryVoiceEnabled.asStateFlow()

    private val _voiceStatus = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    val voiceStatus: StateFlow<VoiceStatus> = _voiceStatus.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var onFinalTranscript: ((String) -> Unit)? = null
    private var onUtteranceDoneCallback: (() -> Unit)? = null

    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isSpeechRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun setTemporaryVoiceEnabled(enabled: Boolean) {
        _isTemporaryVoiceEnabled.value = enabled
        if (!enabled) {
            stopAll()
        } else {
            ensureTtsInitialized()
        }
    }

    fun startListening(onTranscript: (String) -> Unit) {
        if (!hasAudioPermission()) {
            _voiceStatus.value = VoiceStatus.Error("Microphone permission is needed to listen.")
            return
        }
        if (!isSpeechRecognitionAvailable()) {
            _voiceStatus.value = VoiceStatus.Error("Speech recognition is not available on this device.")
            return
        }

        stopSpeaking()
        onFinalTranscript = onTranscript

        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _voiceStatus.value = VoiceStatus.Listening("")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Tap the mic to try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                        else -> "Voice input paused."
                    }
                    Log.w(TAG, "SpeechRecognizer error code: $error")
                    _voiceStatus.value = VoiceStatus.Error(message)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.trim().orEmpty()
                    _voiceStatus.value = VoiceStatus.Idle
                    if (spoken.isNotBlank()) {
                        onFinalTranscript?.invoke(spoken)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        _voiceStatus.value = VoiceStatus.Listening(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            _voiceStatus.value = VoiceStatus.Listening("")
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            _voiceStatus.value = VoiceStatus.Error("Could not start voice input.")
        }
    }

    fun speakIfEnabled(rawText: String, onFinished: () -> Unit = {}) {
        if (!_isTemporaryVoiceEnabled.value) return
        speakText(rawText, onFinished)
    }

    fun speakText(rawText: String, onFinished: () -> Unit = {}) {
        val cleanText = cleanForSpeech(rawText)
        if (cleanText.isBlank()) {
            onFinished()
            return
        }
        onUtteranceDoneCallback = onFinished
        ensureTtsInitialized {
            val tts = textToSpeech
            if (tts == null || !isTtsReady) {
                _voiceStatus.value = VoiceStatus.Idle
                onFinished()
                return@ensureTtsInitialized
            }
            val utteranceId = UUID.randomUUID().toString()
            _voiceStatus.value = VoiceStatus.Speaking
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        if (_voiceStatus.value is VoiceStatus.Listening) {
            _voiceStatus.value = VoiceStatus.Idle
        }
    }

    fun stopSpeaking() {
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {
        }
        if (_voiceStatus.value is VoiceStatus.Speaking) {
            _voiceStatus.value = VoiceStatus.Idle
        }
    }

    fun stopAll() {
        stopListening()
        stopSpeaking()
        _voiceStatus.value = VoiceStatus.Idle
    }

    fun clearError() {
        if (_voiceStatus.value is VoiceStatus.Error) {
            _voiceStatus.value = VoiceStatus.Idle
        }
    }

    fun destroy() {
        stopAll()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
        try {
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }
        textToSpeech = null
        isTtsReady = false
    }

    private fun ensureTtsInitialized(onReady: () -> Unit = {}) {
        if (textToSpeech != null && isTtsReady) {
            onReady()
            return
        }
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _voiceStatus.value = VoiceStatus.Speaking
                    }

                    override fun onDone(utteranceId: String?) {
                        _voiceStatus.value = VoiceStatus.Idle
                        onUtteranceDoneCallback?.invoke()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _voiceStatus.value = VoiceStatus.Idle
                    }
                })
                isTtsReady = true
                onReady()
            } else {
                isTtsReady = false
                Log.w(TAG, "TextToSpeech init failed with status $status")
            }
        }
    }

    private fun cleanForSpeech(text: String): String {
        return text
            .replace(Regex("[*_`#>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private companion object {
        const val TAG = "TemporaryVoiceService"
    }
}
