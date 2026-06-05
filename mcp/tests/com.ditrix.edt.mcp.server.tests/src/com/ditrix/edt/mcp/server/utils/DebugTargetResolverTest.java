/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Headless tests for {@link DebugTargetResolver} — the unified resolver (#19941 /
 * S22) that maps every accepted {@code applicationId} form to one underlying
 * Eclipse debug target.
 *
 * <p>Only the pure parts are exercised here: id-form classification (which
 * prefix maps to which {@link DebugTargetResolver.IdForm}) and null/empty-safety.
 * The actual target resolution needs a live debug session (no
 * {@code IRuntimeDebugClientTargetManager} service and no active launches exist in
 * the Tycho headless runtime), so it can only assert that resolution yields
 * {@code null} rather than throwing.
 */
public class DebugTargetResolverTest
{
    // --- classify(): id-form / prefix routing ---

    @Test
    public void testClassifyNullIsBlank()
    {
        assertEquals(DebugTargetResolver.IdForm.BLANK, DebugTargetResolver.classify(null));
    }

    @Test
    public void testClassifyEmptyIsBlank()
    {
        assertEquals(DebugTargetResolver.IdForm.BLANK, DebugTargetResolver.classify("")); //$NON-NLS-1$
    }

    @Test
    public void testClassifyServerApplicationPrefix()
    {
        assertEquals(DebugTargetResolver.IdForm.SERVER_APPLICATION,
            DebugTargetResolver.classify("ServerApplication.ГрафикДоставки")); //$NON-NLS-1$
        // Use the prefix constant directly so the test tracks any rename.
        assertEquals(DebugTargetResolver.IdForm.SERVER_APPLICATION,
            DebugTargetResolver.classify(DebugServerTargetSupport.SERVER_APP_ID_PREFIX + "X")); //$NON-NLS-1$
    }

    @Test
    public void testClassifyAttachPrefix()
    {
        assertEquals(DebugTargetResolver.IdForm.ATTACH,
            DebugTargetResolver.classify(LaunchConfigUtils.ATTACH_APP_ID_PREFIX + "MyAttachConfig")); //$NON-NLS-1$
    }

    @Test
    public void testClassifyLaunchPrefix()
    {
        assertEquals(DebugTargetResolver.IdForm.LAUNCH,
            DebugTargetResolver.classify(LaunchConfigUtils.LAUNCH_APP_ID_PREFIX + "Standalone server")); //$NON-NLS-1$
    }

    @Test
    public void testClassifyRealOrBare()
    {
        // A real ATTR_APPLICATION_ID (no synthetic prefix) or a bare application name.
        assertEquals(DebugTargetResolver.IdForm.REAL_OR_BARE,
            DebugTargetResolver.classify("ГрафикДоставки")); //$NON-NLS-1$
        assertEquals(DebugTargetResolver.IdForm.REAL_OR_BARE,
            DebugTargetResolver.classify("some-real-application-uuid")); //$NON-NLS-1$
    }

    @Test
    public void testClassifyPrefixIsCaseAndOrderSensitive()
    {
        // The server prefix must not be misclassified as attach/launch and vice versa.
        assertEquals(DebugTargetResolver.IdForm.SERVER_APPLICATION,
            DebugTargetResolver.classify("ServerApplication.attach:foo")); //$NON-NLS-1$
        // A bare name that merely contains (but doesn't start with) a prefix token.
        assertEquals(DebugTargetResolver.IdForm.REAL_OR_BARE,
            DebugTargetResolver.classify("my-launch:thing")); //$NON-NLS-1$
    }

    // --- resolve(): null-safety / headless behavior ---

    @Test
    public void testResolveNullReturnsNull()
    {
        // No active sessions headless → blank id auto-resolves to nothing.
        assertNull(DebugTargetResolver.resolve(null));
    }

    @Test
    public void testResolveEmptyReturnsNull()
    {
        assertNull(DebugTargetResolver.resolve("")); //$NON-NLS-1$
    }

    @Test
    public void testResolveUnknownConcreteIdReturnsNull()
    {
        // A concrete id with no matching session resolves to null (never throws),
        // for every id form.
        assertNull(DebugTargetResolver.resolve("ServerApplication.Nope")); //$NON-NLS-1$
        assertNull(DebugTargetResolver.resolve("attach:Nope")); //$NON-NLS-1$
        assertNull(DebugTargetResolver.resolve("launch:Nope")); //$NON-NLS-1$
        assertNull(DebugTargetResolver.resolve("real-or-bare-nope")); //$NON-NLS-1$
    }

    @Test
    public void testServerTargetForTargetNullIsNull()
    {
        assertNull(DebugTargetResolver.serverTargetForTarget(null));
    }
}
