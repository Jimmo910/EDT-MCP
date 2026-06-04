/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ProfilingStateRegistry}: the per-application ON/OFF tracking
 * that {@code start_profiling} / {@code get_profiling_results} rely on (the EDT
 * profiling API has no isProfiling() query). Pure logic, headless.
 */
public class ProfilingStateRegistryTest
{
    private static final String APP_A = "app-a"; //$NON-NLS-1$
    private static final String APP_B = "app-b"; //$NON-NLS-1$

    @Before
    public void setUp()
    {
        ProfilingStateRegistry.get().clear();
    }

    @After
    public void tearDown()
    {
        ProfilingStateRegistry.get().clear();
    }

    @Test
    public void testSingletonInstance()
    {
        assertSame(ProfilingStateRegistry.get(), ProfilingStateRegistry.get());
    }

    @Test
    public void testFreshRegistryHasNothingActive()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        assertFalse(registry.anyActive());
        assertFalse(registry.isActive(APP_A));
        assertTrue(registry.activeApplicationIds().isEmpty());
    }

    @Test
    public void testToggleFlipsState()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        // First toggle -> ON.
        assertTrue(registry.toggle(APP_A));
        assertTrue(registry.isActive(APP_A));
        assertTrue(registry.anyActive());
        // Second toggle -> OFF.
        assertFalse(registry.toggle(APP_A));
        assertFalse(registry.isActive(APP_A));
        assertFalse(registry.anyActive());
    }

    @Test
    public void testToggleReturnsNewState()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        assertTrue("first toggle should report ON", registry.toggle(APP_A)); //$NON-NLS-1$
        assertFalse("second toggle should report OFF", registry.toggle(APP_A)); //$NON-NLS-1$
        assertTrue("third toggle should report ON again", registry.toggle(APP_A)); //$NON-NLS-1$
    }

    @Test
    public void testIndependentApplications()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        registry.toggle(APP_A);
        assertTrue(registry.isActive(APP_A));
        assertFalse(registry.isActive(APP_B));

        registry.toggle(APP_B);
        assertTrue(registry.isActive(APP_A));
        assertTrue(registry.isActive(APP_B));

        List<String> active = registry.activeApplicationIds();
        assertEquals(2, active.size());
        assertTrue(active.contains(APP_A));
        assertTrue(active.contains(APP_B));

        // Toggling A off must not affect B.
        registry.toggle(APP_A);
        assertFalse(registry.isActive(APP_A));
        assertTrue(registry.isActive(APP_B));
        assertTrue(registry.anyActive());
    }

    @Test
    public void testSetInactiveIsIdempotent()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        registry.toggle(APP_A);
        assertTrue(registry.isActive(APP_A));

        registry.setInactive(APP_A);
        assertFalse(registry.isActive(APP_A));

        // Calling again on an already-inactive app is a no-op.
        registry.setInactive(APP_A);
        assertFalse(registry.isActive(APP_A));

        // After setInactive, the next toggle starts ON again.
        assertTrue(registry.toggle(APP_A));
    }

    @Test
    public void testSetInactiveOnUnknownApp()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        // Should not throw and should change nothing.
        registry.setInactive("never-seen"); //$NON-NLS-1$
        assertFalse(registry.anyActive());
    }

    @Test
    public void testNullApplicationIdIsSafe()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        assertFalse("toggle(null) reports OFF and tracks nothing", registry.toggle(null)); //$NON-NLS-1$
        assertFalse(registry.isActive(null));
        assertFalse(registry.anyActive());
        registry.setInactive(null); // must not throw
    }

    @Test
    public void testActiveApplicationIdsIsSnapshot()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        registry.toggle(APP_A);
        List<String> snapshot = registry.activeApplicationIds();
        assertEquals(1, snapshot.size());

        // Mutating the registry afterwards must not alter the returned snapshot.
        registry.toggle(APP_B);
        assertEquals(1, snapshot.size());
    }

    @Test
    public void testClearResetsEverything()
    {
        ProfilingStateRegistry registry = ProfilingStateRegistry.get();
        registry.toggle(APP_A);
        registry.toggle(APP_B);
        assertTrue(registry.anyActive());

        registry.clear();
        assertFalse(registry.anyActive());
        assertFalse(registry.isActive(APP_A));
        assertFalse(registry.isActive(APP_B));
        assertTrue(registry.activeApplicationIds().isEmpty());
    }
}
