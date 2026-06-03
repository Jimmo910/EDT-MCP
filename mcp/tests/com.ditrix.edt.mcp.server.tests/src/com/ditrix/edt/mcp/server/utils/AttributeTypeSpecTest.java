/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.AttributeTypeSpec.DateFraction;
import com.ditrix.edt.mcp.server.utils.AttributeTypeSpec.Item;

/**
 * Unit tests for {@link AttributeTypeSpec} - the pure (EDT-runtime independent)
 * parser of the {@code type} parameter shared by {@code add_metadata_attribute}
 * and {@code add_form_attribute}.
 */
public class AttributeTypeSpecTest
{
    @Test
    public void testSimpleString()
    {
        AttributeTypeSpec spec = AttributeTypeSpec.parse("String"); //$NON-NLS-1$
        assertFalse(spec.isComposite());
        assertEquals(1, spec.getItems().size());
        Item item = spec.getItems().get(0);
        assertEquals("String", item.name); //$NON-NLS-1$
        assertTrue(item.isString());
        assertNull(item.stringLength);
        assertNull(item.stringFixed);
    }

    @Test
    public void testStringWithLength()
    {
        Item item = AttributeTypeSpec.parse("String(50)").getItems().get(0); //$NON-NLS-1$
        assertEquals(Integer.valueOf(50), item.stringLength);
        assertNull(item.stringFixed);
    }

    @Test
    public void testStringWithLengthAndFixed()
    {
        Item item = AttributeTypeSpec.parse("String(50,fixed)").getItems().get(0); //$NON-NLS-1$
        assertEquals(Integer.valueOf(50), item.stringLength);
        assertEquals(Boolean.TRUE, item.stringFixed);
    }

    @Test
    public void testStringVariableFlag()
    {
        Item item = AttributeTypeSpec.parse("String(10, variable)").getItems().get(0); //$NON-NLS-1$
        assertEquals(Integer.valueOf(10), item.stringLength);
        assertEquals(Boolean.FALSE, item.stringFixed);
    }

    @Test
    public void testNumberPrecisionScale()
    {
        Item item = AttributeTypeSpec.parse("Number(15,2)").getItems().get(0); //$NON-NLS-1$
        assertTrue(item.isNumber());
        assertEquals(Integer.valueOf(15), item.numberPrecision);
        assertEquals(Integer.valueOf(2), item.numberScale);
        assertNull(item.numberNonNegative);
    }

    @Test
    public void testNumberNonNegative()
    {
        Item item = AttributeTypeSpec.parse("Number(10,0,nonnegative)").getItems().get(0); //$NON-NLS-1$
        assertEquals(Integer.valueOf(10), item.numberPrecision);
        assertEquals(Integer.valueOf(0), item.numberScale);
        assertEquals(Boolean.TRUE, item.numberNonNegative);
    }

    @Test
    public void testNumberPrecisionOnly()
    {
        Item item = AttributeTypeSpec.parse("Number(10)").getItems().get(0); //$NON-NLS-1$
        assertEquals(Integer.valueOf(10), item.numberPrecision);
        assertNull(item.numberScale);
    }

    @Test
    public void testDateFractions()
    {
        assertEquals(DateFraction.DATE,
            AttributeTypeSpec.parse("Date(Date)").getItems().get(0).dateFractions); //$NON-NLS-1$
        assertEquals(DateFraction.TIME,
            AttributeTypeSpec.parse("Date(Time)").getItems().get(0).dateFractions); //$NON-NLS-1$
        assertEquals(DateFraction.DATE_TIME,
            AttributeTypeSpec.parse("Date(DateTime)").getItems().get(0).dateFractions); //$NON-NLS-1$
        assertNull(AttributeTypeSpec.parse("Date").getItems().get(0).dateFractions); //$NON-NLS-1$
    }

    @Test
    public void testReferenceType()
    {
        Item item = AttributeTypeSpec.parse("CatalogRef.Products").getItems().get(0); //$NON-NLS-1$
        assertEquals("CatalogRef.Products", item.name); //$NON-NLS-1$
        assertFalse(item.isString());
        assertFalse(item.isNumber());
        assertFalse(item.isDate());
    }

    @Test
    public void testBoolean()
    {
        Item item = AttributeTypeSpec.parse("Boolean").getItems().get(0); //$NON-NLS-1$
        assertEquals("Boolean", item.name); //$NON-NLS-1$
    }

    @Test
    public void testCompositeType()
    {
        AttributeTypeSpec spec = AttributeTypeSpec.parse("String(10), CatalogRef.Products, Number(5,0)"); //$NON-NLS-1$
        assertTrue(spec.isComposite());
        List<Item> items = spec.getItems();
        assertEquals(3, items.size());
        assertEquals("String", items.get(0).name); //$NON-NLS-1$
        assertEquals(Integer.valueOf(10), items.get(0).stringLength);
        assertEquals("CatalogRef.Products", items.get(1).name); //$NON-NLS-1$
        assertEquals("Number", items.get(2).name); //$NON-NLS-1$
        assertEquals(Integer.valueOf(5), items.get(2).numberPrecision);
    }

    @Test
    public void testCommaInsideParensIsNotASeparator()
    {
        // The comma in Number(15,2) must NOT split the spec into two items.
        AttributeTypeSpec spec = AttributeTypeSpec.parse("Number(15,2)"); //$NON-NLS-1$
        assertEquals(1, spec.getItems().size());
    }

    @Test
    public void testRussianReferenceObjectName()
    {
        // "CatalogRef." + a Russian object name, built from code points to keep
        // this source file ASCII-only, matching repo convention.
        String russianName = new String(new char[] {
            0x041a, 0x043e, 0x043d, 0x0442, 0x0440, 0x0430, 0x0433, 0x0435, 0x043d, 0x0442, 0x044b });
        String ref = "CatalogRef." + russianName; //$NON-NLS-1$
        Item item = AttributeTypeSpec.parse(ref).getItems().get(0);
        assertEquals(ref, item.name);
    }

    @Test
    public void testNullSpecRejected()
    {
        assertParseFails(null);
    }

    @Test
    public void testEmptySpecRejected()
    {
        assertParseFails("   "); //$NON-NLS-1$
    }

    @Test
    public void testUnbalancedParenRejected()
    {
        assertParseFails("String(50"); //$NON-NLS-1$
        assertParseFails("String50)"); //$NON-NLS-1$
    }

    @Test
    public void testNonIntegerLengthRejected()
    {
        assertParseFails("String(abc)"); //$NON-NLS-1$
    }

    @Test
    public void testZeroLengthMeansUnlimitedString()
    {
        // String(0) is the standard 1C representation of an unlimited-length
        // string (used e.g. for a document Comment attribute). It must parse to
        // a String item with an explicit length of 0, not be rejected.
        Item item = AttributeTypeSpec.parse("String(0)").getItems().get(0); //$NON-NLS-1$
        assertTrue(item.isString());
        assertEquals(Integer.valueOf(0), item.stringLength);
        assertNull(item.stringFixed);
    }

    @Test
    public void testZeroLengthUnlimitedStringInComposite()
    {
        // An unlimited String must also be accepted as part of a composite type.
        AttributeTypeSpec spec = AttributeTypeSpec.parse("String(0), CatalogRef.Products"); //$NON-NLS-1$
        assertTrue(spec.isComposite());
        assertEquals(Integer.valueOf(0), spec.getItems().get(0).stringLength);
    }

    @Test
    public void testNegativeStringLengthRejected()
    {
        // A negative length is still invalid; only 0 (unlimited) and positives are allowed.
        assertParseFails("String(-1)"); //$NON-NLS-1$
    }

    @Test
    public void testNegativeNumberPrecisionRejected()
    {
        assertParseFails("Number(-1,0)"); //$NON-NLS-1$
    }

    @Test
    public void testQualifiersOnReferenceTypeRejected()
    {
        // Only String/Number/Date accept qualifiers.
        assertParseFails("CatalogRef.Products(5)"); //$NON-NLS-1$
    }

    @Test
    public void testUnknownDateCompositionRejected()
    {
        assertParseFails("Date(Yesterday)"); //$NON-NLS-1$
    }

    private static void assertParseFails(String spec)
    {
        try
        {
            AttributeTypeSpec.parse(spec);
            fail("Expected IllegalArgumentException for: " + spec); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            // ok
        }
    }
}
