
package com.nexusai.app

import android.content.*
import android.os.Bundle
import android.speech.RecognizerIntent
import android.provider.MediaStore
import android.provider.CalendarContract
import android.widget.Toast
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

private val NexusNavy = Color(0xFF080F24)
private val NexusPanel = Color(0xFF121E3B)
private val NexusBlue = Color(0xFF398BFF)
private val NexusCyan = Color(0xFF50D8FF)
private val NexusPurple = Color(0xFF925CFF)
private val LightBackground = Color(0xFFF0F4FF)
private val LightPanel = Color.White
private val InkBlue = Color(0xFF142746)

private const val PREFS_NAME = "nexus_local_data"
private const val KEY_CHAT = "chat_messages"
private const val KEY_NOTES = "notes"
private const val KEY_SCRIPTS = "saved_scripts"
private const val KEY_ALIAS = "NEXUS_AI_ENCRYPTION_KEY"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexusApp() }
    }
}

// AES-256 Encryption using Android Keystore

private fun getEncryptionKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore")
    keyStore.load(null)

    val existing = keyStore.getKey(KEY_ALIAS, null)
    if (existing != null) return existing as SecretKey

    val generator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore"
    )

    val specification = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()

    generator.init(specification)
    return generator.generateKey()
}

private fun encryptData(value: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey())

    val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    val iv = cipher.iv

    return Base64.encodeToString(
        iv + encrypted,
        Base64.NO_WRAP
    )
}

private fun decryptData(value: String): String {
    val combined = Base64.decode(value, Base64.NO_WRAP)

    val iv = combined.copyOfRange(0, 12)
    val encrypted = combined.copyOfRange(12, combined.size)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        getEncryptionKey(),
        GCMParameterSpec(128, iv)
    )

    return String(cipher.doFinal(encrypted), Charsets.UTF_8)
}

private fun readStringList(
    context: Context,
    key: String,
    fallback: List<String> = emptyList()
): List<String> {
    val prefs = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val raw = prefs.getString(key, null) ?: return fallback

    return try {
        val decrypted = decryptData(raw)
        val array = JSONArray(decrypted)

        List(array.length()) { index ->
            array.optString(index)
        }
    } catch (_: Exception) {
        // Support migration of old, unencrypted local data.
        try {
            val array = JSONArray(raw)
            val oldData = List(array.length()) { index ->
                array.optString(index)
            }
            writeStringList(context, key, oldData)
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
        values.forEach { array.put(it) }

        val encrypted = encryptData(array.toString())

        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(key, encrypted)
            .apply()
    } catch (_: Exception) {
        // Do not silently replace encrypted data on encryption failure.
    }
}

private enum class Tab {
    CHAT, NOTES, SAVED, TOOLS, SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexusApp() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dark by remember { mutableStateOf(true) }
    var sinhala by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.CHAT) }

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

    var noteText by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    var chatMessages by remember {
        mutableStateOf(
            readStringList(context, KEY_CHAT).ifEmpty {
                listOf("NEXUS AI වෙත සාදරයෙන් පිළිගනිමු.")
            }
        )
    }

    LaunchedEffect(chatMessages) {
        writeStringList(context, KEY_CHAT, chatMessages)
    }

    LaunchedEffect(notes.toList()) {
        writeStringList(context, KEY_NOTES, notes.toList())
    }

    LaunchedEffect(savedScripts.toList()) {
        writeStringList(context, KEY_SCRIPTS, savedScripts.toList())
    }

    val colors = if (dark) {
        darkColorScheme(
            primary = NexusBlue,
            secondary = NexusPurple,
            tertiary = NexusCyan,
            background = NexusNavy,
            surface = NexusPanel,
            onSurface = Color.White,
            onBackground = Color.White,
            onSurfaceVariant = Color(0xFFB8C9E8),
            outline = Color(0xFF42577C)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1769D2),
            secondary = NexusPurple,
            tertiary = Color(0xFF008DA8),
            background = LightBackground,
            surface = LightPanel,
            onSurface = InkBlue,
            onBackground = InkBlue,
            onSurfaceVariant = Color(0xFF405675)
        )
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()

        if (!spoken.isNullOrBlank()) {
            if (tab == Tab.NOTES) {
                noteText = spoken
            } else {
                prompt = spoken
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Toast.makeText(
            context,
            if (sinhala) "Camera වෙතින් ආපසු පැමිණියා." else "Returned from Camera.",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun copyText(text: String) {
        val clipboard = context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText("NEXUS AI", text)
        )

        Toast.makeText(
            context,
            if (sinhala) "Copy කළා." else "Copied.",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun saveScript(text: String) {
        if (savedScripts.none { it == text }) {
            savedScripts.add(0, text)
            Toast.makeText(
                context,
                if (sinhala) "සුරැකුවා." else "Saved.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    MaterialTheme(colorScheme = colors) {

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,

            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            NexusLogo(44)

                            Column {
                                Text(
                                    "NEXUS AI",
                                    fontWeight = FontWeight.Black,
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Text(
                                    if (sinhala) "ඔබේ පුද්ගලික AI සහායකයා"
                                    else "Your Personal AI Assistant",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },

                    actions = {
                        IconButton(onClick = { dark = !dark }) {
                            Icon(
                                if (dark) Icons.Default.LightMode
                                else Icons.Default.DarkMode,
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
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    NavigationBarItem(
                        selected = tab == Tab.CHAT,
                        onClick = { tab = Tab.CHAT },
                        icon = { Icon(Icons.Default.Chat, null) },
                        label = { Text(if (sinhala) "චැට්" else "Chat") }
                    )

                    NavigationBarItem(
                        selected = tab == Tab.NOTES,
                        onClick = { tab = Tab.NOTES },
                        icon = { Icon(Icons.Default.Notes, null) },
                        label = { Text(if (sinhala) "සටහන්" else "Notes") }
                    )

                    NavigationBarItem(
                        selected = tab == Tab.SAVED,
                        onClick = { tab = Tab.SAVED },
                        icon = { Icon(Icons.Default.Bookmark, null) },
                        label = { Text(if (sinhala) "සුරැකි" else "Saved") }
                    )

                    NavigationBarItem(
                        selected = tab == Tab.TOOLS,
                        onClick = { tab = Tab.TOOLS },
                        icon = { Icon(Icons.Default.Build, null) },
                        label = { Text(if (sinhala) "මෙවලම්" else "Tools") }
                    )

                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text(if (sinhala) "සැකසුම්" else "Settings") }
                    )
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
                                    NexusNavy,
                                    Color(0xFF17264C),
                                    NexusNavy
                                )
                            } else {
                                listOf(
                                    LightBackground,
                                    Color(0xFFE1EAFF),
                                    LightBackground
                                )
                            }
                        )
                    )
                    .padding(padding)
                    .padding(16.dp)
            ) {

                when (tab) {

                    Tab.CHAT -> {

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(5.dp)
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NexusLogo(54)

                                Spacer(Modifier.width(14.dp))

                                Column {
                                    Text(
                                        if (sinhala) "NEXUS සහායකයා"
                                        else "NEXUS Assistant",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleLarge
                                    )

                                    Text(
                                        if (sinhala) "ඔබ වෙනුවෙන් සැමවිටම සූදානම්."
                                        else "Ready to assist you.",
                                        color = NexusCyan,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            itemsIndexed(chatMessages) { index, message ->

                                val isUser = message.startsWith("ඔබ: ") ||
                                        message.startsWith("You: ")

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
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
                                        else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(
                                        Modifier.padding(14.dp)
                                    ) {
                                        Text(message)

                                        if (!isUser && index != 0) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(
                                                    onClick = { copyText(message) }
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, null)
                                                    Text("Copy")
                                                }

                                                TextButton(
                                                    onClick = { saveScript(message) }
                                                ) {
                                                    Icon(Icons.Default.BookmarkAdd, null)
                                                    Text("Save")
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
                            onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    if (sinhala) "NEXUS වෙත පණිවිඩයක් ලියන්න..."
                                    else "Message NEXUS..."
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

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val userText = prompt.trim()

                                if (userText.isNotEmpty() && !isSending) {

                                    chatMessages = chatMessages +
                                            (if (sinhala) "ඔබ: " else "You: ") +
                                            userText

                                    prompt = ""
                                    isSending = true

                                    scope.launch {

                                        val reply = withContext(Dispatchers.IO) {
                                            try {
                                                val connection =
                                                    (URL("https://nexus-ai-backend-six.vercel.app/chat")
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
                                                        }

                                                val body = JSONObject()
                                                    .put("message", userText)
                                                    .toString()

                                                connection.outputStream.use {
                                                    it.write(body.toByteArray(Charsets.UTF_8))
                                                }

                                                val code = connection.responseCode

                                                val stream =
                                                    if (code in 200..299)
                                                        connection.inputStream
                                                    else connection.errorStream

                                                val response = stream
                                                    ?.bufferedReader()
                                                    ?.use { it.readText() }
                                                    .orEmpty()

                                                connection.disconnect()

                                                if (code in 200..299) {
                                                    val json = JSONObject(response)

                                                    json.optString(
                                                        "reply",
                                                        json.optString("response", response)
                                                    )
                                                } else {
                                                    "AI සේවාවේ දෝෂයක්: $code\n$response"
                                                }

                                            } catch (e: Exception) {
                                                "සම්බන්ධතාවය අසාර්ථකයි: ${e.localizedMessage}"
                                            }
                                        }

                                        chatMessages = chatMessages + reply
                                        isSending = false
                                    }
                                }
                            },

                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),

                            enabled = prompt.isNotBlank() && !isSending,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(8.dp))
                            }

                            Icon(Icons.Default.Send, null)
                            Spacer(Modifier.width(8.dp))

                            Text(if (sinhala) "යවන්න" else "Send")
                        }
                    }

                    Tab.NOTES -> {

                        Text(
                            if (sinhala) "මගේ සටහන්" else "My Notes",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(if (sinhala) "සටහන ලියන්න..." else "Write a note...")
                            },
                            minLines = 3,
                            shape = RoundedCornerShape(18.dp)
                        )

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
                            modifier = Modifier.fillMaxWidth(),
                            enabled = noteText.isNotBlank()
                        ) {
                            Icon(Icons.Default.Save, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (sinhala) "සුරකින්න" else "Save Note")
                        }

                        Spacer(Modifier.height(12.dp))

                        LazyColumn(Modifier.weight(1f)) {
                            itemsIndexed(notes) { index, note ->
                                Card(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(note)

                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(
                                                onClick = { copyText(note) }
                                            ) {
                                                Text("Copy")
                                            }

                                            TextButton(
                                                onClick = { notes.removeAt(index) }
                                            ) {
                                                Text(if (sinhala) "මකන්න" else "Delete")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Tab.SAVED -> {

                        Text(
                            if (sinhala) "සුරැකි පිටපත්" else "Saved Scripts",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(12.dp))

                        if (savedScripts.isEmpty()) {
                            Text(
                                if (sinhala) "තවමත් කිසිවක් සුරැකී නැහැ."
                                else "Nothing saved yet."
                            )
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(savedScripts) { index, script ->
                                    Card(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(script)

                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(
                                                    onClick = { copyText(script) }
                                                ) {
                                                    Text("Copy")
                                                }

                                                TextButton(
                                                    onClick = {
                                                        notes.add(0, script)
                                                        tab = Tab.NOTES
                                                    }
                                                ) {
                                                    Text(if (sinhala) "සටහන්" else "To Notes")
                                                }

                                                TextButton(
                                                    onClick = {
                                                        savedScripts.removeAt(index)
                                                    }
                                                ) {
                                                    Text(if (sinhala) "මකන්න" else "Delete")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Tab.TOOLS -> {

                        Text(
                            if (sinhala) "මෙවලම්" else "Tools",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(16.dp))

                        ToolCard(
                            title = if (sinhala) "දිනදර්ශනයට සිදුවීමක් එක් කරන්න"
                            else "Add Calendar Event",

                            description = if (sinhala)
                                "ඔබේ දුරකථනයේ Calendar යෙදුම විවෘත කරන්න."
                            else "Open your phone's Calendar app.",

                            icon = Icons.Default.CalendarMonth
                        ) {
                            try {
                                val start = System.currentTimeMillis() +
                                        60 * 60 * 1000

                                val end = start + 60 * 60 * 1000

                                val intent = Intent(Intent.ACTION_INSERT).apply {
                                    data = CalendarContract.Events.CONTENT_URI

                                    putExtra(
                                        CalendarContract.Events.TITLE,
                                        "NEXUS AI Event"
                                    )

                                    putExtra(
                                        CalendarContract.Events.DESCRIPTION,
                                        "Added using NEXUS AI."
                                    )

                                    putExtra(
                                        CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                                        start
                                    )

                                    putExtra(
                                        CalendarContract.EXTRA_EVENT_END_TIME,
                                        end
                                    )
                                }

                                context.startActivity(intent)

                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Calendar Error: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        ToolCard(
                            title = "Camera",
                            description = if (sinhala)
                                "දුරකථනයේ Camera යෙදුම විවෘත කරන්න."
                            else "Open your phone's Camera app.",

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
                    }

                    Tab.SETTINGS -> {

                        Text(
                            if (sinhala) "NEXUS සැකසුම්" else "NEXUS Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(16.dp))

                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(Modifier.padding(18.dp)) {

                                Text(
                                    if (sinhala) "භාෂාව" else "Language",
                                    fontWeight = FontWeight.Bold
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("සිංහල")
                                    Switch(
                                        checked = sinhala,
                                        onCheckedChange = { sinhala = it }
                                    )
                                    Text("English")
                                }

                                HorizontalDivider()

                                Spacer(Modifier.height(14.dp))

                                Text(
                                    if (sinhala) "පෙනුම" else "Appearance",
                                    fontWeight = FontWeight.Bold
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (dark) "Dark Mode" else "Light Mode")

                                    Spacer(Modifier.weight(1f))

                                    Switch(
                                        checked = dark,
                                        onCheckedChange = { dark = it }
                                    )
                                }

                                HorizontalDivider()

                                Spacer(Modifier.height(14.dp))

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
                                        if (sinhala) "දත්ත ආරක්ෂාව"
                                        else "Data Security",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    if (sinhala)
                                        "Chat, Notes සහ Saved Scripts AES-256-GCM Encryption මඟින් ආරක්ෂා කර ඇත. Encryption Key එක Android Keystore තුළ ගබඩා වේ."
                                    else
                                        "Chat, Notes and Saved Scripts are protected using AES-256-GCM encryption. The encryption key is stored in Android Keystore.",

                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(Modifier.height(12.dp))

                                Text(
                                    "NEXUS AI",
                                    color = NexusCyan,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "Personal AI Assistant",
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

@Composable
private fun NexusLogo(size: Int) {

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(NexusBlue, NexusPurple, NexusCyan)
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
}

@Composable
private fun ToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),

        shape = RoundedCornerShape(20.dp),

        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(17.dp),

            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                icon,
                contentDescription = null,
                tint = NexusCyan,
                modifier = Modifier.size(34.dp)
            )

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {

                Text(
                    title,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onClick) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "Open",
                    tint = NexusPurple
                )
            }
        }
    }
}
