/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.common.FillChecking;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DbObjectAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Indexing;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.RegisterDimension;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.AttributeTypeSpec;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.TypeDescriptionBuilder;

/**
 * Tool to change properties of an <em>existing</em> attribute (or register
 * dimension/resource) without deleting and recreating it.
 * <p>
 * The child is located by its owner FQN plus its name, searching the owner's
 * {@code attributes}, {@code dimensions} and {@code resources} collections. Its
 * properties are then modified inside a single BM write transaction. Supported
 * properties (each optional; at least one must be supplied):
 * <ul>
 * <li>{@code type} - the new attribute type. Same syntax as
 *     {@code add_metadata_attribute}: a single platform type with optional
 *     qualifiers ({@code String(0)} for unlimited length, {@code Number(15,2)},
 *     {@code Date(DateTime)}), a reference type ({@code CatalogRef.Products}), or
 *     a comma-separated list for a composite type. The type is built and resolved
 *     with the shared project-scoped {@link TypeDescriptionBuilder} (the S2
 *     resolver), exactly like {@code add_metadata_attribute}.</li>
 * <li>{@code indexing} - {@code DontIndex} / {@code Index} /
 *     {@code IndexWithAdditionalOrder} (DB-object attributes and register
 *     dimensions only).</li>
 * <li>{@code fillChecking} - {@code DontCheck} / {@code ShowError}.</li>
 * <li>{@code multiLine} - multi-line text flag (boolean).</li>
 * </ul>
 * Resolving a missing attribute yields a clear error. A typical use is widening a
 * {@code String(200)} attribute to an unlimited {@code String} ({@code String(0)})
 * to clear the {@code md-object-attribute-comment-incorrect-type} check.
 */
public class SetAttributePropertyTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "set_attribute_property"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Change properties of an existing attribute (or register dimension/resource) " //$NON-NLS-1$
            + "in place, without deleting and recreating it. The child is located by its owner " //$NON-NLS-1$
            + "FQN and name (searching attributes, dimensions and resources). " //$NON-NLS-1$
            + "Properties (all optional, at least one required): " //$NON-NLS-1$
            + "'type' (same syntax as add_metadata_attribute: 'String', 'String(50)', " //$NON-NLS-1$
            + "'String(0)' for unlimited length, 'Number(15,2)', 'Date(DateTime)', " //$NON-NLS-1$
            + "'CatalogRef.Products', or a comma-separated composite list; resolved with the " //$NON-NLS-1$
            + "same project-scoped resolver as add_metadata_attribute), " //$NON-NLS-1$
            + "'indexing' ('DontIndex' | 'Index' | 'IndexWithAdditionalOrder', DB-object " //$NON-NLS-1$
            + "attributes and register dimensions only), 'fillChecking' " //$NON-NLS-1$
            + "('DontCheck' | 'ShowError'), 'multiLine' (boolean). " //$NON-NLS-1$
            + "Example: {ownerFqn: 'Document.Invoice', attributeName: 'Comment', type: 'String(0)'} " //$NON-NLS-1$
            + "widens a String(200) attribute to unlimited length. " //$NON-NLS-1$
            + "Russian type and object names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("ownerFqn", //$NON-NLS-1$
                "FQN of the owner object (required), e.g. 'Catalog.Products', " //$NON-NLS-1$
                + "'Document.Invoice', 'InformationRegister.Prices'. Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "Name of the existing attribute / dimension / resource to modify (required).", true) //$NON-NLS-1$
            .stringProperty("type", //$NON-NLS-1$
                "Optional new attribute type. A single type or a comma-separated list for a " //$NON-NLS-1$
                + "composite type. Platform types support qualifiers: 'String', 'String(50)', " //$NON-NLS-1$
                + "'String(0)' (unlimited), 'String(50,fixed)', 'Number(15,2)', " //$NON-NLS-1$
                + "'Number(15,2,nonnegative)', 'Date(Date|Time|DateTime)', 'Boolean'. " //$NON-NLS-1$
                + "Reference types: 'CatalogRef.<Name>', 'DocumentRef.<Name>', 'EnumRef.<Name>', etc. " //$NON-NLS-1$
                + "Composite example: 'String(10), CatalogRef.Products'.", false) //$NON-NLS-1$
            .stringProperty("indexing", //$NON-NLS-1$
                "Optional indexing: 'DontIndex', 'Index' or 'IndexWithAdditionalOrder' " //$NON-NLS-1$
                + "(DB-object attributes and register dimensions only).", false) //$NON-NLS-1$
            .stringProperty("fillChecking", //$NON-NLS-1$
                "Optional fill checking: 'DontCheck' or 'ShowError'.", false) //$NON-NLS-1$
            .booleanProperty("multiLine", //$NON-NLS-1$
                "Optional multi-line text field flag (for String attributes).", false) //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " //$NON-NLS-1$
                + "Usage: {projectName: 'MyProject', ownerFqn: 'Document.Invoice', " //$NON-NLS-1$
                + "attributeName: 'Comment', type: 'String(0)'}").toJson(); //$NON-NLS-1$
        }
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            return ToolResult.error("ownerFqn is required. " //$NON-NLS-1$
                + "Examples: 'Catalog.Products', 'Document.Invoice', 'InformationRegister.Prices'.").toJson(); //$NON-NLS-1$
        }
        if (attributeName == null || attributeName.isEmpty())
        {
            return ToolResult.error("attributeName is required. " //$NON-NLS-1$
                + "Usage: {ownerFqn: 'Document.Invoice', attributeName: 'Comment', type: 'String(0)'}").toJson(); //$NON-NLS-1$
        }

        // Parse the optional type/qualifiers/flags BEFORE any transaction.
        String typeSpecRaw = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        boolean typeSet = typeSpecRaw != null && !typeSpecRaw.trim().isEmpty();
        AttributeTypeSpec typeSpec = null;
        if (typeSet)
        {
            try
            {
                typeSpec = AttributeTypeSpec.parse(typeSpecRaw);
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error("Invalid 'type': " + e.getMessage() + ". " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Examples: 'String(0)', 'String(50)', 'Number(15,2)', 'Date(DateTime)', " //$NON-NLS-1$
                    + "'CatalogRef.Products', 'String(10), CatalogRef.Products'.").toJson(); //$NON-NLS-1$
            }
        }

        String indexingRaw = JsonUtils.extractStringArgument(params, "indexing"); //$NON-NLS-1$
        boolean indexingSet = indexingRaw != null && !indexingRaw.trim().isEmpty();
        Indexing indexing = null;
        if (indexingSet)
        {
            try
            {
                indexing = parseIndexing(indexingRaw);
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error(e.getMessage()).toJson();
            }
        }

        String fillCheckingRaw = JsonUtils.extractStringArgument(params, "fillChecking"); //$NON-NLS-1$
        boolean fillCheckingSet = fillCheckingRaw != null && !fillCheckingRaw.trim().isEmpty();
        FillChecking fillChecking = null;
        if (fillCheckingSet)
        {
            try
            {
                fillChecking = parseFillChecking(fillCheckingRaw);
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error(e.getMessage()).toJson();
            }
        }

        boolean multiLineSet = params.containsKey("multiLine"); //$NON-NLS-1$
        boolean multiLine = multiLineSet && JsonUtils.extractBooleanArgument(params, "multiLine", false); //$NON-NLS-1$

        if (!typeSet && !indexingSet && !fillCheckingSet && !multiLineSet)
        {
            return ToolResult.error("No properties to set. Provide at least one of: " //$NON-NLS-1$
                + "'type', 'indexing', 'fillChecking', 'multiLine'.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, ownerFqn, attributeName, typeSpec, indexingSet, indexing,
            fillCheckingSet, fillChecking, multiLineSet, multiLine);
    }

    private String executeInternal(String projectName, String ownerFqn, final String attributeName,
        final AttributeTypeSpec typeSpec, final boolean indexingSet, final Indexing indexing,
        final boolean fillCheckingSet, final FillChecking fillChecking,
        final boolean multiLineSet, final boolean multiLine)
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

        String normalizedOwnerFqn = MetadataTypeUtils.normalizeFqn(ownerFqn);
        String[] parts = normalizedOwnerFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Invalid ownerFqn: " + normalizedOwnerFqn + ". Expected 'Type.Name'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MdObject owner = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (owner == null)
        {
            return ToolResult.error("Owner object not found: " + normalizedOwnerFqn + ". " //$NON-NLS-1$
                + "Check the FQN format: 'Type.Name' (e.g. 'Document.Invoice'). " //$NON-NLS-1$
                + "Use get_metadata_objects to list available objects.").toJson(); //$NON-NLS-1$
        }
        if (!(owner instanceof IBmObject))
        {
            return ToolResult.error("Owner object is not a BM object").toJson(); //$NON-NLS-1$
        }

        // Locate the child outside the transaction so a missing attribute fails
        // with a clear, listing-aware message before any write begins.
        MdObject child = findChild(owner, attributeName);
        if (child == null)
        {
            return ToolResult.error("Attribute not found: '" + attributeName + "' on " + normalizedOwnerFqn //$NON-NLS-1$ //$NON-NLS-2$
                + ". Available: " + describeChildren(owner) //$NON-NLS-1$
                + ". Use add_metadata_attribute to create a new one.").toJson(); //$NON-NLS-1$
        }
        if (typeSpec != null && !(child instanceof BasicFeature))
        {
            return ToolResult.error("Child '" + attributeName + "' (" + child.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ") does not support setting a type.").toJson(); //$NON-NLS-1$
        }
        final long childBmId = ((IBmObject) child).bmGetId();
        final IBmEngine bmEngine = bmModel.getEngine();
        final List<String> applied = new ArrayList<>();

        try
        {
            bmModel.execute(new AbstractBmTask<Void>("SetAttributeProperty") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject fresh = (MdObject) tx.getObjectById(childBmId);
                    if (fresh == null)
                    {
                        throw new RuntimeException("Attribute not found in transaction: " + attributeName); //$NON-NLS-1$
                    }

                    if (typeSpec != null)
                    {
                        String topObjectFqn = TypeDescriptionBuilder.topObjectFqnOf(fresh);
                        TypeDescription typeDescription =
                            TypeDescriptionBuilder.build(typeSpec, version, fresh, bmEngine, topObjectFqn, tx);
                        ((BasicFeature) fresh).setType(typeDescription);
                        applied.add("type=" + TypeDescriptionBuilder.describe(typeSpec)); //$NON-NLS-1$
                    }
                    if (indexingSet)
                    {
                        applyIndexing(fresh, indexing);
                        applied.add("indexing=" + indexing.getName()); //$NON-NLS-1$
                    }
                    if (fillCheckingSet)
                    {
                        if (!(fresh instanceof BasicFeature))
                        {
                            throw new RuntimeException("fillChecking is not supported for: " //$NON-NLS-1$
                                + fresh.eClass().getName());
                        }
                        ((BasicFeature) fresh).setFillChecking(fillChecking);
                        applied.add("fillChecking=" + fillChecking.getName()); //$NON-NLS-1$
                    }
                    if (multiLineSet)
                    {
                        if (!(fresh instanceof BasicFeature))
                        {
                            throw new RuntimeException("multiLine is not supported for: " //$NON-NLS-1$
                                + fresh.eClass().getName());
                        }
                        ((BasicFeature) fresh).setMultiLine(multiLine);
                        applied.add("multiLine=" + multiLine); //$NON-NLS-1$
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error in set_attribute_property", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set attribute properties: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("ownerFqn", normalizedOwnerFqn) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("appliedProperties", applied) //$NON-NLS-1$
            .put("message", "Updated " + applied.size() + " property(ies) on attribute '" //$NON-NLS-1$ //$NON-NLS-2$
                + attributeName + "' of " + normalizedOwnerFqn //$NON-NLS-1$
                + ". Run get_project_errors to verify and get_metadata_details to confirm the new type.") //$NON-NLS-1$
            .toJson();
    }

    private static void applyIndexing(MdObject child, Indexing indexing)
    {
        if (child instanceof DbObjectAttribute)
        {
            ((DbObjectAttribute) child).setIndexing(indexing);
        }
        else if (child instanceof RegisterDimension)
        {
            ((RegisterDimension) child).setIndexing(indexing);
        }
        else
        {
            throw new RuntimeException("Indexing is not supported for: " + child.eClass().getName() //$NON-NLS-1$
                + ". It applies to DB-object attributes and register dimensions only."); //$NON-NLS-1$
        }
    }

    /**
     * Locates a child (attribute, dimension or resource) of the owner by name,
     * case-insensitively, searching the {@code attributes}, {@code dimensions} and
     * {@code resources} collections in that order.
     *
     * @param owner the owner metadata object
     * @param name the child name to find
     * @return the matching child, or {@code null} when none matches
     */
    private static MdObject findChild(MdObject owner, String name)
    {
        for (String getter : CHILD_GETTERS)
        {
            for (MdObject child : childrenVia(owner, getter))
            {
                if (name.equalsIgnoreCase(child.getName()))
                {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * @param owner the owner metadata object
     * @return a comma-separated, human-readable list of the owner's child names by
     *         collection, used in the "not found" error message
     */
    private static String describeChildren(MdObject owner)
    {
        StringBuilder sb = new StringBuilder();
        for (String getter : CHILD_GETTERS)
        {
            List<MdObject> children = childrenVia(owner, getter);
            if (children.isEmpty())
            {
                continue;
            }
            String label = getter.substring("get".length()); //$NON-NLS-1$
            if (sb.length() > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            sb.append(label).append(": "); //$NON-NLS-1$
            for (int i = 0; i < children.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(children.get(i).getName());
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString(); //$NON-NLS-1$
    }

    /** Owner collection getters searched for a child, in priority order. */
    private static final String[] CHILD_GETTERS = {"getAttributes", "getDimensions", "getResources"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static List<MdObject> childrenVia(MdObject owner, String getter)
    {
        List<MdObject> result = new ArrayList<>();
        try
        {
            Method method = owner.getClass().getMethod(getter);
            Object value = method.invoke(owner);
            if (value instanceof EList)
            {
                for (Object o : (EList<?>) value)
                {
                    if (o instanceof MdObject)
                    {
                        result.add((MdObject) o);
                    }
                }
            }
        }
        catch (ReflectiveOperationException e)
        {
            // The owner does not expose this collection; ignore.
        }
        return result;
    }

    private static Indexing parseIndexing(String value)
    {
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
}
