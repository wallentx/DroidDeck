package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerVrLaunchCoordinatorTest {
    @Test fun cancelInvalidatesTheWorkerAndDropsItsLaunch() {
        val coordinator = PowerVrLaunchCoordinator<String>()
        assertTrue(coordinator.defer("game", PowerVrLaunchPriority.GAME))
        val operation = coordinator.nextOperation()

        coordinator.cancel()

        assertFalse(coordinator.isCurrent(operation))
        assertFalse(coordinator.markReady(operation))
        assertNull(coordinator.pendingValue())
        assertNull(coordinator.takeReady())
    }

    @Test fun newerChoiceInvalidatesAnOverlappingOlderChoice() {
        val coordinator = PowerVrLaunchCoordinator<String>()
        coordinator.defer("game", PowerVrLaunchPriority.GAME)
        val older = coordinator.nextOperation()
        val newer = coordinator.nextOperation()

        assertFalse(coordinator.isCurrent(older))
        assertTrue(coordinator.isCurrent(newer))
        assertFalse(coordinator.markReady(older))
        assertTrue(coordinator.markReady(newer))
        assertEquals("game", coordinator.takeReady())
    }

    @Test fun gameLinkReplacesGenericRequestWhenItArrivesSecond() {
        val coordinator = PowerVrLaunchCoordinator<String>()
        assertTrue(coordinator.defer("play", PowerVrLaunchPriority.GENERIC))
        assertFalse(coordinator.defer("game:620", PowerVrLaunchPriority.GAME))

        assertEquals("game:620", coordinator.pendingValue())
    }

    @Test fun genericRequestCannotReplaceGameLinkWhenItArrivesSecond() {
        val coordinator = PowerVrLaunchCoordinator<String>()
        assertTrue(coordinator.defer("game:620", PowerVrLaunchPriority.GAME))
        assertFalse(coordinator.defer("play", PowerVrLaunchPriority.GENERIC))

        assertEquals("game:620", coordinator.pendingValue())
    }
}
