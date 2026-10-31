package com.spotifydashboard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.spotifydashboard.app.auth.SpotifyAuthManager
import com.spotifydashboard.app.ui.theme.SpotifyGreen
import com.spotifydashboard.app.ui.theme.SpotifyLightGray

@Composable
fun SpotifyCredentialsScreen(
    authManager: SpotifyAuthManager,
    onBack: () -> Unit,
    onSaved: () -> Unit = {}
) {
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        clientId = authManager.getClientId()
    }

    val canSave = clientId.trim().isNotBlank()

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = SpotifyLightGray)
                }
            }
            Text(text = "Spotify credentials", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Enter your own Spotify app credentials. Create an app at developer.spotify.com/dashboard.", style = MaterialTheme.typography.bodyLarge, color = SpotifyLightGray)
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = clientId, onValueChange = { clientId = it },
                label = { Text("Spotify Client ID (required)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = clientSecret, onValueChange = { clientSecret = it },
                label = { Text("Spotify Client Secret (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = {
                authManager.saveCredentials(clientId.trim(), clientSecret.trim())
                saved = true
                onSaved()
            }, enabled = canSave, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)) {
                Text(text = if (saved) "Saved ✓" else "Save", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Credentials are stored locally on this device only. A single shared client_id is not permitted (Spotify ToS).", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
        }
    }
}
