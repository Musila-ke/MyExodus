package com.example.myexodus.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.MilestoneEvent

/** Everything one sample renders, computed once per read and compared as a whole. */
internal data class WidgetView(
    val status: DisplayedStatus,
    val streakDays: Int?,
    /** Names the track the day count belongs to (audit finding #12: with 2+ habits an unlabelled
     *  number is the wrong track as far as the user can tell). Null = no habit, or a sample from
     *  before the field existed. */
    val habitLabel: String?,
    /** The id the "Log a relapse" control binds (widget v2): the exact track the number above it
     *  belongs to. Null = no habit or a pre-v2 sample — the control hides rather than binding a
     *  guessed track. */
    val habitId: String?,
    /** Age of the stored reading at render time when the day count can no longer be *proven*
     *  current — a day boundary has passed since it was read, or the device rebooted. Null when
     *  the number needs no qualifier. It renders in the footnote slot, ahead of the milestone
     *  line: a stale count makes that line's arithmetic equally stale. */
    val streakCheckedAgoMillis: Long?,
    /** The next 7/30/90 rung and how many whole days away it is; null when there is none ahead
     *  (past 90, or no streak to measure). */
    val nextMilestoneDay: Int?,
    val nextMilestoneDaysAway: Int?,
    val discreet: Boolean,
)

/** Declared sizes, bracketing what the tested launchers actually report for a 2×1, a 4×2, the
 *  tall 2×2, and a resized full-height card. They are breakpoint hints, not identities —
 *  [EXPANDED_MIN_HEIGHT] and [TALL_MIN_HEIGHT] are what the layout branches on, so any size a
 *  launcher reports picks the right variant (spec §7 Step 5/§12). [NARROW_SIZE] exists because a
 *  2×2 reports roughly 120×110: without a preset near it the launcher maps the cell to
 *  [COMPACT_SIZE] by width and the bottom half renders empty. [TALL_SIZE] is declared at the size
 *  launchers actually *report*, not the size they draw: launchers match on the nominal AOSP grid
 *  (74dp/row — 2 rows = 174dp) while drawing rows far larger, so the tested XOS launcher reports
 *  a resized 2-row card as 312×174dp and renders it ~250dp tall. A preset declared at the visual
 *  size is never matched, and the card stretches the 110dp preset into a hero stranded over a
 *  void (user-flagged 2026-09-27). At the reported size it is matched exactly (verified in the
 *  debug options log, 2026-09-27) and gets its own, band-filled composition. */
internal val COMPACT_SIZE = DpSize(120.dp, 60.dp)
internal val EXPANDED_SIZE = DpSize(260.dp, 110.dp)
internal val NARROW_SIZE = DpSize(120.dp, 110.dp)
internal val TALL_SIZE = DpSize(312.dp, 174.dp)

/** The hero line plus a data strip needs this much; a 2×1 does not have it. Tune against real
 *  launcher values, not in the abstract. */
private val EXPANDED_MIN_HEIGHT = 100.dp

/** From here up the card has room for the progress band. Between the 110dp band-card presets and
 *  [TALL_SIZE]'s reported 174dp, so no stretched preset can cross into the tall layout. */
private val TALL_MIN_HEIGHT = 140.dp

/** The data strip runs its cells side by side above this width; below it the card takes the narrow
 *  composition. A 2-column cell honestly reports ~110dp, where a side-by-side cell is ~27dp and
 *  clips every value; the 2×2 preset is the size this routes. */
private val WIDE_MIN_WIDTH = 200.dp

/**
 * The next un-reached milestone rung and how many whole days stand between the current day and
 * it. Pure, so the arithmetic is testable without a composition.
 */
internal fun nextMilestone(days: Int?): Pair<Int, Int>? {
    if (days == null) return null
    val next = MilestoneEvent.MILESTONE_DAYS.firstOrNull { it > days } ?: return null
    return next to (next - days)
}

/**
 * How far the day count stands toward the next rung, 0..1 — the tall card's progress bar. Null
 * when either side is missing or the rung is not a positive number to measure against.
 */
internal fun milestoneFraction(days: Int?, next: Int?): Float? {
    if (days == null || next == null || next <= 0) return null
    return (days.toFloat() / next).coerceIn(0f, 1f)
}

/**
 * The widget's whole UI, in four variants by the size the launcher reports: the one-line compact
 * card, the narrow 2×2, the band card, and the full-height card a resize produces. Each variant
 * is a whole composition, not a stretched copy of the smaller one — the launcher's RemoteViews
 * host re-measures containers but never rescales type, so slack has to be absorbed by weighted
 * bands (user-flagged 2026-09-27: the resized card rendered a hero over a void). The two wide
 * variants carry the dashboard's action pills — relapse and SOS (owner spec 2026-10-15).
 *
 * Copy is discreet by default — a day count with no status word and no recovery vocabulary (FR5).
 */
@Composable
internal fun DesertDawnWidgetContent(
    view: WidgetView,
    openStreaks: Action,
    manageUrge: Action,
    openApp: Action,
    logRelapse: Action,
    reachOut: Action,
) {
    val context = LocalContext.current
    val statusText = context.getString(view.status.labelRes())
    val footnote = footnoteFor(context, view, shortForm = false)

    // The whole surface is tappable so any launcher-initiated tap re-enters the app, whose
    // cold-start/resume path re-checks protection and re-publishes the snapshot (spec NFR5).
    // The inner targets keep their own clicks — RemoteViews gives the child priority.
    Scaffold(modifier = GlanceModifier.clickable(openApp)) {
        when {
            LocalSize.current.height < EXPANDED_MIN_HEIGHT ->
                CompactContent(view, statusText, footnote, openStreaks)

            LocalSize.current.height >= TALL_MIN_HEIGHT -> TallContent(
                view = view,
                statusText = statusText,
                openStreaks = openStreaks,
                logRelapse = logRelapse,
                reachOut = reachOut,
            )

            LocalSize.current.width < WIDE_MIN_WIDTH -> NarrowContent(
                view = view,
                statusText = statusText,
                manageUrge = manageUrge,
                openStreaks = openStreaks,
            )

            else -> ExpandedContent(
                view = view,
                statusText = statusText,
                openStreaks = openStreaks,
                logRelapse = logRelapse,
                reachOut = reachOut,
            )
        }
    }
}

/**
 * The compact variant: one content line plus the footnote. Discreet: the day count, in the hero
 * lockup. Not discreet: the status word alone — "Day 12 · Not protected" needs ~140dp at readable
 * sizes and the measured 2×1 budget is 124dp, so any combined line either clips or drops to
 * 11sp. A clipped status word is an incomplete claim, which is what FR3a forbids, so the status
 * wins the line and the day count waits for the bigger variant. Not gold either: gold is the
 * streak token, and the status word is not a streak (finding #10).
 */
@Composable
private fun CompactContent(
    view: WidgetView,
    statusText: String,
    footnote: String?,
    openStreaks: Action,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (view.discreet) {
            DayLockup(view, openStreaks, numeralSize = 26.sp)
        } else {
            Text(
                text = statusText,
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
        footnote?.let {
            Text(
                text = it,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * The band card, composed as bands rather than a headline with air under it (reference port
 * 2026-09-27, gallery `11.54.56`): hero row with the state chip, a hairline, the caption line,
 * then the two action pills on the bottom edge. Every band carries information. The weighted
 * spacer before the hairline keeps the caption and pills on the card's bottom edge when a
 * launcher stretches the preset past its nominal height.
 */
@Composable
private fun ExpandedContent(
    view: WidgetView,
    statusText: String,
    openStreaks: Action,
    logRelapse: Action,
    reachOut: Action,
) {
    val context = LocalContext.current
    val caption = captionFor(context, view, includeMilestone = true)
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayLockup(view, openStreaks, numeralSize = 32.sp)
            if (!view.discreet) {
                Spacer(GlanceModifier.defaultWeight())
                StatusChip(statusText)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Box(
            GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GlanceTheme.colors.outline),
        ) {}
        Spacer(GlanceModifier.height(4.dp))
        caption?.let {
            Text(
                text = it,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(4.dp))
        }
        ActionRow(view, logRelapse, reachOut)
    }
}

/**
 * The tall 2×2 card ([NARROW_SIZE]): the compact stack plus the card's action, because at the
 * ~110dp a 2-column cell honestly reports there is no room for a data strip — a chip beside the
 * hero clips (an 11sp "Not protected" needs ~88dp by itself) and so does a label-inline value row.
 * The milestone line takes the short form for the same reason ("Day 30 in 23d" is ~72dp at 10sp
 * where the compact card's sentence form is ~94dp). The action rides the same centered stack
 * rather than pinning to the bottom edge: both tested launchers draw this cell far taller than
 * the preset they match (~187dp drawn for a reported 193dp nominal on AOSP, 2026-09-27), and a
 * pinned action turns that slack into the void the tall card was reworked to remove. Centered,
 * the slack lands as margin.
 */
@Composable
private fun NarrowContent(
    view: WidgetView,
    statusText: String,
    manageUrge: Action,
    openStreaks: Action,
) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DayLockup(view, openStreaks, numeralSize = 26.sp)
        if (!view.discreet) {
            Spacer(GlanceModifier.height(3.dp))
            StatusChip(statusText)
        }
        footnoteFor(context, view, shortForm = true)?.let {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = it,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = actionLabel(context, view),
            modifier = GlanceModifier
                .clickable(manageUrge)
                .padding(vertical = 2.dp, horizontal = 6.dp),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The full-height card (`TALL_SIZE`): hero, the progress band, then the caption line and the two
 * action pills — fixed bands with the slack shared above and below the middle band, so a resized
 * card reads as a designed surface at any stretch instead of a headline floating over a void
 * (user-flagged 2026-09-27). The caption drops the milestone rung: the band already carries it.
 */
@Composable
private fun TallContent(
    view: WidgetView,
    statusText: String,
    openStreaks: Action,
    logRelapse: Action,
    reachOut: Action,
) {
    val context = LocalContext.current
    val caption = captionFor(context, view, includeMilestone = false)
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayLockup(view, openStreaks, numeralSize = 34.sp)
            if (!view.discreet) {
                Spacer(GlanceModifier.defaultWeight())
                StatusChip(statusText)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        MilestoneBand(view)
        Spacer(GlanceModifier.defaultWeight())
        Box(
            GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GlanceTheme.colors.outline),
        ) {}
        Spacer(GlanceModifier.height(6.dp))
        // Caption + actions grouped as one nested band, not flat in the outer Column: Glance's
        // RemoteViews translation caps every container at 10 children and silently drops the tail
        // past that (LayoutSelectionKt in glance-appwidget 1.1.1 — javap-verified; device logcat
        // 2026-10-16: "Truncated Column container from 11 to 10 elements", which ate the whole
        // action row whenever the non-discreet caption was present). With the band wrappers this
        // column holds 7 direct children in every path, well clear of the cap.
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start,
        ) {
            caption?.let {
                Text(
                    text = it,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(6.dp))
            }
            ActionRow(view, logRelapse, reachOut)
        }
    }
}

/**
 * The progress band: how far the day count stands toward the next rung, as one label-over-value
 * line plus the bar itself — the tall card's middle ground, filled with the number's own story
 * instead of air. The bar is the library's indicator (a RemoteViews ProgressBar, which every
 * launcher host inflates) so it stretches to the cell's real width rather than to the declared
 * preset's; only the tall variant renders this band.
 */
@Composable
private fun MilestoneBand(view: WidgetView) {
    val context = LocalContext.current
    val days = view.streakDays ?: return
    val target = view.nextMilestoneDay ?: return
    val away = view.nextMilestoneDaysAway ?: return
    // streakDays is the ordinal display day (ADR #128); the fraction counts completed days.
    // coerceAtLeast guards a stale persisted snapshot written by the pre-ruling build.
    val fraction = milestoneFraction((days - 1).coerceAtLeast(0), target) ?: return

    // One nested band (see TallContent): flat, these are 3 more children in the tall card's outer
    // Column, which Glance truncates at 10.
    Column(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = context.getString(R.string.widget_next_milestone_label).uppercase(),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = milestoneText(context, target, away).orEmpty(),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(5.dp))
        // The library's indicator (a RemoteViews ProgressBar, tinted with theme tokens) rather
        // than two boxes: it stretches to the cell's real width, where a hand-drawn bar could
        // only ever be as wide as the declared preset.
        LinearProgressIndicator(
            progress = fraction,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = GlanceTheme.colors.tertiary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
    }
}

/** The protection state as the card's one chip (non-discreet only). */
@Composable
private fun StatusChip(text: String) {
    Text(
        text = text,
        modifier = GlanceModifier
            .background(GlanceTheme.colors.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

/**
 * The card's action row (owner spec 2026-10-15): the relapse control bound to the displayed
 * track, and the SOS control. Both are plain taps onto the hold-confirm surface, where the
 * long-press-and-ring protection lives — no launcher RemoteViews host delivers touch streams,
 * so the ring cannot live on the widget itself (ADR #159).
 *
 * The relapse pill hides when [WidgetView.habitId] is null (no habit, or a sample from before
 * the field existed): a control must never end a track other than the one the number above it
 * names. The SOS pill then takes the full width — reach-out has no track to bind.
 */
@Composable
private fun ActionRow(view: WidgetView, logRelapse: Action, reachOut: Action) {
    val context = LocalContext.current
    val compact = LocalSize.current.width < COMPACT_ACTION_MIN_WIDTH
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (view.habitId != null) {
            PillButton(
                text = context.getString(relapseLabelRes(discreet = view.discreet, compact = compact)),
                action = logRelapse,
                background = GlanceTheme.colors.surfaceVariant,
                foreground = GlanceTheme.colors.onSurfaceVariant,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(10.dp))
        }
        PillButton(
            text = context.getString(R.string.widget_action_reach_out),
            action = reachOut,
            background = GlanceTheme.colors.primary,
            foreground = GlanceTheme.colors.onPrimary,
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

/** Below this the two-pill row no longer fits "Log a relapse" at 12sp — each pill is about
 *  (width − padding − gap)/2 wide, the label needs ~80dp, and RemoteViews hosts apply the user's
 *  font scale on top — so the relapse control falls back to its short form rather than
 *  ellipsizing an action word. */
private val COMPACT_ACTION_MIN_WIDTH = 220.dp

/** One action pill: the rounded filled control of the reference composition. The SOS pill keeps
 *  the one-filled-control-per-composition role; the relapse pill is tonal so the destructive
 *  control never masquerades as the primary one. */
@Composable
private fun PillButton(
    text: String,
    action: Action,
    background: ColorProvider,
    foreground: ColorProvider,
    modifier: GlanceModifier,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .background(background)
            .cornerRadius(17.dp)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

/**
 * The relapse control's label: named as the action it is when there is room, the short form where
 * the row is tight, and the disclosure-free vocabulary ("Reset", never the recovery word) whenever
 * the card is discreet (FR5). Pure, so the matrix is unit-testable.
 */
internal fun relapseLabelRes(discreet: Boolean, compact: Boolean): Int = when {
    discreet -> R.string.widget_action_reset
    compact -> R.string.widget_action_relapse_short
    else -> R.string.widget_action_relapse
}

/**
 * The one footnote slot, honesty first: a qualifier on the number outranks the milestone line,
 * whose arithmetic the stale number would make equally stale. [shortForm] picks the wording for
 * the card's width — the compact sentence where there is room ([CompactContent]), the strip value
 * where there is not ([NarrowContent]).
 */
private fun footnoteFor(context: Context, view: WidgetView, shortForm: Boolean): String? {
    view.streakCheckedAgoMillis?.let {
        return context.getString(R.string.widget_checked_label) + " " + agoText(context, it)
    }
    return if (shortForm) {
        milestoneText(context, view.nextMilestoneDay, view.nextMilestoneDaysAway)
    } else {
        milestoneSentence(context, view.nextMilestoneDay, view.nextMilestoneDaysAway)
    }
}

/**
 * The caption line under the hairline: the track the number belongs to (only when disclosed) and
 * the number's qualifier, joined so a stale count reads as stale. [includeMilestone] adds the
 * next rung in its short form for the card without a progress band — the tall card's band already
 * carries it, so its caption never repeats it.
 */
private fun captionFor(context: Context, view: WidgetView, includeMilestone: Boolean): String? {
    val qualifier = view.streakCheckedAgoMillis?.let {
        context.getString(R.string.widget_checked_label) + " " + agoText(context, it)
    } ?: if (includeMilestone) {
        milestoneText(context, view.nextMilestoneDay, view.nextMilestoneDaysAway)
    } else {
        null
    }
    val label = view.habitLabel?.takeIf { !view.discreet }
    return listOfNotNull(label, qualifier).joinToString(" · ").ifEmpty { null }
}

/**
 * The card's one action label — the same Recovery deep link whether the card calls it "Manage
 * urge" or, discreetly, "Tools" (FR5; "Open" promised the app's main page and user-flagged
 * 2026-09-27: a nav label must not misstate its destination).
 */
private fun actionLabel(context: Context, view: WidgetView): String =
    context.getString(if (view.discreet) R.string.widget_tools else R.string.widget_manage_urge) + " ›"

/**
 * The day count as a stat lockup: the word small and muted, the number large and gold, so the
 * number — the reason the widget exists — carries the visual weight instead of sitting at caption
 * size (reference port, 2026-09-27). Tapping it opens the Streaks tab, not Home: the surface the
 * number belongs to, reached with the same tab extra the milestone notifications use.
 */
@Composable
private fun DayLockup(view: WidgetView, openStreaks: Action, numeralSize: TextUnit) {
    val context = LocalContext.current
    val days = view.streakDays
    Row(
        modifier = GlanceModifier.clickable(openStreaks),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (days == null) {
            // Nothing to make heroic: the honest placeholder, in the muted slot the number
            // would otherwise occupy.
            Text(
                text = context.getString(R.string.widget_day_unknown),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                maxLines = 1,
            )
        } else {
            Text(
                text = context.getString(R.string.widget_day_label),
                modifier = GlanceModifier.padding(bottom = 3.dp),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(4.dp))
            Text(
                text = context.getString(R.string.widget_day_number, days),
                style = TextStyle(
                    color = GlanceTheme.colors.tertiary,
                    fontSize = numeralSize,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

/** "2h ago" — how old the stored reading is. Minutes while it is minutes (a reboot right after a
 *  read), then hours, then days. */
internal fun agoText(context: Context, millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> context.getString(R.string.widget_ago_now)
        minutes < 60 -> context.getString(R.string.widget_ago_minutes, minutes)
        minutes < 24 * 60 -> context.getString(R.string.widget_ago_hours, minutes / 60)
        else -> context.getString(R.string.widget_ago_days, minutes / (24 * 60))
    }
}

/** The next milestone rung as a data-strip value ("Day 30 in 23d"), or null when there is none. */
internal fun milestoneText(context: Context, day: Int?, daysAway: Int?): String? {
    if (day == null || daysAway == null) return null
    return context.getString(R.string.widget_next_value, day, daysAway)
}

/** The same rung as the compact variant's one-line sentence ("Day 30 in 23 days"), or null. */
internal fun milestoneSentence(context: Context, day: Int?, daysAway: Int?): String? {
    if (day == null || daysAway == null) return null
    return context.resources.getQuantityString(R.plurals.widget_next_milestone, daysAway, day, daysAway)
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
