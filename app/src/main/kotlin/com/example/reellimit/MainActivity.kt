package com.example.reellimit

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.reellimit.overlay.OverlayAlertManager
import com.example.reellimit.service.ReelGuardAccessibilityService
import com.example.reellimit.ui.theme.ReelLimitTheme

class MainActivity : ComponentActivity() {

    private lateinit var overlayPreviewManager: OverlayAlertManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        overlayPreviewManager = OverlayAlertManager(this)

        setContent {
            ReelLimitTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReelLimitHomeScreen(
                        onOpenOverlaySettings = { openOverlaySettings() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() },
                        onTestOverlay = { testOverlay() }
                    )
                }
            }
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun testOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Display Over Other Apps' first!", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        overlayPreviewManager.showAlert("Instagram", "🧪 Test Preview: Usage of 5 mins exceeded!") {
            Toast.makeText(this, "Alert OK tapped!", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelLimitHomeScreen(
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onTestOverlay: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, ReelGuardAccessibilityService::class.java))
    }

    val prefs = remember {
        context.getSharedPreferences(ReelGuardAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var demoMode by remember {
        mutableStateOf(prefs.getBoolean(ReelGuardAccessibilityService.KEY_DEMO_MODE, false))
    }

    // Refresh permission statuses on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context, ReelGuardAccessibilityService::class.java)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "ReelLimit",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Reel Time Blocker & Limiter",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Monitors Instagram, YouTube, and Facebook. Alerts you at 5 minutes and closes the app to home screen after 6 minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Required Permissions Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Required Permissions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Permission 1: Overlay
                        PermissionItem(
                            title = "1. Display Over Other Apps",
                            description = "Required to draw the 5-min warning alert over Instagram & YouTube.",
                            isGranted = hasOverlayPermission,
                            buttonText = if (hasOverlayPermission) "Granted ✓" else "Grant Permission",
                            onClick = onOpenOverlaySettings
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Permission 2: Accessibility
                        PermissionItem(
                            title = "2. Accessibility Service",
                            description = "Required to detect when social apps open/close and take you home at 6 mins.",
                            isGranted = isAccessibilityEnabled,
                            buttonText = if (isAccessibilityEnabled) "Active ✓" else "Enable Service",
                            onClick = onOpenAccessibilitySettings
                        )
                    }
                }
            }

            // Quick Demo Mode Switch
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (demoMode) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Quick Demo / Testing Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (demoMode) "Active: 10s alert, 15s home close (for testing)" else "Standard: 5 min alert, 6 min home close",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = demoMode,
                            onCheckedChange = { isChecked ->
                                demoMode = isChecked
                                prefs.edit().putBoolean(ReelGuardAccessibilityService.KEY_DEMO_MODE, isChecked).apply()
                            }
                        )
                    }
                }
            }

            // Monitored Apps List
            item {
                Text(
                    text = "Monitored Apps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReelGuardAccessibilityService.DEFAULT_TARGET_APPS.forEach { (pkg, name) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, fontWeight = FontWeight.Medium)
                                Text(
                                    "5m warn / 6m exit",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Test Overlay Button
            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTestOverlay,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Preview Floating Overlay Alert Now")
                }
            }
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    Column {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            description,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(buttonText)
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    for (enabledService in enabledServices) {
        val serviceInfo = enabledService.resolveInfo.serviceInfo
        if (serviceInfo.packageName == context.packageName && serviceInfo.name == service.name) {
            return true
        }
    }
    return false
}
