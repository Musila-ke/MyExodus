package com.example.myexodus.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.example.myexodus.R
import com.example.myexodus.ui.theme.LocalIsDarkTheme
import kotlin.math.atan2
import kotlin.math.sqrt

/** One destination: deep-link [key], display [labelRes], and its line-icon (idle) / solid-icon
 *  (active) pair. The key is what intent extras carry — never the label, which is a resource
 *  (ADR #91) and would match no constant the moment it is translated. */
internal data class NavTab(
    val key: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * Deep-link keys for [NavTab.key]. Values are the historical tab labels, kept verbatim: they
 * travel in intent extras, so a pending intent created before this indirection still resolves.
 * [com.example.myexodus.MainActivity]'s `TAB_*` constants carry these values.
 */
object NavKeys {
    const val HOME = "Home"
    const val RULES = "Rules"
    const val RECOVERY = "Recovery"
    const val STREAKS = "Streaks"
    const val PARTNER = "Partner"
    const val SETTINGS = "Settings"
}

/** COMPACT (360dp) base values. The whole bar scales as one unit by the adaptive ramp
 *  (`rememberTypeScaleFactor`, standing rule 2026-09-22) — icons, labels and the notch
 *  geometry are coupled, so scaling one without the others would overflow the bubble. */
private val BarHeight = 78.dp
private val BarMargin = 8.dp
private val BarCorner = 14.dp
private val BubbleRadius = 18.dp
private val RingGap = 2.5.dp
private val NotchFillet = 4.dp
private val BarElevation = 8.dp
/** 28dp in the 36dp bubble = a 4dp ring, the same proportion as Material's own 24dp icon in
 *  its 32dp indicator. Any larger crowds the disc; growing the bubble instead would widen the
 *  notch inset and drop the six labels (ADR #74's arithmetic, re-measured 2026-09-27). */
private val IconSize = 28.dp

/** Idle icon centre, measured down from the bar's top edge. Doubles as the active lift: the
 *  active icon's centre lands exactly on the top edge, which is the bubble's centre. */
private val IconCentreY = 28.dp
private val LabelBottomPadding = 13.dp
private val MinLabelGap = 4.dp

/** The theme's labelSmall, not one step under it (user 2026-09-27: the nav labels read too
 *  small). Six labels still share a 360dp bar because they run at zero tracking and the bar's
 *  side margin gives back what the extra point costs; the measured `showAllLabels` guard below
 *  is what decides, not this number. */
private val LabelSize = 11.sp
private val LabelLineHeight = 14.sp

/**
 * Floating bottom navigation bar: a rounded slab with a circular bite taken out of its top edge,
 * and a bubble of the bar's own fill sitting in that bite — half above the edge, half below —
 * holding the active icon. A hairline of page background shows in the gap between bubble and bar.
 *
 * All tabs keep the same position in every state; the bubble travels over them rather than
 * displacing them.
 */
@Composable
internal fun NotchedNavBar(
    tabs: List<NavTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    val scale = rememberTypeScaleFactor()
    val barHeight = BarHeight * scale
    val bubbleRadius = BubbleRadius * scale
    val barCorner = BarCorner * scale
    val notchFillet = NotchFillet * scale
    val notchRadius = bubbleRadius + RingGap * scale
    // Half-width of the notch where it starts curving down into the bar's top edge. The fillet
    // arc is tangent to both the edge and the notch circle, so its centre sits `notchFillet`
    // below the edge at distance `notchRadius + notchFillet` from the bubble centre — that
    // right triangle gives this span.
    val notchSpan: Dp = run {
        val r = notchRadius.value
        val f = notchFillet.value
        sqrt(r * r + 2f * r * f).dp
    }
    // Centre-to-bar-end distance an item needs so the notch clears the corner arc.
    val minCentreInset = notchSpan + barCorner + 2.dp * scale
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = LabelSize * scale,
        lineHeight = LabelLineHeight * scale,
        // Tracking is what the theme's small-label style spends four of its width budget on;
        // across six tabs it is the difference between fitting and not.
        letterSpacing = 0.sp,
    )
    // Resolved once, outside every measure: a translated label is what the fit test must see.
    val labels = tabs.map { stringResource(it.labelRes) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BarMargin)
            .padding(bottom = BarMargin)
            // Explicit height, last so it is the content size: the canvas has to fill it. Sizing
            // to the item row alone would leave the bubble nowhere to be drawn.
            .height(barHeight + bubbleRadius)
    ) {
        val count = tabs.size
        // Row padding that puts the first item's centre exactly at minCentreInset:
        // pad + pitch/2 = minCentreInset with pitch = (width - 2·pad)/count.
        val sidePad = if (count > 1) {
            max(0.dp, (minCentreInset * count - maxWidth / 2) / (count - 1))
        } else {
            0.dp
        }
        val pitch = (maxWidth - sidePad * 2) / count
        // Measured rather than assumed: the widest label decides whether six of them fit side by
        // side at the user's font scale. When they don't, only the selected tab shows its label.
        val widestLabel = remember(measure, labelStyle, labels, density) {
            val activeStyle = labelStyle.copy(fontWeight = FontWeight.SemiBold)
            with(density) { labels.maxOf { measure.measure(it, activeStyle).size.width }.toDp() }
        }
        val showAllLabels = pitch - widestLabel >= MinLabelGap

        val barTopPx = with(density) { bubbleRadius.toPx() }
        val barBottomPx = with(density) { (barHeight + bubbleRadius).toPx() }
        val cornerPx = with(density) { barCorner.toPx() }
        val notchPx = with(density) { notchRadius.toPx() }
        val filletPx = with(density) { notchFillet.toPx() }
        val bubblePx = with(density) { bubbleRadius.toPx() }

        val targetCentrePx = with(density) { (sidePad + pitch * (selectedIndex + 0.5f)).toPx() }
        val centrePx by animateFloatAsState(
            targetValue = targetCentrePx,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f),
            label = "nav-bubble-x",
        )
        // A recreation-time measure can be zero-width, which puts the upper bound below the
        // lower one; coerceIn throws on that and takes the whole process down (device
        // 2026-11-08: the crash killed both blocker services). Clamp to the lower bound.
        val notchMarginsPx = cornerPx + notchPx + filletPx
        val notchCentrePx = clampToRange(
            centrePx,
            notchMarginsPx,
            with(density) { maxWidth.toPx() } - notchMarginsPx,
        )

        // The bar is the one surface whose role differs per scheme: white slab in light, elevated
        // ink in dark. Everything else is a plain scheme role.
        val barColor = if (LocalIsDarkTheme.current) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest
        val activeTint = scheme.primary
        val idleTint = scheme.onSurfaceVariant
        val activeLabel = scheme.onSurface
        val idleLabel = scheme.onSurfaceVariant

        val shape = NotchedBarShape(barTopPx, barBottomPx, notchCentrePx, cornerPx, notchPx, filletPx)
        Canvas(Modifier.fillMaxSize().shadow(elevation = BarElevation, shape = shape, clip = false)) {
            val bubbleCentre = Offset(notchCentrePx, barTopPx)
            // Soft halo under the bubble. The reference gets its separation from a saturated
            // page; Desert Dawn's light page is near-white, so the bubble needs its own shadow
            // to read as a disc. Drawn first — the opaque bar covers everything below the edge.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent),
                    center = bubbleCentre,
                    radius = bubblePx * 1.45f,
                ),
                radius = bubblePx * 1.45f,
                center = bubbleCentre,
            )
            val path = buildBarPath(
                width = size.width,
                top = barTopPx,
                bottom = size.height,
                centre = notchCentrePx,
                corner = cornerPx,
                notchRadius = notchPx,
                fillet = filletPx,
            )
            drawPath(path, barColor)
            // Same fill, drawn on top: the notch is the hole, this is the disc that sits in it.
            drawCircle(barColor, bubblePx, bubbleCentre)
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(barHeight)
                .padding(horizontal = sidePad)
                .selectableGroup()
        ) {
            tabs.forEachIndexed { index, tab ->
                NavItem(
                    tab = tab,
                    label = labels[index],
                    selected = index == selectedIndex,
                    showLabel = showAllLabels || index == selectedIndex,
                    labelStyle = labelStyle,
                    scale = scale,
                    activeTint = activeTint,
                    idleTint = idleTint,
                    activeLabel = activeLabel,
                    idleLabel = idleLabel,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: NavTab,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
    labelStyle: TextStyle,
    scale: Float,
    activeTint: Color,
    idleTint: Color,
    activeLabel: Color,
    idleLabel: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSize = IconSize * scale
    val iconCentreY = IconCentreY * scale
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lift by animateDpAsState(
        targetValue = if (selected) -iconCentreY else 0.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f),
        label = "nav-icon-lift",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(stiffness = 900f),
        label = "nav-press-scale",
    )

    Box(
        modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                // No ripple: the icon's own press scale is the feedback.
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Icon(
            imageVector = if (selected) tab.selectedIcon else tab.icon,
            contentDescription = null,
            tint = if (selected) activeTint else idleTint,
            modifier = Modifier
                .offset { IntOffset(0, (iconCentreY - iconSize / 2 + lift).roundToPx()) }
                .size(iconSize)
                .scale(pressScale),
        )
        Text(
            text = label,
            style = labelStyle,
            color = if (selected) activeLabel else idleLabel,
            fontWeight = if (selected) FontWeight.SemiBold else labelStyle.fontWeight,
            maxLines = 1,
            softWrap = false,
            // Kept composed at zero alpha so the label stays in the accessibility tree.
            // Unbounded width: a label wider than its slot centres instead of being cut off.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .wrapContentWidth(align = Alignment.CenterHorizontally, unbounded = true)
                .padding(bottom = LabelBottomPadding * scale)
                .alpha(if (showLabel) 1f else 0f),
        )
    }
}

private data class NotchedBarShape(
    private val topPx: Float,
    private val bottomPx: Float,
    private val centrePx: Float,
    private val cornerPx: Float,
    private val notchPx: Float,
    private val filletPx: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(buildBarPath(size.width, topPx, bottomPx, centrePx, cornerPx, notchPx, filletPx))
}

/** coerceIn that tolerates a zero-width transient measure (upper bound below the lower). */
internal fun clampToRange(value: Float, min: Float, max: Float): Float =
    value.coerceIn(min, maxOf(min, max))

/**
 * Rounded slab with a bite out of the top edge at [centre]. Canvas angles run clockwise with
 * 0° at 3 o'clock, so the bite sweeps through 90° (straight down).
 */
private fun buildBarPath(
    width: Float,
    top: Float,
    bottom: Float,
    centre: Float,
    corner: Float,
    notchRadius: Float,
    fillet: Float,
): Path {
    val span = sqrt(notchRadius * notchRadius + 2f * notchRadius * fillet)
    val safeCentre = clampToRange(centre, corner + span, width - corner - span)
    val touchX = notchRadius * span / (notchRadius + fillet)
    val touchY = top + notchRadius * fillet / (notchRadius + fillet)
    val startAngle = Math.toDegrees(atan2(fillet.toDouble(), -span.toDouble())).toFloat()

    return Path().apply {
        moveTo(corner, top)
        lineTo(safeCentre - span, top)
        quadraticBezierTo(safeCentre - notchRadius, top, safeCentre - touchX, touchY)
        arcTo(
            Rect(safeCentre - notchRadius, top - notchRadius, safeCentre + notchRadius, top + notchRadius),
            startAngle,
            (180f - startAngle) - startAngle,
            false,
        )
        quadraticBezierTo(safeCentre + notchRadius, top, safeCentre + span, top)
        lineTo(width - corner, top)
        arcTo(Rect(width - 2f * corner, top, width, top + 2f * corner), 270f, 90f, false)
        lineTo(width, bottom - corner)
        arcTo(Rect(width - 2f * corner, bottom - 2f * corner, width, bottom), 0f, 90f, false)
        lineTo(corner, bottom)
        arcTo(Rect(0f, bottom - 2f * corner, 2f * corner, bottom), 90f, 90f, false)
        lineTo(0f, top + corner)
        arcTo(Rect(0f, top, 2f * corner, top + 2f * corner), 180f, 90f, false)
        close()
    }
}
