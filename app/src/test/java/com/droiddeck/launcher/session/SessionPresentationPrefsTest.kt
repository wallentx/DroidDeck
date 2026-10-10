package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionPresentationPrefsTest {
    @Before fun clearSettings() {
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun sdrDefaultsToCopyWithoutChangingHdrPreference() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_STEAM))
        assertFalse(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_DESKTOP))
        SessionPrefs.setHdr(context, SessionService.MODE_STEAM, true)
        SessionPrefs.setSdrZeroCopy(context, SessionService.MODE_STEAM, true)
        SessionPrefs.setSdrZeroCopy(context, SessionService.MODE_STEAM, false)
        assertTrue(SessionPrefs.hdr(context, SessionService.MODE_STEAM))
        assertFalse(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_STEAM))
    }

    @Test fun modeChoiceIsPersistedAndProgramsUseSteamSettings() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setSdrZeroCopy(context, SessionService.MODE_STEAM, true)
        assertTrue(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_STEAM))
        assertTrue(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_RUN))
        assertFalse(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_DESKTOP))
        SessionPrefs.setSdrZeroCopy(context, SessionService.MODE_RUN, false)
        assertFalse(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_STEAM))
        SessionPrefs.setSdrZeroCopy(context, SessionService.MODE_DESKTOP, true)
        assertFalse(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_STEAM))
        assertTrue(SessionPrefs.sdrZeroCopy(context, SessionService.MODE_DESKTOP))
    }

    @Test fun hdrAlwaysRequiresDisplayLayersButSdrCanOptIn() {
        assertFalse(SessionPrefs.useDisplayLayers(hdr = false, sdrZeroCopy = false))
        assertTrue(SessionPrefs.useDisplayLayers(hdr = false, sdrZeroCopy = true))
        assertTrue(SessionPrefs.useDisplayLayers(hdr = true, sdrZeroCopy = false))
        assertTrue(SessionPrefs.useDisplayLayers(hdr = true, sdrZeroCopy = true))
    }
}
