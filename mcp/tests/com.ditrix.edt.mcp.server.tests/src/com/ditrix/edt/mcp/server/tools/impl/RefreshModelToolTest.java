/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link RefreshModelTool} that exercise tool metadata and
 * the JSON schema without needing the Eclipse/EDT runtime. The {@code execute()}
 * path forces a derived-data recompute and an incremental build, which require a
 * live workbench and BM model, so it is covered by the E2E suite instead.
 */
public class RefreshModelToolTest
{
    @Test
    public void testName()
    {
        assertEquals("refresh_model", new RefreshModelTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(RefreshModelTool.NAME, new RefreshModelTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new RefreshModelTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new RefreshModelTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionMentionsStaleScenarios()
    {
        String desc = new RefreshModelTool().getDescription();
        assertTrue("description should mention the form module stale case", //$NON-NLS-1$
            desc.contains("form module")); //$NON-NLS-1$
        assertTrue("description should mention register recorders", //$NON-NLS-1$
            desc.toLowerCase().contains("register-recorders") //$NON-NLS-1$
                || desc.toLowerCase().contains("register recorders")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsParameters()
    {
        String schema = new RefreshModelTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"revalidate\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameIsRequired()
    {
        String schema = new RefreshModelTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRevalidateIsOptional()
    {
        String schema = new RefreshModelTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("revalidate must not be required", tail.contains("\"revalidate\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExecuteWithoutProjectNameReturnsError()
    {
        // No projectName -> validation error before any EDT service is touched.
        String result = new RefreshModelTool().execute(java.util.Collections.emptyMap());
        assertNotNull(result);
        assertTrue("missing projectName must yield a failure result", //$NON-NLS-1$
            result.contains("\"success\":false") || result.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should mention projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
