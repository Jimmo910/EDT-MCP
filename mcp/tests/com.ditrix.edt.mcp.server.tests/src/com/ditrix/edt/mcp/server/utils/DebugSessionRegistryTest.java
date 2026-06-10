/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IThread;
import org.junit.After;
import org.junit.Test;

/**
 * Tests for the launch-mode predicate that decides whether a launch counts as an
 * active DEBUG session ({@link DebugSessionRegistry#isActiveDebugLaunch}).
 * <p>
 * A RUN-mode launch must be excluded (audit A13): it has no debug target and never
 * suspends, so auto-resolving a debug operation onto it could never succeed.
 * The launch-manager-walking methods around it call {@code DebugPlugin.getDefault()}
 * (a live workbench) and are covered E2E; the pure predicate is unit-tested here.
 */
public class DebugSessionRegistryTest
{
    @Test
    public void testActiveDebugLaunchIsTrueForRunningDebugLaunch()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getLaunchMode()).thenReturn(ILaunchManager.DEBUG_MODE);
        assertTrue(DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForRunMode()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getLaunchMode()).thenReturn(ILaunchManager.RUN_MODE);
        assertFalse("a RUN-mode launch must not count as an active debug session", //$NON-NLS-1$
            DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForTerminatedDebugLaunch()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(true);
        assertFalse("a terminated launch must not count as active", //$NON-NLS-1$
            DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForNull()
    {
        assertFalse(DebugSessionRegistry.isActiveDebugLaunch(null));
    }

    // === forgetApplication (defect FIX-3) ===

    /** Keep the shared singleton clean between cases that mutate it. */
    @After
    public void clearRegistry()
    {
        DebugSessionRegistry.get().clear();
    }

    @Test
    public void testForgetApplicationDropsSnapshot()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        String appId = "ServerApplication.SomeApp"; //$NON-NLS-1$
        IThread thread = mock(IThread.class);
        // injectSuspend takes the appId directly, so this needs no EDT runtime.
        registry.injectSuspend(appId, thread);
        assertTrue("precondition: snapshot present after injectSuspend", //$NON-NLS-1$
            registry.hasSnapshot(appId));

        registry.forgetApplication(appId);

        assertFalse("snapshot must be gone after forgetApplication", //$NON-NLS-1$
            registry.hasSnapshot(appId));
        assertNull("getSnapshot must return null after forgetApplication", //$NON-NLS-1$
            registry.getSnapshot(appId));
    }

    @Test
    public void testForgetApplicationOnlyTargetsGivenApp()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        String victim = "launch:Victim"; //$NON-NLS-1$
        String survivor = "launch:Survivor"; //$NON-NLS-1$
        IThread victimThread = mock(IThread.class);
        IThread survivorThread = mock(IThread.class);
        registry.injectSuspend(victim, victimThread);
        registry.injectSuspend(survivor, survivorThread);

        registry.forgetApplication(victim);

        assertFalse("targeted app must be forgotten", registry.hasSnapshot(victim)); //$NON-NLS-1$
        assertTrue("unrelated app must be untouched", registry.hasSnapshot(survivor)); //$NON-NLS-1$
        // The survivor's thread reference must still resolve via its stable id.
        long survivorThreadId = registry.getSnapshot(survivor).threadId;
        assertSame("survivor's live thread reference must remain", //$NON-NLS-1$
            survivorThread, registry.getThread(survivorThreadId));
    }

    @Test
    public void testForgetApplicationNullIsNoOp()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // Must not throw and must leave state empty.
        registry.forgetApplication(null);
        assertFalse(registry.hasSnapshot("anything")); //$NON-NLS-1$
    }
}
