package com.add.pepers

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import com.add.pepers.cloud.CloudSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 7001
    }

    private var authChecked by mutableStateOf(false)
    private var authenticated by mutableStateOf(false)
    private var unlocked by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkReminderScheduler.schedule(applicationContext)
        CloudSyncScheduler.schedule(applicationContext)
        CloudSyncScheduler.syncNow(applicationContext)
        CoroutineScope(Dispatchers.IO).launch { createInternalAutoBackup(applicationContext) }
        handleOAuthIntent(intent)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFFF8FF)) {
                        if (!authChecked) {
                            LaunchedAuthCheck()
                        } else if (!authenticated) {
                            AuthScreen {
                                authenticated = true
                                unlocked = !hasAppPin(this@MainActivity)
                                CloudSyncScheduler.syncNow(applicationContext)
                            }
                        } else if (unlocked) {
                            WorkLogSheet()
                        } else {
                            Box(Modifier.fillMaxSize().background(Color(0xFFFFF8FF))) {
                                AppLockScreen(this@MainActivity) { unlocked = true }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "pepers" || uri.host != "auth" || uri.path != "/callback") return
        CoroutineScope(Dispatchers.IO).launch {
            val result = SupabaseAuth.handleOAuthCallback(this@MainActivity, uri)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    authenticated = true
                    unlocked = !hasAppPin(this@MainActivity)
                    authChecked = true
                    CloudSyncScheduler.syncNow(applicationContext)
                }.onFailure {
                    Toast.makeText(this@MainActivity, it.message ?: "فشل تسجيل الدخول باستخدام Google", Toast.LENGTH_LONG).show()
                    authChecked = true
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun LaunchedAuthCheck() {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) { SupabaseAuth.ensureSession(this@MainActivity) }
            val local = SupabaseAuth.savedProfile(this@MainActivity)
            authenticated = result != null || local.name.isNotBlank()
            unlocked = !hasAppPin(this@MainActivity)
            authChecked = true
            if (result != null) CloudSyncScheduler.syncNow(this@MainActivity)
        }
    }
}
