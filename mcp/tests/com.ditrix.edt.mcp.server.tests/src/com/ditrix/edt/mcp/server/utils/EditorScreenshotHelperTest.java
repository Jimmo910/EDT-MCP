/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Unit tests for the render-readiness polling in {@link EditorScreenshotHelper}.
 *
 * <p>The render-readiness loop is the fix for the empty-snapshot defect (Bitrix #19821): the EDT
 * form layout is produced by an asynchronous native render, so the first read after opening a form
 * used to return an empty result. These tests exercise the pure polling behaviour of
 * {@link EditorScreenshotHelper#waitUntilRendered} with a {@code null} viewer (so no real WYSIWYG
 * representation and no rebuild are involved) and a controllable readiness predicate. The full
 * capture path against a live WYSIWYG editor is covered by e2e.</p>
 */
public class EditorScreenshotHelperTest
{
    private static final int SHORT_TIMEOUT_MS = 600;

    @Test
    public void testReturnsImmediatelyWhenAlreadyRendered()
    {
        AtomicInteger polls = new AtomicInteger();
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () ->
        {
            polls.incrementAndGet();
            return true;
        }, SHORT_TIMEOUT_MS);

        assertTrue("should report rendered when predicate is satisfied", rendered); //$NON-NLS-1$
        assertTrue("predicate must be evaluated at least once", polls.get() >= 1); //$NON-NLS-1$
    }

    @Test
    public void testReturnsTrueOncePredicateBecomesReady()
    {
        AtomicInteger polls = new AtomicInteger();
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null,
            () -> polls.incrementAndGet() >= 3, SHORT_TIMEOUT_MS);

        assertTrue("should report rendered once predicate flips to true", rendered); //$NON-NLS-1$
        assertTrue("predicate must have been polled repeatedly", polls.get() >= 3); //$NON-NLS-1$
    }

    @Test
    public void testReturnsFalseWhenNeverRendered()
    {
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () -> false, SHORT_TIMEOUT_MS);
        assertFalse("should report not rendered when predicate never satisfied", rendered); //$NON-NLS-1$
    }

    @Test
    public void testPredicateExceptionTreatedAsNotReady()
    {
        // A transient failure while the model is still building must not abort the wait.
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () ->
        {
            throw new IllegalStateException("model still building"); //$NON-NLS-1$
        }, SHORT_TIMEOUT_MS);

        assertFalse("predicate exceptions must be treated as not-ready, not propagated", rendered); //$NON-NLS-1$
    }
}
