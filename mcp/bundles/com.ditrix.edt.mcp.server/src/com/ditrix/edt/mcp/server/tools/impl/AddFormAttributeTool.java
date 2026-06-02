/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tool to add a form attribute ({@link FormAttribute}) to a managed form.
 * <p>
 * The attribute is created with a name, a unique id and (optionally) the
 * {@code main} flag. The value type is intentionally left unset: building a
 * valid mcore {@code TypeDescription} requires the type registry and is the
 * single most version-fragile area of the form model, so it is delegated to the
 * form editor. An untyped attribute is a valid model state.
 */
public class AddFormAttributeTool extends AbstractFormWriteTool
{
    public static final String NAME = "add_form_attribute"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add a form attribute (data source) to a managed form. " + //$NON-NLS-1$
               "Creates a FormAttribute with a unique id and optional 'main' flag. " + //$NON-NLS-1$
               "The value type is not set by this tool (set it in the form editor); an untyped " + //$NON-NLS-1$
               "attribute is a valid intermediate state. " + //$NON-NLS-1$
               "formFqn format: 'OwnerType.OwnerName.Form.FormName' (e.g. 'Catalog.Products.Form.ItemForm'). " + //$NON-NLS-1$
               "Russian type names are also supported."; //$NON-NLS-1$
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
            .stringProperty("attributeName", //$NON-NLS-1$
                "Name for the new form attribute (required). Must be a valid 1C identifier.", true) //$NON-NLS-1$
            .booleanProperty("main", //$NON-NLS-1$
                "When true, marks the attribute as the form's main attribute (default: false).") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        boolean main = JsonUtils.extractBooleanArgument(params, "main", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required.").toJson(); //$NON-NLS-1$
        }
        if (formFqn == null || formFqn.isEmpty())
        {
            return ToolResult.error("formFqn is required. " + //$NON-NLS-1$
                "Example: 'Catalog.Products.Form.ItemForm'.").toJson(); //$NON-NLS-1$
        }
        if (attributeName == null || attributeName.isEmpty())
        {
            return ToolResult.error("attributeName is required.").toJson(); //$NON-NLS-1$
        }
        if (!FormToolSupport.isValidIdentifier(attributeName))
        {
            return ToolResult.error("Invalid attributeName '" + attributeName + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
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
        final boolean mainFlag = main;
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("AddFormAttribute") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Form form = (Form)tx.getObjectById(formBmId);
                    if (form == null)
                    {
                        throw new RuntimeException("Form not found in transaction"); //$NON-NLS-1$
                    }
                    for (FormAttribute existing : form.getAttributes())
                    {
                        if (attributeName.equalsIgnoreCase(existing.getName()))
                        {
                            throw new RuntimeException("Form attribute already exists: " + attributeName); //$NON-NLS-1$
                        }
                    }

                    FormAttribute attr = FormFactory.eINSTANCE.createFormAttribute();
                    attr.setName(attributeName);
                    attr.setId(nextItemId(form));
                    attr.setMain(mainFlag);
                    form.getAttributes().add(attr);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding form attribute", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add form attribute: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("formFqn", FormToolSupport.formFqn(location)) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("main", main) //$NON-NLS-1$
            .put("message", "Form attribute '" + attributeName + "' added. " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Set its value type in the form editor. Run get_project_errors to verify.") //$NON-NLS-1$
            .toJson();
    }
}
