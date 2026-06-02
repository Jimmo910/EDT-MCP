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
 * Lightweight tests for {@link RemoveFormItemTool}: tool metadata and JSON
 * schema. The {@code execute()} path requires a live workbench and BM model, so
 * it is covered by the E2E suite instead.
 */
public class RemoveFormItemToolTest
{
    @Test
    public void testName()
    {
        assertEquals("remove_form_item", new RemoveFormItemTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(RemoveFormItemTool.NAME, new RemoveFormItemTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new RemoveFormItemTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new RemoveFormItemTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionMentionsKeyBehavior()
    {
        String desc = new RemoveFormItemTool().getDescription();
        // The tool must advertise reference cleanup and on-disk persistence.
        assertTrue("description should mention reference cleanup", //$NON-NLS-1$
            desc.toLowerCase().contains("reference")); //$NON-NLS-1$
        assertTrue("description should mention the Form.form file", //$NON-NLS-1$
            desc.contains("Form.form")); //$NON-NLS-1$
        assertTrue("description should mention idempotency", //$NON-NLS-1$
            desc.toLowerCase().contains("idempotent")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new RemoveFormItemTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"itemName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"itemKind\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new RemoveFormItemTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formFqn must be required", tail.contains("\"formFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("itemName must be required", tail.contains("\"itemName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testItemKindOptional()
    {
        String schema = new RemoveFormItemTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("itemKind must not be required", tail.contains("\"itemKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
