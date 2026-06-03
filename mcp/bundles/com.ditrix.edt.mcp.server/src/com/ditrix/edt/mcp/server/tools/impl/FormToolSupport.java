/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.impl.AbstractFormWriteTool.FormLocation;

/**
 * Shared helpers for form structure write tools: identifier validation,
 * form-location error reporting, and FQN formatting. Keeps the per-tool classes
 * focused and avoids duplicating the same checks in every item-level tool.
 */
final class FormToolSupport
{
    private FormToolSupport()
    {
    }

    /**
     * Validates that a name is a legal 1C identifier (letter/underscore start,
     * then letters/digits/underscores).
     *
     * @param name the candidate name
     * @return {@code true} when valid
     */
    static boolean isValidIdentifier(String name)
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

    /**
     * Returns a ready-to-return JSON error when the form could not be fully
     * resolved (missing owner, missing MD-form, or missing content form), or
     * {@code null} when the location is usable for structure edits.
     *
     * @param location the resolved form location
     * @param formFqn the requested form FQN (for diagnostics)
     * @return a JSON error string, or {@code null} when the location is valid
     */
    static String checkFormLocation(FormLocation location, String formFqn)
    {
        if (location.owner == null)
        {
            return ToolResult.error("Form not found: " + formFqn + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Owner object does not exist. Expected FQN 'OwnerType.OwnerName.Form.FormName'.").toJson(); //$NON-NLS-1$
        }
        if (location.mdForm == null)
        {
            return ToolResult.error("Form not found: " + formFqn + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "The owner exists but has no form with this name. " + //$NON-NLS-1$
                "Create it first with create_form.").toJson(); //$NON-NLS-1$
        }
        if (location.content == null)
        {
            return ToolResult.error("Form '" + formFqn + "' has no content model (Form.form). " + //$NON-NLS-1$ //$NON-NLS-2$
                "The MD-form exists but its content form is missing or could not be loaded.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Builds the canonical form FQN ({@code OwnerType.OwnerName.Form.FormName})
     * from a resolved location.
     *
     * @param location a fully resolved form location
     * @return the form FQN
     */
    static String formFqn(FormLocation location)
    {
        String ownerFqn = ((com._1c.g5.v8.bm.core.IBmObject)location.owner).bmGetFqn();
        return ownerFqn + "." + AbstractFormWriteTool.FORM_SEGMENT + "." + location.mdForm.getName(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Finds a single form item by name anywhere in the item tree, failing on
     * ambiguity.
     * <p>
     * Item names are not unique across the whole form tree (two groups may each
     * hold a field called {@code Comment}), so a plain first-match lookup can
     * silently target the wrong element. This collects <em>all</em> matches and:
     * <ul>
     * <li>returns {@code null} when none match;</li>
     * <li>returns the single match when exactly one matches;</li>
     * <li>throws a {@link RuntimeException} listing every candidate and its parent
     * when more than one matches, so the caller fails loudly instead of guessing.</li>
     * </ul>
     *
     * @param form the content form
     * @param name the item name to resolve (case-insensitive)
     * @return the single matching item, or {@code null} when none matches
     * @throws RuntimeException when the name is ambiguous
     */
    static FormItem findUniqueItem(Form form, String name)
    {
        List<FormItem> matches = new ArrayList<>();
        collectItems(form, name, matches);
        if (matches.isEmpty())
        {
            return null;
        }
        if (matches.size() > 1)
        {
            throw new RuntimeException(ambiguityMessage("item", name, matches)); //$NON-NLS-1$
        }
        return matches.get(0);
    }

    /**
     * Finds a single form group by name anywhere in the item tree, failing on
     * ambiguity. See {@link #findUniqueItem(Form, String)}.
     *
     * @param form the content form
     * @param name the group name to resolve (case-insensitive)
     * @return the single matching group, or {@code null} when none matches
     * @throws RuntimeException when the name is ambiguous
     */
    static FormGroup findUniqueGroup(Form form, String name)
    {
        List<FormItem> matches = new ArrayList<>();
        collectGroups(form, name, matches);
        if (matches.isEmpty())
        {
            return null;
        }
        if (matches.size() > 1)
        {
            throw new RuntimeException(ambiguityMessage("group", name, matches)); //$NON-NLS-1$
        }
        return (FormGroup)matches.get(0);
    }

    private static void collectItems(FormItemContainer container, String name, List<FormItem> out)
    {
        for (FormItem item : container.getItems())
        {
            if (name.equalsIgnoreCase(item.getName()))
            {
                out.add(item);
            }
            if (item instanceof FormItemContainer)
            {
                collectItems((FormItemContainer)item, name, out);
            }
        }
    }

    private static void collectGroups(FormItemContainer container, String name, List<FormItem> out)
    {
        for (FormItem item : container.getItems())
        {
            if (item instanceof FormGroup)
            {
                if (name.equalsIgnoreCase(item.getName()))
                {
                    out.add(item);
                }
                collectGroups((FormGroup)item, name, out);
            }
        }
    }

    /**
     * Builds a disambiguation error listing every candidate and its parent.
     *
     * @param kind a human label ({@code "item"} / {@code "group"})
     * @param name the ambiguous name
     * @param matches the matching elements (size &gt; 1)
     * @return the error message
     */
    private static String ambiguityMessage(String kind, String name, List<FormItem> matches)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Ambiguous ").append(kind).append(" name '").append(name) //$NON-NLS-1$ //$NON-NLS-2$
            .append("': ").append(matches.size()).append(" elements match. Candidates (with parent): "); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < matches.size(); i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            FormItem item = matches.get(i);
            sb.append('\'').append(item.getName()).append("' in ").append(parentLabel(item)); //$NON-NLS-1$
        }
        sb.append(". Rename one of them, or move/restructure the form so the name is unique."); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Describes the parent container of a form item for disambiguation messages:
     * the form root, or the enclosing group's name.
     *
     * @param item the form item
     * @return a short parent label
     */
    private static String parentLabel(FormItem item)
    {
        EObject parent = item.eContainer();
        if (parent instanceof Form)
        {
            return "the form root"; //$NON-NLS-1$
        }
        if (parent instanceof FormGroup)
        {
            return "group '" + ((FormGroup)parent).getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (parent instanceof FormItem)
        {
            return "'" + ((FormItem)parent).getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "the form"; //$NON-NLS-1$
    }
}
