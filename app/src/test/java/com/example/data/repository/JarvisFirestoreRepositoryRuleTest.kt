package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.CalendarEvent
import com.example.data.model.SmartDevice
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JarvisFirestoreRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun conversationsAndMessages_validOwner_succeeds() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = JarvisFirestoreRepository(firestore, auth)

        val convId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createConversation("Morning check").getOrThrow()
        }
        assertTrue(convId.isNotBlank())

        val msgId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.addMessage(convId, "user", "What is on my schedule?").getOrThrow()
        }
        assertTrue(msgId.isNotBlank())

        val messages = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMessages(convId).first { it.isNotEmpty() }
        }
        assertEquals(1, messages.size)
        assertEquals("What is on my schedule?", messages.first().text)
    }

    @Test
    fun smartDevices_validCrud_succeeds() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = JarvisFirestoreRepository(firestore, auth)

        val devId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createSmartDevice(
                SmartDevice(
                    name = "Kitchen Light",
                    room = "Kitchen",
                    deviceType = "light",
                    isOn = false
                )
            ).getOrThrow()
        }

        val devices = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.listSmartDevices().getOrThrow()
        }
        assertTrue(devices.any { it.id == devId && it.name == "Kitchen Light" })

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateSmartDeviceState(devId, isOn = true, level = 80L, targetTemp = 70L).getOrThrow()
        }

        val updated = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.listSmartDevices().getOrThrow().first { it.id == devId }
        }
        assertTrue(updated.isOn)
        assertEquals(80L, updated.level)
    }

    @Test
    fun calendarEvents_createAndList_succeeds() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = JarvisFirestoreRepository(firestore, auth)
        val now = System.currentTimeMillis()

        val eventId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createCalendarEvent(
                CalendarEvent(
                    title = "Dentist",
                    location = "Oak Street",
                    startTimeMillis = now + 3600_000L,
                    endTimeMillis = now + 7200_000L
                )
            ).getOrThrow()
        }

        val events = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.listCalendarEvents(now, now + 86400_000L).getOrThrow()
        }
        assertTrue(events.any { it.id == eventId && it.title == "Dentist" })
    }

    @Test
    fun crossUserAccess_isDeniedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = JarvisFirestoreRepository(firestore, auth)
        val devId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createSmartDevice(
                SmartDevice(name = "Bedroom Lamp", deviceType = "light", isOn = true)
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("smart_devices").document(devId)
                    .get()
                    .await()
            }
            fail("Expected PERMISSION_DENIED for cross-user read")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx!!.code)
        }
    }

    @Test
    fun unauthenticatedObserve_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = JarvisFirestoreRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeSmartDevices().first()
            }
            fail("Expected failure when unauthenticated")
        } catch (e: Exception) {
            // Either IllegalStateException from requireUserId() or PERMISSION_DENIED is expected
            assertTrue(
                e is IllegalStateException ||
                    e.unwrapFirestoreException()?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            )
        }
    }

    @Test
    fun purgeExpiredConversations_deletesOlderChats() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = JarvisFirestoreRepository(firestore, auth)

        val convId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createConversation("Old chat").getOrThrow()
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.addMessage(convId, "user", "Hello").getOrThrow()
        }

        // Passing maxAgeMillis = -60_000L ensures cutoff is in the future to verify deletion
        val deleted = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.purgeExpiredConversations(maxAgeMillis = -60_000L)
        }
        assertTrue(deleted >= 1)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_jarvis@test.com"
        const val BOB_EMAIL = "bob_jarvis@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
