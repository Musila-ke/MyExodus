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
    val discreet: Boolean,
)

/** Declared sizes, bracketing what the tested launchers actually report for a 2×1 and a 4×2 cell.
 *  They are breakpoint hints, not identities — [EXPANDED_MIN_HEIGHT] is what the layout branches
 *  on, so any size a launcher reports picks the right variant (spec §7 Step 5/§12). */
internal val COMPACT_SIZE = DpSize(120.dp, 60.dp)
internal val EXPANDED_SIZE = DpSize(260.dp, 110.dp)

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
        // Compact shows one line: the day count alone, or "Day 12 · Protected" when the user has
        // opted into the status word — it still has to fit a 2×1.
        if (LocalSize.current.height < EXPANDED_MIN_HEIGHT) {
            // Measured on the fleet launcher (XOS, 5-column 720px @320dpi): a 2×1 gives the text
            // 124dp. The day-only line is ~60dp at 18sp; the detailed line needs ~141dp at 18sp
            // and clips, so it drops to 14sp (~110dp for "Day 12 · Protected"). Both stay single
            // line: truncating the status word would leave the claim incomplete, which is exactly
            // what FR3a forbids.
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (view.discreet) dayText else "$dayText · $statusText",
                    style = TextStyle(
                        color = GlanceTheme.colors.tertiary,
                        fontSize = if (view.discreet) 18.sp else 14.sp,
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
