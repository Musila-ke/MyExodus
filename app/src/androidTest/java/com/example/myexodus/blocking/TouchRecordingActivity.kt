package com.example.myexodus.blocking

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicInteger

/**
 * §12 tapjacking / multi-window victim: a plain activity whose root view records every touch
 * that reaches the activity's window. Records are static (same process as the tests), so the
 * test asserts "zero events underneath an active overlay" deterministically. Bounds are
 * published to [screenBounds] after the first layout for touch-target geometry.
 */
class TouchRecordingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                touches.incrementAndGet()
                lastX = event.x.toInt()
                lastY = event.y.toInt()
                // Logged for the host-driven tapjacking check (DeviceVerificationActivity runs
                // in the app process; this victim runs in the .test process — logcat is the
                // only shared channel).
                Log.i(TAG, "VictimTouch x=${lastX} y=${lastY} total=${touches.get()}")
                return super.dispatchTouchEvent(event)
            }
        }
        setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            screenBounds = intArrayOf(loc[0], loc[1], root.width, root.height)
            Log.i(TAG, "VictimBounds left=${loc[0]} top=${loc[1]} width=${root.width} height=${root.height}")
        }
    }

    companion object {
        const val TAG = "MyExodusVictim"

        val touches = AtomicInteger(0)
        var lastX = -1
        var lastY = -1

        /** [left, top, width, height] of the activity window on screen, after first layout. */
        var screenBounds: IntArray? = null
    }
}
