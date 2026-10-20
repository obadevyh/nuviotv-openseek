@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.openseek.OpenSeekConnection
import com.nuvio.tv.openseek.OpenSeekRepository
import com.nuvio.tv.openseek.PairResult
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OpenSeekSettingsViewModel @Inject constructor(
    val repository: OpenSeekRepository
) : ViewModel()

private enum class OpenSeekDialog { None, Address, Pair }

@Composable
fun OpenSeekSettingsContent(
    viewModel: OpenSeekSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val repo = viewModel.repository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val connection by repo.connection.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(OpenSeekDialog.None) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings.address, settings.paired) { if (settings.address.isNotEmpty()) repo.checkConnection() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = "OpenSeek",
            subtitle = "Seek-bar thumbnail previews from your own OpenSeek server (unofficial)"
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "openseek_enabled") {
                        SettingsToggleRow(
                            title = "Thumbnail previews",
                            subtitle = if (settings.enabled) "On: previews appear while you scrub, when the server has them"
                            else "Off: seeking works exactly as in Nuvio. Nothing is sent to the server",
                            checked = settings.enabled,
                            onToggle = { repo.setEnabled(!settings.enabled) },
                            modifier = Modifier.padding(top = NuvioTheme.spacing.xxs).then(
                                if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                            )
                        )
                    }
                    item(key = "openseek_address") {
                        SettingsActionRow(
                            title = "Server address",
                            subtitle = "Your OpenSeek server on this network, e.g. 192.168.1.20 or nas.local:8787",
                            value = settings.address.removePrefix("https://").ifEmpty { "Not set" },
                            onClick = { dialog = OpenSeekDialog.Address }
                        )
                    }
                    item(key = "openseek_pair") {
                        SettingsActionRow(
                            title = if (settings.paired) "Pair again" else "Pair this TV",
                            subtitle = "Enter the code shown in the OpenSeek window, or an invitation link or code",
                            value = if (settings.paired) "Paired (${if (settings.role == "guest") "guest" else "owner"})" else "Not paired",
                            onClick = { dialog = OpenSeekDialog.Pair }
                        )
                    }
                    item(key = "openseek_status") {
                        SettingsActionRow(
                            title = "Connection",
                            subtitle = "Select to check again",
                            value = when (connection) {
                                OpenSeekConnection.Unknown -> if (settings.address.isEmpty()) "No server" else "Not checked"
                                OpenSeekConnection.Checking -> "Checking…"
                                OpenSeekConnection.Online -> if (settings.paired) "Connected" else "Server found, not paired"
                                OpenSeekConnection.Offline -> "Server unreachable"
                                OpenSeekConnection.Revoked -> "Access revoked: pair again"
                                OpenSeekConnection.PinChanged -> "Server identity changed: pair again"
                            },
                            onClick = { repo.checkConnection() }
                        )
                    }
                    if (settings.paired) {
                        item(key = "openseek_forget") {
                            SettingsActionRow(
                                title = "Forget this pairing",
                                subtitle = "Removes the access key from this TV",
                                onClick = { repo.forget() }
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    when (dialog) {
        OpenSeekDialog.None -> Unit
        OpenSeekDialog.Address -> OpenSeekInputDialog(
            title = "Server address",
            subtitle = "Host name or IP of the PC running OpenSeek, optionally with :port (default 8787).",
            placeholder = "192.168.1.20",
            initial = settings.address.removePrefix("https://").removeSuffix(":8787"),
            confirmLabel = "Save",
            onDismiss = { dialog = OpenSeekDialog.None },
            onConfirm = { value, setError, _ ->
                if (repo.setAddress(value)) { dialog = OpenSeekDialog.None; repo.checkConnection() }
                else setError("That address is not valid. Use a host name or IP, without http:// or a path.")
            }
        )
        OpenSeekDialog.Pair -> OpenSeekInputDialog(
            title = "Pair this TV",
            subtitle = "Open OpenSeek on the PC, choose Pair a device, and type the code (XXXX-XXXX). Invitation links and codes work too.",
            placeholder = "XXXX-XXXX",
            initial = "",
            confirmLabel = "Pair",
            onDismiss = { dialog = OpenSeekDialog.None },
            onConfirm = { value, setError, setBusy ->
                scope.launch {
                    setBusy(true)
                    val r = repo.pair(value.trim())
                    setBusy(false)
                    if (r == PairResult.Paired) { dialog = OpenSeekDialog.None; repo.setEnabled(true) }
                    else setError(
                        when (r) {
                            PairResult.BadInput -> "That is not a valid code. Set the server address first, then type the 8-symbol code."
                            PairResult.NoConnection -> "Could not reach the server. Check the address and that this TV is on the same network."
                            PairResult.PinMismatch -> "The server's identity does not match. Not paired."
                            PairResult.Closed -> "The server is not accepting pairing. Open the pairing window in OpenSeek first."
                            PairResult.RateLimited -> "Too many attempts. Wait a minute and try again."
                            PairResult.ServerProofFailed -> "The server could not prove it knows the code. Not paired."
                            else -> "The code was not accepted. It may be wrong or expired."
                        }
                    )
                }
            }
        )
    }
}

@Composable
private fun OpenSeekInputDialog(
    title: String,
    subtitle: String,
    placeholder: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (value: String, setError: (String) -> Unit, setBusy: (Boolean) -> Unit) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = subtitle, width = 700.dp) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary,
                        textDirection = TextDirection.Content
                    ),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(text = placeholder, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary)
                        }
                        innerTextField()
                    }
                )
            }
        }
        if (error != null) {
            Text(text = error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Error)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text("Cancel") }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { if (!busy && value.isNotBlank()) onConfirm(value, { error = it }, { busy = it }) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(if (busy) "Working…" else confirmLabel) }
        }
    }
}
