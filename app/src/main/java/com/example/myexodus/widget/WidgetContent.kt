package com.example.myexodus.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.myexodus.R

/** Everything one sample renders, computed once per read and compared as a whole. */
internal data class WidgetView(
    val status: DisplayedStatus,
    val streakDays: Int?,
    /** Names the track the day count belongs to (audit finding #12: with 2+ habits an unlabelled
     *  number is the wrong track as far as the user can tell). Null = no habit, or a sample from
     *  before the field existed. */
    val habitLabel: String?,
    val discreet: Boolean,
)

/** Declared sizes, bracketing what the tested launchers actually report for a 2×1, a 4×2, and the
 *  tall 2×2. They are breakpoint hints, not identities — [EXPANDED_MIN_HEIGHT] is what the layout
 *  branches on, so any size a launcher reports picks the right variant (spec §7 Step 5/§12).
 *  [NARROW_SIZE] exists because a 2×2 reports roughly 120×110: without a preset near it the
 *  launcher maps the cell to [COMPACT_SIZE] by width and the bottom half renders empty. */
internal val COMPACT_SIZE = DpSize(120.dp, 60.dp)
internal val EXPANDED_SIZE = DpSize(260.dp, 110.dp)
internal val NARROW_SIZE = DpSize(120.dp, 110.dp)

/** A 4×1 has the width for the expanded row but not the height for a button plus two lines, so
 *  height is the only axis that decides. Tune against real launcher values, not in the abstract. */
private val EXPANDED_MIN_HEIGHT = 100.dp

/**
 * The widget's whole UI: the streak count, plus protection status and the recovery deep link when
 * there is room and the user has asked for them.
 *
 * Copy is discreet by default — a day count with no status word and no recovery vocabulary (FR5).
 */
@Composable
internal fun DesertDawnWidgetContent(view: WidgetView, manageUrge: Action, openApp: Action) {
    val dayText = view.streakDays
        ?.let { LocalContext.current.getString(R.string.widget_day_count, it) }
        ?: LocalContext.current.getString(R.string.widget_day_unknown)
    val statusText = LocalContext.current.getString(view.status.labelRes())

    // The whole surface is tappable so any launcher-initiated tap re-enters the app, whose
    // cold-start/resume path re-checks protection and re-publishes the snapshot (spec NFR5).
    // The inner "Manage urge" target keeps its own click — RemoteViews gives the child priority.
    Scaffold(modifier = GlanceModifier.clickable(openApp)) {
        // Compact shows one line only. Discreet: the day count. Not discreet: the status word
        // alone — "Day 12 · Not protected" needs ~140dp at readable sizes and the measured 2×1
        // budget is 124dp, so any combined line either clips or drops to 11sp. A clipped status
        // word is an incomplete claim, which is what FR3a forbids, so the status wins the line.
        // Not gold: gold is the streak token, and the status word is not a streak (finding #10).
        if (LocalSize.current.height < EXPANDED_MIN_HEIGHT) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (view.discreet) dayText else statusText,
                    style = TextStyle(
                        color = if (view.discreet) {
                            GlanceTheme.colors.tertiary
                        } else {
                            GlanceTheme.colors.onBackground
                        },
                        fontSize = if (view.discreet) 18.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = GlanceModifier.fillMaxSize().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = dayText,
                style = TextStyle(
                    color = GlanceTheme.colors.tertiary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (!view.discreet) {
                // Which habit the number belongs to, under the number it explains.
                view.habitLabel?.let { label ->
                    Text(
                        text = label,
                        style = TextStyle(
                            color = GlanceTheme.colors.onBackground,
                            fontSize = 12.sp,
                        ),
                        maxLines = 1,
                    )
                }
                Text(
                    text = statusText,
                    style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            // The one action, and the reason the 4×2 exists: reach help from the urge moment
            // without opening the app first (FR4). Reuses the app's existing Recovery deep link.
            Row(modifier = GlanceModifier.padding(top = 2.dp)) {
                Text(
                    text = LocalContext.current.getString(R.string.widget_manage_urge),
                    modifier = GlanceModifier
                        .clickable(manageUrge)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Status words are the only place protection is named, and they are unreachable in discreet mode.
 * "Unverified" is deliberately not "Unknown protection" or "Error": it says what is true — the app
 * cannot confirm — without claiming protection is off, which it has not established (FR3a).
 */
private fun DisplayedStatus.labelRes(): Int = when (this) {
    DisplayedStatus.PROTECTED -> R.string.widget_status_protected
    DisplayedStatus.UNPROTECTED -> R.string.widget_status_unprotected
    DisplayedStatus.UNVERIFIED -> R.string.widget_status_unverified
}
