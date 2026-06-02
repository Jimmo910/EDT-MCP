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
import com._1c.g5.v8.dt.form.model.CommandHandler;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormCommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tool to bind a handler procedure to a form command.
 * <p>
 * Implements the reliable handler-binding path described in the form metamodel
 * docs: the command's action is set to a {@link FormCommandHandlerContainer}
 * holding a {@link CommandHandler} whose {@code name} is the procedure name in
 * the form module. The link to BSL is by name only; this tool writes the model
 * side. The matching {@code &AtClient Procedure <name>(Command)} must exist in
 * the form module (add it with {@code write_module_source}).
 */
public class SetFormCommandHandlerTool extends AbstractFormWriteTool
{
    public static final String NAME = "set_form_command_handler"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Bind a handler procedure to a form command (reliable command-handler path). " + //$NON-NLS-1$
               "Sets the command's action to a FormCommandHandlerContainer with a CommandHandler " + //$NON-NLS-1$
               "named after the handler procedure. The link to BSL is by name only, so a matching " + //$NON-NLS-1$
               "'&AtClient Procedure <handlerName>(Command)' must exist in the form module " + //$NON-NLS-1$
               "(add it with write_module_source). " + //$NON-NLS-1$
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
                "Name of the existing form command to bind (required).", true) //$NON-NLS-1$
            .stringProperty("handlerName", //$NON-NLS-1$
                "Name of the handler procedure in the form module (required). " + //$NON-NLS-1$
                "Must be a valid 1C identifier (commonly the same as the command name).", true) //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        String handlerName = JsonUtils.extractStringArgument(params, "handlerName"); //$NON-NLS-1$

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
        if (handlerName == null || handlerName.isEmpty())
        {
            return ToolResult.error("handlerName is required.").toJson(); //$NON-NLS-1$
        }
        if (!FormToolSupport.isValidIdentifier(handlerName))
        {
            return ToolResult.error("Invalid handlerName '" + handlerName + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
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
            bmModel.execute(new AbstractBmTask<Void>("SetFormCommandHandler") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Form form = (Form)tx.getObjectById(formBmId);
                    if (form == null)
                    {
                        throw new RuntimeException("Form not found in transaction"); //$NON-NLS-1$
                    }

                    FormCommand command = null;
                    for (FormCommand existing : form.getFormCommands())
                    {
                        if (commandName.equalsIgnoreCase(existing.getName()))
                        {
                            command = existing;
                            break;
                        }
                    }
                    if (command == null)
                    {
                        throw new RuntimeException("Form command not found: " + commandName //$NON-NLS-1$
                            + ". Create it first with add_form_command."); //$NON-NLS-1$
                    }

                    CommandHandler handler = FormFactory.eINSTANCE.createCommandHandler();
                    handler.setName(handlerName);

                    FormCommandHandlerContainer container =
                        FormFactory.eINSTANCE.createFormCommandHandlerContainer();
                    container.setHandler(handler);

                    command.setAction(container);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error setting form command handler", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set form command handler: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        String formModulePath = FormToolSupport.formFqn(location) + ".Module"; //$NON-NLS-1$
        return ToolResult.success()
            .put("formFqn", FormToolSupport.formFqn(location)) //$NON-NLS-1$
            .put("commandName", commandName) //$NON-NLS-1$
            .put("handlerName", handlerName) //$NON-NLS-1$
            .put("message", "Command '" + commandName + "' bound to handler '" + handlerName + "'. " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "Ensure '&AtClient Procedure " + handlerName + "(Command) ... EndProcedure' exists in the " + //$NON-NLS-1$ //$NON-NLS-2$
                "form module (" + formModulePath + "); add it with write_module_source. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Run get_project_errors to verify.") //$NON-NLS-1$
            .toJson();
    }
}
