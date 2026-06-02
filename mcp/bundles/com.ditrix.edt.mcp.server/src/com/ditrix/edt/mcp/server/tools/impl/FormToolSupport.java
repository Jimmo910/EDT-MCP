/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

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
}
