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
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tool to remove a named element from a managed form.
 * <p>
 * Removes one of the following, looked up by name across the form:
 * <ul>
 * <li>a form item ({@code field} / {@code group}) from the item tree;</li>
 * <li>a form command ({@code command}) from {@code getFormCommands()}; or</li>
 * <li>a form attribute ({@code attribute}) from {@code getAttributes()}.</li>
 * </ul>
 * When {@code itemKind} is omitted the tool searches all three collections (item,
 * command, attribute) and removes the first match, reporting which kind it was.
 * <p>
 * Removal is done with {@link EcoreUtil#delete(EObject, boolean)} so that every
 * dangling reference to the removed object is cleared in the same step: a
 * {@code Button} that pointed at a removed command has its
 * {@code commandName} reset, event handlers and {@code dataPath} bindings that
 * live inside a removed item's subtree are removed with it, etc. This keeps
 * {@code get_project_errors} clean afterwards. As an extra safety net (because
 * {@code commandName} is a non-containment cross-reference held by buttons), any
 * button still referencing a removed command is cleared explicitly before the
 * delete.
 * <p>
 * The change is persisted to the workspace {@code Form.form} file (see
 * {@link AbstractFormWriteTool#persistForm}). The operation is idempotent: when
 * the named element does not exist the tool returns a clear error without
 * crashing.
 */
public class RemoveFormItemTool extends AbstractFormWriteTool
{
    public static final String NAME = "remove_form_item"; //$NON-NLS-1$

    private static final String KIND_FIELD = "field"; //$NON-NLS-1$
    private static final String KIND_GROUP = "group"; //$NON-NLS-1$
    private static final String KIND_COMMAND = "command"; //$NON-NLS-1$
    private static final String KIND_ATTRIBUTE = "attribute"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Remove a named element from a managed form: a form item ('field' or 'group'), " + //$NON-NLS-1$
               "a form command ('command'), or a form attribute ('attribute'). " + //$NON-NLS-1$
               "Dangling references are cleaned up automatically (a button bound to a removed command " + //$NON-NLS-1$
               "is unbound, event handlers and dataPath bindings inside a removed item are removed with it), " + //$NON-NLS-1$
               "so get_project_errors stays clean. The change is written to the Form.form file on disk. " + //$NON-NLS-1$
               "Idempotent: returns a clear error if the element does not exist. " + //$NON-NLS-1$
               "Optional itemKind ('field'|'group'|'command'|'attribute') disambiguates name clashes; " + //$NON-NLS-1$
               "when omitted, items, commands and attributes are all searched. " + //$NON-NLS-1$
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
                "Name of the element to remove (required).", true) //$NON-NLS-1$
            .stringProperty("itemKind", //$NON-NLS-1$
                "Optional element kind to disambiguate: 'field', 'group', 'command' or 'attribute'. " + //$NON-NLS-1$
                "When omitted, items (fields/groups), commands and attributes are all searched.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "itemName"); //$NON-NLS-1$
        String itemKind = JsonUtils.extractStringArgument(params, "itemKind"); //$NON-NLS-1$

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

        final String kind;
        if (itemKind == null || itemKind.isEmpty())
        {
            kind = null;
        }
        else
        {
            kind = itemKind.toLowerCase(Locale.ROOT);
            if (!KIND_FIELD.equals(kind) && !KIND_GROUP.equals(kind)
                && !KIND_COMMAND.equals(kind) && !KIND_ATTRIBUTE.equals(kind))
            {
                return ToolResult.error("Invalid itemKind '" + itemKind //$NON-NLS-1$
                    + "'. Expected 'field', 'group', 'command' or 'attribute'.").toJson(); //$NON-NLS-1$
            }
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
        final AtomicReference<String> removedKind = new AtomicReference<>();
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("RemoveFormItem") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Form form = (Form)tx.getObjectById(formBmId);
                    if (form == null)
                    {
                        throw new RuntimeException("Form not found in transaction"); //$NON-NLS-1$
                    }
                    removedKind.set(removeElement(form, itemNameFinal, kind));
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error removing form element", e); //$NON-NLS-1$
            return ToolResult.error("Failed to remove form element: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the change to the Form.form file on disk so verification tools
        // (get_form_layout_snapshot / get_form_screenshot) and the user see it.
        String persistWarning = persistForm(ctx.project, contentFormFqn(location.content));

        ToolResult result = ToolResult.success()
            .put("formFqn", FormToolSupport.formFqn(location)) //$NON-NLS-1$
            .put("itemName", itemName) //$NON-NLS-1$
            .put("removedKind", removedKind.get()); //$NON-NLS-1$
        String message = "Removed " + removedKind.get() + " '" + itemName + "' from the form" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " and cleaned up references. The Form.form file was updated on disk."; //$NON-NLS-1$
        if (persistWarning != null)
        {
            message += " Warning: the on-disk export could not be forced (" + persistWarning //$NON-NLS-1$
                + "); the change is committed in the model and will be written by EDT shortly."; //$NON-NLS-1$
            result.put("persistWarning", persistWarning); //$NON-NLS-1$
        }
        return result
            .put("message", message + " Run get_project_errors to verify.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Removes the named element from the form, honoring an optional kind filter.
     * Throws a {@link RuntimeException} with a user-facing message when nothing
     * matches.
     *
     * @param form the content form (fetched inside the transaction)
     * @param name the element name to remove
     * @param kind the kind filter, or {@code null} to search all collections
     * @return the kind of the element that was removed
     */
    private String removeElement(Form form, String name, String kind)
    {
        if (kind == null || KIND_FIELD.equals(kind) || KIND_GROUP.equals(kind))
        {
            FormItem item = findItem(form, name, kind);
            if (item != null)
            {
                String actualKind = item instanceof FormItemContainer ? KIND_GROUP : KIND_FIELD;
                EcoreUtil.delete(item, true);
                return actualKind;
            }
        }

        if (kind == null || KIND_COMMAND.equals(kind))
        {
            FormCommand command = findCommand(form, name);
            if (command != null)
            {
                // commandName is a non-containment cross-reference: clear every
                // button that points at this command before deleting it.
                clearButtonsReferencing(form, command);
                EcoreUtil.delete(command, true);
                return KIND_COMMAND;
            }
        }

        if (kind == null || KIND_ATTRIBUTE.equals(kind))
        {
            FormAttribute attribute = findAttribute(form, name);
            if (attribute != null)
            {
                EcoreUtil.delete(attribute, true);
                return KIND_ATTRIBUTE;
            }
        }

        throw new RuntimeException(notFoundMessage(name, kind));
    }

    private static boolean kindMatchesItem(FormItem item, String kind)
    {
        if (kind == null)
        {
            return true;
        }
        if (KIND_GROUP.equals(kind))
        {
            return item instanceof FormItemContainer;
        }
        // KIND_FIELD: anything that is not a container counts as a "field" here
        // (input fields, labels, buttons, tables, etc.).
        return !(item instanceof FormItemContainer);
    }

    private String notFoundMessage(String name, String kind)
    {
        if (kind == null)
        {
            return "Form element not found: '" + name //$NON-NLS-1$
                + "'. No item, command or attribute with this name exists. " //$NON-NLS-1$
                + "Use get_form_layout_snapshot to inspect the form."; //$NON-NLS-1$
        }
        return "Form " + kind + " not found: '" + name //$NON-NLS-1$ //$NON-NLS-2$
            + "'. Use get_form_layout_snapshot to inspect the form, " //$NON-NLS-1$
            + "or omit itemKind to search all element kinds."; //$NON-NLS-1$
    }

    /**
     * Clears every {@link Button} whose command reference points at the given
     * command, so deleting the command leaves no dangling binding.
     *
     * @param container the container to scan (form root or a group)
     * @param command the command being removed
     */
    private void clearButtonsReferencing(FormItemContainer container, FormCommand command)
    {
        for (FormItem item : container.getItems())
        {
            if (item instanceof Button && ((Button)item).getCommandName() == command)
            {
                ((Button)item).setCommandName(null);
            }
            if (item instanceof FormItemContainer)
            {
                clearButtonsReferencing((FormItemContainer)item, command);
            }
        }
    }

    /**
     * Recursively searches a form's item tree for an item with the given name
     * that also matches the optional kind filter.
     *
     * @param container the container to search (form root or a group)
     * @param name the item name to look up (case-insensitive)
     * @param kind the kind filter ('field' / 'group'), or {@code null} for any
     * @return the matching {@link FormItem}, or {@code null}
     */
    private static FormItem findItem(FormItemContainer container, String name, String kind)
    {
        for (FormItem item : container.getItems())
        {
            if (name.equalsIgnoreCase(item.getName()) && kindMatchesItem(item, kind))
            {
                return item;
            }
            if (item instanceof FormItemContainer)
            {
                FormItem nested = findItem((FormItemContainer)item, name, kind);
                if (nested != null)
                {
                    return nested;
                }
            }
        }
        return null;
    }

    private static FormCommand findCommand(Form form, String name)
    {
        for (FormCommand command : form.getFormCommands())
        {
            if (name.equalsIgnoreCase(command.getName()))
            {
                return command;
            }
        }
        return null;
    }

    private static FormAttribute findAttribute(Form form, String name)
    {
        for (FormAttribute attribute : form.getAttributes())
        {
            if (name.equalsIgnoreCase(attribute.getName()))
            {
                return attribute;
            }
        }
        return null;
    }
}
