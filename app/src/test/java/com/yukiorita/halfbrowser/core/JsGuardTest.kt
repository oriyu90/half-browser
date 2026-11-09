package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogBudgetTest {

    @Test
    fun `allows dialogs up to the limit`() {
        val budget = DialogBudget(maxDialogs = 3, minIntervalMs = 0)
        assertTrue(budget.allowDialog(1))
        assertTrue(budget.allowDialog(2))
        assertTrue(budget.allowDialog(3))
        assertFalse(budget.allowDialog(4))
        assertEquals(1, budget.blockedDialogs)
    }

    @Test
    fun `enforces the minimum interval`() {
        val budget = DialogBudget(maxDialogs = 10, minIntervalMs = 250)
        assertTrue(budget.allowDialog(1_000))
        assertFalse(budget.allowDialog(1_100))
        assertFalse(budget.allowDialog(1_249))
        assertTrue(budget.allowDialog(1_250))
        assertEquals(2, budget.blockedDialogs)
    }

    @Test
    fun `blocks a beforeunload loop`() {
        val budget = DialogBudget(maxBeforeUnload = 2)
        assertTrue(budget.allowBeforeUnload())
        assertTrue(budget.allowBeforeUnload())
        assertFalse(budget.allowBeforeUnload())
        assertEquals(1, budget.blockedBeforeUnload)
    }

    @Test
    fun `reset clears every counter`() {
        val budget = DialogBudget(maxDialogs = 1, minIntervalMs = 0, maxBeforeUnload = 1)
        assertTrue(budget.allowDialog(1))
        assertFalse(budget.allowDialog(2))
        assertTrue(budget.allowBeforeUnload())
        assertFalse(budget.allowBeforeUnload())
        budget.reset()
        assertTrue(budget.allowDialog(3))
        assertTrue(budget.allowBeforeUnload())
        assertEquals(0, budget.blockedDialogs)
        assertEquals(0, budget.blockedBeforeUnload)
    }

    @Test
    fun `a zero limit blocks everything`() {
        val budget = DialogBudget(maxDialogs = 0, minIntervalMs = 0, maxBeforeUnload = 0)
        assertFalse(budget.allowDialog(1))
        assertFalse(budget.allowBeforeUnload())
    }

    @Test
    fun `default limits match the injected script`() {
        // The shim and the native handler must agree, otherwise a page sees one
        // limit and the user experiences another.
        assertTrue(JsGuard.JS.contains("MAX_DIALOGS = ${JsGuard.MAX_DIALOGS_PER_PAGE}"))
        assertTrue(JsGuard.JS.contains("MIN_INTERVAL = ${JsGuard.DIALOG_MIN_INTERVAL_MS}"))
        assertTrue(JsGuard.JS.contains("MAX_BEFORE_UNLOAD = ${JsGuard.MAX_BEFORE_UNLOAD}"))
    }

    @Test
    fun `script is self-contained and strict`() {
        val js = JsGuard.JS
        assertTrue(js.contains("'use strict'"))
        assertTrue(js.contains("__hbJsGuardInstalled"))
        // No network, no eval, no bridge call: the guard must not become a
        // channel of its own.
        assertFalse(js.contains("fetch("))
        assertFalse(js.contains("XMLHttpRequest"))
        assertFalse(js.contains("eval("))
        assertFalse(js.contains("HalfBrowserNotify.postMessage"))
    }

    @Test
    fun `every defence block is failure-isolated`() {
        // One block throwing in strict mode must not take the others down, so
        // each block sits in its own try/catch — including the dialog
        // replacement, which previously was unwrapped.
        val js = JsGuard.JS
        val tryCount = js.split("try {").size - 1
        assertTrue("expected per-block try/catch, got $tryCount", tryCount >= 8)
        val dialogBlock = js.indexOf("var nativeAlert")
        val beforeUnloadBlock = js.indexOf("addEventListener")
        assertTrue(dialogBlock >= 0 && beforeUnloadBlock > dialogBlock)
    }

    @Test
    fun `protect branches on descriptor shape`() {
        // A descriptor is either data (`value`) or accessor (`get`/`set`);
        // passing both forms at once throws a TypeError, so the shim must
        // branch on the shape instead of setting both.
        val js = JsGuard.JS
        assertTrue(js.contains("'value' in descriptor"))
        assertTrue(js.contains("writable: false"))
    }

    @Test
    fun `beforeunload property assignment is counted`() {
        // `window.onbeforeunload = fn` does not go through addEventListener,
        // so without this the limit is bypassable by plain assignment.
        val js = JsGuard.JS
        assertTrue(js.contains("Object.defineProperty(W, 'onbeforeunload'"))
        assertTrue(js.contains("beforeUnloadCount++"))
        assertTrue(js.contains("counters.beforeUnloadBlocked++"))
    }

    @Test
    fun `popup comment matches the always-blocked native layer`() {
        // onCreateWindow returns false, so there is no gesture exception in
        // practice; the shim must not claim OAuth popups are allowed.
        assertFalse(JsGuard.JS.contains("OAuth popups are"))
        assertTrue(JsGuard.JS.contains("onCreateWindow"))
    }
}
