package gg.dekiru.musicplayer.ui.signin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.QuickConnectController.State
import gg.dekiru.musicplayer.data.auth.ServerAddress
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.disc
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.ActionChip
import gg.dekiru.musicplayer.ui.components.CheckIcon
import gg.dekiru.musicplayer.ui.components.PrimaryButton
import gg.dekiru.musicplayer.ui.components.WarningIcon
import gg.dekiru.musicplayer.ui.tetsubiType
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import org.jetbrains.compose.resources.painterResource

/**
 * Sign in to a Jellyfin server (as `ServerConfigView` on iOS): one centred card (440 at most) with
 * the disc and title, the server address and the servers on the network, then the Quick Connect
 * code or the username & password form.
 */
@Composable
fun SignInScreen(
    authenticator: Authenticator,
    onConnected: (JellyfinApi) -> Unit,
    /** The network search; replaceable so a preview or test never touches the network. */
    searchNetwork: suspend () -> List<gg.dekiru.musicplayer.data.auth.DiscoveredServer> = { gg.dekiru.musicplayer.data.auth.ServerDiscovery.search() },
) {
    val scope = rememberCoroutineScope()
    val model = remember { SignInModel(authenticator, scope, onConnected, searchNetwork = searchNetwork) }
    val quickConnectState by model.quickConnect.state.collectAsState()

    LaunchedEffect(Unit) { model.onAppear() }
    DisposableEffect(model) { onDispose { model.onDisappear() } }
    LaunchedEffect(quickConnectState) {
        // Any Quick Connect failure reveals the manual form with the reason.
        (quickConnectState as? State.Failed)?.let { model.onQuickConnectFailed(it.message) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val fullHeight = maxHeight
        // Centred when it fits, scrolling when it doesn't (a short window, the keyboard).
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.heightIn(min = fullHeight).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    Modifier.widthIn(max = 440.dp).fillMaxWidth()
                        .background(TetsubiColors.surface, RoundedCornerShape(Radii.panel))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Header()
                    ServerBox(model)
                    AnimatedContent(
                        targetState = model.mode,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "sign-in mode",
                    ) { mode ->
                        when (mode) {
                            SignInModel.Mode.QuickConnect -> QuickConnectPanel(model, quickConnectState)
                            SignInModel.Mode.Manual -> ManualPanel(model)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column(
        Modifier.padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painterResource(Res.drawable.disc),
            contentDescription = null,
            modifier = Modifier.size(96.dp).shadow(12.dp, RoundedCornerShape(Radii.FULL_PERCENT), clip = false),
        )
        Text("Sign in to Jellyfin", style = tetsubiType.nowPlayingTitle, color = TetsubiColors.textPrimary)
        Text("Tetsubi", style = tetsubiType.body, color = TetsubiColors.textSecondary)
    }
}

// Server

@Composable
private fun ServerBox(model: SignInModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Field("Server URL") {
            OutlinedTextField(
                value = model.serverUrl,
                onValueChange = model::changeServerUrl,
                placeholder = { Text(ServerAddress.EXAMPLE, color = TetsubiColors.textTertiary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                colors = fieldColors(),
                shape = RoundedCornerShape(Radii.card),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DiscoveryList(model)
    }
}

@Composable
private fun DiscoveryList(model: SignInModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("On your network", style = tetsubiType.caption, color = TetsubiColors.textSecondary, modifier = Modifier.weight(1f))
            if (model.isSearching) {
                Spinner()
            } else {
                ActionChip("Search again", onClick = model::search)
            }
        }
        if (model.discovered.isEmpty()) {
            Text(
                if (model.isSearching || !model.hasSearched) "Looking for servers…"
                else "No servers found. Type your server's address above.",
                style = tetsubiType.control,
                color = TetsubiColors.textTertiary,
            )
        }
        model.discovered.forEach { server ->
            val selected = server.address == model.trimmedServerUrl
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.card))
                    .background(if (selected) TetsubiColors.blueTint else TetsubiColors.surface)
                    .clickable(role = Role.Button) { model.pick(server) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(server.name, style = tetsubiType.rowTitle, color = TetsubiColors.textPrimary)
                    Text(server.address, style = tetsubiType.caption, color = TetsubiColors.textSecondary)
                }
                if (selected) CheckIcon(20.dp, TetsubiColors.blue)
            }
        }
    }
}

// Quick Connect

@Composable
private fun QuickConnectPanel(model: SignInModel, state: State) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quick Connect", style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary)
        Text(
            "Approve this login on your Jellyfin server, or in the Jellyfin app on another device you're signed into.",
            style = tetsubiType.control.copy(fontWeight = FontWeight.Normal),
            color = TetsubiColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        when {
            model.isFindingServer -> Waiting("Finding server…")
            state is State.Starting -> Waiting("Requesting login code…")
            state is State.Pending -> {
                SelectionContainer {
                    Text(
                        state.code,
                        color = TetsubiColors.blue,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Waiting("Waiting for approval…")
            }
            state is State.Failed -> ErrorText(state.message)
            else -> {
                Text(
                    if (model.hasServerUrl) "Tap Use Quick Connect to get a code." else "Choose your server above, or type its address.",
                    style = tetsubiType.control.copy(fontWeight = FontWeight.Normal),
                    color = TetsubiColors.textTertiary,
                    textAlign = TextAlign.Center,
                )
                PrimaryButton("Use Quick Connect", onClick = model::startQuickConnect, enabled = model.hasServerUrl, modifier = Modifier.fillMaxWidth())
            }
        }
        LinkButton("Sign in with username & password", onClick = model::useManual)
    }
}

// Username & password

@Composable
private fun ManualPanel(model: SignInModel) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Username & password", style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary)
        model.errorMessage?.let { ErrorText(it) }
        Field("Username") {
            OutlinedTextField(
                value = model.username,
                onValueChange = { model.username = it },
                placeholder = { Text("Username", color = TetsubiColors.textTertiary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                colors = fieldColors(),
                shape = RoundedCornerShape(Radii.card),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Field("Password") {
            OutlinedTextField(
                value = model.password,
                onValueChange = { model.password = it },
                placeholder = { Text("Password", color = TetsubiColors.textTertiary) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { model.signIn() }),
                colors = fieldColors(),
                shape = RoundedCornerShape(Radii.card),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PrimaryButton(
            if (model.isConnecting) "Signing in…" else "Sign in", onClick = model::signIn,
            enabled = model.canSignIn, working = false, modifier = Modifier.fillMaxWidth(),
        )
        LinkButton("Use Quick Connect instead", enabled = !model.isConnecting, onClick = model::useQuickConnect)
    }
}

// Building blocks

@Composable
private fun Field(label: String, input: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = tetsubiType.caption, color = TetsubiColors.textSecondary)
        input()
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = TetsubiColors.surface,
    unfocusedContainerColor = TetsubiColors.surface,
    focusedBorderColor = TetsubiColors.blue,
    unfocusedBorderColor = TetsubiColors.hairline,
    focusedTextColor = TetsubiColors.textPrimary,
    unfocusedTextColor = TetsubiColors.textPrimary,
    cursorColor = TetsubiColors.blue,
)

@Composable
private fun Spinner(color: Color = TetsubiColors.textSecondary) {
    CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spinner()
        Text(text, style = tetsubiType.control.copy(fontWeight = FontWeight.Normal), color = TetsubiColors.textSecondary)
    }
}

@Composable
private fun ErrorText(message: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        WarningIcon(18.dp, TetsubiColors.red)
        Text(message, style = tetsubiType.control.copy(fontWeight = FontWeight.Normal), color = TetsubiColors.red, modifier = Modifier.weight(1f))
    }
}

/** The link-style buttons that swap between the two sign-in methods. */
@Composable
private fun LinkButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, style = tetsubiType.control, color = if (enabled) TetsubiColors.blue else TetsubiColors.textTertiary)
    }
}
