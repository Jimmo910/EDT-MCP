/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;

/**
 * Tests for the ambiguity-aware lookup helpers in {@link FormToolSupport}
 * (Bitrix #19889 S12 fix #5): a name that matches more than one element in the
 * form tree must raise a clear disambiguation error instead of silently
 * targeting the first match. A single match resolves unchanged.
 */
public class FormToolSupportTest
{
    private static FormField field(String name)
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName(name);
        return field;
    }

    private static FormGroup group(String name)
    {
        FormGroup group = FormFactory.eINSTANCE.createFormGroup();
        group.setName(name);
        group.setType(ManagedFormGroupType.USUAL_GROUP);
        return group;
    }

    // ===== findUniqueItem =====

    @Test
    public void testFindUniqueItemReturnsSingleMatch()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField unique = field("Code"); //$NON-NLS-1$
        form.getItems().add(unique);
        form.getItems().add(field("Description")); //$NON-NLS-1$

        assertSame(unique, FormToolSupport.findUniqueItem(form, "Code")); //$NON-NLS-1$
    }

    @Test
    public void testFindUniqueItemReturnsNullWhenAbsent()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(field("Code")); //$NON-NLS-1$

        assertNull(FormToolSupport.findUniqueItem(form, "Missing")); //$NON-NLS-1$
    }

    @Test
    public void testFindUniqueItemFailsOnAmbiguity()
    {
        // Two fields named 'Comment' in different groups: must not pick one.
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup g1 = group("GroupA"); //$NON-NLS-1$
        FormGroup g2 = group("GroupB"); //$NON-NLS-1$
        g1.getItems().add(field("Comment")); //$NON-NLS-1$
        g2.getItems().add(field("Comment")); //$NON-NLS-1$
        form.getItems().add(g1);
        form.getItems().add(g2);

        try
        {
            FormToolSupport.findUniqueItem(form, "Comment"); //$NON-NLS-1$
            fail("expected an ambiguity error"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            String msg = e.getMessage();
            assertTrue("message should flag ambiguity: " + msg, //$NON-NLS-1$
                msg.toLowerCase().contains("ambiguous")); //$NON-NLS-1$
            // The candidates' parents must be listed for disambiguation.
            assertTrue("message should name parent GroupA: " + msg, msg.contains("GroupA")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("message should name parent GroupB: " + msg, msg.contains("GroupB")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ===== findUniqueGroup =====

    @Test
    public void testFindUniqueGroupReturnsSingleMatch()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup g = group("Main"); //$NON-NLS-1$
        form.getItems().add(g);

        assertSame(g, FormToolSupport.findUniqueGroup(form, "Main")); //$NON-NLS-1$
    }

    @Test
    public void testFindUniqueGroupFailsOnAmbiguity()
    {
        // Two groups named 'Tab', one nested inside the other.
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup outer = group("Outer"); //$NON-NLS-1$
        FormGroup tab1 = group("Tab"); //$NON-NLS-1$
        FormGroup tab2 = group("Tab"); //$NON-NLS-1$
        outer.getItems().add(tab2);
        form.getItems().add(tab1);
        form.getItems().add(outer);

        try
        {
            FormToolSupport.findUniqueGroup(form, "Tab"); //$NON-NLS-1$
            fail("expected an ambiguity error"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertTrue("message should flag ambiguity: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().toLowerCase().contains("ambiguous")); //$NON-NLS-1$
        }
    }

    @Test
    public void testFindUniqueGroupIgnoresFields()
    {
        // A field named 'Items' must not be returned as a group.
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(field("Items")); //$NON-NLS-1$

        assertNull(FormToolSupport.findUniqueGroup(form, "Items")); //$NON-NLS-1$
    }
}
