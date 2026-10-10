package com.droiddeck.launcher.input

import android.view.MotionEvent
import android.view.InputDevice
import android.util.Log

/** Keep each finger with its original target, even when pad and guest gestures overlap. */
internal class OnScreenTouchRouter(
    private val isControl: (Float, Float) -> Boolean,
    private val controls: (MotionEvent) -> Boolean,
    private val guest: (MotionEvent) -> Boolean,
) {
    private val controlPointers = mutableSetOf<Int>()
    private val guestPointers = mutableSetOf<Int>()
    private var lastGuestEvent: MotionEvent? = null

    fun onTouch(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            logEdge(event, "forward_mouse")
            return guest(event)
        }
        val action = event.actionMasked
        val pointer = event.getPointerId(event.actionIndex)
        if (action == MotionEvent.ACTION_DOWN) {
            if (controlPointers.isNotEmpty()) {
                val cancel = MotionEvent.obtain(event).apply { this.action = MotionEvent.ACTION_CANCEL }
                try { controls(cancel) } finally { cancel.recycle() }
            }
            cancel()
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val target = if (isControl(event.getX(event.actionIndex), event.getY(event.actionIndex))) controlPointers else guestPointers
            target.add(pointer)
        }
        if (isEdge(event)) {
            val routes = (0 until event.pointerCount).joinToString(",") { index ->
                val id = event.getPointerId(index)
                val route = when (id) {
                    in controlPointers -> "control"
                    in guestPointers -> "guest"
                    else -> "reserved"
                }
                "$id:$route"
            }
            logEdge(event, "route=$routes")
        }
        dispatch(event, controlPointers, controls)
        dispatch(event, guestPointers) { part ->
            lastGuestEvent?.recycle()
            lastGuestEvent = if (part.actionMasked == MotionEvent.ACTION_UP || part.actionMasked == MotionEvent.ACTION_CANCEL)
                null else MotionEvent.obtain(part)
            logEdge(part, "forward_guest")
            guest(part)
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            controlPointers.remove(pointer)
            guestPointers.remove(pointer)
        } else if (action == MotionEvent.ACTION_CANCEL) {
            controlPointers.clear()
            guestPointers.clear()
        }
        // Never send the original, mixed-pointer event to Activity.onTouchEvent as a fallback.
        return true
    }

    fun cancel() {
        controlPointers.clear()
        guestPointers.clear()
        val previous = lastGuestEvent ?: return
        lastGuestEvent = null
        previous.action = MotionEvent.ACTION_CANCEL
        logEdge(previous, "forward_guest")
        try { guest(previous) } finally { previous.recycle() }
    }

    private fun isEdge(event: MotionEvent) = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP,
        MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> true
        else -> false
    }

    /** Gesture boundaries only: no move/frame spam, positions, or keyboard content. */
    private fun logEdge(event: MotionEvent, route: String) {
        if (!isEdge(event)) return
        val pointers = (0 until event.pointerCount).joinToString(",") { event.getPointerId(it).toString() }
        Log.i("OnScreenTouch", "$route action=${MotionEvent.actionToString(event.action)} pointers=$pointers source=0x${event.source.toString(16)}")
    }

    private fun dispatch(event: MotionEvent, pointers: Set<Int>, target: (MotionEvent) -> Boolean) {
        val indices = (0 until event.pointerCount).filter { event.getPointerId(it) in pointers }
        if (indices.isEmpty()) return
        val changedIndex = indices.indexOf(event.actionIndex)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> when {
                changedIndex < 0 -> MotionEvent.ACTION_MOVE
                indices.size == 1 -> MotionEvent.ACTION_DOWN
                else -> MotionEvent.ACTION_POINTER_DOWN or (changedIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> when {
                changedIndex < 0 -> MotionEvent.ACTION_MOVE
                indices.size == 1 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_POINTER_UP or (changedIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            else -> event.actionMasked
        }
        if (indices.size == event.pointerCount && action == event.action) {
            target(event)
            return
        }
        val properties = indices.map { index -> MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) } }.toTypedArray()
        val coords = indices.map { index -> MotionEvent.PointerCoords().also { event.getPointerCoords(index, it) } }.toTypedArray()
        val part = MotionEvent.obtain(event.downTime, event.eventTime, action, indices.size, properties, coords,
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
        try { target(part) } finally { part.recycle() }
    }
}
