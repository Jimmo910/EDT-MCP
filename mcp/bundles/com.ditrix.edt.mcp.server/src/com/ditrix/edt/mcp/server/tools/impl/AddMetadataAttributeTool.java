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
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
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
import com._1c.g5.v8.dt.metadata.mdclass.Indexing;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
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
 * resolved into {@link TypeItem} proxies via the platform type registry
 * ({@link IEObjectProvider.Registry}) for the project's platform
 * {@link Version}. This is the same mechanism EDT itself uses to materialize
 * type proxies by name; the proxies are resolved lazily by the BM resource set.
 */
public class AddMetadataAttributeTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "add_metadata_attribute"; //$NON-NLS-1$

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
                "Name for the new attribute (required)", true) //$NON-NLS-1$
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

        return executeInternal(projectName, parentFqn, attributeName,
            typeSpec, indexing, fillChecking, multiLineSet, multiLine);
    }

    private String executeInternal(String projectName, String parentFqn, String attributeName,
        final AttributeTypeSpec typeSpec, final Indexing indexing, final FillChecking fillChecking,
        final boolean multiLineSet, final boolean multiLine)
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

        // Get bmId of parent for BM task
        if (!(parentObject instanceof IBmObject))
        {
            return ToolResult.error("Parent object is not a BM object").toJson(); //$NON-NLS-1$
        }
        long parentBmId = ((IBmObject) parentObject).bmGetId();

        // Execute write task
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

                    // Check if attribute with this name already exists
                    if (hasAttribute(parent, attributeName))
                    {
                        throw new RuntimeException("Attribute already exists: " + attributeName); //$NON-NLS-1$
                    }

                    // Create and add attribute
                    MdObject newAttribute = createAttribute(parent);
                    if (newAttribute == null)
                    {
                        throw new RuntimeException(
                            "Cannot create attribute for: " + parent.eClass().getName()); //$NON-NLS-1$
                    }
                    newAttribute.setName(attributeName);
                    newAttribute.setUuid(UUID.randomUUID());

                    // Apply optional type, qualifiers and flags.
                    applyType(newAttribute, typeSpec, version);
                    applyFlags(newAttribute, indexing, fillChecking, multiLineSet, multiLine);

                    addAttribute(parent, newAttribute);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding attribute", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add attribute: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put("parentFqn", normalizedParentFqn) //$NON-NLS-1$
            .put("attributeName", attributeName); //$NON-NLS-1$
        if (typeSpec != null)
        {
            result.put("type", describeTypeSpec(typeSpec)); //$NON-NLS-1$
        }
        return result
            .put("message", "Attribute '" + attributeName + "' added successfully to " + normalizedParentFqn //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ". Run get_metadata_details to verify the type and get_project_errors to verify.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Builds a {@link TypeDescription} from the parsed spec and assigns it to
     * the attribute via {@link BasicFeature#setType}. No-op when {@code spec}
     * is {@code null} (the attribute keeps its factory default type).
     * <p>
     * Each type item name is resolved into a {@link TypeItem} proxy through the
     * platform type registry - the same path EDT uses internally. String,
     * Number and Date qualifiers are attached when at least one item of the
     * corresponding kind carries them.
     */
    private void applyType(MdObject attribute, AttributeTypeSpec spec, Version version)
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
            TypeItem typeItem = resolveTypeItem(item.name, version);
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
     * Resolves a type name (platform type like {@code String} or reference type
     * like {@code CatalogRef.Products}) into a {@link TypeItem} proxy via the
     * platform {@link IEObjectProvider.Registry}. The proxy is resolved lazily
     * by the BM resource set when the model is read or persisted.
     */
    private TypeItem resolveTypeItem(String typeName, Version version)
    {
        IEObjectProvider provider =
            IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
        if (provider == null)
        {
            throw new RuntimeException("No type provider available for the project platform version"); //$NON-NLS-1$
        }
        try
        {
            TypeItem typeItem = provider.createProxy(typeName);
            if (typeItem == null)
            {
                throw new RuntimeException("Could not resolve type: " + typeName); //$NON-NLS-1$
            }
            return typeItem;
        }
        catch (IllegalArgumentException e)
        {
            throw new RuntimeException("Unknown type name: " + typeName //$NON-NLS-1$
                + ". Use a platform type (String, Number, Boolean, Date) or a reference type " //$NON-NLS-1$
                + "(e.g. CatalogRef.Products, DocumentRef.SalesOrder, EnumRef.ProductKinds)."); //$NON-NLS-1$
        }
    }

    private void applyFlags(MdObject attribute, Indexing indexing, FillChecking fillChecking,
        boolean multiLineSet, boolean multiLine)
    {
        if (indexing != null)
        {
            if (attribute instanceof DbObjectAttribute)
            {
                ((DbObjectAttribute) attribute).setIndexing(indexing);
            }
            else
            {
                throw new RuntimeException("Indexing is not supported for attribute type: " //$NON-NLS-1$
                    + attribute.eClass().getName());
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

    @SuppressWarnings("unchecked")
    private boolean hasAttribute(MdObject parent, String name)
    {
        try
        {
            java.lang.reflect.Method method = parent.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                EList<? extends MdObject> attrs = (EList<? extends MdObject>) result;
                for (MdObject attr : attrs)
                {
                    if (name.equalsIgnoreCase(attr.getName()))
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Type may not have getAttributes
        }
        return false;
    }

    private MdObject createAttribute(MdObject parent)
    {
        MdClassFactory factory = MdClassFactory.eINSTANCE;

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
        if (parent instanceof InformationRegister)
        {
            return factory.createInformationRegisterAttribute();
        }
        if (parent instanceof AccumulationRegister)
        {
            return factory.createAccumulationRegisterAttribute();
        }
        if (parent instanceof AccountingRegister)
        {
            return factory.createAccountingRegisterAttribute();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addAttribute(MdObject parent, MdObject attribute)
    {
        try
        {
            java.lang.reflect.Method method = parent.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                ((EList<MdObject>) result).add(attribute);
            }
            else
            {
                throw new RuntimeException("getAttributes() did not return EList"); //$NON-NLS-1$
            }
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Failed to add attribute via reflection", e); //$NON-NLS-1$
        }
    }
}
