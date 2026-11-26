package com.mukimov.salom

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CallSessionManagerTest {
    private val a = java.util.UUID.randomUUID().toString()
    private val b = java.util.UUID.randomUUID().toString()
    @After fun reset() { CallSessionManager.end(a); CallSessionManager.end(b) }

    @Test fun sameInviteCannotCreateSecondCall() {
        assertTrue(CallSessionManager.incoming(a))
        assertFalse(CallSessionManager.incoming(a))
        assertFalse(CallSessionManager.incoming(b))
        assertEquals(CallSessionManager.State.RINGING, CallSessionManager.current(a))
    }

    @Test fun acceptIsAtomicAndOnlyOnce() {
        assertFalse(CallSessionManager.hasActiveCall())
        assertTrue(CallSessionManager.incoming(a))
        assertTrue(CallSessionManager.hasActiveCall())
        assertTrue(CallSessionManager.accept(a))
        assertFalse(CallSessionManager.accept(a))
        assertEquals(CallSessionManager.State.CONNECTING, CallSessionManager.current(a))
        CallSessionManager.connected(a)
        assertEquals(CallSessionManager.State.CONNECTED, CallSessionManager.current(a))
        assertTrue(CallSessionManager.hasActiveCall())
        CallSessionManager.end(a)
        assertNull(CallSessionManager.current(a))
        assertFalse(CallSessionManager.hasActiveCall())
        assertTrue(CallSessionManager.incoming(b))
    }

    @Test fun latePushDoesNotRingAfterAcceptanceOrEnd() {
        var alerts = 0
        CallSessionManager.notifyWhileRinging(a) { alerts++ }
        assertTrue(CallSessionManager.accept(a))
        CallSessionManager.notifyWhileRinging(a) { alerts++ }
        CallSessionManager.end(a)
        CallSessionManager.notifyWhileRinging(a) { alerts++ }
        assertEquals(1, alerts)
    }
}
