package com.mobilemcp.pro

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrimeMainActivitySmokeInstrumentedTest {

    @Test
    fun mainActivityLaunchesToUsableWindow() {
        ActivityScenario.launch(
            MainActivity::class.java
        ).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(
                    activity.isFinishing
                )
                assertFalse(
                    activity.isDestroyed
                )
                assertTrue(
                    activity.hasWindowFocus() ||
                        activity.window != null
                )

                val content =
                    activity.findViewById<View>(
                        android.R.id.content
                    )
                val composer =
                    activity.findViewById<View>(
                        R.id.etMessage
                    )

                assertNotNull(content)
                assertNotNull(composer)
                assertTrue(
                    content.isAttachedToWindow
                )
                assertTrue(
                    composer.isAttachedToWindow
                )
                assertTrue(
                    composer.visibility ==
                        View.VISIBLE
                )
            }
        }
    }
}
