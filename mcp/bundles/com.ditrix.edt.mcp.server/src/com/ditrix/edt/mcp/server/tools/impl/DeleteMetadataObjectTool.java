/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to delete a metadata object or attribute, using EDT's own delete refactoring to detect
 * incoming references the same way the EDT/Configurator UI does.
 *
 * Two-phase workflow:
 * 1. Preview mode (confirm=false, default): Returns the refactoring items and any blocking
 *    references (objects that still point at the target).
 * 2. Execute mode (confirm=true): Blocks the deletion if the object is still referenced and
 *    lists the referencing objects; pass force=true to delete anyway, leaving them dangling.
 */
public class DeleteMetadataObjectTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "delete_metadata_object"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete a metadata object or attribute with full refactoring support. " + //$NON-NLS-1$
               "Before deleting, checks for incoming references the same way the EDT/Configurator UI does: " + //$NON-NLS-1$
               "if the object is still referenced by other metadata, the deletion is BLOCKED and the " + //$NON-NLS-1$
               "referencing objects are listed. Pass force=true to delete anyway (the references are then " + //$NON-NLS-1$
               "left dangling, same as a forced delete in the UI). " + //$NON-NLS-1$
               "First call without confirm to preview the refactoring and any blocking references, " + //$NON-NLS-1$
               "then call with confirm=true to apply. " + //$NON-NLS-1$
               "Supports FQNs like 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'. " + //$NON-NLS-1$
               "Russian type names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to delete " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'). " + //$NON-NLS-1$
                "Russian names supported.", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "Set to true to execute the deletion. " + //$NON-NLS-1$
                "Default false = preview only.") //$NON-NLS-1$
            .booleanProperty("force", //$NON-NLS-1$
                "Set to true to delete even when the object is still referenced by other metadata " + //$NON-NLS-1$
                "(the incoming references are left dangling). " + //$NON-NLS-1$
                "Default false = block the deletion and list the referencing objects.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        boolean force = JsonUtils.extractBooleanArgument(params, "force", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " + //$NON-NLS-1$
                "Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products'}").toJson(); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("objectFqn is required. " + //$NON-NLS-1$
                "Examples: 'Catalog.Products' (delete whole catalog), " + //$NON-NLS-1$
                "'Document.SalesOrder.Attribute.Amount' (delete attribute), " + //$NON-NLS-1$
                "'Catalog.Products.TabularSection.Prices' (delete tabular section)").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, objectFqn, confirm, force);
    }

    private String executeInternal(String projectName, String objectFqn, boolean confirm, boolean force)
    {
        // Get project and configuration
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        Configuration config = ctx.config;

        // Get refactoring service
        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return ToolResult.error("IMdRefactoringService not available").toJson(); //$NON-NLS-1$
        }

        // Normalize and find the object
        objectFqn = MetadataTypeUtils.normalizeFqn(objectFqn);
        MdObject targetObject = resolveObject(config, objectFqn);
        if (targetObject == null)
        {
            return ToolResult.error("Object not found: " + objectFqn + ". " + //$NON-NLS-1$
                "Check the FQN format: 'Type.Name' for top-level objects (e.g. 'Catalog.Products'), " + //$NON-NLS-1$
                "'Type.Name.ChildType.ChildName' for nested (e.g. 'Document.Order.Attribute.Amount'). " + //$NON-NLS-1$
                "Supported child types: Attribute, TabularSection, Dimension, Resource.").toJson(); //$NON-NLS-1$
        }

        // Create delete refactoring
        IRefactoring refactoring = refactoringService.createMdObjectDeleteRefactoring(
            Collections.singletonList(targetObject));
        if (refactoring == null)
        {
            return ToolResult.error("Failed to create delete refactoring for: " + objectFqn).toJson(); //$NON-NLS-1$
        }

        if (!confirm)
        {
            return buildPreview(objectFqn, refactoring);
        }
        else
        {
            return performDelete(objectFqn, refactoring, force);
        }
    }

    private String buildPreview(String objectFqn, IRefactoring refactoring)
    {
        List<Map<String, Object>> allItems = new ArrayList<>();

        String title = refactoring.getTitle();

        // Collect refactoring items
        Collection<IRefactoringItem> items = refactoring.getItems();
        if (items != null)
        {
            for (IRefactoringItem item : items)
            {
                Map<String, Object> itemMap = new java.util.LinkedHashMap<>();
                itemMap.put("name", item.getName()); //$NON-NLS-1$
                itemMap.put("optional", item.isOptional()); //$NON-NLS-1$
                itemMap.put("checked", item.isChecked()); //$NON-NLS-1$
                allItems.add(itemMap);
            }
        }

        // Incoming references EDT could not clean automatically — these block the delete
        List<Map<String, Object>> blocking = collectBlockingProblems(refactoring);

        String message = blocking.isEmpty()
            ? "No blocking references found. Call with confirm=true to delete." //$NON-NLS-1$
            : "This object is referenced by " + blocking.size() + " object(s). " //$NON-NLS-1$ //$NON-NLS-2$
                + "Calling with confirm=true will be BLOCKED unless force=true is also passed " //$NON-NLS-1$
                + "(force leaves these references dangling)."; //$NON-NLS-1$

        ToolResult result = ToolResult.success()
            .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectFqn", objectFqn) //$NON-NLS-1$
            .put("refactoringTitle", title) //$NON-NLS-1$
            .put("items", allItems) //$NON-NLS-1$
            .put("blockingReferences", blocking) //$NON-NLS-1$
            .put("blockingReferencesCount", blocking.size()) //$NON-NLS-1$
            .put("message", message); //$NON-NLS-1$

        return result.toJson();
    }

    /**
     * Collects the refactoring's blocking problems — the incoming references EDT could not
     * resolve automatically. This is the same set the EDT/Configurator UI renders before a
     * delete. A {@link CleanReferenceProblem} carries the referencing object and the feature
     * through which it points at the object being deleted; other problem kinds
     * ({@code DeletionForbiddenProblem}, {@code EditingForbiddenProblem}) only carry the
     * target object. A non-empty result means the deletion is unsafe without force.
     */
    private List<Map<String, Object>> collectBlockingProblems(IRefactoring refactoring)
    {
        List<Map<String, Object>> result = new ArrayList<>();

        RefactoringStatus status = refactoring.getStatus();
        if (status == null)
        {
            return result;
        }
        Collection<IRefactoringProblem> problems = status.getProblems();
        if (problems == null)
        {
            return result;
        }

        for (IRefactoringProblem problem : problems)
        {
            Map<String, Object> problemMap = new java.util.LinkedHashMap<>();
            problemMap.put("problemType", problem.getClass().getSimpleName()); //$NON-NLS-1$
            // Best-effort description; never let a single odd problem abort the whole check.
            try
            {
                if (problem instanceof CleanReferenceProblem crp)
                {
                    EObject refObj = crp.getReferencingObject();
                    if (refObj instanceof IBmObject bmObj)
                    {
                        String fqn = bmFqnSafe(bmObj);
                        if (fqn != null)
                        {
                            problemMap.put("referencingObject", fqn); //$NON-NLS-1$
                        }
                    }
                    EStructuralFeature feat = crp.getReference();
                    if (feat != null)
                    {
                        problemMap.put("reference", feat.getName()); //$NON-NLS-1$
                    }
                }
                EObject obj = problem.getObject();
                if (obj instanceof IBmObject bmObj)
                {
                    String fqn = bmFqnSafe(bmObj);
                    if (fqn != null)
                    {
                        problemMap.put("targetObject", fqn); //$NON-NLS-1$
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logError("Error describing refactoring problem", e); //$NON-NLS-1$
            }
            result.add(problemMap);
        }
        return result;
    }

    /**
     * Returns a human-readable FQN for a BM object. {@code bmGetFqn()} is only legal on top
     * objects, so for a nested object (e.g. a register dimension or a type item that holds the
     * reference) we climb to the owning top object and append the nested element's name when
     * one is available. Never throws.
     */
    private static String bmFqnSafe(IBmObject obj)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            if (obj.bmIsTop())
            {
                return obj.bmGetFqn();
            }
        }
        catch (Exception e)
        {
            // fall through to top-object resolution
        }

        String localName = null;
        if (obj instanceof MdObject mdo)
        {
            localName = mdo.getName();
        }
        else if (obj instanceof org.eclipse.emf.ecore.ENamedElement ene)
        {
            localName = ene.getName();
        }

        try
        {
            IBmObject top = obj.bmGetTopObject();
            if (top != null && top != obj)
            {
                String topFqn = top.bmGetFqn();
                if (topFqn != null)
                {
                    return (localName != null && !localName.isEmpty())
                        ? topFqn + " (" + localName + ")" //$NON-NLS-1$ //$NON-NLS-2$
                        : topFqn;
                }
            }
        }
        catch (Exception e)
        {
            // ignore — fall back to the local name (or null)
        }
        return localName;
    }

    private String performDelete(String objectFqn, IRefactoring refactoring, boolean force)
    {
        // EDT's own reference check: if the object is still referenced and the caller did not
        // force, block the deletion and report the referencing objects (mirrors the UI).
        List<Map<String, Object>> blocking = collectBlockingProblems(refactoring);
        if (!blocking.isEmpty() && !force)
        {
            return ToolResult.error("Cannot delete '" + objectFqn + "': it is still referenced by " //$NON-NLS-1$ //$NON-NLS-2$
                    + blocking.size() + " object(s). Remove the references first, or call again with " //$NON-NLS-1$
                    + "force=true to delete anyway (the references will be left dangling).") //$NON-NLS-1$
                .put("action", "blocked") //$NON-NLS-1$ //$NON-NLS-2$
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("blockingReferences", blocking) //$NON-NLS-1$
                .put("blockingReferencesCount", blocking.size()) //$NON-NLS-1$
                .toJson();
        }

        try
        {
            refactoring.perform();
            ToolResult result = ToolResult.success()
                .put("action", "executed") //$NON-NLS-1$ //$NON-NLS-2$
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("forced", force); //$NON-NLS-1$
            if (!blocking.isEmpty())
            {
                result.put("danglingReferences", blocking) //$NON-NLS-1$
                    .put("message", "Delete refactoring completed (forced). " + blocking.size() //$NON-NLS-1$ //$NON-NLS-2$
                        + " incoming reference(s) were left dangling."); //$NON-NLS-1$
            }
            else
            {
                result.put("message", "Delete refactoring completed successfully."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error performing delete refactoring", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves a metadata object from its fully qualified name (FQN).
     * Uses {@link MetadataTypeUtils#findObject(Configuration, String, String)}
     * to locate the top-level object, then traverses nested metadata objects
     * via {@link #findChild(MdObject, String, String)} to resolve deeper paths.
     * Supports both top-level (e.g. 'Catalog.Products') and nested objects
     * (e.g. 'Document.SalesOrder.Attribute.Amount').
     */
    private MdObject resolveObject(Configuration config, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }

        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }

        // Find top-level object
        MdObject topObject = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (topObject == null || parts.length == 2)
        {
            return topObject;
        }

        // Navigate nested path
        MdObject current = topObject;
        for (int i = 2; i + 1 < parts.length; i += 2)
        {
            String childType = parts[i];
            String childName = parts[i + 1];
            MdObject child = findChild(current, childType, childName);
            if (child == null)
            {
                return null;
            }
            current = child;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private MdObject findChild(MdObject parent, String childType, String childName)
    {
        String type = childType.toLowerCase();

        String getterName = null;
        if ("attribute".equals(type) || "attributes".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0440\u0435\u043a\u0432\u0438\u0437\u0438\u0442".equals(type) //$NON-NLS-1$
            || "\u0440\u0435\u043a\u0432\u0438\u0437\u0438\u0442\u044b".equals(type)) //$NON-NLS-1$
        {
            getterName = "getAttributes"; //$NON-NLS-1$
        }
        else if ("tabularsection".equals(type) || "tabularsections".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0442\u0430\u0431\u043b\u0438\u0447\u043d\u0430\u044f\u0447\u0430\u0441\u0442\u044c".equals(type) //$NON-NLS-1$
            || "\u0442\u0430\u0431\u043b\u0438\u0447\u043d\u044b\u0435\u0447\u0430\u0441\u0442\u0438".equals(type)) //$NON-NLS-1$
        {
            getterName = "getTabularSections"; //$NON-NLS-1$
        }
        else if ("dimension".equals(type) || "dimensions".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0438\u0437\u043c\u0435\u0440\u0435\u043d\u0438\u0435".equals(type) //$NON-NLS-1$
            || "\u0438\u0437\u043c\u0435\u0440\u0435\u043d\u0438\u044f".equals(type)) //$NON-NLS-1$
        {
            getterName = "getDimensions"; //$NON-NLS-1$
        }
        else if ("resource".equals(type) || "resources".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0440\u0435\u0441\u0443\u0440\u0441".equals(type) //$NON-NLS-1$
            || "\u0440\u0435\u0441\u0443\u0440\u0441\u044b".equals(type)) //$NON-NLS-1$
        {
            getterName = "getResources"; //$NON-NLS-1$
        }

        if (getterName == null)
        {
            return null;
        }

        try
        {
            java.lang.reflect.Method method = parent.getClass().getMethod(getterName);
            Object result = method.invoke(parent);
            if (result instanceof org.eclipse.emf.common.util.EList)
            {
                org.eclipse.emf.common.util.EList<? extends MdObject> children =
                    (org.eclipse.emf.common.util.EList<? extends MdObject>) result;
                for (MdObject child : children)
                {
                    if (childName.equalsIgnoreCase(child.getName()))
                    {
                        return child;
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error finding child " + childType + "." + childName, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }
}
