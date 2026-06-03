/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for {@link MetadataPathResolver}, focused on the form-FQN separator
 * handling (Bitrix #19889 S12 fix #1): the screenshot/snapshot path must accept
 * both the singular {@code .Form.} separator used by the form-write tools and the
 * plural {@code .Forms.} separator that mirrors the on-disk {@code Forms/}
 * directory, so a form FQN is interchangeable everywhere.
 */
public class MetadataPathResolverTest
{
    @Test
    public void testPluralFormsSeparatorResolves()
    {
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testSingularFormSeparatorResolves()
    {
        // The singular 'Form' separator (as produced by the form-write tools) must
        // resolve to the same on-disk path as the plural 'Forms' separator.
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testSeparatorIsCaseInsensitive()
    {
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Catalog.Products.form.ItemForm")); //$NON-NLS-1$
        assertEquals("src/Catalogs/Products/Forms/ItemForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Catalog.Products.FORMS.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testDocumentSingularSeparatorResolves()
    {
        assertEquals("src/Documents/SalesOrder/Forms/DocumentForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("Document.SalesOrder.Form.DocumentForm")); //$NON-NLS-1$
    }

    @Test
    public void testCommonFormStillResolves()
    {
        assertEquals("src/CommonForms/MyForm/Form.form", //$NON-NLS-1$
            MetadataPathResolver.resolveFormFilePath("CommonForm.MyForm")); //$NON-NLS-1$
    }

    @Test
    public void testWrongSeparatorRejected()
    {
        // A 4-part FQN whose third segment is neither 'Form' nor 'Forms' is not a
        // form path and must not resolve.
        assertNull(MetadataPathResolver.resolveFormFilePath("Catalog.Products.Template.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testNullAndEmptyRejected()
    {
        assertNull(MetadataPathResolver.resolveFormFilePath(null));
        assertNull(MetadataPathResolver.resolveFormFilePath("")); //$NON-NLS-1$
    }
}
