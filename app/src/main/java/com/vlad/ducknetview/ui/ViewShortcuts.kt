package com.vlad.ducknetview.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vlad.ducknetview.MainActivity
import com.vlad.ducknetview.R
import com.vlad.ducknetview.domain.views.SavedView

/**
 * Saved views as launcher shortcuts: long-press the icon and open straight
 * into "VPN traffic, busiest first". The TUI's `--view` from a shell alias,
 * in the shape a phone has.
 */
object ViewShortcuts {

    fun publish(context: Context, views: List<SavedView>) {
        val max = runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }
            .getOrDefault(4)
            .coerceAtMost(MAX)
        val shortcuts = views.take(max).mapIndexed { i, v ->
            ShortcutInfoCompat.Builder(context, idOf(v))
                .setShortLabel(v.name.take(SHORT_LABEL))
                .setLongLabel(v.name)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setRank(i)
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(MainActivity.EXTRA_VIEW, v.name)
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun idOf(v: SavedView) = "view:" + v.name.lowercase()

    private const val MAX = 4
    private const val SHORT_LABEL = 10
}
