/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertNull;

import java.util.Map;

import org.junit.Test;

/**
 * Lightweight tests for the shared {@link AbstractMetadataWriteTool} persist
 * helper (S11, #19888). The {@code persistAndRevalidate(...)} path needs a live
 * workbench / BM model and is covered by the E2E suite; here we exercise only the
 * pure FQN-derivation contract used to pick the top-object FQN to flush, which is
 * what routes create / set-property / add-attribute through the same helper.
 */
public class AbstractMetadataWriteToolTest
{
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
