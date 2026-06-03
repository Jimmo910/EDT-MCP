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
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.ButtonRepresentation;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.common.AllowedLength;
import com._1c.g5.v8.dt.metadata.mdclass.BasicRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodesSeries;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentJournal;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentNumberPeriodicity;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentNumberType;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.FunctionalOption;
import com._1c.g5.v8.dt.metadata.mdclass.FunctionalOptionsParameter;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPService;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Posting;
import com._1c.g5.v8.dt.metadata.mdclass.RealTimePosting;
import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.metadata.mdclass.Sequence;
import com._1c.g5.v8.dt.metadata.mdclass.SessionParameter;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import com._1c.g5.v8.dt.metadata.mdclass.WebService;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.AttributeTypeSpec;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.TypeDescriptionBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Tool to set properties on an existing top-level metadata object.
 * <p>
 * One tool covers several object kinds. A small set of <em>common</em>
 * properties (synonym, comment and the four presentation strings) is handled
 * before the type-specific dispatch and therefore works for any object that
 * carries the corresponding EMF feature. The remaining properties are
 * type-specific; only the ones relevant to the resolved object type are
 * accepted. An unknown or irrelevant property is a hard error (never silently
 * ignored), and all properties are validated and parsed <em>before</em> the BM
 * write transaction so the model is never left partially updated.
 * <p>
 * Common (cross-type) properties:
 * <ul>
 * <li>{@code synonym} (localized; honours the {@code language} parameter, default
 * the configuration language) - works for any {@link MdObject};</li>
 * <li>{@code comment} - works for any {@link MdObject};</li>
 * <li>{@code objectPresentation}, {@code listPresentation},
 * {@code extendedObjectPresentation}, {@code extendedListPresentation}
 * (localized) - work for any object that has the feature (Catalog, Document,
 * register, ...); these close the common validator note
 * {@code md-list-object-presentation}.</li>
 * </ul>
 * Type-specific properties: see the per-kind sections in the description.
 */
public class SetObjectPropertyTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "set_object_property"; //$NON-NLS-1$

    /** EMF feature names of the localized presentation maps. */
    private static final String[] PRESENTATION_FEATURES = {
        "objectPresentation", "listPresentation", //$NON-NLS-1$ //$NON-NLS-2$
        "extendedObjectPresentation", "extendedListPresentation"}; //$NON-NLS-1$ //$NON-NLS-2$

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
            + "Common properties for any object: synonym (localized, uses 'language' or the " //$NON-NLS-1$
            + "configuration default language), comment. " //$NON-NLS-1$
            + "Presentation strings (localized) for objects that have them (Catalog, Document, " //$NON-NLS-1$
            + "registers, ...): objectPresentation, listPresentation, extendedObjectPresentation, " //$NON-NLS-1$
            + "extendedListPresentation (these close the md-list-object-presentation note). " //$NON-NLS-1$
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
            + "EventSubscription: source (type, e.g. 'CatalogRef.Products' or composite), " //$NON-NLS-1$
            + "event (string), handler (string). " //$NON-NLS-1$
            + "ScheduledJob: methodName (string), use (boolean), predefined (boolean), key (string). " //$NON-NLS-1$
            + "FunctionalOption: location (storage object FQN), privilegedGetMode (boolean). " //$NON-NLS-1$
            + "FunctionalOptionsParameter: use (array of object FQNs). " //$NON-NLS-1$
            + "SessionParameter: type (TypeDescription, e.g. 'String(50)' or 'CatalogRef.X'). " //$NON-NLS-1$
            + "WebService: namespace (string), descriptorFileName (string). " //$NON-NLS-1$
            + "HTTPService: rootURL (string). " //$NON-NLS-1$
            + "CommonCommand: commandParameterType (type), " //$NON-NLS-1$
            + "representation (Auto/Text/Picture/PictureAndText). " //$NON-NLS-1$
            + "DocumentJournal: registeredDocuments (array of Document FQNs). " //$NON-NLS-1$
            + "Sequence: documents (array of Document FQNs). " //$NON-NLS-1$
            + "BusinessProcess: task (Task FQN). " //$NON-NLS-1$
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
                    + "Common: {\"synonym\": \"Sales order\", \"comment\": \"...\", " //$NON-NLS-1$
                    + "\"objectPresentation\": \"Order\", \"listPresentation\": \"Orders\"}. " //$NON-NLS-1$
                    + "Example for a Document: " //$NON-NLS-1$
                    + "{\"posting\": \"Allow\", \"numberLength\": 9, \"postInPrivilegedMode\": true, " //$NON-NLS-1$
                    + "\"registerRecords\": [\"AccumulationRegister.Sales\"]}. " //$NON-NLS-1$
                    + "Boolean values accept true/false; enum values are case-insensitive.", true) //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code (e.g. 'ru', 'en') for the localized properties " //$NON-NLS-1$
                    + "(synonym and the presentation strings). If omitted, the configuration " //$NON-NLS-1$
                    + "default language is used.") //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "When true (default), normalizes the Russian letter 'ё'->'е' / 'Ё'->'Е' in the " //$NON-NLS-1$
                    + "text properties (synonym, comment, objectPresentation, listPresentation, " //$NON-NLS-1$
                    + "extendedObjectPresentation, extendedListPresentation) so the result complies " //$NON-NLS-1$
                    + "with the mdo-ru-name-unallowed-letter standard; the result reports which " //$NON-NLS-1$
                    + "fields were changed. Set to false to keep the text exactly as given.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String propertiesRaw = JsonUtils.extractStringArgument(params, "properties"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$

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

        return executeInternal(projectName, objectFqn, properties, language, normalizeYo);
    }

    private String executeInternal(String projectName, String objectFqn,
        Map<String, JsonElement> properties, String language, boolean normalizeYo)
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

        // Resolve the platform version (needed for TypeDescription proxy resolution).
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (v8ProjectManager == null)
        {
            return ToolResult.error("IV8ProjectManager not available").toJson(); //$NON-NLS-1$
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            return ToolResult.error("Could not resolve V8 project for: " + projectName).toJson(); //$NON-NLS-1$
        }
        final Version version = v8Project.getVersion();
        final IBmEngine bmEngine = bmModel.getEngine();

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

        // Resolve the language for localized properties (only needed when one is set).
        final String localizedLanguage;
        if (containsLocalizedProperty(properties))
        {
            localizedLanguage = resolveLanguage(config, language);
            if (localizedLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the localized " //$NON-NLS-1$
                    + "properties (synonym/presentations) in this configuration. Specify " //$NON-NLS-1$
                    + "'language' explicitly (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            localizedLanguage = null;
        }

        // Build the list of validated mutations BEFORE the transaction. Any unknown
        // property, irrelevant property, or unparseable value fails the whole call.
        // The yo-normalizer is threaded through the plan context so the localized
        // text properties (synonym, comment, presentations) are normalized at the
        // single choke point where their string values are parsed.
        MdNameNormalizer.Report yoReport = new MdNameNormalizer.Report(normalizeYo);
        PlannedChanges plan;
        try
        {
            PlanContext planCtx = new PlanContext(config, version, bmEngine, localizedLanguage, yoReport);
            plan = planChanges(target, normalizedFqn, properties, planCtx);
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

        // Flush the edited object to its .mdo on disk and refresh stale validation
        // markers (e.g. md-list-object-presentation) so get_project_errors is fresh.
        String persistError = persistAndRevalidate(project, topObjectFqnOf(target));

        ToolResult result = ToolResult.success()
            .put("objectFqn", normalizedFqn) //$NON-NLS-1$
            .put("objectType", target.eClass().getName()) //$NON-NLS-1$
            .put("appliedProperties", plan.appliedNames); //$NON-NLS-1$
        if (persistError != null)
        {
            result.put("persistWarning", "Properties set in the in-memory model but the export to the " //$NON-NLS-1$ //$NON-NLS-2$
                + ".mdo file could not be forced: " + persistError); //$NON-NLS-1$
        }
        if (yoReport.hasChanges())
        {
            result.put("normalized", yoReport.normalizedFields()) //$NON-NLS-1$
                .put("note", yoReport.note()); //$NON-NLS-1$
        }
        return result
            .put("message", "Set " + plan.appliedNames.size() + " property(ies) on " //$NON-NLS-1$ //$NON-NLS-2$
                + normalizedFqn + ". Run get_project_errors to verify, and " //$NON-NLS-1$
                + "export_configuration_to_xml to confirm values persisted to .mdo.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Validates the supplied properties against the object kind and builds the
     * ordered list of mutations. Throws {@link PropertyException} on the first
     * problem; nothing is mutated here. Common (cross-type) properties are handled
     * first, then the type-specific dispatch.
     */
    private PlannedChanges planChanges(MdObject target, String normalizedFqn,
        Map<String, JsonElement> properties, PlanContext planCtx) throws PropertyException
    {
        PlannedChanges plan = new PlannedChanges();
        for (Map.Entry<String, JsonElement> entry : properties.entrySet())
        {
            String key = entry.getKey();
            JsonElement value = entry.getValue();

            if (isCommonProperty(key))
            {
                planCommonProperty(plan, target, normalizedFqn, key, value, planCtx);
            }
            else if (target instanceof Document)
            {
                planDocumentProperty(plan, key, value, planCtx.config);
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
                planSubsystemProperty(plan, key, value, planCtx.config);
            }
            else if (target instanceof EventSubscription)
            {
                planEventSubscriptionProperty(plan, key, value, planCtx);
            }
            else if (target instanceof ScheduledJob)
            {
                planScheduledJobProperty(plan, key, value);
            }
            else if (target instanceof FunctionalOption)
            {
                planFunctionalOptionProperty(plan, key, value, planCtx.config);
            }
            else if (target instanceof FunctionalOptionsParameter)
            {
                planFunctionalOptionsParameterProperty(plan, key, value, planCtx.config);
            }
            else if (target instanceof SessionParameter)
            {
                planSessionParameterProperty(plan, key, value, planCtx);
            }
            else if (target instanceof WebService)
            {
                planWebServiceProperty(plan, key, value);
            }
            else if (target instanceof HTTPService)
            {
                planHttpServiceProperty(plan, key, value);
            }
            else if (target instanceof CommonCommand)
            {
                planCommonCommandProperty(plan, key, value, planCtx);
            }
            else if (target instanceof DocumentJournal)
            {
                planDocumentJournalProperty(plan, key, value, planCtx.config);
            }
            else if (target instanceof Sequence)
            {
                planSequenceProperty(plan, key, value, planCtx.config);
            }
            else if (target instanceof BusinessProcess)
            {
                planBusinessProcessProperty(plan, key, value, planCtx.config);
            }
            else
            {
                throw new PropertyException("Object type '" + target.eClass().getName() //$NON-NLS-1$
                    + "' (" + normalizedFqn + ") is not supported by set_object_property. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Supported types: Document, Catalog, CommonModule, Subsystem, " //$NON-NLS-1$
                    + "EventSubscription, ScheduledJob, FunctionalOption, FunctionalOptionsParameter, " //$NON-NLS-1$
                    + "SessionParameter, WebService, HTTPService, CommonCommand, DocumentJournal, " //$NON-NLS-1$
                    + "Sequence, BusinessProcess. Common properties (synonym, comment, presentations) " //$NON-NLS-1$
                    + "work on any object that has the feature."); //$NON-NLS-1$
            }
            plan.appliedNames.add(key);
        }
        return plan;
    }

    // --- Common cross-type properties ------------------------------------

    private static boolean isCommonProperty(String key)
    {
        if ("synonym".equals(key) || "comment".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return true;
        }
        for (String f : PRESENTATION_FEATURES)
        {
            if (f.equals(key))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLocalizedProperty(Map<String, JsonElement> properties)
    {
        if (properties.containsKey("synonym")) //$NON-NLS-1$
        {
            return true;
        }
        for (String f : PRESENTATION_FEATURES)
        {
            if (properties.containsKey(f))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Plans a common (cross-type) property. {@code comment} maps to the typed
     * {@link MdObject#setComment(String)}. {@code synonym} and the four
     * presentation strings are localized EMF maps keyed by language code; the
     * feature must exist on the object's {@link org.eclipse.emf.ecore.EClass} or a
     * clear error is raised.
     */
    private void planCommonProperty(PlannedChanges plan, MdObject target, String normalizedFqn,
        String key, JsonElement value, PlanContext planCtx) throws PropertyException
    {
        if ("comment".equals(key)) //$NON-NLS-1$
        {
            final String v = planCtx.yoReport.apply(key, parseString(key, value));
            plan.add((tx, o) -> o.setComment(v));
            return;
        }

        // synonym + presentations: localized EMF maps keyed by language code.
        final String featureName = key; // EMF feature name equals the property key
        EStructuralFeature feature = target.eClass().getEStructuralFeature(featureName);
        if (feature == null)
        {
            throw new PropertyException("Property '" + key + "' is not available on '" //$NON-NLS-1$ //$NON-NLS-2$
                + target.eClass().getName() + "' (" + normalizedFqn + "). " //$NON-NLS-1$ //$NON-NLS-2$
                + "Presentation strings exist on data objects such as Catalog, Document and " //$NON-NLS-1$
                + "registers; 'synonym' exists on every metadata object."); //$NON-NLS-1$
        }
        final String v = planCtx.yoReport.apply(key, parseString(key, value));
        final String lang = planCtx.localizedLanguage;
        plan.add((tx, o) -> putLocalized(o, featureName, lang, v));
    }

    /**
     * Writes a localized string into one of the language-keyed maps ({@code synonym}
     * and the four presentation features). These features are EMF {@code EMap}s, i.e.
     * {@link org.eclipse.emf.common.util.EMap}, which is an {@code EList} of map
     * entries and is <em>not</em> a {@link java.util.Map}. They must therefore be
     * accessed through the EMF {@code EMap} API, not {@code java.util.Map}.
     */
    @SuppressWarnings("unchecked")
    private static void putLocalized(MdObject object, String featureName, String language, String value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null)
        {
            throw new RuntimeException("Feature vanished: " + featureName); //$NON-NLS-1$
        }
        Object map = object.eGet(feature);
        if (!(map instanceof EMap))
        {
            throw new RuntimeException("Feature '" + featureName + "' is not a localized map"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EMap<String, String>)map).put(language, value);
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

    // --- EventSubscription ------------------------------------------------

    private void planEventSubscriptionProperty(PlannedChanges plan, String key, JsonElement value,
        PlanContext planCtx) throws PropertyException
    {
        switch (key)
        {
        case "source": //$NON-NLS-1$
        {
            // The source is a TypeDescription (the set of objects subscribed to).
            AttributeTypeSpec spec = parseTypeSpec(key, value);
            plan.add((tx, o) -> ((EventSubscription)o).setSource(
                buildTypeDescription(spec, planCtx, o, tx)));
            break;
        }
        case "event": //$NON-NLS-1$
        {
            String v = parseString(key, value);
            plan.add((tx, o) -> ((EventSubscription)o).setEvent(v));
            break;
        }
        case "handler": //$NON-NLS-1$
        {
            String v = parseString(key, value);
            plan.add((tx, o) -> ((EventSubscription)o).setHandler(v));
            break;
        }
        default:
            throw unknownProperty(key, "EventSubscription", "source, event, handler"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- ScheduledJob -----------------------------------------------------

    private void planScheduledJobProperty(PlannedChanges plan, String key, JsonElement value)
        throws PropertyException
    {
        switch (key)
        {
        case "methodName": //$NON-NLS-1$
        {
            String v = parseString(key, value);
            plan.add((tx, o) -> ((ScheduledJob)o).setMethodName(v));
            break;
        }
        case "use": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((ScheduledJob)o).setUse(v));
            break;
        }
        case "predefined": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((ScheduledJob)o).setPredefined(v));
            break;
        }
        case "key": //$NON-NLS-1$
        {
            String v = parseString(key, value);
            plan.add((tx, o) -> ((ScheduledJob)o).setKey(v));
            break;
        }
        default:
            throw unknownProperty(key, "ScheduledJob", //$NON-NLS-1$
                "methodName, use, predefined, key (schedule must be edited in the EDT editor)"); //$NON-NLS-1$
        }
    }

    // --- FunctionalOption -------------------------------------------------

    private void planFunctionalOptionProperty(PlannedChanges plan, String key, JsonElement value,
        Configuration config) throws PropertyException
    {
        switch (key)
        {
        case "location": //$NON-NLS-1$
        {
            // The storage where the option value is kept: a Constant or a register
            // resource attribute, referenced by FQN.
            String fqn = parseString(key, value);
            final long bmId = resolveRefBmId(key, fqn, config);
            plan.add((tx, o) -> {
                MdObject storage = (MdObject)tx.getObjectById(bmId);
                if (storage == null)
                {
                    throw new RuntimeException("Location object vanished from transaction: " + fqn); //$NON-NLS-1$
                }
                ((FunctionalOption)o).setLocation(storage);
            });
            break;
        }
        case "privilegedGetMode": //$NON-NLS-1$
        {
            boolean v = parseBoolean(key, value);
            plan.add((tx, o) -> ((FunctionalOption)o).setPrivilegedGetMode(v));
            break;
        }
        default:
            throw unknownProperty(key, "FunctionalOption", "location, privilegedGetMode"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- FunctionalOptionsParameter --------------------------------------

    private void planFunctionalOptionsParameterProperty(PlannedChanges plan, String key, JsonElement value,
        Configuration config) throws PropertyException
    {
        switch (key)
        {
        case "use": //$NON-NLS-1$
        {
            // 'use' is the set of objects parametrized by the option (FQN array).
            List<Long> bmIds = resolveObjectRefs(key, value, config);
            plan.add((tx, o) -> {
                EList<MdObject> use = ((FunctionalOptionsParameter)o).getUse();
                for (Long bmId : bmIds)
                {
                    MdObject member = (MdObject)tx.getObjectById(bmId);
                    if (member == null)
                    {
                        throw new RuntimeException("Object vanished from transaction (bmId=" + bmId + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (!use.contains(member))
                    {
                        use.add(member);
                    }
                }
            });
            break;
        }
        default:
            throw unknownProperty(key, "FunctionalOptionsParameter", "use"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- SessionParameter -------------------------------------------------

    private void planSessionParameterProperty(PlannedChanges plan, String key, JsonElement value,
        PlanContext planCtx) throws PropertyException
    {
        switch (key)
        {
        case "type": //$NON-NLS-1$
        {
            AttributeTypeSpec spec = parseTypeSpec(key, value);
            plan.add((tx, o) -> ((SessionParameter)o).setType(
                buildTypeDescription(spec, planCtx, o, tx)));
            break;
        }
        default:
            throw unknownProperty(key, "SessionParameter", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- WebService -------------------------------------------------------

    private void planWebServiceProperty(PlannedChanges plan, String key, JsonElement value)
        throws PropertyException
    {
        switch (key)
        {
        case "namespace": //$NON-NLS-1$
        {
            String v = parseString(key, value);
            plan.add((tx, o) -> ((WebService)o).setNamespace(v));
            break;
        }
        case "descriptorFileName": //$NON-NLS-1$
        {
            String v = parseString(key, value);
            plan.add((tx, o) -> ((WebService)o).setDescriptorFileName(v));
            break;
        }
        default:
            throw unknownProperty(key, "WebService", "namespace, descriptorFileName"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- HTTPService ------------------------------------------------------

    private void planHttpServiceProperty(PlannedChanges plan, String key, JsonElement value)
        throws PropertyException
    {
        switch (key)
        {
        case "rootURL": //$NON-NLS-1$
        {
            String v = parseString(key, value);
            plan.add((tx, o) -> ((HTTPService)o).setRootURL(v));
            break;
        }
        default:
            throw unknownProperty(key, "HTTPService", "rootURL"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- CommonCommand ----------------------------------------------------

    private void planCommonCommandProperty(PlannedChanges plan, String key, JsonElement value,
        PlanContext planCtx) throws PropertyException
    {
        switch (key)
        {
        case "commandParameterType": //$NON-NLS-1$
        {
            AttributeTypeSpec spec = parseTypeSpec(key, value);
            plan.add((tx, o) -> ((CommonCommand)o).setCommandParameterType(
                buildTypeDescription(spec, planCtx, o, tx)));
            break;
        }
        case "representation": //$NON-NLS-1$
        {
            ButtonRepresentation v =
                parseEnum(ButtonRepresentation.class, ButtonRepresentation.values(), key, value);
            plan.add((tx, o) -> ((CommonCommand)o).setRepresentation(v));
            break;
        }
        default:
            throw unknownProperty(key, "CommonCommand", //$NON-NLS-1$
                "commandParameterType, representation (group must be set in the EDT command " //$NON-NLS-1$
                    + "interface editor)"); //$NON-NLS-1$
        }
    }

    // --- DocumentJournal --------------------------------------------------

    private void planDocumentJournalProperty(PlannedChanges plan, String key, JsonElement value,
        Configuration config) throws PropertyException
    {
        switch (key)
        {
        case "registeredDocuments": //$NON-NLS-1$
        {
            List<Long> bmIds = resolveTypedRefs(key, value, config, Document.class, "Document"); //$NON-NLS-1$
            plan.add((tx, o) -> {
                EList<Document> docs = ((DocumentJournal)o).getRegisteredDocuments();
                for (Long bmId : bmIds)
                {
                    Document doc = (Document)tx.getObjectById(bmId);
                    if (doc == null)
                    {
                        throw new RuntimeException("Document vanished from transaction (bmId=" + bmId + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (!docs.contains(doc))
                    {
                        docs.add(doc);
                    }
                }
            });
            break;
        }
        default:
            throw unknownProperty(key, "DocumentJournal", "registeredDocuments"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- Sequence ---------------------------------------------------------

    private void planSequenceProperty(PlannedChanges plan, String key, JsonElement value,
        Configuration config) throws PropertyException
    {
        switch (key)
        {
        case "documents": //$NON-NLS-1$
        {
            List<Long> bmIds = resolveTypedRefs(key, value, config, Document.class, "Document"); //$NON-NLS-1$
            plan.add((tx, o) -> {
                EList<Document> docs = ((Sequence)o).getDocuments();
                for (Long bmId : bmIds)
                {
                    Document doc = (Document)tx.getObjectById(bmId);
                    if (doc == null)
                    {
                        throw new RuntimeException("Document vanished from transaction (bmId=" + bmId + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (!docs.contains(doc))
                    {
                        docs.add(doc);
                    }
                }
            });
            break;
        }
        case "dimensions": //$NON-NLS-1$
            throw new PropertyException("Sequence 'dimensions' are child objects with their own type; " //$NON-NLS-1$
                + "they cannot be set by reference here. Add a dimension via add_metadata_attribute " //$NON-NLS-1$
                + "is not yet supported for Sequence - edit dimensions in the EDT editor."); //$NON-NLS-1$
        default:
            throw unknownProperty(key, "Sequence", "documents"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // --- BusinessProcess --------------------------------------------------

    private void planBusinessProcessProperty(PlannedChanges plan, String key, JsonElement value,
        Configuration config) throws PropertyException
    {
        switch (key)
        {
        case "task": //$NON-NLS-1$
        {
            String fqn = parseString(key, value);
            final long bmId = resolveTypedRefBmId(key, fqn, config, Task.class, "Task"); //$NON-NLS-1$
            plan.add((tx, o) -> {
                Task task = (Task)tx.getObjectById(bmId);
                if (task == null)
                {
                    throw new RuntimeException("Task vanished from transaction: " + fqn); //$NON-NLS-1$
                }
                ((BusinessProcess)o).setTask(task);
            });
            break;
        }
        default:
            throw unknownProperty(key, "BusinessProcess", "task (and the common synonym/comment/" //$NON-NLS-1$ //$NON-NLS-2$
                + "presentation properties)"); //$NON-NLS-1$
        }
    }

    // --- TypeDescription helper ------------------------------------------

    private static AttributeTypeSpec parseTypeSpec(String key, JsonElement value) throws PropertyException
    {
        String raw = parseString(key, value);
        try
        {
            return AttributeTypeSpec.parse(raw);
        }
        catch (IllegalArgumentException e)
        {
            throw new PropertyException("Property '" + key + "' has an invalid type: " + e.getMessage() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Examples: 'String(50)', 'Number(15,2)', 'CatalogRef.Products', " //$NON-NLS-1$
                + "'String(10), CatalogRef.Products'."); //$NON-NLS-1$
        }
    }

    private static TypeDescription buildTypeDescription(AttributeTypeSpec spec, PlanContext planCtx,
        MdObject contextObject, IBmTransaction tx)
    {
        String contextFqn = TypeDescriptionBuilder.topObjectFqnOf(contextObject);
        return TypeDescriptionBuilder.build(spec, planCtx.version, contextObject, planCtx.bmEngine, contextFqn, tx);
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
        return resolveObjectRefs(key, value, config);
    }

    /** Resolves an array of FQNs to bmIds, requiring each to be a BM object. */
    private List<Long> resolveObjectRefs(String key, JsonElement value, Configuration config)
        throws PropertyException
    {
        List<String> fqns = parseFqnArray(key, value);
        List<Long> bmIds = new ArrayList<>(fqns.size());
        for (String fqn : fqns)
        {
            MdObject obj = resolveObject(config, fqn);
            if (obj == null)
            {
                throw new PropertyException(key + ": object not found: " + fqn //$NON-NLS-1$
                    + ". Use a metadata FQN such as 'Catalog.Products' or 'Document.SalesOrder'."); //$NON-NLS-1$
            }
            if (!(obj instanceof IBmObject))
            {
                throw new PropertyException(key + ": '" + fqn + "' is not a BM object."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            bmIds.add(((IBmObject)obj).bmGetId());
        }
        return bmIds;
    }

    /** Resolves an array of FQNs, requiring each to be of the given type. */
    private List<Long> resolveTypedRefs(String key, JsonElement value, Configuration config,
        Class<? extends MdObject> requiredType, String requiredTypeLabel) throws PropertyException
    {
        List<String> fqns = parseFqnArray(key, value);
        List<Long> bmIds = new ArrayList<>(fqns.size());
        for (String fqn : fqns)
        {
            bmIds.add(resolveTypedRefBmId(key, fqn, config, requiredType, requiredTypeLabel));
        }
        return bmIds;
    }

    /** Resolves a single FQN to a bmId (any BM object). */
    private long resolveRefBmId(String key, String fqn, Configuration config) throws PropertyException
    {
        MdObject obj = resolveObject(config, fqn);
        if (obj == null)
        {
            throw new PropertyException(key + ": object not found: " + fqn //$NON-NLS-1$
                + ". Check the FQN and use get_metadata_objects to list available objects."); //$NON-NLS-1$
        }
        if (!(obj instanceof IBmObject))
        {
            throw new PropertyException(key + ": '" + fqn + "' is not a BM object."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ((IBmObject)obj).bmGetId();
    }

    /** Resolves a single FQN to a bmId, requiring the given type. */
    private long resolveTypedRefBmId(String key, String fqn, Configuration config,
        Class<? extends MdObject> requiredType, String requiredTypeLabel) throws PropertyException
    {
        MdObject obj = resolveObject(config, fqn);
        if (obj == null)
        {
            throw new PropertyException(key + ": " + requiredTypeLabel + " not found: " + fqn //$NON-NLS-1$ //$NON-NLS-2$
                + ". Use a " + requiredTypeLabel + " FQN such as '" + requiredTypeLabel + ".SalesOrder'."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (!requiredType.isInstance(obj))
        {
            throw new PropertyException(key + ": '" + fqn + "' is not a " + requiredTypeLabel //$NON-NLS-1$ //$NON-NLS-2$
                + " (it is a " + obj.eClass().getName() + ")."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!(obj instanceof IBmObject))
        {
            throw new PropertyException(key + ": '" + fqn + "' is not a BM object."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ((IBmObject)obj).bmGetId();
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

    // --- Language resolution ---------------------------------------------

    /**
     * Resolves the language code for localized properties. Mirrors the logic in
     * {@code CreateMetadataObjectTool}: an explicit {@code language} wins,
     * otherwise the configuration default language code, otherwise the first
     * configured language code.
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

    private static String parseString(String key, JsonElement value) throws PropertyException
    {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
        {
            return value.getAsString();
        }
        throw new PropertyException("Property '" + key + "' expects a string, got: " //$NON-NLS-1$ //$NON-NLS-2$
            + describe(value));
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
            + "' for " + typeName + ". Supported properties: " + supported //$NON-NLS-1$ //$NON-NLS-2$
            + " (plus the common synonym, comment and presentation properties)."); //$NON-NLS-1$
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

    /** Read-only context threaded through the planning phase. */
    private static final class PlanContext
    {
        final Configuration config;
        final Version version;
        final IBmEngine bmEngine;
        final String localizedLanguage;
        final MdNameNormalizer.Report yoReport;

        PlanContext(Configuration config, Version version, IBmEngine bmEngine, String localizedLanguage,
            MdNameNormalizer.Report yoReport)
        {
            this.config = config;
            this.version = version;
            this.bmEngine = bmEngine;
            this.localizedLanguage = localizedLanguage;
            this.yoReport = yoReport;
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
