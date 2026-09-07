package com.yx37.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yx37.companion.audio.AudioDspManager
import com.yx37.companion.bluetooth.BluetoothMonitor
import com.yx37.companion.data.CustomProfile
import com.yx37.companion.data.PreferencesManager
import com.yx37.companion.data.Yx37DeviceState
import com.yx37.companion.services.AudioDspService
import com.yx37.companion.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var monitor: BluetoothMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            prefs = PreferencesManager(this)
            monitor = BluetoothMonitor(this)

            // Initialize Audio DSP Engine safely
            AudioDspManager.init(this)

            // Handle Audio Session from Spotify / Media Players if launched as Control Panel
            handleIncomingSession(intent)
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error in onCreate setup", t)
        }

        setContent {
            Yx37CompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(prefs, monitor)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingSession(intent)
    }

    private fun handleIncomingSession(intent: Intent?) {
        try {
            val sessionId = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1) ?: -1
            if (sessionId > 0) {
                AudioDspManager.onSessionOpen(sessionId)
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error handling incoming session", t)
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            monitor.start()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error starting monitor", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            monitor.stop()
        } catch (t: Throwable) {
            // ignore
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(prefs: PreferencesManager, monitor: BluetoothMonitor) {
    val context = LocalContext.current

    var isEqEnabled by remember { mutableStateOf(prefs.isEqEnabled) }
    var selectedPreset by remember { mutableStateOf(prefs.selectedPreset) }
    var isAutoRestore by remember { mutableStateOf(prefs.isAutoRestoreEnabled) }
    var batteryLevel by remember { mutableIntStateOf(prefs.lastBatteryLevel) }
    var isConnected by remember { mutableStateOf(prefs.isConnected) }
    var deviceName by remember { mutableStateOf(prefs.connectedDeviceName ?: "YX37") }
    var statusMessage by remember { mutableStateOf("Đang kiểm tra kết nối...") }
    var hasPermission by remember { mutableStateOf(monitor.hasBluetoothPermission()) }
    var isManuallyRefreshing by remember { mutableStateOf(false) }

    val numBands = AudioDspManager.hardwareBandsCount
    val hardwareFreqs = AudioDspManager.hardwareBandFreqs

    // Custom Profiles Management State
    val customProfiles = remember {
        mutableStateListOf<CustomProfile>().apply {
            addAll(prefs.getCustomProfiles(numBands))
        }
    }
    var activeProfileId by remember { mutableStateOf(prefs.activeCustomProfileId) }
    var showCreateProfileDialog by remember { mutableStateOf(false) }
    var newProfileNameInput by remember { mutableStateOf("") }
    var profileToDelete by remember { mutableStateOf<CustomProfile?>(null) }

    fun formatFreq(hz: Int): String {
        return if (hz >= 1000) {
            val k = hz / 1000f
            if (k % 1f == 0f) "${k.toInt()}kHz" else "${String.format(Locale.US, "%.1f", k)}kHz"
        } else {
            "${hz}Hz"
        }
    }

    val currentBands = remember {
        mutableStateListOf<Float>().apply {
            val initialGains = if (selectedPreset.equals("Tùy chỉnh", ignoreCase = true)) {
                val active = prefs.getActiveCustomProfile(numBands)
                active.gains.toFloatArray()
            } else {
                AudioDspManager.calculatePresetGains(selectedPreset, hardwareFreqs)
            }
            initialGains.forEach { add(it) }
        }
    }

    val presets = listOf("Flat", "Bass Boost", "Treble Boost", "Vocal", "Gaming")

    // Permission launcher for Android 12+ (API 31+)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            true
        }
        hasPermission = granted
        try {
            monitor.start()
            val res = monitor.checkStatus()
            isConnected = res.isConnected
            batteryLevel = res.batteryLevel
            deviceName = res.deviceName ?: "YX37"
            statusMessage = res.statusMessage
            AudioDspManager.setHeadsetConnection(res.isConnected)
            if (res.isConnected && granted) {
                AudioDspService.start(context)
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error after permission result", t)
        }
    }

    // Auto-request permission on first launch if missing
    LaunchedEffect(Unit) {
        if (!hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
    }

    // Core refresh function
    fun refreshState() {
        try {
            hasPermission = monitor.hasBluetoothPermission()
            val res = monitor.checkStatus()
            isConnected = res.isConnected
            batteryLevel = res.batteryLevel
            deviceName = res.deviceName ?: "YX37"
            statusMessage = res.statusMessage
            AudioDspManager.setHeadsetConnection(res.isConnected)

            if (res.isConnected && hasPermission) {
                AudioDspService.start(context)
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error refreshing status", t)
        }
    }

    // Broadcast receiver for Bluetooth lifecycle events
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                refreshState()
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error registering receiver", t)
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // Periodic Auto-Refresh: updates every 3 seconds continuously
    LaunchedEffect(Unit) {
        while (isActive) {
            refreshState()
            delay(3000)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "refresh")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🎧  YX37 COMPANION",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                            fontSize = 16.sp,
                            color = CyberCyan
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "v1.1.0",
                            fontSize = 10.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                actions = {
                    // Manual Refresh Button
                    IconButton(
                        onClick = {
                            isManuallyRefreshing = true
                            refreshState()
                            Toast.makeText(context, "Đã làm mới trạng thái!", Toast.LENGTH_SHORT).show()
                            isManuallyRefreshing = false
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Làm mới",
                            tint = CyberCyan,
                            modifier = if (isManuallyRefreshing) Modifier.rotate(rotation) else Modifier
                        )
                    }

                    // Connection Status Badge
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isConnected) NeonGreen.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f))
                            .border(1.dp, if (isConnected) NeonGreen else Color.Gray, RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isConnected) "ĐÃ KẾT NỐI" else "CHƯA KẾT NỐI",
                            color = if (isConnected) NeonGreen else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Permission Alert Banner for Android 12+
            if (!hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = NeonRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Cần cấp quyền Bluetooth (Nearby Devices)",
                                fontWeight = FontWeight.Bold,
                                color = NeonRed,
                                fontSize = 13.sp
                            )
                        }
                        Text(
                            text = "Để ứng dụng nhận diện chính xác tai nghe YX37 và đọc pin, vui lòng cấp quyền 'Thiết bị ở gần'.",
                            fontSize = 11.sp,
                            color = TextPrimary
                        )
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.BLUETOOTH_SCAN
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonRed),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Cấp quyền ngay", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Card 1: Compact Battery & Connection Status
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Thời lượng Pin Tai nghe",
                                fontSize = 13.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "$deviceName (${Yx37DeviceState.TARGET_MAC_ADDRESS})",
                                fontSize = 11.sp,
                                color = CyberCyan
                            )
                        }
                        Icon(
                            imageVector = if (batteryLevel > 20) Icons.Default.BatteryChargingFull else Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = if (batteryLevel > 20) NeonGreen else if (batteryLevel >= 0) NeonRed else Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (batteryLevel in 0..100) "$batteryLevel%" else "--%",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (batteryLevel > 20) TextPrimary else if (batteryLevel >= 0) NeonRed else Color.LightGray
                        )
                        Column(modifier = Modifier.padding(bottom = 4.dp)) {
                            Text(
                                text = if (isConnected) "• Đang kết nối A2DP • Auto-Refresh 3s" else "• Chưa kết nối",
                                fontSize = 11.sp,
                                color = if (isConnected) NeonGreen else TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = statusMessage,
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val progress = if (batteryLevel in 0..100) batteryLevel / 100f else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (batteryLevel > 20) NeonGreen else NeonRed,
                        trackColor = DarkSurfaceVariant
                    )
                }
            }

            // Card 2: Interactive Real Hardware Equalizer Controller
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Lock Notice when headset is disconnected
                    if (!isConnected) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(NeonRed.copy(alpha = 0.12f))
                                .border(1.dp, NeonRed.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = NeonRed, modifier = Modifier.size(16.dp))
                                Text(
                                    text = "Vui lòng kết nối tai nghe YX37 để kích hoạt bộ chỉnh EQ.",
                                    fontSize = 11.sp,
                                    color = NeonRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Master EQ Switch Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Equalizer Âm Thanh YX37",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) TextPrimary else TextSecondary
                            )
                            Text(
                                text = if (isEqEnabled)
                                    "Đang áp dụng EQ cho Spotify, YouTube & toàn hệ thống."
                                else
                                    "Đang tắt EQ (Âm thanh mộc của tai nghe).",
                                fontSize = 11.sp,
                                color = if (isEqEnabled) NeonGreen else TextSecondary
                            )
                        }

                        Switch(
                            checked = isEqEnabled,
                            enabled = isConnected,
                            onCheckedChange = { enabled ->
                                isEqEnabled = enabled
                                AudioDspManager.setMasterEnabled(enabled, context)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkBackground,
                                checkedTrackColor = CyberCyan,
                                disabledCheckedThumbColor = Color.DarkGray,
                                disabledCheckedTrackColor = Color.Gray
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Preset Chips Rows
                    Text(
                        text = "Cấu hình âm thanh (Presets):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Single compact line of presets
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(presets) { p ->
                            val isSelected = selectedPreset.equals(p, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) CyberCyan else DarkSurfaceVariant)
                                    .clickable(enabled = isConnected && isEqEnabled) {
                                        selectedPreset = p
                                        prefs.selectedPreset = p
                                        val newGains = AudioDspManager.applyPreset(p, context)
                                        for (idx in newGains.indices) {
                                            if (idx < currentBands.size) currentBands[idx] = newGains[idx]
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = p,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) DarkBackground else if (isConnected && isEqEnabled) TextPrimary else Color.Gray
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Custom Profiles Management (Always visible or highlighted when in Custom mode)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Danh sách tùy chỉnh cá nhân:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonGreen
                        )
                        TextButton(
                            enabled = isConnected && isEqEnabled,
                            onClick = {
                                newProfileNameInput = "Tùy chỉnh ${customProfiles.size + 1}"
                                showCreateProfileDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = if (isConnected && isEqEnabled) NeonGreen else Color.Gray, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Tạo mới", fontSize = 11.sp, color = if (isConnected && isEqEnabled) NeonGreen else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Horizontal scrollable list of custom profiles
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(customProfiles) { profile ->
                            val isProfileActive = selectedPreset.equals("Tùy chỉnh", ignoreCase = true) && (profile.id == activeProfileId)

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isProfileActive) NeonGreen.copy(alpha = 0.22f) else DarkSurfaceVariant)
                                    .border(1.dp, if (isProfileActive) NeonGreen else Color.Transparent, RoundedCornerShape(8.dp)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Profile Name / Click to select
                                Box(
                                    modifier = Modifier
                                        .clickable(enabled = isConnected && isEqEnabled) {
                                            selectedPreset = "Tùy chỉnh"
                                            prefs.selectedPreset = "Tùy chỉnh"
                                            activeProfileId = profile.id
                                            prefs.activeCustomProfileId = profile.id
                                            for (idx in profile.gains.indices) {
                                                if (idx < currentBands.size) currentBands[idx] = profile.gains[idx]
                                            }
                                            AudioDspManager.applyGains(currentBands.toFloatArray(), context)
                                        }
                                        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                                ) {
                                    Text(
                                        text = profile.name,
                                        fontSize = 11.sp,
                                        fontWeight = if (isProfileActive) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isProfileActive) NeonGreen else TextPrimary
                                    )
                                }

                                // Delete Button (Always accessible for each profile)
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable(
                                            enabled = isConnected && isEqEnabled,
                                            onClick = {
                                                profileToDelete = profile
                                            }
                                        )
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Xóa ${profile.name}",
                                        tint = if (isProfileActive) NeonRed else Color.LightGray.copy(alpha = 0.6f),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Hardware Sliders Title Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Các Cần Gạt Phần Cứng ($numBands dải):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected && isEqEnabled) TextPrimary else TextSecondary
                        )
                        TextButton(
                            enabled = isConnected && isEqEnabled,
                            onClick = {
                                val flat = FloatArray(numBands) { 0f }
                                for (idx in flat.indices) {
                                    if (idx < currentBands.size) currentBands[idx] = flat[idx]
                                }
                                selectedPreset = "Tùy chỉnh"
                                prefs.selectedPreset = "Tùy chỉnh"
                                AudioDspManager.applyCustomGains(flat, context)
                                val pIdx = customProfiles.indexOfFirst { it.id == activeProfileId }
                                if (pIdx >= 0) {
                                    customProfiles[pIdx] = customProfiles[pIdx].copy(gains = flat.toList())
                                }
                                Toast.makeText(context, "Đã đưa cần gạt về 0 dB!", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text("Về 0 dB", fontSize = 11.sp, color = if (isConnected && isEqEnabled) NeonRed else Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Compact Hardware Sliders Box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceVariant, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        currentBands.indices.forEach { i ->
                            val freq = if (i < hardwareFreqs.size) formatFreq(hardwareFreqs[i]) else "Band $i"
                            val gain = currentBands[i]
                            val isSliderActive = isConnected && isEqEnabled

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = freq,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSliderActive) TextPrimary else Color.DarkGray,
                                    modifier = Modifier.width(48.dp)
                                )

                                Slider(
                                    value = gain,
                                    enabled = isSliderActive,
                                    onValueChange = { newVal ->
                                        val stepped = (newVal * 2).roundToInt() / 2f
                                        if (i < currentBands.size) {
                                            currentBands[i] = stepped
                                        }
                                        selectedPreset = "Tùy chỉnh"
                                        prefs.selectedPreset = "Tùy chỉnh"
                                        AudioDspManager.applyCustomGains(currentBands.toFloatArray(), context)
                                        // Update in-memory profile list so state stays reactive
                                        val pIdx = customProfiles.indexOfFirst { it.id == activeProfileId }
                                        if (pIdx >= 0) {
                                            customProfiles[pIdx] = customProfiles[pIdx].copy(gains = currentBands.toList())
                                        }
                                    },
                                    valueRange = -12f..12f,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(22.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = if (gain != 0f && isSliderActive) CyberCyan else if (isSliderActive) Color.LightGray else Color.Gray,
                                        activeTrackColor = if (gain != 0f && isSliderActive) CyberCyan else if (isSliderActive) Color.Gray else Color.DarkGray,
                                        inactiveTrackColor = Color.DarkGray,
                                        disabledThumbColor = Color.Gray,
                                        disabledActiveTrackColor = Color.DarkGray,
                                        disabledInactiveTrackColor = Color.Black
                                    )
                                )

                                Text(
                                    text = "${if (gain > 0) "+" else ""}${String.format(Locale.US, "%.1f", gain)} dB",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.End,
                                    color = if (gain != 0f && isSliderActive) CyberCyan else if (isSliderActive) TextSecondary else Color.DarkGray,
                                    modifier = Modifier.width(50.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "ℹ EQ chạy nền liên tục qua dịch vụ hệ thống cho Spotify, YouTube & Game.",
                        fontSize = 10.sp,
                        color = TextSecondary,
                        lineHeight = 13.sp
                    )
                }
            }

            // Card 3: Auto-Persistence Switch
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tự động khôi phục cấu hình",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Tự động áp dụng lại cấu hình EQ mỗi khi tai nghe kết nối lại.",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )
                    }

                    Switch(
                        checked = isAutoRestore,
                        onCheckedChange = {
                            isAutoRestore = it
                            prefs.isAutoRestoreEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkBackground,
                            checkedTrackColor = NeonGreen
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "YX37 Companion v1.1.0 • By Krist-OVSL",
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }
    }

    // Dialog: Create New Custom Profile
    if (showCreateProfileDialog) {
        AlertDialog(
            onDismissRequest = { showCreateProfileDialog = false },
            title = {
                Text(
                    text = "Tạo cấu hình tùy chỉnh mới",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Nhập tên cho cấu hình âm thanh của bạn (sẽ lưu lại các vị trí cần gạt hiện tại):",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newProfileNameInput,
                        onValueChange = { newProfileNameInput = it },
                        singleLine = true,
                        placeholder = { Text("Ví dụ: Rock, Acoustic, Gaming Pro...", fontSize = 12.sp, color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newProfileNameInput.trim()
                        if (name.isNotEmpty()) {
                            val created = prefs.addCustomProfile(
                                name = name,
                                gains = currentBands.toFloatArray(),
                                numBands = numBands
                            )
                            customProfiles.clear()
                            customProfiles.addAll(prefs.getCustomProfiles(numBands))
                            activeProfileId = created.id
                            selectedPreset = "Tùy chỉnh"
                            prefs.selectedPreset = "Tùy chỉnh"
                            Toast.makeText(context, "Đã tạo: ${created.name}", Toast.LENGTH_SHORT).show()
                        }
                        showCreateProfileDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)
                ) {
                    Text("Tạo", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateProfileDialog = false }) {
                    Text("Hủy", color = Color.Gray, fontSize = 12.sp)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Dialog: Confirm Delete Profile
    if (profileToDelete != null) {
        val prof = profileToDelete!!
        val isLastOne = customProfiles.size <= 1
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = {
                Text(
                    text = if (isLastOne) "Đặt lại cấu hình" else "Xóa cấu hình",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonRed
                )
            },
            text = {
                Text(
                    text = if (isLastOne)
                        "Cấu hình '${prof.name}' là cấu hình duy nhất. Bạn có muốn xóa và đặt lại cấu hình này về mặc định (0 dB) không?"
                    else
                        "Bạn có chắc chắn muốn xóa cấu hình '${prof.name}' không?",
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = prefs.deleteCustomProfile(prof.id, numBands)
                        customProfiles.clear()
                        customProfiles.addAll(updated)
                        activeProfileId = prefs.activeCustomProfileId
                        val newActive = prefs.getActiveCustomProfile(numBands)
                        for (idx in newActive.gains.indices) {
                            if (idx < currentBands.size) currentBands[idx] = newActive.gains[idx]
                        }
                        if (selectedPreset.equals("Tùy chỉnh", ignoreCase = true)) {
                            AudioDspManager.applyGains(currentBands.toFloatArray(), context)
                        }
                        val deletedName = prof.name
                        profileToDelete = null
                        Toast.makeText(
                            context,
                            if (isLastOne) "Đã đặt lại cấu hình về mặc định!" else "Đã xóa: $deletedName",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
                ) {
                    Text(if (isLastOne) "Đặt lại" else "Xóa", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("Hủy", color = Color.Gray, fontSize = 12.sp)
                }
            },
            containerColor = DarkSurface
        )
    }
}
