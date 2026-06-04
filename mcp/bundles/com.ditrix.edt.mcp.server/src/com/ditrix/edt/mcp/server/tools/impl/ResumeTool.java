/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Resumes a suspended debug thread (or, if {@code applicationId} is given,
 * resumes all threads of the matching debug target).
 *
 * <p>If neither parameter is given and there is exactly one active debug launch,
 * that launch is used as a fallback — useful for Attach configurations whose
 * synthetic applicationId is not known to the caller, and for the common
 * one-session workflow.
 */
public class ResumeTool implements IMcpTool
{
    public static final String NAME = "resume"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Resume a suspended debug thread or all threads of a debug target. " //$NON-NLS-1$
            + "Pass threadId (from wait_for_break) or applicationId (real, 'attach:<name>', or a " //$NON-NLS-1$
            + "server target id 'ServerApplication.<app>' from debug_status). " //$NON-NLS-1$
            + "With no arguments, resumes the single active debug session (launch or server " //$NON-NLS-1$
            + "target) if exactly one exists. NOTE: if resume of a server-side suspend does not " //$NON-NLS-1$
            + "take effect, the breakpoint can also be released from the EDT UI."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("threadId", "Thread id from wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application id (real, 'attach:<configName>', or 'ServerApplication.<app>' — " //$NON-NLS-1$
                    + "resumes all threads of this target)") //$NON-NLS-1$
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
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.ensureListenerRegistered();

        try
        {
            if (threadId > 0)
            {
                IThread thread = registry.getThread(threadId);
                if (thread == null)
                {
                    return ToolResult.error("stale threadId — call wait_for_break again").toJson(); //$NON-NLS-1$
                }
                if (!thread.canResume())
                {
                    return ToolResult.error("thread cannot resume (state: " //$NON-NLS-1$
                            + (thread.isSuspended() ? "suspended" : "running") + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                thread.resume();
                return ToolResult.success().put("resumed", true).put("scope", "thread").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            String effectiveAppId = (applicationId != null && !applicationId.isEmpty())
                ? applicationId
                : DebugSessionRegistry.findLoneActiveApplicationId();

            // SERVER-TARGET PATH: a server-side suspend (debug_yaxunit_tests) lives
            // on a 1C debug-server target, not an Eclipse launch. Resolve it by the
            // minted ServerApplication id (or, when auto-resolving, the lone target).
            DebugServerTargetSupport.ServerTarget serverTarget = effectiveAppId != null
                ? DebugServerTargetSupport.resolve(effectiveAppId)
                : DebugServerTargetSupport.findLoneServerTarget();
            if (serverTarget != null)
            {
                if (effectiveAppId == null)
                {
                    effectiveAppId = serverTarget.applicationId;
                }
                IDebugTarget srv = serverTarget.target;
                if (srv == null || srv.isTerminated() || !srv.canResume())
                {
                    return ToolResult.error("server debug target cannot resume").toJson(); //$NON-NLS-1$
                }
                srv.resume();
                // Drop the cached snapshot so a subsequent wait_for_break does not
                // return the now-stale pre-resume frame.
                registry.clearSnapshot(effectiveAppId);
                ToolResult res = ToolResult.success()
                    .put("resumed", true) //$NON-NLS-1$
                    .put("scope", "target") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("serverTarget", true) //$NON-NLS-1$
                    .put("applicationId", effectiveAppId); //$NON-NLS-1$
                if (applicationId == null || applicationId.isEmpty())
                {
                    res.put("autoResolved", true); //$NON-NLS-1$
                }
                return res.toJson();
            }

            if (effectiveAppId == null)
            {
                return ToolResult.error("Provide threadId or applicationId — no single active debug " //$NON-NLS-1$
                    + "launch available for auto-resolution. Use debug_status to list active launches.").toJson(); //$NON-NLS-1$
            }

            IDebugTarget target = DebugSessionRegistry.findActiveTarget(effectiveAppId);
            if (target == null)
            {
                return ToolResult.error("no active debug target for applicationId: " + effectiveAppId).toJson(); //$NON-NLS-1$
            }
            if (!target.canResume())
            {
                return ToolResult.error("debug target cannot resume").toJson(); //$NON-NLS-1$
            }
            target.resume();
            ToolResult res = ToolResult.success()
                .put("resumed", true) //$NON-NLS-1$
                .put("scope", "target") //$NON-NLS-1$ //$NON-NLS-2$
                .put("applicationId", effectiveAppId); //$NON-NLS-1$
            if (applicationId == null || applicationId.isEmpty())
            {
                res.put("autoResolved", true); //$NON-NLS-1$
            }
            return res.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in resume", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

}
