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
 * Lightweight tests for {@link CreateFormTool}: tool metadata and JSON schema.
 * The {@code execute()} path requires a live workbench and BM model, so it is
 * covered by the E2E suite instead.
 */
public class CreateFormToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_form", new CreateFormTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateFormTool.NAME, new CreateFormTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new CreateFormTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CreateFormTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateFormTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"ownerFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"setAsDefault\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new CreateFormTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("ownerFqn must be required", tail.contains("\"ownerFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formName must be required", tail.contains("\"formName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExtendsFormWriteToolForDiskPersistence()
    {
        // The tool must inherit AbstractFormWriteTool.persistForm so the new
        // Form.form is flushed to disk after the BM transaction commits.
        assertTrue("create_form must extend AbstractFormWriteTool to reuse persistForm", //$NON-NLS-1$
            new CreateFormTool() instanceof AbstractFormWriteTool);
    }
}
