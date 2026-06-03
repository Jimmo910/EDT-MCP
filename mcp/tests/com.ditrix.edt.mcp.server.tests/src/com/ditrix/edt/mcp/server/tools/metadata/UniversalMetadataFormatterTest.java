/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.AutoColor;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StyleElementType;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;

/**
 * Tests for {@link UniversalMetadataFormatter}, focused on rendering the
 * single-valued {@link StyleItem} value (S14, #19891).
 * <p>
 * The value set by {@code set_style_item_value} is a containment reference that is
 * not a collection, so the generic dynamic-property and containment-collection
 * passes skip it; without the dedicated branch the color/font would be invisible
 * in {@code get_metadata_details}. These tests build the mcore value objects via
 * {@link McoreFactory} (the test bundle is a fragment of the host, so EMF
 * factories are available) and assert the rendered Markdown shows the value.
 */
public class UniversalMetadataFormatterTest
{
    private final UniversalMetadataFormatter formatter = UniversalMetadataFormatter.getInstance();

    private static StyleItem styleItem(String name)
    {
        StyleItem item = MdClassFactory.eINSTANCE.createStyleItem();
        item.setName(name);
        return item;
    }

    @Test
    public void testRgbColorValueIsRendered()
    {
        StyleItem item = styleItem("MyColor"); //$NON-NLS-1$
        item.setType(StyleElementType.COLOR);
        ColorValue colorValue = McoreFactory.eINSTANCE.createColorValue();
        ColorDef colorDef = McoreFactory.eINSTANCE.createColorDef();
        colorDef.setRed(255);
        colorDef.setGreen(128);
        colorDef.setBlue(0);
        colorValue.setValue(colorDef);
        item.setValue(colorValue);

        String basic = formatter.format(item, false, "en"); //$NON-NLS-1$
        assertTrue("basic mode must show the color", basic.contains("RGB(255, 128, 0)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("basic mode must label the Value section", basic.contains("Value")); //$NON-NLS-1$ //$NON-NLS-2$

        String full = formatter.format(item, true, "en"); //$NON-NLS-1$
        assertTrue("full mode must show the color", full.contains("RGB(255, 128, 0)")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAutoColorValueIsRendered()
    {
        StyleItem item = styleItem("AutoColor"); //$NON-NLS-1$
        item.setType(StyleElementType.COLOR);
        ColorValue colorValue = McoreFactory.eINSTANCE.createColorValue();
        AutoColor autoColor = McoreFactory.eINSTANCE.createAutoColor();
        colorValue.setValue(autoColor);
        item.setValue(colorValue);

        String out = formatter.format(item, false, "en"); //$NON-NLS-1$
        // AutoColor extends ColorDef, so it must be reported as Auto, not as RGB.
        assertTrue("must show Auto", out.contains("Auto")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must not show RGB for an automatic color", !out.contains("RGB(")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFontValueIsRendered()
    {
        StyleItem item = styleItem("MyFont"); //$NON-NLS-1$
        item.setType(StyleElementType.FONT);
        FontValue fontValue = McoreFactory.eINSTANCE.createFontValue();
        FontDef fontDef = McoreFactory.eINSTANCE.createFontDef();
        fontDef.setFaceName("Arial"); //$NON-NLS-1$
        fontDef.setHeight(12f);
        fontDef.setBold(true);
        fontDef.setItalic(true);
        fontValue.setValue(fontDef);
        item.setValue(fontValue);

        String out = formatter.format(item, false, "en"); //$NON-NLS-1$
        assertTrue("must show the face name", out.contains("Arial")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must show the height", out.contains("height=12")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must show bold", out.contains("bold")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must show italic", out.contains("italic")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStyleItemWithoutValueDoesNotFail()
    {
        // A freshly created StyleItem has no value yet; the formatter must still
        // render without throwing and report the absent value as a dash.
        StyleItem item = styleItem("Empty"); //$NON-NLS-1$
        String out = formatter.format(item, false, "en"); //$NON-NLS-1$
        assertTrue("must render the Value section header", out.contains("Value")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
