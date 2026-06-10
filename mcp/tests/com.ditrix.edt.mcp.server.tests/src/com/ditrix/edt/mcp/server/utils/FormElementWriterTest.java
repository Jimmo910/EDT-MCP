/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormObjectRef;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.Kind;

/**
 * Tests the pure, model-independent logic of {@link FormElementWriter}: the bilingual kind-token map
 * and the form-member FQN parser. The model-dependent write path (the cross-model hop + the EMF
 * mutation) is covered by the e2e suite against a live form.
 *
 * <p>Russian tokens are built from code points (independently of the writer's own construction) so
 * the assertion verifies the real Cyrillic mapping, not a round-trip of the same literal.</p>
 */
public class FormElementWriterTest
{
    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    @Test
    public void testKindForEnglishTokens()
    {
        assertEquals(Kind.ATTRIBUTE, FormElementWriter.kindForToken("Attribute")); //$NON-NLS-1$
        assertEquals(Kind.ATTRIBUTE, FormElementWriter.kindForToken("attributes")); //$NON-NLS-1$
        assertEquals(Kind.COMMAND, FormElementWriter.kindForToken("Command")); //$NON-NLS-1$
        assertEquals(Kind.GROUP, FormElementWriter.kindForToken("group")); //$NON-NLS-1$
        assertEquals(Kind.DECORATION, FormElementWriter.kindForToken("Decoration")); //$NON-NLS-1$
        assertEquals(Kind.FIELD, FormElementWriter.kindForToken("Field")); //$NON-NLS-1$
        assertEquals(Kind.BUTTON, FormElementWriter.kindForToken("Button")); //$NON-NLS-1$
    }

    @Test
    public void testKindForRussianTokens()
    {
        // rekvizit -> ATTRIBUTE
        assertEquals(Kind.ATTRIBUTE, FormElementWriter.kindForToken(
            fromCp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442)));
        // komanda -> COMMAND
        assertEquals(Kind.COMMAND, FormElementWriter.kindForToken(
            fromCp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430)));
        // gruppa -> GROUP
        assertEquals(Kind.GROUP, FormElementWriter.kindForToken(
            fromCp(0x0433, 0x0440, 0x0443, 0x043f, 0x043f, 0x0430)));
        // dekoraciya -> DECORATION
        assertEquals(Kind.DECORATION, FormElementWriter.kindForToken(
            fromCp(0x0434, 0x0435, 0x043a, 0x043e, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f)));
        // pole -> FIELD
        assertEquals(Kind.FIELD, FormElementWriter.kindForToken(fromCp(0x043f, 0x043e, 0x043b, 0x0435)));
        // knopka -> BUTTON
        assertEquals(Kind.BUTTON, FormElementWriter.kindForToken(
            fromCp(0x043a, 0x043d, 0x043e, 0x043f, 0x043a, 0x0430)));
    }

    @Test
    public void testKindForUnknownAndNull()
    {
        assertNull(FormElementWriter.kindForToken("Nonsense")); //$NON-NLS-1$
        assertNull(FormElementWriter.kindForToken("Table")); // not a supported form kind yet //$NON-NLS-1$
        assertNull(FormElementWriter.kindForToken(null));
    }

    @Test
    public void testParseManagedFormMember()
    {
        FormMemberRef ref = FormElementWriter.parse("Catalog.Products.Form.ItemForm.Command.Refresh"); //$NON-NLS-1$
        assertNotNull(ref);
        // The form path is normalized to the 'forms' shape resolveMdForm expects.
        assertEquals("Catalog.Products.forms.ItemForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Command", ref.kindToken); //$NON-NLS-1$
        assertEquals("Refresh", ref.name); //$NON-NLS-1$
    }

    @Test
    public void testParseManagedFormMemberRussianToken()
    {
        // "Форма" (forma) as the form token is accepted and normalized to 'forms'.
        String fqn = "Catalog.Products." + fromCp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430) //$NON-NLS-1$
            + ".ItemForm.Attribute.A"; //$NON-NLS-1$
        FormMemberRef ref = FormElementWriter.parse(fqn);
        assertNotNull(ref);
        assertEquals("Catalog.Products.forms.ItemForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Attribute", ref.kindToken); //$NON-NLS-1$
        assertEquals("A", ref.name); //$NON-NLS-1$
    }

    @Test
    public void testIsHandlerToken()
    {
        assertEquals(Boolean.TRUE, Boolean.valueOf(FormElementWriter.isHandlerToken("Handler"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, Boolean.valueOf(FormElementWriter.isHandlerToken("handler"))); //$NON-NLS-1$
        // obrabotchik -> handler
        assertEquals(Boolean.TRUE, Boolean.valueOf(FormElementWriter.isHandlerToken(
            fromCp(0x043e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x0447, 0x0438, 0x043a))));
        assertEquals(Boolean.FALSE, Boolean.valueOf(FormElementWriter.isHandlerToken("Command"))); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, Boolean.valueOf(FormElementWriter.isHandlerToken(null)));
        // a Handler token is NOT a member Kind (it routes to the handler path, not createMember)
        assertNull(FormElementWriter.kindForToken("Handler")); //$NON-NLS-1$
    }

    @Test
    public void testParseHandlerFqnRoutesAsHandler()
    {
        // Form-level handler: leaf is the event name; the token routes to the handler path.
        FormMemberRef ref = FormElementWriter.parse("Catalog.Products.Form.ItemForm.Handler.OnOpen"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("Catalog.Products.forms.ItemForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Handler", ref.kindToken); //$NON-NLS-1$
        assertEquals("OnOpen", ref.name); //$NON-NLS-1$
        // A form-level handler is NOT item-level.
        assertNull(ref.itemName);
        assertEquals(Boolean.FALSE, Boolean.valueOf(ref.isItemLevel()));
    }

    @Test
    public void testParseItemLevelHandlerManagedForm()
    {
        // Item-level handler: ItemKind.ItemName.Handler.Event (the leaf is the event, the item carries
        // the owning element name).
        FormMemberRef ref =
            FormElementWriter.parse("Catalog.Products.Form.ItemForm.Field.Price.Handler.OnChange"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("Catalog.Products.forms.ItemForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Handler", ref.kindToken); //$NON-NLS-1$
        assertEquals("OnChange", ref.name); //$NON-NLS-1$
        assertEquals("Field", ref.itemKindToken); //$NON-NLS-1$
        assertEquals("Price", ref.itemName); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, Boolean.valueOf(ref.isItemLevel()));
    }

    @Test
    public void testParseItemLevelHandlerCommonForm()
    {
        FormMemberRef ref =
            FormElementWriter.parse("CommonForm.MyForm.Field.Price.Handler.OnChange"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("CommonForm.MyForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Handler", ref.kindToken); //$NON-NLS-1$
        assertEquals("OnChange", ref.name); //$NON-NLS-1$
        assertEquals("Field", ref.itemKindToken); //$NON-NLS-1$
        assertEquals("Price", ref.itemName); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, Boolean.valueOf(ref.isItemLevel()));
    }

    @Test
    public void testParseItemLevelNonHandlerReturnsNull()
    {
        // A 4-token remainder whose third token is NOT a handler token is not a recognized form member.
        assertNull(FormElementWriter.parse("Catalog.Products.Form.ItemForm.Field.Price.Command.X")); //$NON-NLS-1$
        // A 3-token remainder (odd length) is not a recognized form member either.
        assertNull(FormElementWriter.parse("Catalog.Products.Form.ItemForm.Field.Price.Handler")); //$NON-NLS-1$
    }

    @Test
    public void testParseCommonFormMember()
    {
        FormMemberRef ref = FormElementWriter.parse("CommonForm.MyForm.Attribute.Field1"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("CommonForm.MyForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Attribute", ref.kindToken); //$NON-NLS-1$
        assertEquals("Field1", ref.name); //$NON-NLS-1$
    }

    @Test
    public void testParseFormPathManagedAndCommon()
    {
        // A managed-form FQN (Type.Object.Form.FormName) normalizes to the 'forms' shape resolveMdForm
        // expects; the form token is bilingual.
        assertEquals("Catalog.Products.forms.ItemForm", //$NON-NLS-1$
            FormElementWriter.parseFormPath("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
        assertEquals("Catalog.Products.forms.ItemForm", //$NON-NLS-1$
            FormElementWriter.parseFormPath("Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
        // Russian "Форма" (forma) form token.
        String ru = "Catalog.Products." + fromCp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430) + ".ItemForm"; //$NON-NLS-1$
        assertEquals("Catalog.Products.forms.ItemForm", FormElementWriter.parseFormPath(ru)); //$NON-NLS-1$
        // A CommonForm (2 parts) IS a form.
        assertEquals("CommonForm.MyForm", FormElementWriter.parseFormPath("CommonForm.MyForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseFormPathRejectsNonForm()
    {
        // A plain top object is NOT a form FQN (must fall through to the normal object path).
        assertNull(FormElementWriter.parseFormPath("Catalog.Products")); //$NON-NLS-1$
        // A 4-part mdclass member (no form token at position 2) is not a form FQN.
        assertNull(FormElementWriter.parseFormPath("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        // A nested member FQN is not a form FQN.
        assertNull(FormElementWriter.parseFormPath("Catalog.Products.TabularSection.Lines.Attribute.Qty")); //$NON-NLS-1$
        assertNull(FormElementWriter.parseFormPath(null));
    }

    @Test
    public void testParseNonFormFqnReturnsNull()
    {
        // A plain mdclass member (no form token at position 2) is NOT a form member.
        assertNull(FormElementWriter.parse("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        assertNull(FormElementWriter.parse("Catalog.Products.TabularSection.Lines.Attribute.Qty")); //$NON-NLS-1$
        // A top object / too-short FQN is not a form member.
        assertNull(FormElementWriter.parse("Catalog.Products")); //$NON-NLS-1$
        assertNull(FormElementWriter.parse(null));
    }

    // ---- form-OBJECT create FQN parse (F1) -------------------------------------------------------

    @Test
    public void testParseFormObjectCreateManaged()
    {
        // A 4-part form FQN addresses the FORM OBJECT to create (owner type/name + form name).
        FormObjectRef ref = FormElementWriter.parseFormObjectCreate("Catalog.Products.Form.ItemForm"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("Catalog", ref.ownerType); //$NON-NLS-1$
        assertEquals("Products", ref.ownerName); //$NON-NLS-1$
        assertEquals("ItemForm", ref.formName); //$NON-NLS-1$
        assertEquals("Catalog.Products", ref.ownerFqn()); //$NON-NLS-1$
    }

    @Test
    public void testParseFormObjectCreateBilingualFormToken()
    {
        // The form token is bilingual: "Форма" (forma) and "Forms" are both accepted.
        String ru = "Catalog.Products." + fromCp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430) + ".F"; //$NON-NLS-1$
        FormObjectRef ref = FormElementWriter.parseFormObjectCreate(ru);
        assertNotNull(ref);
        assertEquals("F", ref.formName); //$NON-NLS-1$
        assertNotNull(FormElementWriter.parseFormObjectCreate("Document.Inv.Forms.MainForm")); //$NON-NLS-1$
    }

    @Test
    public void testParseFormObjectCreateRejectsNonFormObject()
    {
        // A form MEMBER FQN (6 parts) is NOT a form-object create (it routes to parse()).
        assertNull(FormElementWriter.parseFormObjectCreate("Catalog.Products.Form.ItemForm.Attribute.A")); //$NON-NLS-1$
        // A 4-part mdclass member (no form token at position 2) is not a form-object create.
        assertNull(FormElementWriter.parseFormObjectCreate("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        // A CommonForm (2 parts) IS a top object - created via the normal top-level path, not here.
        assertNull(FormElementWriter.parseFormObjectCreate("CommonForm.MyForm")); //$NON-NLS-1$
        // A plain top object / null is not a form-object create.
        assertNull(FormElementWriter.parseFormObjectCreate("Catalog.Products")); //$NON-NLS-1$
        assertNull(FormElementWriter.parseFormObjectCreate(null));
    }

    // ---- move / reorder position resolution (F2) -------------------------------------------------

    private static final List<String> SIBLINGS = Arrays.asList("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    @Test
    public void testPositionLastAndDefault()
    {
        // null / blank / "last" -> the end (the dest list already EXCLUDES the moved item).
        assertEquals(3, FormElementWriter.resolveMovePosition(null, SIBLINGS, "X")); //$NON-NLS-1$
        assertEquals(3, FormElementWriter.resolveMovePosition("", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, FormElementWriter.resolveMovePosition("last", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, FormElementWriter.resolveMovePosition("LAST", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPositionFirst()
    {
        assertEquals(0, FormElementWriter.resolveMovePosition("first", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, FormElementWriter.resolveMovePosition("First", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPositionBeforeAndAfter()
    {
        // before:<name> = the sibling's own index; after:<name> = its index + 1 (case-insensitive).
        assertEquals(0, FormElementWriter.resolveMovePosition("before:A", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, FormElementWriter.resolveMovePosition("before:B", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, FormElementWriter.resolveMovePosition("after:A", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, FormElementWriter.resolveMovePosition("after:C", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, FormElementWriter.resolveMovePosition("BEFORE:c", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPositionInteger()
    {
        // A plain integer is the desired FINAL 0-based index as-is (no off-by-one compensation).
        assertEquals(0, FormElementWriter.resolveMovePosition("0", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, FormElementWriter.resolveMovePosition(" 2 ", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        // An index beyond the list end is returned verbatim; moveItem() then clamps it to the end.
        assertEquals(9, FormElementWriter.resolveMovePosition("9", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPositionMalformedRejected()
    {
        assertMoveError("nonsense", SIBLINGS, "X", "Invalid position"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertMoveError("-1", SIBLINGS, "X", "zero or positive"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPositionUnknownSiblingRejected()
    {
        assertMoveError("before:Z", SIBLINGS, "X", "not found"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertMoveError("after:", SIBLINGS, "X", "missing a sibling name"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPositionCannotReferenceMovedItem()
    {
        // A before:/after: must not name the moved item itself (it is absent from the dest list anyway).
        assertMoveError("before:B", SIBLINGS, "B", "the moved item itself"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertMoveError("after:b", SIBLINGS, "B", "the moved item itself"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPositionFirstLastOnEmptyDest()
    {
        // Into an empty group both first and last resolve to index 0.
        List<String> empty = Collections.emptyList();
        assertEquals(0, FormElementWriter.resolveMovePosition("first", empty, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, FormElementWriter.resolveMovePosition("last", empty, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, FormElementWriter.resolveMovePosition(null, empty, "X")); //$NON-NLS-1$
    }

    private static void assertMoveError(String position, List<String> dest, String moved, String fragment)
    {
        try
        {
            FormElementWriter.resolveMovePosition(position, dest, moved);
            fail("expected a RuntimeException for position '" + position + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e)
        {
            assertNotNull(e.getMessage());
            assertTrue("message should mention '" + fragment + "' but was: " + e.getMessage(), //$NON-NLS-1$ //$NON-NLS-2$
                e.getMessage().contains(fragment));
        }
    }

    // ---- form-token predicate (D3: shared with MetadataPathResolver) -----------------------------

    @Test
    public void testIsFormTokenAcceptsEnglishAndRussianSingularPlural()
    {
        assertTrue(FormElementWriter.isFormToken("Form")); //$NON-NLS-1$
        assertTrue(FormElementWriter.isFormToken("forms")); //$NON-NLS-1$
        assertTrue(FormElementWriter.isFormToken("FORMS")); //$NON-NLS-1$
        // Forma (capital F-cyrillic, the predicate lowercases) -> accepted.
        assertTrue(FormElementWriter.isFormToken(fromCp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430)));
        // Formy (plural) -> accepted.
        assertTrue(FormElementWriter.isFormToken(fromCp(0x0424, 0x043e, 0x0440, 0x043c, 0x044b)));
    }

    @Test
    public void testIsFormTokenRejectsOthers()
    {
        assertFalse(FormElementWriter.isFormToken("Template")); //$NON-NLS-1$
        assertFalse(FormElementWriter.isFormToken("CommonForm")); //$NON-NLS-1$
        assertFalse(FormElementWriter.isFormToken("")); //$NON-NLS-1$
        assertFalse(FormElementWriter.isFormToken(null));
    }

    // ---- moveItem destination contract (D1: blank / form-name parent -> the form root) -----------

    private static final MoveLikeModel MOVE = new MoveLikeModel();

    @Test
    public void testMoveItemBlankParentMovesToFormRoot()
    {
        // The javadoc contract: a BLANK targetParent means the FORM ROOT - it must re-parent, not fall
        // into the reorder-in-place branch (which would silently leave the item in its group).
        EObject form = MOVE.newForm();
        EObject group = MOVE.newGroup("G"); //$NON-NLS-1$
        EObject field = MOVE.newField("F"); //$NON-NLS-1$
        MOVE.itemsOf(form).add(group);
        MOVE.itemsOf(group).add(field);

        String dest = FormElementWriter.moveItem(form, "F", "", null, "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dest, dest.contains("the form root")); //$NON-NLS-1$
        assertSame(form, field.eContainer());
        assertTrue(MOVE.itemsOf(group).isEmpty());
        assertEquals(2, MOVE.itemsOf(form).size());
    }

    @Test
    public void testMoveItemFormNameParentMovesToFormRoot()
    {
        // The form name (case-insensitive) as targetParent is the other spelling of "the form root".
        EObject form = MOVE.newForm();
        EObject group = MOVE.newGroup("G"); //$NON-NLS-1$
        EObject field = MOVE.newField("F"); //$NON-NLS-1$
        MOVE.itemsOf(form).add(group);
        MOVE.itemsOf(group).add(field);

        String dest = FormElementWriter.moveItem(form, "F", "myform", null, "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dest, dest.contains("the form root")); //$NON-NLS-1$
        assertSame(form, field.eContainer());
    }

    @Test
    public void testMoveItemNullParentReordersInCurrentContainer()
    {
        // null targetParent keeps the current container (reorder in place) - never re-parents.
        EObject form = MOVE.newForm();
        EObject group = MOVE.newGroup("G"); //$NON-NLS-1$
        EObject a = MOVE.newField("A"); //$NON-NLS-1$
        EObject b = MOVE.newField("B"); //$NON-NLS-1$
        MOVE.itemsOf(form).add(group);
        MOVE.itemsOf(group).add(a);
        MOVE.itemsOf(group).add(b);

        String dest = FormElementWriter.moveItem(form, "A", null, "last", "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dest, dest.contains("group 'G'")); //$NON-NLS-1$
        assertSame(group, a.eContainer());
        assertEquals(2, MOVE.itemsOf(group).size());
        assertSame(b, MOVE.itemsOf(group).get(0));
        assertSame(a, MOVE.itemsOf(group).get(1));
    }

    @Test
    public void testMoveItemNamedGroupParentStillReparents()
    {
        // Regression guard for the changed branch predicate: a real group name still re-parents into
        // that group.
        EObject form = MOVE.newForm();
        EObject group = MOVE.newGroup("G"); //$NON-NLS-1$
        EObject field = MOVE.newField("F"); //$NON-NLS-1$
        MOVE.itemsOf(form).add(group);
        MOVE.itemsOf(form).add(field);

        String dest = FormElementWriter.moveItem(form, "F", "G", null, "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dest, dest.contains("group 'G'")); //$NON-NLS-1$
        assertSame(group, field.eContainer());
    }

    /**
     * A tiny dynamic EMF metamodel reproducing the reflective feature names {@code moveItem} navigates
     * ({@code items} / {@code name}) and the {@code FormGroup} classifier name {@code findUniqueGroup}
     * matches, so the move destination contract is testable without the real form model (the same
     * approach {@code FormStructureReaderTest} uses).
     */
    private static final class MoveLikeModel
    {
        final EClass form;
        final EClass formGroup;
        final EClass formField;
        final EAttribute itemName;

        MoveLikeModel()
        {
            EcoreFactory factory = EcoreFactory.eINSTANCE;
            EPackage pkg = factory.createEPackage();
            pkg.setName("movelike"); //$NON-NLS-1$
            pkg.setNsPrefix("movelike"); //$NON-NLS-1$
            pkg.setNsURI("http://ditrix.com/test/movelike"); //$NON-NLS-1$

            EClass formItem = factory.createEClass();
            formItem.setName("FormItem"); //$NON-NLS-1$
            formItem.setAbstract(true);
            itemName = factory.createEAttribute();
            itemName.setName("name"); //$NON-NLS-1$
            itemName.setEType(EcorePackage.Literals.ESTRING);
            formItem.getEStructuralFeatures().add(itemName);

            // The classifier NAME matters: findUniqueGroup matches eClass().getName() == "FormGroup".
            formGroup = factory.createEClass();
            formGroup.setName("FormGroup"); //$NON-NLS-1$
            formGroup.getESuperTypes().add(formItem);
            formGroup.getEStructuralFeatures().add(itemsReference(factory, formItem));

            formField = factory.createEClass();
            formField.setName("FormField"); //$NON-NLS-1$
            formField.getESuperTypes().add(formItem);

            form = factory.createEClass();
            form.setName("Form"); //$NON-NLS-1$
            form.getEStructuralFeatures().add(itemsReference(factory, formItem));

            pkg.getEClassifiers().add(formItem);
            pkg.getEClassifiers().add(formGroup);
            pkg.getEClassifiers().add(formField);
            pkg.getEClassifiers().add(form);
        }

        EObject newForm()
        {
            return new DynamicEObjectImpl(form);
        }

        EObject newGroup(String name)
        {
            EObject group = new DynamicEObjectImpl(formGroup);
            group.eSet(itemName, name);
            return group;
        }

        EObject newField(String name)
        {
            EObject field = new DynamicEObjectImpl(formField);
            field.eSet(itemName, name);
            return field;
        }

        @SuppressWarnings("unchecked")
        List<EObject> itemsOf(EObject container)
        {
            return (List<EObject>)container.eGet(container.eClass().getEStructuralFeature("items")); //$NON-NLS-1$
        }

        private static EReference itemsReference(EcoreFactory factory, EClass itemType)
        {
            EReference reference = factory.createEReference();
            reference.setName("items"); //$NON-NLS-1$
            reference.setEType(itemType);
            reference.setContainment(true);
            reference.setUpperBound(-1);
            return reference;
        }
    }
}
