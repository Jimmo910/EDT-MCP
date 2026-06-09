/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless tests for {@link DebugServerTargetSupport} — the bridge that surfaces
 * 1C debug-server targets (server-side breakpoint suspends from
 * {@code debug_yaxunit_tests}, and EDT-UI-started "Debug As" sessions) as standard
 * Eclipse debug-model elements.
 *
 * <p>Only the null/empty-safe contract is exercised here: in the Tycho headless
 * runtime the {@code IRuntimeDebugClientTargetManager} OSGi service is not
 * registered, so enumeration yields an empty list and resolution yields
 * {@code null}. The live suspend/resume/step path needs a real running 1C session
 * and is verified E2E.
 */
public class DebugServerTargetSupportTest
{
    @Test
    public void testServerAppIdPrefix()
    {
        assertEquals("ServerApplication.", DebugServerTargetSupport.SERVER_APP_ID_PREFIX); //$NON-NLS-1$
    }

    @Test
    public void testListServerTargetsNeverNull()
    {
        // Headless: no debug-core manager → empty, never null, never throws.
        assertNotNull(DebugServerTargetSupport.listServerTargets());
        assertTrue(DebugServerTargetSupport.listServerTargets().isEmpty());
    }

    @Test
    public void testResolveNullReturnsNull()
    {
        assertNull(DebugServerTargetSupport.resolve(null));
    }

    @Test
    public void testResolveEmptyReturnsNull()
    {
        assertNull(DebugServerTargetSupport.resolve("")); //$NON-NLS-1$
    }

    @Test
    public void testResolveUnknownReturnsNull()
    {
        // No targets registered headless, so any id resolves to null.
        assertNull(DebugServerTargetSupport.resolve("ServerApplication.Nope")); //$NON-NLS-1$
        assertNull(DebugServerTargetSupport.resolve("Nope")); //$NON-NLS-1$
    }

    @Test
    public void testFindLoneServerTargetReturnsNull()
    {
        assertNull(DebugServerTargetSupport.findLoneServerTarget());
    }

    @Test
    public void testFindSuspendedThreadNullTargetReturnsNull()
    {
        assertNull(DebugServerTargetSupport.findSuspendedThread(null));
    }

    @Test
    public void testIsAnyThreadSuspendedNullTargetIsFalse()
    {
        assertFalse(DebugServerTargetSupport.isAnyThreadSuspended(null));
    }

    @Test
    public void testPollForSuspendedThreadNullTargetReturnsNull() throws InterruptedException
    {
        // A null/terminated target must return immediately (not block for the timeout).
        long start = System.currentTimeMillis();
        assertNull(DebugServerTargetSupport.pollForSuspendedThread(null, 2000L, 50L));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("must not block for the full timeout on a null target", elapsed < 1500L); //$NON-NLS-1$
    }
}
