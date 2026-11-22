package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * An artist line (album page, Now Playing) that opens the artist's albums
 * but looks exactly like the plain text: no padding or styling, just the
 * usual press ripple over the text, the hand pointer on the desktop, and
 * read out as "Show albums by …". Null [onClick]: plain text.
 */
fun Modifier.artistLink(name: String, onClick: (() -> Unit)?): Modifier =
    if (onClick == null) {
        this
    } else {
        this
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = "Show albums by $name", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Show albums by $name" }
    }
