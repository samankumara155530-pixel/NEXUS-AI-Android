package com.nexusai.app

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexusApp() }
    }
}

private enum class Tab { CHAT, NOTES, TOOLS, SETTINGS }

@Composable
private fun NexusApp() {
    var dark by remember { mutableStateOf(false) }
    var sinhala by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.CHAT) }
    val context = LocalContext.current
    val notes = remember { mutableStateListOf<String>() }
    var noteText by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }\n    var isSending by remember { mutableStateOf(false) }\n    val scope = rememberCoroutineScope()
    var chatMessages by remember { mutableStateOf(listOf(if (sinhala) "NEXUS AI වෙත සාදරයෙන් පිළිගනිමු. AI සේවාව තවම සම්බන්ධ කර නැත." else "Welcome to NEXUS AI. The AI service is not connected yet.")) }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            if (tab == Tab.NOTES) noteText = spoken else prompt = spoken
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Toast.makeText(context, if (sinhala) "Camera යෙදුමෙන් ආපසු පැමිණියා." else "Returned from camera app.", Toast.LENGTH_SHORT).show()
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text("NEXUS AI", fontWeight = FontWeight.Bold); Text(if (sinhala) "ඔබේ පුද්ගලික සහායකයා" else "Your personal assistant", style = MaterialTheme.typography.labelSmall) } },
                    actions = {
                        IconButton(onClick = { dark = !dark }) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "Theme") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == Tab.CHAT, onClick = { tab = Tab.CHAT }, icon = { Icon(Icons.Default.Chat, null) }, label = { Text(if (sinhala) "චැට්" else "Chat") })
                    NavigationBarItem(selected = tab == Tab.NOTES, onClick = { tab = Tab.NOTES }, icon = { Icon(Icons.Default.Notes, null) }, label = { Text(if (sinhala) "සටහන්" else "Notes") })
                    NavigationBarItem(selected = tab == Tab.TOOLS, onClick = { tab = Tab.TOOLS }, icon = { Icon(Icons.Default.Build, null) }, label = { Text(if (sinhala) "මෙවලම්" else "Tools") })
                    NavigationBarItem(selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(if (sinhala) "සැකසුම්" else "Settings") })
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                when (tab) {
                    Tab.CHAT -> {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(if (sinhala) "AI සහායකයා" else "AI Assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(if (sinhala) "මෙය මූලික යෙදුම් සැලසුමයි. සැබෑ AI පිළිතුරු ලබාගැනීමට ආරක්ෂිත backend එකක් සම්බන්ධ කළ යුතුයි." else "This is the initial app shell. A secure backend must be connected for real AI answers.")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(chatMessages) { message ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(message, Modifier.padding(12.dp)) }
                            }
                        }
                        OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text(if (sinhala) "ඔබේ පණිවිඩය" else "Your message") })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (sinhala) "si-LK" else "en-US")
                                }
                                try { voiceLauncher.launch(intent) } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, if (sinhala) "මෙම දුරකථනයේ Speech Recognition සේවාවක් නැහැ." else "Speech recognition is unavailable.", Toast.LENGTH_LONG).show()
                                }
                            }) { Icon(Icons.Default.Mic, contentDescription = "Voice") }
                            Button(onClick = {
                                val message = prompt.trim()
                                if (message.isNotEmpty() && !isSending) {
                                    chatMessages = chatMessages + (if (sinhala) "ඔබ: $message" else "You: $message")
                                    prompt = ""
                                    isSending = true
                                    scope.launch {
                                        val reply = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                val connection = java.net.URL("https://nexus-ai-backend-six.vercel.app/chat").openConnection() as java.net.HttpURLConnection
                                                connection.requestMethod = "POST"
                                                connection.connectTimeout = 15000
                                                connection.readTimeout = 60000
                                                connection.doOutput = true
                                                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                                val body = org.json.JSONObject().put("message", message).toString()
                                                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                                                val code = connection.responseCode
                                                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                                                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                                                connection.disconnect()
                                                val json = org.json.JSONObject(responseText)
                                                if (code in 200..299) json.optString("reply").takeIf { it.isNotBlank() }
                                                    ?: if (sinhala) "AI වෙතින් පිළිතුරක් ලැබුණේ නැහැ." else "The AI returned an empty reply."
                                                else json.optString("error").takeIf { it.isNotBlank() }
                                                    ?: if (sinhala) "AI සේවාවට සම්බන්ධ වීමට නොහැකි වුණා." else "Could not connect to the AI service."
                                            } catch (_: Exception) {
                                                if (sinhala) "සම්බන්ධතාවයේ ගැටලුවක් ඇති වුණා. අන්තර්ජාලය පරීක්ෂා කර නැවත උත්සාහ කරන්න." else "Connection failed. Check your internet and try again."
                                            }
                                        }
                                        chatMessages = chatMessages + reply
                                        isSending = false
                                    }
                                }
                            }, enabled = !isSending, modifier = Modifier.weight(1f)) {
                                Text(if (isSending) { if (sinhala) "පිළිතුර ලැබෙමින්..." else "Loading..." } else { if (sinhala) "පණිවිඩය" else "Send" })
                            }
                        }
                    }
                    Tab.NOTES -> {
                        Text(if (sinhala) "මගේ සටහන්" else "My Notes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(noteText, { noteText = it }, Modifier.fillMaxWidth(), label = { Text(if (sinhala) "සටහන ලියන්න" else "Write a note") })
                        Button(onClick = { if (noteText.isNotBlank()) { notes.add(0, noteText); noteText = "" } }, Modifier.fillMaxWidth()) { Text(if (sinhala) "සටහන එක් කරන්න" else "Add note") }
                        Spacer(Modifier.height(12.dp))
                        Text(if (sinhala) "මෙම විවෘත සැසියේ සටහන්" else "Notes in this session")
                        LazyColumn { items(notes) { note -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(note, Modifier.padding(12.dp)) } } }
                    }
                    Tab.TOOLS -> {
                        Text(if (sinhala) "දුරකථන මෙවලම්" else "Phone tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        ToolButton(if (sinhala) "හඬින් ලියන්න" else "Voice input", Icons.Default.Mic) {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (sinhala) "si-LK" else "en-US")
                            }
                            try { voiceLauncher.launch(intent) } catch (_: Exception) { Toast.makeText(context, "Speech recognition unavailable", Toast.LENGTH_SHORT).show() }
                        }
                        ToolButton(if (sinhala) "කැමරාව විවෘත කරන්න" else "Open camera", Icons.Default.CameraAlt) {
                            try { cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) } catch (_: Exception) { Toast.makeText(context, "Camera unavailable", Toast.LENGTH_SHORT).show() }
                        }
                        ToolButton(if (sinhala) "දිනදර්ශන සිදුවීමක්" else "Calendar event", Icons.Default.CalendarMonth) {
                            val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                                .putExtra(CalendarContract.Events.TITLE, if (sinhala) "NEXUS AI සිදුවීම" else "NEXUS AI event")
                            try { context.startActivity(intent) } catch (_: Exception) { Toast.makeText(context, "Calendar unavailable", Toast.LENGTH_SHORT).show() }
                        }
                    }
                    Tab.SETTINGS -> {
                        Text(if (sinhala) "සැකසුම්" else "Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (sinhala) "අඳුරු තේමාව" else "Dark theme", Modifier.weight(1f))
                            Switch(dark, { dark = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (sinhala) "සිංහල භාෂාව" else "Sinhala language", Modifier.weight(1f))
                            Switch(sinhala, { sinhala = it })
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(if (sinhala) "යාවත්කාලීන මධ්‍යස්ථානය" else "Update Center", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (sinhala) "මෙය දැනට පෙන්වීමක් පමණි. සැබෑ update server එකක් සම්බන්ධ කර නැත." else "Display placeholder only. No update server is connected.")
                        Spacer(Modifier.height(12.dp))
                        Text(if (sinhala) "අනුවාදය: 1.0.0 • AI backend: සම්බන්ධ කර නැත" else "Version: 1.0.0 • AI backend: not connected", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text(label)
    }
}
