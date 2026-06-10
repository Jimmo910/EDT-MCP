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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
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

    // === findRuntimeClientDebugTarget — the delegate-criterion duplicate guard (D5/20074) ===

    @Test
    public void testFindRuntimeClientDebugTargetNullArgsReturnNull()
    {
        // Both keys are required — a null/empty project or app id can never identify the
        // delegate's session, so the guard must never match (and never throw) on them.
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget(null, "app")); //$NON-NLS-1$
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget("Proj", null)); //$NON-NLS-1$
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget("", "app")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget("Proj", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFindRuntimeClientDebugTargetHeadlessReturnsNull()
    {
        // Headless: no IRuntimeDebugClientTargetManager OSGi service is registered, so
        // listDebugTargets() yields nothing and the guard resolves to null (never throws).
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget("Proj", "app")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // === findFirstLiveThread — the live-thread discriminator (D5b/20074) ===
    //
    // findRuntimeClientDebugTarget itself can't be exercised with a mock target headless
    // (it enumerates the IRuntimeDebugClientTargetManager OSGi service, absent here), but
    // findFirstLiveThread IS the discriminator it now requires: a thin-CLIENT debug
    // session has ≥1 non-terminated thread; a standalone-SERVER / profiling target has 0.
    // These mock-target tests pin exactly that "server target no longer matches, client
    // target still matches" behavior the regression fix hinges on.

    @Test
    public void testFindFirstLiveThreadNullTargetReturnsNull()
    {
        assertNull(DebugServerTargetSupport.findFirstLiveThread(null));
    }

    @Test
    public void testFindFirstLiveThreadTerminatedTargetReturnsNull()
    {
        // A terminated target is never a duplicate, even if it still reports threads.
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(true);
        assertNull(DebugServerTargetSupport.findFirstLiveThread(target));
    }

    @Test
    public void testFindFirstLiveThreadServerTargetWithNoThreadsReturnsNull() throws Exception
    {
        // THE SERVER CASE (Bitrix 20074): a standalone-server / profiling target shares
        // the client's app id + project but has ZERO threads. It must NOT be treated as a
        // live client session — so findRuntimeClientDebugTarget will return null for it and
        // the client launch proceeds instead of being wrongly short-circuited.
        IDebugTarget server = mock(IDebugTarget.class);
        when(server.isTerminated()).thenReturn(false);
        when(server.getThreads()).thenReturn(new IThread[0]);
        assertNull(DebugServerTargetSupport.findFirstLiveThread(server));
    }

    @Test
    public void testFindFirstLiveThreadServerTargetWithOnlyTerminatedThreadsReturnsNull() throws Exception
    {
        // A target whose every thread is terminated is equally NOT a live session —
        // mirrors the delegate's filter(!isTerminated) over the thread list.
        IThread dead = mock(IThread.class);
        when(dead.isTerminated()).thenReturn(true);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(new IThread[] {dead});
        assertNull(DebugServerTargetSupport.findFirstLiveThread(target));
    }

    @Test
    public void testFindFirstLiveThreadClientTargetReturnsLiveThread() throws Exception
    {
        // THE CLIENT CASE: a thin-client debug session has ≥1 non-terminated thread; that
        // thread IS returned, so findRuntimeClientDebugTarget would match it (a real
        // already-running client correctly short-circuits / is restarted).
        IThread live = mock(IThread.class);
        when(live.isTerminated()).thenReturn(false);
        IDebugTarget client = mock(IDebugTarget.class);
        when(client.isTerminated()).thenReturn(false);
        when(client.getThreads()).thenReturn(new IThread[] {live});
        assertSame(live, DebugServerTargetSupport.findFirstLiveThread(client));
    }

    @Test
    public void testFindFirstLiveThreadSkipsTerminatedAndReturnsFirstLive() throws Exception
    {
        // A live thread among terminated ones (e.g. a worker that already exited) still
        // makes the target a live session — the first non-terminated thread is returned.
        IThread dead = mock(IThread.class);
        when(dead.isTerminated()).thenReturn(true);
        IThread live = mock(IThread.class);
        when(live.isTerminated()).thenReturn(false);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(new IThread[] {dead, live});
        assertSame(live, DebugServerTargetSupport.findFirstLiveThread(target));
    }

    @Test
    public void testFindFirstLiveThreadDoesNotRequireSuspension() throws Exception
    {
        // Unlike findSuspendedThread, liveness — NOT suspension — is the discriminator:
        // a running (not suspended) client thread still counts as a live session.
        IThread running = mock(IThread.class);
        when(running.isTerminated()).thenReturn(false);
        when(running.isSuspended()).thenReturn(false);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(new IThread[] {running});
        assertSame(running, DebugServerTargetSupport.findFirstLiveThread(target));
        // ...and the suspension-requiring finder returns null for the same target.
        assertNull(DebugServerTargetSupport.findSuspendedThread(target));
    }

    @Test
    public void testFindFirstLiveThreadGetThreadsThrowsReturnsNull() throws Exception
    {
        // Best-effort: a target whose getThreads() throws (model mid-teardown) yields
        // null, never an exception onto the caller.
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenThrow(new org.eclipse.debug.core.DebugException(
            new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                "test", "threads unavailable"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DebugServerTargetSupport.findFirstLiveThread(target));
    }
}
