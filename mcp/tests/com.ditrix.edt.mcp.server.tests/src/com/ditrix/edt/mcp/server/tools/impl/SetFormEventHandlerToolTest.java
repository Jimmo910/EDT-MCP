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
 * Lightweight tests for {@link SetFormEventHandlerTool}: tool metadata and JSON
 * schema. The {@code execute()} path requires a live workbench, the form bundle
 * Guice injector and a BM model, so it is covered by the E2E suite instead.
 */
public class SetFormEventHandlerToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_form_event_handler", new SetFormEventHandlerTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetFormEventHandlerTool.NAME, new SetFormEventHandlerTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new SetFormEventHandlerTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetFormEventHandlerTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new SetFormEventHandlerTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"itemName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"event\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"handlerName\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new SetFormEventHandlerTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formFqn must be required", tail.contains("\"formFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("event must be required", tail.contains("\"event\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("handlerName must be required", tail.contains("\"handlerName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testItemNameIsOptional()
    {
        // itemName is optional (omitting it targets a form-level event), so it
        // must NOT appear in the required array.
        String schema = new SetFormEventHandlerTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("itemName must be optional", tail.contains("\"itemName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExtendsFormWriteToolForDiskPersistence()
    {
        // The tool must inherit AbstractFormWriteTool.persistForm so the change is
        // flushed to the Form.form file after the BM transaction commits.
        assertTrue("set_form_event_handler must extend AbstractFormWriteTool to reuse persistForm", //$NON-NLS-1$
            new SetFormEventHandlerTool() instanceof AbstractFormWriteTool);
    }
}
