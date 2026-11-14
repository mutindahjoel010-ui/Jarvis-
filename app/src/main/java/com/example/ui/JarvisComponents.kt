package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DisplayMessage
import com.example.data.model.InlineAction
import com.example.voice.VoiceStatus

@Composable
fun JarvisSparkleEmblem(
    size: Dp = 28.dp,
    isAnimating: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_spark")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isAnimating) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val cd = stringResource(R.string.jarvis_emblem_cd)

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = cd }
    ) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val scale = if (isAnimating) pulse else 1f
        val radius = (minOf(w, h) / 2f) * scale
        val inner = radius * 0.28f

        val path = Path().apply {
            moveTo(cx, cy - radius)
            quadraticTo(cx + inner * 0.55f, cy - inner * 0.55f, cx + radius, cy)
            quadraticTo(cx + inner * 0.55f, cy + inner * 0.55f, cx, cy + radius)
            quadraticTo(cx - inner * 0.55f, cy + inner * 0.55f, cx - radius, cy)
            quadraticTo(cx - inner * 0.55f, cy - inner * 0.55f, cx, cy - radius)
            close()
        }

        rotate(degrees = if (isAnimating) rotation else 0f, pivot = Offset(cx, cy)) {
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    colors = listOf(primary, secondary, tertiary),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
            )
        }
    }
}

@Composable
fun MessageBubbleRow(
    message: DisplayMessage,
    onCopy: (String) -> Unit,
    onReadAloud: (String) -> Unit,
    onInlineAction: (InlineAction) -> Unit
) {
    if (message.role == "user") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .testTag("user_message_${message.id}")
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.padding(top = 2.dp)) {
                    JarvisSparkleEmblem(
                        size = 24.dp,
                        isAnimating = message.isStreaming
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (message.text.isBlank() && message.isStreaming) {
                        Text(
                            text = "Thinking…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.testTag("assistant_message_${message.id}")
                        )
                    }

                    if (message.inlineAction != null && !message.isStreaming) {
                        Spacer(modifier = Modifier.height(10.dp))
                        InlineActionChip(
                            action = message.inlineAction,
                            onClick = { onInlineAction(message.inlineAction) }
                        )
                    }

                    if (!message.isStreaming && message.text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onReadAloud(message.text) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(40.dp)
                                    .testTag("read_aloud_${message.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                    contentDescription = "Read answer aloud",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onCopy(message.text) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(40.dp)
                                    .testTag("copy_answer_${message.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy answer",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineActionChip(
    action: InlineAction,
    onClick: () -> Unit
) {
    val (label, icon) = when (action) {
        InlineAction.RequestCalendarPermission -> stringResource(R.string.allow_calendar) to Icons.Rounded.CalendarMonth
        InlineAction.ConnectGmail -> stringResource(R.string.connect_gmail) to Icons.Rounded.Mail
        InlineAction.OpenSmartHomeSetup -> stringResource(R.string.smart_home_hub) to Icons.Rounded.Home
    }
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("inline_action_button")
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun JarvisComposerBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    isSending: Boolean,
    isTemporaryVoiceEnabled: Boolean,
    voiceStatus: VoiceStatus,
    onSend: () -> Unit,
    onStopGenerating: () -> Unit,
    onMicClick: () -> Unit,
    onToggleTemporaryVoice: () -> Unit,
    onEndTemporaryVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = isTemporaryVoiceEnabled || voiceStatus !is VoiceStatus.Idle
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val statusText = when (voiceStatus) {
                        is VoiceStatus.Listening -> {
                            if (voiceStatus.partialText.isNotBlank()) {
                                voiceStatus.partialText
                            } else {
                                stringResource(R.string.voice_listening_label)
                            }
                        }
                        VoiceStatus.Speaking -> stringResource(R.string.voice_speaking_label)
                        is VoiceStatus.Error -> voiceStatus.message
                        VoiceStatus.Idle -> stringResource(R.string.voice_mode_active_badge)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 220.dp)
                    )
                    if (isTemporaryVoiceEnabled) {
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = onEndTemporaryVoice,
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("end_temp_voice_button")
                        ) {
                            Text(
                                text = stringResource(R.string.voice_end_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(28.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.prompt_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank() && !isSending) onSend()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("prompt_input_field")
                    )
                }

                // Optional temporary speech-to-voice toggle
                IconButton(
                    onClick = onToggleTemporaryVoice,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(
                            if (isTemporaryVoiceEnabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .testTag("temp_voice_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = stringResource(R.string.voice_mode_toggle_cd),
                        tint = if (isTemporaryVoiceEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // One-tap speech input
                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("mic_input_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = stringResource(R.string.mic_button_cd),
                        tint = if (voiceStatus is VoiceStatus.Listening) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (isSending) {
                    IconButton(
                        onClick = onStopGenerating,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .testTag("stop_generating_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.stop_button_cd),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = onSend,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.send_button_cd),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
