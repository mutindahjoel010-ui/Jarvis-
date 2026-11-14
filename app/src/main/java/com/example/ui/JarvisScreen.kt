package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.InlineAction
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

@Composable
fun JarvisScreen(
    viewModel: JarvisViewModel,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val credentialManager = remember { CredentialManager.create(context) }

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val messages by viewModel.displayMessages.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isTemporaryVoiceEnabled by viewModel.isTemporaryVoiceEnabled.collectAsStateWithLifecycle()
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    val hasCalendarPermission by viewModel.hasCalendarPermission.collectAsStateWithLifecycle()
    val isGmailConnected by viewModel.isGmailConnected.collectAsStateWithLifecycle()
    val smartDevices by viewModel.smartDevices.collectAsStateWithLifecycle()
    val haConfig by viewModel.homeAssistantConfig.collectAsStateWithLifecycle()
    val pendingGmailIntent by viewModel.pendingGmailIntent.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var showSmartHomeSheet by remember { mutableStateOf(false) }
    var showInstallSheet by remember { mutableStateOf(false) }
    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val currentUser = Firebase.auth.currentUser
    val firstName = remember(currentUser?.displayName) {
        currentUser?.displayName?.trim()?.split(" ")?.firstOrNull().orEmpty()
    }
    val userEmail = currentUser?.email.orEmpty()
    val userInitial = remember(firstName, userEmail) {
        (firstName.firstOrNull() ?: userEmail.firstOrNull() ?: 'J').uppercaseChar().toString()
    }

    // Runtime permission launchers
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingMicAction?.invoke()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Microphone access is needed for voice input.")
            }
        }
        pendingMicAction = null
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshCalendarPermissionState()
    }

    val gmailAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.handleGmailActivityResult(result.data)
    }

    LaunchedEffect(pendingGmailIntent) {
        val pending = pendingGmailIntent
        if (pending != null) {
            viewModel.consumePendingGmailIntent()
            gmailAuthLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    LaunchedEffect(errorMessage) {
        val msg = errorMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    BackHandler(enabled = drawerState.isOpen || showSmartHomeSheet || showInstallSheet) {
        when {
            showInstallSheet -> showInstallSheet = false
            showSmartHomeSheet -> showSmartHomeSheet = false
            drawerState.isOpen -> scope.launch { drawerState.close() }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            JarvisNavigationDrawerContent(
                userEmail = userEmail,
                conversations = conversations,
                activeConversationId = activeConversationId,
                hasCalendarPermission = hasCalendarPermission,
                isGmailConnected = isGmailConnected,
                smartDeviceCount = smartDevices.size,
                onNewChat = {
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onSelectConversation = { convId ->
                    viewModel.selectConversation(convId)
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { convId ->
                    viewModel.deleteConversation(convId)
                },
                onRequestCalendarPermission = {
                    calendarPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR
                        )
                    )
                },
                onConnectGmail = {
                    viewModel.requestGmailAuthorization()
                },
                onOpenSmartHomeSheet = {
                    scope.launch { drawerState.close() }
                    showSmartHomeSheet = true
                },
                onOpenInstallSheet = {
                    scope.launch { drawerState.close() }
                    showInstallSheet = true
                },
                onSignOut = {
                    scope.launch { drawerState.close() }
                    signOut(credentialManager, scope, onSignedOut)
                }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                JarvisTopBar(
                    userInitial = userInitial,
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onNewChat = { viewModel.startNewChat() }
                )
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                    contentAlignment = Alignment.Center
                ) {
                    JarvisComposerBar(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        isSending = isSending,
                        isTemporaryVoiceEnabled = isTemporaryVoiceEnabled,
                        voiceStatus = voiceStatus,
                        onSend = {
                            val prompt = inputText
                            inputText = ""
                            viewModel.sendMessage(prompt)
                        },
                        onStopGenerating = { viewModel.stopGenerating() },
                        onMicClick = {
                            viewModel.startSingleVoiceInput {
                                pendingMicAction = {
                                    viewModel.startSingleVoiceInput {}
                                }
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onToggleTemporaryVoice = {
                            viewModel.toggleTemporaryVoiceMode {
                                pendingMicAction = {
                                    viewModel.enableTemporaryVoiceAfterPermission()
                                }
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onEndTemporaryVoice = { viewModel.endTemporaryVoiceMode() },
                        modifier = Modifier.widthIn(max = 760.dp)
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    ),
                contentAlignment = Alignment.TopCenter
            ) {
                if (messages.isEmpty()) {
                    // Clean Gemini-like open greeting — zero system capability info
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 680.dp)
                            .padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            JarvisSparkleEmblem(
                                size = 44.dp,
                                isAnimating = false
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            val greetingText = if (firstName.isNotBlank()) {
                                stringResource(R.string.jarvis_greeting_named, firstName)
                            } else {
                                stringResource(R.string.jarvis_greeting_default)
                            }
                            Text(
                                text = greetingText,
                                style = MaterialTheme.typography.displayMedium.copy(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.jarvis_subtitle),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        modifier = Modifier
                            .widthIn(max = 760.dp)
                            .fillMaxSize()
                            .testTag("chat_message_list")
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubbleRow(
                                message = msg,
                                onCopy = { text ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis", text))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                },
                                onReadAloud = { text ->
                                    viewModel.speakMessageOnce(text)
                                },
                                onInlineAction = { action ->
                                    when (action) {
                                        InlineAction.RequestCalendarPermission -> {
                                            calendarPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.READ_CALENDAR,
                                                    Manifest.permission.WRITE_CALENDAR
                                                )
                                            )
                                        }
                                        InlineAction.ConnectGmail -> {
                                            viewModel.requestGmailAuthorization()
                                        }
                                        InlineAction.OpenSmartHomeSetup -> {
                                            showSmartHomeSheet = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSmartHomeSheet) {
        SmartHomeSheet(
            devices = smartDevices,
            homeAssistantConfig = haConfig,
            onDismiss = { showSmartHomeSheet = false },
            onAddDevice = { name, room, type ->
                viewModel.addSmartDevice(name, room, type)
            },
            onToggleDevice = { device ->
                viewModel.toggleSmartDevice(device)
            },
            onDeleteDevice = { deviceId ->
                viewModel.deleteSmartDevice(deviceId)
            },
            onSaveHomeAssistant = { url, token ->
                viewModel.saveHomeAssistant(url, token)
            },
            onDisconnectHomeAssistant = {
                viewModel.disconnectHomeAssistant()
            }
        )
    }

    if (showInstallSheet) {
        InstallOnPhoneSheet(
            onDismiss = { showInstallSheet = false }
        )
    }
}

@Composable
private fun JarvisTopBar(
    userInitial: String,
    onOpenMenu: () -> Unit,
    onNewChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenMenu,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("menu_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = stringResource(R.string.menu_button_cd),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            JarvisSparkleEmblem(size = 20.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        IconButton(
            onClick = onNewChat,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("top_new_chat_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.EditNote,
                contentDescription = stringResource(R.string.new_chat),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Surface(
            onClick = onOpenMenu,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(36.dp)
                .testTag("account_avatar_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = userInitial,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
