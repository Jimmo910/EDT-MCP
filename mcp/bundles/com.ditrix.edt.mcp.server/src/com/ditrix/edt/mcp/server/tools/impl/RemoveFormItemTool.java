/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.DataItem;
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
 * dangling <em>reference</em> to the removed object is cleared in the same step: a
 * {@code Button} that pointed at a removed command has its
 * {@code commandName} reset, event handlers and {@code dataPath} bindings that
 * live inside a removed item's subtree are removed with it, etc. As an extra
 * safety net (because {@code commandName} is a non-containment cross-reference
 * held by buttons), any button still referencing a removed command is cleared
 * explicitly before the delete.
 * <p>
 * Removing an <b>attribute</b> needs one more step: a {@code FormField}/
 * {@code DataItem} binds to an attribute through its {@code dataPath}, whose
 * segments are plain strings rather than EMF references, so
 * {@link EcoreUtil#delete} does not scrub them. Before deleting an attribute the
 * tool therefore clears the {@code dataPath} of every item whose first segment is
 * the attribute's name and reports those items in {@code clearedDataPathBindings}.
 * Together these keep {@code get_project_errors} clean afterwards.
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
               "is unbound, event handlers and dataPath bindings inside a removed item are removed with it); " + //$NON-NLS-1$
               "removing an attribute also clears the dataPath of any field still bound to it " + //$NON-NLS-1$
               "(reported in clearedDataPathBindings), so get_project_errors stays clean. " + //$NON-NLS-1$
               "The change is written to the Form.form file on disk. " + //$NON-NLS-1$
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
        final List<String> clearedBindings = new ArrayList<>();
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
                    removedKind.set(removeElement(form, itemNameFinal, kind, clearedBindings));
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
        if (!clearedBindings.isEmpty())
        {
            // Report the items whose dataPath bound to the removed attribute was
            // cleared, so the caller knows which fields are now unbound.
            result.put("clearedDataPathBindings", clearedBindings); //$NON-NLS-1$
            message += " Cleared the data-path binding of " + clearedBindings.size() //$NON-NLS-1$
                + " item(s) that referenced the removed attribute (" //$NON-NLS-1$
                + String.join(", ", clearedBindings) + ")."; //$NON-NLS-1$ //$NON-NLS-2$
        }
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
     * @param clearedBindings out-param collecting the names of items whose
     *            {@code dataPath} binding to a removed attribute was cleared
     * @return the kind of the element that was removed
     */
    private String removeElement(Form form, String name, String kind, List<String> clearedBindings)
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
                // A FormField/DataItem binds to an attribute through its dataPath,
                // whose first segment is the attribute name. dataPath segments are
                // plain strings, not EMF references, so EcoreUtil.delete does NOT
                // scrub them: the field would be left pointing at a now-missing
                // attribute and get_project_errors would report an unresolved data
                // path. Clear those bindings explicitly before deleting.
                clearDataPathBindings(form, attribute.getName(), clearedBindings);
                EcoreUtil.delete(attribute, true);
                return KIND_ATTRIBUTE;
            }
        }

        throw new RuntimeException(notFoundMessage(name, kind));
    }

    /**
     * Clears the {@code dataPath} of every {@link DataItem} in the form whose first
     * segment matches the removed attribute's name, so no item is left bound to a
     * deleted attribute. The names of the affected items are collected into
     * {@code clearedBindings}.
     *
     * @param form the content form
     * @param attributeName the name of the attribute being removed
     * @param clearedBindings out-param collecting the cleared items' names
     */
    private static void clearDataPathBindings(Form form, String attributeName, List<String> clearedBindings)
    {
        TreeIterator<EObject> all = form.eAllContents();
        while (all.hasNext())
        {
            EObject obj = all.next();
            if (!(obj instanceof DataItem))
            {
                continue;
            }
            DataItem dataItem = (DataItem)obj;
            AbstractDataPath dataPath = dataItem.getDataPath();
            if (dataPath == null || dataPath.getSegments().isEmpty())
            {
                continue;
            }
            String firstSegment = dataPath.getSegments().get(0);
            if (attributeName.equalsIgnoreCase(firstSegment))
            {
                // Drop the binding entirely; an unset dataPath is valid (the field
                // simply becomes unbound) whereas a dangling one is an error.
                dataItem.setDataPath(null);
                // DataItem extends FormItem, so it always has a name.
                String itemName = dataItem.getName();
                clearedBindings.add(itemName != null && !itemName.isEmpty() ? itemName : firstSegment);
            }
        }
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
     * Searches a form's item tree for an item with the given name that also
     * matches the optional kind filter, failing on ambiguity.
     * <p>
     * Item names are not unique across the whole tree, so a first-match lookup
     * could silently remove the wrong element. This collects every match and
     * throws a {@link RuntimeException} listing the candidates (with their parents)
     * when more than one matches; a single match is returned, and {@code null}
     * when none matches.
     *
     * @param container the container to search (form root or a group)
     * @param name the item name to look up (case-insensitive)
     * @param kind the kind filter ('field' / 'group'), or {@code null} for any
     * @return the unique matching {@link FormItem}, or {@code null} when none
     * @throws RuntimeException when the name is ambiguous
     */
    private static FormItem findItem(FormItemContainer container, String name, String kind)
    {
        List<FormItem> matches = new ArrayList<>();
        collectMatchingItems(container, name, kind, matches);
        if (matches.isEmpty())
        {
            return null;
        }
        if (matches.size() > 1)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Ambiguous form item name '").append(name).append("': ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(matches.size()).append(" elements match. Candidates (with parent): "); //$NON-NLS-1$
            for (int i = 0; i < matches.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                FormItem item = matches.get(i);
                sb.append('\'').append(item.getName()).append("' in ").append(parentLabel(item)); //$NON-NLS-1$
            }
            sb.append(". Pass itemKind to narrow, or rename so the name is unique."); //$NON-NLS-1$
            throw new RuntimeException(sb.toString());
        }
        return matches.get(0);
    }

    private static void collectMatchingItems(FormItemContainer container, String name, String kind,
        List<FormItem> out)
    {
        for (FormItem item : container.getItems())
        {
            if (name.equalsIgnoreCase(item.getName()) && kindMatchesItem(item, kind))
            {
                out.add(item);
            }
            if (item instanceof FormItemContainer)
            {
                collectMatchingItems((FormItemContainer)item, name, kind, out);
            }
        }
    }

    private static String parentLabel(FormItem item)
    {
        EObject parent = item.eContainer();
        if (parent instanceof Form)
        {
            return "the form root"; //$NON-NLS-1$
        }
        if (parent instanceof FormItem)
        {
            return "'" + ((FormItem)parent).getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "the form"; //$NON-NLS-1$
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
