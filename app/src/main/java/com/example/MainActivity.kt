package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ai.JarvisAssistantEngine
import com.example.data.firebase.configureAppCheck
import com.example.data.repository.CalendarPrivilegeRepository
import com.example.data.repository.GmailPrivilegeRepository
import com.example.data.repository.JarvisFirestoreRepository
import com.example.data.repository.SmartHomePrivilegeRepository
import com.example.ui.AuthScreen
import com.example.ui.JarvisScreen
import com.example.ui.JarvisViewModel
import com.example.ui.theme.JarvisTheme
import com.example.voice.TemporaryVoiceService
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose {
            Firebase.auth.removeAuthStateListener(listener)
        }
    }

    if (currentUser == null) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = Firebase.auth.currentUser
            }
        )
    } else {
        val uid = currentUser!!.uid
        key(uid) {
            val viewModel: JarvisViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        val databaseId = app.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        val firestoreRepo = JarvisFirestoreRepository(db, Firebase.auth)
                        val calendarRepo = CalendarPrivilegeRepository(app, firestoreRepo)
                        val gmailRepo = GmailPrivilegeRepository(app)
                        val smartHomeRepo = SmartHomePrivilegeRepository(app, firestoreRepo)
                        val engine = JarvisAssistantEngine(calendarRepo, gmailRepo, smartHomeRepo)
                        val voiceService = TemporaryVoiceService(app)
                        JarvisViewModel(
                            currentUserId = uid,
                            firestoreRepo = firestoreRepo,
                            calendarRepo = calendarRepo,
                            gmailRepo = gmailRepo,
                            smartHomeRepo = smartHomeRepo,
                            assistantEngine = engine,
                            voiceService = voiceService
                        )
                    }
                }
            )
            JarvisScreen(
                viewModel = viewModel,
                onSignedOut = {
                    currentUser = null
                }
            )
        }
    }
}
