/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
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
     * Computes the next free element {@code id} for a form. Element ids must be
     * unique within a single form across items, attributes and commands.
     *
     * @param form the content form
     * @return a positive id greater than every existing one
     */
    protected int nextItemId(Form form)
    {
        int max = 0;
        max = Math.max(max, maxItemId(form));
        for (FormAttribute attr : form.getAttributes())
        {
            max = Math.max(max, attr.getId());
        }
        for (FormCommand cmd : form.getFormCommands())
        {
            max = Math.max(max, cmd.getId());
        }
        return max + 1;
    }

    private int maxItemId(FormItemContainer container)
    {
        int max = 0;
        for (FormItem item : container.getItems())
        {
            // autoCommandBar uses the service id -1; ignore negatives.
            if (item.getId() > max)
            {
                max = item.getId();
            }
            if (item instanceof FormItemContainer)
            {
                max = Math.max(max, maxItemId((FormItemContainer)item));
            }
        }
        return max;
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
}
