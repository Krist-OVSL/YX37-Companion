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
            val saved = prefs.getCustomEqBands(numBands)
            saved.forEach { add(it) }
        }
    }

    val presets = listOf("Flat", "Bass Boost", "Treble Boost", "Vocal", "Gaming", "Tùy chỉnh")

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
                            letterSpacing = 1.2.sp,
                            color = CyberCyan
                        )
                    }
                },
                actions = {
                    // Manual Refresh Button
                    IconButton(
                        onClick = {
                            isManuallyRefreshing = true
                            refreshState()
                            Toast.makeText(context, "Đã làm mới trạng thái tai nghe!", Toast.LENGTH_SHORT).show()
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
                            .padding(end = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isConnected) NeonGreen.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f))
                            .border(1.dp, if (isConnected) NeonGreen else Color.Gray, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isConnected) "ĐÃ KẾT NỐI" else "CHƯA KẾT NỐI",
                            color = if (isConnected) NeonGreen else Color.LightGray,
                            fontSize = 11.sp,
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Alert Banner for Android 12+
            if (!hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = NeonRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed)
                            Text(
                                text = "Cần cấp quyền Bluetooth (Nearby Devices)",
                                fontWeight = FontWeight.Bold,
                                color = NeonRed,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            text = "Để ứng dụng nhận diện chính xác tai nghe YX37 và đọc thời lượng pin, vui lòng cho phép quyền 'Thiết bị ở gần'.",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 16.sp
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
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Cấp quyền ngay", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Card 1: Battery Telemetry & Connection Status
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Thời lượng Pin Tai nghe",
                                fontSize = 14.sp,
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
                            tint = if (batteryLevel > 20) NeonGreen else if (batteryLevel >= 0) NeonRed else Color.Gray
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (batteryLevel in 0..100) "$batteryLevel%" else "--%",
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (batteryLevel > 20) TextPrimary else if (batteryLevel >= 0) NeonRed else Color.LightGray
                        )
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(
                                text = if (isConnected) "• Đang kết nối A2DP • Auto-Refresh 3s" else "• Chưa kết nối",
                                fontSize = 12.sp,
                                color = if (isConnected) NeonGreen else TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = statusMessage,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val progress = if (batteryLevel in 0..100) batteryLevel / 100f else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (batteryLevel > 20) NeonGreen else NeonRed,
                        trackColor = DarkSurfaceVariant
                    )
                }
            }

            // Card 2: Interactive Real Hardware Equalizer Controller
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Lock Notice when headset is disconnected
                    if (!isConnected) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(NeonRed.copy(alpha = 0.12f))
                                .border(1.dp, NeonRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = NeonRed, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Vui lòng kết nối tai nghe YX37 để kích hoạt bộ chỉnh EQ.",
                                    fontSize = 12.sp,
                                    color = NeonRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
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
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) TextPrimary else TextSecondary
                            )
                            Text(
                                text = if (isEqEnabled)
                                    "Đang áp dụng EQ cho Spotify, YouTube & toàn hệ thống."
                                else
                                    "Đang tắt EQ (Âm thanh mộc của tai nghe).",
                                fontSize = 12.sp,
                                color = if (isEqEnabled) NeonGreen else TextSecondary,
                                lineHeight = 16.sp
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // Preset Chips Row
                    Text(
                        text = "Cấu hình âm thanh (Presets):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.take(4).forEach { p ->
                            val isSelected = selectedPreset.equals(p, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) CyberCyan else DarkSurfaceVariant)
                                    .clickable(enabled = isConnected && isEqEnabled) {
                                        selectedPreset = p
                                        val newGains = AudioDspManager.applyPreset(p, context)
                                        for (idx in newGains.indices) {
                                            if (idx < currentBands.size) currentBands[idx] = newGains[idx]
                                        }
                                    }
                                    .padding(vertical = 8.dp),
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

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.drop(4).forEach { p ->
                            val isSelected = selectedPreset.equals(p, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) CyberCyan else DarkSurfaceVariant)
                                    .clickable(enabled = isConnected && isEqEnabled) {
                                        selectedPreset = p
                                        if (!p.equals("Tùy chỉnh", ignoreCase = true)) {
                                            val newGains = AudioDspManager.applyPreset(p, context)
                                            for (idx in newGains.indices) {
                                                if (idx < currentBands.size) currentBands[idx] = newGains[idx]
                                            }
                                        }
                                    }
                                    .padding(vertical = 8.dp),
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // Title for Hardware Sliders
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Các Cần Gạt Tần Số Phần Cứng ($numBands dải tần):",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected && isEqEnabled) TextPrimary else TextSecondary
                        )
                        TextButton(
                            enabled = isConnected && isEqEnabled,
                            onClick = {
                                val flat = prefs.resetEqBands(numBands)
                                for (idx in flat.indices) {
                                    if (idx < currentBands.size) currentBands[idx] = flat[idx]
                                }
                                selectedPreset = "Flat"
                                AudioDspManager.applyGains(flat, context)
                                Toast.makeText(context, "Đã đưa tất cả cần gạt về 0 dB!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Về 0 dB", fontSize = 12.sp, color = if (isConnected && isEqEnabled) NeonRed else Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Dynamic Sliders rendered based on actual hardware bands
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceVariant, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        currentBands.indices.forEach { i ->
                            val freq = if (i < hardwareFreqs.size) formatFreq(hardwareFreqs[i]) else "Band $i"
                            val gain = currentBands[i]
                            val isSliderActive = isConnected && isEqEnabled

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = freq,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSliderActive) TextPrimary else Color.DarkGray,
                                    modifier = Modifier.width(56.dp)
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
                                        AudioDspManager.applyGains(currentBands.toFloatArray(), context)
                                    },
                                    valueRange = -12f..12f,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(28.dp),
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
                                    modifier = Modifier.width(54.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "ℹ EQ chạy nền liên tục qua dịch vụ hệ thống, không bị tắt khi bạn mở Spotify, YouTube hay chơi game.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 15.sp
                    )
                }
            }

            // Card 3: Auto-Persistence Switch
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tự động khôi phục cấu hình",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Tự động áp dụng lại cấu hình EQ mỗi khi tai nghe kết nối lại vào điện thoại.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
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
        }
    }
}
