/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Enum;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to append an {@link EnumValue} to an existing {@link Enum}.
 * <p>
 * The value is created via {@link MdClassFactory}, given a fresh UUID (the same
 * pattern {@code add_metadata_attribute} uses for new child objects) and added
 * to the enum's {@code enumValues} containment list inside a single BM write
 * transaction. The enum top object is re-fetched by its {@code bmId} inside the
 * transaction. After the transaction commits, the enum is force-exported to its
 * {@code .mdo} file (the BM commit only updates the in-memory model; the
 * model-to-file serialization runs asynchronously otherwise), so the new value is
 * persisted and queryable immediately.
 * <p>
 * An optional {@code synonym} (display name) is stored under the configuration
 * default language unless {@code language} is given.
 */
public class AddEnumValueTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "add_enum_value"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Append a value to an existing Enum. Creates the EnumValue with a fresh UUID and " //$NON-NLS-1$
            + "adds it to the enum. Optionally sets a localized synonym (display name) under " //$NON-NLS-1$
            + "'language' or the configuration default language. " //$NON-NLS-1$
            + "Usage: {projectName: 'MyProject', enumFqn: 'Enum.OrderStatus', name: 'Shipped', " //$NON-NLS-1$
            + "synonym: 'Shipped'}. Russian enum names are also supported in the FQN."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("enumFqn", //$NON-NLS-1$
                "FQN of the enum (required), e.g. 'Enum.OrderStatus'. Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Name for the new enum value (required). Must be a valid 1C identifier.", true) //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional synonym (display name) for the value.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Optional language code for the synonym (e.g. 'ru', 'en'). " //$NON-NLS-1$
                    + "If omitted, the configuration default language is used.") //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "When true (default), normalizes the Russian letter 'ё'->'е' / 'Ё'->'Е' in the " //$NON-NLS-1$
                    + "name and synonym so the result complies with the mdo-ru-name-unallowed-letter " //$NON-NLS-1$
                    + "standard; the result reports which fields were changed. " //$NON-NLS-1$
                    + "Set to false to keep the text exactly as given.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String enumFqn = JsonUtils.extractStringArgument(params, "enumFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        // Normalize user text (ё->е / Ё->Е) at the input, before identifier
        // validation, so the value is stored already standard-compliant.
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        MdNameNormalizer.Report yoReport = new MdNameNormalizer.Report(normalizeYo);
        name = yoReport.apply("name", name); //$NON-NLS-1$
        synonym = yoReport.apply("synonym", synonym); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " //$NON-NLS-1$
                + "Usage: {projectName: 'MyProject', enumFqn: 'Enum.OrderStatus', name: 'Shipped'}").toJson(); //$NON-NLS-1$
        }
        if (enumFqn == null || enumFqn.isEmpty())
        {
            return ToolResult.error("enumFqn is required. Example: 'Enum.OrderStatus'.").toJson(); //$NON-NLS-1$
        }
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("name is required. " //$NON-NLS-1$
                + "Usage: {enumFqn: 'Enum.OrderStatus', name: 'Shipped'}").toJson(); //$NON-NLS-1$
        }
        if (!isValidIdentifier(name))
        {
            return ToolResult.error("Invalid value name '" + name + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                + "A name must start with a letter or underscore and contain only letters, " //$NON-NLS-1$
                + "digits and underscores.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, enumFqn, name, synonym, language, yoReport);
    }

    private String executeInternal(String projectName, String enumFqn, final String name,
        final String synonym, String language, MdNameNormalizer.Report yoReport)
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

        String normalizedFqn = MetadataTypeUtils.normalizeFqn(enumFqn);
        String[] parts = normalizedFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Invalid FQN: " + enumFqn + ". Expected 'Enum.Name'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MdObject target = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (target == null)
        {
            return ToolResult.error("Enum not found: " + normalizedFqn //$NON-NLS-1$
                + ". Check the FQN and use get_metadata_objects to list available enums.").toJson(); //$NON-NLS-1$
        }
        if (!(target instanceof Enum))
        {
            return ToolResult.error("Object '" + normalizedFqn + "' is not an Enum (it is a " //$NON-NLS-1$ //$NON-NLS-2$
                + target.eClass().getName() + "). add_enum_value applies to Enum only.").toJson(); //$NON-NLS-1$
        }
        if (!(target instanceof IBmObject))
        {
            return ToolResult.error("Enum is not a BM object: " + normalizedFqn).toJson(); //$NON-NLS-1$
        }

        // Duplicate check before the transaction.
        for (EnumValue existing : ((Enum)target).getEnumValues())
        {
            if (name.equalsIgnoreCase(existing.getName()))
            {
                return ToolResult.error("Enum value already exists: " + normalizedFqn + "." + name).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        // Resolve synonym language only when a synonym is supplied.
        final String synonymLanguage;
        if (synonym != null && !synonym.isEmpty())
        {
            synonymLanguage = resolveLanguage(config, language);
            if (synonymLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the synonym in this " //$NON-NLS-1$
                    + "configuration. Specify 'language' explicitly (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            synonymLanguage = null;
        }

        final long enumBmId = ((IBmObject)target).bmGetId();
        // FQN under which the enum top object is stored in the BM engine; used to
        // force the model-to-file export after the transaction commits.
        final String enumTopObjectFqn = ((IBmObject)target).bmGetFqn();
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("AddEnumValue") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Enum theEnum = (Enum)tx.getObjectById(enumBmId);
                    if (theEnum == null)
                    {
                        throw new RuntimeException("Enum not found in transaction: " + normalizedFqn); //$NON-NLS-1$
                    }

                    EnumValue value = MdClassFactory.eINSTANCE.createEnumValue();
                    value.setName(name);
                    value.setUuid(UUID.randomUUID());
                    if (synonym != null && !synonym.isEmpty())
                    {
                        value.getSynonym().put(synonymLanguage, synonym);
                    }

                    EList<EnumValue> values = theEnum.getEnumValues();
                    values.add(value);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding enum value", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add enum value: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // The BM transaction commits the new value into the in-memory model, but the
        // model-to-file serialization runs asynchronously, so the enum's .mdo file is
        // not updated until that background export completes. Drive the export and the
        // stale-marker refresh synchronously through the shared metadata-write helper
        // (the same path used by create_metadata_object / set_object_property), so the
        // value is persisted on disk and queryable immediately.
        String persistError = persistAndRevalidate(project, enumTopObjectFqn);

        ToolResult result = ToolResult.success()
            .put("enumFqn", normalizedFqn) //$NON-NLS-1$
            .put("name", name); //$NON-NLS-1$
        if (persistError != null)
        {
            result.put("warning", "Value added to the in-memory model but the export to the .mdo " //$NON-NLS-1$ //$NON-NLS-2$
                + "file could not be forced: " + persistError); //$NON-NLS-1$
        }
        if (yoReport.hasChanges())
        {
            result.put("normalized", yoReport.normalizedFields()) //$NON-NLS-1$
                .put("note", yoReport.note()); //$NON-NLS-1$
        }
        return result
            .put("message", "Enum value '" + name + "' added to " + normalizedFqn //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ". Run get_project_errors to verify, or get_metadata_details to confirm.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Resolves the language code for the synonym. An explicit {@code language}
     * wins, otherwise the configuration default language code, otherwise the
     * first configured language code.
     */
    private static String resolveLanguage(Configuration config, String language)
    {
        if (language != null && !language.isEmpty())
        {
            return language;
        }
        Language defaultLanguage = config.getDefaultLanguage();
        if (defaultLanguage != null
            && defaultLanguage.getLanguageCode() != null
            && !defaultLanguage.getLanguageCode().isEmpty())
        {
            return defaultLanguage.getLanguageCode();
        }
        for (Language lang : config.getLanguages())
        {
            if (lang != null && lang.getLanguageCode() != null && !lang.getLanguageCode().isEmpty())
            {
                return lang.getLanguageCode();
            }
        }
        return null;
    }

    private static boolean isValidIdentifier(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_')
            {
                return false;
            }
        }
        return true;
    }
}
