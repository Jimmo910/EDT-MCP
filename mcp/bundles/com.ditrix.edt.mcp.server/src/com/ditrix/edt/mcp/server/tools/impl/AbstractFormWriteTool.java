/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.Collections;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.service.FormIdentifierService;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Base class for form structure write tools.
 * <p>
 * A managed form lives on two model levels:
 * <ul>
 * <li>the MD level ({@code CatalogForm}, {@code DocumentForm}, … – a
 * {@link BasicForm} stored inside the owner's {@code .mdo}); and</li>
 * <li>the content level ({@link Form} from {@code com._1c.g5.v8.dt.form.model}
 * stored in the {@code Form.form} file), which actually holds the items,
 * attributes and commands.</li>
 * </ul>
 * The two are linked through {@link BasicForm#getForm()} /
 * {@code BasicForm.setForm(AbstractForm)}. Structure edits (attributes, items,
 * commands, handlers) always target the content {@link Form}.
 * <p>
 * This class adds reusable helpers on top of {@link AbstractMetadataWriteTool}:
 * locating the MD-form by its FQN, deriving the content {@link Form}, and
 * generating unique element {@code id}s.
 */
public abstract class AbstractFormWriteTool extends AbstractMetadataWriteTool
{
    /** The fixed FQN segment that separates an object owner from its forms. */
    protected static final String FORM_SEGMENT = "Form"; //$NON-NLS-1$

    /**
     * Resolved form location: the MD-form, its owner and the content
     * {@link Form}, or {@code null} fields when the form could not be found.
     */
    protected static final class FormLocation
    {
        /** The owner metadata object (Catalog, Document, …). */
        public MdObject owner;
        /** The MD-level form object. */
        public BasicForm mdForm;
        /** The content-level form (items / attributes / commands live here). */
        public Form content;
    }

    /**
     * Resolves a form by its FQN. The FQN format is
     * {@code <OwnerType>.<OwnerName>.Form.<FormName>}, e.g.
     * {@code Catalog.Products.Form.ItemForm}. Russian type names are accepted.
     *
     * @param config the configuration root
     * @param formFqn the form FQN (already trimmed, may use Russian type names)
     * @return a {@link FormLocation}; its {@link FormLocation#mdForm} is
     *         {@code null} when the owner or form is missing
     */
    protected FormLocation resolveForm(Configuration config, String formFqn)
    {
        FormLocation location = new FormLocation();

        String normalized = MetadataTypeUtils.normalizeFqn(formFqn);
        // Expected: Type.Owner.Form.FormName -> split into owner FQN and form name.
        String[] parts = normalized.split("\\."); //$NON-NLS-1$
        if (parts.length < 4 || !FORM_SEGMENT.equalsIgnoreCase(parts[parts.length - 2]))
        {
            return location;
        }
        String formName = parts[parts.length - 1];
        String ownerType = parts[0];
        // The owner name may itself contain dots in theory; rebuild it safely.
        StringBuilder ownerName = new StringBuilder();
        for (int i = 1; i < parts.length - 2; i++)
        {
            if (ownerName.length() > 0)
            {
                ownerName.append('.');
            }
            ownerName.append(parts[i]);
        }

        MdObject owner = MetadataTypeUtils.findObject(config, ownerType, ownerName.toString());
        if (owner == null)
        {
            return location;
        }
        location.owner = owner;

        BasicForm mdForm = findFormByName(owner, formName);
        if (mdForm == null)
        {
            return location;
        }
        location.mdForm = mdForm;
        location.content = contentOf(mdForm);
        return location;
    }

    /**
     * Returns the MD-form with the given name from the owner's {@code getForms()}
     * collection, or {@code null}. Uses reflection because the {@code getForms()}
     * method is declared per owner type without a common interface.
     *
     * @param owner the owner metadata object
     * @param formName the form name to look up
     * @return the matching {@link BasicForm}, or {@code null}
     */
    protected BasicForm findFormByName(MdObject owner, String formName)
    {
        for (BasicForm form : getForms(owner))
        {
            if (formName.equalsIgnoreCase(form.getName()))
            {
                return form;
            }
        }
        return null;
    }

    /**
     * Returns the owner's forms collection via {@code getForms()}.
     * <p>
     * Reflection is used here for the same reason as in
     * {@code AddMetadataAttributeTool}: {@code getForms()} is declared on each
     * concrete owner type ({@code Catalog}, {@code Document}, …) but there is no
     * shared interface exposing it.
     *
     * @param owner the owner metadata object
     * @return the forms list (never {@code null}; empty when unavailable)
     */
    @SuppressWarnings("unchecked")
    protected EList<BasicForm> getForms(MdObject owner)
    {
        try
        {
            Method method = owner.getClass().getMethod("getForms"); //$NON-NLS-1$
            Object result = method.invoke(owner);
            if (result instanceof EList)
            {
                return (EList<BasicForm>)result;
            }
        }
        catch (ReflectiveOperationException e)
        {
            // Owner type does not expose getForms(); treat as "no forms".
        }
        return org.eclipse.emf.common.util.ECollections.emptyEList();
    }

    /**
     * Returns the content {@link Form} backing an MD-form, or {@code null} when
     * it has none.
     *
     * @param mdForm the MD-level form
     * @return the content form, or {@code null}
     */
    protected Form contentOf(BasicForm mdForm)
    {
        Object content = mdForm.getForm();
        return content instanceof Form ? (Form)content : null;
    }

    /**
     * Computes the next free <b>element</b> {@code id} for a form.
     * <p>
     * Form ids live in three independent spaces: form <em>elements</em>
     * ({@link com._1c.g5.v8.dt.form.model.FormItem} and every subtype –
     * fields, groups, buttons, context menus, extended tooltips,
     * {@code autoCommandBar} buttons, additions, …), form
     * <em>attributes</em> and form <em>commands</em>. A new id must be unique
     * only within its own space; the spaces are never merged.
     * <p>
     * This delegates to EDT's own {@link FormIdentifierService} – the same
     * allocator the form editor uses. It scans the <em>entire</em> form
     * containment tree (via {@code EcoreUtil.getAllContents}), not just the
     * top-level {@code getItems()} collection, so nested elements that are held
     * through dedicated references rather than {@code getItems()} (context
     * menus, extended tooltips, {@code autoCommandBar} buttons, …) are counted.
     * It also keeps a per-transaction running counter, so several elements
     * created within one transaction receive distinct ids even before they are
     * attached to the tree.
     *
     * @param form the content form
     * @return a positive element id greater than every existing element id
     */
    protected int nextItemId(Form form)
    {
        return FormIdentifierService.INSTANCE.getNextItemId(form);
    }

    /**
     * Computes the next free <b>attribute</b> {@code id} for a form. This is a
     * separate id space from form elements and commands; see
     * {@link #nextItemId(Form)}.
     *
     * @param form the content form
     * @return a positive attribute id greater than every existing attribute id
     */
    protected int nextAttributeId(Form form)
    {
        return FormIdentifierService.INSTANCE.getNextAttributeId(form);
    }

    /**
     * Computes the next free <b>command</b> {@code id} for a form. This is a
     * separate id space from form elements and attributes; see
     * {@link #nextItemId(Form)}.
     *
     * @param form the content form
     * @return a positive command id greater than every existing command id
     */
    protected int nextCommandId(Form form)
    {
        return FormIdentifierService.INSTANCE.getNextCommandId(form);
    }

    /**
     * Returns the {@code bmId} of an EMF object so it can be re-fetched inside a
     * BM transaction.
     *
     * @param object the model object (must be a BM object)
     * @return the bmId
     */
    protected static long bmIdOf(Object object)
    {
        return ((IBmObject)object).bmGetId();
    }

    /**
     * Forces the in-memory BM model change to be written to the workspace
     * {@code .form} file on disk.
     * <p>
     * A {@code bmModel.execute(...)} transaction commits the change into the
     * in-memory BM model, but the model-to-file serialization runs
     * asynchronously, so the on-disk {@code Form.form} does not change until that
     * background export completes. Tools that verify the result by reading the
     * file from disk ({@code get_form_layout_snapshot}, {@code get_form_screenshot})
     * would therefore not see the change. This method drives the export
     * synchronously through {@link IBmModelManager#forceExport(IDtProject, java.util.List)},
     * the same API EDT uses to flush a top object to its file, using the content
     * form's own top-object FQN (e.g. {@code Catalog.Products.Form.ItemForm.Form}).
     *
     * @param project the workspace project owning the form
     * @param contentFormFqn the BM top-object FQN of the content form (obtained
     *            via {@code ((IBmObject) form).bmGetFqn()})
     * @return {@code null} on success, or a short diagnostic when the export could
     *         not be performed (the model change is still committed in memory)
     */
    protected String persistForm(IProject project, String contentFormFqn)
    {
        if (contentFormFqn == null || contentFormFqn.isEmpty())
        {
            return "content form FQN is unknown"; //$NON-NLS-1$
        }
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        if (dtProjectManager == null)
        {
            return "IDtProjectManager not available"; //$NON-NLS-1$
        }
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            return "could not resolve DT project"; //$NON-NLS-1$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return "IBmModelManager not available"; //$NON-NLS-1$
        }
        try
        {
            bmModelManager.forceExport(dtProject, Collections.singletonList(contentFormFqn));
            return null;
        }
        catch (Exception e)
        {
            Activator.logError("Error exporting form to disk: " + contentFormFqn, e); //$NON-NLS-1$
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    /**
     * Returns the BM top-object FQN of a content {@link Form} (the FQN under which
     * its {@code Form.form} file is registered), or {@code null} when unavailable.
     *
     * @param content the content form
     * @return the content form's top-object FQN, or {@code null}
     */
    protected static String contentFormFqn(Form content)
    {
        if (!(content instanceof IBmObject))
        {
            return null;
        }
        return ((IBmObject)content).bmGetFqn();
    }
}
