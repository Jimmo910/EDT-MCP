/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Shared helpers for the "prepare for a fresh launch" sequence used by tools
 * that spawn 1С clients via {@code workingCopy.launch()} (YAXUnit run/debug
 * tools, etc.).
 *
 * <p>The sequence is needed because EDT's launch delegate brings up its
 * interactive "Update database before launch?" dialog whenever the
 * configuration has unsynced changes, and that dialog blocks the MCP call
 * indefinitely — especially if a 1С client is already holding the infobase in
 * exclusive use, in which case even pressing OK fails on the lock.
 *
 * <p>By terminating any live launch of the target configuration first and
 * then running {@link IApplicationManager#update} programmatically, we leave
 * the IB in {@link ApplicationUpdateState#UPDATED} state — at which point the
 * launch delegate skips the dialog entirely.
 */
public final class LaunchLifecycleUtils
{
    /**
     * Per-{@code project + applicationId} locks that serialise the auto-chain
     * (terminate-and-update) across MCP tools. Without this, two concurrent
     * MCP calls targeting the same IB could race on {@code terminateAndWait}
     * and {@code appManager.update()}. Keys are interned tuples and never
     * removed — the set of (project, applicationId) pairs is finite in
     * practice (one per EDT launch configuration).
     */
    private static final ConcurrentMap<String, Object> KEY_LOCKS = new ConcurrentHashMap<>();

    /**
     * Set of {@link ILaunch} instances that any MCP tool currently owns via
     * the auto-chain. The auto-chain will refuse to terminate any launch in
     * this set — instead it returns an error suggesting the caller wait or
     * call {@code terminate_launch} explicitly. Both {@code run_yaxunit_tests}
     * and {@code debug_yaxunit_tests} register their spawned launches here so
     * concurrent calls to either tool against the same IB protect each other.
     *
     * <p>Identity-equals is intentional: Eclipse {@code Launch} (the default
     * {@link ILaunch} implementation) inherits {@code Object.equals}, and we
     * register exactly the instance returned by {@code workingCopy.launch()}.
     * Terminated entries are pruned lazily inside {@link #prepareForFreshLaunch}.
     */
    private static final Set<ILaunch> OWNED_LAUNCHES = ConcurrentHashMap.newKeySet();

    private LaunchLifecycleUtils()
    {
        // Utility class
    }

    /**
     * Registers a launch as owned by the auto-chain. After this call,
     * {@link #prepareForFreshLaunch} will refuse to terminate it — pass the
     * launch through {@code terminate_launch} explicitly to stop it.
     *
     * <p>Callers must invoke this with the exact {@link ILaunch} reference
     * returned by {@code workingCopy.launch()} so identity-equals lookups
     * inside the auto-chain work.
     */
    public static void registerOwnedLaunch(ILaunch launch)
    {
        if (launch != null)
        {
            OWNED_LAUNCHES.add(launch);
        }
    }

    /**
     * Removes a launch from the owned registry. Optional — terminated launches
     * are pruned automatically on the next {@link #prepareForFreshLaunch} call.
     */
    public static void unregisterOwnedLaunch(ILaunch launch)
    {
        if (launch != null)
        {
            OWNED_LAUNCHES.remove(launch);
        }
    }

    /**
     * Returns the monitor object used to serialise {@link #prepareForFreshLaunch}
     * for the given {@code project + applicationId} pair. Callers that also
     * need the same lock around their own pre-launch steps (e.g. updating a
     * working copy) can synchronise on the returned object.
     *
     * <p>The internal key uses a NUL ({@code \u0000}) separator because
     * Eclipse project names may contain spaces and other printable characters,
     * which would otherwise allow keys to collide (e.g. {@code project="My",
     * appId="Project x"} vs {@code project="My Project", appId="x"}).
     */
    public static Object lockFor(String projectName, String applicationId)
    {
        String key = (projectName != null ? projectName : "") //$NON-NLS-1$
            + "\u0000" + (applicationId != null ? applicationId : ""); //$NON-NLS-1$ //$NON-NLS-2$
        return KEY_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    /**
     * Result of {@link #prepareForFreshLaunch}. Either {@code ok=true} (caller
     * may proceed with the launch) or {@code ok=false} with a populated
     * {@link #error} (caller must abort).
     */
    public static final class PreLaunchResult
    {
        private final boolean ok;
        private final int terminatedCount;
        private final String error;

        private PreLaunchResult(boolean ok, int terminatedCount, String error)
        {
            this.ok = ok;
            this.terminatedCount = terminatedCount;
            this.error = error;
        }

        public boolean isOk()
        {
            return ok;
        }

        public int getTerminatedCount()
        {
            return terminatedCount;
        }

        public String getError()
        {
            return error;
        }

        /**
         * Returns a single-line human-readable summary, suitable for inclusion in
         * a Markdown report or a JSON {@code preLaunch} field.
         */
        public String summary()
        {
            if (!ok)
            {
                return "failed: " + error; //$NON-NLS-1$
            }
            if (terminatedCount == 0)
            {
                return "no-op (no live launch held the lock; DB ready)"; //$NON-NLS-1$
            }
            return "terminated " + terminatedCount //$NON-NLS-1$
                + " live launch" + (terminatedCount == 1 ? "" : "es") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "; DB ready"; //$NON-NLS-1$
        }
    }

    /**
     * Polls until {@link ILaunch#isTerminated} or the timeout elapses.
     *
     * @return {@code true} if the launch is terminated after the call
     */
    public static boolean waitForTerminated(ILaunch launch, long maxMillis)
    {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline)
        {
            if (launch.isTerminated())
            {
                return true;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return launch.isTerminated();
            }
        }
        return launch.isTerminated();
    }

    /**
     * Politely terminates the given launch and waits up to {@code timeoutSeconds}
     * for it to complete. Does NOT escalate to {@code IProcess.terminate()};
     * callers that need force-kill semantics should use {@code terminate_launch}
     * directly.
     *
     * @return {@code true} if the launch is terminated after the call
     */
    public static boolean terminateAndWait(ILaunch launch, int timeoutSeconds)
    {
        if (launch == null)
        {
            return true;
        }
        if (launch.isTerminated())
        {
            return true;
        }
        try
        {
            launch.terminate();
        }
        catch (DebugException e)
        {
            Activator.logError("Error initiating launch termination", e); //$NON-NLS-1$
            return false;
        }
        return waitForTerminated(launch, Math.max(1, timeoutSeconds) * 1000L);
    }

    /**
     * Waits for the incremental build and derived-data computations of the
     * launch's project <em>and every extension project that depends on it</em>
     * to settle before the pre-launch DB update runs.
     *
     * <p>This closes the race behind bug #19925: after a test module inside an
     * <strong>extension</strong> ({@code .cfe}) is edited, EDT schedules an
     * asynchronous incremental rebuild of that extension. The rebuild lives in a
     * <em>separate</em> {@link IProject} from the launch's configuration project,
     * so {@code ProjectStateChecker} (which only inspects the launch project) and
     * {@link #updateApplicationIfNeeded} (which only reads the application's
     * update state) both see "ready" while the extension is still being rebuilt
     * and exported. The pre-launch update then either no-ops or pushes the stale
     * {@code .cfe} into the infobase, so the first test run executes the old
     * extension and a freshly added test is silently missing — only a second run
     * (after the rebuild settled) picks it up.
     *
     * <p>By joining the workspace-wide build job families (which cover the
     * extension's incremental build regardless of which project owns it) and then
     * waiting on the derived-data managers of the launch project plus its
     * dependent extension projects, we guarantee the rebuilt {@code .cfe} is on
     * disk before {@link #updateApplicationIfNeeded} runs, so the update pushes
     * the current extension into the infobase.
     *
     * <p>Reuses {@link BuildUtils} — the same build/derived-data wait the
     * metadata-persist path uses — rather than inventing a new mechanism.
     *
     * <p>Thin wrapper over {@link #recomputeAndSettle(Collection)} for the full
     * launch-project-plus-extensions scope, preserved so existing callers/tests
     * keep working. Prefer {@link #recomputeAndSettle(Collection)} +
     * {@link #resolveUpdateScope(IProject, String)} for a narrowable scope.
     *
     * @param launchProject the configuration project the launch targets
     */
    public static void waitForLaunchBuildSettled(IProject launchProject)
    {
        if (launchProject == null || !launchProject.exists() || !launchProject.isOpen())
        {
            return;
        }
        recomputeAndSettle(collectLaunchAndExtensionProjects(launchProject));
    }

    /**
     * Forces a derived-data recompute of the given projects and then waits for the
     * workspace build and per-project derived data to settle, so a freshly edited
     * <strong>extension</strong> ({@code .cfe}) is regenerated and exported to disk
     * before the pre-launch DB update reads the application's update state.
     *
     * <p>This is the FORCE lever bug #19925 needs. The previous pre-launch chain
     * only <em>waited</em> for derived data, but {@code waitAllComputations(...)}
     * returns immediately when nothing is scheduled — so a stale extension
     * {@code .cfe} was never regenerated and {@code appManager.update(...)} simply
     * consumed the existing (stale) export artifact. {@link IDerivedDataManager#recomputeAll()}
     * (exactly what {@code refresh_model} / {@code RefreshModelTool} uses) forces the
     * extension's model — and thus its {@code .cfe} — to be rebuilt before the update.
     *
     * <p>Sequence (mirrors {@code RefreshModelTool.refreshModel}'s recompute+settle
     * site: resolve {@code IDtProject} → {@code IDerivedDataManager} via the same
     * provider, then {@code recomputeAll()} followed by {@link BuildUtils} draining):
     * <ol>
     *   <li>schedule {@code recomputeAll()} for EVERY project first (so all rebuilds
     *       are queued before we start blocking on any of them);</li>
     *   <li>drain the workspace-wide build job families once via
     *       {@link BuildUtils#waitForBuildJobs};</li>
     *   <li>wait on each project's derived data via {@link BuildUtils#waitForDerivedData}.</li>
     * </ol>
     *
     * <p>Null-safe and defensive, matching {@link BuildUtils#waitForDerivedData}:
     * a {@code null}/closed project, or unavailable EDT services
     * ({@code IDtProjectManager}, {@code IDerivedDataManagerProvider}, the per-project
     * {@code IDtProject} or {@code IDerivedDataManager}), make the recompute a per-project
     * no-op. Nothing here is allowed to throw into the launch hot path — failures are
     * logged and the loop continues.
     *
     * @param projects projects to force-recompute and settle (may be {@code null} or
     *            contain {@code null}/closed entries — all skipped)
     */
    public static void recomputeAndSettle(Collection<IProject> projects)
    {
        if (projects == null || projects.isEmpty())
        {
            return;
        }

        // Phase 1: schedule a forced recompute for every open project up front,
        // so all extension rebuilds are queued before we start blocking. This is
        // the same provider/manager access pattern RefreshModelTool uses.
        for (IProject project : projects)
        {
            if (project == null || !project.exists() || !project.isOpen())
            {
                continue;
            }
            try
            {
                if (Activator.getDefault() == null)
                {
                    continue;
                }
                IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
                if (dtProjectManager == null)
                {
                    continue;
                }
                IDtProject dtProject = dtProjectManager.getDtProject(project);
                if (dtProject == null)
                {
                    continue;
                }
                IDerivedDataManagerProvider ddProvider =
                    Activator.getDefault().getDerivedDataManagerProvider();
                if (ddProvider == null)
                {
                    continue;
                }
                IDerivedDataManager ddManager = ddProvider.get(dtProject);
                if (ddManager == null)
                {
                    continue;
                }
                Activator.logInfo("Pre-launch: forcing derived-data recompute for project: " //$NON-NLS-1$
                    + project.getName());
                ddManager.recomputeAll();
            }
            catch (RuntimeException e)
            {
                // A recompute failure must never abort the launch — log and move on.
                Activator.logError("Error forcing derived-data recompute for " //$NON-NLS-1$
                    + project.getName(), e);
            }
        }

        // Phase 2: drain the workspace-wide build job families once. This is not
        // project-scoped, so it covers the extension's incremental rebuild
        // regardless of which project owns it.
        BuildUtils.waitForBuildJobs(new NullProgressMonitor());

        // Phase 3: wait on each project's derived data so the recomputed model
        // (and the regenerated .cfe) is fully exported before the DB update runs.
        for (IProject project : projects)
        {
            if (project == null || !project.exists() || !project.isOpen())
            {
                continue;
            }
            BuildUtils.waitForDerivedData(project);
        }
    }

    /**
     * Resolves which projects the pre-launch recompute+update should cover, from
     * the optional {@code updateScope} tool parameter.
     * <ul>
     *   <li>{@code null} / empty / {@code "all"} (case-insensitive) → the full list
     *       from {@link #collectLaunchAndExtensionProjects(IProject)} (launch project
     *       first, then its dependent extensions);</li>
     *   <li>{@code "configuration"} (case-insensitive) → just {@code [launchProject]};</li>
     *   <li>{@code "extension:<Name>"} or a comma-separated list of such tokens →
     *       {@code launchProject} PLUS only the dependent extension projects whose
     *       {@link IExtensionProject#getProject()} name equals a requested
     *       {@code <Name>}. The launch (configuration) project is ALWAYS included,
     *       because an extension cannot reach the infobase without its parent
     *       configuration present. Unknown names are ignored (the configuration is
     *       still recomputed); callers may note them.</li>
     * </ul>
     *
     * <p>Null-safe: a {@code null} {@code launchProject} yields an empty list.
     * The launch (configuration) project is always first in the returned list.
     *
     * @param launchProject the configuration project the launch targets
     * @param updateScope the raw {@code updateScope} parameter value (may be {@code null})
     * @return ordered, de-duplicated project list (configuration first)
     */
    public static List<IProject> resolveUpdateScope(IProject launchProject, String updateScope)
    {
        if (launchProject == null)
        {
            return new ArrayList<>();
        }

        String scope = updateScope != null ? updateScope.trim() : ""; //$NON-NLS-1$
        if (scope.isEmpty() || "all".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            return collectLaunchAndExtensionProjects(launchProject);
        }
        if ("configuration".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            List<IProject> only = new ArrayList<>();
            only.add(launchProject);
            return only;
        }

        // "extension:<Name>" tokens (comma-separated). Collect the requested
        // extension names case-sensitively (project names are case-sensitive on
        // Linux), then keep only the dependent extensions whose project name
        // matches. The configuration project is always included first.
        Set<String> requested = new LinkedHashSet<>();
        for (String token : scope.split(",")) //$NON-NLS-1$
        {
            String trimmed = token.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            // Accept "extension:<Name>"; tolerate a bare name as the same intent.
            int colon = trimmed.indexOf(':');
            if (colon >= 0)
            {
                String prefix = trimmed.substring(0, colon).trim();
                String name = trimmed.substring(colon + 1).trim();
                if ("extension".equalsIgnoreCase(prefix) && !name.isEmpty()) //$NON-NLS-1$
                {
                    requested.add(name);
                }
            }
            else
            {
                requested.add(trimmed);
            }
        }

        Set<IProject> projects = new LinkedHashSet<>();
        projects.add(launchProject);
        if (requested.isEmpty())
        {
            // Scope referenced no parseable extension name — degrade to just the
            // configuration (which is always rebuilt) rather than the full scope.
            return new ArrayList<>(projects);
        }
        for (IProject project : collectLaunchAndExtensionProjects(launchProject))
        {
            if (project == null || launchProject.equals(project))
            {
                continue;
            }
            if (requested.contains(project.getName()))
            {
                projects.add(project);
            }
        }
        return new ArrayList<>(projects);
    }

    /**
     * Returns the launch's project followed by every open extension project whose
     * parent configuration project is the launch's project. Order is preserved
     * and duplicates removed; the launch project is always first.
     *
     * <p>Extensions are discovered via {@link IV8ProjectManager} —
     * {@link IExtensionProject#getParentProject()} links an extension back to the
     * configuration project it extends. When the project manager is unavailable
     * (headless tests, early startup) the list degrades gracefully to just the
     * launch project, so the build wait is still performed for it.
     */
    static List<IProject> collectLaunchAndExtensionProjects(IProject launchProject)
    {
        Set<IProject> projects = new LinkedHashSet<>();
        projects.add(launchProject);

        IV8ProjectManager projectManager = Activator.getDefault() != null
            ? Activator.getDefault().getV8ProjectManager() : null;
        if (projectManager == null)
        {
            return new ArrayList<>(projects);
        }
        try
        {
            Collection<IExtensionProject> extensions =
                projectManager.getProjects(IExtensionProject.class);
            if (extensions != null)
            {
                for (IExtensionProject extension : extensions)
                {
                    if (extension == null)
                    {
                        continue;
                    }
                    IProject parent = extension.getParentProject();
                    if (!launchProject.equals(parent))
                    {
                        continue;
                    }
                    IProject extProject = extension.getProject();
                    if (extProject != null && extProject.exists() && extProject.isOpen())
                    {
                        projects.add(extProject);
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            // Discovery is best-effort: a failure here must not abort the launch.
            // The workspace-wide build join already drained the extension build;
            // we just skip the per-extension derived-data wait.
            Activator.logError("Error collecting extension projects for " //$NON-NLS-1$
                + launchProject.getName(), e);
        }
        return new ArrayList<>(projects);
    }

    /**
     * Brings the given application's database to {@link ApplicationUpdateState#UPDATED}
     * if needed, using the same programmatic path as {@code update_database}.
     * Skips the work when the state is already {@code UPDATED} or {@code BEING_UPDATED}.
     *
     * <p>Returns {@code Optional.empty()} on success (or no-op); on failure,
     * returns a populated error message.
     */
    public static Optional<String> updateApplicationIfNeeded(IProject project, String applicationId,
            IApplicationManager appManager)
    {
        if (appManager == null)
        {
            return Optional.of("IApplicationManager service is not available"); //$NON-NLS-1$
        }
        if (project == null || !project.exists() || !project.isOpen())
        {
            return Optional.of("Project is not available: " //$NON-NLS-1$
                + (project != null ? project.getName() : "<null>")); //$NON-NLS-1$
        }
        try
        {
            Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
            if (!appOpt.isPresent())
            {
                return Optional.of("Application not found: " + applicationId); //$NON-NLS-1$
            }
            IApplication application = appOpt.get();
            ApplicationUpdateState state = appManager.getUpdateState(application);
            if (state == ApplicationUpdateState.UPDATED)
            {
                return Optional.empty();
            }
            if (state == ApplicationUpdateState.BEING_UPDATED)
            {
                // Another caller (or a UI gesture) is already updating this IB.
                // Wait for it to settle into UPDATED — if we proceeded to launch
                // now, the delegate could still hit a locked / half-updated IB.
                long maxMillis = getDefaultTerminateTimeoutSeconds() * 1000L;
                state = waitForUpdateSettled(appManager, application, maxMillis);
                if (state == ApplicationUpdateState.UPDATED)
                {
                    return Optional.empty();
                }
                return Optional.of("Another update is in progress and did not " //$NON-NLS-1$
                    + "settle within " + (maxMillis / 1000) + "s (final state: " //$NON-NLS-1$ //$NON-NLS-2$
                    + state + "). Retry shortly, or increase the " //$NON-NLS-1$
                    + "`terminate_launch.timeoutSeconds` preference if your IB " //$NON-NLS-1$
                    + "updates take longer.");
            }
            ExecutionContext context = new ExecutionContext();
            Shell shell = grabActiveShell();
            if (shell != null)
            {
                context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
            }
            Activator.logInfo("Pre-launch DB update: application=" + applicationId //$NON-NLS-1$
                + ", stateBefore=" + state); //$NON-NLS-1$
            ApplicationUpdateState after = appManager.update(application,
                ApplicationUpdateType.INCREMENTAL, context, new NullProgressMonitor());
            Activator.logInfo("Pre-launch DB update completed: stateAfter=" + after); //$NON-NLS-1$
            // Post-condition gate: the auto-chain promises the IB will be in
            // UPDATED state before workingCopy.launch() runs, otherwise the
            // launch delegate would still see "DB needs update" and pop its
            // modal dialog — which is exactly what we are trying to avoid.
            if (after == ApplicationUpdateState.UPDATED)
            {
                return Optional.empty();
            }
            if (after == ApplicationUpdateState.BEING_UPDATED)
            {
                // appManager.update() returned but the state machine still
                // reports an update in progress (async path). Wait for it.
                long maxMillis = getDefaultTerminateTimeoutSeconds() * 1000L;
                after = waitForUpdateSettled(appManager, application, maxMillis);
                if (after == ApplicationUpdateState.UPDATED)
                {
                    return Optional.empty();
                }
            }
            return Optional.of("Database update finished with state " + after //$NON-NLS-1$
                + " (expected UPDATED). Inspect the EDT problems view, " //$NON-NLS-1$
                + "or call `update_database` with `fullUpdate=true` / " //$NON-NLS-1$
                + "`autoRestructure=true` to handle restructurization, then retry."); //$NON-NLS-1$
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error during pre-launch DB update", e); //$NON-NLS-1$
            return Optional.of("Database update failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Polls {@link IApplicationManager#getUpdateState} until it leaves
     * {@link ApplicationUpdateState#BEING_UPDATED} or the deadline elapses.
     * Used when another caller is mid-update — we must not start a launch
     * against an IB whose update is still running.
     */
    private static ApplicationUpdateState waitForUpdateSettled(IApplicationManager appManager,
            IApplication application, long maxMillis)
    {
        long deadline = System.currentTimeMillis() + maxMillis;
        ApplicationUpdateState state = ApplicationUpdateState.BEING_UPDATED;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                state = appManager.getUpdateState(application);
            }
            catch (ApplicationException e)
            {
                Activator.logError("Error polling update state", e); //$NON-NLS-1$
                return state;
            }
            if (state != ApplicationUpdateState.BEING_UPDATED)
            {
                return state;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return state;
            }
        }
        return state;
    }

    /**
     * Auto-chain executed before {@code workingCopy.launch()} when the caller
     * wants to bypass EDT's interactive "Update database?" dialog:
     * <ol>
     *   <li>Find every live launch matching {@code project + applicationId};</li>
     *   <li>Politely terminate each and wait — aborts on timeout (the IB would
     *       still be locked, defeating the purpose);</li>
     *   <li>Run {@link #updateApplicationIfNeeded} to settle the IB in
     *       {@code UPDATED} state — the launch delegate then skips its dialog.</li>
     * </ol>
     *
     * <p>If any step fails, the result has {@code ok=false} and the caller must
     * abort instead of falling through to a launch that would hang on a modal.
     *
     * @param launchManager           Eclipse launch manager
     * @param project                 target project
     * @param applicationId           target {@code ATTR_APPLICATION_ID}
     * @param appManager              EDT application manager
     * @param terminateTimeoutSeconds polite-wait window per live launch
     * @param updateScope             which projects to force-recompute+update before
     *            the launch (see {@link #resolveUpdateScope(IProject, String)}); pass
     *            {@code null} or {@code "all"} for the configuration plus its
     *            dependent extensions
     */
    public static PreLaunchResult prepareForFreshLaunch(ILaunchManager launchManager,
            IProject project, String applicationId, IApplicationManager appManager,
            int terminateTimeoutSeconds, String updateScope)
    {
        if (launchManager == null)
        {
            return new PreLaunchResult(false, 0, "Launch manager is not available"); //$NON-NLS-1$
        }
        if (project == null)
        {
            return new PreLaunchResult(false, 0, "Project is null"); //$NON-NLS-1$
        }

        // Per-key lock prevents concurrent run_yaxunit_tests / debug_yaxunit_tests
        // calls targeting the same IB from racing on terminate + update. Other
        // (project, applicationId) pairs are unaffected. Java synchronized is
        // reentrant, so callers may already hold this monitor when wrapping
        // their full spawn+register sequence around the auto-chain.
        synchronized (lockFor(project.getName(), applicationId))
        {
            // Prune terminated entries so a stale registration cannot
            // permanently block future auto-chains.
            OWNED_LAUNCHES.removeIf(ILaunch::isTerminated);

            int terminated = 0;
            for (ILaunch live : LaunchConfigUtils.getAllLiveLaunches(launchManager,
                project.getName()))
            {
                if (!applicationId.equals(LaunchConfigUtils.getApplicationIdFor(live)))
                {
                    continue;
                }
                String name = live.getLaunchConfiguration() != null
                    ? live.getLaunchConfiguration().getName() : "<unknown>"; //$NON-NLS-1$
                // Identity-equals lookup against the OWNED registry — relies on
                // the invariant that callers pass the exact ILaunch instance
                // returned by workingCopy.launch() to registerOwnedLaunch.
                if (OWNED_LAUNCHES.contains(live))
                {
                    return new PreLaunchResult(false, terminated,
                        "Another test run is already in progress for this IB " //$NON-NLS-1$
                            + "(launch '" + name + "'). Wait for it to finish " //$NON-NLS-1$ //$NON-NLS-2$
                            + "(call this tool again later with the same arguments), " //$NON-NLS-1$
                            + "or call `terminate_launch` to stop it first."); //$NON-NLS-1$
                }
                boolean done = terminateAndWait(live, terminateTimeoutSeconds);
                if (!done)
                {
                    return new PreLaunchResult(false, terminated,
                        "Could not terminate previous launch '" + name //$NON-NLS-1$
                            + "' within " + terminateTimeoutSeconds //$NON-NLS-1$
                            + "s. Call `terminate_launch` with `force=true` to kill " //$NON-NLS-1$
                            + "the stuck process, then retry."); //$NON-NLS-1$
                }
                terminated++;
            }

            // Second pass: stale Attach launches on the same project. Attach
            // configs don't carry a real ATTR_APPLICATION_ID — getApplicationIdFor
            // synthesises "attach:<name>", which never equals a runtime client's
            // UUID, so the per-applicationId loop above never sweeps them. A
            // lingering Attach (e.g. left over from a previous debug session) can
            // mask which 1С client really holds the IB and clutter diagnostics,
            // so disconnect it before the new spawn. Killing an Attach launch is
            // just a debugger disconnect — the 1С server keeps running, no
            // unsaved state is at risk.
            for (ILaunch live : LaunchConfigUtils.getAllLiveLaunches(launchManager,
                project.getName()))
            {
                if (!LaunchConfigUtils.isAttachConfig(live.getLaunchConfiguration()))
                {
                    continue;
                }
                String name = live.getLaunchConfiguration() != null
                    ? live.getLaunchConfiguration().getName() : "<unknown>"; //$NON-NLS-1$
                // Defensive: today both YAXUnit tools register only runtime
                // launches, so an Attach in OWNED is purely hypothetical. If a
                // future tool starts registering Attach launches as owned, we
                // intentionally fast-fail here rather than skipping — at the
                // attach-pass level we don't know which IB the foreign attach
                // targets, and silent skip would risk a spawn that hangs on a
                // locked IB until the MCP timeout. The error message points the
                // caller at terminate_launch, which is the right escape hatch.
                if (OWNED_LAUNCHES.contains(live))
                {
                    return new PreLaunchResult(false, terminated,
                        "An Attach debug session for this project is owned by another " //$NON-NLS-1$
                            + "MCP tool (launch '" + name + "'). Wait for it to finish, " //$NON-NLS-1$ //$NON-NLS-2$
                            + "or call `terminate_launch` to disconnect it first."); //$NON-NLS-1$
                }
                boolean done = terminateAndWait(live, terminateTimeoutSeconds);
                if (!done)
                {
                    // An Attach disconnect that doesn't complete is unusual but
                    // not fatal to the test run: Attach launches don't hold the
                    // IB lock (the foreign 1С client does), so a stuck disconnect
                    // shouldn't block the new spawn. Log and continue.
                    Activator.logError("Could not disconnect stale Attach launch '" //$NON-NLS-1$
                        + name + "' within " + terminateTimeoutSeconds //$NON-NLS-1$
                        + "s, continuing with the auto-chain", null); //$NON-NLS-1$
                    continue;
                }
                // Surface attach disconnects in the log because the per-call
                // PreLaunchResult counter slips them into the same total as
                // runtime terminations — without this line, post-mortems can't
                // tell which launches the second pass swept.
                Activator.logInfo("Pre-launch auto-chain disconnected stale Attach launch '" //$NON-NLS-1$
                    + name + "' on project " + project.getName()); //$NON-NLS-1$
                terminated++;
            }

            // FORCE a derived-data recompute of the launch project AND the
            // requested extensions BEFORE the update runs, then wait for it to
            // settle. Without the forced recompute, an extension (.cfe) edited
            // just before the launch is never regenerated (waitAllComputations
            // no-ops when nothing is scheduled) and appManager.update() consumes
            // the stale export artifact — so the first test run executes the old
            // extension, missing freshly added tests (bug #19925). updateScope
            // narrows the recompute when only a specific extension changed.
            recomputeAndSettle(resolveUpdateScope(project, updateScope));

            Optional<String> updateErr = updateApplicationIfNeeded(project, applicationId,
                appManager);
            if (updateErr.isPresent())
            {
                return new PreLaunchResult(false, terminated, updateErr.get());
            }
            return new PreLaunchResult(true, terminated, null);
        }
    }

    /**
     * Convenience: returns the configured default terminate timeout (in seconds)
     * shared with the {@code terminate_launch} tool, so the auto-chain honours
     * the same preference.
     */
    public static int getDefaultTerminateTimeoutSeconds()
    {
        return ToolParameterSettings.getInstance()
            .getParameterValue("terminate_launch", "timeoutSeconds", 10); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns the active SWT {@link Shell} (or the first available one) to seed
     * an {@link ExecutionContext} so EDT can parent its dialogs correctly.
     * Returns {@code null} in headless environments where no shell exists.
     *
     * <p>Shared by every tool that builds an {@code ExecutionContext} before
     * calling {@link IApplicationManager#update} (update_database, debug_launch,
     * the YAXUnit auto-chain) so the SWT-grab logic lives in exactly one place.
     */
    public static Shell grabActiveShell()
    {
        Display display;
        try
        {
            // Headless environments (CI Linux with no X server, EDT via CLI with
            // no UI) cannot initialise SWT — gtk_init_check() throws SWTError.
            // No dialogs will appear there anyway, so we return null.
            display = Display.getDefault();
        }
        catch (SWTError | UnsatisfiedLinkError e)
        {
            return null;
        }
        if (display == null || display.isDisposed())
        {
            return null;
        }
        final Shell[] holder = new Shell[1];
        display.syncExec(() -> {
            holder[0] = display.getActiveShell();
            if (holder[0] == null)
            {
                Shell[] shells = display.getShells();
                if (shells.length > 0)
                {
                    holder[0] = shells[0];
                }
            }
        });
        return holder[0];
    }
}
