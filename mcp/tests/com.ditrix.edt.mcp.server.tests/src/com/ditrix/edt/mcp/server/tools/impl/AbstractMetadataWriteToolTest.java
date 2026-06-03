/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.tools.impl.AbstractMetadataWriteTool.LanguageResolution;

/**
 * Lightweight tests for the shared {@link AbstractMetadataWriteTool} persist
 * helper (S11, #19888). The {@code persistAndRevalidate(...)} path needs a live
 * workbench / BM model and is covered by the E2E suite; here we exercise only the
 * pure FQN-derivation contract used to pick the top-object FQN to flush, which is
 * what routes create / set-property / add-attribute through the same helper.
 * <p>
 * The hoisted {@code resolveLanguage} helper (S15c/S15e, #19892) is exercised
 * directly here: it is a pure function over the configuration's Languages and is
 * the single source of truth now shared by create_metadata_object,
 * add_enum_value, set_object_property and create_form.
 */
public class AbstractMetadataWriteToolTest
{
    /** Builds a Configuration with the given configured language codes. */
    private static Configuration configWithLanguages(String defaultCode, String... codes)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Language defaultLanguage = null;
        for (String code : codes)
        {
            Language language = MdClassFactory.eINSTANCE.createLanguage();
            language.setLanguageCode(code);
            config.getLanguages().add(language);
            if (code.equals(defaultCode))
            {
                defaultLanguage = language;
            }
        }
        if (defaultLanguage != null)
        {
            config.setDefaultLanguage(defaultLanguage);
        }
        return config;
    }

    @Test
    public void testResolveLanguageAcceptsConfiguredExplicitCode()
    {
        Configuration config = configWithLanguages("ru", "ru", "en"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LanguageResolution res = AbstractMetadataWriteTool.resolveLanguage(config, "en"); //$NON-NLS-1$
        assertFalse(res.hasError());
        assertEquals("en", res.code); //$NON-NLS-1$
    }

    @Test
    public void testResolveLanguageRejectsUnconfiguredCode()
    {
        // S15c: a synonym under an unconfigured language code is silently invisible
        // in EDT, so the resolver must reject the code with a clear error rather
        // than accepting it verbatim.
        Configuration config = configWithLanguages("ru", "ru", "en"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LanguageResolution res = AbstractMetadataWriteTool.resolveLanguage(config, "de"); //$NON-NLS-1$
        assertTrue(res.hasError());
        assertNull(res.code);
        assertTrue("error should name the offending code", res.error.contains("de")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should list configured codes", res.error.contains("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResolveLanguageFallsBackToDefault()
    {
        Configuration config = configWithLanguages("ru", "ru", "en"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LanguageResolution res = AbstractMetadataWriteTool.resolveLanguage(config, null);
        assertFalse(res.hasError());
        assertEquals("ru", res.code); //$NON-NLS-1$
    }

    @Test
    public void testResolveLanguageFallsBackToFirstWhenNoDefault()
    {
        Configuration config = configWithLanguages(null, "en", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        LanguageResolution res = AbstractMetadataWriteTool.resolveLanguage(config, null);
        assertFalse(res.hasError());
        assertEquals("en", res.code); //$NON-NLS-1$
    }

    @Test
    public void testResolveLanguageNoLanguagesYieldsError()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        LanguageResolution res = AbstractMetadataWriteTool.resolveLanguage(config, null);
        assertTrue(res.hasError());
        assertNull(res.code);
    }

    /**
     * Minimal concrete subclass so the package-visible protected static helper can
     * be exercised without a workbench. {@code executeOnUiThread} is never invoked
     * by these tests.
     */
    private static final class Probe extends AbstractMetadataWriteTool
    {
        @Override
        public String getName()
        {
            return "test_probe"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return ""; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{}"; //$NON-NLS-1$
        }

        @Override
        protected String executeOnUiThread(Map<String, String> params)
        {
            return ""; //$NON-NLS-1$
        }
    }

    @Test
    public void testTopObjectFqnOfNull()
    {
        // A null target has no derivable top-object FQN.
        assertNull(AbstractMetadataWriteTool.topObjectFqnOf(null));
    }

    @Test
    public void testTopObjectFqnOfNonBmObject()
    {
        // A plain object that is not an IBmObject yields no FQN (the caller then
        // surfaces a persistWarning instead of failing the edit).
        assertNull(AbstractMetadataWriteTool.topObjectFqnOf(new Object()));
    }

    @Test
    public void testTopObjectFqnOfNonBmStringTarget()
    {
        // Guard against accidental String handling: a String is not a BM object.
        assertNull(AbstractMetadataWriteTool.topObjectFqnOf("Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void testProbeIsMetadataWriteTool()
    {
        // The shared helper is reachable from a concrete metadata write tool, i.e.
        // create / set-property / add-attribute all inherit the same persist path.
        Probe probe = new Probe();
        assertNull(AbstractMetadataWriteTool.topObjectFqnOf(probe)); // probe is not a BM object
    }
}
