/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegisterType;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterPeriodicity;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.RegisterWriteMode;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tool to set register-specific properties on an InformationRegister or an
 * AccumulationRegister.
 * <p>
 * Properties are supplied as a JSON object under the {@code properties}
 * parameter and applied inside a single BM write transaction (re-fetching the
 * register by its {@code bmId}). Supported keys:
 * <ul>
 * <li><b>InformationRegister</b>:
 *     <ul>
 *     <li>{@code writeMode} - {@code Independent} or {@code SubordinateToRecorder}
 *         (alias {@code RecorderSubordinate}); maps to
 *         {@link RegisterWriteMode};</li>
 *     <li>{@code periodicity} - one of {@code Nonperiodical}, {@code Second},
 *         {@code Day}, {@code Month}, {@code Quarter}, {@code Year} or
 *         {@code RecorderPosition}; maps to
 *         {@link InformationRegisterPeriodicity}.</li>
 *     </ul></li>
 * <li><b>AccumulationRegister</b>:
 *     <ul>
 *     <li>{@code registerType} - {@code Balance} or {@code Turnovers}; maps to
 *         {@link AccumulationRegisterType}.</li>
 *     </ul></li>
 * </ul>
 * A property that is not relevant for the target register kind (e.g.
 * {@code writeMode} on an AccumulationRegister) yields a clear error instead of
 * being silently ignored.
 */
public class SetRegisterPropertyTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "set_register_property"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set register-specific properties on an InformationRegister or an " //$NON-NLS-1$
            + "AccumulationRegister. Properties are passed as a JSON object under 'properties'. " //$NON-NLS-1$
            + "InformationRegister: 'writeMode' ('Independent' | 'SubordinateToRecorder') and " //$NON-NLS-1$
            + "'periodicity' ('Nonperiodical' | 'Second' | 'Day' | 'Month' | 'Quarter' | 'Year' | " //$NON-NLS-1$
            + "'RecorderPosition'). AccumulationRegister: 'registerType' ('Balance' | 'Turnovers'). " //$NON-NLS-1$
            + "Example: {objectFqn: 'InformationRegister.Prices', " //$NON-NLS-1$
            + "properties: {writeMode: 'Independent', periodicity: 'Day'}}. " //$NON-NLS-1$
            + "Russian object names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the register (required), e.g. 'InformationRegister.Prices', " //$NON-NLS-1$
                + "'AccumulationRegister.Stocks'. Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("properties", //$NON-NLS-1$
                "JSON object with register properties to set (required). " //$NON-NLS-1$
                + "InformationRegister keys: 'writeMode' " //$NON-NLS-1$
                + "('Independent' | 'SubordinateToRecorder'), 'periodicity' " //$NON-NLS-1$
                + "('Nonperiodical' | 'Second' | 'Day' | 'Month' | 'Quarter' | 'Year' | " //$NON-NLS-1$
                + "'RecorderPosition'). AccumulationRegister key: 'registerType' " //$NON-NLS-1$
                + "('Balance' | 'Turnovers').", true) //$NON-NLS-1$
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
                + "Usage: {projectName: 'MyProject', objectFqn: 'InformationRegister.Prices', " //$NON-NLS-1$
                + "properties: {writeMode: 'Independent'}}").toJson(); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("objectFqn is required. " //$NON-NLS-1$
                + "Examples: 'InformationRegister.Prices', 'AccumulationRegister.Stocks'.").toJson(); //$NON-NLS-1$
        }
        if (propertiesRaw == null || propertiesRaw.trim().isEmpty())
        {
            return ToolResult.error("properties is required and must be a non-empty JSON object. " //$NON-NLS-1$
                + "Example: {writeMode: 'Independent', periodicity: 'Day'}.").toJson(); //$NON-NLS-1$
        }

        // Parse the properties object BEFORE any transaction.
        JsonObject properties;
        try
        {
            JsonElement element = JsonParser.parseString(propertiesRaw);
            if (!element.isJsonObject())
            {
                return ToolResult.error("'properties' must be a JSON object, got: " + propertiesRaw).toJson(); //$NON-NLS-1$
            }
            properties = element.getAsJsonObject();
        }
        catch (RuntimeException e)
        {
            return ToolResult.error("'properties' is not valid JSON: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (properties.size() == 0)
        {
            return ToolResult.error("'properties' is empty. Provide at least one property to set.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, objectFqn, properties);
    }

    private String executeInternal(String projectName, String objectFqn, final JsonObject properties)
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

        String normalizedFqn = MetadataTypeUtils.normalizeFqn(objectFqn);
        String[] parts = normalizedFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Invalid FQN: " + normalizedFqn + ". Expected 'Type.Name'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MdObject target = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (target == null)
        {
            return ToolResult.error("Object not found: " + normalizedFqn + ". " //$NON-NLS-1$
                + "Check the FQN format: 'Type.Name' (e.g. 'InformationRegister.Prices'). " //$NON-NLS-1$
                + "Use get_metadata_objects to list available objects.").toJson(); //$NON-NLS-1$
        }
        if (!(target instanceof InformationRegister) && !(target instanceof AccumulationRegister))
        {
            return ToolResult.error("Object type '" + target.eClass().getName() //$NON-NLS-1$
                + "' is not supported. set_register_property applies to InformationRegister " //$NON-NLS-1$
                + "and AccumulationRegister only.").toJson(); //$NON-NLS-1$
        }

        // Validate the property set against the register kind BEFORE the transaction.
        final List<String> applied = new ArrayList<>();
        try
        {
            validateProperties(target, properties);
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        if (!(target instanceof IBmObject))
        {
            return ToolResult.error("Target object is not a BM object").toJson(); //$NON-NLS-1$
        }
        final long targetBmId = ((IBmObject) target).bmGetId();

        try
        {
            bmModel.execute(new AbstractBmTask<Void>("SetRegisterProperty") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject fresh = (MdObject) tx.getObjectById(targetBmId);
                    if (fresh == null)
                    {
                        throw new RuntimeException("Register not found in transaction"); //$NON-NLS-1$
                    }
                    applyProperties(fresh, properties, applied);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error in set_register_property", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set register properties: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("objectFqn", normalizedFqn) //$NON-NLS-1$
            .put("appliedProperties", applied) //$NON-NLS-1$
            .put("message", "Register properties updated on " + normalizedFqn //$NON-NLS-1$ //$NON-NLS-2$
                + ". Run get_metadata_details to verify and get_project_errors to confirm.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Validates the property keys/values against the target register kind without
     * mutating the model. Unknown keys and properties not relevant for the kind
     * fail loudly.
     *
     * @param target the resolved register
     * @param properties the requested properties
     * @throws IllegalArgumentException on an unknown key, an irrelevant property
     *             for the register kind, or an invalid enum value
     */
    private void validateProperties(MdObject target, JsonObject properties)
    {
        boolean isInformation = target instanceof InformationRegister;
        boolean isAccumulation = target instanceof AccumulationRegister;

        for (Map.Entry<String, JsonElement> entry : properties.entrySet())
        {
            String key = entry.getKey();
            String value = asString(entry.getValue());
            String normalizedKey = key.trim().toLowerCase();
            switch (normalizedKey)
            {
                case "writemode": //$NON-NLS-1$
                    if (!isInformation)
                    {
                        throw new IllegalArgumentException(irrelevant(key, target));
                    }
                    parseWriteMode(value); // validate
                    break;
                case "periodicity": //$NON-NLS-1$
                    if (!isInformation)
                    {
                        throw new IllegalArgumentException(irrelevant(key, target));
                    }
                    parsePeriodicity(value); // validate
                    break;
                case "registertype": //$NON-NLS-1$
                    if (!isAccumulation)
                    {
                        throw new IllegalArgumentException(irrelevant(key, target));
                    }
                    parseRegisterType(value); // validate
                    break;
                default:
                    throw new IllegalArgumentException("Unknown register property: '" + key + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Supported: 'writeMode', 'periodicity' (InformationRegister), " //$NON-NLS-1$
                        + "'registerType' (AccumulationRegister)."); //$NON-NLS-1$
            }
        }
    }

    /**
     * Applies the validated properties to the freshly re-fetched register and
     * records human-readable {@code key=Value} descriptions into {@code applied}.
     *
     * @param target the register (re-fetched inside the transaction)
     * @param properties the requested properties
     * @param applied collects the applied property descriptions
     */
    private void applyProperties(MdObject target, JsonObject properties, List<String> applied)
    {
        for (Map.Entry<String, JsonElement> entry : properties.entrySet())
        {
            String key = entry.getKey();
            String value = asString(entry.getValue());
            String normalizedKey = key.trim().toLowerCase();
            switch (normalizedKey)
            {
                case "writemode": //$NON-NLS-1$
                {
                    RegisterWriteMode mode = parseWriteMode(value);
                    ((InformationRegister) target).setWriteMode(mode);
                    applied.add("writeMode=" + mode.getName()); //$NON-NLS-1$
                    break;
                }
                case "periodicity": //$NON-NLS-1$
                {
                    InformationRegisterPeriodicity periodicity = parsePeriodicity(value);
                    ((InformationRegister) target).setInformationRegisterPeriodicity(periodicity);
                    applied.add("periodicity=" + periodicity.getName()); //$NON-NLS-1$
                    break;
                }
                case "registertype": //$NON-NLS-1$
                {
                    AccumulationRegisterType registerType = parseRegisterType(value);
                    ((AccumulationRegister) target).setRegisterType(registerType);
                    applied.add("registerType=" + registerType.getName()); //$NON-NLS-1$
                    break;
                }
                default:
                    // Already rejected by validateProperties.
                    throw new RuntimeException("Unexpected property: " + key); //$NON-NLS-1$
            }
        }
    }

    private static String irrelevant(String key, MdObject target)
    {
        return "Property '" + key + "' is not relevant for '" + target.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
            + "'. 'writeMode'/'periodicity' apply to InformationRegister; " //$NON-NLS-1$
            + "'registerType' applies to AccumulationRegister."; //$NON-NLS-1$
    }

    private static String asString(JsonElement element)
    {
        if (element == null || element.isJsonNull())
        {
            return null;
        }
        if (element.isJsonPrimitive())
        {
            return element.getAsString();
        }
        return GsonProvider.toJson(element);
    }

    private static RegisterWriteMode parseWriteMode(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new IllegalArgumentException("'writeMode' value is required. " //$NON-NLS-1$
                + "Expected 'Independent' or 'SubordinateToRecorder'."); //$NON-NLS-1$
        }
        String v = value.trim().toLowerCase();
        switch (v)
        {
            case "independent": //$NON-NLS-1$
                return RegisterWriteMode.INDEPENDENT;
            case "subordinatetorecorder": //$NON-NLS-1$
            case "recordersubordinate": //$NON-NLS-1$
            case "subordinate": //$NON-NLS-1$
                return RegisterWriteMode.RECORDER_SUBORDINATE;
            default:
                throw new IllegalArgumentException("Invalid 'writeMode': " + value //$NON-NLS-1$
                    + ". Expected 'Independent' or 'SubordinateToRecorder'."); //$NON-NLS-1$
        }
    }

    private static InformationRegisterPeriodicity parsePeriodicity(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new IllegalArgumentException("'periodicity' value is required. " //$NON-NLS-1$
                + "Expected one of 'Nonperiodical', 'Second', 'Day', 'Month', 'Quarter', " //$NON-NLS-1$
                + "'Year', 'RecorderPosition'."); //$NON-NLS-1$
        }
        String v = value.trim().toLowerCase();
        switch (v)
        {
            case "nonperiodical": //$NON-NLS-1$
            case "nonperiodic": //$NON-NLS-1$
            case "none": //$NON-NLS-1$
                return InformationRegisterPeriodicity.NONPERIODICAL;
            case "second": //$NON-NLS-1$
            case "withinsecond": //$NON-NLS-1$
                return InformationRegisterPeriodicity.SECOND;
            case "day": //$NON-NLS-1$
                return InformationRegisterPeriodicity.DAY;
            case "month": //$NON-NLS-1$
                return InformationRegisterPeriodicity.MONTH;
            case "quarter": //$NON-NLS-1$
                return InformationRegisterPeriodicity.QUARTER;
            case "year": //$NON-NLS-1$
                return InformationRegisterPeriodicity.YEAR;
            case "recorderposition": //$NON-NLS-1$
            case "recorder": //$NON-NLS-1$
                return InformationRegisterPeriodicity.RECORDER_POSITION;
            default:
                throw new IllegalArgumentException("Invalid 'periodicity': " + value //$NON-NLS-1$
                    + ". Expected one of 'Nonperiodical', 'Second', 'Day', 'Month', 'Quarter', " //$NON-NLS-1$
                    + "'Year', 'RecorderPosition'."); //$NON-NLS-1$
        }
    }

    private static AccumulationRegisterType parseRegisterType(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new IllegalArgumentException("'registerType' value is required. " //$NON-NLS-1$
                + "Expected 'Balance' or 'Turnovers'."); //$NON-NLS-1$
        }
        String v = value.trim().toLowerCase();
        switch (v)
        {
            case "balance": //$NON-NLS-1$
            case "balances": //$NON-NLS-1$
                return AccumulationRegisterType.BALANCE;
            case "turnovers": //$NON-NLS-1$
            case "turnover": //$NON-NLS-1$
                return AccumulationRegisterType.TURNOVERS;
            default:
                throw new IllegalArgumentException("Invalid 'registerType': " + value //$NON-NLS-1$
                    + ". Expected 'Balance' or 'Turnovers'."); //$NON-NLS-1$
        }
    }
}
