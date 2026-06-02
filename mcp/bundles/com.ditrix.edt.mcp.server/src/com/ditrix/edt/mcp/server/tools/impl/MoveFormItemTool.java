/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tool to move or reorder a form item within a managed form.
 * <p>
 * The item (a field or group, looked up by name anywhere in the item tree) can
 * be:
 * <ul>
 * <li><b>re-parented</b> into another group (give {@code targetGroup}), or back
 * to the form root (give an empty/omitted {@code targetGroup} together with a
 * {@code position}, or {@code targetGroup} equal to the form name);</li>
 * <li><b>reordered</b> among its siblings (give {@code position}).</li>
 * </ul>
 * {@code position} accepts an integer index (0-based), {@code 'first'},
 * {@code 'last'}, {@code 'before:<name>'} or {@code 'after:<name>'} where
 * {@code <name>} is a sibling in the destination container. When both
 * {@code targetGroup} and {@code position} are omitted the call is a no-op error
 * (nothing to do).
 * <p>
 * A group cannot be moved into itself or one of its own descendants. The change
 * is persisted to the workspace {@code Form.form} file (see
 * {@link AbstractFormWriteTool#persistForm}).
 */
public class MoveFormItemTool extends AbstractFormWriteTool
{
    public static final String NAME = "move_form_item"; //$NON-NLS-1$

    private static final String POS_FIRST = "first"; //$NON-NLS-1$
    private static final String POS_LAST = "last"; //$NON-NLS-1$
    private static final String POS_BEFORE = "before:"; //$NON-NLS-1$
    private static final String POS_AFTER = "after:"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Move or reorder a form item (field or group) within a managed form. " + //$NON-NLS-1$
               "Re-parent it into another group (targetGroup) or back to the form root, and/or reorder it " + //$NON-NLS-1$
               "among its siblings (position). position accepts an integer index (0-based), 'first', 'last', " + //$NON-NLS-1$
               "'before:<siblingName>' or 'after:<siblingName>'. " + //$NON-NLS-1$
               "Omit targetGroup to reorder in place; set it to a group name to move into that group, " + //$NON-NLS-1$
               "or to the form name to move to the form root. A group cannot be moved into itself or a descendant. " + //$NON-NLS-1$
               "The change is written to the Form.form file on disk. " + //$NON-NLS-1$
               "Clear errors are returned when the item or target group does not exist. " + //$NON-NLS-1$
               "formFqn format: 'OwnerType.OwnerName.Form.FormName'. Russian type names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("formFqn", //$NON-NLS-1$
                "FQN of the form: 'OwnerType.OwnerName.Form.FormName' " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products.Form.ItemForm'). Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("itemName", //$NON-NLS-1$
                "Name of the form item (field or group) to move (required).", true) //$NON-NLS-1$
            .stringProperty("targetGroup", //$NON-NLS-1$
                "Optional name of an existing group to move the item into. " + //$NON-NLS-1$
                "Set to the form name (or omit while giving a position) to move the item to the form root.") //$NON-NLS-1$
            .stringProperty("position", //$NON-NLS-1$
                "Optional position in the destination container: an integer index (0-based), " + //$NON-NLS-1$
                "'first', 'last', 'before:<siblingName>' or 'after:<siblingName>'. " + //$NON-NLS-1$
                "When omitted, a moved item is appended to the end of the target group.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "itemName"); //$NON-NLS-1$
        String targetGroup = JsonUtils.extractStringArgument(params, "targetGroup"); //$NON-NLS-1$
        String position = JsonUtils.extractStringArgument(params, "position"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required.").toJson(); //$NON-NLS-1$
        }
        if (formFqn == null || formFqn.isEmpty())
        {
            return ToolResult.error("formFqn is required. Example: 'Catalog.Products.Form.ItemForm'.").toJson(); //$NON-NLS-1$
        }
        if (itemName == null || itemName.isEmpty())
        {
            return ToolResult.error("itemName is required.").toJson(); //$NON-NLS-1$
        }
        boolean hasTargetGroup = targetGroup != null && !targetGroup.isEmpty();
        boolean hasPosition = position != null && !position.isEmpty();
        if (!hasTargetGroup && !hasPosition)
        {
            return ToolResult.error("Nothing to do: provide targetGroup (to re-parent) " + //$NON-NLS-1$
                "and/or position (to reorder).").toJson(); //$NON-NLS-1$
        }

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        Configuration config = ctx.config;

        FormLocation location = resolveForm(config, formFqn);
        String locationError = FormToolSupport.checkFormLocation(location, formFqn);
        if (locationError != null)
        {
            return locationError;
        }

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        final long formBmId = bmIdOf(location.content);
        final String itemNameFinal = itemName;
        final String targetGroupFinal = targetGroup;
        final String positionFinal = position;
        final String mdFormName = location.mdForm.getName();
        final AtomicReference<String> destDescription = new AtomicReference<>();
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("MoveFormItem") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Form form = (Form)tx.getObjectById(formBmId);
                    if (form == null)
                    {
                        throw new RuntimeException("Form not found in transaction"); //$NON-NLS-1$
                    }
                    destDescription.set(moveItem(form, itemNameFinal, targetGroupFinal, positionFinal, mdFormName));
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error moving form item", e); //$NON-NLS-1$
            return ToolResult.error("Failed to move form item: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        String persistWarning = persistForm(ctx.project, contentFormFqn(location.content));

        ToolResult result = ToolResult.success()
            .put("formFqn", FormToolSupport.formFqn(location)) //$NON-NLS-1$
            .put("itemName", itemName) //$NON-NLS-1$
            .put("destination", destDescription.get()); //$NON-NLS-1$
        String message = "Moved form item '" + itemName + "' to " + destDescription.get() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ". The Form.form file was updated on disk."; //$NON-NLS-1$
        if (persistWarning != null)
        {
            message += " Warning: the on-disk export could not be forced (" + persistWarning //$NON-NLS-1$
                + "); the change is committed in the model and will be written by EDT shortly."; //$NON-NLS-1$
            result.put("persistWarning", persistWarning); //$NON-NLS-1$
        }
        return result
            .put("message", message + " Run get_project_errors / get_form_layout_snapshot to verify.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Performs the move inside the transaction. Throws a {@link RuntimeException}
     * with a user-facing message on any resolution problem.
     *
     * @param form the content form
     * @param itemName the item to move
     * @param targetGroup the destination group name, or {@code null}/form name for root
     * @param position the requested position, or {@code null}
     * @param mdFormName the MD-form name (matching it as targetGroup means "root")
     * @return a human-readable description of where the item ended up
     */
    private String moveItem(Form form, String itemName, String targetGroup, String position, String mdFormName)
    {
        FormItem item = findItem(form, itemName);
        if (item == null)
        {
            throw new RuntimeException("Form item not found: '" + itemName //$NON-NLS-1$
                + "'. Use get_form_layout_snapshot to inspect the form."); //$NON-NLS-1$
        }

        FormItemContainer sourceContainer = (FormItemContainer)item.eContainer();
        if (sourceContainer == null)
        {
            throw new RuntimeException("Form item '" + itemName //$NON-NLS-1$
                + "' has no parent container and cannot be moved."); //$NON-NLS-1$
        }

        // Resolve the destination container.
        FormItemContainer destContainer;
        String destLabel;
        boolean reparent = targetGroup != null && !targetGroup.isEmpty();
        if (reparent && !targetGroup.equalsIgnoreCase(mdFormName))
        {
            FormGroup group = findGroup(form, targetGroup);
            if (group == null)
            {
                throw new RuntimeException("Target group not found: '" + targetGroup //$NON-NLS-1$
                    + "'. Use get_form_layout_snapshot to inspect the form's groups."); //$NON-NLS-1$
            }
            if (group == item || isDescendant(item, group))
            {
                throw new RuntimeException("Cannot move group '" + itemName //$NON-NLS-1$
                    + "' into itself or one of its own descendants."); //$NON-NLS-1$
            }
            destContainer = group;
            destLabel = "group '" + group.getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (reparent)
        {
            // targetGroup equals the form name -> form root.
            destContainer = form;
            destLabel = "the form root"; //$NON-NLS-1$
        }
        else
        {
            // No targetGroup: reorder within the current container.
            destContainer = sourceContainer;
            destLabel = sameContainerLabel(sourceContainer, form);
        }

        EList<FormItem> destItems = destContainer.getItems();

        // Remove from the source list first (it may be the same list).
        sourceContainer.getItems().remove(item);

        int index = resolvePosition(position, destItems, itemName);
        if (index < 0 || index > destItems.size())
        {
            index = destItems.size();
        }
        destItems.add(index, item);

        return destLabel + " at index " + index; //$NON-NLS-1$
    }

    private String sameContainerLabel(FormItemContainer container, Form form)
    {
        if (container == form)
        {
            return "the form root"; //$NON-NLS-1$
        }
        if (container instanceof FormGroup)
        {
            return "group '" + ((FormGroup)container).getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "its container"; //$NON-NLS-1$
    }

    /**
     * Resolves a requested position into a 0-based insertion index in the
     * destination list (which already excludes the moved item).
     *
     * @param position the requested position spec, or {@code null} for "last"
     * @param destItems the destination items (without the moved item)
     * @param movedName the moved item name (for diagnostics)
     * @return the insertion index
     */
    private int resolvePosition(String position, EList<FormItem> destItems, String movedName)
    {
        if (position == null || position.isEmpty() || POS_LAST.equalsIgnoreCase(position))
        {
            return destItems.size();
        }
        if (POS_FIRST.equalsIgnoreCase(position))
        {
            return 0;
        }
        String lower = position.toLowerCase(Locale.ROOT);
        if (lower.startsWith(POS_BEFORE))
        {
            String sibling = position.substring(POS_BEFORE.length()).trim();
            int idx = indexOfSibling(destItems, sibling, movedName);
            return idx;
        }
        if (lower.startsWith(POS_AFTER))
        {
            String sibling = position.substring(POS_AFTER.length()).trim();
            int idx = indexOfSibling(destItems, sibling, movedName);
            return idx + 1;
        }
        try
        {
            int idx = Integer.parseInt(position.trim());
            if (idx < 0)
            {
                throw new RuntimeException("Invalid position index '" + position //$NON-NLS-1$
                    + "': must be zero or positive."); //$NON-NLS-1$
            }
            return idx;
        }
        catch (NumberFormatException e)
        {
            throw new RuntimeException("Invalid position '" + position //$NON-NLS-1$
                + "'. Expected an integer index, 'first', 'last', 'before:<name>' or 'after:<name>'."); //$NON-NLS-1$
        }
    }

    private int indexOfSibling(EList<FormItem> destItems, String sibling, String movedName)
    {
        if (sibling.isEmpty())
        {
            throw new RuntimeException("Position reference is missing a sibling name " //$NON-NLS-1$
                + "(use 'before:<name>' or 'after:<name>')."); //$NON-NLS-1$
        }
        if (sibling.equalsIgnoreCase(movedName))
        {
            throw new RuntimeException("Position cannot reference the moved item itself: '" //$NON-NLS-1$
                + sibling + "'."); //$NON-NLS-1$
        }
        for (int i = 0; i < destItems.size(); i++)
        {
            if (sibling.equalsIgnoreCase(destItems.get(i).getName()))
            {
                return i;
            }
        }
        throw new RuntimeException("Sibling '" + sibling //$NON-NLS-1$
            + "' not found in the destination container."); //$NON-NLS-1$
    }

    /**
     * Returns {@code true} when {@code candidate} is the same as, or nested
     * inside, {@code item} (used to reject moving a group into its own subtree).
     *
     * @param item the item being moved (a potential ancestor)
     * @param candidate the candidate destination
     * @return {@code true} when candidate is item or a descendant of item
     */
    private static boolean isDescendant(FormItem item, FormItem candidate)
    {
        org.eclipse.emf.ecore.EObject parent = candidate.eContainer();
        while (parent != null)
        {
            if (parent == item)
            {
                return true;
            }
            parent = parent.eContainer();
        }
        return false;
    }

    private static FormItem findItem(FormItemContainer container, String name)
    {
        for (FormItem item : container.getItems())
        {
            if (name.equalsIgnoreCase(item.getName()))
            {
                return item;
            }
            if (item instanceof FormItemContainer)
            {
                FormItem nested = findItem((FormItemContainer)item, name);
                if (nested != null)
                {
                    return nested;
                }
            }
        }
        return null;
    }

    private static FormGroup findGroup(FormItemContainer container, String name)
    {
        for (FormItem item : container.getItems())
        {
            if (item instanceof FormGroup)
            {
                if (name.equalsIgnoreCase(item.getName()))
                {
                    return (FormGroup)item;
                }
                FormGroup nested = findGroup((FormGroup)item, name);
                if (nested != null)
                {
                    return nested;
                }
            }
        }
        return null;
    }
}
