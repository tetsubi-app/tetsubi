package gg.dekiru.musicplayer.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.home.StatusBanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Making playlists and adding songs to them: the rules, the wording, and
// the "Add to Playlist…" sheet. The same rules and words as iOS.

/** A playlist's name as it's saved. */
object PlaylistName {
    /** Spaces and line breaks at the ends removed; null when nothing is left (the server would take an empty name). */
    fun cleaned(raw: String): String? = raw.trim().ifEmpty { null }
}

/** What to add when some of the songs may already be in the playlist. */
object PlaylistAdd {
    data class Plan(
        /** Every song asked for, in order. */
        val all: List<String>,
        /** Those not in the playlist yet, in order. */
        val notThereYet: List<String>,
    ) {
        /** Ask only when at least one song is already there. */
        val needsQuestion: Boolean get() = notThereYet.size < all.size

        /**
         * The songs to send: all of them for "Add", those not there yet for
         * "Skip". Nothing is sent when this is empty (one song that's
         * already there, skipped; or an album all of which is).
         */
        fun trackIds(addAgain: Boolean): List<String> = if (addAgain) all else notThereYet
    }

    /** `adding`: the songs chosen; `existing`: the playlist's songs now. */
    fun plan(adding: List<String>, existing: List<String>): Plan {
        val there = existing.toSet()
        return Plan(all = adding, notThereYet = adding.filter { it !in there })
    }
}

object PlaylistWording {
    fun duplicateQuestion(playlist: String) = "Already in $playlist. Add again?"
    fun added(count: Int, playlist: String) = if (count == 1) "Added to $playlist." else "Added $count songs to $playlist."
    fun addFailed(playlist: String, error: String?) = "Couldn't add to $playlist. ${error.orEmpty()}"
    fun createFailed(error: String?) = "Couldn't create the playlist. ${error.orEmpty()}"
    fun saveFailed(error: String?) = "Couldn't save your changes. ${error.orEmpty()}"
    fun deleteFailed(error: String?) = "Couldn't delete the playlist. ${error.orEmpty()}"
    fun deleteTitle(playlist: String) = "Delete “$playlist”?"
    const val DELETE_MESSAGE = "This removes the playlist from your server. The songs stay in your library."
}

/**
 * "Add to Playlist…": one for the signed-in app. [start] opens the sheet
 * for some songs; choosing a playlist looks at what's in it, then adds, or
 * asks first when some of the songs are already there. What happened is
 * said in [notice], shown over whatever is in front. [onChanged] is told
 * the playlist that changed (null: a new one) so lists and pages refresh.
 */
class AddToPlaylistModel(
    private val api: JellyfinApi,
    private val scope: CoroutineScope,
    private val onChanged: (playlistId: String?) -> Unit = {},
) {
    data class Question(val playlist: JellyfinPlaylist, val plan: PlaylistAdd.Plan)
    data class Notice(val message: String, val isError: Boolean = false)

    /** The songs being added; the sheet is open while there are some. */
    var trackIds by mutableStateOf<List<String>?>(null)
        private set
    val isOpen: Boolean get() = trackIds != null

    /** The user's playlists by name; only those they may change. */
    var playlists by mutableStateOf<List<JellyfinPlaylist>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    /** A playlist was chosen (or a name given); its songs are being checked or added. */
    var isWorking by mutableStateOf(false)
        private set
    var question by mutableStateOf<Question?>(null)
        private set

    /** "New Playlist…" is asking for a name. */
    var isNaming by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Notice?>(null)
        private set

    private var loadJob: Job? = null
    private var workJob: Job? = null

    /** Opens the sheet for these songs. */
    fun start(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        dismiss()
        notice = null
        this.trackIds = trackIds
        load()
    }

    /** Fetches the playlists and asks, for each, whether the user may change it. */
    fun load() {
        if (!isOpen) return
        isLoading = true
        loadError = null
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val found = api.playlists()
                val editable = coroutineScope { found.map { async { api.canEditPlaylist(it.id) } }.awaitAll() }
                playlists = PlaylistsModel.sort(found.filterIndexed { i, _ -> editable[i] }, PlaylistSort.NAME)
                loaded = true
            } catch (e: JellyfinError) {
                loadError = "Couldn't load your playlists. ${e.message}"
            }
            isLoading = false
        }
    }

    /** The playlist was tapped. */
    fun choose(playlist: JellyfinPlaylist) {
        val ids = trackIds ?: return
        if (isWorking) return
        isWorking = true
        workJob = scope.launch {
            try {
                val plan = PlaylistAdd.plan(ids, api.playlistTracks(playlist.id).map { it.track.id })
                if (plan.needsQuestion) {
                    question = Question(playlist, plan)
                } else {
                    add(plan.all, playlist)
                }
            } catch (e: JellyfinError) {
                fail(PlaylistWording.addFailed(playlist.name, e.message))
            }
        }
    }

    /** "Add" (every song again) or "Skip" (only those not there yet). */
    fun answer(addAgain: Boolean) {
        val asked = question ?: return
        question = null
        workJob = scope.launch { add(asked.plan.trackIds(addAgain), asked.playlist) }
    }

    /** The question was put away without an answer: back to the list. */
    fun questionDismissed() {
        question = null
        isWorking = false
    }

    private suspend fun add(ids: List<String>, playlist: JellyfinPlaylist) {
        // Skipped, and every song was already there: nothing to send.
        if (ids.isEmpty()) {
            dismiss()
            return
        }
        try {
            api.addToPlaylist(playlist.id, ids)
            finish(Notice(PlaylistWording.added(ids.size, playlist.name)))
            onChanged(playlist.id)
        } catch (e: JellyfinError) {
            fail(PlaylistWording.addFailed(playlist.name, e.message))
        }
    }

    fun askForName() {
        if (isOpen && !isWorking) isNaming = true
    }

    fun nameDismissed() {
        isNaming = false
    }

    /** "New Playlist…": makes it with the songs already in it. An empty name is refused (false). */
    fun create(raw: String): Boolean {
        val ids = trackIds ?: return false
        val name = PlaylistName.cleaned(raw) ?: return false
        if (isWorking) return false
        isWorking = true
        isNaming = false
        workJob = scope.launch {
            try {
                api.createPlaylist(name, ids)
                finish(Notice(PlaylistWording.added(ids.size, name)))
                onChanged(null)
            } catch (e: JellyfinError) {
                fail(PlaylistWording.createFailed(e.message))
            }
        }
        return true
    }

    /** Closes the sheet; anything still being asked of the server is dropped. */
    fun dismiss() {
        loadJob?.cancel()
        workJob?.cancel()
        close()
    }

    fun dismissNotice() {
        notice = null
    }

    private fun finish(result: Notice) {
        close()
        notice = result
    }

    private fun fail(message: String) = finish(Notice(message, isError = true))

    private fun close() {
        trackIds = null
        playlists = emptyList()
        isLoading = false
        loaded = false
        loadError = null
        isWorking = false
        question = null
        isNaming = false
    }
}

// The sheet

/** The sheet, its question and its name prompt; nothing while the model has no songs to add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(model: AddToPlaylistModel) {
    if (!model.isOpen) return
    ModalBottomSheet(
        onDismissRequest = model::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TetsubiColors.background,
        contentColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Add to Playlist", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = model::dismiss) { Text("Cancel", color = TetsubiColors.blue, fontSize = 16.sp) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                item(key = "new") {
                    SheetRow(enabled = !model.isWorking, onClick = model::askForName) {
                        Text("+", color = TetsubiColors.blue, fontSize = 22.sp)
                        Text("New Playlist…", color = TetsubiColors.blue, fontSize = 17.sp)
                    }
                }
                val error = model.loadError
                when {
                    error != null -> item(key = "error") { StatusBanner(error, "Retry", model::load) }
                    model.isLoading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                        }
                    }
                    else -> items(model.playlists, key = { it.id }) { playlist ->
                        SheetRow(enabled = !model.isWorking, onClick = { model.choose(playlist) }) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(playlist.displayName, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(PlaylistsModel.songCount(playlist.songCount), color = TetsubiColors.albumArtistText, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    model.question?.let { question ->
        AlertDialog(
            onDismissRequest = model::questionDismissed,
            title = { Text(PlaylistWording.duplicateQuestion(question.playlist.displayName)) },
            confirmButton = { TextButton(onClick = { model.answer(addAgain = true) }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { model.answer(addAgain = false) }) { Text("Skip") } },
        )
    }
    if (model.isNaming) {
        PlaylistNameDialog(title = "New Playlist", action = "Create", initial = "", onDismiss = model::nameDismissed) { model.create(it) }
    }
}

@Composable
private fun SheetRow(enabled: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
}

/**
 * Asks for a playlist's name ("New Playlist", "Rename Playlist"). The
 * action does nothing while the name, trimmed, is empty; `onDone` says
 * whether the name was taken.
 */
@Composable
fun PlaylistNameDialog(title: String, action: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Boolean) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val valid = PlaylistName.cleaned(name) != null
    fun done() {
        if (valid && onDone(name)) onDismiss()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (name.isEmpty()) Text("Playlist name", color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp, maxLines = 1)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(TetsubiColors.blue),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Playlist name" },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::done, enabled = valid) { Text(action, color = if (valid) TetsubiColors.blue else Color.White.copy(alpha = 0.3f)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What adding did ("Added to <name>."), or why it couldn't: the usual
 * banner on a solid backing so it reads over whatever is under it. Goes
 * away by itself; tap to put it away sooner.
 */
@Composable
fun PlaylistNoticeBanner(model: AddToPlaylistModel, modifier: Modifier = Modifier) {
    val notice = model.notice ?: return
    NoticeBanner(notice.message, notice.isError, notice, model::dismissNotice, modifier)
}

/** What Play Next / Add to Queue did, as [PlaylistNoticeBanner]. */
@Composable
fun QueueNoticeBanner(player: gg.dekiru.musicplayer.playback.PlayerController, modifier: Modifier = Modifier) {
    val notice = player.queueNotice ?: return
    NoticeBanner(notice.message, notice.isError, notice, player::dismissQueueNotice, modifier)
}

@Composable
private fun NoticeBanner(message: String, isError: Boolean, key: Any, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(key) {
        delay(if (isError) 6_000 else 3_000)
        onDismiss()
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(TetsubiColors.background, RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clickable(onClick = onDismiss)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (isError) "⚠" else "✓", color = TetsubiColors.pink, fontSize = 15.sp)
        Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
