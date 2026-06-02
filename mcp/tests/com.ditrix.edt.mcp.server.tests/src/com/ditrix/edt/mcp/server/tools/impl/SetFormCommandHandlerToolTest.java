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
 * Lightweight tests for {@link SetFormCommandHandlerTool}: tool metadata and
 * JSON schema. The {@code execute()} path requires a live workbench and BM
 * model, so it is covered by the E2E suite instead.
 */
public class SetFormCommandHandlerToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_form_command_handler", new SetFormCommandHandlerTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetFormCommandHandlerTool.NAME, new SetFormCommandHandlerTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new SetFormCommandHandlerTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetFormCommandHandlerTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new SetFormCommandHandlerTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"commandName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"handlerName\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new SetFormCommandHandlerTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formFqn must be required", tail.contains("\"formFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("commandName must be required", tail.contains("\"commandName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("handlerName must be required", tail.contains("\"handlerName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
