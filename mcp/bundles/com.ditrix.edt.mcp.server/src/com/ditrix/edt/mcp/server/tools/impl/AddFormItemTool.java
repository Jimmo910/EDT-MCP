/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.ManagedFormFieldType;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;
import com._1c.g5.v8.dt.form.model.Visible;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tool to add an item (field or group) to a managed form.
 * <p>
 * Supports two item kinds:
 * <ul>
 * <li>{@code field} – a {@link FormField} of type {@code INPUT_FIELD} with an
 * {@code InputFieldExtInfo} and an optional {@code dataPath} binding (e.g.
 * {@code Object.Code});</li>
 * <li>{@code group} – a {@link FormGroup} of type {@code USUAL_GROUP} with a
 * {@code UsualGroupExtInfo} that can contain other items.</li>
 * </ul>
 * The item is appended either to the form root or, when {@code parentGroup} is
 * given, to an existing group with that name.
 * <p>
 * New items are initialized with the same visibility defaults the EDT form
 * editor applies through {@code FormObjectFactory} ({@code visible = true},
 * {@code enabled = true}, {@code userVisible.common = true}). Without these the
 * element is part of the form tree but is never rendered in the WYSIWYG editor
 * or at runtime.
 */
public class AddFormItemTool extends AbstractFormWriteTool
{
    public static final String NAME = "add_form_item"; //$NON-NLS-1$

    private static final String KIND_FIELD = "field"; //$NON-NLS-1$
    private static final String KIND_GROUP = "group"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add an item to a managed form: a 'field' (input field, optionally bound to data via " + //$NON-NLS-1$
               "dataPath like 'Object.Code') or a 'group' (usual group container). " + //$NON-NLS-1$
               "The item is appended to the form root, or to an existing group when 'parentGroup' is given. " + //$NON-NLS-1$
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
            .stringProperty("itemKind", //$NON-NLS-1$
                "Item kind (required): 'field' or 'group'.", true) //$NON-NLS-1$
            .stringProperty("itemName", //$NON-NLS-1$
                "Name for the new item (required). Must be a valid 1C identifier.", true) //$NON-NLS-1$
            .stringProperty("dataPath", //$NON-NLS-1$
                "For 'field' only: optional data path binding, dot-separated " + //$NON-NLS-1$
                "(e.g. 'Object.Code'). Each segment becomes one DataPath segment.") //$NON-NLS-1$
            .stringProperty("parentGroup", //$NON-NLS-1$
                "Optional name of an existing form group to place the item into. " + //$NON-NLS-1$
                "When omitted, the item is added to the form root.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemKind = JsonUtils.extractStringArgument(params, "itemKind"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "itemName"); //$NON-NLS-1$
        String dataPath = JsonUtils.extractStringArgument(params, "dataPath"); //$NON-NLS-1$
        String parentGroup = JsonUtils.extractStringArgument(params, "parentGroup"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required.").toJson(); //$NON-NLS-1$
        }
        if (formFqn == null || formFqn.isEmpty())
        {
            return ToolResult.error("formFqn is required. Example: 'Catalog.Products.Form.ItemForm'.").toJson(); //$NON-NLS-1$
        }
        if (itemKind == null || itemKind.isEmpty())
        {
            return ToolResult.error("itemKind is required: 'field' or 'group'.").toJson(); //$NON-NLS-1$
        }
        String kind = itemKind.toLowerCase(Locale.ROOT);
        if (!KIND_FIELD.equals(kind) && !KIND_GROUP.equals(kind))
        {
            return ToolResult.error("Invalid itemKind '" + itemKind + "'. Expected 'field' or 'group'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (itemName == null || itemName.isEmpty())
        {
            return ToolResult.error("itemName is required.").toJson(); //$NON-NLS-1$
        }
        if (!FormToolSupport.isValidIdentifier(itemName))
        {
            return ToolResult.error("Invalid itemName '" + itemName + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
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
        final String kindFinal = kind;
        final String dataPathFinal = dataPath;
        final String parentGroupFinal = parentGroup;
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("AddFormItem") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Form form = (Form)tx.getObjectById(formBmId);
                    if (form == null)
                    {
                        throw new RuntimeException("Form not found in transaction"); //$NON-NLS-1$
                    }

                    FormItemContainer container = form;
                    if (parentGroupFinal != null && !parentGroupFinal.isEmpty())
                    {
                        FormGroup group = findGroup(form, parentGroupFinal);
                        if (group == null)
                        {
                            throw new RuntimeException("Parent group not found: " + parentGroupFinal); //$NON-NLS-1$
                        }
                        container = group;
                    }

                    if (itemNameExists(form, itemName))
                    {
                        throw new RuntimeException("Form item already exists: " + itemName); //$NON-NLS-1$
                    }

                    FormItem newItem = KIND_GROUP.equals(kindFinal)
                        ? createGroup(form, itemName) : createField(form, itemName, dataPathFinal);
                    container.getItems().add(newItem);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding form item", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add form item: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the change to the Form.form file on disk so verification tools
        // (get_form_layout_snapshot / get_form_screenshot) and the user see it.
        String persistWarning = persistForm(ctx.project, contentFormFqn(location.content));

        ToolResult result = ToolResult.success()
            .put("formFqn", FormToolSupport.formFqn(location)) //$NON-NLS-1$
            .put("itemKind", kind) //$NON-NLS-1$
            .put("itemName", itemName); //$NON-NLS-1$
        String message = "Form item '" + itemName + "' (" + kind + ") added. " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "The Form.form file was updated on disk."; //$NON-NLS-1$
        if (persistWarning != null)
        {
            message += " Warning: the on-disk export could not be forced (" + persistWarning //$NON-NLS-1$
                + "); the change is committed in the model and will be written by EDT shortly."; //$NON-NLS-1$
            result.put("persistWarning", persistWarning); //$NON-NLS-1$
        }
        return result
            .put("message", message //$NON-NLS-1$
                + " Run get_project_errors to verify; get_form_layout_snapshot to inspect layout.") //$NON-NLS-1$
            .toJson();
    }

    private FormField createField(Form form, String name, String dataPath)
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName(name);
        field.setId(nextItemId(form));
        field.setType(ManagedFormFieldType.INPUT_FIELD);
        field.setExtInfo(FormFactory.eINSTANCE.createInputFieldExtInfo());
        // Apply the same rendering defaults the EDT form editor sets via
        // FormObjectFactory.newFormField; without them the field stays invisible.
        applyVisibilityDefaults(field);
        if (dataPath != null && !dataPath.isEmpty())
        {
            DataPath path = FormFactory.eINSTANCE.createDataPath();
            for (String segment : dataPath.split("\\.")) //$NON-NLS-1$
            {
                if (!segment.isEmpty())
                {
                    path.getSegments().add(segment);
                }
            }
            field.setDataPath(path);
        }
        return field;
    }

    private FormGroup createGroup(Form form, String name)
    {
        FormGroup group = FormFactory.eINSTANCE.createFormGroup();
        group.setName(name);
        group.setId(nextItemId(form));
        group.setType(ManagedFormGroupType.USUAL_GROUP);
        group.setExtInfo(FormFactory.eINSTANCE.createUsualGroupExtInfo());
        // Same rendering defaults as FormObjectFactory.newFormGroup; without them
        // the group (and anything placed in it) is not drawn on the form.
        applyVisibilityDefaults(group);
        return group;
    }

    /**
     * Applies the visibility/availability defaults the EDT form editor sets on a
     * freshly created form item. Mirrors {@code FormObjectFactory}: {@code visible}
     * and {@code enabled} become {@code true}, and {@code userVisible} is set to a
     * fresh {@link AdjustableBoolean} with {@code common = true}. Both
     * {@link FormField} and {@link FormGroup} implement {@link Visible}.
     *
     * @param item the newly created form item (field or group)
     */
    private void applyVisibilityDefaults(Visible item)
    {
        item.setEnabled(true);
        item.setVisible(true);
        item.setUserVisible(createDefaultUserVisible());
    }

    /**
     * Builds the default {@code userVisible} value for a form item: an
     * {@link AdjustableBoolean} that is commonly visible (no per-role overrides),
     * matching {@code FormObjectFactory.newAdjustableBoolean()}.
     *
     * @return a new {@link AdjustableBoolean} with {@code common = true}
     */
    private AdjustableBoolean createDefaultUserVisible()
    {
        AdjustableBoolean userVisible = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        userVisible.setCommon(true);
        return userVisible;
    }

    private FormGroup findGroup(FormItemContainer container, String name)
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

    private boolean itemNameExists(FormItemContainer container, String name)
    {
        for (FormItem item : container.getItems())
        {
            if (name.equalsIgnoreCase(item.getName()))
            {
                return true;
            }
            if (item instanceof FormItemContainer && itemNameExists((FormItemContainer)item, name))
            {
                return true;
            }
        }
        return false;
    }
}
