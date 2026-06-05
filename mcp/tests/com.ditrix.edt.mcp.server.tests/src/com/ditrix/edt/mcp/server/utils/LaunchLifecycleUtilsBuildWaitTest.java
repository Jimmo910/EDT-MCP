/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

/**
 * Tests for the pre-launch build/derived-data settle step added for bug #19925
 * ({@link LaunchLifecycleUtils#waitForLaunchBuildSettled} /
 * {@link LaunchLifecycleUtils#collectLaunchAndExtensionProjects}).
 *
 * <p>These run outside the Eclipse OSGi runtime, so {@code Activator.getDefault()}
 * is {@code null} and the extension discovery degrades gracefully to "just the
 * launch project". That degradation path is exactly what is asserted here; the
 * live extension-discovery path needs the EDT target platform and is exercised by
 * a real YAXUnit-in-extension run. The point of these tests is that the new code
 * is null-safe and never throws on the launch hot path.
 */
public class LaunchLifecycleUtilsBuildWaitTest
{
    private static IProject mockOpenProject(String name)
    {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn(name);
        return project;
    }

    @Test
    public void testCollectIncludesLaunchProjectFirst()
    {
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects = LaunchLifecycleUtils.collectLaunchAndExtensionProjects(launch);
        assertFalse("result must not be empty", projects.isEmpty());
        assertSame("launch project must always be first", launch, projects.get(0));
    }

    @Test
    public void testCollectWithoutActivatorDegradesToLaunchProjectOnly()
    {
        // No OSGi runtime -> Activator.getDefault() is null -> no extension
        // discovery, but the launch project must still be returned so its own
        // build/derived data is waited on.
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects = LaunchLifecycleUtils.collectLaunchAndExtensionProjects(launch);
        assertEquals("only the launch project is expected without a project manager",
            1, projects.size());
        assertSame(launch, projects.get(0));
    }

    @Test
    public void testWaitForLaunchBuildSettledNullProjectIsNoOp()
    {
        // Must not throw on a null project (defensive: callers derive the project
        // from a launch config that could, in theory, be missing).
        LaunchLifecycleUtils.waitForLaunchBuildSettled(null);
    }

    @Test
    public void testWaitForLaunchBuildSettledClosedProjectIsNoOp()
    {
        IProject closed = mock(IProject.class);
        when(closed.exists()).thenReturn(true);
        when(closed.isOpen()).thenReturn(false);
        // Closed project short-circuits before any build join — no exception.
        LaunchLifecycleUtils.waitForLaunchBuildSettled(closed);
    }

    @Test
    public void testWaitForLaunchBuildSettledOpenProjectDoesNotThrow()
    {
        // With no OSGi services available the build join and derived-data wait are
        // best-effort no-ops; the call must complete cleanly rather than throw.
        IProject launch = mockOpenProject("MyConfig");
        LaunchLifecycleUtils.waitForLaunchBuildSettled(launch);
        assertTrue(true);
    }

    // --- resolveUpdateScope (bug #19925 S20b) -------------------------------
    //
    // These run headless (Activator.getDefault() == null), so extension
    // discovery via collectLaunchAndExtensionProjects degrades to "just the
    // launch project". The assertions therefore exercise the scope-PARSING logic
    // (all / configuration / extension:<Name> / unknown / null) and the
    // invariant that the configuration project is always present and first. The
    // live extension-discovery branch is covered by a real YAXUnit-in-extension
    // run.

    @Test
    public void testResolveUpdateScopeNullIsAllAndConfigFirst()
    {
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects = LaunchLifecycleUtils.resolveUpdateScope(launch, null);
        assertFalse("null scope must resolve to a non-empty list", projects.isEmpty());
        assertSame("configuration must always be first", launch, projects.get(0));
    }

    @Test
    public void testResolveUpdateScopeEmptyIsAll()
    {
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects = LaunchLifecycleUtils.resolveUpdateScope(launch, "   ");
        assertFalse(projects.isEmpty());
        assertSame(launch, projects.get(0));
    }

    @Test
    public void testResolveUpdateScopeAllKeywordCaseInsensitive()
    {
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects = LaunchLifecycleUtils.resolveUpdateScope(launch, "ALL");
        assertFalse(projects.isEmpty());
        assertSame(launch, projects.get(0));
    }

    @Test
    public void testResolveUpdateScopeConfigurationIsLaunchProjectOnly()
    {
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects = LaunchLifecycleUtils.resolveUpdateScope(launch, "configuration");
        assertEquals("configuration scope is exactly the launch project", 1, projects.size());
        assertSame(launch, projects.get(0));
    }

    @Test
    public void testResolveUpdateScopeConfigurationCaseInsensitive()
    {
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects = LaunchLifecycleUtils.resolveUpdateScope(launch, "Configuration");
        assertEquals(1, projects.size());
        assertSame(launch, projects.get(0));
    }

    @Test
    public void testResolveUpdateScopeExtensionAlwaysIncludesConfigFirst()
    {
        // An extension cannot reach the IB without its parent configuration, so the
        // configuration project is always included (and first). Headless: the named
        // extension is not discoverable, so the result is exactly the launch project.
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects =
            LaunchLifecycleUtils.resolveUpdateScope(launch, "extension:yaxunit");
        assertFalse("extension scope must always include the configuration", projects.isEmpty());
        assertSame("configuration must be first", launch, projects.get(0));
        assertEquals("headless: only the configuration is resolvable", 1, projects.size());
    }

    @Test
    public void testResolveUpdateScopeMultipleExtensionsIncludesConfigFirst()
    {
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects = LaunchLifecycleUtils.resolveUpdateScope(launch,
            "extension:yaxunit, extension:other");
        assertFalse(projects.isEmpty());
        assertSame(launch, projects.get(0));
    }

    @Test
    public void testResolveUpdateScopeUnknownNameStillRecomputesConfig()
    {
        // Unknown extension names are ignored, but the configuration is always
        // recomputed — never an empty scope.
        IProject launch = mockOpenProject("MyConfig");
        List<IProject> projects =
            LaunchLifecycleUtils.resolveUpdateScope(launch, "extension:doesNotExist");
        assertEquals(1, projects.size());
        assertSame(launch, projects.get(0));
    }

    @Test
    public void testResolveUpdateScopeNullProjectIsEmpty()
    {
        // Defensive: a null launch project must not throw and yields an empty scope.
        List<IProject> projects = LaunchLifecycleUtils.resolveUpdateScope(null, "all");
        assertTrue("null project yields an empty scope", projects.isEmpty());
    }

    @Test
    public void testRecomputeAndSettleNullCollectionIsNoOp()
    {
        // Must not throw on a null/empty collection (defensive launch hot path).
        LaunchLifecycleUtils.recomputeAndSettle(null);
        LaunchLifecycleUtils.recomputeAndSettle(java.util.Collections.emptyList());
        assertTrue(true);
    }

    @Test
    public void testRecomputeAndSettleOpenProjectDoesNotThrow()
    {
        // With no OSGi services the per-project recompute degrades to a no-op and
        // the build/derived-data drain is best-effort; the call must not throw.
        IProject launch = mockOpenProject("MyConfig");
        LaunchLifecycleUtils.recomputeAndSettle(java.util.Arrays.asList(launch));
        assertTrue(true);
    }
}
