package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnipDriverSelectionTest {
    @Test fun nonAdrenoNeverUsesTheSdkGenerationFallback() {
        assertNull(TurnipDriver.automaticDriver(false, null, 37))
        assertNull(TurnipDriver.automaticDriver(false, "PowerVR C-Series 1536", 37))
        assertNull(TurnipDriver.automaticDriver(false, "Mali-G715", 34))
    }

    @Test fun knownAdrenoModelsKeepTheirDriver() {
        assertEquals("turnip25.1.0", TurnipDriver.automaticDriver(true, "Adreno750", 37))
        assertEquals("turnip-sdk36", TurnipDriver.automaticDriver(true, "adreno_830", 36))
    }

    @Test fun unidentifiedAdrenoRetainsItsExistingFallback() {
        assertEquals("turnip25.1.0", TurnipDriver.automaticDriver(true, null, 35))
        assertEquals("turnip-sdk36", TurnipDriver.automaticDriver(true, null, 37))
    }
}
