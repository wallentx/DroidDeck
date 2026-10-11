package com.droiddeck.launcher.ui

import java.util.concurrent.atomic.AtomicLong

internal enum class PowerVrLaunchPriority(val weight: Int) {
    GENERIC(0),
    GAME(1),
}

/**
 * Owns the one Steam request waiting for the PowerVR choice. The generation is also the commit
 * token passed to the profile manager, so cancellation and newer choices invalidate old workers.
 */
internal class PowerVrLaunchCoordinator<T> {
    private data class Pending<T>(val value: T, val priority: PowerVrLaunchPriority)

    private val generation = AtomicLong()
    private val pendingLock = Any()
    private var pending: Pending<T>? = null
    private var ready = false

    /** Returns true only when this is the first request and choice inspection must start. */
    fun defer(value: T, priority: PowerVrLaunchPriority): Boolean = synchronized(pendingLock) {
        val current = pending
        if (current == null) {
            pending = Pending(value, priority)
            ready = false
            true
        } else {
            // A game/deep link replaces generic Play or auto-start. Generic requests never erase it.
            if (priority.weight >= current.priority.weight) pending = Pending(value, priority)
            false
        }
    }

    fun pendingValue(): T? = synchronized(pendingLock) { pending?.value }

    fun nextOperation(): Long = generation.incrementAndGet()

    fun isCurrent(operation: Long): Boolean = generation.get() == operation

    fun markReady(operation: Long): Boolean = synchronized(pendingLock) {
        if (!isCurrent(operation) || pending == null) return@synchronized false
        ready = true
        true
    }

    fun takeReady(): T? = synchronized(pendingLock) {
        if (!ready) return@synchronized null
        pending?.value.also {
            pending = null
            ready = false
        }
    }

    /** Invalidates workers before clearing their launch, without waiting for their I/O. */
    fun cancel() {
        generation.incrementAndGet()
        synchronized(pendingLock) {
            pending = null
            ready = false
        }
    }
}
