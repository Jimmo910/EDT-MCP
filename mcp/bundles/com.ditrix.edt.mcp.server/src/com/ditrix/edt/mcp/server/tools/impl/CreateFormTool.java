/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.ItemHorizontalAlignment;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to create a new managed form on a metadata object (Catalog, Document, …).
 * <p>
 * A managed form spans two model levels (see {@link AbstractFormWriteTool}). This
 * tool creates both in a single BM transaction:
 * <ul>
 * <li>the MD-form ({@code CatalogForm}/{@code DocumentForm}/… – a
 * {@link BasicForm}) via the standard {@link IModelObjectFactory}, added to the
 * owner's {@code forms} collection;</li>
 * <li>an empty content {@link Form} ({@code FormFactory.eINSTANCE.createForm()})
 * registered as a BM top object (its own {@code Form.form} file) and linked to
 * the MD-form via {@code BasicForm.setForm(...)}.</li>
 * </ul>
 * The content form is attached under the FQN produced by
 * {@link com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator#generateExternalPropertyFqn}
 * for the {@code BasicForm.form} reference (e.g.
 * {@code Catalog.Products.Form.ItemForm.Form}) – the same FQN EDT's own form
 * infrastructure uses. This is what lets the BM namespace assign a store for the
 * content object so structure tools can re-resolve it later; attaching it under
 * any other FQN leaves it without a store and later access fails with
 * "No store with '&lt;id&gt;' is assigned to namespace ...".
 * The new form is created empty (no auto-generated items); use
 * {@code add_form_attribute}, {@code add_form_item} and {@code add_form_command}
 * to populate its structure. Optionally registers the form as the owner's
 * default form.
 */
public class CreateFormTool extends AbstractFormWriteTool
{
    public static final String NAME = "create_form"; //$NON-NLS-1$

    /** Name of the EMF feature holding the owner's forms collection. */
    private static final String FORMS_FEATURE = "forms"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a new empty managed form on a metadata object " + //$NON-NLS-1$
               "(Catalog, Document, InformationRegister, etc.). " + //$NON-NLS-1$
               "Creates both the MD-form node (in the owner .mdo) and an empty content form (Form.form), " + //$NON-NLS-1$
               "links them, and registers a generated UUID. " + //$NON-NLS-1$
               "The form is created empty; use add_form_attribute, add_form_item and add_form_command to " + //$NON-NLS-1$
               "populate it. Optionally sets the form as the owner's default object form. " + //$NON-NLS-1$
               "Russian type names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("ownerFqn", //$NON-NLS-1$
                "FQN of the owner metadata object that will hold the form " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products', 'Document.SalesOrder'). Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("formName", //$NON-NLS-1$
                "Name for the new form (required). Must be a valid 1C identifier (e.g. 'ItemForm').", true) //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional synonym (display name) for the configuration default language " + //$NON-NLS-1$
                "unless 'language' is specified.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code for the synonym (e.g. 'en', 'ru'). Defaults to the configuration default language.") //$NON-NLS-1$
            .booleanProperty("setAsDefault", //$NON-NLS-1$
                "When true, registers the form as the owner's default object form (default: false).") //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "When true (default), normalizes the Russian letter 'ё'->'е' / 'Ё'->'Е' in the " + //$NON-NLS-1$
                "formName and synonym so the result complies with the mdo-ru-name-unallowed-letter " + //$NON-NLS-1$
                "standard; the result reports which fields were changed. " + //$NON-NLS-1$
                "Set to false to keep the text exactly as given.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        boolean setAsDefault = JsonUtils.extractBooleanArgument(params, "setAsDefault", false); //$NON-NLS-1$

        // Normalize user text (ё->е / Ё->Е) at the input, before identifier
        // validation, so the form name/synonym are stored standard-compliant.
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        MdNameNormalizer.Report yoReport = new MdNameNormalizer.Report(normalizeYo);
        formName = yoReport.apply("formName", formName); //$NON-NLS-1$
        synonym = yoReport.apply("synonym", synonym); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " + //$NON-NLS-1$
                "Usage: {projectName: 'MyProject', ownerFqn: 'Catalog.Products', formName: 'ItemForm'}").toJson(); //$NON-NLS-1$
        }
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            return ToolResult.error("ownerFqn is required. " + //$NON-NLS-1$
                "Examples: 'Catalog.Products', 'Document.SalesOrder'.").toJson(); //$NON-NLS-1$
        }
        if (formName == null || formName.isEmpty())
        {
            return ToolResult.error("formName is required (e.g. 'ItemForm').").toJson(); //$NON-NLS-1$
        }
        if (!isValidIdentifier(formName))
        {
            return ToolResult.error("Invalid form name '" + formName + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "A name must start with a letter or underscore and contain only letters, digits and underscores.") //$NON-NLS-1$
                .toJson();
        }

        return executeInternal(projectName, ownerFqn, formName, synonym, language, setAsDefault, yoReport);
    }

    private String executeInternal(String projectName, String ownerFqn, String formName,
        String synonym, String language, boolean setAsDefault, MdNameNormalizer.Report yoReport)
    {
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        // Locate the owner object.
        String normalizedOwnerFqn = MetadataTypeUtils.normalizeFqn(ownerFqn);
        String[] ownerParts = normalizedOwnerFqn.split("\\.", 2); //$NON-NLS-1$
        if (ownerParts.length < 2)
        {
            return ToolResult.error("Invalid ownerFqn: " + ownerFqn + ". Expected 'Type.Name'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MdObject owner = MetadataTypeUtils.findObject(config, ownerParts[0], ownerParts[1]);
        if (owner == null)
        {
            return ToolResult.error("Owner object not found: " + normalizedOwnerFqn + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Use get_metadata_objects to list available objects.").toJson(); //$NON-NLS-1$
        }

        // Resolve the owner's 'forms' feature and the MD-form EClass it holds.
        EStructuralFeature formsFeature = owner.eClass().getEStructuralFeature(FORMS_FEATURE);
        if (formsFeature == null || !(formsFeature.getEType() instanceof EClass))
        {
            return ToolResult.error("Object type '" + owner.eClass().getName() + //$NON-NLS-1$
                "' does not support forms.").toJson(); //$NON-NLS-1$
        }
        final EClass mdFormEClass = (EClass)formsFeature.getEType();

        // Duplicate check.
        if (findFormByName(owner, formName) != null)
        {
            return ToolResult.error("Form already exists: " + normalizedOwnerFqn + ".Form." + formName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (v8ProjectManager == null)
        {
            return ToolResult.error("IV8ProjectManager not available").toJson(); //$NON-NLS-1$
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            return ToolResult.error("Could not resolve V8 project for: " + projectName).toJson(); //$NON-NLS-1$
        }
        final Version version = v8Project.getVersion();

        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        if (factory == null)
        {
            return ToolResult.error("IModelObjectFactory not available").toJson(); //$NON-NLS-1$
        }

        // The content Form must be built by the FORM model factory (the same
        // FormObjectFactory the "New form" wizard uses), not by the MD factory
        // and not by a bare FormFactory.createForm(). Only the form factory fills
        // the predefined autoCommandBar (plus command interface and the form-level
        // flags); without the autoCommandBar EDT's WYSIWYG layout generator
        // (HippoGenerator.readElement -> findHGClass(null)) throws
        // IllegalArgumentException and the form never renders. May be null if the
        // form bundle injector is unavailable; we fall back to a manual minimal-
        // but-renderable build inside the transaction.
        final IModelObjectFactory formFactory = Activator.getDefault().getFormModelObjectFactory();

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // The content form is an external-property top object of the MD-form
        // (the BasicForm.form reference). Its BM top-object FQN must be the one
        // EDT's own form infrastructure assigns, otherwise the BM namespace has
        // no store for it and any later resolution fails with
        // "No store with '<id>' is assigned to namespace ...". We therefore use
        // the same ITopObjectFqnGenerator EDT uses (see ExtInfoManagementService).
        final ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        if (fqnGenerator == null)
        {
            return ToolResult.error("ITopObjectFqnGenerator not available").toJson(); //$NON-NLS-1$
        }

        // Resolve synonym language only when a synonym was supplied.
        final String synonymLanguage;
        if (synonym != null && !synonym.isEmpty())
        {
            LanguageResolution langResolution = resolveLanguage(config, language);
            if (langResolution.hasError())
            {
                return ToolResult.error(langResolution.error).toJson();
            }
            synonymLanguage = langResolution.code;
        }
        else
        {
            synonymLanguage = null;
        }

        final long ownerBmId = bmIdOf(owner);
        final String formFqn = normalizedOwnerFqn + "." + FORM_SEGMENT + "." + formName; //$NON-NLS-1$ //$NON-NLS-2$

        // Capture the content-form top-object FQN generated inside the transaction
        // so the newly created Form.form can be flushed to disk after commit.
        final AtomicReference<String> contentFqnRef = new AtomicReference<>();

        try
        {
            bmModel.execute(new AbstractBmTask<Void>("CreateForm") //$NON-NLS-1$
            {
                @Override
                @SuppressWarnings("unchecked")
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject freshOwner = (MdObject)tx.getObjectById(ownerBmId);
                    if (freshOwner == null)
                    {
                        throw new RuntimeException("Owner not found in transaction"); //$NON-NLS-1$
                    }

                    // (1) Create the MD-form via the standard factory (wizard-equivalent).
                    BasicForm mdForm = (BasicForm)factory.create(mdFormEClass, version);
                    if (mdForm == null)
                    {
                        throw new RuntimeException("Factory returned null for form type: " //$NON-NLS-1$
                            + mdFormEClass.getName());
                    }
                    mdForm.setName(formName);
                    mdForm.setUuid(UUID.randomUUID());
                    if (synonym != null && !synonym.isEmpty())
                    {
                        mdForm.getSynonym().put(synonymLanguage, synonym);
                    }

                    // (2) Create the content form with EDT's default structure.
                    // Built by the FORM model factory so it gets the predefined
                    // autoCommandBar (and command interface / form flags) the
                    // WYSIWYG layout generator requires; otherwise rendering
                    // crashes in HippoGenerator.findHGClass(null). Falls back to a
                    // manual minimal-but-renderable build if the factory is absent.
                    Form content = createContentForm(formFactory, freshOwner, version);

                    // (3) Link MD-form <-> content form (both directions).
                    mdForm.setForm(content);
                    content.setMdForm(mdForm);

                    // (4) Add the MD-form to the owner's forms collection. This must
                    // happen before generating the content FQN, so the MD-form has a
                    // resolvable parent chain (owner -> configuration) and therefore a
                    // resolvable FQN.
                    Object collection = freshOwner.eGet(formsFeature);
                    if (!(collection instanceof EList))
                    {
                        throw new RuntimeException("Owner feature 'forms' is not a list"); //$NON-NLS-1$
                    }
                    ((EList<BasicForm>)collection).add(mdForm);

                    // (5) Register the content form as a BM top object (its own Form.form
                    // file). The FQN is the external-property FQN of the BasicForm.form
                    // reference (e.g. "Catalog.Products.Form.ItemForm.Form"), generated
                    // exactly as EDT does, so the BM namespace assigns a store for it and
                    // later look-ups resolve instead of failing with "No store ...".
                    String contentFqn = fqnGenerator.generateExternalPropertyFqn(mdForm,
                        MdClassPackage.Literals.BASIC_FORM__FORM);
                    if (contentFqn == null || contentFqn.isEmpty())
                    {
                        throw new RuntimeException(
                            "Could not generate the content-form FQN for: " + formFqn); //$NON-NLS-1$
                    }
                    tx.attachTopObject((IBmObject)content, contentFqn);
                    contentFqnRef.set(contentFqn);

                    // (6) Fill default references / usePurposes as the wizard does.
                    factory.fillDefaultReferences(mdForm);

                    // (7) Optionally set as the owner's default object form.
                    if (setAsDefault)
                    {
                        setDefaultObjectForm(freshOwner, mdForm);
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating form", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create form: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the newly created content form to its Form.form file on disk so it
        // appears immediately and verification tools (get_form_layout_snapshot /
        // get_form_screenshot) and the user see it. The FQN is the content form's own
        // top-object FQN generated inside the transaction (e.g.
        // "Catalog.Products.Form.ItemForm.Form"), the same FQN it was attached under,
        // so it is resolvable post-commit.
        String persistWarning = persistForm(project, contentFqnRef.get());

        // Also persist + revalidate the OWNER .mdo. The owner top object is what
        // registers the new form (its <forms> entry) and, when setAsDefault was
        // requested, the defaultObjectForm reference. Those edits live only in the
        // in-memory BM model until the owner is force-exported; without this the
        // owner .mdo on disk would not list the form and get_project_errors could
        // stay stale. This mirrors how metadata tools persist the changed object.
        String ownerFqnForPersist = topObjectFqnOf(owner);
        String ownerPersistWarning = persistAndRevalidate(project, ownerFqnForPersist);
        if (persistWarning == null)
        {
            persistWarning = ownerPersistWarning;
        }
        else if (ownerPersistWarning != null)
        {
            persistWarning = persistWarning + "; owner: " + ownerPersistWarning; //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("ownerFqn", normalizedOwnerFqn) //$NON-NLS-1$
            .put("formName", formName) //$NON-NLS-1$
            .put("setAsDefault", setAsDefault); //$NON-NLS-1$
        String message = "Form '" + formFqn + "' created. " + //$NON-NLS-1$ //$NON-NLS-2$
            "The Form.form file and the owner .mdo (which registers the form) were written to disk. " + //$NON-NLS-1$
            "Add structure with add_form_attribute / add_form_item / add_form_command. " + //$NON-NLS-1$
            "Run get_project_errors to verify; get_form_layout_snapshot to inspect."; //$NON-NLS-1$
        if (persistWarning != null)
        {
            message += " Warning: the on-disk export could not be forced (" + persistWarning //$NON-NLS-1$
                + "); the form is committed in the model and will be written by EDT shortly."; //$NON-NLS-1$
            result.put("persistWarning", persistWarning); //$NON-NLS-1$
        }
        if (yoReport.hasChanges())
        {
            result.put("normalized", yoReport.normalizedFields()) //$NON-NLS-1$
                .put("note", yoReport.note()); //$NON-NLS-1$
        }
        return result
            .put("message", message) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Creates the content {@link Form} with EDT's default structure.
     * <p>
     * Prefers the FORM model factory ({@code FormObjectFactory}, resolved from
     * the form bundle injector) — calling
     * {@code create(FormPackage.Literals.FORM, owner, version)} produces exactly
     * what the "New form" wizard builds: a {@link Form} whose predefined
     * {@code autoCommandBar} is set, along with the command interface and the
     * form-level flags ({@code autoTitle}, {@code group}, …). The
     * {@code autoCommandBar} is the part the WYSIWYG layout generator requires:
     * {@code HippoGenerator.readElement} unconditionally calls
     * {@code findHGClass(ctx, form.getAutoCommandBar(), false)} for the form
     * (a {@code CommandBarHolder}) and throws {@code IllegalArgumentException}
     * when it is {@code null}, aborting layout generation so nothing renders.
     * <p>
     * If the form factory is unavailable, falls back to a manual build that still
     * attaches a minimal valid {@code autoCommandBar} so the form renders.
     *
     * @param formFactory the FORM model factory, or {@code null} if unavailable
     * @param owner the owner metadata object (passed as the creation parent)
     * @param version the platform version
     * @return a content {@link Form} that renders in the WYSIWYG editor
     */
    private Form createContentForm(IModelObjectFactory formFactory, MdObject owner, Version version)
    {
        if (formFactory != null)
        {
            Form content = formFactory.create(FormPackage.Literals.FORM, owner, version);
            if (content != null)
            {
                // Guard against a future factory change that stops seeding the
                // command bar: ensure the render-critical element is present.
                if (content.getAutoCommandBar() == null)
                {
                    content.setAutoCommandBar(createDefaultAutoCommandBar());
                }
                return content;
            }
        }
        // Fallback: bare form plus the render-critical autoCommandBar.
        Form content = FormFactory.eINSTANCE.createForm();
        content.setAutoCommandBar(createDefaultAutoCommandBar());
        return content;
    }

    /**
     * Builds the form's predefined automatic command bar, mirroring
     * {@code FormObjectFactory.newAutoCommandBar}: {@code autoFill = true},
     * {@code horizontalAlign = LEFT}. The id is set to {@code -1}, the sentinel
     * EDT persists for a form's own predefined command bar (see any
     * EDT-authored {@code Form.form}), keeping it out of the regular element id
     * space. A name is assigned so the element is well-formed; EDT renames
     * predefined items to their canonical names on the next form sync.
     *
     * @return a renderable {@link AutoCommandBar}
     */
    private AutoCommandBar createDefaultAutoCommandBar()
    {
        AutoCommandBar bar = FormFactory.eINSTANCE.createAutoCommandBar();
        bar.setAutoFill(true);
        bar.setHorizontalAlign(ItemHorizontalAlignment.LEFT);
        bar.setId(-1);
        bar.setName("ФормаКоманднаяПанель"); //$NON-NLS-1$
        return bar;
    }

    /**
     * Sets the owner's default object form via {@code setDefaultObjectForm(...)}
     * when present. Uses reflection because that setter is declared per owner
     * type without a common interface; a missing setter is reported clearly
     * rather than failing silently.
     *
     * @param owner the owner metadata object
     * @param mdForm the MD-form to register as default
     */
    private void setDefaultObjectForm(MdObject owner, BasicForm mdForm)
    {
        Method setter = findDefaultFormSetter(owner, mdForm);
        if (setter == null)
        {
            throw new RuntimeException("Owner type '" + owner.eClass().getName() //$NON-NLS-1$
                + "' has no compatible setDefaultObjectForm(...) method; " //$NON-NLS-1$
                + "create the form without setAsDefault and assign it manually."); //$NON-NLS-1$
        }
        try
        {
            setter.invoke(owner, mdForm);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Failed to set default object form", e); //$NON-NLS-1$
        }
    }

    private Method findDefaultFormSetter(MdObject owner, BasicForm mdForm)
    {
        for (Method method : owner.getClass().getMethods())
        {
            if (!"setDefaultObjectForm".equals(method.getName())) //$NON-NLS-1$
            {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 1 && paramTypes[0].isInstance(mdForm))
            {
                return method;
            }
        }
        return null;
    }

    private static boolean isValidIdentifier(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_')
            {
                return false;
            }
        }
        return true;
    }
}
