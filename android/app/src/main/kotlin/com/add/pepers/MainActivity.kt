package com.add.pepers

import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 7001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkReminderScheduler.schedule(applicationContext)
        CoroutineScope(Dispatchers.IO).launch { createInternalAutoBackup(applicationContext) }

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
                        var authChecked by remember { mutableStateOf(false) }
                        var authenticated by remember { mutableStateOf(false) }

                        if (!authChecked) {
                            LaunchedAuthCheck {
                                authenticated = it
                                authChecked = true
                            }
                        } else if (!authenticated) {
                            AuthScreen { authenticated = true }
                        } else {
                            var unlocked by remember { mutableStateOf(!hasAppPin(this@MainActivity)) }
                            if (unlocked) {
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
    }

    @androidx.compose.runtime.Composable
    private fun LaunchedAuthCheck(onResult: (Boolean) -> Unit) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) { SupabaseAuth.ensureSession(this@MainActivity) }
            val local = SupabaseAuth.savedProfile(this@MainActivity)
            onResult(result != null || local.name.isNotBlank())
        }
    }
}
