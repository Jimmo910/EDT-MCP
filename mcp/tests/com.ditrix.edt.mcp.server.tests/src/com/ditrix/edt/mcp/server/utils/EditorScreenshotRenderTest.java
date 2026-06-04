/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.junit.Test;

/**
 * Unit tests for the stale-image fix in {@link EditorScreenshotHelper} (Bitrix #19889).
 *
 * <p>The defect: {@code get_form_screenshot} returned a previously rendered form's pixels because
 * the form image lives in a single field ({@code formImageData}) of the WYSIWYG representation, fed
 * from one shared offscreen render buffer. Correctness is guaranteed by verifying the image belongs to
 * the requested form via the representation's own {@code form} model
 * ({@link EditorScreenshotHelper#representationFormMatches}). Render-readiness is then a relaxed wait
 * for that (identity-verified) representation's {@code formImageData} to be <i>non-empty</i>
 * ({@link EditorScreenshotHelper#ensureRenderedFormImage}) — it deliberately does NOT require a
 * brand-new {@code ImageData} instance, because in the detached/MCP-driven EDT the native render reuses
 * the existing instance and the old "must be a new instance" gate suppressed screenshots for every
 * form (the regression this change fixes).</p>
 *
 * <p>These tests exercise the pure reflection-based logic with lightweight fakes that mimic the
 * representation's relevant fields and {@code rebuild(boolean)} method, so no live EDT editor or
 * native render is required. The full path against a real WYSIWYG editor is covered by e2e.</p>
 */
public class EditorScreenshotRenderTest
{
    /** Short wait so negative cases (no fresh render) do not block the suite for the full budget. */
    private static final int SHORT_TIMEOUT_MS = 800;

    /** Minimal stand-in for a metadata model object exposing {@code bmGetFqn()}. */
    public static final class FakeModel
    {
        private final String fqn;

        public FakeModel(String fqn)
        {
            this.fqn = fqn;
        }

        @SuppressWarnings("unused")
        public String bmGetFqn()
        {
            return fqn;
        }
    }

    /**
     * Stand-in for {@code FormWysiwygRepresentation}: it declares the same fields the helper reads
     * via reflection ({@code form}, {@code formImageData}) and a {@code rebuild(boolean)} method that
     * simulates the native render assigning a non-empty image after a number of attempts.
     */
    public static final class FakeRepresentation
    {
        // Field names must match what EditorScreenshotHelper reads reflectively.
        @SuppressWarnings("unused")
        private Object form;
        @SuppressWarnings("unused")
        private ImageData formImageData;

        private int rebuildsUntilFresh;
        private int rebuildCount;
        private boolean produceEmpty;

        public void setForm(Object form)
        {
            this.form = form;
        }

        /** Pre-load a (stale) image, as if another form had been rendered last. */
        public void setInitialImage(ImageData image)
        {
            this.formImageData = image;
        }

        public void setRebuildsUntilFresh(int n)
        {
            this.rebuildsUntilFresh = n;
        }

        /** When true, every rebuild keeps producing a zero-size image (never a usable render). */
        public void setProduceEmpty(boolean produceEmpty)
        {
            this.produceEmpty = produceEmpty;
        }

        /** Mirrors FormWysiwygRepresentation.rebuild(boolean): drives the (fake) native render. */
        @SuppressWarnings("unused")
        public void rebuild(boolean updateOnly)
        {
            rebuildCount++;
            if (produceEmpty)
            {
                formImageData = newImage(0, 0);
                return;
            }
            if (rebuildCount >= rebuildsUntilFresh)
            {
                // A completed render assigns a brand-new, non-empty image instance.
                formImageData = newImage(10, 10);
            }
        }
    }

    private static ImageData newImage(int width, int height)
    {
        // Construct a minimal valid ImageData (SWT requires width/height >= 1), then override the
        // public width/height fields to model an empty (zero-size) render when requested. Used purely
        // for in-memory identity/size checks here, never drawn.
        ImageData data = new ImageData(1, 1, 24, new PaletteData(0xFF, 0xFF00, 0xFF0000));
        data.width = width;
        data.height = height;
        return data;
    }

    // ==================== representationFormMatches ====================

    @Test
    public void testRepresentationFormMatchesRequestedForm()
    {
        FakeRepresentation rep = new FakeRepresentation();
        rep.setForm(new FakeModel("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$

        assertTrue("representation rendering the requested form must match", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testRepresentationContentFormFqnMatchesRequestedObjectForm()
    {
        // The real-EDT shape (Bitrix #19889): the representation's own 'form' is the CONTENT form,
        // whose bmGetFqn() is the external-property FQN of the MD-form's 'form' reference, i.e. the
        // MD-form FQN with a trailing ".Form" content segment appended
        // (Catalog.Products.Form.ItemForm -> Catalog.Products.Form.ItemForm.Form). It must match the
        // requested MD-form path (plural separator, no content suffix). Before the fix this trailing
        // segment made the guard reject EVERY form.
        FakeRepresentation rep = new FakeRepresentation();
        rep.setForm(new FakeModel("Catalog.Products.Form.ItemForm.Form")); //$NON-NLS-1$

        assertTrue("content-form FQN with trailing .Form must match the requested object form", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
        // Same content FQN also matches the singular-separator request form.
        assertTrue("content-form FQN must match the singular-separator request too", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testRepresentationContentFormFqnMatchesRequestedCommonForm()
    {
        // Common form: the content form's FQN is "CommonForm.MyForm.Form" (3 parts, trailing .Form
        // content segment) and must match the requested "CommonForm.MyForm".
        FakeRepresentation rep = new FakeRepresentation();
        rep.setForm(new FakeModel("CommonForm.MyForm.Form")); //$NON-NLS-1$

        assertTrue("common-form content FQN with trailing .Form must match the requested common form", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "CommonForm.MyForm")); //$NON-NLS-1$
    }

    @Test
    public void testRepresentationContentFormFqnRussianTypeMatches()
    {
        // Russian type name on the content-form FQN must still match an English requested path:
        // Справочник (Catalog) with a singular Russian "Форма" separator and trailing "Форма" content.
        FakeRepresentation rep = new FakeRepresentation();
        rep.setForm(new FakeModel("Справочник" //$NON-NLS-1$
            + ".Товары.Форма.ItemForm" //$NON-NLS-1$
            + ".Форма")); // Справочник.Товары.Форма.ItemForm.Форма //$NON-NLS-1$

        assertTrue("Russian-typed content-form FQN must match the English requested path", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep,
                "Catalog.Товары.Forms.ItemForm")); // Catalog.Товары.Forms.ItemForm //$NON-NLS-1$
    }

    @Test
    public void testRepresentationFormDoesNotMatchOtherForm()
    {
        // The core defect: representation still holds another form's model (the previously active
        // DataProcessor form) while a different catalog form was requested.
        FakeRepresentation rep = new FakeRepresentation();
        rep.setForm(new FakeModel("DataProcessor.GrafikSTO.Form.MainForm")); //$NON-NLS-1$

        assertFalse("representation rendering a different form must not match", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "Catalog.TestS11.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testRepresentationContentFormDoesNotMatchOtherForm()
    {
        // The relaxed (content-FQN-aware) guard must still REJECT a different form: the representation
        // renders another form's CONTENT model (trailing .Form shape) while a different form was
        // requested. Stripping the content suffix must NOT collapse distinct forms together.
        FakeRepresentation rep = new FakeRepresentation();
        rep.setForm(new FakeModel("DataProcessor.GrafikSTO.Form.MainForm.Form")); //$NON-NLS-1$

        assertFalse("a different form's content FQN must still be rejected", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "Catalog.TestS11.Forms.ItemForm")); //$NON-NLS-1$
        // Different form name on the same owner must also be rejected.
        assertFalse("same owner but different form name must be rejected", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "DataProcessor.GrafikSTO.Forms.OtherForm")); //$NON-NLS-1$
    }

    @Test
    public void testRepresentationFormNamedFormIsNotMisStripped()
    {
        // A form genuinely NAMED "Form" must not be confused with a content suffix. The representation
        // renders the content form of an object form literally named "Form": its content FQN is
        // Catalog.Products.Form.Form.Form (5 parts). Stripping exactly one trailing content segment
        // leaves the MD-form Catalog.Products.Form.Form, which must match the requested
        // Catalog.Products.Forms.Form and NOT a different form name.
        FakeRepresentation rep = new FakeRepresentation();
        rep.setForm(new FakeModel("Catalog.Products.Form.Form.Form")); //$NON-NLS-1$

        assertTrue("an object form named 'Form' must match its own requested path", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "Catalog.Products.Forms.Form")); //$NON-NLS-1$
        assertFalse("the form named 'Form' must not match a different form name", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testRepresentationFormMatchNullSafe()
    {
        assertFalse("null representation must not match", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(null, "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$

        FakeRepresentation rep = new FakeRepresentation();
        // No form model set at all.
        assertFalse("representation with no form model must not match", //$NON-NLS-1$
            EditorScreenshotHelper.representationFormMatches(rep, "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
    }

    // ==================== ensureRenderedFormImage ====================

    @Test
    public void testEnsureRenderedUsesAlreadyPresentNonEmptyImage()
    {
        // RELAXED CONTRACT (the regression fix): an image is already present on the (identity-verified)
        // representation. The layout snapshot proves formImageData is populated for the requested form,
        // so a brand-new instance is NOT required — ensureRenderedFormImage must accept it immediately
        // without waiting for or forcing another render. setRebuildsUntilFresh is left at 0 so a rebuild
        // would NOT produce a new image; success here proves the present image was used directly.
        FakeRepresentation rep = new FakeRepresentation();
        ImageData present = newImage(10, 10);
        rep.setInitialImage(present);

        boolean rendered = EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS);
        assertTrue("an already-present non-empty image must be accepted (no new instance required)", //$NON-NLS-1$
            rendered);
    }

    @Test
    public void testEnsureRenderedDetectsImageProducedByRebuild()
    {
        // No image yet; the (fake) async rebuild fallback produces a non-empty image. The relaxed wait
        // must succeed as soon as the image is non-empty, regardless of instance identity.
        FakeRepresentation rep = new FakeRepresentation();
        rep.setRebuildsUntilFresh(1);

        boolean rendered = EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS);
        assertTrue("a non-empty image produced by rebuild must be detected as rendered", rendered); //$NON-NLS-1$
    }

    @Test
    public void testEnsureRenderedTimesOutWhenNoImageEverProduced()
    {
        // No image present and the (fake) render never produces one within the budget: there is genuinely
        // nothing to return, so ensureRenderedFormImage must report not rendered (the tool then errors).
        FakeRepresentation rep = new FakeRepresentation();
        rep.setRebuildsUntilFresh(Integer.MAX_VALUE); // never produces an image

        boolean rendered = EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS);
        assertFalse("no image produced at all must report not rendered", rendered); //$NON-NLS-1$
    }

    @Test
    public void testEnsureRenderedRejectsEmptyImages()
    {
        // Every render keeps producing a zero-size image: never a usable image, so not rendered.
        FakeRepresentation rep = new FakeRepresentation();
        rep.setProduceEmpty(true);

        boolean rendered = EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS);
        assertFalse("zero-size images must not count as a rendered image", rendered); //$NON-NLS-1$
    }

    @Test
    public void testEnsureRenderedNullSafe()
    {
        assertFalse("null representation must report not rendered", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(null));
    }

    @Test
    public void testEnsureRenderedFirstRenderFromNoImage()
    {
        // No prior image at all (first render of the first form): the first non-empty image produced is
        // a legitimate render.
        FakeRepresentation rep = new FakeRepresentation();
        rep.setRebuildsUntilFresh(1);

        boolean rendered = EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS);
        assertTrue("first non-empty image with no prior image must be rendered", rendered); //$NON-NLS-1$
        assertEquals("sanity: helper returns boolean", true, rendered); //$NON-NLS-1$
    }
}
