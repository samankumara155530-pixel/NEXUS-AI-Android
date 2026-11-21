
package com.nexusai.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.provider.MediaStore
import android.provider.CalendarContract
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// NEXUS AI COLORS

private val NexusNavy = Color(0xFF070B1D)
private val NexusPanel = Color(0xFF111A35)
private val NexusBlue = Color(0xFF397BFF)
private val NexusCyan = Color(0xFF48E0FF)
private val NexusPurple = Color(0xFF9B55FF)
private val LightBackground = Color(0xFFF1F5FF)
private val LightPanel = Color.White
private val InkBlue = Color(0xFF142746)

private const val PREFS_NAME = "nexus_local_data"
private const val KEY_CHAT = "chat_messages"
private const val KEY_NOTES = "notes"
private const val KEY_SCRIPTS = "saved_scripts"
private const val KEY_MEMORIES = "nexus_smart_memories"
private const val KEY_ALIAS = "NEXUS_AI_ENCRYPTION_KEY"

private const val KEY_STABLE_DEVICE_ID = "stable_device_id"

/**
 * Stable identifier for this app installation. It is generated once and kept
 * locally; it is not an IMEI, phone number, or hardware identifier.
 */
private fun getStableDeviceId(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_STABLE_DEVICE_ID, null)
    if (!existing.isNullOrBlank()) return existing
    val created = java.util.UUID.randomUUID().toString()
    prefs.edit().putString(KEY_STABLE_DEVICE_ID, created).apply()
    return created
}


private const val BACKEND_URL =
    "https://nexus-ai-backend-six.vercel.app/chat"

// MAIN ACTIVITY

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NexusRoot()
        }
    }
}

// ENCRYPTION

private fun getEncryptionKey(): SecretKey {

    val keyStore = KeyStore.getInstance("AndroidKeyStore")
    keyStore.load(null)

    val existing = keyStore.getKey(KEY_ALIAS, null)

    if (existing != null) {
        return existing as SecretKey
    }

    val generator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore"
    )

    val spec = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or
            KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(
            KeyProperties.ENCRYPTION_PADDING_NONE
        )
        .setKeySize(256)
        .build()

    generator.init(spec)

    return generator.generateKey()
}

internal fun encryptData(value: String): String {

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    cipher.init(
        Cipher.ENCRYPT_MODE,
        getEncryptionKey()
    )

    val encrypted = cipher.doFinal(
        value.toByteArray(Charsets.UTF_8)
    )

    return Base64.encodeToString(
        cipher.iv + encrypted,
        Base64.NO_WRAP
    )
}

internal fun decryptData(value: String): String {

    val combined = Base64.decode(
        value,
        Base64.NO_WRAP
    )

    require(combined.size >= 28) {
        "Invalid encrypted data"
    }

    val iv = combined.copyOfRange(0, 12)

    val encrypted = combined.copyOfRange(
        12,
        combined.size
    )

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    cipher.init(
        Cipher.DECRYPT_MODE,
        getEncryptionKey(),
        GCMParameterSpec(128, iv)
    )

    return String(
        cipher.doFinal(encrypted),
        Charsets.UTF_8
    )
}

// ENCRYPTED LOCAL STORAGE

private fun readStringList(
    context: Context,
    key: String,
    fallback: List<String> = emptyList()
): List<String> {

    val prefs = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val raw = prefs.getString(key, null)
        ?: return fallback

    return try {

        val array = JSONArray(decryptData(raw))

        List(array.length()) {
            array.optString(it)
        }

    } catch (_: Exception) {

        // Backward compatibility with old unencrypted data.

        try {

            val array = JSONArray(raw)

            val oldData = List(array.length()) {
                array.optString(it)
            }

            writeStringList(
                context,
                key,
                oldData
            )

            oldData

        } catch (_: Exception) {

            fallback
        }
    }
}

private fun writeStringList(
    context: Context,
    key: String,
    values: List<String>
) {

    try {

        val array = JSONArray()

        values.forEach {
            array.put(it)
        }

        val encrypted = encryptData(array.toString())

        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(key, encrypted)
            .apply()

    } catch (_: Exception) {
        // Preserve existing data if encryption fails.
    }
}

// NAVIGATION

private enum class Tab {
    CHAT,
    NOTES,
    SAVED,
    MEMORY,
    TOOLS,
    PROGRESS,
    SETTINGS
}

// MAIN APP

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NexusApp(accessToken: String) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var themeMode by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("nexus_theme_mode", null)
                ?: if (
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean("nexus_dark_theme", true)
                ) "dark" else "light"
        )
    }

    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }

    LaunchedEffect(themeMode, systemDark) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("nexus_theme_mode", themeMode)
            .putBoolean("nexus_dark_theme", dark)
            .apply()
    }

    var sinhala by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("nexus_sinhala_language", true)
        )
    }

    LaunchedEffect(sinhala) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("nexus_sinhala_language", sinhala)
            .apply()
    }

    var tab by remember {
        mutableStateOf(if (BuildConfig.DEVICE_ROLE == "target") Tab.TOOLS else Tab.CHAT)
    }

    var voiceEngineEnabled by remember {
        mutableStateOf(false)
    }

    var voicePermissionDenied by remember {
        mutableStateOf(false)
    }

    // MICROPHONE PERMISSION

    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                try {

                    val serviceIntent = Intent(
                        context,
                        NexusVoiceService::class.java
                    ).apply {
                        action = NexusVoiceService.ACTION_START
                        putExtra(
                            NexusVoiceService.EXTRA_LANGUAGE,
                            if (sinhala) "si-LK" else "en-US"
                        )
                    }

                    ContextCompat.startForegroundService(
                        context,
                        serviceIntent
                    )

                    voiceEngineEnabled = true
                    voicePermissionDenied = false

                } catch (e: Exception) {

                    voiceEngineEnabled = false

                    Toast.makeText(
                        context,
                        "Voice Engine Error: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } else {

                voiceEngineEnabled = false
                voicePermissionDenied = true

                Toast.makeText(
                    context,
                    "Microphone permission required.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // VOICE ENGINE CONTROL

    fun startVoiceEngine() {

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {

            try {

                val serviceIntent = Intent(
                    context,
                    NexusVoiceService::class.java
                ).apply {
                    action = NexusVoiceService.ACTION_START
                    putExtra(
                        NexusVoiceService.EXTRA_LANGUAGE,
                        if (sinhala) "si-LK" else "en-US"
                    )
                }

                ContextCompat.startForegroundService(
                    context,
                    serviceIntent
                )

                voiceEngineEnabled = true
                voicePermissionDenied = false

            } catch (e: Exception) {

                voiceEngineEnabled = false

                Toast.makeText(
                    context,
                    "Voice Error: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    fun stopVoiceEngine() {

        val stopIntent = Intent(
            context,
            NexusVoiceService::class.java
        ).apply {
            action = NexusVoiceService.ACTION_STOP
        }

        try {
            context.startService(stopIntent)
        } catch (_: Exception) {
            context.stopService(
                Intent(context, NexusVoiceService::class.java)
            )
        }

        voiceEngineEnabled = false
    }

    // LOCAL DATA

    val notes = remember {
        mutableStateListOf<String>().apply {
            addAll(readStringList(context, KEY_NOTES))
        }
    }

    val savedScripts = remember {
        mutableStateListOf<String>().apply {
            addAll(readStringList(context, KEY_SCRIPTS))
        }
    }

    val memories = remember {
        mutableStateListOf<String>().apply {
            addAll(readStringList(context, KEY_MEMORIES))
        }
    }

    val pendingMemories = remember {
        mutableStateListOf<String>()
    }

    var memoryText by remember {
        mutableStateOf("")
    }

    var noteText by remember {
        mutableStateOf("")
    }

    var calendarEventTitle by remember {
        mutableStateOf("")
    }

    var devicePairCode by remember { mutableStateOf("") }
    var deviceClaimCode by remember { mutableStateOf("") }
    var deviceStatusMessage by remember { mutableStateOf("") }
    var deviceRequestBusy by remember { mutableStateOf(false) }
    val pairedDevices = remember { mutableStateListOf<JSONObject>() }
    var deviceListLoaded by remember { mutableStateOf(false) }
    var targetPaired by remember { mutableStateOf(false) }
    var wakeBusy by remember { mutableStateOf(false) }
    var wakeStatusMessage by remember { mutableStateOf("") }

    var prompt by remember {
        mutableStateOf("")
    }

    var isSending by remember {
        mutableStateOf(false)
    }

    var chatMessages by remember {
        mutableStateOf(
            readStringList(context, KEY_CHAT).ifEmpty {
                listOf(
                    "NEXUS AI වෙත සාදරයෙන් පිළිගනිමු! 💙"
                )
            }
        )
    }

    val chatState = rememberLazyListState()

    // SAVE CHAT

    LaunchedEffect(chatMessages) {

        writeStringList(
            context,
            KEY_CHAT,
            chatMessages
        )

        if (chatMessages.isNotEmpty()) {
            chatState.animateScrollToItem(
                chatMessages.lastIndex
            )
        }
    }

    LaunchedEffect(notes.toList()) {
        writeStringList(
            context,
            KEY_NOTES,
            notes.toList()
        )
    }

    LaunchedEffect(savedScripts.toList()) {
        writeStringList(
            context,
            KEY_SCRIPTS,
            savedScripts.toList()
        )
    }

    LaunchedEffect(memories.toList()) {
        writeStringList(
            context,
            KEY_MEMORIES,
            memories.toList()
        )
    }

    // THEME

    val colors = if (dark) {

        darkColorScheme(
            primary = NexusBlue,
            onPrimary = Color.White,
            secondary = NexusPurple,
            onSecondary = Color.White,
            tertiary = NexusCyan,
            background = NexusNavy,
            onBackground = Color.White,
            surface = NexusPanel,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1B2A4B),
            onSurfaceVariant = Color(0xFFB8C9E8),
            outline = Color(0xFF42577C)
        )

    } else {

        lightColorScheme(
            primary = Color(0xFF1769D2),
            onPrimary = Color.White,
            secondary = NexusPurple,
            tertiary = Color(0xFF008DA8),
            background = LightBackground,
            onBackground = InkBlue,
            surface = LightPanel,
            onSurface = InkBlue,
            surfaceVariant = Color(0xFFE2ECFF),
            onSurfaceVariant = Color(0xFF405675),
            outline = Color(0xFFB9C9E0)
        )
    }

    // VOICE INPUT

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->

        val spoken = result.data
            ?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )
            ?.firstOrNull()

        if (!spoken.isNullOrBlank()) {

            when (tab) {

                Tab.NOTES -> noteText = spoken

                Tab.MEMORY -> memoryText = spoken

                else -> prompt = spoken
            }
        }
    }

    // CAMERA

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {

        Toast.makeText(
            context,
            if (sinhala)
                "Camera වෙතින් ආපසු පැමිණියා."
            else
                "Returned from Camera.",
            Toast.LENGTH_SHORT
        ).show()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        Toast.makeText(
            context,
            if (uri != null) {
                if (sinhala) "Gallery ඡායාරූපය තෝරාගත්තා." else "Image selected from Gallery."
            } else {
                if (sinhala) "ඡායාරූපයක් තෝරා නැහැ." else "No image selected."
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    // COPY

    fun copyText(text: String) {

        val clipboard = context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "NEXUS AI",
                text
            )
        )

        Toast.makeText(
            context,
            if (sinhala) "Copy කළා." else "Copied.",
            Toast.LENGTH_SHORT
        ).show()
    }

    suspend fun deviceApiPost(
        route: String,
        payload: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (
            URL("https://nexus-ai-backend-six.vercel.app/$route")
                .openConnection() as HttpURLConnection
        )
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${NexusAuthManager.refreshAccessToken(context, accessToken)}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(response.ifBlank { "{}" })
            if (status !in 200..299) {
                throw IllegalStateException(json.optString("error", "HTTP $status"))
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun registerCurrentFcmToken() {
        try {
            val token = withContext(Dispatchers.IO) {
                Tasks.await(FirebaseMessaging.getInstance().token)
            }
            deviceApiPost(
                "devices/push/register",
                JSONObject()
                    .put("deviceId", getStableDeviceId(context))
                    .put("fcmToken", token)
            )
        } catch (error: Exception) {
            deviceStatusMessage = (error.message ?: "FCM token registration pending").take(120)
        }
    }

    suspend fun deviceApiGet(route: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (
            URL("https://nexus-ai-backend-six.vercel.app/$route")
                .openConnection() as HttpURLConnection
        )
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty(
                "Authorization",
                "Bearer ${NexusAuthManager.refreshAccessToken(context, accessToken)}"
            )
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(response.ifBlank { "{}" })
            if (status !in 200..299) {
                throw IllegalStateException(json.optString("error", "HTTP $status"))
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    LaunchedEffect(accessToken) {
        try {
            val result = deviceApiGet("devices")
            val list = result.optJSONArray("devices") ?: JSONArray()
            val ownId = getStableDeviceId(context)
            val ownDevice = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .firstOrNull { it.optString("device_id") == ownId && it.isNull("revoked_at") }
            if (BuildConfig.DEVICE_ROLE == "target") targetPaired = ownDevice?.optString("device_role") == "target"
            if (ownDevice != null) registerCurrentFcmToken()
        } catch (error: Exception) {
            deviceStatusMessage = (error.message ?: "Device status unavailable").take(120)
        }
    }

    LaunchedEffect(tab) {
        if (tab == Tab.TOOLS && !deviceListLoaded && !deviceRequestBusy) {
            deviceRequestBusy = true
            try {
                val result = deviceApiGet("devices")
                val list = result.optJSONArray("devices") ?: JSONArray()
                pairedDevices.clear()
                for (index in 0 until list.length()) {
                    pairedDevices.add(list.optJSONObject(index) ?: JSONObject())
                }
                deviceListLoaded = true
            } catch (e: Exception) {
                deviceStatusMessage = (e.message ?: "Could not load devices").take(120)
            } finally {
                deviceRequestBusy = false
            }
        }
    }

    // SAVE SCRIPT

    fun saveScript(text: String) {

        if (savedScripts.none { it == text }) {

            savedScripts.add(0, text)

            Toast.makeText(
                context,
                if (sinhala)
                    "පිළිතුර සුරැකුවා."
                else
                    "Script saved.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // SUGGEST MEMORY

    fun suggestMemory(text: String) {

        val clean = text
            .removePrefix("ඔබ: ")
            .removePrefix("You: ")
            .trim()

        if (
            clean.isNotBlank() &&
            memories.none { it == clean } &&
            pendingMemories.none { it == clean }
        ) {

            pendingMemories.add(clean)

            Toast.makeText(
                context,
                if (sinhala)
                    "මතක යෝජනාවක් සූදානම්. Memory Center එකෙන් අනුමත කරන්න."
                else
                    "Memory suggestion created. Approve it in Memory Center.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // APPROVE MEMORY

    fun approveMemory(text: String) {

        if (memories.none { it == text }) {

            if (memories.size >= 30) {

                Toast.makeText(
                    context,
                    if (sinhala)
                        "මතක සීමාව 30කි. පැරණි මතකයක් මකන්න."
                    else
                        "Memory limit reached. Delete an existing memory.",
                    Toast.LENGTH_LONG
                ).show()

                return
            }

            memories.add(0, text)

            Toast.makeText(
                context,
                if (sinhala)
                    "මතකය ආරක්ෂිතව සුරැකුවා."
                else
                    "Memory saved securely.",
                Toast.LENGTH_SHORT
            ).show()
        }

        pendingMemories.remove(text)
    }

    // SEND CHAT

    fun sendMessage() {

        val userText = prompt.trim()

        if (userText.isEmpty() || isSending) {
            return
        }

        // Capture previous history before adding current message.
        // This prevents sending the current message twice.

        val previousHistory = chatMessages.toList()
        val memorySnapshot = memories.toList()

        chatMessages = chatMessages +
            ((if (sinhala) "ඔබ: " else "You: ") + userText)

        prompt = ""
        isSending = true

        scope.launch {

            val reply = withContext(Dispatchers.IO) {

                var connection: HttpURLConnection? = null

                try {

                    connection =
                        (URL(BACKEND_URL)
                            .openConnection() as HttpURLConnection)
                            .apply {

                                requestMethod = "POST"
                                connectTimeout = 20000
                                readTimeout = 30000
                                doOutput = true

                                setRequestProperty(
                                    "Content-Type",
                                    "application/json; charset=utf-8"
                                )

                                setRequestProperty(
                                    "Accept",
                                    "application/json"
                                )

                                setRequestProperty(
                                    "Authorization",
                                    "Bearer ${NexusAuthManager.refreshAccessToken(context, accessToken)}"
                                )
                            }

                    val memoryArray = JSONArray()

                    memorySnapshot
                        .take(30)
                        .forEach {
                            memoryArray.put(it.take(500))
                        }

                    val historyArray = JSONArray()

                    previousHistory
                        .takeLast(12)
                        .forEach { item ->

                            val isUser =
                                item.startsWith("ඔබ: ") ||
                                    item.startsWith("You: ")

                            val text = item
                                .removePrefix("ඔබ: ")
                                .removePrefix("You: ")

                            historyArray.put(
                                JSONObject()
                                    .put(
                                        "role",
                                        if (isUser) "user" else "model"
                                    )
                                    .put("text", text)
                            )
                        }

                    val body = JSONObject()
                        .put("message", userText)
                        .put("memory", memoryArray)
                        .put("history", historyArray)
                        .toString()

                    connection.outputStream.use {
                        it.write(
                            body.toByteArray(Charsets.UTF_8)
                        )
                    }

                    val code = connection.responseCode

                    val stream = if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                    val response = stream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()

                    if (code in 200..299) {

                        try {

                            val json = JSONObject(response)

                            val answer = json.optString(
                                "reply",
                                json.optString(
                                    "response",
                                    ""
                                )
                            )

                            if (answer.isNotBlank()) {
                                answer
                            } else {
                                response.ifBlank {
                                    "AI වෙතින් හිස් පිළිතුරක් ලැබුණි."
                                }
                            }

                        } catch (_: Exception) {

                            response.ifBlank {
                                "AI වෙතින් වලංගු පිළිතුරක් ලැබුණේ නැහැ."
                            }
                        }

                    } else {

                        "AI සේවාවේ දෝෂයක්: $code\n$response"
                    }

                } catch (e: Exception) {

                    "සම්බන්ධතාවය අසාර්ථකයි: " +
                        (e.localizedMessage ?: "නොදන්නා දෝෂයක්")

                } finally {

                    connection?.disconnect()
                }
            }

            chatMessages = chatMessages + reply
            isSending = false
        }
    }

    // UI

    MaterialTheme(colorScheme = colors) {

        Scaffold(
            containerColor = Color.Transparent,

            topBar = {

                TopAppBar(

                    title = {

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            NexusLogo(46)

                            Column {

                                Text(
                                    "NEXUS AI",
                                    fontWeight = FontWeight.Black,
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Text(
                                    if (sinhala)
                                        "ඔබේ බුද්ධිමත් පුද්ගලික සහායකයා"
                                    else
                                        "Your Intelligent Personal Assistant",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },

                    actions = {

                        IconButton(
                            onClick = {
                                themeMode = if (dark) "light" else "dark"
                            }
                        ) {

                            Icon(
                                if (dark)
                                    Icons.Default.LightMode
                                else
                                    Icons.Default.DarkMode,
                                contentDescription = "Theme",
                                tint = NexusCyan
                            )
                        }
                    },

                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },

            bottomBar = {

                if (BuildConfig.DEVICE_ROLE == "controller") {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 12.dp
                ) {

                    NavigationBarItem(
                        selected = tab == Tab.CHAT,
                        onClick = { tab = Tab.CHAT },
                        icon = { Icon(Icons.Default.Chat, null) },
                        label = {
                            Text(if (sinhala) "චැට්" else "Chat", fontWeight = FontWeight.SemiBold)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NexusCyan,
                            selectedTextColor = NexusCyan,
                            indicatorColor = NexusBlue.copy(alpha = 0.22f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    NavigationBarItem(
                        selected = tab == Tab.NOTES,
                        onClick = { tab = Tab.NOTES },
                        icon = { Icon(Icons.Default.Notes, null) },
                        label = {
                            Text(if (sinhala) "සටහන්" else "Notes", fontWeight = FontWeight.SemiBold)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NexusCyan,
                            selectedTextColor = NexusCyan,
                            indicatorColor = NexusBlue.copy(alpha = 0.22f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    NavigationBarItem(
                        selected = tab == Tab.SAVED,
                        onClick = { tab = Tab.SAVED },
                        icon = { Icon(Icons.Default.Bookmark, null) },
                        label = {
                            Text(if (sinhala) "සුරැකි" else "Saved", fontWeight = FontWeight.SemiBold)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NexusCyan,
                            selectedTextColor = NexusCyan,
                            indicatorColor = NexusBlue.copy(alpha = 0.22f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    NavigationBarItem(
                        selected = tab == Tab.MEMORY,
                        onClick = { tab = Tab.MEMORY },
                        icon = { Icon(Icons.Default.Psychology, null) },
                        label = {
                            Text(if (sinhala) "මතකය" else "Memory", fontWeight = FontWeight.SemiBold)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NexusCyan,
                            selectedTextColor = NexusCyan,
                            indicatorColor = NexusBlue.copy(alpha = 0.22f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    NavigationBarItem(
                        selected = tab == Tab.TOOLS,
                        onClick = { tab = Tab.TOOLS },
                        icon = { Icon(Icons.Default.Build, null) },
                        label = {
                            Text(if (sinhala) "මෙවලම්" else "Tools", fontWeight = FontWeight.SemiBold)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NexusCyan,
                            selectedTextColor = NexusCyan,
                            indicatorColor = NexusBlue.copy(alpha = 0.22f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = {
                            Text(if (sinhala) "සැකසුම්" else "Settings", fontWeight = FontWeight.SemiBold)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NexusCyan,
                            selectedTextColor = NexusCyan,
                            indicatorColor = NexusBlue.copy(alpha = 0.22f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                }
            }

        ) { padding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            if (dark) {

                                listOf(
                                    Color(0xFF070B1D),
                                    Color(0xFF142451),
                                    Color(0xFF25134B),
                                    Color(0xFF070B1D)
                                )

                            } else {

                                listOf(
                                    Color(0xFFF1F5FF),
                                    Color(0xFFDCE8FF),
                                    Color(0xFFF8F5FF)
                                )
                            }
                        )
                    )
                    .padding(padding)
                    .padding(
                        horizontal = 14.dp,
                        vertical = 12.dp
                    )
                    .then(
                        if (tab == Tab.CHAT) Modifier
                        else Modifier.verticalScroll(rememberScrollState())
                    )
            ) {

                when (tab) {

                    // CHAT

                    Tab.CHAT -> {

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Transparent
                            ),
                            elevation = CardDefaults.cardElevation(8.dp)
                        ) {

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                NexusBlue.copy(alpha = if (dark) 0.30f else 0.16f),
                                                NexusPurple.copy(alpha = if (dark) 0.24f else 0.12f),
                                                NexusCyan.copy(alpha = if (dark) 0.12f else 0.10f)
                                            )
                                        )
                                    )
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                NexusLogo(54)

                                Spacer(Modifier.width(14.dp))

                                Column {

                                    Text(
                                        if (sinhala)
                                            "NEXUS AI සහායකයා"
                                        else
                                            "NEXUS AI Assistant",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleLarge
                                    )

                                    Spacer(Modifier.height(4.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {

                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF39D98A))
                                        )

                                        Spacer(Modifier.width(6.dp))

                                        Text(
                                            if (sinhala)
                                                "ඔබ වෙනුවෙන් සූදානම්"
                                            else
                                                "Ready to assist",
                                            color = NexusCyan,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            state = chatState
                        ) {

                            itemsIndexed(chatMessages) { index, message ->

                                val isUser =
                                    message.startsWith("ඔබ: ") ||
                                        message.startsWith("You: ")

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isUser)
                                        Arrangement.End
                                    else
                                        Arrangement.Start
                                ) {

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth(0.91f)
                                            .padding(vertical = 5.dp),

                                        shape = RoundedCornerShape(
                                            topStart = 18.dp,
                                            topEnd = 18.dp,
                                            bottomStart = if (isUser) 18.dp else 4.dp,
                                            bottomEnd = if (isUser) 4.dp else 18.dp
                                        ),

                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isUser)
                                                Color(0xFF244E91)
                                            else
                                                MaterialTheme.colorScheme.surface
                                        ),

                                        elevation = CardDefaults.cardElevation(2.dp)
                                    ) {

                                        Column(
                                            Modifier.padding(14.dp)
                                        ) {

                                            Text(
                                                message,
                                                color = if (isUser || dark)
                                                    Color.White
                                                else
                                                    InkBlue
                                            )

                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {

                                                if (isUser) {

                                                    TextButton(
                                                        onClick = {
                                                            suggestMemory(message)
                                                        }
                                                    ) {

                                                        Icon(
                                                            Icons.Default.Lightbulb,
                                                            null,
                                                            tint = NexusCyan
                                                        )

                                                        Text(
                                                            if (sinhala)
                                                                "මතකයට යෝජනා කරන්න"
                                                            else
                                                                "Suggest Memory"
                                                        )
                                                    }

                                                } else if (index != 0) {

                                                    TextButton(
                                                        onClick = {
                                                            copyText(message)
                                                        }
                                                    ) {

                                                        Icon(
                                                            Icons.Default.ContentCopy,
                                                            null,
                                                            tint = NexusCyan
                                                        )

                                                        Text("Copy")
                                                    }

                                                    TextButton(
                                                        onClick = {
                                                            saveScript(message)
                                                        }
                                                    ) {

                                                        Icon(
                                                            Icons.Default.BookmarkAdd,
                                                            null,
                                                            tint = NexusCyan
                                                        )

                                                        Text(
                                                            if (sinhala)
                                                                "සුරකින්න"
                                                            else
                                                                "Save"
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = prompt,
                            onValueChange = {
                                prompt = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    if (sinhala)
                                        "NEXUS වෙත පණිවිඩයක් ලියන්න..."
                                    else
                                        "Message NEXUS..."
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                            trailingIcon = {

                                IconButton(
                                    onClick = {

                                        try {

                                            val intent = Intent(
                                                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                                            ).apply {

                                                putExtra(
                                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                                )

                                                putExtra(
                                                    RecognizerIntent.EXTRA_LANGUAGE,
                                                    if (sinhala) "si-LK" else "en-US"
                                                )
                                            }

                                            voiceLauncher.launch(intent)

                                        } catch (_: Exception) {

                                            Toast.makeText(
                                                context,
                                                "Voice input unavailable.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.Mic,
                                        null,
                                        tint = NexusCyan
                                    )
                                }
                            }
                        )

                        Spacer(Modifier.height(9.dp))

                        Button(
                            onClick = {
                                sendMessage()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        if (prompt.isNotBlank() && !isSending)
                                            listOf(NexusBlue, NexusPurple, NexusCyan)
                                        else
                                            listOf(
                                                NexusBlue.copy(alpha = 0.38f),
                                                NexusPurple.copy(alpha = 0.38f)
                                            )
                                    ),
                                    RoundedCornerShape(18.dp)
                                ),
                            enabled = prompt.isNotBlank() && !isSending,
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.White,
                                disabledContainerColor = Color.Transparent,
                                disabledContentColor = Color.White.copy(alpha = 0.75f)
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp
                            )
                        ) {

                            if (isSending) {

                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )

                                Spacer(Modifier.width(8.dp))
                            }

                            Icon(Icons.Default.Send, null)

                            Spacer(Modifier.width(8.dp))

                            Text(
                                if (sinhala) "යවන්න" else "Send",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // NOTES

                    Tab.NOTES -> {

                        ScreenTitle(
                            if (sinhala) "මගේ සටහන්" else "My Notes"
                        )

                        OutlinedTextField(
                            value = noteText,
                            onValueChange = {
                                noteText = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    if (sinhala)
                                        "ඔබේ සටහන මෙහි ලියන්න..."
                                    else
                                        "Write your note..."
                                )
                            },
                            minLines = 3,
                            shape = RoundedCornerShape(18.dp)
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {

                                if (noteText.isNotBlank()) {

                                    notes.add(0, noteText.trim())
                                    noteText = ""

                                    Toast.makeText(
                                        context,
                                        "සටහන සුරැකුවා.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            enabled = noteText.isNotBlank(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexusPurple,
                                contentColor = Color.White,
                                disabledContainerColor = NexusPurple.copy(alpha = 0.35f)
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 4.dp,
                                pressedElevation = 1.dp
                            )
                        ) {

                            Icon(Icons.Default.Save, null)

                            Spacer(Modifier.width(8.dp))

                            Text(
                                if (sinhala) "සුරකින්න" else "Save Note"
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            Modifier.weight(1f)
                        ) {

                            itemsIndexed(notes) { index, note ->

                                Card(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {

                                    Column(
                                        Modifier.padding(14.dp)
                                    ) {

                                        Text(note)

                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {

                                            TextButton(
                                                onClick = {
                                                    copyText(note)
                                                }
                                            ) {
                                                Text("Copy")
                                            }

                                            TextButton(
                                                onClick = {
                                                    notes.removeAt(index)
                                                }
                                            ) {
                                                Text(
                                                    if (sinhala)
                                                        "මකන්න"
                                                    else
                                                        "Delete"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SAVED SCRIPTS

                    Tab.SAVED -> {

                        ScreenTitle(
                            if (sinhala)
                                "සුරැකි පිටපත්"
                            else
                                "Saved Scripts"
                        )

                        if (savedScripts.isEmpty()) {

                            EmptyState(
                                Icons.Default.BookmarkBorder,
                                if (sinhala)
                                    "තවමත් කිසිවක් සුරැකී නැහැ."
                                else
                                    "Nothing saved yet."
                            )

                        } else {

                            LazyColumn(
                                Modifier.weight(1f)
                            ) {

                                itemsIndexed(savedScripts) { index, script ->

                                    Card(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp),
                                        shape = RoundedCornerShape(18.dp)
                                    ) {

                                        Column(
                                            Modifier.padding(14.dp)
                                        ) {

                                            Text(script)

                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {

                                                TextButton(
                                                    onClick = {
                                                        copyText(script)
                                                    }
                                                ) {
                                                    Text("Copy")
                                                }

                                                TextButton(
                                                    onClick = {

                                                        notes.add(0, script)
                                                        tab = Tab.NOTES

                                                        Toast.makeText(
                                                            context,
                                                            "සටහන් වෙත එක් කළා.",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                ) {

                                                    Text(
                                                        if (sinhala)
                                                            "සටහන්"
                                                        else
                                                            "To Notes"
                                                    )
                                                }

                                                TextButton(
                                                    onClick = {
                                                        savedScripts.removeAt(index)
                                                    }
                                                ) {

                                                    Text(
                                                        if (sinhala)
                                                            "මකන්න"
                                                        else
                                                            "Delete"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SMART MEMORY CENTER

                    Tab.MEMORY -> {

                        ScreenTitle("NEXUS Smart Memory")

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp)
                        ) {

                            Column(
                                Modifier.padding(16.dp)
                            ) {

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Icon(
                                        Icons.Default.Psychology,
                                        null,
                                        tint = NexusCyan,
                                        modifier = Modifier.size(30.dp)
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Text(
                                        if (sinhala)
                                            "ඔබේ පුද්ගලික මතක මධ්‍යස්ථානය"
                                        else
                                            "Your Personal Memory Center",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    if (sinhala)
                                        "ඔබ අනුමත කරන තොරතුරු පමණක් මතකයට සුරැකේ. ඒවා Chat අතරතුර AI වෙත යවනු ලැබේ."
                                    else
                                        "Only information you approve is saved. Approved memories are sent to the AI during chats.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Spacer(Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = memoryText,
                                    onValueChange = {
                                        memoryText = it
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(
                                            if (sinhala)
                                                "NEXUS මතක තබාගත යුතු දෙයක් ලියන්න..."
                                            else
                                                "Write something for NEXUS to remember..."
                                        )
                                    },
                                    minLines = 2,
                                    shape = RoundedCornerShape(16.dp)
                                )

                                Spacer(Modifier.height(8.dp))

                                Button(
                                    onClick = {

                                        val value = memoryText.trim()

                                        if (value.isNotBlank()) {

                                            if (memories.none { it == value }) {

                                                if (memories.size >= 30) {

                                                    Toast.makeText(
                                                        context,
                                                        "Memory limit reached.",
                                                        Toast.LENGTH_LONG
                                                    ).show()

                                                    return@Button
                                                }

                                                memories.add(0, value)
                                            }

                                            memoryText = ""

                                            Toast.makeText(
                                                context,
                                                if (sinhala)
                                                    "මතකයට සුරැකුවා."
                                                else
                                                    "Memory saved.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    enabled = memoryText.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NexusBlue,
                                        contentColor = Color.White,
                                        disabledContainerColor = NexusBlue.copy(alpha = 0.35f)
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(
                                        defaultElevation = 4.dp,
                                        pressedElevation = 1.dp
                                    )
                                ) {

                                    Icon(Icons.Default.Add, null)

                                    Spacer(Modifier.width(8.dp))

                                    Text(
                                        if (sinhala)
                                            "මතකයට සුරකින්න"
                                        else
                                            "Save Memory"
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        if (pendingMemories.isNotEmpty()) {

                            Text(
                                if (sinhala)
                                    "අනුමැතිය බලාපොරොත්තු වන යෝජනා"
                                else
                                    "Pending Memory Suggestions",
                                fontWeight = FontWeight.Bold,
                                color = NexusCyan
                            )

                            Spacer(Modifier.height(6.dp))

                            LazyColumn(
                                modifier = Modifier.heightIn(max = 220.dp)
                            ) {

                                itemsIndexed(
                                    pendingMemories.toList()
                                ) { _, suggestion ->

                                    Card(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {

                                        Column(
                                            Modifier.padding(12.dp)
                                        ) {

                                            Text(suggestion)

                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {

                                                TextButton(
                                                    onClick = {
                                                        pendingMemories.remove(suggestion)
                                                    }
                                                ) {

                                                    Text(
                                                        if (sinhala)
                                                            "ප්‍රතික්ෂේප කරන්න"
                                                        else
                                                            "Reject"
                                                    )
                                                }

                                                TextButton(
                                                    onClick = {
                                                        approveMemory(suggestion)
                                                    }
                                                ) {

                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        null,
                                                        tint = Color(0xFF39D98A)
                                                    )

                                                    Text(
                                                        if (sinhala)
                                                            "අනුමත කරන්න"
                                                        else
                                                            "Approve"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                if (sinhala)
                                    "සුරැකි මතකයන්"
                                else
                                    "Saved Memories",
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.weight(1f))

                            Text(
                                "${memories.size}/30",
                                color = NexusCyan
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        if (memories.isEmpty()) {

                            EmptyState(
                                Icons.Default.Psychology,
                                if (sinhala)
                                    "තවමත් මතකයන් නැහැ."
                                else
                                    "No memories saved yet."
                            )

                        } else {

                            LazyColumn(
                                Modifier.weight(1f)
                            ) {

                                itemsIndexed(
                                    memories.toList()
                                ) { index, memory ->

                                    Card(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp),
                                        shape = RoundedCornerShape(17.dp)
                                    ) {

                                        Column(
                                            Modifier.padding(13.dp)
                                        ) {

                                            Row(
                                                verticalAlignment = Alignment.Top
                                            ) {

                                                Icon(
                                                    Icons.Default.Lightbulb,
                                                    null,
                                                    tint = NexusCyan
                                                )

                                                Spacer(Modifier.width(8.dp))

                                                Text(
                                                    memory,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }

                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {

                                                TextButton(
                                                    onClick = {
                                                        copyText(memory)
                                                    }
                                                ) {
                                                    Text("Copy")
                                                }

                                                TextButton(
                                                    onClick = {

                                                        memoryText = memory
                                                        memories.removeAt(index)
                                                    }
                                                ) {

                                                    Text(
                                                        if (sinhala)
                                                            "සංස්කරණය"
                                                        else
                                                            "Edit"
                                                    )
                                                }

                                                TextButton(
                                                    onClick = {
                                                        memories.removeAt(index)
                                                    }
                                                ) {

                                                    Text(
                                                        if (sinhala)
                                                            "මකන්න"
                                                        else
                                                            "Delete"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // TOOLS

                    Tab.TOOLS -> {
                        ScreenTitle(
                            if (sinhala) "Target දුරකථනය සම්බන්ධ කිරීම" else "Connect Target Phone"
                        )
                        if (BuildConfig.DEVICE_ROLE == "target") {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Default.PhoneAndroid, null, tint = NexusCyan, modifier = Modifier.size(42.dp))
                                    Text(
                                        if (targetPaired) {
                                            if (sinhala) "Target දුරකථනය සාර්ථකව සම්බන්ධයි" else "Target phone is paired"
                                        } else {
                                            if (sinhala) "Controller දුරකථනයේ පෙන්වන ඉලක්කම් 8ක Pairing Number එක ඇතුළත් කරන්න." else "Enter the 8-digit pairing number shown on the Controller phone."
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!targetPaired) {
                                        OutlinedTextField(
                                            value = deviceClaimCode,
                                            onValueChange = { deviceClaimCode = it.filter(Char::isDigit).take(8) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            label = { Text("Pairing Number") }
                                        )
                                        Button(
                                            enabled = !deviceRequestBusy && deviceClaimCode.length == 8,
                                            onClick = {
                                                scope.launch {
                                                    deviceRequestBusy = true
                                                    try {
                                                        deviceApiPost(
                                                            "devices/pair/claim",
                                                            JSONObject().put("pairingCode", deviceClaimCode)
                                                                .put("deviceName", android.os.Build.MODEL)
                                                                .put("deviceId", getStableDeviceId(context))
                                                        )
                                                        deviceClaimCode = ""
                                                        targetPaired = true
                                                        registerCurrentFcmToken()
                                                        deviceStatusMessage = if (sinhala) "Pairing සාර්ථකයි. Target සූදානම්." else "Pairing successful. Target is ready."
                                                    } catch (error: Exception) {
                                                        deviceStatusMessage = (error.message ?: "Pairing failed").take(120)
                                                    } finally {
                                                        deviceRequestBusy = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (deviceRequestBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                            else Text(if (sinhala) "Pair කරන්න" else "Pair device")
                                        }
                                    }
                                    if (deviceStatusMessage.isNotBlank()) {
                                        Text(deviceStatusMessage, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        } else {
                        OutlinedButton(
                            onClick = { tab = Tab.PROGRESS },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Checklist, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (sinhala) "ව්‍යාපෘති ප්‍රගතිය සහ වැඩ සැලැස්ම" else "Project Progress & Roadmap")
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null)
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Devices, null, tint = NexusCyan)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (sinhala) "NEXUS Device Link" else "NEXUS Device Link",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    if (sinhala)
                                        "දෙවන දුරකථනයේත් එකම NEXUS ගිණුමෙන් ඇතුළු වී Pair කිරීමේ කේතය භාවිත කරන්න. කේතය මිනිත්තු 5කින් කල් ඉකුත් වේ."
                                    else
                                        "Sign in with the same NEXUS account on your second phone and use the pairing code. Codes expire after 5 minutes.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    enabled = !deviceRequestBusy,
                                    onClick = {
                                        scope.launch {
                                            deviceRequestBusy = true
                                            try {
                                                val result = deviceApiPost(
                                                    "devices/pair/start",
                                                    JSONObject()
                                                        .put("deviceName", android.os.Build.MODEL)
                                                        .put("deviceId", getStableDeviceId(context))
                                                )
                                                devicePairCode = result.optString("pairingCode")
                                                registerCurrentFcmToken()
                                                deviceStatusMessage = if (sinhala) "කේතය මිනිත්තු 5ක් වලංගුයි." else "Code is valid for 5 minutes."
                                            } catch (e: Exception) {
                                                deviceStatusMessage = (e.message ?: "Pairing failed").take(120)
                                            } finally {
                                                deviceRequestBusy = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.QrCode, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (sinhala) "Pairing කේතයක් ලබාගන්න" else "Generate pairing code")
                                }
                                if (devicePairCode.isNotBlank()) {
                                    Text(
                                        devicePairCode,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = NexusCyan
                                    )
                                    TextButton(onClick = { copyText(devicePairCode) }) {
                                        Text(if (sinhala) "කේතය Copy කරන්න" else "Copy code")
                                    }
                                }
                                OutlinedTextField(
                                    value = deviceClaimCode,
                                    onValueChange = { deviceClaimCode = it.filter(Char::isDigit).take(8) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(if (sinhala) "අනෙක් දුරකථනයේ Pairing කේතය" else "Pairing code from other phone") }
                                )
                                OutlinedButton(
                                    enabled = !deviceRequestBusy && deviceClaimCode.length == 8,
                                    onClick = {
                                        scope.launch {
                                            deviceRequestBusy = true
                                            try {
                                                deviceApiPost(
                                                    "devices/pair/claim",
                                                    JSONObject()
                                                        .put("pairingCode", deviceClaimCode)
                                                        .put("deviceName", android.os.Build.MODEL)
                                                        .put("deviceId", getStableDeviceId(context))
                                                )
                                                deviceClaimCode = ""
                                                registerCurrentFcmToken()
                                                deviceStatusMessage = if (sinhala) "දුරකථනය සාර්ථකව Pair කළා." else "Phone paired successfully."
                                            } catch (e: Exception) {
                                                deviceStatusMessage = (e.message ?: "Pairing failed").take(120)
                                            } finally {
                                                deviceRequestBusy = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(if (sinhala) "මෙම දුරකථනය Pair කරන්න" else "Pair this phone")
                                }
                                if (deviceStatusMessage.isNotBlank()) {
                                    Text(deviceStatusMessage, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Devices, null, tint = NexusCyan)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (sinhala) "සම්බන්ධ කළ දුරකථන" else "Paired devices",
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        enabled = !deviceRequestBusy,
                                        onClick = {
                                            scope.launch {
                                                deviceRequestBusy = true
                                                try {
                                                    val result = deviceApiGet("devices")
                                                    val list = result.optJSONArray("devices") ?: JSONArray()
                                                    pairedDevices.clear()
                                                    for (index in 0 until list.length()) {
                                                        pairedDevices.add(list.optJSONObject(index) ?: JSONObject())
                                                    }
                                                    deviceListLoaded = true
                                                    deviceStatusMessage = if (sinhala) "දුරකථන ලැයිස්තුව යාවත්කාලීන කළා." else "Device list refreshed."
                                                } catch (e: Exception) {
                                                    deviceStatusMessage = (e.message ?: "Could not load devices").take(120)
                                                } finally {
                                                    deviceRequestBusy = false
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Refresh, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (sinhala) "Refresh" else "Refresh")
                                    }
                                }
                                if (!deviceListLoaded) {
                                    Text(
                                        if (sinhala) "ඔබගේ සම්බන්ධ කළ දුරකථන බැලීමට Refresh ඔබන්න." else "Tap Refresh to load devices linked to your account.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                } else if (pairedDevices.isEmpty()) {
                                    Text(
                                        if (sinhala) "තවම සම්බන්ධ කළ දුරකථන නැහැ." else "No paired devices found.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                } else {
                                    val uniqueDevices = pairedDevices
                                        .groupBy { device ->
                                            val stableId = device.optString("device_id").ifBlank {
                                                device.optString("deviceId")
                                            }
                                            if (stableId.isNotBlank() && stableId != "null") {
                                                "device:" + stableId
                                            } else {
                                                // Legacy server records have no stable installation ID.
                                                // Keep their old grouping until the backend migration is deployed.
                                                device.optString("device_name", "NEXUS Android").trim().lowercase() +
                                                    "|" + device.optString("platform", "android").lowercase()
                                            }
                                        }
                                        .values
                                        .map { duplicates -> duplicates.maxByOrNull { it.optString("created_at") } ?: duplicates.first() }
                                    uniqueDevices.forEach { device ->
                                        val revoked = !device.isNull("revoked_at") && !device.optString("revoked_at").equals("null", true)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Smartphone, null, tint = NexusCyan)
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(device.optString("device_name", "NEXUS Android"), fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    device.optString("platform", "Android") +
                                                        if (revoked) " • " + (if (sinhala) "අවලංගුයි" else "Revoked")
                                                        else " • " + (if (sinhala) "සම්බන්ධයි" else "Paired"),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                                )
                                                val lastSeen = device.optString("last_seen_at", "")
                                                if (lastSeen.isNotBlank() && lastSeen != "null") {
                                                    Text((if (sinhala) "අවසන් වරට: " else "Last seen: ") + lastSeen,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.secondary)
                                                }
                                            }
                                            Icon(if (revoked) Icons.Default.Block else Icons.Default.CheckCircle, null,
                                                tint = if (revoked) MaterialTheme.colorScheme.error else Color(0xFF35B779))
                                        }
                                    }
                                    if (pairedDevices.size > uniqueDevices.size) {
                                        Text(
                                            if (sinhala) "එකම නම සහිත අනුපිටපත් ${pairedDevices.size - uniqueDevices.size}ක් සඟවා ඇත." else "${pairedDevices.size - uniqueDevices.size} duplicate entries with the same device name were grouped.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                Text(if (sinhala) "දුරස්ථ පහසුකම්" else "Remote features", fontWeight = FontWeight.Bold)
                                val remoteFeatures = listOf(
                                    Triple(Icons.Default.Videocam, if (sinhala) "කැමරා" else "Camera", "camera"),
                                    Triple(Icons.Default.Mic, if (sinhala) "මයික්" else "Microphone", "microphone"),
                                    Triple(Icons.Default.PhotoLibrary, if (sinhala) "ගැලරිය" else "Gallery", "gallery"),
                                    Triple(Icons.Default.LocationOn, if (sinhala) "ස්ථානය" else "Location", "location")
                                )
                                remoteFeatures.chunked(2).forEach { rowFeatures ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        rowFeatures.forEach { feature ->
                                            OutlinedButton(
                                                onClick = { deviceStatusMessage = if (sinhala) "මෙම පහසුකම තවම සකස් කරමින් පවතී." else "${feature.second} remote access is not available yet." },
                                                enabled = false,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(feature.first, null)
                                                Spacer(Modifier.width(6.dp))
                                                Text(feature.second)
                                            }
                                        }
                                        if (rowFeatures.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                                Text(
                                    if (sinhala) "මෙය සම්බන්ධ කළ උපාංග ලැයිස්තුවයි. Remote Screen පාලනය තවම සක්‍රිය කර නැත." else "This is your paired-device list. Remote screen control is not enabled yet.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        OutlinedTextField(
                            value = calendarEventTitle,
                            onValueChange = { calendarEventTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = {
                                Text(if (sinhala) "සිදුවීමේ නම" else "Event title")
                            },
                            placeholder = {
                                Text(if (sinhala) "උදා: වෛද්‍ය හමුව" else "e.g. Doctor appointment")
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        ToolCard(
                            title = if (sinhala)
                                "දිනදර්ශන සිදුවීමක් එක් කරන්න"
                            else
                                "Add Calendar Event",

                            description = if (sinhala)
                                "දුරකථනයේ Calendar එකට නව සිදුවීමක් එක් කරන්න."
                            else
                                "Create an event in your phone's Calendar.",

                            icon = Icons.Default.CalendarMonth
                        ) {

                            try {
                                val start = System.currentTimeMillis() + 60 * 60 * 1000
                                val end = start + 60 * 60 * 1000

                                val calendarIntent = Intent(Intent.ACTION_INSERT).apply {
                                    setDataAndType(
                                        CalendarContract.Events.CONTENT_URI,
                                        "vnd.android.cursor.item/event"
                                    )
                                    putExtra(
                                        CalendarContract.Events.TITLE,
                                        calendarEventTitle.trim().ifBlank { "NEXUS AI Event" }
                                    )
                                    putExtra(
                                        CalendarContract.Events.DESCRIPTION,
                                        "Added using NEXUS AI."
                                    )
                                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                                }

                                Toast.makeText(
                                    context,
                                    if (sinhala) "Calendar Event පෝරමය විවෘත කරමින්..." else "Opening Calendar event form...",
                                    Toast.LENGTH_SHORT
                                ).show()

                                // Launch directly; package visibility can hide resolveActivity results.
                                context.startActivity(
                                    Intent.createChooser(
                                        calendarIntent,
                                        if (sinhala) "Calendar යෙදුම තෝරන්න" else "Choose Calendar app"
                                    )
                                )
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(
                                    context,
                                    if (sinhala) "Calendar යෙදුමක් හමු වුණේ නැහැ." else "No compatible Calendar app found.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    if (sinhala) "Calendar දෝෂයක් ඇති වුණා. නැවත උත්සාහ කරන්න." else "Calendar error. Please try again.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        ToolCard(
                            title = "Camera",

                            description = if (sinhala)
                                "දුරකථනයේ Camera යෙදුම විවෘත කරන්න."
                            else
                                "Open your phone's Camera.",

                            icon = Icons.Default.CameraAlt
                        ) {

                            try {

                                cameraLauncher.launch(
                                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                                )

                            } catch (_: Exception) {

                                Toast.makeText(
                                    context,
                                    "Camera unavailable.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        ToolCard(
                            title = if (sinhala) "Gallery වෙතින් ඡායාරූපයක් තෝරන්න" else "Choose from Gallery",
                            description = if (sinhala) "දුරකථනයේ Gallery එකෙන් ඡායාරූපයක් තෝරන්න." else "Select an image from your phone's Gallery.",
                            icon = Icons.Default.PhotoLibrary
                        ) {
                            try {
                                galleryLauncher.launch("image/*")
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    if (sinhala) "Gallery විවෘත කළ නොහැක." else "Gallery is unavailable.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    // SETTINGS

                        }
                    }

                    Tab.PROGRESS -> {
                        val roadmap = listOf(
                            if (sinhala) "Stable Device ID සහ Duplicate ගැටලුව ස්ථිරව විසඳීම" else "Stable Device ID and permanent duplicate fix",
                            if (sinhala) "Camera සහ Microphone වෙන වෙනම පාලනය" else "Independent Camera and Microphone controls",
                            if (sinhala) "Background Connection Service" else "Background Connection Service",
                            if (sinhala) "Wi-Fi සහ Mobile Data Auto Reconnection" else "Wi-Fi and Mobile Data auto-reconnection",
                            if (sinhala) "Remote Camera – Front / Back / Live Preview / Photo" else "Remote Camera – Front / Back / Live Preview / Photo",
                            if (sinhala) "Remote Microphone – වෙනම Audio Session" else "Remote Microphone – independent audio session",
                            if (sinhala) "Screen Sharing සහ Remote Control" else "Screen Sharing and Remote Control",
                            if (sinhala) "Gallery සහ Files Access" else "Gallery and Files Access",
                            if (sinhala) "Location සහ Notifications" else "Location and Notifications",
                            if (sinhala) "Security, Permissions සහ සම්පූර්ණ පරීක්ෂණ" else "Security, Permissions and full testing"
                        )
                        val implementationStatus = listOf(
                            Triple(if (sinhala) "Device Pairing සහ Backend" else "Device Pairing and Backend", if (sinhala) "පවතිනවා" else "Present", true),
                            Triple(if (sinhala) "Tools / Paired Devices Dashboard" else "Tools / Paired Devices Dashboard", if (sinhala) "මූලික UI එක පවතී" else "Basic UI present", true),
                            Triple(if (sinhala) "Stable Device ID සහ Duplicate නිවැරදි කිරීම" else "Stable Device ID and duplicate fix", if (sinhala) "Client ID එක එකතු කළා; Backend ඉතිරියි" else "Client ID added; backend pending", false),
                            Triple(if (sinhala) "Background Connection Service" else "Background Connection Service", if (sinhala) "Backend heartbeat පසු" else "After backend heartbeat", false),
                            Triple(if (sinhala) "Remote Camera සහ Photo Capture" else "Remote Camera and Photo Capture", if (sinhala) "තවම ක්‍රියාත්මක කර නැත" else "Not implemented yet", false),
                            Triple(if (sinhala) "Independent Remote Microphone" else "Independent Remote Microphone", if (sinhala) "තවම ක්‍රියාත්මක කර නැත" else "Not implemented yet", false),
                            Triple(if (sinhala) "Screen Sharing / Remote Control" else "Screen Sharing / Remote Control", if (sinhala) "සැලසුම් අදියර" else "Planned", false),
                            Triple(if (sinhala) "Gallery / Files / Location / Notifications" else "Gallery / Files / Location / Notifications", if (sinhala) "සැලසුම් අදියර" else "Planned", false)
                        )
                        val roadmapPrefs = remember { context.getSharedPreferences("nexus_roadmap_progress", Context.MODE_PRIVATE) }
                        val completed = remember {
                            mutableStateListOf<Int>().apply {
                                addAll(roadmapPrefs.getStringSet("completed_items", emptySet())
                                    .mapNotNull { it.toIntOrNull() }
                                    .filter { it in roadmap.indices })
                            }
                        }
                        ScreenTitle(if (sinhala) "NEXUS ව්‍යාපෘති ප්‍රගතිය" else "NEXUS Project Progress")
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(if (sinhala) "සැබෑ සංවර්ධන තත්ත්වය" else "Implementation Status", fontWeight = FontWeight.Bold)
                                implementationStatus.forEach { item ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Icon(
                                            if (item.third) Icons.Default.CheckCircle else Icons.Default.Schedule,
                                            null,
                                            tint = if (item.third) Color(0xFF35B779) else MaterialTheme.colorScheme.secondary
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(item.first, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Text(item.second, style = MaterialTheme.typography.labelSmall, color = if (item.third) Color(0xFF35B779) else MaterialTheme.colorScheme.secondary)
                                    }
                                }
                                Text(
                                    if (sinhala) "මෙම තත්ත්වයන් කේතය පරීක්ෂා කර සකස් කළ මූලික වාර්තාවකි; Build සහ දුරකථන පරීක්ෂණවලින් පසුව පමණක් අවසන් බව තහවුරු කරමු." else "This is a conservative code-review snapshot; final completion requires successful builds and device testing.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        Text(
                            if (sinhala) "අපගේ Remote Control වැඩ සැලැස්ම මෙතැනින් නිරීක්ෂණය කරන්න." else "Track the NEXUS Remote Control roadmap here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (sinhala) "සම්පූර්ණයි" else "Completed", fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text("${completed.size}/${roadmap.size}", color = NexusCyan, fontWeight = FontWeight.Bold)
                                }
                                LinearProgressIndicator(
                                    progress = { completed.size.toFloat() / roadmap.size.toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = NexusCyan
                                )
                                roadmap.forEachIndexed { index, item ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Checkbox(
                                            checked = index in completed,
                                            onCheckedChange = { checked ->
                                                if (checked && index !in completed) completed.add(index)
                                                else completed.remove(index)
                                                roadmapPrefs.edit()
                                                    .putStringSet("completed_items", completed.map { it.toString() }.toSet())
                                                    .apply()
                                            }
                                        )
                                        Text(item, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (index != roadmap.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                }
                                Text(
                                    if (sinhala) "ඔබගේ සලකුණු මෙම දුරකථනයේ ස්ථිරව සුරැකේ. ඒවා GitHub හි වැඩ අවසන් බව ස්වයංක්‍රීයව තහවුරු නොකරයි." else "Your checkmarks are saved on this device and do not automatically verify implementation status in GitHub.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        OutlinedButton(onClick = { tab = Tab.TOOLS }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ArrowBack, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (sinhala) "මෙවලම් වෙත ආපසු" else "Back to Tools")
                        }
                    }

                    Tab.SETTINGS -> {

                        ScreenTitle(
                            if (sinhala)
                                "NEXUS සැකසුම්"
                            else
                                "NEXUS Settings"
                        )

                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp)
                        ) {

                            Column(
                                Modifier.padding(18.dp)
                            ) {

                                // LANGUAGE

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Icon(
                                        Icons.Default.Language,
                                        null,
                                        tint = NexusCyan
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Text(
                                        if (sinhala)
                                            "භාෂාව"
                                        else
                                            "Language",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Text("සිංහල")

                                    Spacer(Modifier.width(10.dp))

                                    Switch(
                                        checked = sinhala,
                                        onCheckedChange = {
                                            sinhala = it
                                        }
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Text("English")
                                }

                                HorizontalDivider()

                                Spacer(Modifier.height(16.dp))

                                // APPEARANCE

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Icon(
                                        Icons.Default.Palette,
                                        null,
                                        tint = NexusCyan
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Text(
                                        if (sinhala)
                                            "පෙනුම"
                                        else
                                            "Appearance",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = themeMode == "system",
                                        onClick = { themeMode = "system" },
                                        label = {
                                            Text(if (sinhala) "පද්ධතිය" else "System")
                                        }
                                    )
                                    FilterChip(
                                        selected = themeMode == "light",
                                        onClick = { themeMode = "light" },
                                        label = {
                                            Text(if (sinhala) "ආලෝක" else "Light")
                                        }
                                    )
                                    FilterChip(
                                        selected = themeMode == "dark",
                                        onClick = { themeMode = "dark" },
                                        label = {
                                            Text(if (sinhala) "අඳුරු" else "Dark")
                                        }
                                    )
                                }

                                HorizontalDivider()

                                Spacer(Modifier.height(16.dp))

                                // NEXUS VOICE ENGINE

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Icon(
                                        if (voiceEngineEnabled)
                                            Icons.Default.Mic
                                        else
                                            Icons.Default.MicOff,
                                        null,
                                        tint = if (voiceEngineEnabled)
                                            Color(0xFF39D98A)
                                        else
                                            NexusCyan,
                                        modifier = Modifier.size(28.dp)
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Column(
                                        Modifier.weight(1f)
                                    ) {

                                        Text(
                                            "NEXUS Voice Engine",
                                            fontWeight = FontWeight.Bold
                                        )

                                        Text(
                                            if (voiceEngineEnabled) {
                                                if (sinhala)
                                                    "Voice Engine ක්‍රියාත්මකයි"
                                                else
                                                    "Voice Engine Active"
                                            } else {
                                                if (sinhala)
                                                    "Voice Engine අක්‍රියයි"
                                                else
                                                    "Voice Engine Disabled"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Switch(
                                        checked = voiceEngineEnabled,
                                        onCheckedChange = { enabled ->

                                            if (enabled) {
                                                startVoiceEngine()
                                            } else {
                                                stopVoiceEngine()
                                            }
                                        }
                                    )
                                }

                                if (voicePermissionDenied) {

                                    Text(
                                        if (sinhala)
                                            "Voice Engine භාවිතයට Microphone අවසරය ලබා දෙන්න."
                                        else
                                            "Microphone permission is required to use Voice Engine.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Text(
                                    if (sinhala)
                                        "Switch එක ON කළ විට NEXUS Voice Engine ආරම්භ වේ. OFF කළ විට එය නතර වේ."
                                    else
                                        "Turn ON to start the NEXUS Voice Engine. Turn OFF to stop it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                HorizontalDivider()

                                Spacer(Modifier.height(16.dp))

                                // DATA SECURITY

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Icon(
                                        Icons.Default.Security,
                                        null,
                                        tint = NexusCyan
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    Text(
                                        if (sinhala)
                                            "දත්ත ආරක්ෂාව"
                                        else
                                            "Data Security",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    if (sinhala)
                                        "Chat, Notes, Saved Scripts සහ Smart Memories AES-256-GCM Encryption මඟින් ආරක්ෂා කර ඇත. Encryption Key එක Android Keystore තුළ ගබඩා වේ."
                                    else
                                        "Chat, Notes, Saved Scripts and Smart Memories use AES-256-GCM encryption. The encryption key is stored in Android Keystore.",

                                    style = MaterialTheme.typography.bodyMedium,

                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(Modifier.height(18.dp))

                                // APP INFORMATION

                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    NexusBlue,
                                                    NexusPurple,
                                                    NexusCyan
                                                )
                                            )
                                        )
                                        .padding(16.dp)
                                ) {

                                    Column {

                                        Text(
                                            "NEXUS AI",
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            style = MaterialTheme.typography.titleLarge
                                        )

                                        Text(
                                            "Personal Intelligence • Secure Workspace",
                                            color = Color.White
                                        )

                                        Spacer(Modifier.height(5.dp))

                                        Text(
                                            "Designed for your digital life.",
                                            color = Color.White.copy(alpha = 0.85f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// REUSABLE COMPONENTS

@Composable
private fun NexusLogo(size: Int) {

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.34f).dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF07152F),
                        Color(0xFF173B79),
                        Color(0xFF4A2B83)
                    )
                )
            )
            .then(
                Modifier
                    .padding((size * 0.035f).dp)
            )
            .clip(RoundedCornerShape((size * 0.30f).dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(NexusCyan.copy(alpha = 0.20f), NexusBlue.copy(alpha = 0.10f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.94f)
                .border(
                    1.5.dp,
                    Brush.sweepGradient(
                        listOf(NexusCyan, NexusPurple, NexusBlue, NexusCyan)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.78f)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(NexusBlue, NexusPurple, NexusCyan)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "N",
                color = Color.White,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.headlineMedium
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding((size * 0.13f).dp)
                .size((size * 0.13f).dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun ScreenTitle(title: String) {

    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground
    )

    Spacer(Modifier.height(16.dp))
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    message: String
) {

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Icon(
                icon,
                null,
                tint = NexusCyan,
                modifier = Modifier.size(55.dp)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// CLICKABLE TOOL CARD

@Composable
private fun ToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),

        shape = RoundedCornerShape(20.dp),

        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),

        elevation = CardDefaults.cardElevation(4.dp)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(17.dp),

            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(NexusBlue, NexusPurple)
                        )
                    ),

                contentAlignment = Alignment.Center
            ) {

                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    title,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.ArrowForward,
                contentDescription = "Open",
                tint = NexusCyan
            )
        }
    }
}
