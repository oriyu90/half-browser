package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthAttemptTrackerTest {

    private val key = AuthAttemptTracker.keyOf("https", "example.com", 443, "Realm")

    @Test
    fun `auto fill is allowed twice then stops`() {
        val tracker = AuthAttemptTracker()
        assertTrue(tracker.shouldAutoFill(key))
        tracker.record(key)
        assertTrue(tracker.shouldAutoFill(key))
        tracker.record(key)
        assertFalse(tracker.shouldAutoFill(key))
        assertEquals(2, tracker.attempts(key))
    }

    @Test
    fun `a committed page clears the counter`() {
        val tracker = AuthAttemptTracker()
        tracker.record(key)
        tracker.record(key)
        tracker.clearHost("example.com")
        assertTrue(tracker.shouldAutoFill(key))
    }

    @Test
    fun `ports and realms are independent`() {
        val tracker = AuthAttemptTracker()
        tracker.record(key)
        tracker.record(key)
        val other = AuthAttemptTracker.keyOf("https", "example.com", 8443, "Realm")
        assertTrue(tracker.shouldAutoFill(other))
        assertFalse(tracker.shouldAutoFill(key))
    }

    @Test
    fun `keys are normalized`() {
        assertEquals(
            key,
            AuthAttemptTracker.keyOf("HTTPS", "Example.COM", 443, "Realm"),
        )
    }
}
