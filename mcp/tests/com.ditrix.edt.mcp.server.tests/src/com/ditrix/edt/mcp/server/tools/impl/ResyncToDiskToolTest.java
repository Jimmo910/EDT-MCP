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
 * Lightweight tests for {@link ResyncToDiskTool} that exercise tool metadata and
 * JSON schema without needing the Eclipse/EDT runtime. The {@code execute()} path
 * requires a live workbench and BM model, so it is covered by live verification
 * / the E2E suite instead.
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
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new ResyncToDiskTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ResyncToDiskTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionMentionsCoreBehaviour()
    {
        String desc = new ResyncToDiskTool().getDescription();
        assertTrue("description should mention .mdo files", desc.contains(".mdo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention src/", desc.contains("src/")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention idempotent re-export", //$NON-NLS-1$
            desc.toLowerCase().contains("idempotent")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsParameters()
    {
        String schema = new ResyncToDiskTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"revalidate\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameRequired()
    {
        String schema = new ResyncToDiskTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRevalidateNotRequired()
    {
        String schema = new ResyncToDiskTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("revalidate must not be required", tail.contains("\"revalidate\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRevalidateIsBoolean()
    {
        String schema = new ResyncToDiskTool().getInputSchema();
        int idx = schema.indexOf("\"revalidate\""); //$NON-NLS-1$
        assertTrue("schema must declare revalidate property", idx >= 0); //$NON-NLS-1$
        // The boolean type marker should appear in the revalidate property block.
        String tail = schema.substring(idx);
        assertTrue("revalidate should be a boolean property", //$NON-NLS-1$
            tail.contains("\"boolean\"")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameDefault()
    {
        // Inherits the default IMcpTool naming (JSON tools still expose a file name).
        assertEquals("resync_to_disk.md", new ResyncToDiskTool().getResultFileName(null)); //$NON-NLS-1$
    }
}
