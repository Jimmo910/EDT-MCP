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
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tool to add a form command ({@link FormCommand}) to a managed form.
 * <p>
 * The command is created with a name and a unique id. To make it actually run a
 * procedure, bind a handler afterwards with {@code set_form_command_handler}.
 */
public class AddFormCommandTool extends AbstractFormWriteTool
{
    public static final String NAME = "add_form_command"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add a form command (FormCommand) to a managed form. " + //$NON-NLS-1$
               "Creates the command with a unique id; bind its handler procedure afterwards with " + //$NON-NLS-1$
               "set_form_command_handler. " + //$NON-NLS-1$
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
            .stringProperty("commandName", //$NON-NLS-1$
                "Name for the new form command (required). Must be a valid 1C identifier.", true) //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required.").toJson(); //$NON-NLS-1$
        }
        if (formFqn == null || formFqn.isEmpty())
        {
            return ToolResult.error("formFqn is required. Example: 'Catalog.Products.Form.ItemForm'.").toJson(); //$NON-NLS-1$
        }
        if (commandName == null || commandName.isEmpty())
        {
            return ToolResult.error("commandName is required.").toJson(); //$NON-NLS-1$
        }
        if (!FormToolSupport.isValidIdentifier(commandName))
        {
            return ToolResult.error("Invalid commandName '" + commandName + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
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
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("AddFormCommand") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Form form = (Form)tx.getObjectById(formBmId);
                    if (form == null)
                    {
                        throw new RuntimeException("Form not found in transaction"); //$NON-NLS-1$
                    }
                    for (FormCommand existing : form.getFormCommands())
                    {
                        if (commandName.equalsIgnoreCase(existing.getName()))
                        {
                            throw new RuntimeException("Form command already exists: " + commandName); //$NON-NLS-1$
                        }
                    }

                    FormCommand command = FormFactory.eINSTANCE.createFormCommand();
                    command.setName(commandName);
                    command.setId(nextItemId(form));
                    form.getFormCommands().add(command);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding form command", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add form command: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("formFqn", FormToolSupport.formFqn(location)) //$NON-NLS-1$
            .put("commandName", commandName) //$NON-NLS-1$
            .put("message", "Form command '" + commandName + "' added. " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Bind its handler with set_form_command_handler. Run get_project_errors to verify.") //$NON-NLS-1$
            .toJson();
    }
}
