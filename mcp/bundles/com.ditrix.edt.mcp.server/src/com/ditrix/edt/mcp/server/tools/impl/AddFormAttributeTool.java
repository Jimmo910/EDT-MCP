/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.AttributeTypeSpec;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.TypeDescriptionBuilder;

/**
 * Tool to add a form attribute ({@link FormAttribute}) to a managed form.
 * <p>
 * The attribute is created with a name, a unique id and (optionally) the
 * {@code main} flag. When a {@code type} is given, this tool builds the value
 * {@link TypeDescription} (including composite types and String/Number/Date
 * qualifiers) and assigns it through {@link FormAttribute#setValueType}. The
 * type is resolved with the same project-scoped mechanism the
 * {@code add_metadata_attribute} tool uses (see {@link TypeDescriptionBuilder}):
 * platform types resolve against the platform type registry, reference types
 * (e.g. {@code CatalogRef.Products}) against this project's metadata objects.
 * Without {@code type} the attribute stays untyped, which is a valid
 * intermediate model state (backward compatible).
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
               "Optionally sets the attribute value type. The 'type' parameter accepts platform types " + //$NON-NLS-1$
               "(String, Number, Boolean, Date) with qualifiers, and reference types " + //$NON-NLS-1$
               "(CatalogRef.<Name>, DocumentRef.<Name>, EnumRef.<Name>, ...). " + //$NON-NLS-1$
               "A comma-separated list produces a composite type. " + //$NON-NLS-1$
               "Examples: 'String(50)', 'Number(15,2)', 'Date(DateTime)', 'CatalogRef.Products', " + //$NON-NLS-1$
               "'String(10), CatalogRef.Products'. When 'type' is omitted the attribute stays untyped " + //$NON-NLS-1$
               "(a valid intermediate state). " + //$NON-NLS-1$
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
            .stringProperty("type", //$NON-NLS-1$
                "Optional value type. A single type or a comma-separated list for a composite type. " + //$NON-NLS-1$
                "Platform types support qualifiers: 'String', 'String(50)', 'String(50,fixed)', " + //$NON-NLS-1$
                "'Number(15,2)', 'Number(15,2,nonnegative)', 'Date(Date|Time|DateTime)', 'Boolean'. " + //$NON-NLS-1$
                "Reference types: 'CatalogRef.<Name>', 'DocumentRef.<Name>', 'EnumRef.<Name>', etc. " + //$NON-NLS-1$
                "Composite example: 'String(10), CatalogRef.Products'.", //$NON-NLS-1$
                false)
            .booleanProperty("main", //$NON-NLS-1$
                "When true, marks the attribute as the form's main attribute (default: false).") //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "When true (default), normalizes the Russian letter 'ё'->'е' / 'Ё'->'Е' in the " + //$NON-NLS-1$
                "attributeName so the result complies with the mdo-ru-name-unallowed-letter " + //$NON-NLS-1$
                "standard; the result reports the change. Set to false to keep it exactly as given.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        boolean main = JsonUtils.extractBooleanArgument(params, "main", false); //$NON-NLS-1$

        // Normalize the new identifier (ё->е / Ё->Е) at the input, before
        // identifier validation, so it is stored standard-compliant.
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        MdNameNormalizer.Report yoReport = new MdNameNormalizer.Report(normalizeYo);
        attributeName = yoReport.apply("attributeName", attributeName); //$NON-NLS-1$

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

        // Optional type. Parse and validate BEFORE any transaction.
        String typeSpecRaw = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        AttributeTypeSpec typeSpec = null;
        if (typeSpecRaw != null && !typeSpecRaw.trim().isEmpty())
        {
            try
            {
                typeSpec = AttributeTypeSpec.parse(typeSpecRaw);
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error("Invalid 'type': " + e.getMessage() + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                    "Examples: 'String(50)', 'Number(15,2)', 'Date(DateTime)', 'CatalogRef.Products', " + //$NON-NLS-1$
                    "'String(10), CatalogRef.Products'.").toJson(); //$NON-NLS-1$
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

        // Resolve the platform version (needed for type proxy resolution).
        Version version = null;
        if (typeSpec != null)
        {
            IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
            if (v8ProjectManager == null)
            {
                return ToolResult.error("IV8ProjectManager not available").toJson(); //$NON-NLS-1$
            }
            IV8Project v8Project = v8ProjectManager.getProject(ctx.project);
            if (v8Project == null)
            {
                return ToolResult.error("Could not resolve V8 project for: " + projectName).toJson(); //$NON-NLS-1$
            }
            version = v8Project.getVersion();
        }

        final long formBmId = bmIdOf(location.content);
        final boolean mainFlag = main;
        final String attributeNameFinal = attributeName;
        final AttributeTypeSpec typeSpecFinal = typeSpec;
        final Version versionFinal = version;
        final IBmEngine bmEngine = bmModel.getEngine();
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
                        if (attributeNameFinal.equalsIgnoreCase(existing.getName()))
                        {
                            throw new RuntimeException("Form attribute already exists: " + attributeNameFinal); //$NON-NLS-1$
                        }
                    }

                    FormAttribute attr = FormFactory.eINSTANCE.createFormAttribute();
                    attr.setName(attributeNameFinal);
                    // Attributes have their own id space, separate from form
                    // elements and commands.
                    attr.setId(nextAttributeId(form));
                    attr.setMain(mainFlag);

                    if (typeSpecFinal != null)
                    {
                        // The form is the resolution context; reference type
                        // names resolve against this project's metadata objects.
                        String contextFqn = TypeDescriptionBuilder.topObjectFqnOf(form);
                        TypeDescription typeDescription = TypeDescriptionBuilder.build(
                            typeSpecFinal, versionFinal, form, bmEngine, contextFqn, tx);
                        attr.setValueType(typeDescription);
                    }

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

        // Persist the change to the Form.form file on disk so verification tools
        // (get_form_layout_snapshot / get_form_screenshot) and the user see it.
        String persistWarning = persistForm(ctx.project, contentFormFqn(location.content));

        ToolResult result = ToolResult.success()
            .put("formFqn", FormToolSupport.formFqn(location)) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("main", main); //$NON-NLS-1$
        if (typeSpec != null)
        {
            result.put("type", TypeDescriptionBuilder.describe(typeSpec)); //$NON-NLS-1$
        }
        String message = "Form attribute '" + attributeName + "' added" //$NON-NLS-1$ //$NON-NLS-2$
            + (typeSpec != null ? " with type " + TypeDescriptionBuilder.describe(typeSpec) : "") //$NON-NLS-1$ //$NON-NLS-2$
            + ". The Form.form file was updated on disk."; //$NON-NLS-1$
        if (persistWarning != null)
        {
            message += " Warning: the on-disk export could not be forced (" + persistWarning //$NON-NLS-1$
                + "); the change is committed in the model and will be written by EDT shortly."; //$NON-NLS-1$
            result.put("persistWarning", persistWarning); //$NON-NLS-1$
        }
        if (yoReport.hasChanges())
        {
            result.put("normalized", yoReport.normalizedFields()) //$NON-NLS-1$
                .put("note", yoReport.note()); //$NON-NLS-1$
        }
        return result
            .put("message", message + " Run get_project_errors to verify.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }
}
