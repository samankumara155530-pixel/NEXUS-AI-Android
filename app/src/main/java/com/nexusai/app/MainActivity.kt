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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NexusNavy = Color(0xFF081326)
private val NexusPanel = Color(0xFF111F38)
private val NexusBlue = Color(0xFF398BFF)
private val NexusCyan = Color(0xFF50D8FF)
private val NexusPurple = Color(0xFF8A5CFF)
private val LightBackground = Color(0xFFF3F7FF)
private val LightPanel = Color(0xFFFFFFFF)
private val LightBlue = Color(0xFFE7F0FF)
private val InkBlue = Color(0xFF142746)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexusApp()
        }
    }
}

private enum class Tab {
    CHAT, NOTES, TOOLS, SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexusApp() {
    var dark by remember { mutableStateOf(false) }
    var sinhala by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.CHAT) }

    val context = LocalContext.current
    val notes = remember { mutableStateListOf<String>() }
    var noteText by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var chatMessages by remember {
        mutableStateOf(listOf("NEXUS AI වෙත සාදරයෙන් පිළිගනිමු."))
    }

    val colors = if (dark) {
        darkColorScheme(
            primary = NexusBlue,
            onPrimary = Color.White,
            secondary = NexusPurple,
            onSecondary = Color.White,
            tertiary = NexusCyan,
            background = NexusNavy,
            onBackground = Color(0xFFF1F5FF),
            surface = NexusPanel,
            onSurface = Color(0xFFF1F5FF),
            surfaceVariant = Color(0xFF1B2B48),
            onSurfaceVariant = Color(0xFFCAD7EF),
            outline = Color(0xFF3B5276)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1769D2),
            onPrimary = Color.White,
            secondary = NexusPurple,
            onSecondary = Color.White,
            tertiary = Color(0xFF008DA8),
            background = LightBackground,
            onBackground = InkBlue,
            surface = LightPanel,
            onSurface = InkBlue,
            surfaceVariant = LightBlue,
            onSurfaceVariant = Color(0xFF405675),
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
            if (sinhala) "Camera යෙදුමෙන් ආපසු පැමිණියා."
            else "Returned from camera app.",
            Toast.LENGTH_SHORT
        ).show()
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
                            NexusLogo(size = 42)

                            Column {
                                Text(
                                    "NEXUS AI",
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Text(
                                    if (sinhala) "ඔබේ පුද්ගලික සහායකයා"
                                    else "Your personal assistant",
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
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    NavigationBarItem(
                        selected = tab == Tab.CHAT,
                        onClick = { tab = Tab.CHAT },
                        icon = {
                            Icon(Icons.Default.Chat, contentDescription = null)
                        },
                        label = {
                            Text(if (sinhala) "චැට්" else "Chat")
                        }
                    )

                    NavigationBarItem(
                        selected = tab == Tab.NOTES,
                        onClick = { tab = Tab.NOTES },
                        icon = {
                            Icon(Icons.Default.Notes, contentDescription = null)
                        },
                        label = {
                            Text(if (sinhala) "සටහන්" else "Notes")
                        }
                    )

                    NavigationBarItem(
                        selected = tab == Tab.TOOLS,
                        onClick = { tab = Tab.TOOLS },
                        icon = {
                            Icon(Icons.Default.Build, contentDescription = null)
                        },
                        label = {
                            Text(if (sinhala) "මෙවලම්" else "Tools")
                        }
                    )

                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        },
                        label = {
                            Text(if (sinhala) "සැකසුම්" else "Settings")
                        }
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
                                    Color(0xFF0B1730),
                                    NexusNavy
                                )
                            } else {
                                listOf(
                                    LightBackground,
                                    Color(0xFFEAF2FF),
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
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = 3.dp
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NexusLogo(size = 50)

                                Spacer(Modifier.width(12.dp))

                                Column {
                                    Text(
                                        if (sinhala) "AI සහායකයා"
                                        else "AI Assistant",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(Modifier.height(4.dp))

                                    Text(
                                        if (sinhala) {
                                            "ඔබේ පණිවිඩය යවා AI පිළිතුර ලබාගන්න."
                                        } else {
                                            "Send a message to receive an AI reply."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            items(chatMessages) { message ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Text(
                                        text = message,
                                        modifier = Modifier.padding(14.dp),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            label = {
                                Text(
                                    if (sinhala) "ඔබේ පණිවිඩය"
                                    else "Your message"
                                )
                            }
                        )

                        Spacer(Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
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

                                    try {
                                        voiceLauncher.launch(intent)
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(
                                            context,
                                            if (sinhala) {
                                                "හඬ හඳුනාගැනීමේ සේවාව ලබාගත නොහැක."
                                            } else {
                                                "Speech recognition is unavailable."
                                            },
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Voice",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            Button(
                                onClick = {
                                    val message = prompt.trim()

                                    if (message.isNotEmpty() && !isSending) {
                                        chatMessages = chatMessages + if (sinhala) {
                                            "ඔබ: $message"
                                        } else {
                                            "You: $message"
                                        }

                                        prompt = ""
                                        isSending = true

                                        scope.launch {
                                            val reply = withContext(
                                                kotlinx.coroutines.Dispatchers.IO
                                            ) {
                                                try {
                                                    val connection = java.net.URL(
                                                        "https://nexus-ai-backend-six.vercel.app/chat"
                                                    ).openConnection()
                                                        as java.net.HttpURLConnection

                                                    try {
                                                        connection.requestMethod = "POST"
                                                        connection.connectTimeout = 15000
                                                        connection.readTimeout = 60000
                                                        connection.doOutput = true

                                                        connection.setRequestProperty(
                                                            "Content-Type",
                                                            "application/json; charset=utf-8"
                                                        )

                                                        val body = org.json.JSONObject()
                                                            .put("message", message)
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

                                                        val responseText = stream
                                                            ?.bufferedReader(Charsets.UTF_8)
                                                            ?.use { it.readText() }
                                                            .orEmpty()

                                                        val json = org.json.JSONObject(
                                                            responseText
                                                        )

                                                        if (code in 200..299) {
                                                            json.optString("reply")
                                                                .takeIf { it.isNotBlank() }
                                                                ?: if (sinhala) {
                                                                    "AI පිළිතුරක් ලැබුණේ නැහැ."
                                                                } else {
                                                                    "The AI returned an empty reply."
                                                                }
                                                        } else {
                                                            json.optString("error")
                                                                .takeIf { it.isNotBlank() }
                                                                ?: if (sinhala) {
                                                                    "AI සේවාවට සම්බන්ධ වීමට නොහැකි වුණා."
                                                                } else {
                                                                    "Could not connect to the AI service."
                                                                }
                                                        }
                                                    } finally {
                                                        connection.disconnect()
                                                    }
                                                } catch (_: Exception) {
                                                    if (sinhala) {
                                                        "සම්බන්ධතාවයේ ගැටලුවක් ඇති වුණා. අන්තර්ජාලය පරීක්ෂා කරන්න."
                                                    } else {
                                                        "Connection failed. Check your internet."
                                                    }
                                                }
                                            }

                                            chatMessages = chatMessages + reply
                                            isSending = false
                                        }
                                    }
                                },
                                enabled = !isSending,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = null
                                )

                                Spacer(Modifier.width(8.dp))

                                Text(
                                    if (isSending) {
                                        if (sinhala) "පිළිතුර ලැබෙමින්..."
                                        else "Loading..."
                                    } else {
                                        if (sinhala) "පණිවිඩය"
                                        else "Send"
                                    }
                                )
                            }
                        }
                    }

                    Tab.NOTES -> {

                        SectionHeading(
                            if (sinhala) "මගේ සටහන්" else "My Notes"
                        )

                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            label = {
                                Text(
                                    if (sinhala) "සටහන ලියන්න"
                                    else "Write a note"
                                )
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (noteText.isNotBlank()) {
                                    notes.add(0, noteText)
                                    noteText = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (sinhala) "සටහන එක් කරන්න"
                                else "Add note"
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            if (sinhala) "මෙම විවෘත සැසියේ සටහන්"
                            else "Notes in this session",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        LazyColumn {
                            items(notes) { note ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Text(
                                        note,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    Tab.TOOLS -> {

                        SectionHeading(
                            if (sinhala) "දුරකථන මෙවලම්"
                            else "Phone tools"
                        )

                        Text(
                            if (sinhala) {
                                "දුරකථනයේ පහසුකම් ඉක්මනින් විවෘත කරන්න."
                            } else {
                                "Quick access to your phone tools."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))

                        ToolButton(
                            label = if (sinhala) "හඬින් ලියන්න" else "Voice input",
                            icon = Icons.Default.Mic
                        ) {
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

                            try {
                                voiceLauncher.launch(intent)
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "Speech recognition unavailable",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        ToolButton(
                            label = if (sinhala) "කැමරාව විවෘත කරන්න"
                            else "Open camera",
                            icon = Icons.Default.CameraAlt
                        ) {
                            try {
                                cameraLauncher.launch(
                                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                                )
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "Camera unavailable",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        ToolButton(
                            label = if (sinhala) "දිනදර්ශන සිදුවීමක්"
                            else "Calendar event",
                            icon = Icons.Default.CalendarMonth
                        ) {
                            Toast.makeText(
                                context,
                                "Calendar button pressed",
                                Toast.LENGTH_LONG
                            ).show()

                            val startTime = System.currentTimeMillis()
                            val endTime = startTime + 60 * 60 * 1000L

                            val calendarIntent = Intent(
                                Intent.ACTION_INSERT
                            ).apply {
                                setData(CalendarContract.Events.CONTENT_URI)

                                putExtra(
                                    CalendarContract.Events.TITLE,
                                    if (sinhala) "NEXUS AI සිදුවීම"
                                    else "NEXUS AI event"
                                )

                                putExtra(
                                    CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                                    startTime
                                )

                                putExtra(
                                    CalendarContract.EXTRA_EVENT_END_TIME,
                                    endTime
                                )
                            }

                            try {
                                val googleCalendarIntent =
                                    Intent(calendarIntent).apply {
                                        setPackage("com.google.android.calendar")
                                    }

                                try {
                                    context.startActivity(googleCalendarIntent)
                                } catch (_: ActivityNotFoundException) {
                                    context.startActivity(
                                        Intent.createChooser(
                                            calendarIntent,
                                            if (sinhala) "දින දර්ශනය තෝරන්න"
                                            else "Choose calendar"
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    if (sinhala) {
                                        "දිනදර්ශනය විවෘත කළ නොහැක: ${e.javaClass.simpleName}"
                                    } else {
                                        "Could not open calendar: ${e.javaClass.simpleName}"
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    Tab.SETTINGS -> {

                        SectionHeading(
                            if (sinhala) "සැකසුම්" else "Settings"
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 8.dp
                                )
                            ) {
                                SettingSwitchRow(
                                    label = if (sinhala) "අඳුරු තේමාව"
                                    else "Dark theme",
                                    checked = dark,
                                    onCheckedChange = { dark = it }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(
                                        alpha = 0.35f
                                    )
                                )

                                SettingSwitchRow(
                                    label = if (sinhala) "සිංහල භාෂාව"
                                    else "Sinhala language",
                                    checked = sinhala,
                                    onCheckedChange = { sinhala = it }
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            if (sinhala) "යාවත්කාලීන මධ්‍යස්ථානය"
                            else "Update Center",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            if (sinhala) {
                                "මෙය දැනට පෙන්වීමක් පමණි. සැබෑ update server එකක් සම්බන්ධ කර නැත."
                            } else {
                                "Display placeholder only. No update server is connected."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            if (sinhala) {
                                "අනුවාදය: 1.0.0 • AI backend: සම්බන්ධ කර නැත"
                            } else {
                                "Version: 1.0.0 • AI backend: not connected"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            .clip(RoundedCornerShape((size / 3).dp))
            .background(
                Brush.linearGradient(
                    listOf(NexusBlue, NexusPurple, NexusCyan)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "N",
            color = Color.White,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground
    )

    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        ),
        contentPadding = PaddingValues(
            horizontal = 16.dp,
            vertical = 14.dp
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label
        )

        Spacer(Modifier.width(12.dp))

        Text(
            text = label,
            modifier = Modifier.weight(1f)
        )

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null
        )
    }
}
