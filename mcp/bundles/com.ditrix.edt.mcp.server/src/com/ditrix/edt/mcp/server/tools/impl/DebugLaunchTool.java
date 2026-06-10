/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to launch an EDT debug session.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code launchConfigurationName} — start an existing EDT launch configuration
 *       by its exact name. Works for both runtime-client configs (spawns 1cv8c) and
 *       Attach configurations (attaches to {@code ragent}/{@code rphost} for
 *       server-side code). Does not require {@code applicationId}.</li>
 *   <li>{@code projectName} + {@code applicationId} — legacy path: searches the
 *       runtime-client configs for a match and launches it.</li>
 * </ul>
 */
public class DebugLaunchTool implements IMcpTool
{
    public static final String NAME = "debug_launch"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Start an EDT debug session: either an existing config by launchConfigurationName " //$NON-NLS-1$
            + "(runtime client OR Attach, the latter needed to debug server-side code), or a " //$NON-NLS-1$
            + "runtime-client config matched by projectName + applicationId. If that config is " //$NON-NLS-1$
            + "already running it short-circuits with alreadyRunning:true (terminate_launch first " //$NON-NLS-1$
            + "to force a restart). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('debug_launch')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name; required unless launchConfigurationName is given.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications; required in the projectName+applicationId mode.") //$NON-NLS-1$
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact name of an EDT debug launch config (runtime client or Attach); skips projectName/applicationId.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true: silently apply the configuration->DB update before launching so no " //$NON-NLS-1$
                    + "'Update database?' modal blocks the call (even on a Russian-locale EDT the dialog " //$NON-NLS-1$
                    + "is auto-confirmed); false skips the update and the platform may then show that " //$NON-NLS-1$
                    + "modal. Ignored for Attach.") //$NON-NLS-1$
            .booleanProperty("restartIfRunning", //$NON-NLS-1$
                "Default false: if a matching session is already running, short-circuit with " //$NON-NLS-1$
                    + "alreadyRunning:true and do NOT relaunch (call terminate_launch to restart). " //$NON-NLS-1$
                    + "true: non-interactively terminate the existing session, then relaunch — no " //$NON-NLS-1$
                    + "'Debug session already exists' modal blocks the call.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("launchConfiguration", "Name of the launched/running launch configuration") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("configurationType", "Launch configuration type id") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("attach", "True if this is an Attach (server-side debug) configuration") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("project", "EDT project name associated with the launch") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application id of the launched configuration") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("alreadyRunning", "True if a matching session was already alive; re-launch skipped") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("mode", "Launch mode of the session (e.g. debug, run)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("status", "\"launching\" when the launch was dispatched asynchronously and is " //$NON-NLS-1$ //$NON-NLS-2$
                + "still starting; absent on the alreadyRunning short-circuit. Poll debug_status for readiness.") //$NON-NLS-1$
            .stringProperty("message", "Human-readable status message") //$NON-NLS-1$ //$NON-NLS-2$
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
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", true); //$NON-NLS-1$
        boolean restartIfRunning = JsonUtils.extractBooleanArgument(params, "restartIfRunning", false); //$NON-NLS-1$

        // Mode 1: explicit config name — no project/application required.
        if (configName != null && !configName.isEmpty())
        {
            return launchByConfigName(configName, updateBeforeLaunch, restartIfRunning);
        }

        // Mode 2: project + application (runtime-client only).
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required (or pass launchConfigurationName)").toJson(); //$NON-NLS-1$
        }

        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required. Use get_applications to get application list, " //$NON-NLS-1$
                + "or pass launchConfigurationName to start a config by name (e.g. an Attach config).").toJson(); //$NON-NLS-1$
        }

        // Refuse only the transient BUILDING state; a missing/closed project falls
        // through to the value-naming "Project not found" below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        return launchDebug(projectName, applicationId, updateBeforeLaunch, restartIfRunning);
    }

    /**
     * Launches a specific EDT debug configuration by name.
     * Works for both runtime-client and Attach configuration types.
     */
    private String launchByConfigName(String configName, boolean updateBeforeLaunch,
        boolean restartIfRunning)
    {
        try
        {
            ILaunchManager launchManager = LaunchConfigUtils.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            ILaunchConfiguration config = LaunchConfigUtils.findLaunchConfigByName(launchManager, configName);
            if (config == null)
            {
                ToolResult err = ToolResult.error("Launch configuration not found: '" + configName //$NON-NLS-1$
                    + "'. Create it in EDT first."); //$NON-NLS-1$
                err.put("availableConfigurations", listAvailableConfigs(launchManager)); //$NON-NLS-1$
                return err.toJson();
            }

            String typeId = LaunchConfigUtils.getConfigTypeId(config);
            boolean isAttach = LaunchConfigUtils.isAttachConfigTypeId(typeId);
            String configProject = LaunchConfigUtils.readAttribute(config,
                LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String effectiveAppId = LaunchConfigUtils.getApplicationIdFor(config);

            // Unified existing-session decision (D5c, Bitrix 20074). One
            // (project, app-id) → at most one live CLIENT session, with the
            // live-thread discriminator applied so a thread-less standalone-SERVER /
            // profiling session sharing this app id NEVER short-circuits the client.
            // Covers both a live DEBUG target and a debug-target-less RUN-mode launch
            // (the former A12 guard). restartIfRunning is honored here exactly as in
            // the target-manager path: false → alreadyRunning, true → non-interactive
            // terminate + relaunch.
            ExistingClientSession existingByName = resolveExistingClientSession(effectiveAppId);
            if (existingByName != null)
            {
                AlreadyRunningContext ctx = new AlreadyRunningContext(ALREADY_RUNNING_MESSAGE);
                ctx.launchConfiguration = config.getName();
                ctx.configurationType = typeId;
                ctx.attach = Boolean.valueOf(isAttach);
                ctx.project = configProject;
                String shortCircuit = handleExistingClientSession(existingByName, effectiveAppId,
                    restartIfRunning, ctx);
                if (shortCircuit != null)
                {
                    return shortCircuit;
                }
                // restartIfRunning=true: the old client was terminated — fall through
                // and relaunch.
            }

            // Delegate-criterion duplicate guard (Bitrix 20074). Runtime-client DEBUG
            // path only: the "Debug session already exists" code-1003 modal is raised
            // by RuntimeClientLaunchDelegate, which scans
            // IRuntimeDebugClientTargetManager.listDebugTargets() (NOT ILaunchManager)
            // and keys on ATTR_PROJECT_NAME + (ATTR_APPLICATION_ID else default app).
            // Our findActiveTarget/findActiveLaunch guards above scan ILaunchManager
            // and key on getApplicationIdFor() — so a UI-started ("Debug As") session,
            // or a config without a persisted ATTR_APPLICATION_ID (we mint a synthetic
            // launch:<name> the delegate never uses), slips past them and the unattended
            // call then hangs on the human modal. This supplements them with the
            // delegate's own set + key.
            if (!isAttach && configProject != null && !configProject.isEmpty())
            {
                String dupResult = handleDelegateDuplicateSession(config, configProject,
                    isAttach, typeId, restartIfRunning);
                if (dupResult != null)
                {
                    return dupResult;
                }
            }

            // For runtime-client configs, run the usual DB-update preflight.
            if (!isAttach && updateBeforeLaunch && configProject != null && !configProject.isEmpty())
            {
                String notReady = ProjectStateChecker.checkReadyOrError(configProject);
                if (notReady != null)
                {
                    return ToolResult.error(notReady).toJson();
                }
                String updateError = updateDatabaseIfNeeded(configProject, effectiveAppId);
                if (updateError != null)
                {
                    return ToolResult.error(updateError).toJson();
                }
            }

            String launchError = performLaunch(config, updateBeforeLaunch);
            if (launchError != null)
            {
                return ToolResult.error("Failed to launch debug session: " + launchError).toJson(); //$NON-NLS-1$
            }

            ToolResult result = ToolResult.success()
                .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                .put("configurationType", typeId) //$NON-NLS-1$
                .put("attach", isAttach) //$NON-NLS-1$
                .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                .put("status", "launching") //$NON-NLS-1$ //$NON-NLS-2$
                .put("message", isAttach //$NON-NLS-1$
                    ? "Attach debug session is connecting — poll debug_status to confirm it is " //$NON-NLS-1$
                        + "running, then wait_for_break to block until a breakpoint is hit." //$NON-NLS-1$
                    : "Debug session is starting asynchronously. The 1C client may show startup " //$NON-NLS-1$
                        + "dialogs (login / database update); this call does NOT wait for it. " //$NON-NLS-1$
                        + "Poll debug_status until the session appears running, then use " //$NON-NLS-1$
                        + "wait_for_break."); //$NON-NLS-1$
            if (configProject != null && !configProject.isEmpty())
            {
                result.put("project", configProject); //$NON-NLS-1$
            }
            if (effectiveAppId != null)
            {
                result.put("applicationId", effectiveAppId); //$NON-NLS-1$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during debug launch by name", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Legacy path: launch a runtime-client config matched by project+application.
     */
    private String launchDebug(String projectName, String applicationId, boolean updateBeforeLaunch,
        boolean restartIfRunning)
    {
        try
        {
            ProjectContext ctx = ProjectContext.of(projectName);

            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }

            if (!ctx.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }

            IProject project = ctx.project();

            // Verify application exists and get its name
            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            String applicationName = applicationId; // Default to ID if can't get name
            IApplication application = null;

            if (appManager != null)
            {
                try
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return ToolResult.error("Application not found: " + applicationId + //$NON-NLS-1$
                                ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
                    }
                    application = appOpt.get();
                    applicationName = application.getName();
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Error checking application", e); //$NON-NLS-1$
                    // Continue - we'll try to find launch config anyway
                }
            }

            // Unified existing-session decision (D5c, Bitrix 20074) — the SAME
            // live-thread-discriminated detector + restartIfRunning handling the
            // by-name path uses, so both call styles behave identically. A live DEBUG
            // client target OR a debug-target-less RUN-mode launch short-circuits (the
            // former A12 guard); a thread-less standalone-SERVER session sharing this
            // app id does NOT (the client proceeds and attaches). To force a fresh
            // launch when restartIfRunning is false, terminate_launch first.
            ExistingClientSession existingByApp = resolveExistingClientSession(applicationId);
            if (existingByApp != null)
            {
                ILaunchConfiguration activeConfig = existingByApp.launch != null
                    ? existingByApp.launch.getLaunchConfiguration() : null;
                AlreadyRunningContext runningCtx = new AlreadyRunningContext(ALREADY_RUNNING_MESSAGE);
                runningCtx.project = projectName;
                runningCtx.attach = Boolean.FALSE;
                if (activeConfig != null)
                {
                    runningCtx.launchConfiguration = activeConfig.getName();
                }
                String shortCircuit = handleExistingClientSession(existingByApp, applicationId,
                    restartIfRunning, runningCtx);
                if (shortCircuit != null)
                {
                    return shortCircuit;
                }
                // restartIfRunning=true: the old client was terminated — fall through
                // and relaunch.
            }

            // Update database before launch if requested. Routes through the
            // shared LaunchLifecycleUtils.updateApplicationIfNeeded so debug_launch
            // analyses "does the IB need updating?" the same way the YAXUnit tools
            // do: skip on UPDATED, wait on BEING_UPDATED, incremental-update otherwise.
            // For a STANDALONE-SERVER application the programmatic update is SKIPPED
            // and deferred to the launch delegate's coordinated path instead (D6,
            // Bitrix 20091) — see runPreLaunchUpdateStep.
            if (appManager != null && application != null)
            {
                String updateError = runPreLaunchUpdateStep(project, applicationId,
                    appManager, updateBeforeLaunch);
                if (updateError != null)
                {
                    return ToolResult.error(updateError).toJson();
                }
            }

            // Get launch manager
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            // Get launch configuration type
            ILaunchConfigurationType configType = launchManager
                    .getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID);
            if (configType == null)
            {
                return ToolResult.error("Launch configuration type not found: " //$NON-NLS-1$
                        + LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID).toJson();
            }

            // Find matching launch configuration via the shared helper.
            ILaunchConfiguration matchingConfig = LaunchConfigUtils.findLaunchConfig(
                    launchManager, configType, projectName, applicationId);

            if (matchingConfig == null)
            {
                ToolResult errorResult = ToolResult.error("No launch configuration found for project '" //$NON-NLS-1$
                    + projectName + "' and application '" + applicationName + "' (" //$NON-NLS-1$ //$NON-NLS-2$
                    + applicationId + "). Create a runtime-client launch configuration in EDT, " //$NON-NLS-1$
                    + "or pass launchConfigurationName to start an Attach configuration."); //$NON-NLS-1$
                errorResult.put("availableConfigurations", listAvailableConfigs(launchManager)); //$NON-NLS-1$
                return errorResult.toJson();
            }

            final String configName = matchingConfig.getName();
            Activator.logInfo("Launching debug: config=" + configName //$NON-NLS-1$
                + ", project=" + projectName //$NON-NLS-1$
                + ", application=" + applicationId); //$NON-NLS-1$

            // Delegate-criterion duplicate guard (Bitrix 20074) — same supplement as
            // the by-name path: catch a UI-started / target-manager-only DEBUG session
            // the ILaunchManager guards above cannot see, BEFORE config.launch raises
            // the human "Debug session already exists" modal. See
            // handleDelegateDuplicateSession.
            String dupResult = handleDelegateDuplicateSession(matchingConfig, projectName,
                false, LaunchConfigUtils.getConfigTypeId(matchingConfig), restartIfRunning);
            if (dupResult != null)
            {
                return dupResult;
            }

            String launchError = performLaunch(matchingConfig, updateBeforeLaunch);
            if (launchError != null)
            {
                return ToolResult.error("Failed to launch debug session: " + launchError).toJson(); //$NON-NLS-1$
            }

            return ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("launchConfiguration", configName) //$NON-NLS-1$
                .put("configurationType", LaunchConfigUtils.getConfigTypeId(matchingConfig)) //$NON-NLS-1$
                .put("attach", false) //$NON-NLS-1$
                .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                .put("status", "launching") //$NON-NLS-1$ //$NON-NLS-2$
                .put("message", "Debug session is starting asynchronously. The 1C client may show " //$NON-NLS-1$ //$NON-NLS-2$
                    + "startup dialogs (login / database update); this call does NOT wait for it. " //$NON-NLS-1$
                    + "Poll debug_status until the session appears running, then use wait_for_break.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during debug launch", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Runs the EDT "update database before launch" step for a runtime-client launch.
     * Returns {@code null} on success, or an error message describing the failure.
     *
     * <p>Synthetic application ids — {@code attach:<configName>},
     * {@code launch:<configName>} and {@code ServerApplication.<app>}, see
     * {@link LaunchConfigUtils#isSyntheticApplicationId} — skip the preflight.
     * They are minted by
     * {@link LaunchConfigUtils#getApplicationIdFor(ILaunchConfiguration)} (or, for
     * the {@code ServerApplication.} form, by
     * {@code DebugServerTargetSupport}) for sessions WITHOUT a persisted
     * {@code ATTR_APPLICATION_ID} and cannot be
     * resolved through {@link IApplicationManager}: feeding one into
     * {@code updateApplicationIfNeeded} fails with "Application not found:
     * launch:&lt;name&gt;" and would refuse a perfectly launchable configuration.
     * (The original guard knew only the {@code attach:} prefix, so introducing the
     * {@code launch:} fallback for UI-started-session tracking silently turned
     * such by-name launches into errors — rv1 review FIND-1.) Skipping is safe:
     * if the launch delegate still detects an out-of-date IB it shows its update
     * modal, which the armed {@link LaunchUpdateDialogAutoConfirmer} presses.
     *
     * <p>For the {@code ServerApplication.*} form the skip is not merely an
     * "unresolvable id" technicality — it is the INTENDED behavior (D6, Bitrix
     * 20091): a standalone-server application must never be DB-updated out-of-band,
     * because {@code IApplicationManager.update} on it starts the standalone server
     * in RUN mode and holds a cached designer-agent connection that wedges the
     * subsequent debug restart. The update is deferred to the launch delegate's
     * coordinated path (server prepared in debug mode FIRST, then updated), whose
     * dialog the armed confirmer auto-presses — see
     * {@link DebugServerTargetSupport#isServerApplicationId} and
     * {@link #runPreLaunchUpdateStep}, the same gate on the
     * project+applicationId path.
     */
    private String updateDatabaseIfNeeded(String projectName, String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty()
            || LaunchConfigUtils.isSyntheticApplicationId(applicationId))
        {
            return null;
        }
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.isOpen())
        {
            return null;
        }
        IProject project = ctx.project();
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return null;
        }
        // Shared update analysis: skip on UPDATED, wait on BEING_UPDATED, otherwise
        // incremental-update — same path as the YAXUnit auto-chain.
        return LaunchLifecycleUtils.updateApplicationIfNeeded(project, applicationId, appManager)
            .orElse(null);
    }

    /**
     * The pre-launch DB-update step of the project+applicationId path, with the D6
     * server-application gate (Bitrix 20091). Returns {@code null} to proceed with
     * the launch, or an error message that aborts the call.
     *
     * <ul>
     *   <li>{@code updateBeforeLaunch=false} — documented opt-out: no programmatic
     *       update is run (and {@link #performLaunch} leaves the update confirmer
     *       unarmed, so the platform's update modal — if any — is a human's).</li>
     *   <li>{@code ServerApplication.*} id ({@link
     *       DebugServerTargetSupport#isServerApplicationId}) — the programmatic
     *       update is SKIPPED and deferred to the launch delegate's coordinated
     *       path. Updating a standalone-server application out-of-band starts the
     *       server in RUN mode and caches a live designer-agent connection
     *       (DesignerSessionPool); the launch delegate then restarts the server in
     *       DEBUG mode and the connection teardown wedges the launch. EDT's native
     *       order (prepare the server in debug mode FIRST, then update) has no such
     *       restart; its "Application update" dialog — shown only when the IB is
     *       stale — is auto-pressed by the confirmer {@link #performLaunch} arms
     *       exactly when {@code updateBeforeLaunch=true}. Trade-off: the synchronous
     *       "stale IB" refusal disappears for server apps (the update happens
     *       asynchronously inside the launch); failures surface via
     *       {@code debug_status} / the EDT log — matching EDT-native UX.</li>
     *   <li>Any other (file / client-server infobase) application — the programmatic
     *       pre-update runs exactly as before through
     *       {@link LaunchLifecycleUtils#updateApplicationIfNeeded}: skip on UPDATED,
     *       wait on BEING_UPDATED, incremental-update otherwise; a stale IB still
     *       refuses synchronously.</li>
     * </ul>
     *
     * <p>Package-private (static, mock-friendly) so the headless unit tests can
     * assert the gate decision without a live workbench.
     */
    static String runPreLaunchUpdateStep(IProject project, String applicationId,
        IApplicationManager appManager, boolean updateBeforeLaunch)
    {
        if (!updateBeforeLaunch)
        {
            return null;
        }
        if (DebugServerTargetSupport.isServerApplicationId(applicationId))
        {
            Activator.logInfo("debug_launch: server application: deferring DB update to the " //$NON-NLS-1$
                + "launch delegate's coordinated path (auto-confirmed): applicationId=" //$NON-NLS-1$
                + applicationId);
            return null;
        }
        return LaunchLifecycleUtils.updateApplicationIfNeeded(project, applicationId, appManager)
            .orElse(null);
    }

    /** Max wait (ms) for a non-interactive terminate to take effect — mirrors the
     * delegate's {@code terminateOldDebugSessions} ~3s grace. */
    private static final long RESTART_TERMINATE_TIMEOUT_MS = 3000L;

    /**
     * The single existing-session decision (D5c, Bitrix 20074): one
     * {@code (project, applicationId)} resolves to AT MOST one
     * {@link ExistingClientSession} via {@link #resolveExistingClientSession}, and
     * every call site funnels that result through
     * {@link #handleExistingClientSession} so the {@code restartIfRunning} flag is
     * honored identically in EVERY path — by-name and by-project+application, and
     * BOTH the {@link DebugSessionRegistry} ({@code ILaunchManager}) guards and the
     * {@link DebugServerTargetSupport} (target-manager) detect.
     *
     * <p>A session is a real CLIENT session worth short-circuiting/terminating only
     * when it is either:
     * <ul>
     *   <li>a DEBUG launch/target with ≥1 non-terminated thread — a thin-client
     *       debug session ({@link DebugServerTargetSupport#findFirstLiveThread} is
     *       the same discriminator EDT's launch delegate uses), or</li>
     *   <li>a RUN-mode launch — a genuine running 1C client that carries NO debug
     *       target at all (the A12/A13 already-running guard); the live-thread gate
     *       does not apply because there is no debug target to inspect.</li>
     * </ul>
     * A DEBUG launch/target whose every debug target is thread-less is a 1C
     * standalone-SERVER / profiling session for the same app id — it is NEVER a
     * client session: it must not short-circuit the client (the client proceeds and
     * attaches) and must never be terminated by {@code restartIfRunning}.
     */
    static final class ExistingClientSession
    {
        /**
         * The owning Eclipse launch. Non-{@code null} for the {@code ILaunchManager}
         * paths (it IS the terminate handle when {@link #liveTarget} is {@code null},
         * the RUN-mode case); may be {@code null} for the target-manager path, where
         * {@link #liveTarget} is always set and is the terminate handle instead.
         */
        final ILaunch launch;
        /**
         * The matched live DEBUG target with ≥1 live thread, or {@code null} when
         * the session is a RUN-mode launch (no debug target). Drives the terminate
         * path: a target is terminated via the target; a RUN-mode launch via the
         * launch.
         */
        final IDebugTarget liveTarget;
        /** The session's launch mode (e.g. {@code debug}, {@code run}). */
        final String mode;

        ExistingClientSession(ILaunch launch, IDebugTarget liveTarget, String mode)
        {
            this.launch = launch;
            this.liveTarget = liveTarget;
            this.mode = mode;
        }
    }

    /**
     * Resolves the ONE live CLIENT session the {@code ILaunchManager} knows for the
     * given applicationId, with the live-thread discriminator applied so a
     * thread-less standalone-SERVER / profiling session never matches (Bitrix 20074).
     *
     * <p>Order, mirroring the legacy guards it unifies:
     * <ol>
     *   <li>{@link DebugSessionRegistry#findActiveTarget} — a non-terminated DEBUG
     *       target for this app id. It matches ONLY when that target also carries a
     *       live thread ({@link DebugServerTargetSupport#findFirstLiveThread}); a
     *       thread-less server/profiling target is rejected so the client proceeds.</li>
     *   <li>{@link DebugSessionRegistry#findActiveLaunch} — any non-terminated launch
     *       for this app id, catching a RUN-mode (or otherwise debug-target-less)
     *       client the target scan misses. A RUN-mode launch carries no debug target,
     *       so it is a genuine running client (returned as a session with a
     *       {@code null} target). A DEBUG launch is returned ONLY when one of its
     *       debug targets has a live thread — otherwise it is the same thread-less
     *       server session and is rejected.</li>
     * </ol>
     *
     * @param applicationId the application id (real or synthetic); {@code null}/empty
     *     never matches
     * @return the live client session, or {@code null} when none exists (so the
     *     caller proceeds to launch, including when only a thread-less server session
     *     shares this app id)
     */
    static ExistingClientSession resolveExistingClientSession(String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return null;
        }
        // The two ILaunchManager views the legacy guards used, now run through one
        // live-thread-discriminated decision (decideExistingClientSession). The lookups
        // are the only workbench-bound part; the decision is pure and unit-tested.
        return decideExistingClientSession(
            DebugSessionRegistry.findActiveTarget(applicationId),
            DebugSessionRegistry.findActiveLaunch(applicationId));
    }

    /**
     * The PURE existing-client decision over the two {@code ILaunchManager} views
     * (D5c, Bitrix 20074) — split out from {@link #resolveExistingClientSession} so
     * the live-thread discrimination is unit-testable without a live workbench.
     *
     * <ol>
     *   <li>{@code activeTarget} (from {@link DebugSessionRegistry#findActiveTarget}) —
     *       a live DEBUG target. Matches a client ONLY when it carries a live thread
     *       ({@link DebugServerTargetSupport#findFirstLiveThread}); a thread-less
     *       server/profiling target is rejected here.</li>
     *   <li>{@code activeLaunch} (from {@link DebugSessionRegistry#findActiveLaunch}) —
     *       any non-terminated launch the target scan missed. A live-thread debug
     *       target it owns ⇒ client; ZERO debug targets ⇒ genuine RUN-mode client (the
     *       A12/A13 guard); debug target(s) but none with a live thread ⇒ standalone-
     *       server session ⇒ NOT a client (returns {@code null}, so the client
     *       proceeds and attaches).</li>
     * </ol>
     *
     * @param activeTarget the live DEBUG target for the app id, or {@code null}
     * @param activeLaunch a non-terminated launch for the app id, or {@code null}
     * @return the live client session, or {@code null} when none is a real client
     */
    static ExistingClientSession decideExistingClientSession(IDebugTarget activeTarget,
        ILaunch activeLaunch)
    {
        // 1) A live DEBUG target with a live thread = a real client debug session.
        if (activeTarget != null && DebugServerTargetSupport.findFirstLiveThread(activeTarget) != null)
        {
            ILaunch launch = activeTarget.getLaunch();
            String mode = launch != null ? launch.getLaunchMode() : ILaunchManager.DEBUG_MODE;
            return new ExistingClientSession(launch, activeTarget, mode);
        }

        // 2) A non-terminated launch the target scan missed (e.g. RUN mode, no debug
        //    target). A RUN-mode launch is a genuine running client and short-circuits
        //    as before; a DEBUG launch whose every debug target is thread-less is the
        //    same standalone-server session and must NOT short-circuit the client.
        if (activeLaunch == null)
        {
            return null;
        }
        IDebugTarget liveTarget = firstLiveThreadTarget(activeLaunch);
        if (liveTarget != null)
        {
            // A live DEBUG target this launch owns — a client debug session.
            return new ExistingClientSession(activeLaunch, liveTarget, activeLaunch.getLaunchMode());
        }
        if (activeLaunch.getDebugTargets().length == 0)
        {
            // No debug target at all (RUN mode, or a launch that never produced one):
            // a genuine running client — the A12/A13 already-running guard. There is
            // no thread-less server target to confuse it with.
            return new ExistingClientSession(activeLaunch, null, activeLaunch.getLaunchMode());
        }
        // The launch HAS debug target(s) but none carries a live thread — a
        // standalone-server / profiling session. Do NOT short-circuit; the client
        // proceeds and attaches (Bitrix 20074).
        return null;
    }

    /**
     * @return the first debug target of {@code launch} that carries a non-terminated
     *     thread ({@link DebugServerTargetSupport#findFirstLiveThread}), or
     *     {@code null} when the launch has no such live-client target. Best-effort.
     */
    static IDebugTarget firstLiveThreadTarget(ILaunch launch)
    {
        if (launch == null)
        {
            return null;
        }
        for (IDebugTarget target : launch.getDebugTargets())
        {
            if (target != null && !target.isTerminated()
                && DebugServerTargetSupport.findFirstLiveThread(target) != null)
            {
                return target;
            }
        }
        return null;
    }

    /**
     * The single existing-CLIENT-session handler all {@code ILaunchManager}-sourced
     * call sites funnel through, so {@code restartIfRunning} is honored identically
     * everywhere (D5c, Bitrix 20074):
     * <ul>
     *   <li>{@code restartIfRunning=false} (default) → returns the
     *       {@code alreadyRunning:true} short-circuit JSON (no launch), carrying the
     *       identity fields the caller supplied.</li>
     *   <li>{@code restartIfRunning=true} → non-interactively terminates the existing
     *       client session (its live DEBUG target, or — for a RUN-mode launch — the
     *       launch), {@code forgetApplication}s it and waits ≤3s for death, then
     *       returns {@code null} so the caller relaunches — exactly what the
     *       target-manager path ({@link #handleDelegateDuplicateSession}) already does.</li>
     * </ul>
     *
     * @param session the resolved live client session (never {@code null})
     * @param applicationId the application id under which the session was found
     * @param restartIfRunning the flag from the request
     * @param ctx identity fields to echo into the {@code alreadyRunning} payload
     * @return the short-circuit JSON, or {@code null} to proceed with the launch
     */
    String handleExistingClientSession(ExistingClientSession session, String applicationId,
        boolean restartIfRunning, AlreadyRunningContext ctx)
    {
        if (!restartIfRunning)
        {
            Activator.logInfo("debug_launch short-circuit (alreadyRunning): applicationId=" //$NON-NLS-1$
                + applicationId + ", mode=" + session.mode //$NON-NLS-1$
                + ", config=" + ctx.launchConfiguration); //$NON-NLS-1$
            return ctx.buildAlreadyRunning(session.mode, applicationId).toJson();
        }

        // restartIfRunning: stop the existing client session non-interactively, then
        // proceed. resolveExistingClientSession only ever returns a real client (a
        // live-thread DEBUG target, or a RUN-mode launch), NEVER a thread-less
        // server/profiling target — so this terminate can never kill a debug server
        // (Bitrix 20074).
        if (session.liveTarget != null)
        {
            Activator.logInfo("debug_launch restartIfRunning: terminating existing client debug " //$NON-NLS-1$
                + "target: applicationId=" + applicationId); //$NON-NLS-1$
            terminateExistingSessionAndWait(session.liveTarget, applicationId);
        }
        else
        {
            Activator.logInfo("debug_launch restartIfRunning: terminating existing client launch " //$NON-NLS-1$
                + "(mode=" + session.mode + "): applicationId=" + applicationId); //$NON-NLS-1$ //$NON-NLS-2$
            terminateExistingLaunchAndWait(session.launch, applicationId);
        }
        return null;
    }

    /**
     * Identity fields echoed into an {@code alreadyRunning:true} short-circuit so the
     * unified {@link #handleExistingClientSession} can build a per-call-site payload
     * that matches what each legacy guard emitted (output-schema parity). Optional
     * fields ({@code null}/empty) are omitted.
     */
    static final class AlreadyRunningContext
    {
        String launchConfiguration;
        String configurationType;
        Boolean attach;
        String project;
        final String message;

        AlreadyRunningContext(String message)
        {
            this.message = message;
        }

        ToolResult buildAlreadyRunning(String mode, String applicationId)
        {
            ToolResult already = ToolResult.success()
                .put("alreadyRunning", true) //$NON-NLS-1$
                .put("mode", mode) //$NON-NLS-1$
                .put("message", message); //$NON-NLS-1$
            if (launchConfiguration != null && !launchConfiguration.isEmpty())
            {
                already.put("launchConfiguration", launchConfiguration); //$NON-NLS-1$
            }
            if (configurationType != null && !configurationType.isEmpty())
            {
                already.put("configurationType", configurationType); //$NON-NLS-1$
            }
            if (attach != null)
            {
                already.put("attach", attach.booleanValue()); //$NON-NLS-1$
            }
            if (project != null && !project.isEmpty())
            {
                already.put("project", project); //$NON-NLS-1$
            }
            if (applicationId != null && !applicationId.isEmpty())
            {
                already.put("applicationId", applicationId); //$NON-NLS-1$
            }
            return already;
        }
    }

    /** Default short-circuit message for a still-running client session. */
    private static final String ALREADY_RUNNING_MESSAGE =
        "Launch configuration is already running — skipped re-launch. " //$NON-NLS-1$
            + "Call terminate_launch first, or pass restartIfRunning=true, to start a fresh session."; //$NON-NLS-1$

    /**
     * Terminates the given running launch non-interactively (the RUN-mode / no-debug-
     * target client case) and waits up to {@link #RESTART_TERMINATE_TIMEOUT_MS} for it
     * to die, then clears the registry for {@code appId} — the launch analogue of
     * {@link #terminateExistingSessionAndWait}. Best-effort: a failure is logged, not
     * thrown; the caller proceeds to launch regardless.
     */
    void terminateExistingLaunchAndWait(ILaunch launch, String appId)
    {
        if (launch == null)
        {
            if (appId != null && !appId.isEmpty())
            {
                DebugSessionRegistry.get().forgetApplication(appId);
            }
            return;
        }
        try
        {
            if (launch.canTerminate())
            {
                launch.terminate();
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error terminating existing launch before restart", e); //$NON-NLS-1$
        }
        long deadline = System.currentTimeMillis() + RESTART_TERMINATE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                if (launch.isTerminated())
                {
                    break;
                }
            }
            catch (Exception e)
            {
                break;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (appId != null && !appId.isEmpty())
        {
            DebugSessionRegistry.get().forgetApplication(appId);
        }
    }

    /**
     * Detects a live runtime-client DEBUG session for {@code config}'s
     * {@code (project, delegate-app-id)} the EXACT way EDT's
     * {@code RuntimeClientLaunchDelegate.checkExistingDebugSessions} does — via
     * {@link DebugServerTargetSupport#findRuntimeClientDebugTarget} over the target
     * manager's {@code listDebugTargets()} set, keyed on the delegate's app id
     * ({@code ATTR_APPLICATION_ID} else {@code getDefaultApplication(project)}, see
     * {@link #resolveDelegateApplicationId}). This is the primary fix for Bitrix
     * 20074: it fires BEFORE {@code config.launch} can raise the human
     * "Debug session already exists" code-1003 modal that hangs an unattended call.
     *
     * <ul>
     *   <li>No live duplicate → returns {@code null}; the caller proceeds to launch.</li>
     *   <li>Duplicate found, {@code restartIfRunning=false} (default) → returns the
     *       {@code alreadyRunning:true} short-circuit JSON (no dialog, no launch),
     *       consistent with the documented contract.</li>
     *   <li>Duplicate found, {@code restartIfRunning=true} → terminates the existing
     *       session NON-interactively, {@code forgetApplication}s it, waits up to
     *       ~3s for process death, then returns {@code null} so the caller relaunches.</li>
     * </ul>
     *
     * @return the short-circuit JSON, or {@code null} to proceed with the launch
     */
    private String handleDelegateDuplicateSession(ILaunchConfiguration config, String projectName,
        boolean isAttach, String typeId, boolean restartIfRunning)
    {
        String delegateAppId = resolveDelegateApplicationId(config, projectName);
        IDebugTarget existing =
            DebugServerTargetSupport.findRuntimeClientDebugTarget(projectName, delegateAppId);
        if (existing == null)
        {
            return null;
        }

        // Defensive re-assert (Bitrix 20074): findRuntimeClientDebugTarget already
        // required a live thread, but if the matched target lost its last live thread
        // between detection and now it is no longer a client — do NOT short-circuit or
        // terminate; just proceed to launch.
        if (DebugServerTargetSupport.findFirstLiveThread(existing) == null)
        {
            Activator.logInfo("debug_launch: target-manager match has no live thread " //$NON-NLS-1$
                + "(server/profiling target) — not short-circuiting; proceeding: project=" //$NON-NLS-1$
                + projectName + ", applicationId=" + delegateAppId); //$NON-NLS-1$
            return null;
        }

        // Funnel through the SAME restartIfRunning-aware handler the ILaunchManager
        // guards use, so the flag is honored identically across every path (D5c). The
        // matched target carries a live thread, so the handler's terminate path stops a
        // real client, never a debug server.
        ExistingClientSession session = new ExistingClientSession(existing.getLaunch(), existing,
            ILaunchManager.DEBUG_MODE);
        AlreadyRunningContext ctx = new AlreadyRunningContext(
            "Debug session is already running (detected via EDT's debug target manager — e.g. a " //$NON-NLS-1$
                + "UI-started 'Debug As' session) — skipped re-launch to avoid the 'Debug session " //$NON-NLS-1$
                + "already exists' modal. Call terminate_launch first, or pass " //$NON-NLS-1$
                + "restartIfRunning=true, to start a fresh session."); //$NON-NLS-1$
        ctx.launchConfiguration = config.getName();
        ctx.configurationType = typeId;
        ctx.attach = Boolean.valueOf(isAttach);
        ctx.project = projectName;
        // Force mode "debug" in the short-circuit payload (this path is the runtime-
        // client DEBUG delegate) regardless of the synthetic launch's reported mode.
        return handleExistingClientSession(session, delegateAppId, restartIfRunning, ctx);
    }

    /**
     * Resolves the application id EXACTLY the way EDT's launch delegate's
     * {@code findConfiguredApplicationIdentifier} does: the config's persisted
     * {@code ATTR_APPLICATION_ID} when present, else the project's
     * {@code IApplicationManager.getDefaultApplication(project)} id. NOT the
     * synthetic {@code launch:<configName>} our {@code getApplicationIdFor} mints
     * when no real id is persisted — that synthetic id is what made the duplicate
     * guard miss the delegate's session in Bitrix 20074. Returns {@code null} if the
     * id cannot be resolved (no persisted id and no default application).
     */
    private String resolveDelegateApplicationId(ILaunchConfiguration config, String projectName)
    {
        String realId = LaunchConfigUtils.readAttribute(config,
            LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
        if (realId != null && !realId.isEmpty())
        {
            return realId;
        }
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.isOpen())
        {
            return null;
        }
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return null;
        }
        // resolveDefaultApplicationId returns the original (empty) value when there is
        // no default — normalize that to null so findRuntimeClientDebugTarget's
        // empty-id guard short-circuits instead of matching on "".
        String resolved = LaunchLifecycleUtils.resolveDefaultApplicationId(
            ctx.project(), null, appManager);
        return resolved != null && !resolved.isEmpty() ? resolved : null;
    }

    /**
     * Terminates the given live debug target non-interactively and waits up to
     * {@link #RESTART_TERMINATE_TIMEOUT_MS} for it to die, then clears the
     * registry for {@code appId} — mirroring the delegate's
     * {@code terminateOldDebugSessions} (terminate → short wait → proceed) and the
     * {@code terminate_launch} cleanup (forget the application so a half-dead client
     * is not raced). Best-effort: a termination failure is logged, not thrown — the
     * caller proceeds to launch regardless, and if a stale client lingers the launch
     * delegate's modal is the armed auto-confirmer's job.
     */
    private void terminateExistingSessionAndWait(IDebugTarget target, String appId)
    {
        try
        {
            if (target.canTerminate())
            {
                target.terminate();
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error terminating existing debug session before restart", e); //$NON-NLS-1$
        }
        long deadline = System.currentTimeMillis() + RESTART_TERMINATE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                if (target.isTerminated())
                {
                    break;
                }
            }
            catch (Exception e)
            {
                break;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (appId != null && !appId.isEmpty())
        {
            DebugSessionRegistry.get().forgetApplication(appId);
        }
    }

    /**
     * Launches the given configuration in debug mode, asynchronously.
     *
     * <p>Uses a direct {@code config.launch(DEBUG_MODE, null)} — not
     * {@code DebugUITools.launch} — because the latter may open modal dialogs
     * (save-prompt, perspective-switch, already-running-confirmation) that
     * block the MCP worker thread indefinitely and eventually close the HTTP
     * socket. {@code debug_yaxunit_tests} uses the same direct path.
     *
     * <p>The launch is scheduled on the UI thread via {@code asyncExec} and this
     * method returns immediately — it does NOT wait for the 1C client to finish
     * starting. A runtime client typically shows GUI dialogs at startup (login,
     * database update), so blocking here (as the previous {@code syncExec} did)
     * would hang the MCP worker until it timed out and would freeze the EDT UI
     * while the modal dialog is up. Callers therefore report
     * {@code status: "launching"}; readiness is observed separately via
     * {@code debug_status} / {@code wait_for_break}.
     *
     * <p>Because the launch now runs after this method returns, any failure can no
     * longer be surfaced synchronously to the caller — it is logged from inside the
     * async lambda instead. Only the synchronous (headless, no UI thread) path can
     * still return an error message.
     *
     * <p>The {@code config.launch(...)} call is always wrapped in
     * {@link LaunchUpdateDialogAutoConfirmer#arm(boolean, boolean)}/{@link LaunchUpdateDialogAutoConfirmer#disarm(boolean, boolean)}.
     * Two independently-gated matchers share one {@link Display} filter:
     * <ul>
     *   <li>the D4 "Application update" matcher is armed only when
     *       {@code autoConfirmUpdateDialog} is {@code true}. Even though the
     *       pre-launch update ({@code updateApplicationIfNeeded}) normally leaves the
     *       IB {@code UPDATED} so the EDT launch delegate skips its modal, an IB whose
     *       DB config is genuinely behind (e.g. a restructure the delegate re-detects)
     *       can still pop the "Update then run / Run without update" dialog; while
     *       armed the filter auto-presses its default ("Update then run") button — see
     *       Bitrix 20074.</li>
     *   <li>the D5 code-1003 "debug session already exists" matcher is armed
     *       <em>unconditionally</em> on this debug path (independent of
     *       {@code autoConfirmUpdateDialog}). With {@code restartIfRunning=true} and a
     *       {@code terminate()} that times out, the relaunch can still race a residual
     *       1003 modal; auto-pressing its default ("stop existing and start new")
     *       keeps an unattended call from hanging. Pressing it performs NO DB update,
     *       so it does not undo the {@code updateBeforeLaunch=false} opt-out.</li>
     * </ul>
     * The arm/disarm runs INSIDE the {@code asyncExec} lambda, on the same UI thread
     * the modal blocks, so the auto-press is dispatched by the modal's nested event
     * loop; the MCP worker has already returned, so the server is never hung.
     *
     * <p>Callers pass {@code updateBeforeLaunch} for {@code autoConfirmUpdateDialog}:
     * with {@code updateBeforeLaunch=false} the documented contract is that the
     * platform "may then show that modal" — auto-pressing the UPDATE dialog's default
     * button would silently perform the very DB update the caller disabled, so the
     * UPDATE matcher is NOT armed and that dialog is left for a human. The 1003
     * matcher, which performs no update, stays armed regardless.
     *
     * <p>Package-private (not {@code private}) so the headless unit tests can
     * exercise the synchronous fallback directly.
     *
     * @return {@code null} when the launch was scheduled (or, in a headless test
     *         with no UI thread, completed) successfully; otherwise an error message.
     */
    String performLaunch(ILaunchConfiguration config, boolean autoConfirmUpdateDialog)
    {
        // Workbench-aware probe (rv1 review FIND-2): never creates a display, so
        // a truly headless runtime takes the synchronous fallback below instead
        // of queueing onto an event loop no thread ever pumps.
        Display display = LaunchLifecycleUtils.workbenchDisplayOrNull();
        if (display != null && !display.isDisposed())
        {
            // Fire-and-forget on the UI thread: returns control to the MCP worker
            // immediately so a 1C startup dialog can never block the HTTP socket.
            // The launch outcome can no longer be returned to the caller, so log it.
            display.asyncExec(() -> {
                // Auto-confirm EDT's blocking launch modals for the duration of this
                // single launch only. The "Application update" (D4) modal is pressed
                // only when the caller did NOT opt out of the DB update; the
                // code-1003 "debug session already exists" (D5) modal is ALWAYS
                // auto-confirmed on this debug path (it is independent of the update
                // opt-out). Manual EDT launches outside this window still prompt.
                LaunchUpdateDialogAutoConfirmer.arm(autoConfirmUpdateDialog, true);
                try
                {
                    config.launch(ILaunchManager.DEBUG_MODE, null);
                }
                catch (Exception e)
                {
                    Activator.logError("Error launching debug session (async)", e); //$NON-NLS-1$
                }
                finally
                {
                    LaunchUpdateDialogAutoConfirmer.disarm(autoConfirmUpdateDialog, true);
                }
            });
            return null;
        }
        // No UI thread (headless tests): launch synchronously and surface errors.
        LaunchUpdateDialogAutoConfirmer.arm(autoConfirmUpdateDialog, true);
        try
        {
            config.launch(ILaunchManager.DEBUG_MODE, null);
            return null;
        }
        catch (CoreException e)
        {
            Activator.logError("Error launching debug session", e); //$NON-NLS-1$
            return e.getMessage();
        }
        finally
        {
            LaunchUpdateDialogAutoConfirmer.disarm(autoConfirmUpdateDialog, true);
        }
    }

    /**
     * Builds a diagnostic list of every debug-capable launch configuration known
     * to EDT (runtime client + attach types), so the MCP client can discover
     * what's available when a lookup fails.
     */
    private static JsonArray listAvailableConfigs(ILaunchManager launchManager)
    {
        JsonArray arr = new JsonArray();
        for (ILaunchConfiguration cfg : LaunchConfigUtils.getAllDebugConfigs(launchManager))
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", cfg.getName()); //$NON-NLS-1$
            String typeId = LaunchConfigUtils.getConfigTypeId(cfg);
            obj.addProperty("type", typeId); //$NON-NLS-1$
            obj.addProperty("attach", LaunchConfigUtils.isAttachConfigTypeId(typeId)); //$NON-NLS-1$
            obj.addProperty("project", LaunchConfigUtils.readAttribute(cfg, //$NON-NLS-1$
                LaunchConfigUtils.ATTR_PROJECT_NAME, "")); //$NON-NLS-1$
            obj.addProperty("applicationId", LaunchConfigUtils.readAttribute(cfg, //$NON-NLS-1$
                LaunchConfigUtils.ATTR_APPLICATION_ID, "")); //$NON-NLS-1$
            String alias = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_DEBUG_INFOBASE_ALIAS, ""); //$NON-NLS-1$
            if (!alias.isEmpty())
            {
                obj.addProperty("infobaseAlias", alias); //$NON-NLS-1$
            }
            String url = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_DEBUG_SERVER_URL, ""); //$NON-NLS-1$
            if (!url.isEmpty())
            {
                obj.addProperty("debugServerUrl", url); //$NON-NLS-1$
            }
            arr.add(obj);
        }
        return arr;
    }
}
