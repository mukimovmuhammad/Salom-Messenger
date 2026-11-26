package com.mukimov.salom

/** Process-wide call gate shared by FCM, the chat screen and notification actions. */
internal object CallSessionManager {
    private const val RING_TIMEOUT_MS = 45_000L
    enum class State { RINGING, CONNECTING, CONNECTED }
    private var callId: String? = null
    private var state: State? = null
    private var startedAt = 0L
    private val ended = LinkedHashSet<String>()

    @Synchronized fun incoming(id: String): Boolean {
        if (state == State.RINGING && System.currentTimeMillis() - startedAt > RING_TIMEOUT_MS) callId?.let(::end)
        if (id in ended) return false
        if (callId == id) return false
        if (callId != null) return false
        callId = id; state = State.RINGING; startedAt = System.currentTimeMillis()
        return true
    }

    @Synchronized fun accept(id: String): Boolean {
        if (callId != id || state != State.RINGING) return false
        state = State.CONNECTING
        return true
    }

    @Synchronized fun connected(id: String) {
        if (callId == id && state == State.CONNECTING) state = State.CONNECTED
    }

    @Synchronized fun end(id: String) {
        ended.add(id)
        if (ended.size > 128) ended.remove(ended.first())
        if (callId == id) { callId = null; state = null }
    }

    @Synchronized fun notifyWhileRinging(id: String, notify: () -> Unit) {
        incoming(id)
        if (current(id) == State.RINGING) notify()
    }

    @Synchronized fun current(id: String): State? = if (callId == id) state else null

    @Synchronized fun hasActiveCall(): Boolean =
        state != null && (state != State.RINGING || System.currentTimeMillis() - startedAt <= RING_TIMEOUT_MS)
}
