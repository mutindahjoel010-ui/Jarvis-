package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.SmartDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Context.homeHubDataStore: DataStore<Preferences> by preferencesDataStore(name = "home_hub_prefs")

data class HomeAssistantConfig(
    val serverUrl: String = "",
    val accessToken: String = ""
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && accessToken.isNotBlank()
}

data class SmartDeviceActionResult(
    val success: Boolean,
    val message: String,
    val needsSetup: Boolean = false
)

class SmartHomePrivilegeRepository(
    private val context: Context,
    private val firestoreRepository: JarvisFirestoreRepository
) {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val urlKey = stringPreferencesKey("ha_server_url")
    private val tokenKey = stringPreferencesKey("ha_access_token")

    val homeAssistantConfig: Flow<HomeAssistantConfig> = context.homeHubDataStore.data.map { prefs ->
        HomeAssistantConfig(
            serverUrl = prefs[urlKey].orEmpty(),
            accessToken = prefs[tokenKey].orEmpty()
        )
    }

    fun observeRegisteredDevices(): Flow<List<SmartDevice>> = firestoreRepository.observeSmartDevices()

    suspend fun saveHomeAssistantConfig(serverUrl: String, accessToken: String) {
        val normalized = serverUrl.trim().trimEnd('/')
        context.homeHubDataStore.edit { prefs ->
            prefs[urlKey] = normalized
            prefs[tokenKey] = accessToken.trim()
        }
    }

    suspend fun clearHomeAssistantConfig() {
        context.homeHubDataStore.edit { prefs ->
            prefs.remove(urlKey)
            prefs.remove(tokenKey)
        }
    }

    suspend fun listDevices(roomFilter: String = ""): List<SmartDevice> = withContext(Dispatchers.IO) {
        val config = homeAssistantConfig.first()
        val haDevices = if (config.isConfigured) {
            fetchHomeAssistantDevices(config)
        } else {
            emptyList()
        }

        val cloudDevices = firestoreRepository.listSmartDevices().getOrDefault(emptyList())
        val haEntityIds = haDevices.map { it.entityId }.toSet()

        val merged = buildList {
            addAll(haDevices)
            cloudDevices.forEach { dev ->
                if (dev.entityId.isBlank() || dev.entityId !in haEntityIds) {
                    add(dev)
                }
            }
        }

        if (roomFilter.isBlank()) {
            merged
        } else {
            merged.filter { it.room.contains(roomFilter, ignoreCase = true) }
        }
    }

    suspend fun addDevice(
        name: String,
        room: String,
        deviceType: String,
        entityId: String = ""
    ): SmartDeviceActionResult {
        val device = SmartDevice(
            name = name,
            room = room,
            deviceType = deviceType,
            isOn = false,
            level = 100L,
            targetTemp = 70L,
            entityId = entityId
        )
        val result = firestoreRepository.createSmartDevice(device)
        return if (result.isSuccess) {
            SmartDeviceActionResult(
                success = true,
                message = "Added ${device.name} to your home devices."
            )
        } else {
            SmartDeviceActionResult(
                success = false,
                message = result.exceptionOrNull()?.localizedMessage ?: "Could not add smart device."
            )
        }
    }

    suspend fun deleteDevice(deviceId: String): Result<Unit> {
        return firestoreRepository.deleteSmartDevice(deviceId)
    }

    suspend fun controlDevice(
        deviceNameOrId: String,
        turnOn: Boolean? = null,
        level: Int? = null,
        targetTemp: Int? = null
    ): SmartDeviceActionResult = withContext(Dispatchers.IO) {
        val allDevices = listDevices()
        if (allDevices.isEmpty()) {
            return@withContext SmartDeviceActionResult(
                success = false,
                message = "No smart home devices are added yet.",
                needsSetup = true
            )
        }

        val target = allDevices.firstOrNull {
            it.id == deviceNameOrId ||
                it.entityId.equals(deviceNameOrId, ignoreCase = true) ||
                it.name.equals(deviceNameOrId, ignoreCase = true) ||
                it.name.contains(deviceNameOrId, ignoreCase = true)
        }

        if (target == null) {
            return@withContext SmartDeviceActionResult(
                success = false,
                message = "I couldn't find a device called \"$deviceNameOrId\"."
            )
        }

        val newIsOn = turnOn ?: if (level != null) level > 0 else target.isOn
        val newLevel = level?.toLong()?.coerceIn(0L, 100L) ?: target.level
        val newTemp = targetTemp?.toLong()?.coerceIn(40L, 100L) ?: target.targetTemp

        val config = homeAssistantConfig.first()
        if (config.isConfigured && target.entityId.isNotBlank()) {
            val haSuccess = callHomeAssistantService(
                config = config,
                device = target,
                turnOn = newIsOn,
                level = level,
                targetTemp = targetTemp
            )
            if (!haSuccess) {
                return@withContext SmartDeviceActionResult(
                    success = false,
                    message = "Could not reach Home Assistant for ${target.name}."
                )
            }
        }

        if (target.id.isNotBlank() && !target.id.startsWith("ha_")) {
            firestoreRepository.updateSmartDeviceState(
                deviceId = target.id,
                isOn = newIsOn,
                level = newLevel,
                targetTemp = newTemp
            )
        }

        val stateSummary = buildString {
            append(target.name)
            append(if (newIsOn) " is now on" else " is now off")
            if (level != null && target.deviceType in setOf("light", "fan")) {
                append(" at $newLevel%")
            }
            if (targetTemp != null && target.deviceType == "thermostat") {
                append(" set to $newTemp°F")
            }
            append(".")
        }

        SmartDeviceActionResult(success = true, message = stateSummary)
    }

    private fun fetchHomeAssistantDevices(config: HomeAssistantConfig): List<SmartDevice> {
        return try {
            val req = Request.Builder()
                .url("${config.serverUrl}/api/states")
                .header("Authorization", "Bearer ${config.accessToken}")
                .get()
                .build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                val array = JSONArray(body)
                val list = mutableListOf<SmartDevice>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val entityId = obj.optString("entity_id")
                    val domain = entityId.substringBefore(".")
                    if (domain !in setOf("light", "switch", "climate", "lock", "fan", "media_player")) {
                        continue
                    }
                    val attrs = obj.optJSONObject("attributes")
                    val friendly = attrs?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: entityId
                    val state = obj.optString("state")
                    val isOn = state in setOf("on", "unlocked", "heat", "cool", "auto", "playing")
                    val brightnessPct = attrs?.optInt("brightness", -1)
                        ?.takeIf { it >= 0 }
                        ?.let { (it * 100 / 255).toLong() } ?: 100L
                    val temp = attrs?.optDouble("temperature", Double.NaN)
                        ?.takeIf { !it.isNaN() }?.toLong() ?: 70L

                    val mappedType = when (domain) {
                        "light" -> "light"
                        "climate" -> "thermostat"
                        "lock" -> "lock"
                        "switch" -> "plug"
                        "fan" -> "fan"
                        "media_player" -> "tv"
                        else -> "other"
                    }

                    list.add(
                        SmartDevice(
                            id = "ha_$entityId",
                            name = friendly,
                            room = "",
                            deviceType = mappedType,
                            isOn = isOn,
                            level = brightnessPct,
                            targetTemp = temp,
                            entityId = entityId
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query Home Assistant states", e)
            emptyList()
        }
    }

    private fun callHomeAssistantService(
        config: HomeAssistantConfig,
        device: SmartDevice,
        turnOn: Boolean,
        level: Int?,
        targetTemp: Int?
    ): Boolean {
        return try {
            val domain = device.entityId.substringBefore(".").ifBlank { "homeassistant" }
            val service = when {
                domain == "lock" -> if (turnOn) "unlock" else "lock"
                domain == "climate" && targetTemp != null -> "set_temperature"
                else -> if (turnOn) "turn_on" else "turn_off"
            }

            val payload = JSONObject().apply {
                put("entity_id", device.entityId)
                if (domain == "light" && turnOn && level != null) {
                    put("brightness_pct", level.coerceIn(0, 100))
                }
                if (domain == "climate" && targetTemp != null) {
                    put("temperature", targetTemp.coerceIn(40, 100))
                }
            }

            val req = Request.Builder()
                .url("${config.serverUrl}/api/services/$domain/$service")
                .header("Authorization", "Bearer ${config.accessToken}")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttp.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Home Assistant service call failed", e)
            false
        }
    }

    private companion object {
        const val TAG = "SmartHomeRepo"
    }
}
