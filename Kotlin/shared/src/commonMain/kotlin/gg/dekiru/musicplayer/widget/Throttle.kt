package gg.dekiru.musicplayer.widget

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * Passes the first value on at once, then at most one every
 * `periodMillis`, always the latest: a burst of changes (skipping through
 * an album, seeking) updates the widget about once a second, and the last
 * change is never lost.
 */
fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> =
    conflate().transform {
        emit(it)
        delay(periodMillis)
    }
