/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.AccountingRegister;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegisterType;
import com._1c.g5.v8.dt.metadata.mdclass.CalculationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.CalculationRegisterPeriodicity;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
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
 * Tool to set register-specific properties on an InformationRegister,
 * AccumulationRegister, AccountingRegister or CalculationRegister.
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
 * <li><b>AccountingRegister</b>:
 *     <ul>
 *     <li>{@code chartOfAccounts} - FQN of a {@code ChartOfAccounts}, resolved
 *         in this project; maps to {@code setChartOfAccounts};</li>
 *     <li>{@code correspondence} - boolean;</li>
 *     <li>{@code enableTotalsSplitting} - boolean;</li>
 *     <li>{@code periodAdjustmentLength} - integer.</li>
 *     </ul></li>
 * <li><b>CalculationRegister</b>:
 *     <ul>
 *     <li>{@code chartOfCalculationTypes} - FQN of a
 *         {@code ChartOfCalculationTypes}; maps to
 *         {@code setChartOfCalculationTypes};</li>
 *     <li>{@code periodicity} - one of {@code Day}, {@code Month},
 *         {@code Quarter}, {@code Year}; maps to
 *         {@link CalculationRegisterPeriodicity};</li>
 *     <li>{@code actionPeriod} - boolean;</li>
 *     <li>{@code basePeriod} - boolean;</li>
 *     <li>{@code schedule} - FQN of an {@code InformationRegister} used as the
 *         schedule; maps to {@code setSchedule}.</li>
 *     </ul></li>
 * </ul>
 * Reference values ({@code chartOfAccounts}, {@code chartOfCalculationTypes},
 * {@code schedule}) are resolved against the metadata objects of the current
 * project (the same project-scoped resolution path used by the other write
 * tools); a missing or wrong-typed target fails loudly.
 * <p>
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
        return "Set register-specific properties on an InformationRegister, " //$NON-NLS-1$
            + "AccumulationRegister, AccountingRegister or CalculationRegister. " //$NON-NLS-1$
            + "Properties are passed as a JSON object under 'properties'. " //$NON-NLS-1$
            + "InformationRegister: 'writeMode' ('Independent' | 'SubordinateToRecorder') and " //$NON-NLS-1$
            + "'periodicity' ('Nonperiodical' | 'Second' | 'Day' | 'Month' | 'Quarter' | 'Year' | " //$NON-NLS-1$
            + "'RecorderPosition'). AccumulationRegister: 'registerType' ('Balance' | 'Turnovers'). " //$NON-NLS-1$
            + "AccountingRegister: 'chartOfAccounts' (FQN of a ChartOfAccounts), " //$NON-NLS-1$
            + "'correspondence' (boolean), 'enableTotalsSplitting' (boolean), " //$NON-NLS-1$
            + "'periodAdjustmentLength' (integer). CalculationRegister: " //$NON-NLS-1$
            + "'chartOfCalculationTypes' (FQN of a ChartOfCalculationTypes), " //$NON-NLS-1$
            + "'periodicity' ('Day' | 'Month' | 'Quarter' | 'Year'), 'actionPeriod' (boolean), " //$NON-NLS-1$
            + "'basePeriod' (boolean), 'schedule' (FQN of an InformationRegister). " //$NON-NLS-1$
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
                + "'AccumulationRegister.Stocks', 'AccountingRegister.Main', " //$NON-NLS-1$
                + "'CalculationRegister.Payroll'. Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("properties", //$NON-NLS-1$
                "JSON object with register properties to set (required). " //$NON-NLS-1$
                + "InformationRegister keys: 'writeMode' " //$NON-NLS-1$
                + "('Independent' | 'SubordinateToRecorder'), 'periodicity' " //$NON-NLS-1$
                + "('Nonperiodical' | 'Second' | 'Day' | 'Month' | 'Quarter' | 'Year' | " //$NON-NLS-1$
                + "'RecorderPosition'). AccumulationRegister key: 'registerType' " //$NON-NLS-1$
                + "('Balance' | 'Turnovers'). AccountingRegister keys: 'chartOfAccounts' " //$NON-NLS-1$
                + "(ChartOfAccounts FQN), 'correspondence' (bool), 'enableTotalsSplitting' (bool), " //$NON-NLS-1$
                + "'periodAdjustmentLength' (int). CalculationRegister keys: " //$NON-NLS-1$
                + "'chartOfCalculationTypes' (ChartOfCalculationTypes FQN), 'periodicity' " //$NON-NLS-1$
                + "('Day' | 'Month' | 'Quarter' | 'Year'), 'actionPeriod' (bool), " //$NON-NLS-1$
                + "'basePeriod' (bool), 'schedule' (InformationRegister FQN).", true) //$NON-NLS-1$
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
                + "Examples: 'InformationRegister.Prices', 'AccumulationRegister.Stocks', " //$NON-NLS-1$
                + "'AccountingRegister.Main', 'CalculationRegister.Payroll'.").toJson(); //$NON-NLS-1$
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
        final Configuration config = ctx.config;

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
        if (!isSupportedRegister(target))
        {
            return ToolResult.error("Object type '" + target.eClass().getName() //$NON-NLS-1$
                + "' is not supported. set_register_property applies to InformationRegister, " //$NON-NLS-1$
                + "AccumulationRegister, AccountingRegister and CalculationRegister only.").toJson(); //$NON-NLS-1$
        }

        // Validate the property set against the register kind BEFORE the transaction,
        // resolving reference targets up front so a missing/wrong target fails early.
        // Resolved reference targets are kept as bmIds (keyed by normalized property
        // name) and re-fetched inside the transaction.
        final List<String> applied = new ArrayList<>();
        final Map<String, Long> refBmIds = new HashMap<>();
        try
        {
            validateProperties(target, properties, config, refBmIds);
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
                    applyProperties(fresh, properties, applied, tx, refBmIds);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error in set_register_property", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set register properties: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Flush the register to its .mdo on disk and refresh stale validation markers.
        String persistError = persistAndRevalidate(project, topObjectFqnOf(target));

        ToolResult result = ToolResult.success()
            .put("objectFqn", normalizedFqn) //$NON-NLS-1$
            .put("appliedProperties", applied); //$NON-NLS-1$
        if (persistError != null)
        {
            result.put("persistWarning", "Properties set in the in-memory model but the export to the " //$NON-NLS-1$ //$NON-NLS-2$
                + ".mdo file could not be forced: " + persistError); //$NON-NLS-1$
        }
        return result
            .put("message", "Register properties updated on " + normalizedFqn //$NON-NLS-1$ //$NON-NLS-2$
                + ". Run get_metadata_details to verify and get_project_errors to confirm.") //$NON-NLS-1$
            .toJson();
    }

    private static boolean isSupportedRegister(MdObject target)
    {
        return target instanceof InformationRegister
            || target instanceof AccumulationRegister
            || target instanceof AccountingRegister
            || target instanceof CalculationRegister;
    }

    /**
     * Validates the property keys/values against the target register kind without
     * mutating the model. Unknown keys and properties not relevant for the kind
     * fail loudly. Reference values are resolved here so a missing or wrong-typed
     * target is reported before the transaction starts.
     *
     * @param target the resolved register
     * @param properties the requested properties
     * @param config the configuration (used to resolve reference FQNs)
     * @param refBmIds collects resolved reference target bmIds, keyed by the
     *            normalized property name
     * @throws IllegalArgumentException on an unknown key, an irrelevant property
     *             for the register kind, an invalid enum value, or an unresolved
     *             reference
     */
    private void validateProperties(MdObject target, JsonObject properties, Configuration config,
        Map<String, Long> refBmIds)
    {
        boolean isInformation = target instanceof InformationRegister;
        boolean isAccumulation = target instanceof AccumulationRegister;
        boolean isAccounting = target instanceof AccountingRegister;
        boolean isCalculation = target instanceof CalculationRegister;

        for (Map.Entry<String, JsonElement> entry : properties.entrySet())
        {
            String key = entry.getKey();
            String value = asString(entry.getValue());
            String normalizedKey = key.trim().toLowerCase();
            switch (normalizedKey)
            {
                case "writemode": //$NON-NLS-1$
                    requireKind(isInformation, key, target);
                    parseWriteMode(value); // validate
                    break;
                case "periodicity": //$NON-NLS-1$
                    // Shared key name, different enum per kind.
                    if (isInformation)
                    {
                        parsePeriodicity(value); // validate
                    }
                    else if (isCalculation)
                    {
                        parseCalculationPeriodicity(value); // validate
                    }
                    else
                    {
                        throw new IllegalArgumentException(irrelevant(key, target));
                    }
                    break;
                case "registertype": //$NON-NLS-1$
                    requireKind(isAccumulation, key, target);
                    parseRegisterType(value); // validate
                    break;
                case "chartofaccounts": //$NON-NLS-1$
                    requireKind(isAccounting, key, target);
                    refBmIds.put(normalizedKey,
                        resolveRef(key, value, config, ChartOfAccounts.class, "ChartOfAccounts")); //$NON-NLS-1$
                    break;
                case "correspondence": //$NON-NLS-1$
                    requireKind(isAccounting, key, target);
                    parseBool(key, value); // validate
                    break;
                case "enabletotalssplitting": //$NON-NLS-1$
                    requireKind(isAccounting, key, target);
                    parseBool(key, value); // validate
                    break;
                case "periodadjustmentlength": //$NON-NLS-1$
                    requireKind(isAccounting, key, target);
                    parseInt(key, value); // validate
                    break;
                case "chartofcalculationtypes": //$NON-NLS-1$
                    requireKind(isCalculation, key, target);
                    refBmIds.put(normalizedKey, resolveRef(key, value, config, ChartOfCalculationTypes.class,
                        "ChartOfCalculationTypes")); //$NON-NLS-1$
                    break;
                case "actionperiod": //$NON-NLS-1$
                    requireKind(isCalculation, key, target);
                    parseBool(key, value); // validate
                    break;
                case "baseperiod": //$NON-NLS-1$
                    requireKind(isCalculation, key, target);
                    parseBool(key, value); // validate
                    break;
                case "schedule": //$NON-NLS-1$
                    requireKind(isCalculation, key, target);
                    refBmIds.put(normalizedKey, resolveRef(key, value, config, InformationRegister.class,
                        "InformationRegister")); //$NON-NLS-1$
                    break;
                default:
                    throw new IllegalArgumentException("Unknown register property: '" + key + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Supported: 'writeMode', 'periodicity' (InformationRegister); " //$NON-NLS-1$
                        + "'registerType' (AccumulationRegister); 'chartOfAccounts', 'correspondence', " //$NON-NLS-1$
                        + "'enableTotalsSplitting', 'periodAdjustmentLength' (AccountingRegister); " //$NON-NLS-1$
                        + "'chartOfCalculationTypes', 'periodicity', 'actionPeriod', 'basePeriod', " //$NON-NLS-1$
                        + "'schedule' (CalculationRegister)."); //$NON-NLS-1$
            }
        }
    }

    private static void requireKind(boolean ok, String key, MdObject target)
    {
        if (!ok)
        {
            throw new IllegalArgumentException(irrelevant(key, target));
        }
    }

    /**
     * Resolves a reference FQN to a metadata object of the expected type in this
     * project and returns its {@code bmId}. The object is re-fetched by that id
     * inside the transaction so the assignment uses the transactional instance.
     *
     * @param key the property name (for error messages)
     * @param fqn the FQN value
     * @param config the configuration to resolve against
     * @param expectedType the expected metadata class
     * @param expectedTypeName human-readable expected type name
     * @return the resolved target's {@code bmId}
     * @throws IllegalArgumentException when the FQN is missing, not found or of the
     *             wrong type
     */
    private static long resolveRef(String key, String fqn, Configuration config, Class<?> expectedType,
        String expectedTypeName)
    {
        if (fqn == null || fqn.trim().isEmpty())
        {
            throw new IllegalArgumentException("'" + key + "' value is required: a " + expectedTypeName //$NON-NLS-1$ //$NON-NLS-2$
                + " FQN, e.g. '" + expectedTypeName + ".Main'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String normalized = MetadataTypeUtils.normalizeFqn(fqn.trim());
        String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            throw new IllegalArgumentException("'" + key + "': invalid FQN '" + fqn //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Expected 'Type.Name', e.g. '" + expectedTypeName + ".Main'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MdObject obj = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (obj == null)
        {
            throw new IllegalArgumentException("'" + key + "': object not found: " + normalized //$NON-NLS-1$ //$NON-NLS-2$
                + ". Use a " + expectedTypeName + " FQN that exists in this project."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!expectedType.isInstance(obj))
        {
            throw new IllegalArgumentException("'" + key + "': '" + normalized + "' is a " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + obj.eClass().getName() + ", not a " + expectedTypeName + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!(obj instanceof IBmObject))
        {
            throw new IllegalArgumentException("'" + key + "': '" + normalized + "' is not a BM object."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return ((IBmObject) obj).bmGetId();
    }

    /**
     * Applies the validated properties to the freshly re-fetched register and
     * records human-readable {@code key=Value} descriptions into {@code applied}.
     *
     * @param target the register (re-fetched inside the transaction)
     * @param properties the requested properties
     * @param applied collects the applied property descriptions
     * @param tx the active transaction (used to re-fetch reference targets)
     * @param refBmIds resolved reference target bmIds, keyed by normalized property name
     */
    private void applyProperties(MdObject target, JsonObject properties, List<String> applied, IBmTransaction tx,
        Map<String, Long> refBmIds)
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
                    if (target instanceof InformationRegister)
                    {
                        InformationRegisterPeriodicity periodicity = parsePeriodicity(value);
                        ((InformationRegister) target).setInformationRegisterPeriodicity(periodicity);
                        applied.add("periodicity=" + periodicity.getName()); //$NON-NLS-1$
                    }
                    else
                    {
                        CalculationRegisterPeriodicity periodicity = parseCalculationPeriodicity(value);
                        ((CalculationRegister) target).setPeriodicity(periodicity);
                        applied.add("periodicity=" + periodicity.getName()); //$NON-NLS-1$
                    }
                    break;
                }
                case "registertype": //$NON-NLS-1$
                {
                    AccumulationRegisterType registerType = parseRegisterType(value);
                    ((AccumulationRegister) target).setRegisterType(registerType);
                    applied.add("registerType=" + registerType.getName()); //$NON-NLS-1$
                    break;
                }
                case "chartofaccounts": //$NON-NLS-1$
                {
                    ChartOfAccounts chart = (ChartOfAccounts) refTarget(tx, key, refBmIds.get(normalizedKey));
                    ((AccountingRegister) target).setChartOfAccounts(chart);
                    applied.add("chartOfAccounts=" + value); //$NON-NLS-1$
                    break;
                }
                case "correspondence": //$NON-NLS-1$
                {
                    boolean v = parseBool(key, value);
                    ((AccountingRegister) target).setCorrespondence(v);
                    applied.add("correspondence=" + v); //$NON-NLS-1$
                    break;
                }
                case "enabletotalssplitting": //$NON-NLS-1$
                {
                    boolean v = parseBool(key, value);
                    ((AccountingRegister) target).setEnableTotalsSplitting(v);
                    applied.add("enableTotalsSplitting=" + v); //$NON-NLS-1$
                    break;
                }
                case "periodadjustmentlength": //$NON-NLS-1$
                {
                    int v = parseInt(key, value);
                    ((AccountingRegister) target).setPeriodAdjustmentLength(v);
                    applied.add("periodAdjustmentLength=" + v); //$NON-NLS-1$
                    break;
                }
                case "chartofcalculationtypes": //$NON-NLS-1$
                {
                    ChartOfCalculationTypes chart =
                        (ChartOfCalculationTypes) refTarget(tx, key, refBmIds.get(normalizedKey));
                    ((CalculationRegister) target).setChartOfCalculationTypes(chart);
                    applied.add("chartOfCalculationTypes=" + value); //$NON-NLS-1$
                    break;
                }
                case "actionperiod": //$NON-NLS-1$
                {
                    boolean v = parseBool(key, value);
                    ((CalculationRegister) target).setActionPeriod(v);
                    applied.add("actionPeriod=" + v); //$NON-NLS-1$
                    break;
                }
                case "baseperiod": //$NON-NLS-1$
                {
                    boolean v = parseBool(key, value);
                    ((CalculationRegister) target).setBasePeriod(v);
                    applied.add("basePeriod=" + v); //$NON-NLS-1$
                    break;
                }
                case "schedule": //$NON-NLS-1$
                {
                    InformationRegister schedule = (InformationRegister) refTarget(tx, key, refBmIds.get(normalizedKey));
                    ((CalculationRegister) target).setSchedule(schedule);
                    applied.add("schedule=" + value); //$NON-NLS-1$
                    break;
                }
                default:
                    // Already rejected by validateProperties.
                    throw new RuntimeException("Unexpected property: " + key); //$NON-NLS-1$
            }
        }
    }

    /**
     * Re-fetches a reference target by its {@code bmId} inside the transaction. The
     * target existence/type was already validated up front by {@link #resolveRef};
     * this re-fetches it against the live transaction so the assignment uses the
     * transactional instance.
     */
    private static MdObject refTarget(IBmTransaction tx, String key, Long bmId)
    {
        if (bmId == null)
        {
            throw new RuntimeException("'" + key + "': reference target was not resolved"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MdObject obj = (MdObject) tx.getObjectById(bmId);
        if (obj == null)
        {
            throw new RuntimeException("'" + key + "': referenced object vanished from transaction (bmId=" //$NON-NLS-1$ //$NON-NLS-2$
                + bmId + ")"); //$NON-NLS-1$
        }
        return obj;
    }

    private static String irrelevant(String key, MdObject target)
    {
        return "Property '" + key + "' is not relevant for '" + target.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
            + "'. 'writeMode' applies to InformationRegister; 'periodicity' applies to " //$NON-NLS-1$
            + "InformationRegister and CalculationRegister; 'registerType' applies to " //$NON-NLS-1$
            + "AccumulationRegister; 'chartOfAccounts'/'correspondence'/'enableTotalsSplitting'/" //$NON-NLS-1$
            + "'periodAdjustmentLength' apply to AccountingRegister; 'chartOfCalculationTypes'/" //$NON-NLS-1$
            + "'actionPeriod'/'basePeriod'/'schedule' apply to CalculationRegister."; //$NON-NLS-1$
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

    private static boolean parseBool(String key, String value)
    {
        if (value != null)
        {
            String v = value.trim().toLowerCase();
            if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return true;
            }
            if ("false".equals(v) || "0".equals(v) || "no".equals(v)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return false;
            }
        }
        throw new IllegalArgumentException("'" + key + "' expects a boolean (true/false), got: " + value); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int parseInt(String key, String value)
    {
        if (value != null)
        {
            try
            {
                int v = Integer.parseInt(value.trim());
                if (v < 0)
                {
                    throw new IllegalArgumentException("'" + key + "' must not be negative, got: " + value); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return v;
            }
            catch (NumberFormatException ignored)
            {
                // fall through
            }
        }
        throw new IllegalArgumentException("'" + key + "' expects a non-negative integer, got: " + value); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static CalculationRegisterPeriodicity parseCalculationPeriodicity(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new IllegalArgumentException("'periodicity' value is required for a CalculationRegister. " //$NON-NLS-1$
                + "Expected one of 'Day', 'Month', 'Quarter', 'Year'."); //$NON-NLS-1$
        }
        String v = value.trim().toLowerCase();
        switch (v)
        {
            case "day": //$NON-NLS-1$
                return CalculationRegisterPeriodicity.DAY;
            case "month": //$NON-NLS-1$
                return CalculationRegisterPeriodicity.MONTH;
            case "quarter": //$NON-NLS-1$
                return CalculationRegisterPeriodicity.QUARTER;
            case "year": //$NON-NLS-1$
                return CalculationRegisterPeriodicity.YEAR;
            default:
                throw new IllegalArgumentException("Invalid CalculationRegister 'periodicity': " + value //$NON-NLS-1$
                    + ". Expected one of 'Day', 'Month', 'Quarter', 'Year'."); //$NON-NLS-1$
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
