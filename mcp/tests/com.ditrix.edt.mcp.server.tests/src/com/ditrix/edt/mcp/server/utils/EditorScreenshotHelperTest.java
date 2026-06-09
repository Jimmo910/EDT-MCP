/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Unit tests for the render-readiness polling and the screenshot identity guard in
 * {@link EditorScreenshotHelper}.
 *
 * <p>The render-readiness loop is the fix for the empty-snapshot defect (Bitrix #19821): the EDT
 * form layout is produced by an asynchronous native render, so the first read after opening a form
 * used to return an empty result. These tests exercise the pure polling behaviour of
 * {@link EditorScreenshotHelper#waitUntilRendered} with a {@code null} viewer (so no real WYSIWYG
 * representation and no rebuild are involved) and a controllable readiness predicate.</p>
 *
 * <p>The {@code fqnMatchesFormPath} tests cover the identity guard for {@code get_form_screenshot}
 * (Bitrix #19889): the tool must only return the requested form's image when the active editor and
 * its WYSIWYG representation genuinely show the requested form, never another form's PNG from the
 * shared offscreen render buffer. The full capture path against a live WYSIWYG editor is covered by
 * e2e.</p>
 */
public class EditorScreenshotHelperTest
{
    private static final int SHORT_TIMEOUT_MS = 600;

    @Test
    public void testReturnsImmediatelyWhenAlreadyRendered()
    {
        AtomicInteger polls = new AtomicInteger();
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () ->
        {
            polls.incrementAndGet();
            return true;
        }, SHORT_TIMEOUT_MS);

        assertTrue("should report rendered when predicate is satisfied", rendered); //$NON-NLS-1$
        assertTrue("predicate must be evaluated at least once", polls.get() >= 1); //$NON-NLS-1$
    }

    @Test
    public void testReturnsTrueOncePredicateBecomesReady()
    {
        AtomicInteger polls = new AtomicInteger();
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null,
            () -> polls.incrementAndGet() >= 3, SHORT_TIMEOUT_MS);

        assertTrue("should report rendered once predicate flips to true", rendered); //$NON-NLS-1$
        assertTrue("predicate must have been polled repeatedly", polls.get() >= 3); //$NON-NLS-1$
    }

    @Test
    public void testReturnsFalseWhenNeverRendered()
    {
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () -> false, SHORT_TIMEOUT_MS);
        assertFalse("should report not rendered when predicate never satisfied", rendered); //$NON-NLS-1$
    }

    @Test
    public void testPredicateExceptionTreatedAsNotReady()
    {
        // A transient failure while the model is still building must not abort the wait.
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () ->
        {
            throw new IllegalStateException("model still building"); //$NON-NLS-1$
        }, SHORT_TIMEOUT_MS);

        assertFalse("predicate exceptions must be treated as not-ready, not propagated", rendered); //$NON-NLS-1$
    }

    // ==================== fqnMatchesFormPath (Bitrix #19889 identity guard) ====================

    @Test
    public void testFqnMatchesSamePathAndModelFqn()
    {
        // The model FQN uses the singular 'Form' separator; the requested path may use plural
        // 'Forms'. Both must be recognized as the same form (object form case).
        assertTrue("singular Form FQN must match the same form", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm", "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("plural 'Forms' request must match singular 'Form' model FQN", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm", "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFqnMatchesCommonForm()
    {
        assertTrue("CommonForm FQN must match itself", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath("CommonForm.MyForm", "CommonForm.MyForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFqnMatchesContentFormFqn()
    {
        // The WYSIWYG representation renders the content form, whose bmGetFqn carries an extra
        // trailing ".Form" segment. That content-form FQN must still match the requested MD-form path.
        assertTrue("object content-form FQN (trailing .Form) must match the requested form", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm.Form", "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("common content-form FQN (trailing .Form) must match the requested common form", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "CommonForm.MyForm.Form", "CommonForm.MyForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFqnMatchesFormNamedForm()
    {
        // A form genuinely named "Form" must not be mis-stripped: the 4-part FQN is canonicalized
        // directly and matches itself, and does NOT collapse to the 3-part owner FQN.
        assertTrue("a form actually named 'Form' must match itself", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Forms.Form", "Catalog.Products.Forms.Form")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFqnMatchesIgnoresCaseAndRussianType()
    {
        assertTrue("type segment must match case-insensitively", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm", "catalog.Products.forms.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        // Russian type name on the requested side must resolve to the same English type.
        // "Catalog.Товары.Form.X" must match
        // "Справочник.Товары.Forms.X".
        assertTrue("Russian metadata type must match its English equivalent", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Товары.Form.X", // Catalog.Товары.Form.X //$NON-NLS-1$
                "Справочник.Товары.Forms.X")); // Справочник.Товары.Forms.X //$NON-NLS-1$
    }

    @Test
    public void testFqnDoesNotMatchDifferentForm()
    {
        // The core defect: a previously active form (DataProcessor.X) must NOT be accepted when a
        // different catalog object form was requested.
        assertFalse("different form must not match (the wrong-form defect)", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "DataProcessor.GrafikSTO.Form.MainForm", //$NON-NLS-1$
                "Catalog.TestS11.Forms.ItemForm")); //$NON-NLS-1$
        assertFalse("same object different form name must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
                "Catalog.Products.Forms.ListForm")); //$NON-NLS-1$
    }

    @Test
    public void testFqnMatchNullSafe()
    {
        assertFalse("null actual FQN must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(null, "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
        assertFalse("null requested path must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath("Catalog.Products.Form.ItemForm", null)); //$NON-NLS-1$
    }

    @Test
    public void testFqnDoesNotMatchMalformedShape()
    {
        // Unrecognized shapes (not a 2-part common form or 4-part object form) must never match,
        // so a non-form or truncated FQN cannot be accepted as the requested form.
        assertFalse("a bare single segment must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath("Catalog", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a 4-part FQN without a forms separator must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Attribute.Name", "Catalog.Products.Attribute.Name")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
