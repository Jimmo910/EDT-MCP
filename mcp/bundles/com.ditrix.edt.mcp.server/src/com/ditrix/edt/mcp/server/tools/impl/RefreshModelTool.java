/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool to force a rebuild of an EDT project derived-data model without manually
 * opening any editor.
 *
 * <p>Unlike {@code revalidate_objects} (which only re-runs checks and re-installs
 * markers on the current model state) this tool forces EDT to recompute ALL
 * derived data for the project before revalidating. Derived data in EDT includes
 * the cross-model links that go stale on programmatic edits, in particular:
 * <ul>
 *   <li>the form &harr; form-module binding (attachable event/command handler name
 *       resolution), which otherwise yields a false "handler X is missing in the
 *       form module" until the form is force-opened;</li>
 *   <li>the register recorders composition, whose stale "register recorders" error
 *       only clears after a targeted recompute.</li>
 * </ul>
 *
 * <p>Mechanism: obtain the project {@link IDerivedDataManager} through the EDT
 * {@link IDerivedDataManagerProvider} service and call
 * {@link IDerivedDataManager#recomputeAll()} -- the supported EDT lever to rebuild
 * every derived-data segment. After the recompute settles, an incremental build is
 * triggered to re-establish validation markers on the fresh model.
 *
 * <p>This is a read-side, project-wide operation: it does not mutate the model,
 * so it is safe to run from a worker thread (no UI-thread BM transaction needed).
 */
public class RefreshModelTool implements IMcpTool
{
    public static final String NAME = "refresh_model"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Force EDT to rebuild a project derived-data model and clear stale validation, " //$NON-NLS-1$
            + "WITHOUT manually opening any editor. " //$NON-NLS-1$
            + "Use this when validation is stale after programmatic edits, e.g. a false " //$NON-NLS-1$
            + "'handler is missing in the form module' for a method that actually exists, or a stale " //$NON-NLS-1$
            + "register-recorders composition error. It recomputes all derived data (form <-> form-module " //$NON-NLS-1$
            + "binding, register recorders, presentations) and then revalidates the project. " //$NON-NLS-1$
            + "Heavier than revalidate_objects; prefer revalidate_objects when only markers are stale."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("revalidate", //$NON-NLS-1$
                "Trigger an incremental build to re-install validation markers after the model rebuild " //$NON-NLS-1$
                    + "(default: true). Set false to recompute derived data only.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        boolean revalidate = JsonUtils.extractBooleanArgument(params, "revalidate", true); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " //$NON-NLS-1$
                + "Example: 'MyConfiguration'.").toJson(); //$NON-NLS-1$
        }

        // Do not start a rebuild while the project is still building / not ready.
        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }

        return refreshModel(projectName, revalidate);
    }

    /**
     * Forces a derived-data recompute for the project and, optionally, re-installs
     * validation markers via an incremental build.
     *
     * @param projectName name of the project to refresh
     * @param revalidate whether to trigger an incremental build afterwards
     * @return JSON string with the result
     */
    private static String refreshModel(String projectName, boolean revalidate)
    {
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProgressMonitor monitor = new NullProgressMonitor();

            IProject project = workspace.getRoot().getProject(projectName);
            if (project == null || !project.exists())
            {
                return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
            }
            if (!project.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }

            // Resolve the EDT project + derived data manager up front so we fail with a
            // clear message instead of silently doing nothing.
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;
            if (dtProject == null)
            {
                return ToolResult.error("Not an EDT project: " + projectName).toJson(); //$NON-NLS-1$
            }

            IDerivedDataManagerProvider ddProvider = Activator.getDefault().getDerivedDataManagerProvider();
            if (ddProvider == null)
            {
                return ToolResult.error("IDerivedDataManagerProvider service is not available").toJson(); //$NON-NLS-1$
            }
            IDerivedDataManager ddManager = ddProvider.get(dtProject);
            if (ddManager == null)
            {
                return ToolResult.error("Derived data manager not available for project: " + projectName) //$NON-NLS-1$
                    .toJson();
            }

            // Step 1: refresh resources from disk to pick up any external changes.
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            // Step 2: force EDT to recompute ALL derived data. This rebuilds the
            // cross-model links that go stale on programmatic edits (form <-> form
            // module handler binding, register recorders composition, presentations).
            Activator.logInfo("Recomputing all derived data for project: " + projectName); //$NON-NLS-1$
            ddManager.recomputeAll();

            // Step 3: wait for the recompute to settle before touching markers.
            BuildUtils.waitForDerivedData(project);

            // Step 4 (optional): re-install validation markers on the fresh model.
            if (revalidate)
            {
                Activator.logInfo("Revalidating project after model rebuild: " + projectName); //$NON-NLS-1$
                project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
                BuildUtils.waitForBuildAndDerivedData(project, monitor);
            }

            return ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("modelRebuilt", true) //$NON-NLS-1$
                .put("revalidated", revalidate) //$NON-NLS-1$
                .put("message", "Model rebuilt via derived-data recompute. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Run get_project_errors to verify stale markers are gone.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error during model refresh for project: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Failed to refresh model: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
