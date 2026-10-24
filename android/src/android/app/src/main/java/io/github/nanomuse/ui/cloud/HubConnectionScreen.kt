package io.github.nanomuse.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.hub.Hub
import io.github.nanomuse.hub.HubAccount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubConnectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by Hub.devices.collectAsState()
    val connected by Hub.connected.collectAsState()
    val detail by Hub.detail.collectAsState()
    var relay by remember { mutableStateOf(HubAccount.relay(context)) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(HubAccount.token(context) != null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.nm_hub_connection_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text(stringResource(R.string.nm_hub_connection_local_note), style = MaterialTheme.typography.bodyMedium) }
            item {
                OutlinedTextField(
                    value = relay, onValueChange = { relay = it }, enabled = !signedIn, label = { Text(stringResource(R.string.nm_hub_connection_relay)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (connected) R.string.nm_hub_connection_online else R.string.nm_hub_connection_offline))
                    if (detail.isNotBlank() && !connected) Text(detail, color = MaterialTheme.colorScheme.error)
                }
            }
            if (!signedIn) {
                item {
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.nm_hub_connection_email)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                }
                if (codeSent) item {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text(stringResource(R.string.nm_hub_connection_code)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                item {
                    Button(enabled = !busy && email.isNotBlank() && (!codeSent || code.isNotBlank()), onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching {
                                HubAccount.saveRelay(context, relay)
                                if (codeSent) HubAccount.signIn(context, relay, email, code)
                                else { HubAccount.requestCode(context, relay, email); codeSent = true }
                                signedIn = HubAccount.token(context) != null
                            }.onFailure { error = it.message ?: it.javaClass.simpleName }
                            busy = false
                        }
                    }) {
                        if (busy) CircularProgressIndicator()
                        else Text(stringResource(if (codeSent) R.string.nm_hub_connection_sign_in else R.string.nm_hub_connection_send_code))
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            } else {
                item { Text(stringResource(R.string.nm_hub_connection_devices), style = MaterialTheme.typography.titleMedium) }
                items(devices.filter { it.id != Hub.deviceId(context) }) { device ->
                    Text(stringResource(if (device.online) R.string.nm_hub_device_online else R.string.nm_hub_device_offline, device.name, device.kind), modifier = Modifier.fillMaxWidth())
                }
                item {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch { runCatching { HubAccount.signOut(context) }.onFailure { error = it.message }; signedIn = HubAccount.token(context) != null; busy = false }
                    }) { Text(stringResource(R.string.nm_hub_connection_sign_out)) }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}
