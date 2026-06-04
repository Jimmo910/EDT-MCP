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
 * Lightweight tests for {@link DeleteMetadataObjectTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and refactoring service, so
 * it is covered by the E2E suite instead.
 * <p>
 * {@link #testResponseType()} also guards the refactoring that moved
 * {@code getResponseType()} into {@link AbstractMetadataWriteTool}: the tool
 * must still report JSON.
 */
public class DeleteMetadataObjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("delete_metadata_object", new DeleteMetadataObjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DeleteMetadataObjectTool.NAME, new DeleteMetadataObjectTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new DeleteMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DeleteMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new DeleteMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirm\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new DeleteMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("objectFqn must be required", tail.contains("\"objectFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConfirmNotRequired()
    {
        String schema = new DeleteMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("confirm must not be required", tail.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaContainsForce()
    {
        String schema = new DeleteMetadataObjectTool().getInputSchema();
        assertTrue("schema must expose the force parameter", //$NON-NLS-1$
            schema.contains("\"force\"")); //$NON-NLS-1$
    }

    @Test
    public void testForceNotRequired()
    {
        String schema = new DeleteMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("force must not be required", tail.contains("\"force\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionMentionsReferenceBlock()
    {
        String desc = new DeleteMetadataObjectTool().getDescription().toLowerCase();
        assertTrue("description must explain the reference check / block behaviour", //$NON-NLS-1$
            desc.contains("referenc") && desc.contains("block")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must mention the force escape hatch", //$NON-NLS-1$
            desc.contains("force")); //$NON-NLS-1$
        assertFalse("description must drop the false 'cleans up all references' claim", //$NON-NLS-1$
            desc.contains("cleans up all references")); //$NON-NLS-1$
    }
}
