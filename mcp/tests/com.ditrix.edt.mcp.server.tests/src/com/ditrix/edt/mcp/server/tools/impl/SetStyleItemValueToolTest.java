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
 * Lightweight tests for {@link SetStyleItemValueTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and BM model (UI thread +
 * BM transaction), so it is covered by the E2E suite instead.
 */
public class SetStyleItemValueToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_style_item_value", new SetStyleItemValueTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetStyleItemValueTool.NAME, new SetStyleItemValueTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new SetStyleItemValueTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetStyleItemValueTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new SetStyleItemValueTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"styleType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"red\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"green\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"blue\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"auto\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"faceName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"height\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"bold\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"italic\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"underline\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"strikeout\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new SetStyleItemValueTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("objectFqn must be required", tail.contains("\"objectFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("styleType must be required", tail.contains("\"styleType\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
