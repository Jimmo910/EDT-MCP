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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.naming.ISymbolicNameService;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.common.FillChecking;
import com._1c.g5.v8.dt.metadata.mdclass.AccountingRegister;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DbObjectAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.Indexing;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.RegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.AttributeTypeSpec;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to add a new attribute to a metadata object.
 * <p>
 * Creates the attribute via a BM write transaction. By default (no {@code type}
 * parameter) the attribute keeps the EDT factory default type, preserving the
 * original behavior. When a {@code type} is given, this tool builds the
 * {@link TypeDescription} (including composite types and String/Number/Date
 * qualifiers) and assigns it through {@link BasicFeature#setType}. The optional
 * {@code indexing}, {@code fillChecking} and {@code multiLine} flags configure
 * the corresponding attribute properties.
 * <p>
 * Type items (platform types such as {@code String}/{@code Number}/{@code Date}/
 * {@code Boolean} and reference types such as {@code CatalogRef.Products}) are
 * resolved into {@link TypeItem} proxies through
 * {@link ISymbolicNameService#convertSymbolicNameToUri}, scoped to the project's
 * own {@link IBmEngine}. This is the same project-scoped mechanism EDT uses when
 * it imports a configuration from XML: platform type names resolve against the
 * platform type registry, while reference type names (e.g. {@code CatalogRef.X})
 * resolve against the metadata objects of the <em>current project</em>. The
 * proxies are then resolved lazily by the BM resource set.
 */
public class AddMetadataAttributeTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "add_metadata_attribute"; //$NON-NLS-1$

    /**
     * The kind of register child to create. {@link #ATTRIBUTE} is the default and
     * the only kind valid for non-register objects. {@link #DIMENSION} and
     * {@link #RESOURCE} apply only to {@link InformationRegister} and
     * {@link AccumulationRegister}.
     */
    private enum Kind
    {
        ATTRIBUTE, DIMENSION, RESOURCE
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add a new attribute to a metadata object. " + //$NON-NLS-1$
               "Supports: Catalog, Document, ExchangePlan, ChartOfCharacteristicTypes, " + //$NON-NLS-1$
               "ChartOfAccounts, ChartOfCalculationTypes, BusinessProcess, Task, " + //$NON-NLS-1$
               "DataProcessor, Report, InformationRegister, AccumulationRegister, AccountingRegister. " + //$NON-NLS-1$
               "Optionally sets the attribute type, qualifiers and flags. " + //$NON-NLS-1$
               "The 'type' parameter accepts platform types (String, Number, Boolean, Date) " + //$NON-NLS-1$
               "with qualifiers, and reference types (CatalogRef.<Name>, DocumentRef.<Name>, " + //$NON-NLS-1$
               "EnumRef.<Name>, ...). A comma-separated list produces a composite type. " + //$NON-NLS-1$
               "Examples: 'String(50)', 'String(50,fixed)', 'Number(15,2)', " + //$NON-NLS-1$
               "'Number(15,2,nonnegative)', 'Date(DateTime)', 'CatalogRef.Products', " + //$NON-NLS-1$
               "'String(10), CatalogRef.Products'. " + //$NON-NLS-1$
               "When 'type' is omitted the attribute keeps the default type (backward compatible). " + //$NON-NLS-1$
               "For InformationRegister and AccumulationRegister the 'kind' parameter selects " + //$NON-NLS-1$
               "what to create: 'Attribute' (default), 'Dimension' or 'Resource'. " + //$NON-NLS-1$
               "Dimensions accept the 'master', 'mainFilter' (InformationRegister only) and " + //$NON-NLS-1$
               "'indexing' flags. " + //$NON-NLS-1$
               "Russian type and object names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("parentFqn", //$NON-NLS-1$
                "FQN of the parent object " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products', 'Document.SalesOrder'). " + //$NON-NLS-1$
                "Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "Name for the new attribute/dimension/resource (required)", true) //$NON-NLS-1$
            .stringProperty("kind", //$NON-NLS-1$
                "Optional kind of register child to create: 'Attribute' (default), " + //$NON-NLS-1$
                "'Dimension' or 'Resource'. 'Dimension'/'Resource' are valid only for " + //$NON-NLS-1$
                "InformationRegister and AccumulationRegister.", //$NON-NLS-1$
                false)
            .stringProperty("type", //$NON-NLS-1$
                "Optional attribute type. A single type or a comma-separated " + //$NON-NLS-1$
                "list for a composite type. Platform types support qualifiers: " + //$NON-NLS-1$
                "'String', 'String(50)', 'String(50,fixed)', 'Number(15,2)', " + //$NON-NLS-1$
                "'Number(15,2,nonnegative)', 'Date(Date|Time|DateTime)', 'Boolean'. " + //$NON-NLS-1$
                "Reference types: 'CatalogRef.<Name>', 'DocumentRef.<Name>', " + //$NON-NLS-1$
                "'EnumRef.<Name>', etc. Composite example: 'String(10), CatalogRef.Products'.", //$NON-NLS-1$
                false)
            .stringProperty("indexing", //$NON-NLS-1$
                "Optional indexing for DB-object attributes: " + //$NON-NLS-1$
                "'DontIndex', 'Index' or 'IndexWithAdditionalOrder'. " + //$NON-NLS-1$
                "Ignored for register attributes (they have no indexing).", //$NON-NLS-1$
                false)
            .stringProperty("fillChecking", //$NON-NLS-1$
                "Optional fill checking: 'DontCheck' or 'ShowError'.", //$NON-NLS-1$
                false)
            .booleanProperty("multiLine", //$NON-NLS-1$
                "Optional multi-line text field flag (for String attributes).", //$NON-NLS-1$
                false)
            .booleanProperty("master", //$NON-NLS-1$
                "Optional 'master' (leading) flag for a register dimension " + //$NON-NLS-1$
                "(kind=Dimension). Supported only for InformationRegister dimensions.", //$NON-NLS-1$
                false)
            .booleanProperty("mainFilter", //$NON-NLS-1$
                "Optional 'main filter' flag for a register dimension " + //$NON-NLS-1$
                "(kind=Dimension). Supported only for InformationRegister dimensions.", //$NON-NLS-1$
                false)
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String parentFqn = JsonUtils.extractStringArgument(params, "parentFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " + //$NON-NLS-1$
                "Usage: {projectName: 'MyProject', parentFqn: 'Catalog.Products', attributeName: 'Weight'}").toJson(); //$NON-NLS-1$
        }
        if (parentFqn == null || parentFqn.isEmpty())
        {
            return ToolResult.error("parentFqn is required. " + //$NON-NLS-1$
                "Examples: 'Catalog.Products', 'Document.SalesOrder'. " + //$NON-NLS-1$
                "Usage: {parentFqn: 'Catalog.Products', attributeName: 'Weight'}").toJson(); //$NON-NLS-1$
        }
        if (attributeName == null || attributeName.isEmpty())
        {
            return ToolResult.error("attributeName is required. " + //$NON-NLS-1$
                "Usage: {parentFqn: 'Catalog.Products', attributeName: 'Weight'}").toJson(); //$NON-NLS-1$
        }

        // Optional type/qualifiers/flags. Parse and validate BEFORE any transaction.
        String typeSpecRaw = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        AttributeTypeSpec typeSpec = null;
        if (typeSpecRaw != null && !typeSpecRaw.trim().isEmpty())
        {
            try
            {
                typeSpec = AttributeTypeSpec.parse(typeSpecRaw);
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error("Invalid 'type': " + e.getMessage() + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                    "Examples: 'String(50)', 'Number(15,2)', 'Date(DateTime)', 'CatalogRef.Products', " + //$NON-NLS-1$
                    "'String(10), CatalogRef.Products'.").toJson(); //$NON-NLS-1$
            }
        }

        Indexing indexing;
        try
        {
            indexing = parseIndexing(JsonUtils.extractStringArgument(params, "indexing")); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        FillChecking fillChecking;
        try
        {
            fillChecking = parseFillChecking(JsonUtils.extractStringArgument(params, "fillChecking")); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        boolean multiLineSet = params.containsKey("multiLine"); //$NON-NLS-1$
        boolean multiLine = multiLineSet && JsonUtils.extractBooleanArgument(params, "multiLine", false); //$NON-NLS-1$

        Kind kind;
        try
        {
            kind = parseKind(JsonUtils.extractStringArgument(params, "kind")); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        boolean masterSet = params.containsKey("master"); //$NON-NLS-1$
        boolean master = masterSet && JsonUtils.extractBooleanArgument(params, "master", false); //$NON-NLS-1$
        boolean mainFilterSet = params.containsKey("mainFilter"); //$NON-NLS-1$
        boolean mainFilter = mainFilterSet && JsonUtils.extractBooleanArgument(params, "mainFilter", false); //$NON-NLS-1$

        // Dimension-only flags must not be combined with a non-dimension kind.
        if (kind != Kind.DIMENSION && (masterSet || mainFilterSet))
        {
            return ToolResult.error("'master' and 'mainFilter' are only valid when kind=Dimension.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, parentFqn, attributeName, kind,
            typeSpec, indexing, fillChecking, multiLineSet, multiLine,
            masterSet, master, mainFilterSet, mainFilter);
    }

    private String executeInternal(String projectName, String parentFqn, String attributeName, final Kind kind,
        final AttributeTypeSpec typeSpec, final Indexing indexing, final FillChecking fillChecking,
        final boolean multiLineSet, final boolean multiLine,
        final boolean masterSet, final boolean master,
        final boolean mainFilterSet, final boolean mainFilter)
    {
        // Get project and configuration
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        // Get BM model
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

        // Resolve the platform version (needed for type proxy resolution).
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

        // Normalize and find the parent object
        parentFqn = MetadataTypeUtils.normalizeFqn(parentFqn);
        String[] parts = parentFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Invalid FQN: " + parentFqn).toJson(); //$NON-NLS-1$
        }
        MdObject parentObject = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (parentObject == null)
        {
            return ToolResult.error("Parent object not found: " + parentFqn + ". " + //$NON-NLS-1$
                "Check the FQN format: 'Type.Name' (e.g. 'Catalog.Products', 'Document.SalesOrder'). " + //$NON-NLS-1$
                "Use get_metadata_objects tool to list available objects.").toJson(); //$NON-NLS-1$
        }

        // Check parent type supports attributes
        if (!supportsAttributes(parentObject))
        {
            return ToolResult.error("Object type '" + parentObject.eClass().getName() + //$NON-NLS-1$
                "' does not support attributes. Supported types: " + //$NON-NLS-1$
                "Catalog, Document, ExchangePlan, ChartOfCharacteristicTypes, ChartOfAccounts, " + //$NON-NLS-1$
                "ChartOfCalculationTypes, BusinessProcess, Task, DataProcessor, Report, " + //$NON-NLS-1$
                "InformationRegister, AccumulationRegister, AccountingRegister.").toJson(); //$NON-NLS-1$
        }

        // Dimension/Resource are only meaningful for registers that expose those
        // collections (InformationRegister and AccumulationRegister).
        boolean isRegisterWithDimensions =
            parentObject instanceof InformationRegister || parentObject instanceof AccumulationRegister;
        if (kind != Kind.ATTRIBUTE && !isRegisterWithDimensions)
        {
            return ToolResult.error("kind='" + describeKind(kind) + "' is only valid for " + //$NON-NLS-1$ //$NON-NLS-2$
                "InformationRegister and AccumulationRegister, not for '" //$NON-NLS-1$
                + parentObject.eClass().getName() + "'. Use kind='Attribute' (the default) instead.").toJson(); //$NON-NLS-1$
        }
        // 'master'/'mainFilter' exist only on InformationRegister dimensions.
        if ((masterSet || mainFilterSet) && !(parentObject instanceof InformationRegister))
        {
            return ToolResult.error("'master' and 'mainFilter' are only supported for " + //$NON-NLS-1$
                "InformationRegister dimensions, not for '" + parentObject.eClass().getName() + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Get bmId of parent for BM task
        if (!(parentObject instanceof IBmObject))
        {
            return ToolResult.error("Parent object is not a BM object").toJson(); //$NON-NLS-1$
        }
        long parentBmId = ((IBmObject) parentObject).bmGetId();

        // Execute write task. The project BM engine is needed to resolve
        // reference type names (e.g. CatalogRef.X) against this project's
        // metadata objects.
        final IBmEngine bmEngine = bmModel.getEngine();
        final String normalizedParentFqn = parentFqn;
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("AddMetadataAttribute") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject parent = (MdObject) tx.getObjectById(parentBmId);
                    if (parent == null)
                    {
                        throw new RuntimeException("Parent object not found in transaction"); //$NON-NLS-1$
                    }

                    // Check if a child of this kind with this name already exists.
                    if (hasChild(parent, kind, attributeName))
                    {
                        throw new RuntimeException(
                            describeKind(kind) + " already exists: " + attributeName); //$NON-NLS-1$
                    }

                    // Create and add the child (attribute, dimension or resource).
                    MdObject newChild = createChild(parent, kind);
                    if (newChild == null)
                    {
                        throw new RuntimeException("Cannot create " + describeKind(kind) //$NON-NLS-1$
                            + " for: " + parent.eClass().getName()); //$NON-NLS-1$
                    }
                    newChild.setName(attributeName);
                    newChild.setUuid(UUID.randomUUID());

                    // FQN of the parent top object, used as the resolution context
                    // for reference type names.
                    String topObjectFqn = ((IBmObject) parent).bmGetTopObject().bmGetFqn();

                    // Apply optional type, qualifiers and flags.
                    applyType(newChild, typeSpec, version, bmEngine, topObjectFqn, tx);
                    applyFlags(newChild, indexing, fillChecking, multiLineSet, multiLine);
                    applyDimensionFlags(newChild, masterSet, master, mainFilterSet, mainFilter);

                    addChild(parent, kind, newChild);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding attribute", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add attribute: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        String kindLabel = describeKind(kind);
        ToolResult result = ToolResult.success()
            .put("parentFqn", normalizedParentFqn) //$NON-NLS-1$
            .put("kind", kindLabel) //$NON-NLS-1$
            .put("attributeName", attributeName); //$NON-NLS-1$
        if (typeSpec != null)
        {
            result.put("type", describeTypeSpec(typeSpec)); //$NON-NLS-1$
        }
        return result
            .put("message", kindLabel + " '" + attributeName + "' added successfully to " + normalizedParentFqn //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ". Run get_metadata_details to verify the type and get_project_errors to verify.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Builds a {@link TypeDescription} from the parsed spec and assigns it to
     * the attribute via {@link BasicFeature#setType}. No-op when {@code spec}
     * is {@code null} (the attribute keeps its factory default type).
     * <p>
     * Each type item name is resolved into a {@link TypeItem} proxy through the
     * project-scoped {@link ISymbolicNameService} - the same path EDT uses when
     * importing a configuration from XML. String, Number and Date qualifiers are
     * attached when at least one item of the corresponding kind carries them.
     *
     * @param attribute the attribute being configured
     * @param spec the parsed type specification (may be {@code null})
     * @param version the project platform version
     * @param engine the project BM engine (resolution scope for reference types)
     * @param contextTopObjectFqn FQN of the attribute's top object
     * @param tx the active BM transaction (used to verify referenced objects exist)
     */
    private void applyType(MdObject attribute, AttributeTypeSpec spec, Version version, IBmEngine engine,
        String contextTopObjectFqn, IBmTransaction tx)
    {
        if (spec == null)
        {
            return;
        }
        if (!(attribute instanceof BasicFeature))
        {
            throw new RuntimeException(
                "Attribute type '" + attribute.eClass().getName() + "' does not support setting a type"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        McoreFactory mcore = McoreFactory.eINSTANCE;
        TypeDescription typeDescription = mcore.createTypeDescription();
        EList<TypeItem> typeItems = typeDescription.getTypes();

        StringQualifiers stringQualifiers = null;
        NumberQualifiers numberQualifiers = null;
        DateQualifiers dateQualifiers = null;

        for (AttributeTypeSpec.Item item : spec.getItems())
        {
            TypeItem typeItem = resolveTypeItem(item.name, version, attribute, engine, contextTopObjectFqn, tx);
            typeItems.add(typeItem);

            if (item.isString() && hasStringQualifiers(item))
            {
                stringQualifiers = mcore.createStringQualifiers();
                stringQualifiers.setLength(item.stringLength != null ? item.stringLength : 0);
                if (item.stringFixed != null)
                {
                    stringQualifiers.setFixed(item.stringFixed);
                }
            }
            else if (item.isNumber() && hasNumberQualifiers(item))
            {
                numberQualifiers = mcore.createNumberQualifiers();
                if (item.numberPrecision != null)
                {
                    numberQualifiers.setPrecision(item.numberPrecision);
                }
                if (item.numberScale != null)
                {
                    numberQualifiers.setScale(item.numberScale);
                }
                if (item.numberNonNegative != null)
                {
                    numberQualifiers.setNonNegative(item.numberNonNegative);
                }
            }
            else if (item.isDate() && item.dateFractions != null)
            {
                dateQualifiers = mcore.createDateQualifiers();
                dateQualifiers.setDateFractions(toDateFractions(item.dateFractions));
            }
        }

        if (stringQualifiers != null)
        {
            typeDescription.setStringQualifiers(stringQualifiers);
        }
        if (numberQualifiers != null)
        {
            typeDescription.setNumberQualifiers(numberQualifiers);
        }
        if (dateQualifiers != null)
        {
            typeDescription.setDateQualifiers(dateQualifiers);
        }

        ((BasicFeature) attribute).setType(typeDescription);
    }

    /**
     * Resolves a type name (a platform type like {@code String} or a reference
     * type like {@code CatalogRef.Products}) into a {@link TypeItem} proxy.
     * <p>
     * Resolution is delegated to the project-scoped {@link ISymbolicNameService},
     * called with the {@code types} reference of {@link TypeDescription} as the
     * context feature and the project {@link IBmEngine}. This is the exact
     * mechanism EDT uses when it materializes a {@code <Type>} element while
     * importing a configuration from XML:
     * <ul>
     * <li>platform type names ({@code String}, {@code Number}, {@code Boolean},
     *     {@code Date}, ...) resolve against the platform type registry;</li>
     * <li>reference type names ({@code CatalogRef.X}, {@code DocumentRef.X},
     *     {@code EnumRef.X}, ...) resolve against the metadata objects of
     *     <em>this</em> project.</li>
     * </ul>
     * The returned proxy carries the resolved URI and is resolved lazily by the
     * BM resource set when the model is read or persisted.
     * <p>
     * When the name cannot be resolved the service yields an {@code unresolved:/}
     * URI; this method detects that and fails loudly rather than silently
     * producing a dangling type.
     *
     * @param typeName the verbatim type name to resolve
     * @param version the project platform version
     * @param contextObject the object the type is being set on (resolution context)
     * @param engine the project BM engine (resolution scope for reference types)
     * @param contextTopObjectFqn FQN of the context object's top object
     * @param tx the active BM transaction (used to verify referenced objects exist)
     * @return a resolved {@link TypeItem} proxy
     */
    private TypeItem resolveTypeItem(String typeName, Version version, MdObject contextObject, IBmEngine engine,
        String contextTopObjectFqn, IBmTransaction tx)
    {
        ISymbolicNameService symbolicNameService = ServiceAccess.get(ISymbolicNameService.class);
        if (symbolicNameService == null)
        {
            throw new RuntimeException("ISymbolicNameService not available; cannot resolve type: " + typeName); //$NON-NLS-1$
        }

        URI uri = symbolicNameService.convertSymbolicNameToUri(typeName, contextObject,
            McorePackage.Literals.TYPE_DESCRIPTION__TYPES, contextTopObjectFqn, engine, version);
        if (uri == null || "unresolved".equals(uri.scheme())) //$NON-NLS-1$
        {
            throw new RuntimeException(unknownTypeMessage(typeName));
        }

        // For a reference type the URI points at a metadata object of this
        // project (e.g. bm://.../Catalog.Products#/producedTypes/refType/type).
        // The symbolic-name service builds that URI from the name without
        // checking the target exists, so a typo would yield a dangling type.
        // Verify the referenced top object is actually present in the project
        // and fail loudly otherwise. Platform type URIs are not BM URIs and are
        // left untouched.
        if (BmUriUtil.isBmUri(uri))
        {
            String referencedTopObjectFqn = BmUriUtil.extractTopObjectFqn(uri);
            if (referencedTopObjectFqn != null && tx.getTopObjectByFqn(referencedTopObjectFqn) == null)
            {
                throw new RuntimeException(unknownTypeMessage(typeName));
            }
        }

        Type typeItem = McoreFactory.eINSTANCE.createType();
        ((InternalEObject) typeItem).eSetProxyURI(uri);
        return typeItem;
    }

    private static String unknownTypeMessage(String typeName)
    {
        return "Unknown type name: " + typeName //$NON-NLS-1$
            + ". Use a platform type (String, Number, Boolean, Date) or a reference type to an " //$NON-NLS-1$
            + "object that already exists in this project " //$NON-NLS-1$
            + "(e.g. CatalogRef.Products, DocumentRef.SalesOrder, EnumRef.ProductKinds)."; //$NON-NLS-1$
    }

    private void applyFlags(MdObject attribute, Indexing indexing, FillChecking fillChecking,
        boolean multiLineSet, boolean multiLine)
    {
        if (indexing != null)
        {
            // DB-object attributes and register dimensions both expose indexing,
            // through different interfaces (DbObjectAttribute / RegisterDimension).
            if (attribute instanceof DbObjectAttribute)
            {
                ((DbObjectAttribute) attribute).setIndexing(indexing);
            }
            else if (attribute instanceof RegisterDimension)
            {
                ((RegisterDimension) attribute).setIndexing(indexing);
            }
            else
            {
                throw new RuntimeException("Indexing is not supported for: " //$NON-NLS-1$
                    + attribute.eClass().getName()
                    + ". It applies to DB-object attributes and register dimensions only."); //$NON-NLS-1$
            }
        }
        if (fillChecking != null && attribute instanceof BasicFeature)
        {
            ((BasicFeature) attribute).setFillChecking(fillChecking);
        }
        if (multiLineSet && attribute instanceof BasicFeature)
        {
            ((BasicFeature) attribute).setMultiLine(multiLine);
        }
    }

    /**
     * Applies the dimension-specific {@code master} and {@code mainFilter} flags.
     * Both exist only on {@link InformationRegisterDimension}; callers already
     * validate that the parent is an {@link InformationRegister} before these are
     * set, so reaching a non-matching type here is a programming error.
     *
     * @param child the freshly created register child
     * @param masterSet whether the {@code master} flag was provided
     * @param master the {@code master} value
     * @param mainFilterSet whether the {@code mainFilter} flag was provided
     * @param mainFilter the {@code mainFilter} value
     */
    private void applyDimensionFlags(MdObject child, boolean masterSet, boolean master,
        boolean mainFilterSet, boolean mainFilter)
    {
        if (!masterSet && !mainFilterSet)
        {
            return;
        }
        if (!(child instanceof InformationRegisterDimension))
        {
            throw new RuntimeException("'master'/'mainFilter' are only supported for " //$NON-NLS-1$
                + "InformationRegister dimensions, not for: " + child.eClass().getName()); //$NON-NLS-1$
        }
        InformationRegisterDimension dim = (InformationRegisterDimension) child;
        if (masterSet)
        {
            dim.setMaster(master);
        }
        if (mainFilterSet)
        {
            dim.setMainFilter(mainFilter);
        }
    }

    private static boolean hasStringQualifiers(AttributeTypeSpec.Item item)
    {
        return item.stringLength != null || item.stringFixed != null;
    }

    private static boolean hasNumberQualifiers(AttributeTypeSpec.Item item)
    {
        return item.numberPrecision != null || item.numberScale != null || item.numberNonNegative != null;
    }

    private static DateFractions toDateFractions(AttributeTypeSpec.DateFraction fraction)
    {
        switch (fraction)
        {
            case DATE:
                return DateFractions.DATE;
            case TIME:
                return DateFractions.TIME;
            case DATE_TIME:
            default:
                return DateFractions.DATE_TIME;
        }
    }

    private static String describeTypeSpec(AttributeTypeSpec spec)
    {
        StringBuilder sb = new StringBuilder();
        for (AttributeTypeSpec.Item item : spec.getItems())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(item.name);
        }
        return sb.toString();
    }

    private static Indexing parseIndexing(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        String v = value.trim().toLowerCase();
        switch (v)
        {
            case "dontindex": //$NON-NLS-1$
            case "donotindex": //$NON-NLS-1$
            case "none": //$NON-NLS-1$
                return Indexing.DONT_INDEX;
            case "index": //$NON-NLS-1$
                return Indexing.INDEX;
            case "indexwithadditionalorder": //$NON-NLS-1$
            case "indexwithadditionalordering": //$NON-NLS-1$
                return Indexing.INDEX_WITH_ADDITIONAL_ORDER;
            default:
                throw new IllegalArgumentException("Invalid 'indexing': " + value //$NON-NLS-1$
                    + ". Expected 'DontIndex', 'Index' or 'IndexWithAdditionalOrder'."); //$NON-NLS-1$
        }
    }

    private static FillChecking parseFillChecking(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        String v = value.trim().toLowerCase();
        switch (v)
        {
            case "dontcheck": //$NON-NLS-1$
            case "donotcheck": //$NON-NLS-1$
                return FillChecking.DONT_CHECK;
            case "showerror": //$NON-NLS-1$
            case "error": //$NON-NLS-1$
                return FillChecking.SHOW_ERROR;
            default:
                throw new IllegalArgumentException("Invalid 'fillChecking': " + value //$NON-NLS-1$
                    + ". Expected 'DontCheck' or 'ShowError'."); //$NON-NLS-1$
        }
    }

    /**
     * Parses the optional {@code kind} parameter. Defaults to {@link Kind#ATTRIBUTE}.
     * Accepts English and Russian names (singular/plural).
     *
     * @param value the raw parameter value (may be {@code null}/blank)
     * @return the resolved kind
     * @throws IllegalArgumentException when the value is not a recognized kind
     */
    private static Kind parseKind(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return Kind.ATTRIBUTE;
        }
        String v = value.trim().toLowerCase();
        switch (v)
        {
            case "attribute": //$NON-NLS-1$
            case "attributes": //$NON-NLS-1$
            case "реквизит": // Реквизит //$NON-NLS-1$
            case "реквизиты": // Реквизиты //$NON-NLS-1$
                return Kind.ATTRIBUTE;
            case "dimension": //$NON-NLS-1$
            case "dimensions": //$NON-NLS-1$
            case "измерение": // Измерение //$NON-NLS-1$
            case "измерения": // Измерения //$NON-NLS-1$
                return Kind.DIMENSION;
            case "resource": //$NON-NLS-1$
            case "resources": //$NON-NLS-1$
            case "ресурс": // Ресурс //$NON-NLS-1$
            case "ресурсы": // Ресурсы //$NON-NLS-1$
                return Kind.RESOURCE;
            default:
                throw new IllegalArgumentException("Invalid 'kind': " + value //$NON-NLS-1$
                    + ". Expected 'Attribute', 'Dimension' or 'Resource'."); //$NON-NLS-1$
        }
    }

    private static String describeKind(Kind kind)
    {
        switch (kind)
        {
            case DIMENSION:
                return "Dimension"; //$NON-NLS-1$
            case RESOURCE:
                return "Resource"; //$NON-NLS-1$
            case ATTRIBUTE:
            default:
                return "Attribute"; //$NON-NLS-1$
        }
    }

    private boolean supportsAttributes(MdObject obj)
    {
        return obj instanceof Catalog
            || obj instanceof Document
            || obj instanceof ExchangePlan
            || obj instanceof ChartOfCharacteristicTypes
            || obj instanceof ChartOfAccounts
            || obj instanceof ChartOfCalculationTypes
            || obj instanceof BusinessProcess
            || obj instanceof Task
            || obj instanceof DataProcessor
            || obj instanceof Report
            || obj instanceof InformationRegister
            || obj instanceof AccumulationRegister
            || obj instanceof AccountingRegister;
    }

    /**
     * Returns the EMF collection getter name for the given child kind
     * ({@code getAttributes}/{@code getDimensions}/{@code getResources}).
     *
     * @param kind the child kind
     * @return the getter method name
     */
    private static String collectionGetterName(Kind kind)
    {
        switch (kind)
        {
            case DIMENSION:
                return "getDimensions"; //$NON-NLS-1$
            case RESOURCE:
                return "getResources"; //$NON-NLS-1$
            case ATTRIBUTE:
            default:
                return "getAttributes"; //$NON-NLS-1$
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasChild(MdObject parent, Kind kind, String name)
    {
        try
        {
            java.lang.reflect.Method method = parent.getClass().getMethod(collectionGetterName(kind));
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                EList<? extends MdObject> children = (EList<? extends MdObject>) result;
                for (MdObject child : children)
                {
                    if (name.equalsIgnoreCase(child.getName()))
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Type may not expose this collection.
        }
        return false;
    }

    /**
     * Creates the EMF child object matching the parent type and the requested
     * kind. Dimensions/resources are only produced for
     * {@link InformationRegister} and {@link AccumulationRegister}; for every
     * other supported parent only {@link Kind#ATTRIBUTE} is valid (the caller
     * validates this before the transaction).
     *
     * @param parent the parent metadata object
     * @param kind the kind of child to create
     * @return the new EMF child, or {@code null} when the combination is unsupported
     */
    private MdObject createChild(MdObject parent, Kind kind)
    {
        MdClassFactory factory = MdClassFactory.eINSTANCE;

        if (parent instanceof InformationRegister)
        {
            switch (kind)
            {
                case DIMENSION:
                    return factory.createInformationRegisterDimension();
                case RESOURCE:
                    return factory.createInformationRegisterResource();
                case ATTRIBUTE:
                default:
                    return factory.createInformationRegisterAttribute();
            }
        }
        if (parent instanceof AccumulationRegister)
        {
            switch (kind)
            {
                case DIMENSION:
                    return factory.createAccumulationRegisterDimension();
                case RESOURCE:
                    return factory.createAccumulationRegisterResource();
                case ATTRIBUTE:
                default:
                    return factory.createAccumulationRegisterAttribute();
            }
        }

        // Non-register or attribute-only parents: only attributes are valid here.
        if (parent instanceof Catalog)
        {
            return factory.createCatalogAttribute();
        }
        if (parent instanceof Document)
        {
            return factory.createDocumentAttribute();
        }
        if (parent instanceof ExchangePlan)
        {
            return factory.createExchangePlanAttribute();
        }
        if (parent instanceof ChartOfCharacteristicTypes)
        {
            return factory.createChartOfCharacteristicTypesAttribute();
        }
        if (parent instanceof ChartOfAccounts)
        {
            return factory.createChartOfAccountsAttribute();
        }
        if (parent instanceof ChartOfCalculationTypes)
        {
            return factory.createChartOfCalculationTypesAttribute();
        }
        if (parent instanceof BusinessProcess)
        {
            return factory.createBusinessProcessAttribute();
        }
        if (parent instanceof Task)
        {
            return factory.createTaskAttribute();
        }
        if (parent instanceof DataProcessor)
        {
            return factory.createDataProcessorAttribute();
        }
        if (parent instanceof Report)
        {
            return factory.createReportAttribute();
        }
        if (parent instanceof AccountingRegister)
        {
            return factory.createAccountingRegisterAttribute();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addChild(MdObject parent, Kind kind, MdObject child)
    {
        String getter = collectionGetterName(kind);
        try
        {
            java.lang.reflect.Method method = parent.getClass().getMethod(getter);
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                ((EList<MdObject>) result).add(child);
            }
            else
            {
                throw new RuntimeException(getter + "() did not return EList"); //$NON-NLS-1$
            }
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Failed to add " + describeKind(kind) //$NON-NLS-1$
                + " via reflection", e); //$NON-NLS-1$
        }
    }
}
