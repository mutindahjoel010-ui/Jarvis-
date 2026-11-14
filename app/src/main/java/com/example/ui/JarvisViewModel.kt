package com.example.ui

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.JarvisAssistantEngine
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.DisplayMessage
import com.example.data.model.InlineAction
import com.example.data.model.SmartDevice
import com.example.data.repository.CalendarPrivilegeRepository
import com.example.data.repository.GmailAuthCheckResult
import com.example.data.repository.GmailPrivilegeRepository
import com.example.data.repository.HomeAssistantConfig
import com.example.data.repository.JarvisFirestoreRepository
import com.example.data.repository.SmartHomePrivilegeRepository
import com.example.voice.TemporaryVoiceService
import com.example.voice.VoiceStatus
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JarvisViewModel(
    private val currentUserId: String,
    private val firestoreRepo: JarvisFirestoreRepository,
    private val calendarRepo: CalendarPrivilegeRepository,
    private val gmailRepo: GmailPrivilegeRepository,
    private val smartHomeRepo: SmartHomePrivilegeRepository,
    private val assistantEngine: JarvisAssistantEngine,
    val voiceService: TemporaryVoiceService
) : ViewModel() {

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _streamingAssistantText = MutableStateFlow("")
    private val _pendingUserPrompt = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _inlineActions = MutableStateFlow<Map<String, InlineAction>>(emptyMap())
    private val _hasCalendarPermission = MutableStateFlow(calendarRepo.hasCalendarPermissions())
    val hasCalendarPermission: StateFlow<Boolean> = _hasCalendarPermission.asStateFlow()

    val isGmailConnected: StateFlow<Boolean> = gmailRepo.isGmailConnected
    val isTemporaryVoiceEnabled: StateFlow<Boolean> = voiceService.isTemporaryVoiceEnabled
    val voiceStatus: StateFlow<VoiceStatus> = voiceService.voiceStatus

    private val _pendingGmailIntent = MutableStateFlow<PendingIntent?>(null)
    val pendingGmailIntent: StateFlow<PendingIntent?> = _pendingGmailIntent.asStateFlow()

    private var activeGenerationJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            assistantEngine.warmUp()
            firestoreRepo.purgeExpiredConversations()
        }
    }

    val conversations: StateFlow<List<Conversation>> = firestoreRepo.observeConversations()
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing conversations", firestoreError ?: error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val storedMessages: StateFlow<List<ChatMessage>> = _activeConversationId
        .flatMapLatest { convId ->
            if (convId == null) {
                flowOf(emptyList())
            } else {
                firestoreRepo.observeMessages(convId).catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing messages", firestoreError ?: error)
                    emit(emptyList())
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val displayMessages: StateFlow<List<DisplayMessage>> = kotlinx.coroutines.flow.combine(
        storedMessages,
        _pendingUserPrompt,
        _streamingAssistantText,
        _isSending,
        _inlineActions
    ) { persisted, pendingPrompt, streaming, sending, actions ->
        val list = persisted.map { msg ->
            DisplayMessage(
                id = msg.id,
                role = msg.role,
                text = msg.text,
                isStreaming = false,
                inlineAction = actions[msg.id]
            )
        }.toMutableList()

        if (!pendingPrompt.isNullOrBlank() && persisted.none { it.role == "user" && it.text == pendingPrompt }) {
            list.add(
                DisplayMessage(
                    id = "pending_user",
                    role = "user",
                    text = pendingPrompt
                )
            )
        }

        if (sending || (streaming.isNotBlank() && persisted.none { it.role == "assistant" && it.text == streaming })) {
            list.add(
                DisplayMessage(
                    id = "streaming_assistant",
                    role = "assistant",
                    text = streaming,
                    isStreaming = sending
                )
            )
        }
        list.toList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    val smartDevices: StateFlow<List<SmartDevice>> = smartHomeRepo.observeRegisteredDevices()
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing smart devices", firestoreError ?: error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val homeAssistantConfig: StateFlow<HomeAssistantConfig> = smartHomeRepo.homeAssistantConfig
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = HomeAssistantConfig()
        )

    fun refreshCalendarPermissionState() {
        _hasCalendarPermission.value = calendarRepo.hasCalendarPermissions()
    }

    fun selectConversation(conversationId: String) {
        activeGenerationJob?.cancel()
        _isSending.value = false
        _streamingAssistantText.value = ""
        _pendingUserPrompt.value = null
        _errorMessage.value = null
        _activeConversationId.value = conversationId
        voiceService.stopAll()
    }

    fun startNewChat() {
        activeGenerationJob?.cancel()
        _isSending.value = false
        _streamingAssistantText.value = ""
        _pendingUserPrompt.value = null
        _errorMessage.value = null
        _activeConversationId.value = null
        // Reset temporary voice service when starting a fresh chat
        voiceService.setTemporaryVoiceEnabled(false)
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                startNewChat()
            }
            firestoreRepo.deleteConversation(conversationId)
        }
    }

    fun sendMessage(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty() || _isSending.value) return

        _errorMessage.value = null
        _pendingUserPrompt.value = prompt
        _streamingAssistantText.value = ""
        _isSending.value = true
        voiceService.stopSpeaking()

        activeGenerationJob = viewModelScope.launch {
            try {
                val historySnapshot = storedMessages.value
                val existingConvId = _activeConversationId.value

                // Persist user prompt concurrently so Gemini starts streaming immediately
                val convIdDeferred = async(Dispatchers.IO) {
                    try {
                        val targetConvId = existingConvId ?: run {
                            val title = prompt.take(48)
                            firestoreRepo.createConversation(title).getOrThrow()
                        }
                        if (existingConvId == null) {
                            _activeConversationId.value = targetConvId
                        }
                        firestoreRepo.addMessage(targetConvId, "user", prompt).getOrThrow()
                        targetConvId
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist user prompt", e)
                        existingConvId
                    }
                }

                val reply = assistantEngine.generateReply(
                    historyMessages = historySnapshot,
                    userPrompt = prompt,
                    onStreamingText = { partial ->
                        _streamingAssistantText.value = partial
                    }
                )

                val convId = convIdDeferred.await()
                if (convId != null) {
                    _pendingUserPrompt.value = null
                    val msgIdResult = firestoreRepo.addMessage(convId, "assistant", reply.text)
                    val msgId = msgIdResult.getOrNull()
                    if (msgId != null && reply.inlineAction != null) {
                        _inlineActions.value = _inlineActions.value + (msgId to reply.inlineAction)
                    }
                    _streamingAssistantText.value = ""
                } else {
                    _streamingAssistantText.value = reply.text
                }

                _isSending.value = false

                if (voiceService.isTemporaryVoiceEnabled.value) {
                    voiceService.speakIfEnabled(reply.text) {
                        // In temporary voice mode, automatically listen again if still enabled
                        if (voiceService.isTemporaryVoiceEnabled.value && voiceService.hasAudioPermission()) {
                            voiceService.startListening { spokenText ->
                                sendMessage(spokenText)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                _isSending.value = false
                _pendingUserPrompt.value = null
                _streamingAssistantText.value = ""
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during Jarvis response for user $currentUserId", e)
                _isSending.value = false
                _pendingUserPrompt.value = null
                _streamingAssistantText.value = ""
                _errorMessage.value = e.localizedMessage ?: "Unable to get a response right now."
            }
        }
    }

    fun stopGenerating() {
        activeGenerationJob?.cancel()
        _isSending.value = false
        _streamingAssistantText.value = ""
        _pendingUserPrompt.value = null
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    // --- Optional & Temporary Speech-to-Voice Controls ---

    fun toggleTemporaryVoiceMode(onNeedMicPermission: () -> Unit) {
        val next = !voiceService.isTemporaryVoiceEnabled.value
        if (next && !voiceService.hasAudioPermission()) {
            onNeedMicPermission()
            return
        }
        voiceService.setTemporaryVoiceEnabled(next)
        if (next && voiceService.isSpeechRecognitionAvailable()) {
            voiceService.startListening { spoken ->
                sendMessage(spoken)
            }
        }
    }

    fun enableTemporaryVoiceAfterPermission() {
        voiceService.setTemporaryVoiceEnabled(true)
        if (voiceService.isSpeechRecognitionAvailable()) {
            voiceService.startListening { spoken ->
                sendMessage(spoken)
            }
        }
    }

    fun endTemporaryVoiceMode() {
        voiceService.setTemporaryVoiceEnabled(false)
    }

    fun startSingleVoiceInput(onNeedMicPermission: () -> Unit) {
        if (!voiceService.hasAudioPermission()) {
            onNeedMicPermission()
            return
        }
        voiceService.startListening { spoken ->
            sendMessage(spoken)
        }
    }

    fun speakMessageOnce(text: String) {
        voiceService.speakText(text)
    }

    fun stopVoiceActivity() {
        voiceService.stopAll()
    }

    // --- Privileges & Connections ---

    fun requestGmailAuthorization() {
        viewModelScope.launch {
            when (val res = gmailRepo.requestAuthorization()) {
                is GmailAuthCheckResult.Authorized -> {
                    _errorMessage.value = null
                }
                is GmailAuthCheckResult.NeedsResolution -> {
                    _pendingGmailIntent.value = res.pendingIntent
                }
                is GmailAuthCheckResult.Unavailable -> {
                    _errorMessage.value = res.reason
                }
            }
        }
    }

    fun consumePendingGmailIntent() {
        _pendingGmailIntent.value = null
    }

    fun handleGmailActivityResult(data: Intent?) {
        val ok = gmailRepo.handleAuthorizationActivityResult(data)
        if (!ok) {
            _errorMessage.value = "Gmail connection was not completed."
        }
    }

    fun addSmartDevice(name: String, room: String, type: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            smartHomeRepo.addDevice(name = name, room = room, deviceType = type)
        }
    }

    fun toggleSmartDevice(device: SmartDevice) {
        viewModelScope.launch {
            smartHomeRepo.controlDevice(device.id, turnOn = !device.isOn)
        }
    }

    fun deleteSmartDevice(deviceId: String) {
        viewModelScope.launch {
            smartHomeRepo.deleteDevice(deviceId)
        }
    }

    fun saveHomeAssistant(serverUrl: String, accessToken: String) {
        viewModelScope.launch {
            smartHomeRepo.saveHomeAssistantConfig(serverUrl, accessToken)
        }
    }

    fun disconnectHomeAssistant() {
        viewModelScope.launch {
            smartHomeRepo.clearHomeAssistantConfig()
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceService.destroy()
        gmailRepo.clearSession()
    }

    private companion object {
        const val TAG = "JarvisViewModel"
    }
}
