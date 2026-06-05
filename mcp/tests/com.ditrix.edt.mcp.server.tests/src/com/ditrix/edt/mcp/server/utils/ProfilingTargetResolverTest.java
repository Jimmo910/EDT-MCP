/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Headless tests for {@link ProfilingTargetResolver} — the bridge (#19941 / S22)
 * that resolves the {@code IProfileTarget} for any accepted {@code applicationId}
 * form so {@code start_profiling} / {@code get_profiling_results} accept the same
 * ids the rest of the debug chain does.
 *
 * <p>Only the null/empty-safe contract is exercised here: the Tycho headless
 * runtime has no active debug session, so resolution always yields an unresolved
 * {@link ProfilingTargetResolver.Result} with a populated error message (never an
 * exception). The live toggle path needs a real running 1C server session and is
 * verified E2E.
 */
public class ProfilingTargetResolverTest
{
    @Test
    public void testConstantsAreStable()
    {
        // Guard the reflectively-loaded class/bundle names against accidental edits.
        assertNotNull(ProfilingTargetResolver.PROFILING_CORE_BUNDLE);
        assertNotNull(ProfilingTargetResolver.IPROFILE_TARGET_CLASS);
        assertFalse(ProfilingTargetResolver.PROFILING_CORE_BUNDLE.isEmpty());
        assertFalse(ProfilingTargetResolver.IPROFILE_TARGET_CLASS.isEmpty());
    }

    @Test
    public void testResolveNullIsUnresolvedNotNull()
    {
        // A blank id with no active session: a non-null Result that is not resolved
        // and carries an actionable error (never throws, never returns null).
        ProfilingTargetResolver.Result r = ProfilingTargetResolver.resolve(null);
        assertNotNull(r);
        assertFalse(r.isResolved());
        assertNull(r.profileTarget);
        assertNotNull(r.error);
    }

    @Test
    public void testResolveEmptyIsUnresolved()
    {
        ProfilingTargetResolver.Result r = ProfilingTargetResolver.resolve(""); //$NON-NLS-1$
        assertNotNull(r);
        assertFalse(r.isResolved());
        assertNotNull(r.error);
    }

    @Test
    public void testResolveUnknownServerIdIsUnresolved()
    {
        // ServerApplication.<app> with no live target: unresolved with an error
        // naming the missing session — not an exception.
        ProfilingTargetResolver.Result r =
            ProfilingTargetResolver.resolve("ServerApplication.Nope"); //$NON-NLS-1$
        assertNotNull(r);
        assertFalse(r.isResolved());
        assertNotNull(r.error);
    }

    @Test
    public void testAdaptToProfileTargetNullSafe()
    {
        // Null target and/or null class must yield null, never throw.
        assertNull(ProfilingTargetResolver.adaptToProfileTarget(null, null));
    }
}
