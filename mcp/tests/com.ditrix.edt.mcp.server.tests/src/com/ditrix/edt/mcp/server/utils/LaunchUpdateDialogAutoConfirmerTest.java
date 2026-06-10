/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the pure decision of {@link LaunchUpdateDialogAutoConfirmer}: which
 * shell title is the "Application update" launch modal it auto-confirms.
 *
 * <p>The SWT plumbing ({@code arm}/{@code disarm} + the {@code Display} filter)
 * is exercised only live (it needs a real workbench); here we lock down the
 * exact-match contract so the filter never fires on an unrelated dialog, plus
 * the no-op safety of an unbalanced {@code disarm}.
 *
 * <p>EDT ships the modal title in two locales (English / Russian); both MUST
 * match, because the English-only match silently failed on a Russian-locale
 * stand and the unattended launch then hung on the un-dismissed modal
 * (Bitrix 20074).
 */
public class LaunchUpdateDialogAutoConfirmerTest
{
    /**
     * Russian title of the modal ("Обновление приложения"), {@code \\uXXXX}-escaped
     * just like the production constant so this test compiles identically whatever
     * source encoding the Tycho compiler picks for the test bundle (which has no
     * explicit encoding setting, unlike the production bundle).
     */
    private static final String RUSSIAN_TITLE =
        "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u0438\u0435 \u043F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u044F";

    @Test
    public void testExactTitleMatches()
    {
        assertTrue("the exact EDT modal title must match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Application update"));
    }

    @Test
    public void testRussianTitleMatches()
    {
        // The stand that hit Bitrix 20074 runs a Russian EDT — its localized modal
        // title must match, or the auto-confirm filter never fires and the launch
        // hangs on the modal.
        assertTrue("the Russian EDT modal title must match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle(RUSSIAN_TITLE));
    }

    @Test
    public void testRussianConstantMatchesExpectedString()
    {
        // Guards against a typo in the escaped production constant: it must equal
        // the exact string EDT's messages_ru.properties renders. Both sides are
        // \\uXXXX-escaped, so the assertion is encoding-independent; we also pin the
        // decoded length so a wrong escape can't accidentally still pass.
        assertEquals("the Russian title constant must equal the expected localized string",
            RUSSIAN_TITLE, LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLE_RU);
        assertEquals("the Russian title is 'Обновление приложения' (21 chars)",
            21, LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLE_RU.length());
    }

    @Test
    public void testTitleSetHasBothLocales()
    {
        assertTrue("the known-titles set must contain the English title",
            LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLES.contains("Application update"));
        assertTrue("the known-titles set must contain the Russian title",
            LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLES.contains(RUSSIAN_TITLE));
    }

    @Test
    public void testDifferentTitleDoesNotMatch()
    {
        assertFalse("an unrelated dialog title must not match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Save resources"));
    }

    @Test
    public void testMatchIsCaseSensitive()
    {
        assertFalse("matching is exact, not case-insensitive",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("application update"));
    }

    @Test
    public void testWhitespaceVariantDoesNotMatch()
    {
        assertFalse("a trailing-space variant must not match (exact compare)",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Application update "));
    }

    @Test
    public void testNullTitleDoesNotMatch()
    {
        assertFalse("a null shell title must not match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle(null));
    }

    @Test
    public void testUnbalancedDisarmIsNoOp()
    {
        // Releasing without a prior arm() must not throw or touch a Display:
        // with no filter installed it returns before any UI access.
        LaunchUpdateDialogAutoConfirmer.disarm();
        LaunchUpdateDialogAutoConfirmer.disarm();
    }
}
