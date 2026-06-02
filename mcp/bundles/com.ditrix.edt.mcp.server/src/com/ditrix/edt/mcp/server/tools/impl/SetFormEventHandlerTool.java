/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.EventHandler;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tool to bind a handler procedure to an event of a form element (field, table)
 * or of the form itself.
 * <p>
 * Unlike a form command (which uses a {@link com._1c.g5.v8.dt.form.model.CommandHandler}
 * with no event), an element event is modeled as an {@link EventHandler} that
 * carries both the handler procedure name ({@code setName}) and the concrete
 * mcore {@link Event} it reacts to ({@code setEvent}). The event object cannot be
 * fabricated: it must be one of the events the platform defines for that element
 * type. This tool obtains the valid event set the same way the form editor's
 * property palette does — through
 * {@link FormItemInformationService#getAllowedEvents(FormVisualEntity)} — and
 * matches the requested {@code event} against it by English or Russian name. If
 * no event matches, the error lists the events available for that element.
 * <p>
 * The handler is added to the element's {@link EventHandlerContainer#getHandlers()}.
 * The link to BSL is by name only, so a matching client procedure with the
 * event's signature must exist in the form module (add it with
 * {@code write_module_source}).
 * <p>
 * Buttons are <em>not</em> event-handler containers: a button reacts through a
 * form command. To wire a button, create a command with {@code add_form_command}
 * and bind it with {@code set_form_command_handler}.
 */
public class SetFormEventHandlerTool extends AbstractFormWriteTool
{
    public static final String NAME = "set_form_event_handler"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Bind a handler procedure to an event of a form element (field/table) or of the form itself. " + //$NON-NLS-1$
               "Creates an EventHandler with the procedure name and the element's mcore Event " + //$NON-NLS-1$
               "(e.g. 'OnChange' for a field), resolved from the element's valid event set. " + //$NON-NLS-1$
               "'event' is the event name (English or Russian, e.g. 'OnChange' / 'ПриИзменении'). " + //$NON-NLS-1$
               "To target the form's own events (e.g. 'OnOpen'), set itemName to the form name or omit it. " + //$NON-NLS-1$
               "If the event does not exist for the element, the error lists the available events. " + //$NON-NLS-1$
               "The link to BSL is by name only, so a matching '&AtClient Procedure <handlerName>(...)' " + //$NON-NLS-1$
               "with the event's signature must exist in the form module (add it with write_module_source). " + //$NON-NLS-1$
               "Buttons use commands instead: see add_form_command / set_form_command_handler. " + //$NON-NLS-1$
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
                "Name of the form element (field or table) whose event to bind. " + //$NON-NLS-1$
                "Set to the form name or omit to bind a form-level event (e.g. 'OnOpen').") //$NON-NLS-1$
            .stringProperty("event", //$NON-NLS-1$
                "Event name to bind, English or Russian (e.g. 'OnChange', 'StartChoice', " + //$NON-NLS-1$
                "'OnOpen'). Must be one of the element's valid events.", true) //$NON-NLS-1$
            .stringProperty("handlerName", //$NON-NLS-1$
                "Name of the handler procedure in the form module (required). " + //$NON-NLS-1$
                "Must be a valid 1C identifier.", true) //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String itemName = JsonUtils.extractStringArgument(params, "itemName"); //$NON-NLS-1$
        String event = JsonUtils.extractStringArgument(params, "event"); //$NON-NLS-1$
        String handlerName = JsonUtils.extractStringArgument(params, "handlerName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required.").toJson(); //$NON-NLS-1$
        }
        if (formFqn == null || formFqn.isEmpty())
        {
            return ToolResult.error("formFqn is required. Example: 'Catalog.Products.Form.ItemForm'.").toJson(); //$NON-NLS-1$
        }
        if (event == null || event.isEmpty())
        {
            return ToolResult.error("event is required. Example: 'OnChange'.").toJson(); //$NON-NLS-1$
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

        FormItemInformationService infoService = Activator.getDefault().getFormItemInformationService();
        if (infoService == null)
        {
            String diagnostic = Activator.getDefault().getFormItemInformationServiceDiagnostic();
            return ToolResult.error("FormItemInformationService not available; cannot resolve element events. " //$NON-NLS-1$
                + "Cause: " + (diagnostic != null ? diagnostic : "unknown (see EDT error log)") + ".") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .toJson();
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

        // When itemName equals the form name (or is blank), target the form itself.
        final String mdFormName = location.mdForm.getName();
        final boolean targetForm = itemName == null || itemName.isEmpty()
            || itemName.equalsIgnoreCase(mdFormName);
        final String itemNameFinal = itemName;
        final String eventFinal = event;
        final String handlerNameFinal = handlerName;
        final long formBmId = bmIdOf(location.content);

        String[] resolvedEventName = new String[1];
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("SetFormEventHandler") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Form form = (Form)tx.getObjectById(formBmId);
                    if (form == null)
                    {
                        throw new RuntimeException("Form not found in transaction"); //$NON-NLS-1$
                    }

                    FormVisualEntity target;
                    if (targetForm)
                    {
                        target = form;
                    }
                    else
                    {
                        FormItem item = findItem(form, itemNameFinal);
                        if (item == null)
                        {
                            throw new RuntimeException("Form element not found: " + itemNameFinal //$NON-NLS-1$
                                + ". Use get_form_layout_snapshot to inspect the form layout."); //$NON-NLS-1$
                        }
                        if (!(item instanceof FormVisualEntity))
                        {
                            throw new RuntimeException("Form element '" + itemNameFinal //$NON-NLS-1$
                                + "' does not support events."); //$NON-NLS-1$
                        }
                        target = (FormVisualEntity)item;
                    }

                    if (!(target instanceof EventHandlerContainer))
                    {
                        throw new RuntimeException("Form element '" //$NON-NLS-1$
                            + (targetForm ? mdFormName : itemNameFinal)
                            + "' (" + target.eClass().getName() + ") is not an event-handler container. " //$NON-NLS-1$ //$NON-NLS-2$
                            + "Buttons react through a form command: use add_form_command and " //$NON-NLS-1$
                            + "set_form_command_handler instead."); //$NON-NLS-1$
                    }

                    // Obtain the events valid for this element exactly as the form
                    // editor property palette does.
                    List<Event> allowed = infoService.getAllowedEvents(target);
                    Event matched = matchEvent(allowed, eventFinal);
                    if (matched == null)
                    {
                        throw new RuntimeException("Event '" + eventFinal + "' is not valid for '" //$NON-NLS-1$ //$NON-NLS-2$
                            + (targetForm ? mdFormName : itemNameFinal) + "'. Available events: " //$NON-NLS-1$
                            + describeEvents(allowed) + "."); //$NON-NLS-1$
                    }
                    resolvedEventName[0] = matched.getName();

                    EventHandlerContainer container = (EventHandlerContainer)target;
                    for (EventHandler existing : container.getHandlers())
                    {
                        if (existing.getEvent() == matched
                            || (existing.getEvent() != null && eventEquals(existing.getEvent(), matched)))
                        {
                            throw new RuntimeException("Event '" + matched.getName() //$NON-NLS-1$
                                + "' already has a handler ('" + existing.getName() //$NON-NLS-1$
                                + "'). One handler per event is allowed."); //$NON-NLS-1$
                        }
                    }

                    EventHandler handler = FormFactory.eINSTANCE.createEventHandler();
                    handler.setName(handlerNameFinal);
                    handler.setEvent(matched);
                    container.getHandlers().add(handler);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error setting form event handler", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set form event handler: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the change to the Form.form file on disk so verification tools
        // (get_form_layout_snapshot / get_form_screenshot) and the user see it.
        String persistWarning = persistForm(ctx.project, contentFormFqn(location.content));

        String boundEvent = resolvedEventName[0] != null ? resolvedEventName[0] : event;
        String formModulePath = FormToolSupport.formFqn(location) + ".Module"; //$NON-NLS-1$
        ToolResult result = ToolResult.success()
            .put("formFqn", FormToolSupport.formFqn(location)) //$NON-NLS-1$
            .put("itemName", targetForm ? mdFormName : itemName) //$NON-NLS-1$
            .put("event", boundEvent) //$NON-NLS-1$
            .put("handlerName", handlerName); //$NON-NLS-1$
        String message = "Event '" + boundEvent + "' bound to handler '" + handlerName + "'. " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "The Form.form file was updated on disk. " + //$NON-NLS-1$
            "Ensure a matching '&AtClient Procedure " + handlerName + "(...)' with the event's " + //$NON-NLS-1$ //$NON-NLS-2$
            "signature exists in the form module (" + formModulePath + "); add it with " + //$NON-NLS-1$ //$NON-NLS-2$
            "write_module_source. Run get_project_errors to verify."; //$NON-NLS-1$
        if (persistWarning != null)
        {
            message += " Warning: the on-disk export could not be forced (" + persistWarning //$NON-NLS-1$
                + "); the change is committed in the model and will be written by EDT shortly."; //$NON-NLS-1$
            result.put("persistWarning", persistWarning); //$NON-NLS-1$
        }
        return result
            .put("message", message) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Matches a requested event name against the element's allowed events,
     * comparing the English ({@link Event#getName()}) and Russian
     * ({@link Event#getNameRu()}) names case-insensitively.
     *
     * @param allowed the events valid for the element
     * @param requested the requested event name
     * @return the matching {@link Event}, or {@code null} when none matches
     */
    private static Event matchEvent(List<Event> allowed, String requested)
    {
        for (Event candidate : allowed)
        {
            if (requested.equalsIgnoreCase(candidate.getName())
                || requested.equalsIgnoreCase(candidate.getNameRu()))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * @param a first event
     * @param b second event
     * @return {@code true} when both events share the same English name
     */
    private static boolean eventEquals(Event a, Event b)
    {
        return a.getName() != null && a.getName().equalsIgnoreCase(b.getName());
    }

    /**
     * Renders the allowed events as a comma-separated list of English names
     * (with the Russian name in parentheses when present) for diagnostics.
     *
     * @param allowed the events valid for the element
     * @return a readable list, or a placeholder when the element has no events
     */
    private static String describeEvents(List<Event> allowed)
    {
        if (allowed == null || allowed.isEmpty())
        {
            return "(none)"; //$NON-NLS-1$
        }
        List<String> names = new ArrayList<>(allowed.size());
        for (Event candidate : allowed)
        {
            String en = candidate.getName();
            String ru = candidate.getNameRu();
            if (ru != null && !ru.isEmpty() && !ru.equals(en))
            {
                names.add(en + " (" + ru + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                names.add(en);
            }
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    /**
     * Recursively searches a form's item tree for an item with the given name.
     *
     * @param container the container to search (form root or a group)
     * @param name the item name to look up (case-insensitive)
     * @return the matching {@link FormItem}, or {@code null}
     */
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
}
