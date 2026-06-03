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
 * Lightweight tests for {@link SetRegisterPropertyTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and BM model, so it is
 * covered by the E2E suite instead.
 */
public class SetRegisterPropertyToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_register_property", new SetRegisterPropertyTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetRegisterPropertyTool.NAME, new SetRegisterPropertyTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new SetRegisterPropertyTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetRegisterPropertyTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionMentionsSupportedProperties()
    {
        String desc = new SetRegisterPropertyTool().getDescription();
        assertTrue("description should mention writeMode", desc.contains("writeMode")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention periodicity", desc.contains("periodicity")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention registerType", desc.contains("registerType")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionMentionsAccountingAndCalculationRegister()
    {
        String desc = new SetRegisterPropertyTool().getDescription();
        assertTrue("description should mention AccountingRegister", //$NON-NLS-1$
            desc.contains("AccountingRegister")); //$NON-NLS-1$
        assertTrue("description should mention CalculationRegister", //$NON-NLS-1$
            desc.contains("CalculationRegister")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsNewRegisterProperties()
    {
        String desc = new SetRegisterPropertyTool().getDescription();
        // AccountingRegister properties.
        assertTrue("description should mention chartOfAccounts", //$NON-NLS-1$
            desc.contains("chartOfAccounts")); //$NON-NLS-1$
        assertTrue("description should mention correspondence", //$NON-NLS-1$
            desc.contains("correspondence")); //$NON-NLS-1$
        // CalculationRegister properties.
        assertTrue("description should mention chartOfCalculationTypes", //$NON-NLS-1$
            desc.contains("chartOfCalculationTypes")); //$NON-NLS-1$
        assertTrue("description should mention actionPeriod", //$NON-NLS-1$
            desc.contains("actionPeriod")); //$NON-NLS-1$
        assertTrue("description should mention basePeriod", //$NON-NLS-1$
            desc.contains("basePeriod")); //$NON-NLS-1$
        assertTrue("description should mention schedule", //$NON-NLS-1$
            desc.contains("schedule")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new SetRegisterPropertyTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"properties\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new SetRegisterPropertyTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("objectFqn must be required", tail.contains("\"objectFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("properties must be required", tail.contains("\"properties\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
