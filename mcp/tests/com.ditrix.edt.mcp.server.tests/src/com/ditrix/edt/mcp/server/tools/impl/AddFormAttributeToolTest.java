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
 * Lightweight tests for {@link AddFormAttributeTool}: tool metadata and JSON
 * schema. The {@code execute()} path requires a live workbench and BM model, so
 * it is covered by the E2E suite instead.
 */
public class AddFormAttributeToolTest
{
    @Test
    public void testName()
    {
        assertEquals("add_form_attribute", new AddFormAttributeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(AddFormAttributeTool.NAME, new AddFormAttributeTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new AddFormAttributeTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new AddFormAttributeTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AddFormAttributeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"attributeName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"type\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"main\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AddFormAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formFqn must be required", tail.contains("\"formFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("attributeName must be required", tail.contains("\"attributeName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTypeParameterIsOptional()
    {
        // 'type' is optional (an untyped attribute is valid), so it must NOT
        // appear in the required array.
        String schema = new AddFormAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("type must be optional", tail.contains("\"type\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
