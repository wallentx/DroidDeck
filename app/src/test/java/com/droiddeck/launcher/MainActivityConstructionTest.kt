package com.droiddeck.launcher

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MainActivityConstructionTest {
    @Test fun constructingTheLauncherDoesNotReadAnUnattachedActivityContext() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()

        assertNotNull(activity.baseContext)
    }
}
