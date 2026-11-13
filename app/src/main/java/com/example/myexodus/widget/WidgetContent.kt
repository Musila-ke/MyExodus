package com.example.myexodus.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.size
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
    /** The id the "Log a relapse" control binds (owner ruling 2026-10-17): the exact track the
     *  number above it belongs to. Null = no habit or a pre-field sample — the control hides
     *  rather than binding a guessed track. */
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
 * bands (user-flagged 2026-09-27: the resized card rendered a hero over a void). Every variant
 * above the one-line compact card carries the four icon controls — urge assist, log a relapse,
 * log an urge, reach out (owner rulings 2026-10-16/17: icon-only, because text on the launcher
 * surface spells private intent; and the log controls draw the same glyphs as the app's own
 * buttons, so one icon means one action).
 *
 * Copy is discreet by default — a day count with no status word and no recovery vocabulary (FR5).
 */
@Composable
internal fun DesertDawnWidgetContent(
    view: WidgetView,
    openStreaks: Action,
    openApp: Action,
    /** Null when the sample carries no bound habit: no track to end, no control to offer. */
    logRelapse: Action?,
    logUrge: Action,
    reachOut: Action,
    /** Owner add-on 2026-11-07: opens Urge Assist directly. */
    urgeAssist: Action,
) {
    val context = LocalContext.current
    val statusText = context.getString(view.status.labelRes())
    val footnote = footnoteFor(context, view, shortForm = false)

    // The whole surface is tappable so any launcher-initiated tap re-enters the app, whose
    // cold-start/resume path re-checks protection and re-publishes the snapshot (spec NFR5).
    // The inner targets keep their own clicks — RemoteViews gives the child priority.
    // horizontalPadding = 0: Scaffold's default 12dp on each side stacked on top of each variant's
    // own padding and cost the 2x1 card ~25% of its width. Each variant owns its inset instead.
    Scaffold(
        modifier = GlanceModifier.clickable(openApp),
        backgroundColor = GlanceTheme.colors.background,
        horizontalPadding = 0.dp,
    ) {
        when {
            LocalSize.current.height < EXPANDED_MIN_HEIGHT ->
                CompactContent(view, statusText, footnote, openStreaks)

            LocalSize.current.height >= TALL_MIN_HEIGHT -> TallContent(
                view = view,
                statusText = statusText,
                openStreaks = openStreaks,
                logRelapse = logRelapse,
                logUrge = logUrge,
                reachOut = reachOut,
                urgeAssist = urgeAssist,
            )

            LocalSize.current.width < WIDE_MIN_WIDTH -> NarrowContent(
                view = view,
                statusText = statusText,
                openStreaks = openStreaks,
                logRelapse = logRelapse,
                logUrge = logUrge,
                reachOut = reachOut,
                urgeAssist = urgeAssist,
            )

            else -> ExpandedContent(
                view = view,
                statusText = statusText,
                openStreaks = openStreaks,
                logRelapse = logRelapse,
                logUrge = logUrge,
                reachOut = reachOut,
                urgeAssist = urgeAssist,
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
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (view.discreet) {
            DayLockup(view, openStreaks, numeralSize = 28.sp)
        } else {
            // The status word alone (FR3a): the combined line clips at this width. Colored by
            // state so the word carries the same signal as the bigger cards' chip.
            Text(
                text = statusText,
                style = TextStyle(
                    color = statusColor(view.status),
                    fontSize = 15.sp,
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
 * then the icon controls on the bottom edge — trailing, per the reference's control placement.
 * Every band carries information. The weighted spacer before the hairline keeps the caption and
 * controls on the card's bottom edge when a launcher stretches the preset past its nominal height.
 */
@Composable
private fun ExpandedContent(
    view: WidgetView,
    statusText: String,
    openStreaks: Action,
    logRelapse: Action?,
    logUrge: Action,
    reachOut: Action,
    urgeAssist: Action,
) {
    val context = LocalContext.current
    val caption = captionFor(context, view, includeMilestone = true)
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayLockup(view, openStreaks, numeralSize = 38.sp)
            if (!view.discreet) {
                Spacer(GlanceModifier.defaultWeight())
                StatusChip(view.status, statusText)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Hairline()
        Spacer(GlanceModifier.height(5.dp))
        caption?.let {
            Text(
                text = it,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(4.dp))
        }
        ControlRow(logRelapse, logUrge, reachOut, view.discreet, urgeAssist = urgeAssist)
    }
}

/**
 * The tall 2×2 card ([NARROW_SIZE]): the compact stack plus the same four icon controls the wide
 * variants carry (widget v3, 2026-10-17; fourth control added 2026-11-13). At the ~110dp a
 * 2-column cell honestly reports there is no room for a data strip — a chip beside the hero clips
 * and so does a label-inline value row — so the card stays a centered stack. Four controls cannot
 * sit at 28dp here (4×28 + gaps overruns the 104dp content width), so the row tightens to 24dp
 * circles with 2dp gaps (102dp) rather than dropping one action: one control idiom across every
 * variant, and no launcher text naming an action. Both tested launchers draw this cell far
 * taller than the preset they match, so a pinned action would turn that slack into a void;
 * centered, the slack lands as margin.
 */
@Composable
private fun NarrowContent(
    view: WidgetView,
    statusText: String,
    openStreaks: Action,
    logRelapse: Action?,
    logUrge: Action,
    reachOut: Action,
    urgeAssist: Action,
) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DayLockup(view, openStreaks, numeralSize = 30.sp)
        if (!view.discreet) {
            Spacer(GlanceModifier.height(4.dp))
            StatusChip(view.status, statusText)
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
        Spacer(GlanceModifier.height(8.dp))
        ControlRow(
            logRelapse = logRelapse,
            logUrge = logUrge,
            reachOut = reachOut,
            discreet = view.discreet,
            urgeAssist = urgeAssist,
            size = 24.dp,
            alignment = Alignment.CenterHorizontally,
        )
    }
}

/**
 * The full-height card (`TALL_SIZE`): hero, the progress band, then the caption line and the icon
 * controls — fixed bands with the slack shared above and below the middle band, so a resized
 * card reads as a designed surface at any stretch instead of a headline floating over a void
 * (user-flagged 2026-09-27). The caption drops the milestone rung: the band already carries it.
 */
@Composable
private fun TallContent(
    view: WidgetView,
    statusText: String,
    openStreaks: Action,
    logRelapse: Action?,
    logUrge: Action,
    reachOut: Action,
    urgeAssist: Action,
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
            DayLockup(view, openStreaks, numeralSize = 44.sp)
            if (!view.discreet) {
                Spacer(GlanceModifier.defaultWeight())
                StatusChip(view.status, statusText)
            }
        }
        Spacer(GlanceModifier.height(12.dp))
        MilestoneBand(view)
        // One flexible band, not two: splitting the slack above and below the milestone band
        // (the pre-v3 layout) turned a stretched card into two voids with a gauge stranded
        // between them. Here the slack lands below the gauge, above the footer hairline.
        Spacer(GlanceModifier.defaultWeight())
        Hairline()
        Spacer(GlanceModifier.height(6.dp))
        // Footer keeps the reference's bottom-edge grammar: caption left, controls right. Glance's
        // RemoteViews translation caps every container at 10 children and silently drops the tail
        // past that (LayoutSelectionKt in glance-appwidget 1.1.1 — javap-verified; device logcat
        // 2026-10-16: "Truncated Column container from 11 to 10 elements", which ate the whole
        // action row whenever the non-discreet caption was present). This column holds 7 direct
        // children in every path, well clear of the cap.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (caption != null) {
                Text(
                    text = caption,
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                    maxLines = 1,
                )
            } else {
                Spacer(GlanceModifier.defaultWeight())
            }
            ControlRow(
                logRelapse = logRelapse,
                logUrge = logUrge,
                reachOut = reachOut,
                discreet = view.discreet,
                urgeAssist = urgeAssist,
                modifier = GlanceModifier,
            )
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
    // streakDays is the displayed day count, already on the completed-day scale (ADR #176).
    val fraction = milestoneFraction(days, target) ?: return

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
            modifier = GlanceModifier.fillMaxWidth().height(5.dp),
            color = GlanceTheme.colors.tertiary,
            backgroundColor = HairlineColor,
        )
    }
}

/**
 * The protection state as the card's one chip (non-discreet only). Semantic container colors,
 * not one neutral pill for all three states: the chip is the card's only colored evidence of
 * protection, so a teal "Protected" and a red "Not protected" must not look identical
 * (widget redesign 2026-10-17).
 */
@Composable
private fun StatusChip(status: DisplayedStatus, text: String) {
    val colors = GlanceTheme.colors
    val (background, foreground) = when (status) {
        DisplayedStatus.PROTECTED -> colors.primaryContainer to colors.onPrimaryContainer
        DisplayedStatus.UNPROTECTED -> colors.errorContainer to colors.onErrorContainer
        DisplayedStatus.UNVERIFIED -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Text(
        text = text,
        modifier = GlanceModifier
            .background(background)
            .cornerRadius(9.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = TextStyle(
            color = foreground,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

/**
 * The card's control row (owner corrections 2026-10-16/17): icon-only circle buttons, trailing —
 * the reference gallery's control idiom (gallery `11.54.56`: small monochrome glyphs in circles,
 * one filled accent control per composition). Icon-only is the privacy design, not a style
 * choice: a pill saying "Reach out" tells anyone glancing at the home screen exactly what the
 * user is dealing with; a speech bubble tells them nothing. The action's words exist only on the
 * transient hold surface and in the content descriptions (read by TalkBack, never drawn).
 *
 * The log controls draw the same glyphs as the app's own buttons — [R.drawable.ic_log_urge]
 * is Recovery's "Log an urge", [R.drawable.ic_log_relapse] is the streak card's "Log a relapse" —
 * so one shape means one action on both surfaces (owner: "avoid confusion and allow
 * recognition"). [R.drawable.ic_widget_assist] opens Urge Assist (owner add-on 2026-11-07) and
 * leads the row as the in-the-moment entry; the relapse circle follows because ending a streak
 * is the graver act, and hides when the sample binds no habit (no track to end). All three sit
 * tonal; reach-out keeps the one-filled-control role.
 *
 * The three write controls are plain taps onto the hold-confirm surface, where the
 * long-press-and-ring protection lives — no launcher RemoteViews host delivers touch streams, so
 * the ring cannot live on the widget itself (ADR #159). The assist control writes nothing and
 * carries no hold: it only opens the app.
 */
@Composable
private fun ControlRow(
    logRelapse: Action?,
    logUrge: Action,
    reachOut: Action,
    discreet: Boolean,
    urgeAssist: Action,
    size: Dp = 36.dp,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
    alignment: Alignment.Horizontal = Alignment.End,
) {
    val context = LocalContext.current
    val gap = when {
        size >= 34.dp -> 8.dp
        size >= 26.dp -> 5.dp
        else -> 2.dp
    }
    // The tonal circles use the secondary container, not surfaceVariant: on the cream canvas
    // surfaceVariant is within ~8 tone steps of the card and the circles all but vanished.
    val colors = GlanceTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = alignment,
    ) {
        ActionCircle(
            iconRes = R.drawable.ic_widget_assist,
            contentDescription = context.getString(R.string.widget_action_assist),
            action = urgeAssist,
            background = colors.secondaryContainer,
            tint = colors.onSecondaryContainer,
            size = size,
        )
        Spacer(GlanceModifier.width(gap))
        logRelapse?.let {
            ActionCircle(
                iconRes = R.drawable.ic_log_relapse,
                contentDescription = context.getString(
                    if (discreet) R.string.widget_action_reset else R.string.widget_action_relapse,
                ),
                action = it,
                background = colors.secondaryContainer,
                tint = colors.onSecondaryContainer,
                size = size,
            )
            Spacer(GlanceModifier.width(gap))
        }
        ActionCircle(
            iconRes = R.drawable.ic_log_urge,
            contentDescription = context.getString(
                if (discreet) R.string.widget_action_urge_discreet else R.string.widget_action_urge,
            ),
            action = logUrge,
            background = colors.secondaryContainer,
            tint = colors.onSecondaryContainer,
            size = size,
        )
        Spacer(GlanceModifier.width(gap))
        ActionCircle(
            iconRes = R.drawable.ic_widget_reach_out,
            contentDescription = context.getString(R.string.widget_action_reach_out),
            action = reachOut,
            background = colors.primary,
            tint = colors.onPrimary,
            size = size,
        )
    }
}

/** One circle control. 36dp keeps the two-band bottom block (caption + controls) inside the
 *  110dp 4×2 preset's real budget; the narrow 2×2 tightens to 24dp so its row fits four. */
@Composable
private fun ActionCircle(
    iconRes: Int,
    contentDescription: String,
    action: Action,
    background: ColorProvider,
    tint: ColorProvider,
    size: Dp,
) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .background(background)
            .cornerRadius(size / 2)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        // Resource-backed Image (RemoteViews.setImageViewResource) + a tint applied through the
        // ImageView's color filter (RemoteViewsCompat.setImageViewColorFilter) — both paths
        // javap-verified in glance-appwidget 1.1.1, the same evidence standard as the 10-child
        // cap above. The drawables are authored white and tinted per surface.
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = contentDescription,
            modifier = GlanceModifier.size(if (size >= 34.dp) 19.dp else 14.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
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
 * The day count as a stat lockup, in the reference's label/value grammar: a tracked uppercase
 * micro-label in the streak gold, then the number itself as the card's largest ink mark. The
 * value carries the weight, the accent names what it measures (widget redesign 2026-10-17 — the
 * inverse of the old gold-on-muddy-gold hierarchy). Tapping it opens the Streaks tab, not Home:
 * the surface the number belongs to, reached with the same tab extra the milestone notifications
 * use.
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
                text = context.getString(R.string.widget_day_label).uppercase(),
                modifier = GlanceModifier.padding(bottom = 3.dp),
                style = TextStyle(
                    color = GlanceTheme.colors.tertiary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(5.dp))
            Text(
                text = context.getString(R.string.widget_day_number, days),
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontSize = numeralSize,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

/** The compact card's status word, in the same three semantic colors as the large cards' chip. */
@Composable
private fun statusColor(status: DisplayedStatus): ColorProvider = when (status) {
    DisplayedStatus.PROTECTED -> GlanceTheme.colors.primary
    DisplayedStatus.UNPROTECTED -> GlanceTheme.colors.error
    DisplayedStatus.UNVERIFIED -> GlanceTheme.colors.onSurfaceVariant
}

/** The 1dp band divider shared by the expanded and tall cards. */
@Composable
private fun Hairline() {
    Box(
        GlanceModifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HairlineColor),
    ) {}
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

/** The next milestone rung as a data-strip value ("23d to 30-day mark"), or null when there is none. */
internal fun milestoneText(context: Context, day: Int?, daysAway: Int?): String? {
    if (day == null || daysAway == null) return null
    return context.getString(R.string.widget_next_value, day, daysAway)
}

/** The same rung as the compact variant's one-line sentence ("23 days to a 30-day mark"), or null. */
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
