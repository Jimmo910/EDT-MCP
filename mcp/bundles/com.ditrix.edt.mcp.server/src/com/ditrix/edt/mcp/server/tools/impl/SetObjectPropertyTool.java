/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.Enumerator;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.common.AllowedLength;
import com._1c.g5.v8.dt.metadata.mdclass.BasicRegister;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodesSeries;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentNumberPeriodicity;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentNumberType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Posting;
import com._1c.g5.v8.dt.metadata.mdclass.RealTimePosting;
import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Tool to set properties on an existing top-level metadata object.
 * <p>
 * One tool covers several object kinds; only the properties relevant to the
 * resolved object type are accepted. An unknown or irrelevant property is a hard
 * error (never silently ignored), and all properties are validated and parsed
 * <em>before</em> the BM write transaction so the model is never left partially
 * updated.
 * <p>
 * Supported kinds and properties:
 * <ul>
 * <li><b>Document</b>: {@code posting} (Allow/Deny), {@code realTimePosting}
 * (Allow/Deny), {@code postInPrivilegedMode} (boolean),
 * {@code unpostInPrivilegedMode} (boolean), {@code numberType}
 * (Number/String), {@code numberLength} (int), {@code numberAllowedLength}
 * (Variable/Fixed), {@code numberPeriodicity}
 * (Nonperiodical/Year/Quarter/Month/Day), {@code checkUnique} (boolean),
 * {@code autonumbering} (boolean), {@code registerRecords} (string array of
 * register FQNs).</li>
 * <li><b>Catalog</b>: {@code codeLength} (int), {@code descriptionLength}
 * (int), {@code codeType} (Number/String), {@code codeAllowedLength}
 * (Variable/Fixed), {@code codeSeries}
 * (WholeCatalog/WithinSubordination/WithinOwnerSubordination),
 * {@code checkUnique} (boolean), {@code autonumbering} (boolean).</li>
 * <li><b>CommonModule</b>: {@code server}, {@code serverCall},
 * {@code clientManagedApplication}, {@code clientOrdinaryApplication},
 * {@code externalConnection}, {@code global}, {@code privileged} (all
 * boolean), {@code returnValuesReuse}
 * (DontUse/DuringRequest/DuringSession).</li>
 * <li><b>Subsystem</b>: {@code includeInCommandInterface} (boolean),
 * {@code content} (string array of object FQNs to include in the
 * subsystem).</li>
 * </ul>
 */
public class SetObjectPropertyTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "set_object_property"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set properties on an existing top-level metadata object. " //$NON-NLS-1$
            + "Only properties relevant to the object type are accepted; an unknown or " //$NON-NLS-1$
            + "irrelevant property is reported as an error (never ignored). " //$NON-NLS-1$
            + "Document: posting (Allow/Deny), realTimePosting (Allow/Deny), " //$NON-NLS-1$
            + "postInPrivilegedMode, unpostInPrivilegedMode (boolean), " //$NON-NLS-1$
            + "numberType (Number/String), numberLength (int), " //$NON-NLS-1$
            + "numberAllowedLength (Variable/Fixed), " //$NON-NLS-1$
            + "numberPeriodicity (Nonperiodical/Year/Quarter/Month/Day), " //$NON-NLS-1$
            + "checkUnique, autonumbering (boolean), registerRecords (array of register FQNs). " //$NON-NLS-1$
            + "Catalog: codeLength, descriptionLength (int), codeType (Number/String), " //$NON-NLS-1$
            + "codeAllowedLength (Variable/Fixed), " //$NON-NLS-1$
            + "codeSeries (WholeCatalog/WithinSubordination/WithinOwnerSubordination), " //$NON-NLS-1$
            + "checkUnique, autonumbering (boolean). " //$NON-NLS-1$
            + "CommonModule: server, serverCall, clientManagedApplication, " //$NON-NLS-1$
            + "clientOrdinaryApplication, externalConnection, global, privileged (boolean), " //$NON-NLS-1$
            + "returnValuesReuse (DontUse/DuringRequest/DuringSession). " //$NON-NLS-1$
            + "Subsystem: includeInCommandInterface (boolean), content (array of object FQNs). " //$NON-NLS-1$
            + "Russian type names are also supported in FQNs."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to modify (required), e.g. 'Document.SalesOrder', " //$NON-NLS-1$
                    + "'Catalog.Products', 'CommonModule.Utils', 'Subsystem.Sales'. " //$NON-NLS-1$
                    + "Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("properties", //$NON-NLS-1$
                "JSON object of property name -> value to set (required). " //$NON-NLS-1$
                    + "Example for a Document: " //$NON-NLS-1$
                    + "{\"posting\": \"Allow\", \"numberLength\": 9, \"postInPrivilegedMode\": true, " //$NON-NLS-1$
                    + "\"registerRecords\": [\"AccumulationRegister.Sales\"]}. " //$NON-NLS-1$
                    + "Boolean values accept true/false; enum values are case-insensitive.", true) //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String propertiesRaw = JsonUtils.extractStringArgument(params, "properties"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " //$NON-NLS-1$
                + "Usage: {projectName: 'MyProject', objectFqn: 'Document.SalesOrder', " //$NON-NLS-1$
                + "properties: {posting: 'Allow'}}").toJson(); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("objectFqn is required. " //$NON-NLS-1$
                + "Examples: 'Document.SalesOrder', 'Catalog.Products', 'CommonModule.Utils'.").toJson(); //$NON-NLS-1$
        }

        // Parse the properties JSON object up front.
        Map<String, JsonElement> properties = parseProperties(propertiesRaw);
        if (properties == null)
        {
            return ToolResult.error("properties is required and must be a non-empty JSON object, " //$NON-NLS-1$
                + "e.g. {\"posting\": \"Allow\", \"numberLength\": 9}.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, objectFqn, properties);
    }

    private String executeInternal(String projectName, String objectFqn,
        Map<String, JsonElement> properties)
    {
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Resolve the target object outside the transaction.
        String normalizedFqn = MetadataTypeUtils.normalizeFqn(objectFqn);
        String[] parts = normalizedFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Invalid FQN: " + objectFqn //$NON-NLS-1$
                + ". Expected 'Type.Name', e.g. 'Document.SalesOrder'.").toJson(); //$NON-NLS-1$
        }
        MdObject target = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (target == null)
        {
            return ToolResult.error("Object not found: " + normalizedFqn //$NON-NLS-1$
                + ". Check the FQN and use get_metadata_objects to list available objects.").toJson(); //$NON-NLS-1$
        }
        if (!(target instanceof IBmObject))
        {
            return ToolResult.error("Target object is not a BM object: " + normalizedFqn).toJson(); //$NON-NLS-1$
        }

        // Build the list of validated mutations BEFORE the transaction. Any unknown
        // property, irrelevant property, or unparseable value fails the whole call.
        PlannedChanges plan;
        try
        {
            plan = planChanges(target, normalizedFqn, properties, config);
        }
        catch (PropertyException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        final long targetBmId = ((IBmObject)target).bmGetId();
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("SetObjectProperty") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject fresh = (MdObject)tx.getObjectById(targetBmId);
                    if (fresh == null)
                    {
                        throw new RuntimeException("Object not found in transaction: " + normalizedFqn); //$NON-NLS-1$
                    }
                    for (Mutation mutation : plan.mutations)
                    {
                        mutation.apply(tx, fresh);
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error in set_object_property", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set properties: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("objectFqn", normalizedFqn) //$NON-NLS-1$
            .put("objectType", target.eClass().getName()) //$NON-NLS-1$
            .put("appliedProperties", plan.appliedNames) //$NON-NLS-1$
            .put("message", "Set " + plan.appliedNames.size() + " property(ies) on " //$NON-NLS-1$ //$NON-NLS-2$
                + normalizedFqn + ". Run get_project_errors to verify, and " //$NON-NLS-1$
                + "export_configuration_to_xml to confirm values persisted to .mdo.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Validates the supplied properties against the object kind and builds the
     * ordered list of mutations. Throws {@link PropertyException} on the first
     * problem; nothing is mutated here.
     */
    private PlannedChanges planChanges(MdObject target, String normalizedFqn,
        Map<String, JsonElement> properties, Configuration config) throws PropertyException
    {
        PlannedChanges plan = new PlannedChanges();
        for (Map.Entry<String, JsonElement> entry : properties.entrySet())
        {
            String key = entry.getKey();
            JsonElement value = entry.getValue();

            if (target instanceof Document)
            {
                planDocumentProperty(plan, key, value, config);
            }
            else if (target instanceof Catalog)
            {
                planCatalogProperty(plan, key, value);
            }
            else if (target instanceof CommonModule)
            {
                planCommonModuleProperty(plan, key, value);
            }
            else if (target instanceof Subsystem)
            {
                planSubsystemProperty(plan, key, value, config);
            }
            else
            {
                throw new PropertyException("Object type '" + target.eClass().getName() //$NON-NLS-1$
                    + "' (" + normalizedFqn + ") is not supported by set_object_property. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Supported types: Document, Catalog, CommonModule, Subsystem."); //$NON-NLS-1$
            }
            plan.appliedNames.add(key);
        }
        return plan;
    }

    // --- Document ---------------------------------------------------------

    private void planDocumentProperty(PlannedChanges plan, String key, JsonElement value,
        Configuration config) throws PropertyException
    {
        switch (key)
        {
        case "posting": //$NON-NLS-1$
        {
            Posting v = parseEnum(Posting.class, Posting.values(), key, value);
            plan.add((tx, o) -> ((Document)o).setPosting(v));
            break;
        }
        case "realTimePosting": //$NON-NLS-1$
        {
            RealTimePosting v = parseEnum(RealTimePosting.class, RealTimePosting.values(), key, value);
            plan.add((tx, o) -> ((Document)o).setRealTimePosting(v));
            break;
        }
        case "postInPrivilegedMode": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((Document)o).setPostInPrivilegedMode(v));
            break;
        }
        case "unpostInPrivilegedMode": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((Document)o).setUnpostInPrivilegedMode(v));
            break;
        }
        case "numberType": //$NON-NLS-1$
        {
            DocumentNumberType v = parseEnum(DocumentNumberType.class, DocumentNumberType.values(), key, value);
            plan.add((tx, o) -> ((Document)o).setNumberType(v));
            break;
        }
        case "numberLength": //$NON-NLS-1$
        {
            int v = parseInt(key, value, 0, Integer.MAX_VALUE);
            plan.add((tx, o) -> ((Document)o).setNumberLength(v));
            break;
        }
        case "numberAllowedLength": //$NON-NLS-1$
        {
            AllowedLength v = parseEnum(AllowedLength.class, AllowedLength.values(), key, value);
            plan.add((tx, o) -> ((Document)o).setNumberAllowedLength(v));
            break;
        }
        case "numberPeriodicity": //$NON-NLS-1$
        {
            DocumentNumberPeriodicity v =
                parseEnum(DocumentNumberPeriodicity.class, DocumentNumberPeriodicity.values(), key, value);
            plan.add((tx, o) -> ((Document)o).setNumberPeriodicity(v));
            break;
        }
        case "checkUnique": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((Document)o).setCheckUnique(v));
            break;
        }
        case "autonumbering": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((Document)o).setAutonumbering(v));
            break;
        }
        case "registerRecords": //$NON-NLS-1$
        {
            List<Long> registerBmIds = resolveRegisterRecords(key, value, config);
            plan.add((tx, o) -> {
                EList<BasicRegister> records = ((Document)o).getRegisterRecords();
                for (Long bmId : registerBmIds)
                {
                    BasicRegister register = (BasicRegister)tx.getObjectById(bmId);
                    if (register == null)
                    {
                        throw new RuntimeException("Register vanished from transaction (bmId=" + bmId + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (!records.contains(register))
                    {
                        records.add(register);
                    }
                }
            });
            break;
        }
        default:
            throw unknownProperty(key, "Document", //$NON-NLS-1$
                "posting, realTimePosting, postInPrivilegedMode, unpostInPrivilegedMode, " //$NON-NLS-1$
                    + "numberType, numberLength, numberAllowedLength, numberPeriodicity, " //$NON-NLS-1$
                    + "checkUnique, autonumbering, registerRecords"); //$NON-NLS-1$
        }
    }

    // --- Catalog ----------------------------------------------------------

    private void planCatalogProperty(PlannedChanges plan, String key, JsonElement value)
        throws PropertyException
    {
        switch (key)
        {
        case "codeLength": //$NON-NLS-1$
        {
            int v = parseInt(key, value, 0, Integer.MAX_VALUE);
            plan.add((tx, o) -> ((Catalog)o).setCodeLength(v));
            break;
        }
        case "descriptionLength": //$NON-NLS-1$
        {
            int v = parseInt(key, value, 0, Integer.MAX_VALUE);
            plan.add((tx, o) -> ((Catalog)o).setDescriptionLength(v));
            break;
        }
        case "codeType": //$NON-NLS-1$
        {
            CatalogCodeType v = parseEnum(CatalogCodeType.class, CatalogCodeType.values(), key, value);
            plan.add((tx, o) -> ((Catalog)o).setCodeType(v));
            break;
        }
        case "codeAllowedLength": //$NON-NLS-1$
        {
            AllowedLength v = parseEnum(AllowedLength.class, AllowedLength.values(), key, value);
            plan.add((tx, o) -> ((Catalog)o).setCodeAllowedLength(v));
            break;
        }
        case "codeSeries": //$NON-NLS-1$
        {
            CatalogCodesSeries v = parseEnum(CatalogCodesSeries.class, CatalogCodesSeries.values(), key, value);
            plan.add((tx, o) -> ((Catalog)o).setCodeSeries(v));
            break;
        }
        case "checkUnique": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((Catalog)o).setCheckUnique(v));
            break;
        }
        case "autonumbering": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((Catalog)o).setAutonumbering(v));
            break;
        }
        default:
            throw unknownProperty(key, "Catalog", //$NON-NLS-1$
                "codeLength, descriptionLength, codeType, codeAllowedLength, codeSeries, " //$NON-NLS-1$
                    + "checkUnique, autonumbering"); //$NON-NLS-1$
        }
    }

    // --- CommonModule -----------------------------------------------------

    private void planCommonModuleProperty(PlannedChanges plan, String key, JsonElement value)
        throws PropertyException
    {
        switch (key)
        {
        case "server": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((CommonModule)o).setServer(v));
            break;
        }
        case "serverCall": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((CommonModule)o).setServerCall(v));
            break;
        }
        case "clientManagedApplication": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((CommonModule)o).setClientManagedApplication(v));
            break;
        }
        case "clientOrdinaryApplication": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((CommonModule)o).setClientOrdinaryApplication(v));
            break;
        }
        case "externalConnection": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((CommonModule)o).setExternalConnection(v));
            break;
        }
        case "global": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((CommonModule)o).setGlobal(v));
            break;
        }
        case "privileged": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((CommonModule)o).setPrivileged(v));
            break;
        }
        case "returnValuesReuse": //$NON-NLS-1$
        {
            ReturnValuesReuse v = parseEnum(ReturnValuesReuse.class, ReturnValuesReuse.values(), key, value);
            plan.add((tx, o) -> ((CommonModule)o).setReturnValuesReuse(v));
            break;
        }
        default:
            throw unknownProperty(key, "CommonModule", //$NON-NLS-1$
                "server, serverCall, clientManagedApplication, clientOrdinaryApplication, " //$NON-NLS-1$
                    + "externalConnection, global, privileged, returnValuesReuse"); //$NON-NLS-1$
        }
    }

    // --- Subsystem --------------------------------------------------------

    private void planSubsystemProperty(PlannedChanges plan, String key, JsonElement value,
        Configuration config) throws PropertyException
    {
        switch (key)
        {
        case "includeInCommandInterface": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((Subsystem)o).setIncludeInCommandInterface(v));
            break;
        }
        case "content": //$NON-NLS-1$
        {
            List<Long> contentBmIds = resolveSubsystemContent(key, value, config);
            plan.add((tx, o) -> {
                EList<MdObject> content = ((Subsystem)o).getContent();
                for (Long bmId : contentBmIds)
                {
                    MdObject member = (MdObject)tx.getObjectById(bmId);
                    if (member == null)
                    {
                        throw new RuntimeException("Object vanished from transaction (bmId=" + bmId + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (!content.contains(member))
                    {
                        content.add(member);
                    }
                }
            });
            break;
        }
        default:
            throw unknownProperty(key, "Subsystem", "includeInCommandInterface, content"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- Reference resolution --------------------------------------------

    private List<Long> resolveRegisterRecords(String key, JsonElement value, Configuration config)
        throws PropertyException
    {
        List<String> fqns = parseFqnArray(key, value);
        List<Long> bmIds = new ArrayList<>(fqns.size());
        for (String fqn : fqns)
        {
            MdObject obj = resolveObject(config, fqn);
            if (obj == null)
            {
                throw new PropertyException("registerRecords: register not found: " + fqn //$NON-NLS-1$
                    + ". Use a register FQN such as 'AccumulationRegister.Sales' or " //$NON-NLS-1$
                    + "'InformationRegister.Prices'."); //$NON-NLS-1$
            }
            if (!(obj instanceof BasicRegister))
            {
                throw new PropertyException("registerRecords: '" + fqn //$NON-NLS-1$
                    + "' is not a register. Only registers (InformationRegister, " //$NON-NLS-1$
                    + "AccumulationRegister, AccountingRegister, CalculationRegister) can be record sets."); //$NON-NLS-1$
            }
            if (!(obj instanceof IBmObject))
            {
                throw new PropertyException("registerRecords: '" + fqn + "' is not a BM object."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            bmIds.add(((IBmObject)obj).bmGetId());
        }
        return bmIds;
    }

    private List<Long> resolveSubsystemContent(String key, JsonElement value, Configuration config)
        throws PropertyException
    {
        List<String> fqns = parseFqnArray(key, value);
        List<Long> bmIds = new ArrayList<>(fqns.size());
        for (String fqn : fqns)
        {
            MdObject obj = resolveObject(config, fqn);
            if (obj == null)
            {
                throw new PropertyException("content: object not found: " + fqn //$NON-NLS-1$
                    + ". Use a metadata FQN such as 'Catalog.Products' or 'Document.SalesOrder'."); //$NON-NLS-1$
            }
            if (!(obj instanceof IBmObject))
            {
                throw new PropertyException("content: '" + fqn + "' is not a BM object."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            bmIds.add(((IBmObject)obj).bmGetId());
        }
        return bmIds;
    }

    private static MdObject resolveObject(Configuration config, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String normalized = MetadataTypeUtils.normalizeFqn(fqn.trim());
        String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        return MetadataTypeUtils.findObject(config, parts[0], parts[1]);
    }

    // --- Value parsing helpers -------------------------------------------

    private static Map<String, JsonElement> parseProperties(String raw)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }
        try
        {
            JsonElement element = JsonParser.parseString(raw);
            if (!element.isJsonObject())
            {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, JsonElement> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet())
            {
                result.put(e.getKey(), e.getValue());
            }
            return result.isEmpty() ? null : result;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static boolean parseBoolean(String key, JsonElement value) throws PropertyException
    {
        if (value != null && value.isJsonPrimitive())
        {
            JsonPrimitive p = value.getAsJsonPrimitive();
            if (p.isBoolean())
            {
                return p.getAsBoolean();
            }
            String s = p.getAsString().trim().toLowerCase();
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return true;
            }
            if ("false".equals(s) || "0".equals(s) || "no".equals(s)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return false;
            }
        }
        throw new PropertyException("Property '" + key + "' expects a boolean (true/false), got: " //$NON-NLS-1$ //$NON-NLS-2$
            + describe(value));
    }

    private static int parseInt(String key, JsonElement value, int min, int max) throws PropertyException
    {
        if (value != null && value.isJsonPrimitive())
        {
            JsonPrimitive p = value.getAsJsonPrimitive();
            try
            {
                double d = p.isNumber() ? p.getAsDouble() : Double.parseDouble(p.getAsString().trim());
                if (d == Math.floor(d) && !Double.isInfinite(d) && d >= min && d <= max)
                {
                    return (int)d;
                }
            }
            catch (NumberFormatException ignored)
            {
                // fall through to the error below
            }
        }
        throw new PropertyException("Property '" + key + "' expects an integer in [" + min + ", " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + max + "], got: " + describe(value)); //$NON-NLS-1$
    }

    /**
     * Parses an EMF enum value from a user token. Matching is case-insensitive and
     * accepts the Java constant name (e.g. {@code ALLOW}), the EMF literal
     * (e.g. {@code Allow} / {@code "Whole catalog"}), and a normalized form with
     * separators removed (e.g. {@code WholeCatalog}).
     */
    private static <E extends Enum<E> & Enumerator> E parseEnum(Class<E> enumType, E[] constants,
        String key, JsonElement value) throws PropertyException
    {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
        {
            String token = value.getAsString().trim();
            String normalizedToken = normalizeEnumToken(token);
            for (E constant : constants)
            {
                if (constant.name().equalsIgnoreCase(token)
                    || normalizeEnumToken(constant.name()).equals(normalizedToken)
                    || normalizeEnumToken(constant.getLiteral()).equals(normalizedToken))
                {
                    return constant;
                }
            }
        }
        throw new PropertyException("Property '" + key + "' expects one of " //$NON-NLS-1$ //$NON-NLS-2$
            + enumLiterals(constants) + ", got: " + describe(value)); //$NON-NLS-1$
    }

    private static String normalizeEnumToken(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        return s.replace("_", "").replace(" ", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static <E extends Enum<E> & Enumerator> String enumLiterals(E[] constants)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < constants.length; i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append('\'').append(constants[i].getLiteral()).append('\'');
        }
        return sb.toString();
    }

    private static List<String> parseFqnArray(String key, JsonElement value) throws PropertyException
    {
        List<String> result = new ArrayList<>();
        if (value != null && value.isJsonArray())
        {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement el : array)
            {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString())
                {
                    String s = el.getAsString().trim();
                    if (!s.isEmpty())
                    {
                        result.add(s);
                    }
                }
                else
                {
                    throw new PropertyException("Property '" + key //$NON-NLS-1$
                        + "' must be an array of FQN strings, got element: " + describe(el)); //$NON-NLS-1$
                }
            }
        }
        else if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
        {
            // Allow a single FQN string for convenience.
            String s = value.getAsString().trim();
            if (!s.isEmpty())
            {
                result.add(s);
            }
        }
        else
        {
            throw new PropertyException("Property '" + key //$NON-NLS-1$
                + "' must be an array of FQN strings, got: " + describe(value)); //$NON-NLS-1$
        }
        if (result.isEmpty())
        {
            throw new PropertyException("Property '" + key + "' must contain at least one FQN."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    private static PropertyException unknownProperty(String key, String typeName, String supported)
    {
        return new PropertyException("Unknown or irrelevant property '" + key //$NON-NLS-1$
            + "' for " + typeName + ". Supported properties: " + supported + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String describe(JsonElement value)
    {
        if (value == null || value.isJsonNull())
        {
            return "null"; //$NON-NLS-1$
        }
        return value.toString();
    }

    // --- Internal types ---------------------------------------------------

    /** A single deferred model mutation, applied inside the BM transaction. */
    @FunctionalInterface
    private interface Mutation
    {
        void apply(IBmTransaction tx, MdObject object);
    }

    /** Validated changes ready to be applied inside the transaction. */
    private static final class PlannedChanges
    {
        final List<Mutation> mutations = new ArrayList<>();
        final List<String> appliedNames = new ArrayList<>();

        void add(Mutation mutation)
        {
            mutations.add(mutation);
        }
    }

    /** Signals a validation/parse failure with a user-facing message. */
    private static final class PropertyException extends Exception
    {
        private static final long serialVersionUID = 1L;

        PropertyException(String message)
        {
            super(message);
        }
    }
}
