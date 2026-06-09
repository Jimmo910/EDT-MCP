/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight contract tests for {@link ResyncToDiskTool}: tool metadata, JSON input/output
 * schema, and the documented response-format policy, without needing the Eclipse/EDT runtime.
 * <p>
 * The {@code execute()} path walks the live BM model, force-exports {@code .mdo} files to disk
 * and mutates the {@code Configuration} (dangling-reference removal), so it needs a live
 * workbench and BM model; the real resync / dangling-cleanup behaviour is covered by the E2E
 * suite (delete a {@code .mdo} and restore it; leave a dangling ref and clear it).
 */
public class ResyncToDiskToolTest
{
    @Test
    public void testName()
    {
        assertEquals("resync_to_disk", new ResyncToDiskTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ResyncToDiskTool.NAME, new ResyncToDiskTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        // resync_to_disk returns a machine-structured payload (counts + FQN lists), so it is a
        // JSON tool and therefore MUST declare an output schema (BuiltInToolOutputSchemaTest).
        assertEquals(ResponseType.JSON, new ResyncToDiskTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new ResyncToDiskTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('resync_to_disk')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new ResyncToDiskTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue("schema must declare the cleanDanglingReferences toggle", //$NON-NLS-1$
            schema.contains("\"cleanDanglingReferences\"")); //$NON-NLS-1$
        assertTrue("schema must declare the revalidate toggle", //$NON-NLS-1$
            schema.contains("\"revalidate\"")); //$NON-NLS-1$
        // projectName is the only required parameter.
        assertTrue("projectName must be required", //$NON-NLS-1$
            schema.contains("\"required\"") && schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDescribesTheSuccessEnvelope()
    {
        String schema = new ResyncToDiskTool().getOutputSchema();
        assertNotNull("a JSON tool must declare an outputSchema", schema); //$NON-NLS-1$
        assertFalse(schema.isEmpty());
        // The success envelope plus the load-bearing report fields.
        assertTrue(schema.contains("\"success\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectsExported\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"missingBefore\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"stillMissing\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"danglingFound\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"danglingRemovedCount\"")); //$NON-NLS-1$
    }
}
