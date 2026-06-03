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
 * Lightweight tests for {@link AddMetadataAttributeTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and BM model, so it is
 * covered by the E2E suite instead.
 * <p>
 * {@link #testResponseType()} also guards the refactoring that moved
 * {@code getResponseType()} into {@link AbstractMetadataWriteTool}: the tool
 * must still report JSON.
 */
public class AddMetadataAttributeToolTest
{
    @Test
    public void testName()
    {
        assertEquals("add_metadata_attribute", new AddMetadataAttributeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(AddMetadataAttributeTool.NAME, new AddMetadataAttributeTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new AddMetadataAttributeTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new AddMetadataAttributeTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AddMetadataAttributeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"parentFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"attributeName\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AddMetadataAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("parentFqn must be required", tail.contains("\"parentFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("attributeName must be required", tail.contains("\"attributeName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaContainsOptionalTypeParameters()
    {
        String schema = new AddMetadataAttributeTool().getInputSchema();
        assertTrue(schema.contains("\"type\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"indexing\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fillChecking\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"multiLine\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsRegisterKindParameters()
    {
        // S3: register child kind plus dimension-only flags.
        String schema = new AddMetadataAttributeTool().getInputSchema();
        assertTrue(schema.contains("\"kind\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"master\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"mainFilter\"")); //$NON-NLS-1$
    }

    @Test
    public void testOptionalParametersAreNotRequired()
    {
        // Backward compatibility: the new type/qualifier/flag parameters must
        // stay optional so existing callers keep working unchanged.
        String schema = new AddMetadataAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String requiredTail = schema.substring(requiredIdx);
        assertFalse("type must NOT be required", requiredTail.contains("\"type\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("indexing must NOT be required", requiredTail.contains("\"indexing\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("fillChecking must NOT be required", requiredTail.contains("\"fillChecking\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("multiLine must NOT be required", requiredTail.contains("\"multiLine\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("kind must NOT be required", requiredTail.contains("\"kind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("master must NOT be required", requiredTail.contains("\"master\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("mainFilter must NOT be required", requiredTail.contains("\"mainFilter\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionMentionsRegisterKinds()
    {
        String desc = new AddMetadataAttributeTool().getDescription();
        assertTrue("description should mention Dimension", desc.contains("Dimension")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention Resource", desc.contains("Resource")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionMentionsAccountingAndCalculationRegister()
    {
        // S3: Dimension/Resource kinds are now valid for AccountingRegister and
        // CalculationRegister too, and both must be listed as supported owners.
        String desc = new AddMetadataAttributeTool().getDescription();
        assertTrue("description should mention AccountingRegister", //$NON-NLS-1$
            desc.contains("AccountingRegister")); //$NON-NLS-1$
        assertTrue("description should mention CalculationRegister", //$NON-NLS-1$
            desc.contains("CalculationRegister")); //$NON-NLS-1$
    }
}
