package com.droiddeck.launcher.gpu

import org.junit.Assert.*
import org.junit.Test

class SystemVulkanSelectionTest {
    @Test fun automaticPowerVrUsesTheSystemBridge() {
        assertTrue(SystemVulkanDriver.usesDefault(true, ""))
        assertTrue(SystemVulkanDriver.usesDefault(true, null))
    }

    @Test fun otherGpusAndExplicitImportsKeepTheirDriver() {
        assertFalse(SystemVulkanDriver.usesDefault(false, ""))
        assertFalse(SystemVulkanDriver.usesDefault(true, "custom-driver"))
    }

    @Test fun powerVrIsExperimentalAndOtherNonAdrenoGpusRemainUnsupported() {
        val gpu = GpuInfo("PowerVR", 0, GpuInfo.Family.NOT_ADRENO, "Tensor G6", false, powerVr = true)
        assertEquals(GpuInfo.Support.UNTESTED, gpu.support)
        assertEquals(GpuInfo.Support.UNSUPPORTED, gpu.copy(powerVr = false).support)
    }
}
