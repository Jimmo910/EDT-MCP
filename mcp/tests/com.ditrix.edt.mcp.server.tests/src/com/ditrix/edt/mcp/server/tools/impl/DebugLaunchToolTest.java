/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link DebugLaunchTool}.
 * <p>
 * Covers tool metadata, the input schema, and the two headless-reachable
 * required-argument validations in the project+application launch mode
 * (projectName, then applicationId), which return before the first
 * {@code ProjectStateChecker}/launch-manager access. NOTE: these checks are
 * only reachable when {@code launchConfigurationName} is absent — supplying it
 * enters the by-name launch mode whose first statement touches the live launch
 * manager. Actual launching is covered by the E2E suite.
 */
public class DebugLaunchToolTest
{
    @Test
    public void testName()
    {
        assertEquals("debug_launch", new DebugLaunchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DebugLaunchTool.NAME, new DebugLaunchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DebugLaunchTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DebugLaunchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new DebugLaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresLaunchingStatus()
    {
        // The launch is async: a fresh launch emits status:"launching" so the caller
        // knows to poll debug_status. This test only asserts the SCHEMA half of that
        // contract — that the output schema advertises the field; it cannot verify the
        // emitted result here (a real launch needs a live workbench, covered by E2E).
        // The coherence check below ties the metadata (schema + guide) together so the
        // promise can't silently drift in one place only.
        String schema = new DebugLaunchTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"status\"")); //$NON-NLS-1$
        assertTrue(schema.contains("launching")); //$NON-NLS-1$
    }

    @Test
    public void testLaunchingContractIsCoherentAcrossMetadata()
    {
        // Runtime-free coherence check: the async "launching" contract must be
        // declared consistently in BOTH the output schema and the guide, so neither
        // can advertise it while the other forgets to. The actual result emission is
        // verified by the E2E suite (needs a live workbench).
        DebugLaunchTool tool = new DebugLaunchTool();
        String schema = tool.getOutputSchema();
        String guide = tool.getGuide();
        assertNotNull(schema);
        assertNotNull(guide);
        assertTrue(schema.contains("launching")); //$NON-NLS-1$
        assertTrue(guide.contains("launching")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDescribesAsyncLaunch()
    {
        // The launch dispatch is non-blocking (asyncExec): the guide must tell the
        // caller it returns status:"launching" immediately and to poll debug_status
        // for readiness rather than expecting a running session synchronously.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.contains("launching")); //$NON-NLS-1$
        assertTrue(guide.contains("debug_status")); //$NON-NLS-1$
    }

    @Test
    public void testUpdateBeforeLaunchFalseContractIsCoherent()
    {
        // rv1 review: with updateBeforeLaunch=false the DB update is skipped AND
        // the launch delegate's modal is NOT auto-confirmed (auto-pressing "Update
        // then run" would perform the very update the caller disabled). Both
        // metadata halves must keep documenting that the platform may then show
        // the modal, so the contract can't silently drift in one place only.
        DebugLaunchTool tool = new DebugLaunchTool();
        String schema = tool.getInputSchema();
        String guide = tool.getGuide();
        assertNotNull(schema);
        assertNotNull(guide);
        assertTrue("schema must document that updateBeforeLaunch=false may show the modal",
            schema.contains("may then show that modal")); //$NON-NLS-1$
        assertTrue("guide must document the updateBeforeLaunch=false contract",
            guide.contains("updateBeforeLaunch=false")); //$NON-NLS-1$
        assertTrue("guide must document that updateBeforeLaunch=false may show the modal",
            guide.contains("may then show that modal")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsRunModeAlreadyRunningGuard()
    {
        // rv1 review: the already-running guard covers RUN-mode launches (no debug
        // target) in BOTH selection modes (by-name and project+application); the
        // guide documents that promise — keep it ratcheted.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the RUN-mode already-running guard",
            guide.contains("RUN mode")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive detail (Attach mode, the alreadyRunning short-circuit and
        // updateBeforeLaunch nuances) moved out of the slimmed description/schema
        // into getGuide(); assert it survived there rather than vanishing.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("Attach")); //$NON-NLS-1$
        assertTrue(guide.contains("alreadyRunning")); //$NON-NLS-1$
        assertTrue(guide.contains("updateBeforeLaunch")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        // No launchConfigurationName -> project+application mode -> projectName required.
        Map<String, String> params = new HashMap<>();
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationId()
    {
        // projectName present, no launchConfigurationName, applicationId omitted.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }
}
