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
}
