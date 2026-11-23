package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.TetsubiColors

/*
 * Play Next / Add to Queue wherever an album, playlist or song is shown
 * (as iOS's menus and context menus): in ⋯ menus, and on a long press
 * (touch) or right-click (mouse) of cards and rows. The player confirms
 * with its banner ("Playing next" / "Added to queue").
 */

/** The signed-in player, for the cards' menus (null: none, or no player on this device). */
val LocalPlayer = compositionLocalOf<PlayerController?> { null }

/** One menu item. */
class MenuAction(val title: String, val enabled: Boolean = true, val action: () -> Unit)

object QueueMenuText {
    const val PLAY_NEXT = "Play Next"
    const val ADD_TO_QUEUE = "Add to Queue"
}

/** "Play Next" and "Add to Queue" for an album (every disc); none without a player. */
@Composable
fun albumQueueActions(album: JellyfinAlbum): List<MenuAction> {
    val player = LocalPlayer.current?.takeIf { it.isSupported } ?: return emptyList()
    return queueActions({ player.playNext(album) }, { player.addToQueue(album) })
}

/** "Play Next" and "Add to Queue" for a playlist (its songs are fetched); none without a player. */
@Composable
fun playlistQueueActions(playlist: JellyfinPlaylist): List<MenuAction> {
    val player = LocalPlayer.current?.takeIf { it.isSupported } ?: return emptyList()
    return queueActions({ player.playNext(playlist) }, { player.addToQueue(playlist) })
}

fun queueActions(playNext: () -> Unit, addToQueue: () -> Unit): List<MenuAction> = listOf(
    MenuAction(QueueMenuText.PLAY_NEXT, action = playNext),
    MenuAction(QueueMenuText.ADD_TO_QUEUE, action = addToQueue),
)

/**
 * A tap does `onClick`; a long press, or a right-click with a mouse, does
 * `onMenu` (when there is one). Use inside [WithContextMenu].
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.menuClickable(
    onClick: () -> Unit,
    onMenu: (() -> Unit)?,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    enabled: Boolean = true,
): Modifier =
    if (onMenu == null) {
        clickable(enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
    } else {
        onRightClick(onMenu).combinedClickable(
            enabled = enabled, role = role, onClickLabel = onClickLabel,
            onLongClickLabel = "More", onLongClick = onMenu, onClick = onClick,
        )
    }

/** A right-click (secondary button) does `action`; it isn't passed on as a click. */
fun Modifier.onRightClick(action: () -> Unit): Modifier = pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                action()
            }
        }
    }
}

/**
 * `content` with a menu of `items` that opens over it: `content` gets the
 * opener (null when there are no items) for [menuClickable].
 */
@Composable
fun WithContextMenu(items: List<MenuAction>, modifier: Modifier = Modifier, content: @Composable (openMenu: (() -> Unit)?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        content(if (items.isEmpty()) null else ({ open = true }))
        if (items.isNotEmpty()) MenuDropdown(open, { open = false }, items)
    }
}

/** The dropdown the ⋯ buttons and context menus share. */
@Composable
fun MenuDropdown(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuAction>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (item in items) {
            DropdownMenuItem(
                text = { Text(item.title, color = if (item.enabled) Color.White else Color.White.copy(alpha = 0.4f)) },
                enabled = item.enabled,
                onClick = {
                    onDismiss()
                    item.action()
                },
            )
        }
    }
}

/** A ⋯ button and its menu (nothing when there are no items). */
@Composable
fun MoreMenuButton(label: String, items: List<MenuAction>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(50))
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Text("⋯", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        MenuDropdown(open, { open = false }, items)
    }
}

// The Play prompt

/** What Play would do, and the alternatives the prompt offers. */
class PlayChoice(
    /** The album's or playlist's name, for the prompt's title. */
    val title: String,
    val replace: () -> Unit,
    val playNext: () -> Unit,
    val addToQueue: () -> Unit,
)

/**
 * Play on an album or playlist (or a tapped song) while more than the
 * playing album is queued asks first: Replace queue · Play Next · Add to
 * Queue · Cancel. With nothing else queued it plays at once, as before.
 */
@Stable
class PlayPrompt {
    var pending by mutableStateOf<PlayChoice?>(null)
        private set

    /** Plays now, or asks when `player` has more than the playing album queued. */
    fun play(player: PlayerController, choice: PlayChoice) {
        if (player.asksBeforePlay) pending = choice else choice.replace()
    }

    fun dismiss() {
        pending = null
    }

    companion object {
        /** Play “Abbey Road”. */
        fun title(name: String) = "Play \u201C$name\u201D"
        const val MESSAGE = "There's other music in the queue."
        const val REPLACE = "Replace queue"
        const val CANCEL = "Cancel"
    }
}

/** The app's prompt (one, shown by [PlayPromptDialog] over everything). */
val LocalPlayPrompt = staticCompositionLocalOf { PlayPrompt() }

@Composable
fun PlayPromptDialog(prompt: PlayPrompt) {
    val choice = prompt.pending ?: return
    fun pick(action: () -> Unit) {
        prompt.dismiss()
        action()
    }
    AlertDialog(
        onDismissRequest = prompt::dismiss,
        title = { Text(PlayPrompt.title(choice.title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PlayPrompt.MESSAGE, modifier = Modifier.padding(bottom = 8.dp))
                PromptButton(PlayPrompt.REPLACE) { pick(choice.replace) }
                PromptButton(QueueMenuText.PLAY_NEXT) { pick(choice.playNext) }
                PromptButton(QueueMenuText.ADD_TO_QUEUE) { pick(choice.addToQueue) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = prompt::dismiss) { Text(PlayPrompt.CANCEL) } },
    )
}

@Composable
private fun PromptButton(title: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = TetsubiColors.blue, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
    }
}
