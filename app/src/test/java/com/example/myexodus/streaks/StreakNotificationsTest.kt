package com.example.myexodus.streaks

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * §6/§8 Step 4 milestone notification identity. Owner warning 2026-10-23 ("we don't want
 * collisions"): the post used to be untagged under a 31-bit hash id, so two milestones could
 * silently replace each other (and a hash could land on another surface's fixed id). Posting
 * is now (tag, id) with the tag scoped to streak + day and a fixed id — pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreakNotificationsTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun milestone_postsNamedCopyOnTheMilestonesChannel() {
        StreakNotifications.postMilestone(context, STREAK_A, 7, "Alcohol", HABIT_A)

        val posted = manager.activeNotifications.single()
        assertEquals("streak_milestones", posted.notification.channelId)
        assertEquals(
            context.getString(R.string.streaks_milestone_notification_title, 7, "Alcohol"),
            posted.notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.streaks_milestone_notification_text, 7),
            posted.notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** Two habits and two days all coexist: the tag carries streak + day, so the fixed id
     *  (shared by design) can never merge entries. */
    @Test
    fun everyMilestoneHasItsOwnKey_twoStreaksAndTwoDaysCoexist() {
        StreakNotifications.postMilestone(context, STREAK_A, 7, "Alcohol", HABIT_A)
        StreakNotifications.postMilestone(context, STREAK_A, 30, "Alcohol", HABIT_A)
        StreakNotifications.postMilestone(context, STREAK_B, 7, "Gambling", HABIT_B)

        val posted = manager.activeNotifications
        assertEquals(3, posted.size)
        assertEquals(
            setOf(
                StreakNotifications.milestoneTag(STREAK_A, 7),
                StreakNotifications.milestoneTag(STREAK_A, 30),
                StreakNotifications.milestoneTag(STREAK_B, 7),
            ),
            posted.map { it.tag }.toSet(),
        )
        assertEquals("one fixed id, uniqueness from the tag", 1, posted.map { it.id }.toSet().size)
    }

    /** The same milestone re-posted (catch-up sweep) replaces its own entry in place — never
     *  a second tray entry for one achievement. */
    @Test
    fun repostingTheSameMilestone_replacesInPlace() {
        StreakNotifications.postMilestone(context, STREAK_A, 7, "Alcohol", HABIT_A)
        StreakNotifications.postMilestone(context, STREAK_A, 7, "Alcohol", HABIT_A)

        assertEquals(1, manager.activeNotifications.size)
    }

    /** Multi-habit tap routing: the habit rides in the extras AND the data URI, so two habits
     *  with colliding id hashes still get distinct PendingIntents (extras alone don't count
     *  toward identity). */
    @Test
    fun tap_carriesTheHabitExtraAndAHabitScopedDataUri() {
        StreakNotifications.postMilestone(context, STREAK_A, 7, "Alcohol", HABIT_A)

        val tap = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        assertEquals(MainActivity.TAB_HOME, tap.getStringExtra(MainActivity.EXTRA_START_TAB))
        assertEquals(HABIT_A, tap.getStringExtra(MainActivity.EXTRA_START_HABIT))
        assertEquals("myexodus://habit/$HABIT_A", tap.data.toString())
    }

    /** Denied permission skips the post entirely (§9): the splash card is the guaranteed path. */
    @Test
    fun deniedPermission_isASilentNoOp() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        StreakNotifications.postMilestone(context, STREAK_A, 7, "Alcohol", HABIT_A)

        assertTrue(manager.activeNotifications.isEmpty())
    }

    private companion object {
        const val STREAK_A = "streak-a"
        const val STREAK_B = "streak-b"
        const val HABIT_A = "habit-a"
        const val HABIT_B = "habit-b"
    }
}
