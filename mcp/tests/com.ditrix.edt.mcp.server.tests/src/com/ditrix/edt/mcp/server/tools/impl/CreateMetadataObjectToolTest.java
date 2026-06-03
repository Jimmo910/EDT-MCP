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
 * Lightweight tests for {@link CreateMetadataObjectTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and BM model, so it is
 * covered by the E2E suite instead.
 */
public class CreateMetadataObjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_metadata_object", new CreateMetadataObjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateMetadataObjectTool.NAME, new CreateMetadataObjectTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new CreateMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CreateMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionMentionsSupportedTypes()
    {
        String desc = new CreateMetadataObjectTool().getDescription();
        assertTrue("description should mention Catalog", desc.contains("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention Document", desc.contains("Document")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionMentionsOriginalEightTypes()
    {
        // Backward compatibility: the originally supported types must stay supported.
        String desc = new CreateMetadataObjectTool().getDescription();
        for (String type : new String[] {"Catalog", "Document", "InformationRegister", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "AccumulationRegister", "Enum", "CommonModule", "Report", "DataProcessor"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue("description should still mention " + type, desc.contains(type)); //$NON-NLS-1$
        }
    }

    @Test
    public void testDescriptionMentionsRequiredNewTypes()
    {
        // The new top-level kinds explicitly required by the task (Subsystem, Role,
        // StyleItem) plus a representative sample of the broadened set.
        String desc = new CreateMetadataObjectTool().getDescription();
        for (String type : new String[] {"Subsystem", "Role", "StyleItem", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ChartOfCharacteristicTypes", "ExchangePlan", "Constant", "BusinessProcess", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Task", "FilterCriterion", "SettingsStorage", "DocumentJournal", "CommonForm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "CommonCommand", "CommonAttribute", "DefinedType", "Sequence", "CommonPicture", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "CommonTemplate"}) //$NON-NLS-1$
        {
            assertTrue("description should mention " + type, desc.contains(type)); //$NON-NLS-1$
        }
    }

    @Test
    public void testDescriptionMentionsNewlySupportedKinds()
    {
        // Task #19881: XDTOPackage, IntegrationService, Bot and WebSocketClient are
        // now supported (the last three only when the platform exposes them, but
        // they are still advertised) and must appear in the supported list.
        String desc = new CreateMetadataObjectTool().getDescription();
        for (String type : new String[] {"XDTOPackage", "IntegrationService", //$NON-NLS-1$ //$NON-NLS-2$
            "Bot", "WebSocketClient"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue("description should mention newly supported " + type, desc.contains(type)); //$NON-NLS-1$
        }
    }

    @Test
    public void testDescriptionDoesNotMentionExcludedTypes()
    {
        // Types that remain deliberately excluded because they cannot be created
        // blank by the plain factory + attachTopObject chain.
        String desc = new CreateMetadataObjectTool().getDescription();
        for (String type : new String[] {"WSReference", "ExternalDataSource"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertFalse("description must not list excluded type " + type, desc.contains(type)); //$NON-NLS-1$
        }
        // "Language" / "Style" / "Interface" are excluded too, but those
        // substrings can legitimately appear in prose, so assert against the
        // comma-delimited supported list instead.
        String list = ", " + desc + ","; //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Language must not be a supported type", list.contains(", Language,")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Style must not be a supported type", list.contains(", Style,")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Interface must not be a supported type", list.contains(", Interface,")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaListsExpandedTypeHints()
    {
        // The metadataType property hint quotes the supported type names; verify a
        // representative required type and that StyleItem (not Style) is present.
        String schema = new CreateMetadataObjectTool().getInputSchema();
        assertTrue("schema should hint 'Subsystem'", schema.contains("'Subsystem'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema should hint 'Role'", schema.contains("'Role'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema should hint 'StyleItem'", schema.contains("'StyleItem'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema should hint 'XDTOPackage'", schema.contains("'XDTOPackage'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("schema must not hint excluded 'WSReference'", //$NON-NLS-1$
            schema.contains("'WSReference'")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"metadataType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"name\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"synonym\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"comment\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsCommonModuleAndXdtoParameters()
    {
        String schema = new CreateMetadataObjectTool().getInputSchema();
        assertTrue("schema must declare commonModuleKind", //$NON-NLS-1$
            schema.contains("\"commonModuleKind\"")); //$NON-NLS-1$
        assertTrue("schema must declare serverCall", schema.contains("\"serverCall\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare privileged", schema.contains("\"privileged\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare returnValuesReuse", //$NON-NLS-1$
            schema.contains("\"returnValuesReuse\"")); //$NON-NLS-1$
        assertTrue("schema must declare targetNamespace", //$NON-NLS-1$
            schema.contains("\"targetNamespace\"")); //$NON-NLS-1$
    }

    @Test
    public void testCommonModuleKindHintsListTheKinds()
    {
        String schema = new CreateMetadataObjectTool().getInputSchema();
        for (String kind : new String[] {"'Server'", "'ServerCall'", "'ClientManaged'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "'ClientOrdinary'", "'ClientServer'", "'Global'"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("schema should hint commonModuleKind " + kind, schema.contains(kind)); //$NON-NLS-1$
        }
        // The default kind is documented in the description too.
        assertTrue("description should note the default kind 'Server'", //$NON-NLS-1$
            new CreateMetadataObjectTool().getDescription().contains("'Server'")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new CreateMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("metadataType must be required", tail.contains("\"metadataType\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOptionalParametersNotRequired()
    {
        String schema = new CreateMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("synonym must not be required", tail.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("comment must not be required", tail.contains("\"comment\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must not be required", tail.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("commonModuleKind must not be required", //$NON-NLS-1$
            tail.contains("\"commonModuleKind\"")); //$NON-NLS-1$
        assertFalse("targetNamespace must not be required", //$NON-NLS-1$
            tail.contains("\"targetNamespace\"")); //$NON-NLS-1$
    }
}
