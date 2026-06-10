/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.JUnitMarkdownFormatter;
import com.ditrix.edt.mcp.server.utils.JUnitTestResults;
import com.ditrix.edt.mcp.server.utils.JUnitXmlParser;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Tool to run YAXUnit tests for a 1C:Enterprise project.
 *
 * Launches the application with the {@code RunUnitTests} startup parameter,
 * polls until the launch terminates or the polling window expires, then parses
 * the JUnit XML report and returns a Markdown summary. The full Markdown report
 * is also written to {@code report.md} next to {@code junit.xml} so the user
 * can read it directly from disk.
 */
public class RunYaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "run_yaxunit_tests"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 60;
    private static final int POLL_INTERVAL_MS = 1000;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Active launches keyed by stable run id (projectName:applicationId:filterHash). */
    private static final Map<String, ILaunch> ACTIVE_LAUNCHES = new ConcurrentHashMap<>();

    /** Max number of finished-but-unfetched runs retained for the retry handoff. */
    static final int FINISHED_MAX_ENTRIES = 32;

    /** TTL for finished-but-unfetched results — same freshness window as the read cache. */
    static final long FINISHED_TTL_MS = CACHE_TTL_MS;

    /**
     * Finished-but-unfetched launches keyed by run id. When one of OUR launches
     * terminates before the caller fetches the result (the launch listener or the
     * entry purge evicts it from {@link #ACTIVE_LAUNCHES}), the completed report
     * directory is parked here so a retry "with the same arguments" — exactly
     * what the Pending message instructs — returns the finished junit.xml instead
     * of re-running the whole suite. The handoff applies regardless of
     * {@code updateBeforeLaunch}: the parked result IS from a fresh run we
     * launched, so the cache bypass (which exists to force a fresh run after a
     * source edit) does not apply to it. Entries are consumed exactly once and
     * bounded by {@link #FINISHED_MAX_ENTRIES} / {@link #FINISHED_TTL_MS}.
     */
    private static final Map<String, FinishedRun> FINISHED_LAUNCHES = new ConcurrentHashMap<>();

    /** Value record for {@link #FINISHED_LAUNCHES}: where the report lives + when the run finished. */
    static final class FinishedRun
    {
        final Path reportDir;
        final long completedAtMs;

        FinishedRun(Path reportDir, long completedAtMs)
        {
            this.reportDir = reportDir;
            this.completedAtMs = completedAtMs;
        }
    }

    /** Lazily registered listener that evicts terminated launches from {@link #ACTIVE_LAUNCHES}. */
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean(false);

    /** Per-launch counter for the unique debug-mode report directory name. */
    private static final AtomicLong DEBUG_LAUNCH_COUNTER = new AtomicLong(0);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Run YAXUnit tests for a 1C:Enterprise project and return a JUnit Markdown report. " //$NON-NLS-1$
               + "Polls for up to `timeout` seconds, then returns the report or **Pending** " //$NON-NLS-1$
               + "(call again with identical arguments to keep waiting; the launch is not terminated). " //$NON-NLS-1$
               + "Pass `debug=true` to instead launch in DEBUG mode (breakpoints fire) and return at once " //$NON-NLS-1$
               + "so you can call wait_for_break. Requires an existing runtime-client launch configuration " //$NON-NLS-1$
               + "and the YAXUnit extension installed in the infobase. " //$NON-NLS-1$
               + "Full parameters and examples: call get_tool_guide('run_yaxunit_tests')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact runtime-client launch config name (preferred; from list_configurations).") //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name (required if launchConfigurationName is omitted).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications (required if launchConfigurationName is omitted).") //$NON-NLS-1$
            .stringArrayProperty("extensions", "Extension names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("modules", "Module names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("tests", "Test names in Module.Method format (array; a comma-separated string is also accepted).") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("timeout", "Polling window in seconds (default: 60); on expiry returns Pending.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Auto-chain (default: true): force-recompute the project + its extensions, terminate a " //$NON-NLS-1$
                    + "live client and run a silent DB update first, so a freshly edited extension runs " //$NON-NLS-1$
                    + "fresh (not stale). true also BYPASSES the cached junit.xml so the run is always fresh.") //$NON-NLS-1$
            .stringProperty("updateScope", UPDATE_SCOPE_DESCRIPTION) //$NON-NLS-1$
            .booleanProperty("debug", //$NON-NLS-1$
                "Default false: poll and return the report. true: launch in DEBUG mode so breakpoints " //$NON-NLS-1$
                    + "fire, return immediately and call wait_for_break next (ignores timeout).") //$NON-NLS-1$
            .build();
    }

    /**
     * Shared schema doc for the {@code updateScope} parameter (also forwarded by
     * the {@code debug_yaxunit_tests} alias).
     */
    static final String UPDATE_SCOPE_DESCRIPTION =
        "Which projects to rebuild+update before the run: 'all' (configuration + dependent " //$NON-NLS-1$
            + "extensions, default), 'configuration', or 'extension:<ProjectName>' " //$NON-NLS-1$
            + "(comma-separate several). Forces a derived-data recompute so a freshly edited " //$NON-NLS-1$
            + "extension's .cfe is regenerated and loaded into the infobase before the run. " //$NON-NLS-1$
            + "Unknown extension names fail the call (the error lists the available names). " //$NON-NLS-1$
            + "Only applies when updateBeforeLaunch=true."; //$NON-NLS-1$

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        // extensions/modules/tests are declared as arrays but threaded internally as
        // comma-strings (cache key, retry, buildParamsJson). extractArrayArgument accepts
        // BOTH a JSON array and a comma-separated string; re-join to the canonical comma
        // form so the downstream String plumbing is unchanged.
        String extensions = joinList(JsonUtils.extractArrayArgument(params, "extensions")); //$NON-NLS-1$
        String modules = joinList(JsonUtils.extractArrayArgument(params, "modules")); //$NON-NLS-1$
        String tests = joinList(JsonUtils.extractArrayArgument(params, "tests")); //$NON-NLS-1$
        int timeout = JsonUtils.extractIntArgument(params, "timeout", DEFAULT_TIMEOUT); //$NON-NLS-1$
        if (timeout < 1)
        {
            timeout = 1;
        }
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, //$NON-NLS-1$
            "updateBeforeLaunch", true); //$NON-NLS-1$
        String updateScope = JsonUtils.extractStringArgument(params, "updateScope"); //$NON-NLS-1$
        boolean debug = JsonUtils.extractBooleanArgument(params, "debug", false); //$NON-NLS-1$ //$NON-NLS-2$

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

        ensureLaunchListenerRegistered();
        purgeTerminatedLaunches();

        return runTests(configName, projectName, applicationId, extensions, modules, tests,
            timeout, updateBeforeLaunch, updateScope, debug);
    }

    /**
     * Main test execution flow.
     *
     * Non-blocking with state tracking. Behaviour:
     * <ol>
     *   <li>Compute stable runKey from projectName + applicationId + filter.</li>
     *   <li>If a launch is already running for this key — poll up to {@code timeout}s, return result or "Pending".</li>
     *   <li>If one of OUR launches for this key finished before the caller fetched —
     *       return the parked result exactly once, regardless of {@code updateBeforeLaunch}
     *       (see {@link #FINISHED_LAUNCHES}).</li>
     *   <li>If no active launch but a fresh junit.xml exists — return cached result
     *       (only when {@code updateBeforeLaunch=false}).</li>
     *   <li>Otherwise — start a new launch, poll, return result or "Pending".</li>
     * </ol>
     *
     * The temp directory is NEVER deleted in finally — the caller can invoke the tool again to fetch
     * the result. Old runs are cleaned up automatically before starting a new launch.
     */
    private String runTests(String configName, String projectName, String applicationId,
            String extensions, String modules, String tests, int timeout, boolean updateBeforeLaunch,
            String updateScope, boolean debug)
    {
        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            ILaunchConfiguration matchingConfig = LaunchConfigUtils.resolveLaunchConfig(
                    launchManager, configName, projectName, applicationId);
            if (matchingConfig == null)
            {
                boolean hasName = configName != null && !configName.isEmpty();
                return hasName
                    ? ToolResult.error("Launch configuration not found: '" + configName + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Use list_configurations to see what's available.").toJson() //$NON-NLS-1$
                    : buildNoConfigError(launchManager,
                        launchManager.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID),
                        projectName, applicationId);
            }
            if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(matchingConfig)))
            {
                return ToolResult.error("Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' is not a runtime-client config — YAXUnit tests require one.").toJson(); //$NON-NLS-1$
            }

            // Derive effective project/application from the resolved config.
            String effectiveProject = LaunchConfigUtils.readAttribute(matchingConfig,
                LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String effectiveAppId = LaunchConfigUtils.readAttribute(matchingConfig,
                LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
            if (projectName == null || projectName.isEmpty())
            {
                projectName = effectiveProject;
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                applicationId = effectiveAppId;
            }
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' has no project attribute set").toJson(); //$NON-NLS-1$
            }

            String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }

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

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("IApplicationManager service is not available").toJson(); //$NON-NLS-1$
            }

            // A runtime-client launch config may carry no applicationId (it was not
            // bound to an application). Fall back to the project's default application
            // so updateBeforeLaunch has a target and the EDT launch delegate does not
            // pop its blocking "Update infobase before launch?" modal.
            applicationId = LaunchLifecycleUtils.resolveDefaultApplicationId(project, applicationId, appManager);

            if (applicationId != null && !applicationId.isEmpty())
            {
                try
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return ToolResult.error("Application not found: " + applicationId //$NON-NLS-1$
                                + ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
                    }
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Error checking application", e); //$NON-NLS-1$
                    return ToolResult.error("Failed to validate application: " + applicationId //$NON-NLS-1$
                            + " (" + e.getMessage() + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            // DEBUG mode shares the whole setup above (resolve/validate/effective
            // project+app), then spawns a DEBUG launch and returns at once for
            // wait_for_break — no polling, no run-key reuse cache.
            if (debug)
            {
                return launchDebugMode(matchingConfig, project, projectName, applicationId,
                    appManager, launchManager, extensions, modules, tests, updateBeforeLaunch,
                    updateScope);
            }

            // Use the launch config name as the run-key root — stable across
            // (project, applicationId) vs. launchConfigurationName call styles.
            String runKey = matchingConfig.getName() + ":" //$NON-NLS-1$
                    + sha1(safe(extensions) + "|" + safe(modules) + "|" + safe(tests)); //$NON-NLS-1$ //$NON-NLS-2$
            Path reportDir = stableReportDir(runKey);

            // If a launch is already running for this key, just poll it.
            ILaunch existing = ACTIVE_LAUNCHES.get(runKey);
            if (existing != null)
            {
                if (existing.isTerminated())
                {
                    ACTIVE_LAUNCHES.remove(runKey);
                    // We are fetching the result right here — consume any entry the
                    // launch listener parked for this key so a later call with the
                    // same arguments starts a genuinely new run.
                    takeFinishedRun(runKey, System.currentTimeMillis());
                    File junitXml = findJunitXml(reportDir);
                    if (junitXml != null)
                    {
                        return readResults(junitXml);
                    }
                    return ToolResult.error("Previous launch finished but no JUnit XML found in " //$NON-NLS-1$
                            + reportDir + ". Make sure YAXUnit extension is installed.").toJson(); //$NON-NLS-1$
                }
                String pollResult = pollLaunch(existing, reportDir, timeout, runKey);
                if (pollResult != null)
                {
                    return pollResult;
                }
                return buildPendingMessage(reportDir);
            }

            // Finished-but-unfetched handoff. One of OUR launches for this exact run
            // key terminated before the caller could fetch the result: the launch
            // listener (or the entry purge) evicted it from ACTIVE_LAUNCHES, so the
            // isTerminated() recovery branch above is unreachable for it. The parked
            // junit.xml IS the result of a fresh run we launched, so return it exactly
            // once — regardless of updateBeforeLaunch (the cache bypass exists to force
            // a fresh run after a source edit, and this result already came from such a
            // fresh run). The NEXT call with the same arguments starts a new run.
            Path finishedDir = takeFinishedRun(runKey, System.currentTimeMillis());
            if (finishedDir != null)
            {
                File finishedJunit = findJunitXml(finishedDir);
                if (finishedJunit != null)
                {
                    Activator.logInfo("Returning finished-but-unfetched YAXUnit results for runKey=" //$NON-NLS-1$
                        + runKey);
                    return readResults(finishedJunit);
                }
                return ToolResult.error("Previous launch finished but no JUnit XML found in " //$NON-NLS-1$
                        + finishedDir + ". Make sure YAXUnit extension is installed.").toJson(); //$NON-NLS-1$
            }

            // No active launch — return a fresh cached result if available, BUT never
            // when updateBeforeLaunch=true. With updateBeforeLaunch=true the caller is
            // explicitly asking for a fresh run after a possible source edit: returning a
            // cached junit here would skip BOTH the pre-launch recompute+DB sync (Phase 2)
            // and the run itself, silently yielding a stale result for the just-edited code
            // (e.g. a deleted/added test not reflected). The cache stays valid only for
            // updateBeforeLaunch=false (an explicit "just fetch the last result").
            // The non-blocking poll/fetch of an in-flight or just-finished launch goes
            // through the ACTIVE_LAUNCHES path above, not this cache.
            File cached = findJunitXml(reportDir);
            if (!updateBeforeLaunch && cached != null
                && (System.currentTimeMillis() - cached.lastModified()) < CACHE_TTL_MS)
            {
                Activator.logInfo("Returning cached YAXUnit results from " + cached); //$NON-NLS-1$
                return readResults(cached);
            }

            // Phase 1 (quick, JVM-wide): try to reuse an active launch for this runKey.
            ILaunch launch = null;
            synchronized (ACTIVE_LAUNCHES)
            {
                ILaunch concurrent = ACTIVE_LAUNCHES.get(runKey);
                if (concurrent != null && !concurrent.isTerminated())
                {
                    Activator.logInfo("Reusing active YAXUnit launch for runKey=" + runKey); //$NON-NLS-1$
                    launch = concurrent;
                }
                else if (concurrent != null)
                {
                    ACTIVE_LAUNCHES.remove(runKey);
                }
            }

            // Phases 2 + 3 under the per-key lock — this serialises auto-chain
            // and spawn across both YAXUnit tools for the same IB, and closes
            // the narrow window between workingCopy.launch() and
            // registerOwnedLaunch where a concurrent call could otherwise
            // terminate this fresh launch before it's registered. Different
            // (project, applicationId) pairs are unaffected.
            PreLaunchResult preLaunch = null;
            if (launch == null)
            {
                synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
                {
                    // Phase 2: auto-chain (LaunchLifecycleUtils.prepareForFreshLaunch
                    // re-acquires the same monitor — Java synchronized is reentrant).
                    if (updateBeforeLaunch)
                    {
                        int terminateTimeout = LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
                        preLaunch = LaunchLifecycleUtils.prepareForFreshLaunch(launchManager,
                            project, applicationId, appManager, terminateTimeout, updateScope);
                        if (!preLaunch.isOk())
                        {
                            return ToolResult.error("Pre-launch preparation failed: " //$NON-NLS-1$
                                + preLaunch.getError()
                                + "\n\nIf the previous launch is stuck, call `terminate_launch` " //$NON-NLS-1$
                                + "with `force=true` and retry. As a last resort, pass " //$NON-NLS-1$
                                + "`updateBeforeLaunch=false` — but the EDT launch delegate may " //$NON-NLS-1$
                                + "then pop a modal dialog that blocks the MCP call.").toJson(); //$NON-NLS-1$
                        }
                    }

                    // Phase 3: re-check ACTIVE_LAUNCHES under JVM-wide sync (another
                    // thread may have spawned for the same runKey while we waited
                    // on the per-key lock), then either reuse or spawn.
                    synchronized (ACTIVE_LAUNCHES)
                    {
                        ILaunch racer = ACTIVE_LAUNCHES.get(runKey);
                        if (racer != null && !racer.isTerminated())
                        {
                            Activator.logInfo("Reusing YAXUnit launch spawned during auto-chain: runKey=" //$NON-NLS-1$
                                + runKey);
                            launch = racer;
                        }
                        else
                        {
                            // This spawn supersedes any parked finished-but-unfetched
                            // result for the key: the old report dir is deleted below,
                            // so the parked entry must not dangle (a later retry would
                            // otherwise find its junit.xml missing).
                            takeFinishedRun(runKey, System.currentTimeMillis());
                            cleanupTempDir(reportDir);
                            Files.createDirectories(reportDir);
                            Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
                            String paramsJson = buildParamsJson(reportDir.resolve("junit.xml").toString(), //$NON-NLS-1$
                                    extensions, modules, tests);
                            Files.write(paramsFile, paramsJson.getBytes(StandardCharsets.UTF_8));
                            Activator.logInfo("YAXUnit params written to: " + paramsFile); //$NON-NLS-1$

                            ILaunchConfigurationWorkingCopy workingCopy = matchingConfig.getWorkingCopy();
                            String startupOption = "RunUnitTests=" + paramsFile.toString(); //$NON-NLS-1$
                            workingCopy.setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, startupOption);
                            // Stamp the resolved applicationId onto the launch so the spawned
                            // client carries it (an app-less config would otherwise launch with
                            // an empty id), keeping it matchable by the terminate-before-launch
                            // sweep keyed on applicationId.
                            if (applicationId != null && !applicationId.isEmpty())
                            {
                                workingCopy.setAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, applicationId);
                            }

                            Activator.logInfo("Launching YAXUnit tests: config=" + matchingConfig.getName() //$NON-NLS-1$
                                    + ", startup=" + startupOption); //$NON-NLS-1$

                            // Auto-confirm EDT's blocking "Application update" modal
                            // for the duration of this launch only (the dependent
                            // test extension keeps the app in INCREMENTAL_UPDATE_REQUIRED,
                            // which no pre-update durably clears). Manual EDT launches
                            // outside this window still prompt normally.
                            LaunchUpdateDialogAutoConfirmer.arm();
                            try
                            {
                                launch = workingCopy.launch(ILaunchManager.RUN_MODE,
                                    new NullProgressMonitor());
                            }
                            finally
                            {
                                LaunchUpdateDialogAutoConfirmer.disarm();
                            }
                            // Register BEFORE leaving the per-key lock so a concurrent
                            // auto-chain on the same IB sees this launch as owned and
                            // refuses to terminate it.
                            LaunchLifecycleUtils.registerOwnedLaunch(launch);
                            ACTIVE_LAUNCHES.put(runKey, launch);
                        }
                    }
                }
            }

            String pollResult = pollLaunch(launch, reportDir, timeout, runKey);
            if (pollResult != null)
            {
                return prependPreLaunchInfo(preLaunch, pollResult);
            }

            // Polling window expired — return Pending without terminating the launch.
            return prependPreLaunchInfo(preLaunch, buildPendingMessage(reportDir));
        }
        catch (CoreException e)
        {
            Activator.logError("Error running YAXUnit tests", e); //$NON-NLS-1$
            return ToolResult.error("Launch failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Test execution was interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error running YAXUnit tests", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * DEBUG-mode launch (shared by {@code debug=true} and the deprecated
     * {@code debug_yaxunit_tests} alias): spawns the test run in DEBUG mode so
     * breakpoints fire, then returns a Markdown launch handle immediately. Unlike
     * the polling path it does NOT wait for {@code junit.xml}; the caller is
     * expected to call {@code wait_for_break} next. The report is still written to
     * {@code reportDir} once the run finishes.
     */
    private String launchDebugMode(ILaunchConfiguration matchingConfig, IProject project,
            String projectName, String applicationId, IApplicationManager appManager,
            ILaunchManager launchManager, String extensions, String modules, String tests,
            boolean updateBeforeLaunch, String updateScope) throws IOException, CoreException
    {
        // Native path separators: YAXUnit builds file:// URIs and breaks on forward slashes on Windows.
        Path reportDir = Paths.get(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
            "edt-mcp-yaxunit-debug", projectName + "-" + System.currentTimeMillis() //$NON-NLS-1$ //$NON-NLS-2$
                + "-" + DEBUG_LAUNCH_COUNTER.getAndIncrement()); //$NON-NLS-1$
        Files.createDirectories(reportDir);
        Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
        Path junitFile = reportDir.resolve("junit.xml"); //$NON-NLS-1$
        Files.write(paramsFile,
            buildParamsJson(junitFile.toString(), extensions, modules, tests).getBytes(StandardCharsets.UTF_8));

        // Suspend listener must be live before the launch starts producing events.
        DebugSessionRegistry.get().ensureListenerRegistered();

        PreLaunchResult preLaunch = null;
        synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
        {
            if (updateBeforeLaunch)
            {
                int terminateTimeout = LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
                preLaunch = LaunchLifecycleUtils.prepareForFreshLaunch(launchManager, project,
                    applicationId, appManager, terminateTimeout, updateScope);
                if (!preLaunch.isOk())
                {
                    return ToolResult.error("Pre-launch preparation failed: " + preLaunch.getError() //$NON-NLS-1$
                        + "\n\nIf the previous launch is stuck, call `terminate_launch` with `force=true` " //$NON-NLS-1$
                        + "and retry. As a last resort, pass `updateBeforeLaunch=false` — but the EDT launch " //$NON-NLS-1$
                        + "delegate may then pop a modal dialog that blocks the MCP call.").toJson(); //$NON-NLS-1$
                }
            }

            // Fresh-run guarantee (D7b, Bitrix 20092): a YAXUnit debug run is ALWAYS a
            // new session — detect and non-interactively terminate any existing live
            // CLIENT session of this application BEFORE workingCopy.launch, so EDT's
            // launch delegate never raises its blocking code-1003 "Debug session
            // already exists" modal. This covers BOTH the ILaunchManager view and
            // EDT's debug target manager (a UI-started "Debug As" session lives ONLY
            // there: prepareForFreshLaunch's sweep keys on getApplicationIdFor and
            // never matches it). The detect is CLIENT-typed-thread-discriminated, so
            // a debug-mode standalone server session is never matched and never
            // terminated (Bitrix 20074). applicationId here is already the
            // delegate-resolved id (ATTR_APPLICATION_ID else project default — see
            // resolveDefaultApplicationId above) and is stamped onto the working copy
            // below, so it is exactly the key the delegate's 1003 check uses.
            if (LaunchLifecycleUtils.ensureNoExistingClientSession(project, applicationId))
            {
                Activator.logInfo("YAXUnit debug: terminated an existing client session before " //$NON-NLS-1$
                    + "the fresh debug launch: applicationId=" + applicationId); //$NON-NLS-1$
            }

            ILaunchConfigurationWorkingCopy workingCopy = matchingConfig.getWorkingCopy();
            String startupOption = "RunUnitTests=" + paramsFile.toString(); //$NON-NLS-1$
            workingCopy.setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, startupOption);
            // Stamp the resolved applicationId so the spawned ILaunch carries it:
            // DebugSessionRegistry keys the suspend snapshot by this id and the
            // handle below hands the SAME id to wait_for_break.
            if (applicationId != null && !applicationId.isEmpty())
            {
                workingCopy.setAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, applicationId);
            }
            Activator.logInfo("Launching YAXUnit tests in DEBUG mode: config=" + matchingConfig.getName() //$NON-NLS-1$
                + ", startup=" + startupOption); //$NON-NLS-1$
            // Auto-confirm EDT's blocking launch modals for the launch window only:
            // the "Application update" matcher (as before, unconditional on this
            // path), PLUS the code-1003 "Debug session already exists" matcher as the
            // race net behind ensureNoExistingClientSession — if a session slips in
            // (or a terminate times out) between the sweep above and the delegate's
            // check, the armed confirmer presses "Keep existing and start new" so an
            // unattended call never hangs on the modal (Bitrix 20092).
            LaunchUpdateDialogAutoConfirmer.arm(true, true);
            try
            {
                ILaunch spawned = workingCopy.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
                LaunchLifecycleUtils.registerOwnedLaunch(spawned);
            }
            catch (CoreException ex)
            {
                Activator.logError("Failed to launch YAXUnit in debug mode", ex); //$NON-NLS-1$
                return ToolResult.error("Launch failed: " + ex.getMessage()).toJson(); //$NON-NLS-1$
            }
            finally
            {
                LaunchUpdateDialogAutoConfirmer.disarm(true, true);
            }
        }
        return buildDebugLaunchMarkdown(matchingConfig.getName(), projectName, applicationId,
            reportDir, junitFile, preLaunch);
    }

    /** Markdown launch handle returned by DEBUG mode — readable, with the wait_for_break next step. */
    private static String buildDebugLaunchMarkdown(String configName, String projectName,
            String applicationId, Path reportDir, Path junitFile, PreLaunchResult preLaunch)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# YAXUnit Debug Launch\n\n"); //$NON-NLS-1$
        sb.append("Debug launch **queued** for `").append(configName).append("`.\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- **applicationId:** `").append(applicationId == null ? "" : applicationId).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("- **projectName:** `").append(projectName).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- **reportDir:** `").append(reportDir).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- **junitXml:** `").append(junitFile).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (preLaunch != null && preLaunch.getTerminatedCount() > 0)
        {
            sb.append("- **preLaunch:** ").append(preLaunch.summary()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n**Next step:** call `wait_for_break` (the applicationId is auto-resolved when this is " //$NON-NLS-1$
            + "the only active debug launch) to block until a breakpoint is hit, then `get_variables` / " //$NON-NLS-1$
            + "`evaluate_expression` / `step` / `resume`. Set breakpoints with `set_breakpoint` BEFORE the " //$NON-NLS-1$
            + "test reaches them. The `junit.xml` report is still written to `reportDir` after the run.\n"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Polls a launch for up to {@code timeoutSec} seconds. Returns the parsed Markdown report
     * if the launch finished, or {@code null} if still running (caller should return a Pending message).
     */
    private String pollLaunch(ILaunch launch, Path reportDir, int timeoutSec, String runKey)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
        while (!launch.isTerminated())
        {
            if (System.currentTimeMillis() > deadline)
            {
                return null;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        ACTIVE_LAUNCHES.remove(runKey);
        // The launch listener may have parked this run in the finished-handoff map
        // the moment it terminated; the result is being fetched right here, so
        // consume the parked entry to keep the "returned exactly once" contract.
        takeFinishedRun(runKey, System.currentTimeMillis());
        Activator.logInfo("YAXUnit tests completed for " + runKey); //$NON-NLS-1$

        File junitXml = findJunitXml(reportDir);
        if (junitXml == null)
        {
            return ToolResult.error("No JUnit XML report found in " + reportDir //$NON-NLS-1$
                    + ". Make sure YAXUnit extension is installed in the infobase " //$NON-NLS-1$
                    + "and test configuration is correct.").toJson(); //$NON-NLS-1$
        }

        return readResults(junitXml);
    }

    /**
     * Parses the JUnit XML, formats it as Markdown and writes report.md next to junit.xml so
     * that the user can open the report manually from disk. Returns the Markdown content for
     * the MCP response, with an extra footer pointing at the on-disk file.
     */
    private String readResults(File junitXml)
    {
        try
        {
            JUnitTestResults results = JUnitXmlParser.parse(junitXml);
            String markdown = JUnitMarkdownFormatter.format(results);

            Path reportFile = junitXml.toPath().resolveSibling("report.md"); //$NON-NLS-1$
            boolean reportWritten = false;
            try
            {
                Files.write(reportFile, markdown.getBytes(StandardCharsets.UTF_8));
                reportWritten = Files.exists(reportFile);
            }
            catch (IOException io)
            {
                Activator.logError("Failed to write Markdown report to " + reportFile, io); //$NON-NLS-1$
            }

            if (reportWritten)
            {
                return markdown + "\n---\n*Full report saved to:* `" + reportFile + "`\n"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return markdown;
        }
        catch (Exception e)
        {
            Activator.logError("Error parsing JUnit XML: " + junitXml, e); //$NON-NLS-1$
            return ToolResult.error("Failed to parse test results: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Lazily registers a launch listener that evicts terminated launches from
     * {@link #ACTIVE_LAUNCHES}, preventing memory leaks for launches that the
     * tool never observes itself (for example because the caller never polls
     * again after a Pending response and the launch then crashes or finishes).
     */
    private static void ensureLaunchListenerRegistered()
    {
        if (LISTENER_REGISTERED.compareAndSet(false, true))
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null)
            {
                LISTENER_REGISTERED.set(false);
                return;
            }
            ILaunchManager launchManager = debugPlugin.getLaunchManager();
            if (launchManager == null)
            {
                LISTENER_REGISTERED.set(false);
                return;
            }
            launchManager.addLaunchListener(new ILaunchListener()
            {
                @Override
                public void launchAdded(ILaunch launch)
                {
                    // ignored
                }

                @Override
                public void launchChanged(ILaunch launch)
                {
                    if (launch != null && launch.isTerminated())
                    {
                        evict(launch);
                    }
                }

                @Override
                public void launchRemoved(ILaunch launch)
                {
                    evict(launch);
                }
            });
            Activator.logInfo("YAXUnit launch listener registered"); //$NON-NLS-1$
        }
    }

    /**
     * Removes the given launch from {@link #ACTIVE_LAUNCHES} regardless of which
     * key it lives under. A terminated launch of OURS may hold a
     * finished-but-unfetched result — its report directory is parked in
     * {@link #FINISHED_LAUNCHES} for the retry handoff instead of being silently
     * dropped (the Pending contract promises a retry "with the same arguments"
     * fetches the result, never re-runs the suite).
     */
    private static void evict(ILaunch launch)
    {
        if (launch == null)
        {
            return;
        }
        long now = System.currentTimeMillis();
        ACTIVE_LAUNCHES.entrySet().removeIf(e -> {
            if (e.getValue() != launch)
            {
                return false;
            }
            recordFinishedRunIfReportExists(e.getKey(), now);
            return true;
        });
        LaunchLifecycleUtils.unregisterOwnedLaunch(launch);
    }

    /**
     * Defensive sweep that drops any terminated launches still lingering in the
     * map, parking each finished run for the retry handoff (see {@link #evict}).
     */
    private static void purgeTerminatedLaunches()
    {
        long now = System.currentTimeMillis();
        ACTIVE_LAUNCHES.entrySet().removeIf(e -> {
            ILaunch l = e.getValue();
            if (l == null)
            {
                return true;
            }
            if (!l.isTerminated())
            {
                return false;
            }
            recordFinishedRunIfReportExists(e.getKey(), now);
            return true;
        });
    }

    /**
     * Parks the run for the retry handoff ONLY when its report directory actually
     * holds a JUnit XML: a launch killed mid-run (e.g. via {@code terminate_launch})
     * produced no result, and parking it would make the next retry return a
     * spurious "no JUnit XML" error instead of starting a fresh run.
     */
    private static void recordFinishedRunIfReportExists(String runKey, long nowMs)
    {
        Path reportDir = stableReportDir(runKey);
        if (findJunitXml(reportDir) != null)
        {
            recordFinishedRun(runKey, reportDir, nowMs);
        }
    }

    /**
     * Parks a finished-but-unfetched run for the retry handoff. Prunes expired
     * entries and evicts the oldest beyond {@link #FINISHED_MAX_ENTRIES} so the
     * map cannot grow without bound.
     */
    static void recordFinishedRun(String runKey, Path reportDir, long nowMs)
    {
        if (runKey == null || reportDir == null)
        {
            return;
        }
        FINISHED_LAUNCHES.put(runKey, new FinishedRun(reportDir, nowMs));
        pruneFinishedRuns(nowMs);
    }

    /**
     * Consumes (returns at most ONCE) the parked report directory for the given
     * run key, or {@code null} when none exists or the entry exceeded
     * {@link #FINISHED_TTL_MS}. After a hit, the next call with the same key
     * starts a genuinely new run — the handoff never turns into a stale cache.
     */
    static Path takeFinishedRun(String runKey, long nowMs)
    {
        if (runKey == null)
        {
            return null;
        }
        FinishedRun finished = FINISHED_LAUNCHES.remove(runKey);
        if (finished == null || nowMs - finished.completedAtMs > FINISHED_TTL_MS)
        {
            return null;
        }
        return finished.reportDir;
    }

    /** Drops expired entries, then evicts the oldest entries beyond the size bound. */
    static void pruneFinishedRuns(long nowMs)
    {
        FINISHED_LAUNCHES.entrySet()
            .removeIf(e -> nowMs - e.getValue().completedAtMs > FINISHED_TTL_MS);
        while (FINISHED_LAUNCHES.size() > FINISHED_MAX_ENTRIES)
        {
            String oldestKey = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<String, FinishedRun> e : FINISHED_LAUNCHES.entrySet())
            {
                if (e.getValue().completedAtMs < oldestTs)
                {
                    oldestTs = e.getValue().completedAtMs;
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey == null)
            {
                return;
            }
            FINISHED_LAUNCHES.remove(oldestKey);
        }
    }

    /** Test hook: clears the finished-run handoff map (static-state isolation). */
    static void clearFinishedRunsForTest()
    {
        FINISHED_LAUNCHES.clear();
    }

    /** Test hook: current number of parked finished runs. */
    static int finishedRunCountForTest()
    {
        return FINISHED_LAUNCHES.size();
    }

    /**
     * Builds a Pending message that instructs the caller to invoke the tool again with
     * identical arguments to fetch the result.
     */
    private String buildPendingMessage(Path reportDir)
    {
        return "**Pending:** YAXUnit tests are still running.\n\n" //$NON-NLS-1$
                + "Report directory: `" + reportDir + "`\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "Call `run_yaxunit_tests` again with the same arguments to wait further " //$NON-NLS-1$
                + "and fetch the JUnit XML once the launch completes.\n"; //$NON-NLS-1$
    }

    /**
     * Prepends a one-line pre-launch summary to the given report, but only when
     * the auto-chain actually terminated a live launch — a no-op chain is silent
     * to avoid cluttering reports.
     */
    private static String prependPreLaunchInfo(PreLaunchResult preLaunch, String report)
    {
        if (preLaunch == null || preLaunch.getTerminatedCount() == 0)
        {
            return report;
        }
        return "> **Pre-launch:** " + preLaunch.summary() + "\n\n" + report; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns a stable directory under the system temp folder for the given run key.
     * Static so the launch-listener eviction path can derive the report directory
     * of a finished run from its run key alone.
     */
    private static Path stableReportDir(String runKey)
    {
        String safeKey = runKey.replaceAll("[^a-zA-Z0-9_.-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        // Always preserve a unique hash suffix so different runs can never collide into the same dir.
        String uniqueSuffix = sha1Full(runKey);
        int maxSafeKeyLength = Math.max(0, 80 - uniqueSuffix.length() - 1);
        if (safeKey.length() > maxSafeKeyLength)
        {
            safeKey = safeKey.substring(0, maxSafeKeyLength);
        }
        String dirName = safeKey.isEmpty() ? uniqueSuffix : safeKey + "_" + uniqueSuffix; //$NON-NLS-1$
        return Paths.get(System.getProperty("java.io.tmpdir"), "edt-mcp-yaxunit", dirName); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Computes a full hex SHA-1 hash for values that must remain unique after truncation.
     */
    private static String sha1Full(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest)
            {
                hex.append(String.format("%02x", b)); //$NON-NLS-1$
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Computes a short hex SHA-1 hash for filter parts so the runKey is bounded.
     */
    private String sha1(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++)
            {
                hex.append(String.format("%02x", digest[i])); //$NON-NLS-1$
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(input.hashCode());
        }
    }

    private String safe(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }

    /**
     * Builds the xUnitParams.json content.
     */
    private String buildParamsJson(String reportPath, String extensions, String modules, String tests)
    {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reportPath", reportPath); //$NON-NLS-1$
        params.put("reportFormat", "jUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("closeAfterTests", true); //$NON-NLS-1$

        Map<String, Object> filter = new LinkedHashMap<>();
        boolean hasFilter = false;

        if (extensions != null && !extensions.isEmpty())
        {
            filter.put("extensions", splitToList(extensions)); //$NON-NLS-1$
            hasFilter = true;
        }

        if (modules != null && !modules.isEmpty())
        {
            filter.put("modules", splitToList(modules)); //$NON-NLS-1$
            hasFilter = true;
        }

        if (tests != null && !tests.isEmpty())
        {
            filter.put("tests", splitToList(tests)); //$NON-NLS-1$
            hasFilter = true;
        }

        if (hasFilter)
        {
            params.put("filter", filter); //$NON-NLS-1$
        }

        return GsonProvider.toJson(params);
    }

    /**
     * Splits a comma-separated string into a list.
     */
    private List<String> splitToList(String value)
    {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) //$NON-NLS-1$
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
            {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Joins a list-valued argument back to the canonical comma-separated string used
     * internally (filter, cache key, retry). Returns {@code null} when the list is
     * null/empty so the existing "no filter" branches keep working unchanged.
     */
    private static String joinList(List<String> values)
    {
        return (values == null || values.isEmpty()) ? null : String.join(",", values); //$NON-NLS-1$
    }

    /**
     * Builds an error message when no launch configuration is found.
     */
    private String buildNoConfigError(ILaunchManager launchManager,
            ILaunchConfigurationType configType, String projectName, String applicationId)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("No launch configuration found for project '"); //$NON-NLS-1$
        sb.append(projectName);
        sb.append("' and application '"); //$NON-NLS-1$
        sb.append(applicationId);
        sb.append("'.\n\n"); //$NON-NLS-1$
        sb.append("Create a launch configuration in EDT first (Run > Run Configurations > 1C:Enterprise Runtime Client).\n\n"); //$NON-NLS-1$

        ILaunchConfiguration[] allConfigs = LaunchConfigUtils.getAllRuntimeClientConfigs(launchManager, configType);
        if (allConfigs.length > 0)
        {
            sb.append("Available launch configurations:\n\n"); //$NON-NLS-1$
            sb.append("| Name | Project | Application ID |\n"); //$NON-NLS-1$
            sb.append("|------|---------|----------------|\n"); //$NON-NLS-1$
            for (ILaunchConfiguration config : allConfigs)
            {
                sb.append("| ").append(config.getName()); //$NON-NLS-1$
                sb.append(" | ").append(LaunchConfigUtils.readAttribute(config, LaunchConfigUtils.ATTR_PROJECT_NAME, "")); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(" | ").append(LaunchConfigUtils.readAttribute(config, LaunchConfigUtils.ATTR_APPLICATION_ID, "")); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(" |\n"); //$NON-NLS-1$
            }
        }

        return ToolResult.error(sb.toString()).toJson();
    }

    /**
     * Finds the JUnit XML report file in the temp directory.
     */
    private static File findJunitXml(Path tempDir)
    {
        if (tempDir == null || !Files.exists(tempDir))
        {
            return null;
        }

        String[] candidates = {"junit.xml", "report.xml", "test-report.xml"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String name : candidates)
        {
            File f = tempDir.resolve(name).toFile();
            if (f.exists() && f.length() > 0)
            {
                return f;
            }
        }

        File[] xmlFiles = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".xml")); //$NON-NLS-1$
        if (xmlFiles != null && xmlFiles.length > 0)
        {
            return xmlFiles[0];
        }

        return null;
    }

    /**
     * Recursively deletes a temp directory if it exists. Silent if missing.
     */
    private void cleanupTempDir(Path tempDir)
    {
        if (tempDir == null || !Files.exists(tempDir))
        {
            return;
        }
        // try-with-resources releases the file-system handle held by Files.walk's stream;
        // on Windows, leaving it open can prevent subsequent deletions of the same path.
        try (java.util.stream.Stream<Path> stream = Files.walk(tempDir))
        {
            stream.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try
                    {
                        Files.delete(p);
                    }
                    catch (IOException ex)
                    {
                        Activator.logError("Failed to delete " + p, ex); //$NON-NLS-1$
                    }
                });
        }
        catch (IOException e)
        {
            Activator.logError("Failed to cleanup temp directory: " + tempDir, e); //$NON-NLS-1$
        }
    }
}
