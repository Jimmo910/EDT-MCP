/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tests for {@link UpdateDatabaseTool}.
 *
 * <p>Verifies tool identity, response type, schema completeness (including the
 * new {@code terminateSessions} and {@code timeoutSeconds} parameters added for
 * the "infobase busy" bug-fix) and the early input-validation branches that do
 * not require a live Eclipse workbench. The real update flow needs the EDT
 * runtime ({@code IApplicationManager}, launch manager) and is covered by E2E.
 */
public class UpdateDatabaseToolTest
{
    // === Identity ===

    @Test
    public void testToolName()
    {
        IMcpTool tool = new UpdateDatabaseTool();
        assertEquals("update_database", tool.getName());
        assertEquals(UpdateDatabaseTool.NAME, tool.getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        IMcpTool tool = new UpdateDatabaseTool();
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
    }

    @Test
    public void testDescriptionMentionsBusyInfobaseHandling()
    {
        IMcpTool tool = new UpdateDatabaseTool();
        String desc = tool.getDescription();
        assertNotNull(desc);
        assertTrue("description should not be empty", desc.length() > 0);
        assertTrue("description should mention terminateSessions option",
            desc.contains("terminateSessions"));
        assertTrue("description should mention the exclusive lock / busy IB",
            desc.toLowerCase().contains("exclusive"));
    }

    // === Schema ===

    @Test
    public void testSchemaDeclaresAllParameters()
    {
        IMcpTool tool = new UpdateDatabaseTool();
        String schema = tool.getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"launchConfigurationName\""));
        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"applicationId\""));
        assertTrue(schema.contains("\"fullUpdate\""));
        assertTrue(schema.contains("\"autoRestructure\""));
        assertTrue("schema must expose new terminateSessions param",
            schema.contains("\"terminateSessions\""));
        assertTrue("schema must expose new timeoutSeconds param",
            schema.contains("\"timeoutSeconds\""));
    }

    @Test
    public void testSchemaHasNoRequiredFields()
    {
        // Either launchConfigurationName or projectName+applicationId is accepted —
        // the combination is validated at runtime, not via the JSON schema.
        IMcpTool tool = new UpdateDatabaseTool();
        String schema = tool.getInputSchema();
        assertTrue("required array should be present and empty",
            schema.contains("\"required\":[]"));
    }

    // === Early input validation (no Eclipse runtime needed) ===

    @Test
    public void testMissingProjectNameReturnsError()
    {
        IMcpTool tool = new UpdateDatabaseTool();
        String result = tool.execute(new HashMap<String, String>());
        assertNotNull(result);
        assertTrue("must report failure", result.contains("\"success\":false"));
        assertTrue("must mention projectName", result.contains("projectName"));
    }

    @Test
    public void testMissingApplicationIdReturnsError()
    {
        IMcpTool tool = new UpdateDatabaseTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue("must report failure", result.contains("\"success\":false"));
        assertTrue("must mention applicationId", result.contains("applicationId"));
    }
}
