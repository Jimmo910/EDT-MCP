/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Tool to update database (infobase) for an application.
 * Supports full and incremental update modes.
 *
 * <p>The infobase update fails (or, worse, blocks indefinitely) when a 1С
 * client launched from this EDT instance is still running against the target
 * infobase: the IB is held in exclusive use, and EDT may either pop a modal
 * dialog (which never gets answered over MCP) or stall on the lock. To avoid a
 * silent hang this tool:
 * <ol>
 *   <li>detects live runtime-client launches that hold the target IB BEFORE
 *       starting the update and, unless {@code terminateSessions=true}, returns
 *       an actionable error instead of blocking;</li>
 *   <li>with {@code terminateSessions=true}, terminates those launches first
 *       (same path as {@code terminate_launch} — only launches started from
 *       this EDT instance are affected; externally started 1С clients are never
 *       touched and cannot be detected here);</li>
 *   <li>runs the actual {@link IApplicationManager#update} on a bounded
 *       background task with a {@code timeoutSeconds} deadline, so a stuck
 *       update reports a clear timeout error rather than hanging the MCP call
 *       forever.</li>
 * </ol>
 */
public class UpdateDatabaseTool implements IMcpTool
{
    public static final String NAME = "update_database"; //$NON-NLS-1$

    /** Fallback update-wait window in seconds, used when preferences cannot be read. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** Hard cap on the update wait, prevents an unbounded block on a wedged IB. */
    private static final int MAX_TIMEOUT_SECONDS = 1800;

    /** Lower bound — an update never completes meaningfully under a second. */
    private static final int MIN_TIMEOUT_SECONDS = 5;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Update database (infobase) for an application. " //$NON-NLS-1$
            + "Target the application either by launchConfigurationName (preferred; " //$NON-NLS-1$
            + "from list_configurations) or by projectName + applicationId (from get_applications). " //$NON-NLS-1$
            + "Supports full update (complete reload) and incremental update (changes only). " //$NON-NLS-1$
            + "IMPORTANT: if a 1С client launched from this EDT is currently running against " //$NON-NLS-1$
            + "the target infobase, the IB is held in exclusive use and the update cannot proceed. " //$NON-NLS-1$
            + "This tool detects that up front and returns a clear error (instead of hanging); " //$NON-NLS-1$
            + "pass terminateSessions=true to stop those EDT-launched 1С clients first, or call " //$NON-NLS-1$
            + "terminate_launch manually and retry. NOTE: only 1С clients started from THIS EDT " //$NON-NLS-1$
            + "instance are visible/terminable — a session opened by an external 1С:Enterprise / " //$NON-NLS-1$
            + "Designer cannot be detected here; if the update times out, close such sessions manually. " //$NON-NLS-1$
            + "Before updating, the tool FORCES a derived-data recompute (recomputeAll) of the " //$NON-NLS-1$
            + "configuration and its dependent EXTENSION (.cfe) projects and waits for it to settle, " //$NON-NLS-1$
            + "so an edited extension's regenerated .cfe is loaded into the infobase (not left stale). " //$NON-NLS-1$
            + "After issuing the update it WAITS until the infobase has actually applied the change " //$NON-NLS-1$
            + "(polls the update state to UPDATED), because the update call can return before the DB " //$NON-NLS-1$
            + "application completes; if the IB does not reach UPDATED in time it reports an error with " //$NON-NLS-1$
            + "infobaseOutOfSync=true instead of a misleading success. " //$NON-NLS-1$
            + "Use updateScope to narrow this to the fast 'extension:<ProjectName>' path."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact EDT runtime-client launch configuration name (preferred; from list_configurations)") //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name (required if launchConfigurationName is omitted)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications (required if launchConfigurationName is omitted)") //$NON-NLS-1$
            .booleanProperty("fullUpdate", "If true - full reload, if false - incremental update (default: false)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("autoRestructure", "Automatically apply restructurization if needed (default: true)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("terminateSessions", //$NON-NLS-1$
                "If true, terminate any 1С client launched from THIS EDT instance that holds the " //$NON-NLS-1$
                    + "target infobase before updating (same effect as terminate_launch; may lose " //$NON-NLS-1$
                    + "unsaved 1С state). If false (default) and such a launch is running, the tool " //$NON-NLS-1$
                    + "returns an error instead of blocking. Externally started 1С sessions are " //$NON-NLS-1$
                    + "never affected.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Maximum time to wait for the update to finish before reporting a timeout " //$NON-NLS-1$
                    + "(prevents an unbounded hang when the IB is busy). Default is configured in EDT " //$NON-NLS-1$
                    + "preferences (MCP Server -> Tools -> update_database), factory default 120. " //$NON-NLS-1$
                    + "Clamped to [5, 1800].") //$NON-NLS-1$
            .stringProperty("updateScope", RunYaxunitTestsTool.UPDATE_SCOPE_DESCRIPTION) //$NON-NLS-1$
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
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        boolean fullUpdate = JsonUtils.extractBooleanArgument(params, "fullUpdate", false); //$NON-NLS-1$
        boolean autoRestructure = JsonUtils.extractBooleanArgument(params, "autoRestructure", true); //$NON-NLS-1$
        boolean terminateSessions = JsonUtils.extractBooleanArgument(params, "terminateSessions", false); //$NON-NLS-1$
        String updateScope = JsonUtils.extractStringArgument(params, "updateScope"); //$NON-NLS-1$
        int timeoutSeconds = resolveTimeoutSeconds(params);

        boolean hasName = configName != null && !configName.isEmpty();
        if (!hasName)
        {
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("projectName is required (or pass launchConfigurationName)").toJson(); //$NON-NLS-1$
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                return ToolResult.error("applicationId is required (or pass launchConfigurationName). " //$NON-NLS-1$
                    + "Use get_applications or list_configurations.").toJson(); //$NON-NLS-1$
            }
        }

        // Resolve via launch config if name is given — it fixes the project + applicationId pair.
        ILaunchManager launchManager = DebugPlugin.getDefault() != null
            ? DebugPlugin.getDefault().getLaunchManager() : null;
        if (hasName)
        {
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }
            ILaunchConfiguration cfg = LaunchConfigUtils.findLaunchConfigByName(launchManager, configName);
            if (cfg == null)
            {
                return ToolResult.error("Launch configuration not found: '" + configName //$NON-NLS-1$
                    + "'. Use list_configurations to see what's available.").toJson(); //$NON-NLS-1$
            }
            if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(cfg)))
            {
                return ToolResult.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                    + "' is not a runtime-client config — update_database requires one.").toJson(); //$NON-NLS-1$
            }
            String cfgProject = LaunchConfigUtils.readAttribute(cfg,
                LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String cfgAppId = LaunchConfigUtils.readAttribute(cfg,
                LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
            if (cfgProject.isEmpty() || cfgAppId.isEmpty())
            {
                return ToolResult.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                    + "' has no project or applicationId attribute — cannot derive update target.").toJson(); //$NON-NLS-1$
            }
            projectName = cfgProject;
            applicationId = cfgAppId;
        }

        // Check if project is ready for operations
        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }

        // Detect (and optionally stop) EDT-launched 1С clients that hold the IB in
        // exclusive use. This is the core of bug #19804: without it, the update
        // below either silently blocks on a modal dialog or stalls on the lock.
        String busyError = handleBusyInfobase(launchManager, projectName, applicationId,
            terminateSessions);
        if (busyError != null)
        {
            return busyError;
        }

        return updateDatabase(projectName, applicationId, fullUpdate, autoRestructure, timeoutSeconds,
            updateScope);
    }

    /**
     * Resolves the configured update timeout, honouring an explicit
     * {@code timeoutSeconds} argument, then the EDT preference, then the factory
     * default — clamped to {@code [MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS]}.
     */
    private static int resolveTimeoutSeconds(Map<String, String> params)
    {
        int configuredDefault = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS); //$NON-NLS-1$
        int timeoutSeconds = JsonUtils.extractIntArgument(params, "timeoutSeconds", configuredDefault); //$NON-NLS-1$
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS)
        {
            return MIN_TIMEOUT_SECONDS;
        }
        if (timeoutSeconds > MAX_TIMEOUT_SECONDS)
        {
            return MAX_TIMEOUT_SECONDS;
        }
        return timeoutSeconds;
    }

    /**
     * Detects live runtime-client launches (started from this EDT instance) that
     * hold the target infobase. Returns:
     * <ul>
     *   <li>{@code null} — the IB is free (or all holders were terminated), so the
     *       caller may proceed with the update;</li>
     *   <li>a JSON error string — either the IB is busy and {@code terminateSessions}
     *       is {@code false}, or termination did not complete in time.</li>
     * </ul>
     *
     * <p>Only EDT-launched clients are visible via the launch manager; an external
     * 1С:Enterprise/Designer session cannot be seen here and is handled instead by
     * the bounded timeout in {@link #updateDatabase}.
     */
    private String handleBusyInfobase(ILaunchManager launchManager, String projectName,
            String applicationId, boolean terminateSessions)
    {
        if (launchManager == null)
        {
            // No launch manager → we cannot enumerate launches; fall through to the
            // timeout-guarded update rather than blocking the operation outright.
            return null;
        }

        List<ILaunch> holders = findInfobaseHolders(launchManager, projectName, applicationId);
        if (holders.isEmpty())
        {
            return null;
        }

        if (!terminateSessions)
        {
            String names = describeLaunches(holders);
            return ToolResult.error("Infobase is busy: " + holders.size() //$NON-NLS-1$
                + " 1С client launch(es) started from this EDT instance are running against it (" //$NON-NLS-1$
                + names + "). Updating now would block or fail on the exclusive lock. " //$NON-NLS-1$
                + "Pass terminateSessions=true to stop them automatically, or call terminate_launch " //$NON-NLS-1$
                + "and retry. If a 1С:Enterprise client or Designer was opened OUTSIDE EDT, close it " //$NON-NLS-1$
                + "manually — such external sessions are not visible to this tool.") //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .toJson();
        }

        // terminateSessions=true: stop each holder using the shared polite-terminate
        // helper (same path terminate_launch and the YAXUnit auto-chain use).
        int timeoutSeconds = LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
        for (ILaunch holder : holders)
        {
            String name = launchName(holder);
            boolean done = LaunchLifecycleUtils.terminateAndWait(holder, timeoutSeconds);
            if (!done)
            {
                return ToolResult.error("Could not stop the 1С client launch '" + name //$NON-NLS-1$
                    + "' holding the infobase within " + timeoutSeconds //$NON-NLS-1$
                    + "s. Call terminate_launch with force=true to kill the stuck process, " //$NON-NLS-1$
                    + "then retry update_database.") //$NON-NLS-1$
                    .put("projectName", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .toJson();
            }
            Activator.logInfo("update_database terminated IB holder '" + name //$NON-NLS-1$
                + "' before updating project=" + projectName + ", application=" + applicationId); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Returns the live runtime-client launches whose configuration targets the
     * given {@code project + applicationId}. An Attach launch does not hold the IB
     * in exclusive use (it only debugs a foreign 1С process), so it is excluded.
     */
    private static List<ILaunch> findInfobaseHolders(ILaunchManager launchManager,
            String projectName, String applicationId)
    {
        List<ILaunch> holders = new ArrayList<>();
        for (ILaunch live : LaunchConfigUtils.getAllLiveLaunches(launchManager, projectName))
        {
            if (LaunchConfigUtils.isAttachConfig(live.getLaunchConfiguration()))
            {
                continue;
            }
            if (applicationId.equals(LaunchConfigUtils.getApplicationIdFor(live)))
            {
                holders.add(live);
            }
        }
        return holders;
    }

    private static String describeLaunches(List<ILaunch> launches)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < launches.size(); i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append('\'').append(launchName(launches.get(i))).append('\'');
        }
        return sb.toString();
    }

    private static String launchName(ILaunch launch)
    {
        ILaunchConfiguration cfg = launch.getLaunchConfiguration();
        return cfg != null ? cfg.getName() : "<unknown>"; //$NON-NLS-1$
    }

    /**
     * Updates the database for the specified application.
     *
     * @param projectName name of the project
     * @param applicationId ID of the application
     * @param fullUpdate true for full update, false for incremental
     * @param autoRestructure whether to auto-apply restructurization
     * @param timeoutSeconds maximum time to wait for the update before reporting a timeout
     * @param updateScope which projects to force-recompute before the update (see
     *            {@link LaunchLifecycleUtils#resolveUpdateScope(IProject, String)})
     * @return JSON string with result
     */
    private String updateDatabase(String projectName, String applicationId,
            boolean fullUpdate, boolean autoRestructure, int timeoutSeconds, String updateScope)
    {
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject project = workspace.getRoot().getProject(projectName);

            if (project == null || !project.exists())
            {
                return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
            }

            if (!project.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }

            // Get application manager
            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("IApplicationManager service is not available").toJson(); //$NON-NLS-1$
            }

            // Find application by ID
            Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
            if (!appOpt.isPresent())
            {
                return ToolResult.error("Application not found: " + applicationId + //$NON-NLS-1$
                        ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
            }

            IApplication application = appOpt.get();

            // FORCE a derived-data recompute of the configuration and the requested
            // extensions, then wait for it to settle, BEFORE updating. Without this
            // an extension (.cfe) edited just before the update is never regenerated
            // (waitAllComputations no-ops when nothing is scheduled), so the update
            // consumes the stale export artifact and the infobase keeps the old
            // extension — exactly the "explicit update_database still stale" symptom
            // of bug #19925. updateScope narrows the recompute to a specific extension.
            LaunchLifecycleUtils.recomputeAndSettle(
                LaunchLifecycleUtils.resolveUpdateScope(project, updateScope));

            // Check current update state before proceeding
            ApplicationUpdateState stateBefore = appManager.getUpdateState(application);
            if (stateBefore == ApplicationUpdateState.BEING_UPDATED)
            {
                return ToolResult.error("Application is currently being updated. Please wait.").toJson(); //$NON-NLS-1$
            }

            // Determine update type
            ApplicationUpdateType updateType = fullUpdate
                    ? ApplicationUpdateType.FULL
                    : ApplicationUpdateType.INCREMENTAL;

            // Create execution context with the active Shell so EDT can parent
            // its dialogs. Shared SWT-grab lives in LaunchLifecycleUtils.
            ExecutionContext context = new ExecutionContext();
            Shell shell = LaunchLifecycleUtils.grabActiveShell();
            if (shell != null)
            {
                context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
            }

            Activator.logInfo("Update database: project=" + projectName +  //$NON-NLS-1$
                    ", application=" + applicationId +  //$NON-NLS-1$
                    ", type=" + updateType +  //$NON-NLS-1$
                    ", autoRestructure=" + autoRestructure +  //$NON-NLS-1$
                    ", timeoutSeconds=" + timeoutSeconds); //$NON-NLS-1$

            // Perform the update on a bounded background task. appManager.update()
            // is synchronous and offers no timeout of its own; if the IB is held by
            // a session this tool could not see (e.g. an external 1С client), the
            // call would otherwise block the MCP request thread forever. Future.get
            // with a deadline turns that into a diagnosable timeout error.
            ApplicationUpdateState stateAfter = runUpdateWithTimeout(appManager, application,
                updateType, context, timeoutSeconds, projectName, applicationId);

            // appManager.update() may return before the DB has actually applied the
            // change (async / BEING_UPDATED): the return value is NOT the real gate.
            // Block until getUpdateState is observed UPDATED (bug #19925) so callers
            // never see a stale "success" while the IB still requires update. Reuses
            // the same blocking poll the YAXUnit auto-chain uses.
            if (stateAfter != ApplicationUpdateState.UPDATED)
            {
                stateAfter = LaunchLifecycleUtils.waitForInfobaseApplied(appManager, application);
            }
            boolean applied = stateAfter == ApplicationUpdateState.UPDATED;

            // Build result
            ToolResult result = (applied ? ToolResult.success() : ToolResult.error(
                "Infobase is still out of sync after the update (final state: " //$NON-NLS-1$
                    + stateAfter.name() + "). Extension/configuration changes are NOT yet " //$NON-NLS-1$
                    + "applied to the DB, so a test run would be stale. Retry, or run with " //$NON-NLS-1$
                    + "fullUpdate=true / autoRestructure=true and inspect the EDT problems view.")) //$NON-NLS-1$
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("applicationName", application.getName()) //$NON-NLS-1$
                .put("updateType", updateType.name()) //$NON-NLS-1$
                .put("stateBefore", stateBefore.name()) //$NON-NLS-1$
                .put("stateAfter", stateAfter.name()) //$NON-NLS-1$
                .put("infobaseOutOfSync", !applied); //$NON-NLS-1$

            // Add status message based on result
            if (applied)
            {
                result.put("message", "Database updated successfully"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                result.put("message", "Update did not reach UPDATED (state: " //$NON-NLS-1$
                    + stateAfter.name() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            return result.toJson();
        }
        catch (UpdateTimeoutException e)
        {
            Activator.logError("Database update timed out for application: " + applicationId, e); //$NON-NLS-1$
            return ToolResult.error("Database update timed out after " + e.getTimeoutSeconds() //$NON-NLS-1$
                + "s. The infobase is most likely held by a 1С session this tool cannot stop — " //$NON-NLS-1$
                + "typically a 1С:Enterprise client or Designer opened OUTSIDE EDT. " //$NON-NLS-1$
                + "Close every open 1С session for this infobase and retry. If the session was " //$NON-NLS-1$
                + "started from this EDT instance, run update_database with terminateSessions=true. " //$NON-NLS-1$
                + "You may also raise the limit via the timeoutSeconds parameter for genuinely " //$NON-NLS-1$
                + "long updates.") //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("timeoutSeconds", e.getTimeoutSeconds()) //$NON-NLS-1$
                .toJson();
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error updating database for application: " + applicationId, e); //$NON-NLS-1$

            // Return detailed error information
            ToolResult errorResult = ToolResult.error("Database update failed: " + e.getMessage()); //$NON-NLS-1$
            errorResult.put("applicationId", applicationId); //$NON-NLS-1$
            errorResult.put("projectName", projectName); //$NON-NLS-1$

            // Try to get additional error details
            if (e.getCause() != null)
            {
                errorResult.put("causeMessage", e.getCause().getMessage()); //$NON-NLS-1$
                errorResult.put("causeType", e.getCause().getClass().getSimpleName()); //$NON-NLS-1$
            }

            return errorResult.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during database update", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Runs {@link IApplicationManager#update} on a single-use daemon thread and
     * waits up to {@code timeoutSeconds}. Unwraps the original
     * {@link ApplicationException} so the caller's existing handling still applies;
     * a blown deadline becomes an {@link UpdateTimeoutException}.
     */
    private static ApplicationUpdateState runUpdateWithTimeout(IApplicationManager appManager,
            IApplication application, ApplicationUpdateType updateType, ExecutionContext context,
            int timeoutSeconds, String projectName, String applicationId)
        throws ApplicationException, UpdateTimeoutException
    {
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, "mcp-update-database-" + projectName); //$NON-NLS-1$
            t.setDaemon(true);
            return t;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(factory);
        try
        {
            Callable<ApplicationUpdateState> task = () -> {
                IProgressMonitor monitor = new NullProgressMonitor();
                return appManager.update(application, updateType, context, monitor);
            };
            Future<ApplicationUpdateState> future = executor.submit(task);
            try
            {
                return future.get(timeoutSeconds, TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                // Best-effort interrupt; appManager.update may not respond, hence the
                // daemon thread so a wedged update never keeps the JVM alive.
                future.cancel(true);
                throw new UpdateTimeoutException(timeoutSeconds, e);
            }
            catch (ExecutionException e)
            {
                Throwable cause = e.getCause();
                if (cause instanceof ApplicationException)
                {
                    throw (ApplicationException)cause;
                }
                if (cause instanceof RuntimeException)
                {
                    throw (RuntimeException)cause;
                }
                if (cause instanceof Error)
                {
                    throw (Error)cause;
                }
                throw new RuntimeException(cause != null ? cause : e);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new RuntimeException("Interrupted while waiting for database update", e); //$NON-NLS-1$
            }
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    /**
     * Internal signal that {@link IApplicationManager#update} did not complete
     * within the configured deadline. Carries the timeout so the error message can
     * surface it.
     */
    private static final class UpdateTimeoutException extends Exception
    {
        private static final long serialVersionUID = 1L;

        private final int timeoutSeconds;

        UpdateTimeoutException(int timeoutSeconds, Throwable cause)
        {
            super("Database update timed out after " + timeoutSeconds + "s", cause); //$NON-NLS-1$ //$NON-NLS-2$
            this.timeoutSeconds = timeoutSeconds;
        }

        int getTimeoutSeconds()
        {
            return timeoutSeconds;
        }
    }
}
