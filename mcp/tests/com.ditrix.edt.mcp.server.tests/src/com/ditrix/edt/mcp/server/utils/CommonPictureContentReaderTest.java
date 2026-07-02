/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link CommonPictureContentReader}'s pure, Display-free static helpers:
 * the nullable-enum → literal mapping, the manifest/SVG entry-name predicates, and
 * the {@code best}/{@code svg}/exact variant-selection logic. These need no live
 * model and no picture-jar services, so the whole class is headless (it does not
 * construct the reader, which would require the MD Guice injector).
 */
public class CommonPictureContentReaderTest
{
    /** A local enum standing in for the picture enums, so the test needs no picture jar. */
    private enum SampleEnum
    {
        LIGHT, DARK
    }

    // --- mapEnumLiteral ---------------------------------------------------

    @Test
    public void mapEnumLiteralReturnsNameForValue()
    {
        assertEquals("LIGHT", CommonPictureContentReader.mapEnumLiteral(SampleEnum.LIGHT)); //$NON-NLS-1$
        assertEquals("DARK", CommonPictureContentReader.mapEnumLiteral(SampleEnum.DARK)); //$NON-NLS-1$
    }

    @Test
    public void mapEnumLiteralReturnsDashForNull()
    {
        assertEquals("-", CommonPictureContentReader.mapEnumLiteral(null)); //$NON-NLS-1$
    }

    // --- isManifestEntry --------------------------------------------------

    @Test
    public void isManifestEntryDetectsManifestCaseInsensitively()
    {
        assertTrue(CommonPictureContentReader.isManifestEntry("manifest.xml")); //$NON-NLS-1$
        assertTrue(CommonPictureContentReader.isManifestEntry("MANIFEST.XML")); //$NON-NLS-1$
    }

    @Test
    public void isManifestEntryRejectsOthersAndNull()
    {
        assertFalse(CommonPictureContentReader.isManifestEntry("picture.png")); //$NON-NLS-1$
        assertFalse(CommonPictureContentReader.isManifestEntry("manifest.png")); //$NON-NLS-1$
        assertFalse(CommonPictureContentReader.isManifestEntry(null));
    }

    // --- isSvgName --------------------------------------------------------

    @Test
    public void isSvgNameDetectsSvgSuffixCaseInsensitively()
    {
        assertTrue(CommonPictureContentReader.isSvgName("icon.svg")); //$NON-NLS-1$
        assertTrue(CommonPictureContentReader.isSvgName("icon.SVG")); //$NON-NLS-1$
    }

    @Test
    public void isSvgNameRejectsRasterAndNull()
    {
        assertFalse(CommonPictureContentReader.isSvgName("icon.png")); //$NON-NLS-1$
        assertFalse(CommonPictureContentReader.isSvgName("svg.png")); //$NON-NLS-1$
        assertFalse(CommonPictureContentReader.isSvgName(null));
    }

    // --- selectVariantName: svg ------------------------------------------

    @Test
    public void selectSvgReturnsTheSvgEntry()
    {
        List<String> names = Arrays.asList("mdpi.png", "hdpi.png", "vector.svg"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("vector.svg", CommonPictureContentReader.selectVariantName(names, "svg")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void selectSvgReturnsNullWhenNoVectorVariant()
    {
        List<String> names = Arrays.asList("mdpi.png", "hdpi.png"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(CommonPictureContentReader.selectVariantName(names, "svg")); //$NON-NLS-1$
    }

    // --- pickDensest (the real "best" density ranking selectBestRasterName runs) ----------

    @Test
    public void pickDensestReturnsHighestDensityRank()
    {
        // Highest screen-density rank wins, independent of candidate order.
        List<CommonPictureContentReader.RasterCandidate> candidates = Arrays.asList(
            new CommonPictureContentReader.RasterCandidate("mdpi.png", 0, 100L), //$NON-NLS-1$
            new CommonPictureContentReader.RasterCandidate("hdpi.png", 2, 100L), //$NON-NLS-1$
            new CommonPictureContentReader.RasterCandidate("ldpi.png", 1, 100L)); //$NON-NLS-1$
        assertEquals("hdpi.png", CommonPictureContentReader.pickDensest(candidates)); //$NON-NLS-1$
    }

    @Test
    public void pickDensestTieBreaksOnLargerSize()
    {
        // Same density rank -> the larger raw size wins (not first-seen / entry order).
        List<CommonPictureContentReader.RasterCandidate> candidates = Arrays.asList(
            new CommonPictureContentReader.RasterCandidate("a.png", 3, 100L), //$NON-NLS-1$
            new CommonPictureContentReader.RasterCandidate("b.png", 3, 500L), //$NON-NLS-1$
            new CommonPictureContentReader.RasterCandidate("c.png", 3, 200L)); //$NON-NLS-1$
        assertEquals("b.png", CommonPictureContentReader.pickDensest(candidates)); //$NON-NLS-1$
    }

    @Test
    public void pickDensestReturnsNullForEmpty()
    {
        assertNull(CommonPictureContentReader.pickDensest(new ArrayList<>()));
    }

    // --- selectVariantName: exact ----------------------------------------

    @Test
    public void selectExactReturnsTheMatchingEntry()
    {
        List<String> names = Arrays.asList("mdpi.png", "hdpi.png", "vector.svg"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("mdpi.png", CommonPictureContentReader.selectVariantName(names, "mdpi.png")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void selectExactIsCaseSensitiveForEntryNames()
    {
        List<String> names = Arrays.asList("MdPi.png"); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(names, "mdpi.png")); //$NON-NLS-1$
    }

    @Test
    public void selectUnknownExactReturnsNull()
    {
        List<String> names = Arrays.asList("mdpi.png"); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(names, "nope.png")); //$NON-NLS-1$
    }

    // --- selectVariantName: null/empty ------------------------------------

    @Test
    public void selectReturnsNullForEmptyOrNullInputs()
    {
        assertNull(CommonPictureContentReader.selectVariantName(new ArrayList<>(), "best")); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(null, "best")); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(Arrays.asList("mdpi.png"), null)); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(Arrays.asList("mdpi.png"), "   ")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
