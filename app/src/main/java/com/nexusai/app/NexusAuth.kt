package com.nexusai.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val NEXUS_SUPABASE_URL = "https://mcqoqgxnfhpvmnwkxgln.supabase.co"
private const val NEXUS_SUPABASE_KEY = "sb_publishable_KNW5l1PpMd6crI2jthhcrA_5_2Ubzcb"
private const val AUTH_PREFS = "nexus_local_data"
private const val AUTH_REFRESH_KEY = "nexus_supabase_refresh_token"

internal data class NexusAuthResult(
    val accessToken: String?,
    val message: String
)

internal object NexusAuthManager {
    private fun preferences(context: Context) =
        context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)

    private fun saveRefreshToken(context: Context, token: String) {
        preferences(context).edit()
            .putString(AUTH_REFRESH_KEY, encryptData(token))
            .apply()
    }

    fun clearSession(context: Context) {
        preferences(context).edit().remove(AUTH_REFRESH_KEY).apply()
    }

    private fun storedRefreshToken(context: Context): String? {
        val encrypted = preferences(context).getString(AUTH_REFRESH_KEY, null)
            ?: return null
        return try {
            decryptData(encrypted).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            clearSession(context)
            null
        }
    }

    private fun request(path: String, payload: JSONObject): JSONObject {
        val connection = (URL("$NEXUS_SUPABASE_URL/auth/v1/$path")
            .openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("apikey", NEXUS_SUPABASE_KEY)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) {
                val message = json.optString("error_description")
                    .ifBlank { json.optString("msg") }
                    .ifBlank { json.optString("message") }
                    .ifBlank { "Authentication failed (HTTP $status)." }
                throw IllegalStateException(message)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun authenticate(
        context: Context,
        email: String,
        password: String,
        createAccount: Boolean
    ): NexusAuthResult {
        val path = if (createAccount) "signup" else "token?grant_type=password"
        val json = request(path, JSONObject().put("email", email.trim()).put("password", password))
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
        if (access != null && refresh != null) {
            saveRefreshToken(context, refresh)
            return NexusAuthResult(access, "සාර්ථකව ඇතුළු වුණා.")
        }
        if (createAccount) {
            return NexusAuthResult(null, "ගිණුම සෑදීම සාර්ථකයි. විද්‍යුත් තැපෑල තහවුරු කර පසුව ඇතුළු වන්න.")
        }
        throw IllegalStateException("පිවිසුම් සැසියක් ලැබුණේ නැහැ. නැවත උත්සාහ කරන්න.")
    }

    fun restoreSession(context: Context): String? {
        val refresh = storedRefreshToken(context) ?: return null
        return try {
            refreshWithToken(context, refresh)
        } catch (_: Exception) {
            // Keep the encrypted refresh token during temporary network failures.
            null
        }
    }

    fun refreshAccessToken(context: Context, fallback: String): String {
        val refresh = storedRefreshToken(context) ?: return fallback
        return try {
            refreshWithToken(context, refresh)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun refreshWithToken(context: Context, refresh: String): String {
        val json = request(
            "token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refresh)
        )
        val access = json.optString("access_token")
        val nextRefresh = json.optString("refresh_token")
        if (access.isBlank() || nextRefresh.isBlank()) {
            throw IllegalStateException("සැසිය අලුත් කිරීම අසාර්ථකයි.")
        }
        saveRefreshToken(context, nextRefresh)
        return access
    }
}

@Composable
internal fun NexusRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var accessToken by remember { mutableStateOf<String?>(null) }
    var checkingSession by remember { mutableStateOf(true) }
    var authBusy by remember { mutableStateOf(false) }
    var authMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        accessToken = withContext(Dispatchers.IO) {
            NexusAuthManager.restoreSession(context)
        }
        checkingSession = false
    }

    MaterialTheme {
    when {
        checkingSession -> Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(14.dp))
                Text("NEXUS AI ආරක්ෂිත සම්බන්ධතාව සකස් කරමින්...")
            }
        }
        accessToken != null -> NexusApp(accessToken = accessToken!!)
        else -> NexusAuthScreen(
            busy = authBusy,
            message = authMessage,
            onSubmit = { email, password, createAccount ->
                scope.launch {
                    authBusy = true
                    authMessage = ""
                    try {
                        val result = withContext(Dispatchers.IO) {
                            NexusAuthManager.authenticate(context, email, password, createAccount)
                        }
                        accessToken = result.accessToken
                        authMessage = result.message
                    } catch (error: Exception) {
                        authMessage = error.message ?: "Authentication failed."
                    } finally {
                        authBusy = false
                    }
                }
            }
        )
    }
    }
}

@Composable
private fun NexusAuthScreen(
    busy: Boolean,
    message: String,
    onSubmit: (String, String, Boolean) -> Unit
) {
    var createAccount by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("NEXUS AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (createAccount) "නව ගිණුමක් සාදන්න" else "ඔබගේ ගිණුමට ඇතුළු වන්න",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("විද්‍යුත් තැපෑල") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("මුරපදය") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onSubmit(email, password, createAccount) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && email.contains("@") && password.length >= 6
                    ) {
                        if (busy) CircularProgressIndicator()
                        else Text(if (createAccount) "ගිණුම සාදන්න" else "ඇතුළු වන්න")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { createAccount = !createAccount },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) {
                        Text(if (createAccount) "දැනටමත් ගිණුමක් තිබේද? ඇතුළු වන්න" else "ගිණුමක් නැද්ද? අලුතින් සාදන්න")
                    }
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(message, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ඔබගේ NEXUS AI වෙබ් ගිණුමම මෙහි භාවිත කළ හැකියි.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
