/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.Map;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProfilingStateRegistry;
import com.ditrix.edt.mcp.server.utils.ProfilingTargetResolver;

/**
 * Toggles 1C performance measurement (замер производительности) on the active
 * debug target. Once enabled, every executed BSL line is tracked with call count
 * and timing. Call {@code get_profiling_results} after the test finishes to
 * retrieve which code was covered.
 *
 * <p>Uses reflection to access {@code IProfilingService} via
 * {@code ServiceAccess.get()} from the {@code com._1c.g5.wiring} bundle,
 * and {@code IProfileTarget.toggleProfiling()} on the debug target.
 */
public class StartProfilingTool implements IMcpTool
{
    public static final String NAME = "start_profiling"; //$NON-NLS-1$

    private static final String WIRING_BUNDLE = "com._1c.g5.wiring"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Toggle performance measurement (замер производительности) on the active debug target. " //$NON-NLS-1$
            + "Enables line-level profiling: call counts and timing for every executed BSL line. " //$NON-NLS-1$
            + "This is a TOGGLE: the response field 'profilingEnabled' tells you the resulting state " //$NON-NLS-1$
            + "(true = now ON, false = now OFF). " //$NON-NLS-1$
            + "Full sequence: start_profiling (turns ON) -> run your code/test -> start_profiling again " //$NON-NLS-1$
            + "with the same applicationId (turns OFF) -> get_profiling_results. " //$NON-NLS-1$
            + "Profiling data appears ONLY after the measurement is stopped (toggled OFF). " //$NON-NLS-1$
            + "Requires an active debug session (debug_launch or debug_yaxunit_tests)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application id of the running debug session. Accepts ANY id form for the " //$NON-NLS-1$
                    + "session: the real id, 'attach:<name>', 'launch:<name>', or " //$NON-NLS-1$
                    + "'ServerApplication.<app>' (the id debug_yaxunit_tests/wait_for_break use). " //$NON-NLS-1$
                    + "Optional if exactly one debug session is active.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

        try
        {
            // Unified resolution: accept ANY id form for the session (real,
            // attach:<name>, launch:<name>, ServerApplication.<app>, bare app name,
            // debug server URL) and resolve the profiling-capable IProfileTarget.
            // A blank id auto-resolves the single active session.
            ProfilingTargetResolver.Result profiling = ProfilingTargetResolver.resolve(applicationId);
            if (!profiling.isResolved())
            {
                return ToolResult.error(profiling.error).toJson();
            }
            Object profileTarget = profiling.profileTarget;
            Class<?> profileTargetClass = profiling.profileTargetClass;
            // Track and report against the canonical id so get_profiling_results can
            // be called with the SAME id (or any other form) and find the session.
            String canonicalId = profiling.canonicalId;

            Bundle profilingBundle = Platform.getBundle(ProfilingTargetResolver.PROFILING_CORE_BUNDLE);
            if (profilingBundle == null)
            {
                return ToolResult.error("Profiling core bundle not found").toJson(); //$NON-NLS-1$
            }

            // Get IProfilingService via ServiceAccess.get() — it manages the
            // UUID↔target mapping needed for module resolution in results.
            Bundle wiringBundle = Platform.getBundle(WIRING_BUNDLE);
            if (wiringBundle == null)
            {
                return ToolResult.error("Wiring bundle not found").toJson(); //$NON-NLS-1$
            }

            Class<?> serviceAccessClass = wiringBundle.loadClass("com._1c.g5.wiring.ServiceAccess"); //$NON-NLS-1$
            Class<?> profilingServiceClass = profilingBundle.loadClass(
                "com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$
            Method getService = serviceAccessClass.getMethod("get", Class.class); //$NON-NLS-1$
            Object profilingService = getService.invoke(null, profilingServiceClass);
            if (profilingService == null)
            {
                return ToolResult.error("IProfilingService not available").toJson(); //$NON-NLS-1$
            }

            // IProfilingService.toggleProfiling(IProfileTarget) — generates UUID
            // internally, registers it in targets map, sends to debug server.
            Method toggleProfiling = profilingServiceClass.getMethod("toggleProfiling", profileTargetClass); //$NON-NLS-1$
            toggleProfiling.invoke(profilingService, profileTarget);

            // The platform toggle succeeded: flip the in-plugin tracker and report
            // the resulting state. The EDT profiling API has no isProfiling() query,
            // so this tracker is the only accurate source of the ON/OFF state. Track
            // by the canonical id so the OFF toggle and get_profiling_results agree.
            boolean profilingEnabled = ProfilingStateRegistry.get().toggle(canonicalId);

            Activator.logInfo("Profiling toggled via IProfilingService for applicationId=" + canonicalId //$NON-NLS-1$
                + " -> profilingEnabled=" + profilingEnabled); //$NON-NLS-1$

            String message = profilingEnabled
                ? "Profiling is now ON for applicationId=" + canonicalId //$NON-NLS-1$
                    + ". Run your code/test, then call start_profiling again with the same " //$NON-NLS-1$
                    + "applicationId to STOP the measurement. Results appear ONLY after stopping." //$NON-NLS-1$
                : "Profiling is now OFF for applicationId=" + canonicalId //$NON-NLS-1$
                    + ". The measurement is stopped — call get_profiling_results to read the data."; //$NON-NLS-1$

            ToolResult result = ToolResult.success()
                .put("toggled", true) //$NON-NLS-1$
                .put("profilingEnabled", profilingEnabled) //$NON-NLS-1$
                .put("applicationId", canonicalId) //$NON-NLS-1$
                .put("message", message); //$NON-NLS-1$
            if (profiling.autoResolved)
            {
                result.put("autoResolved", true); //$NON-NLS-1$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in start_profiling", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
