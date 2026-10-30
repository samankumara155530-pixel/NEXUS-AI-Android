package com.nexusai.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.provider.MediaStore
import android.provider.CalendarContract
import android.widget.Toast
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

private val NexusNavy = Color(0xFF081326)
private val NexusPanel = Color(0xFF111F38)
private val NexusBlue = Color(0xFF398BFF)
private val NexusCyan = Color(0xFF50D8FF)
private val NexusPurple = Color(0xFF8A5CFF)
private val LightBackground = Color(0xFFF3F7FF)
private val LightPanel = Color(0xFFFFFFFF)
private val LightBlue = Color(0xFFE7F0FF)
private val InkBlue = Color(0xFF142746)

private const val PREFS_NAME = "nexus_local_data"
private const val KEY_CHAT = "chat_messages"
private const val KEY_NOTES = "notes"
private const val KEY_SCRIPTS = "saved_scripts"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexusApp() }
    }
}

private enum class Tab { CHAT, NOTES, SAVED, TOOLS, SETTINGS }

private fun readStringList(context: Context, key: String, fallback: List<String> = emptyList()): List<String> {
    return try {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null) ?: return fallback
        val array = JSONArray(raw)
        List(array.length()) { index -> array.optString(index) }
    } catch (_: Exception) {
        fallback
    }
}

private fun writeStringList(context: Context, key: String, values: List<String>) {
    val array = JSONArray()
    values.forEach { array.put(it) }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(key, array.toString())
        .apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexusApp() {
    var dark by remember { mutableStateOf(false) }
    var sinhala by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.CHAT) }

    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
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
    val scope = rememberCoroutineScope()
    var chatMessages by remember {
        mutableStateOf(
            readStringList(context, KEY_CHAT).ifEmpty {
                listOf("NEXUS AI වෙත සාදරයෙන් පිළිගනිමු.")
            }
        )
    }

    LaunchedEffect(chatMessages) { writeStringList(context, KEY_CHAT, chatMessages) }
    LaunchedEffect(notes.toList()) { writeStringList(context, KEY_NOTES, notes.toList()) }
    LaunchedEffect(savedScripts.toList()) { writeStringList(context, KEY_SCRIPTS, savedScripts.toList()) }

    val colors = if (dark) {
        darkColorScheme(
            primary = NexusBlue, onPrimary = Color.White,
            secondary = NexusPurple, onSecondary = Color.White,
            tertiary = NexusCyan, background = NexusNavy,
            onBackground = Color(0xFFF1F5FF), surface = NexusPanel,
            onSurface = Color(0xFFF1F5FF), surfaceVariant = Color(0xFF1B2B48),
            onSurfaceVariant = Color(0xFFCAD7EF), outline = Color(0xFF3B5276)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1769D2), onPrimary = Color.White,
            secondary = NexusPurple, onSecondary = Color.White,
            tertiary = Color(0xFF008DA8), background = LightBackground,
            onBackground = InkBlue, surface = LightPanel, onSurface = InkBlue,
            surfaceVariant = LightBlue, onSurfaceVariant = Color(0xFF405675),
            outline = Color(0xFFB9C9E0)
        )
    }    
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            if (tab == Tab.NOTES) noteText = spoken else prompt = spoken
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Toast.makeText(
            context,
            if (sinhala) "Camera යෙදුමෙන් ආපසු පැමිණියා." else "Returned from camera app.",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun copyText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NEXUS AI", text))
        Toast.makeText(context, if (sinhala) "පිළිතුර Copy කළා." else "Copied.", Toast.LENGTH_SHORT).show()
    }

    fun saveScript(text: String) {
        if (savedScripts.none { it == text }) {
            savedScripts.add(0, text)
            Toast.makeText(context, if (sinhala) "පිටපත සුරැකුවා." else "Script saved.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, if (sinhala) "මෙම පිටපත දැනටමත් සුරැකලා." else "Already saved.", Toast.LENGTH_SHORT).show()
        }
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NexusLogo(size = 42)
                            Column {
                                Text("NEXUS AI", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    if (sinhala) "ඔබේ පුද්ගලික සහායකයා" else "Your personal assistant",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { dark = !dark }) {
                            Icon(
                                if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Theme",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(tab == Tab.CHAT, { tab = Tab.CHAT }, { Icon(Icons.Default.Chat, null) }, label = { Text(if (sinhala) "චැට්" else "Chat") })
                    NavigationBarItem(tab == Tab.NOTES, { tab = Tab.NOTES }, { Icon(Icons.Default.Notes, null) }, label = { Text(if (sinhala) "සටහන්" else "Notes") })
                    NavigationBarItem(tab == Tab.SAVED, { tab = Tab.SAVED }, { Icon(Icons.Default.Bookmark, null) }, label = { Text(if (sinhala) "සුරැකි" else "Saved") })
                    NavigationBarItem(tab == Tab.TOOLS, { tab = Tab.TOOLS }, { Icon(Icons.Default.Build, null) }, label = { Text(if (sinhala) "මෙවලම්" else "Tools") })
                    NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text(if (sinhala) "සැකසුම්" else "Settings") })
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(if (dark) listOf(NexusNavy, Color(0xFF0B1730), NexusNavy) else listOf(LightBackground, Color(0xFFEAF2FF), LightBackground)))
                    .padding(padding).padding(16.dp)
            ) {
                when (tab) {
                    Tab.CHAT -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                NexusLogo(size = 50)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(if (sinhala) "AI සහායකයා" else "AI Assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (sinhala) "ඔබේ පණිවිඩය යවා AI පිළිතුර ලබාගන්න." else "Send a message to receive an AI reply.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            itemsIndexed(chatMessages) { index, message ->
                                val isUserMessage = message.startsWith("ඔබ: ") || message.startsWith("You: ")
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                        Text(text = message, color = MaterialTheme.colorScheme.onSurface)
                                        if (!isUserMessage && index != 0 && message.isNotBlank()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(onClick = { copyText(message) }) {
                                                    Icon(Icons.Default.ContentCopy, null)
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (sinhala) "Copy" else "Copy")
                                                }
                                                TextButton(onClick = { saveScript(message) }) {
                                                    Icon(Icons.Default.BookmarkAdd, null)
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (sinhala) "Save" else "Save")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }                        
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(if (sinhala) "ඔබේ පණිවිඩය මෙහි ලියන්න..." else "Type your message...") },
                            shape = RoundedCornerShape(18.dp),
                            trailingIcon = {
                                IconButton(onClick = {
                                    try {
                                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (sinhala) "si-LK" else "en-US")
                                        }
                                        voiceLauncher.launch(intent)
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(context, if (sinhala) "Voice පහසුකම ලබාගත නොහැක." else "Voice input is unavailable.", Toast.LENGTH_LONG).show()
                                    }
                                }) {
                                    Icon(Icons.Default.Mic, contentDescription = "Voice")
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val userText = prompt.trim()
                                if (userText.isNotEmpty() && !isSending) {
                                    chatMessages = chatMessages + (if (sinhala) "ඔබ: " else "You: ") + userText
                                    prompt = ""
                                    isSending = true
                                    scope.launch {
                                        val reply = withContext(Dispatchers.IO) {
                                            try {
                                                val connection = (URL("https://nexus-ai-backend-six.vercel.app/chat").openConnection() as HttpURLConnection).apply {
                                                    requestMethod = "POST"
                                                    connectTimeout = 20000
                                                    readTimeout = 30000
                                                    doOutput = true
                                                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                                }
                                                val body = JSONObject().put("message", userText).toString()
                                                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                                                val responseCode = connection.responseCode
                                                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                                                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                                                connection.disconnect()
                                                if (responseCode in 200..299) {
                                                    val json = JSONObject(response)
                                                    json.optString("reply", json.optString("response", response))
                                                } else {
                                                    "AI සේවාවෙන් දෝෂයක් ලැබුණා. ($responseCode)\n$response"
                                                }
                                            } catch (e: Exception) {
                                                "AI සම්බන්ධතාවය අසාර්ථකයි: ${e.localizedMessage ?: "නොදන්නා දෝෂයක්"}"
                                            }
                                        }
                                        chatMessages = chatMessages + reply
                                        isSending = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = prompt.isNotBlank() && !isSending,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.Default.Send, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (sinhala) "යවන්න" else "Send")
                        }
                    }

                    Tab.NOTES -> {
                        Text(if (sinhala) "මගේ සටහන්" else "My Notes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(if (sinhala) "සටහන මෙහි ලියන්න..." else "Write a note...") },
                            minLines = 3,
                            shape = RoundedCornerShape(16.dp)
                        )
                        Button(
                            onClick = {
                                if (noteText.isNotBlank()) {
                                    notes.add(0, noteText.trim())
                                    noteText = ""
                                    Toast.makeText(context, if (sinhala) "සටහන සුරැකුවා." else "Note saved.", Toast.LENGTH_SHORT).show()
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
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(note)
                                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                            TextButton(onClick = { copyText(note) }) { Text(if (sinhala) "Copy" else "Copy") }
                                            TextButton(onClick = { notes.removeAt(index) }) { Text(if (sinhala) "මකන්න" else "Delete") }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Tab.SAVED -> {
                        Text(if (sinhala) "සුරැකි පිටපත්" else "Saved Scripts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (savedScripts.isEmpty()) {
                            Text(if (sinhala) "තවමත් පිටපත් සුරැකී නැහැ." else "No saved scripts yet.")
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(savedScripts) { index, script ->
                                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(script)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(onClick = { copyText(script) }) {
                                                    Icon(Icons.Default.ContentCopy, null)
                                                    Text("Copy")
                                                }
                                                TextButton(onClick = {
                                                    notes.add(0, script)
                                                    tab = Tab.NOTES
                                                    Toast.makeText(context, if (sinhala) "සටහන් වෙත එක් කළා." else "Added to Notes.", Toast.LENGTH_SHORT).show()
                                                }) {
                                                    Text(if (sinhala) "සටහන් වෙත" else "To Notes")
                                                }
                                                TextButton(onClick = {
                                                    savedScripts.removeAt(index)
                                                }) {
                                                    Text(if (sinhala) "මකන්න" else "Delete")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }                    Tab.TOOLS 
                    -> {
                        Text(if (sinhala) "මෙවලම්" else "Tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))

                        ToolCard(
                            title = if (sinhala) "දිනදර්ශනයට සිදුවීමක් එක් කරන්න" else "Add Calendar Event",
                            description = if (sinhala) "ඔබේ දුරකථනයේ Calendar යෙදුම විවෘත කරන්න." else "Open your phone's Calendar app.",
                            icon = Icons.Default.CalendarMonth
                        ) {
                            try {
                                val startMillis = System.currentTimeMillis() + 60 * 60 * 1000
                                val endMillis = startMillis + 60 * 60 * 1000
                                val intent = Intent(Intent.ACTION_INSERT).apply {
                                    data = CalendarContract.Events.CONTENT_URI
                                    putExtra(CalendarContract.Events.TITLE, if (sinhala) "NEXUS AI සිදුවීම" else "NEXUS AI Event")
                                    putExtra(CalendarContract.Events.DESCRIPTION, if (sinhala) "NEXUS AI මඟින් එක් කරන ලදී." else "Added using NEXUS AI.")
                                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                                }
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, if (sinhala) "දිනදර්ශන යෙදුමක් සොයාගත නොහැක." else "No Calendar app found.", Toast.LENGTH_LONG).show()
                            }
                        }

                        ToolCard(
                            title = if (sinhala) "Camera" else "Camera",
                            description = if (sinhala) "දුරකථනයේ Camera යෙදුම විවෘත කරන්න." else "Open your phone's Camera app.",
                            icon = Icons.Default.CameraAlt
                        ) {
                            try {
                                cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, if (sinhala) "Camera යෙදුම සොයාගත නොහැක." else "No Camera app found.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    Tab.SETTINGS -> {
                        Text(if (sinhala) "සැකසුම්" else "Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(if (sinhala) "භාෂාව" else "Language", fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("සිංහල")
                                    Switch(checked = sinhala, onCheckedChange = { sinhala = it })
                                    Text("English")
                                }
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))
                                Text(if (sinhala) "පෙනුම" else "Appearance", fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (dark) "Dark" else "Light")
                                    Switch(checked = dark, onCheckedChange = { dark = it })
                                }
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))
                                Text(if (sinhala) "දත්ත ආරක්ෂාව" else "Data Storage", fontWeight = FontWeight.Bold)
                                Text(
                                    if (sinhala) "Chat, Notes සහ Saved Scripts ඔබගේ දුරකථනයේ පමණක් ගබඩා වේ. දැනට ඒවා Encryption කර නැත." else "Chat, Notes and Saved Scripts are stored locally on your phone. They are not encrypted yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
        modifier = Modifier.size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(NexusBlue, NexusPurple))),
        contentAlignment = Alignment.Center
    ) {
        Text("N", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.ArrowForward, contentDescription = "Open")
            }
        }
    }
}
