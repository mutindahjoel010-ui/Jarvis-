package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Conversation
import com.example.data.model.SmartDevice
import com.example.data.repository.HomeAssistantConfig

@Composable
fun JarvisNavigationDrawerContent(
    userEmail: String,
    conversations: List<Conversation>,
    activeConversationId: String?,
    hasCalendarPermission: Boolean,
    isGmailConnected: Boolean,
    smartDeviceCount: Int,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onConnectGmail: () -> Unit,
    onOpenSmartHomeSheet: () -> Unit,
    onOpenInstallSheet: () -> Unit,
    onSignOut: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(304.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Button(
                onClick = onNewChat,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("drawer_new_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.new_chat))
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.recent_chats_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Text(
                text = stringResource(R.string.recent_chats_retention),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )

            if (conversations.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_recent_chats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 280.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        NavigationDrawerItem(
                            selected = conv.id == activeConversationId,
                            onClick = { onSelectConversation(conv.id) },
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = conv.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            badge = {
                                IconButton(
                                    onClick = { onDeleteConversation(conv.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.delete_label),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            modifier = Modifier.testTag("drawer_chat_${conv.id}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.connected_services_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )

            NavigationDrawerItem(
                selected = false,
                onClick = {
                    if (!hasCalendarPermission) onRequestCalendarPermission()
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.CalendarMonth,
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = if (hasCalendarPermission) {
                            stringResource(R.string.calendar_allowed)
                        } else {
                            stringResource(R.string.allow_calendar)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                badge = {
                    if (hasCalendarPermission) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                modifier = Modifier.testTag("drawer_calendar_item")
            )

            NavigationDrawerItem(
                selected = false,
                onClick = onConnectGmail,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Mail,
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = if (isGmailConnected) {
                            stringResource(R.string.gmail_connected)
                        } else {
                            stringResource(R.string.connect_gmail)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                badge = {
                    if (isGmailConnected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                modifier = Modifier.testTag("drawer_gmail_item")
            )

            NavigationDrawerItem(
                selected = false,
                onClick = onOpenSmartHomeSheet,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Home,
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.smart_home_hub),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                badge = {
                    if (smartDeviceCount > 0) {
                        Text(
                            text = smartDeviceCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier.testTag("drawer_smarthome_item")
            )

            NavigationDrawerItem(
                selected = false,
                onClick = onOpenInstallSheet,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.InstallMobile,
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.install_on_phone),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                modifier = Modifier.testTag("drawer_install_phone_item")
            )

            Spacer(modifier = Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = userEmail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("sign_out_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = stringResource(R.string.sign_out),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartHomeSheet(
    devices: List<SmartDevice>,
    homeAssistantConfig: HomeAssistantConfig,
    onDismiss: () -> Unit,
    onAddDevice: (name: String, room: String, type: String) -> Unit,
    onToggleDevice: (SmartDevice) -> Unit,
    onDeleteDevice: (String) -> Unit,
    onSaveHomeAssistant: (url: String, token: String) -> Unit,
    onDisconnectHomeAssistant: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddForm by remember { mutableStateOf(false) }
    var showHaForm by remember { mutableStateOf(false) }

    var newDeviceName by remember { mutableStateOf("") }
    var newDeviceRoom by remember { mutableStateOf("") }
    var newDeviceType by remember { mutableStateOf("light") }

    var haUrl by remember(homeAssistantConfig.serverUrl) {
        mutableStateOf(homeAssistantConfig.serverUrl)
    }
    var haToken by remember(homeAssistantConfig.accessToken) {
        mutableStateOf(homeAssistantConfig.accessToken)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.smart_home_hub),
                    style = MaterialTheme.typography.titleLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showHaForm = !showHaForm
                            showAddForm = false
                        },
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        Text(stringResource(R.string.home_assistant_title))
                    }
                    Button(
                        onClick = {
                            showAddForm = !showAddForm
                            showHaForm = false
                        },
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .testTag("open_add_device_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.add_device))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (showAddForm) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = newDeviceName,
                            onValueChange = { newDeviceName = it },
                            label = { Text("Device name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("device_name_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newDeviceRoom,
                            onValueChange = { newDeviceRoom = it },
                            label = { Text("Room") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("light", "thermostat", "lock", "plug").forEach { type ->
                                val selected = newDeviceType == type
                                if (selected) {
                                    Button(
                                        onClick = { newDeviceType = type },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(type.replaceFirstChar { it.uppercase() }, maxLines = 1)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { newDeviceType = type },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(type.replaceFirstChar { it.uppercase() }, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showAddForm = false }) {
                                Text(stringResource(R.string.cancel_label))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onAddDevice(newDeviceName, newDeviceRoom, newDeviceType)
                                    newDeviceName = ""
                                    newDeviceRoom = ""
                                    showAddForm = false
                                },
                                enabled = newDeviceName.isNotBlank(),
                                modifier = Modifier.testTag("save_device_button")
                            ) {
                                Text(stringResource(R.string.save_label))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (showHaForm) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = haUrl,
                            onValueChange = { haUrl = it },
                            label = { Text(stringResource(R.string.home_assistant_url_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = haToken,
                            onValueChange = { haToken = it },
                            label = { Text(stringResource(R.string.home_assistant_token_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (homeAssistantConfig.isConfigured) {
                                TextButton(onClick = {
                                    onDisconnectHomeAssistant()
                                    showHaForm = false
                                }) {
                                    Text("Disconnect")
                                }
                            }
                            TextButton(onClick = { showHaForm = false }) {
                                Text(stringResource(R.string.cancel_label))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onSaveHomeAssistant(haUrl, haToken)
                                    showHaForm = false
                                },
                                enabled = haUrl.isNotBlank() && haToken.isNotBlank()
                            ) {
                                Text(stringResource(R.string.save_label))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (devices.isEmpty()) {
                Text(
                    text = "No home devices added yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { it.id }) { device ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    val sub = buildString {
                                        if (device.room.isNotBlank()) append(device.room).append(" · ")
                                        append(device.deviceType.replaceFirstChar { it.uppercase() })
                                    }
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = device.isOn,
                                    onCheckedChange = { onToggleDevice(device) }
                                )
                                IconButton(
                                    onClick = { onDeleteDevice(device.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.delete_label),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
