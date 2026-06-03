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
 * Lightweight tests for {@link SetAttributePropertyTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and BM model, so it is
 * covered by the E2E suite instead.
 */
public class SetAttributePropertyToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_attribute_property", new SetAttributePropertyTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetAttributePropertyTool.NAME, new SetAttributePropertyTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new SetAttributePropertyTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetAttributePropertyTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionMentionsEditableProperties()
    {
        String desc = new SetAttributePropertyTool().getDescription();
        assertTrue("description should mention type", desc.contains("type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention indexing", desc.contains("indexing")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention fillChecking", desc.contains("fillChecking")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention multiLine", desc.contains("multiLine")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new SetAttributePropertyTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"ownerFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"attributeName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"type\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"indexing\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fillChecking\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"multiLine\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new SetAttributePropertyTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("ownerFqn must be required", tail.contains("\"ownerFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("attributeName must be required", tail.contains("\"attributeName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPropertyParametersAreNotRequired()
    {
        // The editable properties must stay optional (at least one is required at
        // runtime, but none individually).
        String schema = new SetAttributePropertyTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String requiredTail = schema.substring(requiredIdx);
        assertFalse("type must NOT be required", requiredTail.contains("\"type\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("indexing must NOT be required", requiredTail.contains("\"indexing\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("fillChecking must NOT be required", requiredTail.contains("\"fillChecking\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("multiLine must NOT be required", requiredTail.contains("\"multiLine\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
