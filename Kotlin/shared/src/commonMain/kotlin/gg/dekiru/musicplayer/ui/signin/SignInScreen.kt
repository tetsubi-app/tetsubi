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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import gg.dekiru.musicplayer.ui.TetsubiColors
import org.jetbrains.compose.resources.painterResource

/**
 * Sign in to a Jellyfin server (as `ServerConfigView` on iOS): the disc and
 * title, the server box (address field + servers on the network), then the
 * Quick Connect code or the username & password form.
 */
@Composable
fun SignInScreen(authenticator: Authenticator, onConnected: (JellyfinApi) -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember { SignInModel(authenticator, scope, onConnected) }
    val quickConnectState by model.quickConnect.state.collectAsState()

    LaunchedEffect(Unit) { model.onAppear() }
    LaunchedEffect(quickConnectState) {
        // Any Quick Connect failure reveals the manual form with the reason.
        (quickConnectState as? State.Failed)?.let { model.onQuickConnectFailed(it.message) }
    }

    Box(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(16.dp),
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

@Composable
private fun Header() {
    Column(
        Modifier.padding(top = 40.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painterResource(Res.drawable.disc),
            contentDescription = null,
            modifier = Modifier.size(112.dp).shadow(12.dp, RoundedCornerShape(56.dp), clip = false),
        )
        Text("Sign in to Jellyfin", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Tetsubi", color = TetsubiColors.albumArtistText, fontSize = 15.sp)
    }
}

// Server

@Composable
private fun ServerBox(model: SignInModel) {
    Panel {
        Field("Server URL") {
            OutlinedTextField(
                value = model.serverUrl,
                onValueChange = { model.serverUrl = it },
                placeholder = { Text(ServerAddress.EXAMPLE, color = TetsubiColors.albumYearText) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { model.submitServer() }),
                colors = fieldColors(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(14.dp))
        DiscoveryList(model)
    }
}

@Composable
private fun DiscoveryList(model: SignInModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("On your network", color = TetsubiColors.albumArtistText, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (model.isSearching) {
                Spinner()
            } else {
                LinkButton("Search again", onClick = model::search)
            }
        }
        if (model.discovered.isEmpty()) {
            Text(
                if (model.isSearching || !model.hasSearched) "Looking for servers…"
                else "No servers found. Type your server's address above.",
                color = TetsubiColors.albumYearText,
                fontSize = 13.sp,
            )
        }
        model.discovered.forEach { server ->
            val selected = server.address == model.trimmedServerUrl
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) TetsubiColors.blue.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(role = Role.Button) { model.pick(server) }
                    .semantics { this.selected = selected }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(server.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(server.address, color = TetsubiColors.albumArtistText, fontSize = 12.sp)
                }
                if (selected) Text("✓", color = TetsubiColors.blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Quick Connect

@Composable
private fun QuickConnectPanel(model: SignInModel, state: State) {
    Panel(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Quick Connect", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Approve this login on your Jellyfin server, or in the Jellyfin app on another device you're signed into.",
            color = TetsubiColors.albumArtistText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(10.dp))
                Waiting("Waiting for approval…")
            }
            state is State.Failed -> ErrorText(state.message)
            else -> Text(
                if (model.hasServerUrl) "Press Go to get a code." else "Choose your server above, or type its address.",
                color = TetsubiColors.albumYearText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        LinkButton("Sign in with username & password", onClick = model::useManual)
    }
}

// Username & password

@Composable
private fun ManualPanel(model: SignInModel) {
    Panel(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Username & password", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        model.errorMessage?.let {
            Spacer(Modifier.height(14.dp))
            ErrorText(it)
        }
        Spacer(Modifier.height(14.dp))
        Field("Username") {
            OutlinedTextField(
                value = model.username,
                onValueChange = { model.username = it },
                placeholder = { Text("Username", color = TetsubiColors.albumYearText) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                colors = fieldColors(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Field("Password") {
            OutlinedTextField(
                value = model.password,
                onValueChange = { model.password = it },
                placeholder = { Text("Password", color = TetsubiColors.albumYearText) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { model.signIn() }),
                colors = fieldColors(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = model::signIn,
            enabled = model.canSignIn,
            colors = ButtonDefaults.buttonColors(containerColor = TetsubiColors.blue, contentColor = Color.White),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (model.isConnecting) {
                Spinner(color = Color.White)
                Spacer(Modifier.size(8.dp))
            }
            Text(if (model.isConnecting) "Signing in…" else "Sign in", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        LinkButton("Use Quick Connect instead", enabled = !model.isConnecting, onClick = model::useQuickConnect)
    }
}

// Building blocks

/** A dark rounded box, as on Settings and Downloads. */
@Composable
private fun Panel(
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TetsubiColors.groupBackground, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

@Composable
private fun Field(label: String, input: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TetsubiColors.albumArtistText, fontSize = 12.sp)
        input()
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.1f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
    focusedBorderColor = TetsubiColors.blue,
    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = TetsubiColors.blue,
)

@Composable
private fun Spinner(color: Color = TetsubiColors.albumArtistText) {
    CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spinner()
        Text(text, color = TetsubiColors.albumArtistText, fontSize = 13.sp)
    }
}

@Composable
private fun ErrorText(message: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠", color = errorColor, fontSize = 13.sp)
        Text(message, color = errorColor, fontSize = 13.sp)
    }
}

private val errorColor = Color(1f, 0.55f, 0.55f)

/** The link-style buttons that swap between the two sign-in methods. */
@Composable
private fun LinkButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = if (enabled) TetsubiColors.blue else TetsubiColors.albumYearText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
