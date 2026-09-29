/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.widget.RemoteViews

/** "3:07", or "1:02:07" for an hour or more. */
internal fun formatWidgetTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Elapsed time and remaining time ("-1:23") shown under the widget progress bar. */
internal fun widgetTimes(duration: Long, position: Long): Pair<String, String> {
    if (duration <= 0) return "" to ""
    val clamped = position.coerceIn(0L, duration)
    return formatWidgetTime(clamped) to "-" + formatWidgetTime(duration - clamped)
}

internal fun RemoteViews.setWidgetTimes(
    elapsedViewId: Int,
    remainingViewId: Int,
    duration: Long,
    position: Long,
) {
    val (elapsed, remaining) = widgetTimes(duration, position)
    setTextViewText(elapsedViewId, elapsed)
    setTextViewText(remainingViewId, remaining)
}
