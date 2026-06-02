/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import org.eclipse.ui.IStartup;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/**
 * Startup class for auto-starting MCP server on EDT startup.
 */
public class McpServerStartup implements IStartup
{
    @Override
    public void earlyStartup()
    {
        // Self-enable EDT's buffered native form renderer (belt-and-suspenders for
        // the org.eclipse.ui.startup path, mirroring Activator.start). The flag is
        // read once in the static init of NativeRenderService / the
        // HippoLayoutService.INSTANCE constructor — both in LAZY bundles
        // (form.layout / form.presentation) that class-load only on the FIRST form
        // render, which happens after startup — so setting it here is still in time.
        // On Windows it defaults OFF; without it get_form_screenshot returns a blank
        // grey canvas and get_form_layout_snapshot returns no element bounds. Only
        // set when undefined so an explicit user -DnativeFormBufferedLayoutRender=false
        // is respected.
        if (System.getProperty("nativeFormBufferedLayoutRender") == null) //$NON-NLS-1$
        {
            System.setProperty("nativeFormBufferedLayoutRender", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Check auto-start preference
        boolean autoStart = Activator.getDefault().getPreferenceStore()
            .getBoolean(PreferenceConstants.PREF_AUTO_START);
        
        if (autoStart)
        {
            int port = Activator.getDefault().getPreferenceStore()
                .getInt(PreferenceConstants.PREF_PORT);
            
            try
            {
                Activator.getDefault().getMcpServer().start(port);
                Activator.logInfo("MCP Server auto-started on port " + port);
            }
            catch (Exception e)
            {
                Activator.logError("Failed to auto-start MCP Server", e);
            }
        }

        // Schedule a background check for a new plugin release (after 60 s delay)
        UpdateChecker.getInstance().scheduleCheck();
    }
}
