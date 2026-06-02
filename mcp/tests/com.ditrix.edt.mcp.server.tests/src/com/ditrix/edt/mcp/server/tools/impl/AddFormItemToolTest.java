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
 * Lightweight tests for {@link AddFormItemTool}: tool metadata and JSON schema.
 * The {@code execute()} path requires a live workbench and BM model, so it is
 * covered by the E2E suite instead.
 */
public class AddFormItemToolTest
{
    @Test
    public void testName()
    {
        assertEquals("add_form_item", new AddFormItemTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(AddFormItemTool.NAME, new AddFormItemTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new AddFormItemTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new AddFormItemTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AddFormItemTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"itemKind\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"itemName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"dataPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"parentGroup\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AddFormItemTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formFqn must be required", tail.contains("\"formFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("itemKind must be required", tail.contains("\"itemKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("itemName must be required", tail.contains("\"itemName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExtendsFormWriteToolForDiskPersistence()
    {
        // The tool must inherit AbstractFormWriteTool.persistForm so the change is
        // flushed to the Form.form file after the BM transaction commits.
        assertTrue("add_form_item must extend AbstractFormWriteTool to reuse persistForm", //$NON-NLS-1$
            new AddFormItemTool() instanceof AbstractFormWriteTool);
    }
}
