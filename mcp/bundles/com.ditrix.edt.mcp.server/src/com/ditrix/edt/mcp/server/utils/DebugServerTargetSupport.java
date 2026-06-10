/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Bridges EDT's 1C-native debug-server targets (the ones a breakpoint actually
 * suspends on when code runs <em>server-side</em> during {@code debug_yaxunit_tests},
 * or that a user started from the EDT UI via "Debug As") into the generic Eclipse
 * debug model the MCP debug tools already speak.
 *
 * <h2>Why this exists</h2>
 * When YAXUnit tests run on a file-mode standalone server (rphost) — or a debug
 * session is started directly from the EDT UI — a breakpoint trips on a debuggee
 * that EDT tracks through its own
 * {@code IRuntimeDebugClientTargetManager.listDebugTargets()} view, NOT through
 * the threads of the Eclipse {@link org.eclipse.debug.core.ILaunch} a runtime
 * client started. So {@code launch.getDebugTargets()[*].getThreads()} reports
 * {@code threadCount:0} and {@code suspended:false} even though the server is
 * really suspended on the breakpoint line. The other debug tools, which resolve
 * an {@code applicationId} to an Eclipse {@code ILaunch}, never see the suspend.
 *
 * <h2>The key fact that makes the bridge clean</h2>
 * The 1C-native debug model is <strong>not</strong> a separate model — it
 * implements the standard Eclipse debug interfaces (verified in the EDT 2025.2
 * Javadoc):
 * <ul>
 *   <li>{@code IRuntimeDebugClientTarget extends org.eclipse.debug.core.model.IDebugTarget}
 *       (and {@code ISuspendResume}, {@code ITerminate})</li>
 *   <li>{@code IRuntimeDebugTargetThread extends org.eclipse.debug.core.model.IThread}
 *       (and {@code IStep}, {@code ISuspendResume})</li>
 *   <li>{@code IBslStackFrame extends org.eclipse.debug.core.model.IStackFrame}
 *       (and {@code IStep})</li>
 * </ul>
 * Therefore, once a server target is obtained, its threads/frames can be read,
 * resumed, stepped, inspected ({@code getVariables}) and evaluated against
 * through the very same Eclipse-interface code paths the existing tools use for
 * thin-client launches.
 *
 * <h2>Reflection boundary</h2>
 * Only the <em>discovery</em> of the targets is reflective — obtaining the
 * {@code IRuntimeDebugClientTargetManager} (an OSGi service looked up by class
 * name in {@link Activator#getRuntimeDebugClientTargetManager()}) and calling
 * its {@code listDebugTargets()}. Each returned element is then cast straight to
 * {@link IDebugTarget}; from that point on no reflection is needed.
 */
public final class DebugServerTargetSupport
{
    /**
     * Prefix for the stable, addressable applicationId minted for a debug-server
     * target. Mirrors the {@code ServerApplication.<app>} form so the id the user
     * sees on a server debuggee resolves to the (really suspended) server target.
     */
    public static final String SERVER_APP_ID_PREFIX = "ServerApplication."; //$NON-NLS-1$

    private DebugServerTargetSupport()
    {
        // utility class
    }

    /**
     * A single debug-server target seen through the Eclipse debug model, with the
     * stable applicationId the MCP tools address it by.
     */
    public static final class ServerTarget
    {
        /** The 1C-native target, viewed as a standard Eclipse {@link IDebugTarget}. */
        public final IDebugTarget target;
        /** Primary stable id: {@code ServerApplication.<appName|url>}. */
        public final String applicationId;
        /** Bare application name (may be {@code null}). */
        public final String application;
        /** Debug server URL (may be {@code null}). */
        public final String debugServerUrl;

        ServerTarget(IDebugTarget target, String applicationId, String application, String debugServerUrl)
        {
            this.target = target;
            this.applicationId = applicationId;
            this.application = application;
            this.debugServerUrl = debugServerUrl;
        }
    }

    /**
     * Enumerates the debug-server targets EDT currently tracks, each viewed as a
     * standard Eclipse {@link IDebugTarget}. Best-effort and fully guarded —
     * returns an empty list (never throws) when the debug-core manager is
     * unavailable (e.g. headless test runtime) or the API shape differs.
     *
     * @return list of server targets (possibly empty)
     */
    public static List<ServerTarget> listServerTargets()
    {
        List<ServerTarget> out = new ArrayList<>();
        Object manager = Activator.getDefault() != null
            ? Activator.getDefault().getRuntimeDebugClientTargetManager()
            : null;
        if (manager == null)
        {
            return out;
        }
        try
        {
            Method listMethod = manager.getClass().getMethod("listDebugTargets"); //$NON-NLS-1$
            listMethod.setAccessible(true);
            Object targets = listMethod.invoke(manager);
            if (!(targets instanceof Iterable<?>))
            {
                return out;
            }
            for (Object t : (Iterable<?>)targets)
            {
                if (!(t instanceof IDebugTarget))
                {
                    // The 1C target implements org.eclipse.debug IDebugTarget; if a
                    // build ever changes that, skip rather than crash.
                    continue;
                }
                IDebugTarget target = (IDebugTarget)t;
                String app = reflectApplicationName(t);
                String url = reflectString(t, "getDebugServerUrl"); //$NON-NLS-1$
                String idKey = app != null && !app.isEmpty() ? app : url;
                if (idKey == null || idKey.isEmpty())
                {
                    idKey = target.getName();
                }
                String appId = SERVER_APP_ID_PREFIX + idKey;
                out.add(new ServerTarget(target, appId, app, url));
            }
        }
        catch (Throwable e)
        {
            Activator.logError("Error enumerating debug-server targets", e); //$NON-NLS-1$
        }
        return out;
    }

    /**
     * Finds a live runtime-client DEBUG target that EDT's launch delegate would
     * consider a duplicate of a launch for {@code (projectName, resolvedAppId)} —
     * the criterion behind the "Debug session already exists" code-1003 modal.
     *
     * <p>This mirrors
     * {@code RuntimeClientLaunchDelegate.checkExistingDebugSessions}: it enumerates
     * EDT's own {@code IRuntimeDebugClientTargetManager.listDebugTargets()} view
     * (the SAME set the delegate scans — which includes UI-started "Debug As" and
     * otherwise-unregistered runtime-client sessions that never surface in
     * {@link org.eclipse.debug.core.ILaunchManager#getLaunches()}, and so are
     * invisible to {@link DebugSessionRegistry#findActiveTarget}/
     * {@link DebugSessionRegistry#findActiveLaunch}), and matches a target whose
     * bound {@code IApplication} has the same project name AND the same application
     * id as the launch about to start.
     *
     * <p>{@code resolvedAppId} MUST already be resolved the delegate's way:
     * {@code ATTR_APPLICATION_ID} if the config has it, else the project's
     * {@code IApplicationManager.getDefaultApplication(project)} id — see
     * {@code DebugLaunchTool} call sites. The synthetic {@code launch:<configName>}
     * id is NOT the delegate's key; passing one here never matches (that mismatch is
     * exactly the bug 20074 missed).
     *
     * <p><strong>A live thread is required (Bitrix 20074).</strong> The application
     * id alone does NOT discriminate a thin-CLIENT debug session from a 1C
     * standalone-SERVER (or profiling) target: both a "Автономный сервер …" server
     * target and a thin client of the same project resolve to the SAME
     * {@code ServerApplication.<proj>} app id. EDT's own delegate keys the duplicate
     * on a LIVE THREAD, not the app id:
     * {@code RuntimeClientLaunchDelegate.findDebugTargetThreadsForSameApplication}
     * collects the non-terminated {@code IThread}s of every same-app-id target and
     * {@code checkExistingDebugSessions} raises no modal when that list is empty.
     * A server/profiling target carries zero live threads, so we mirror that test
     * exactly: a same-app-id+project target matches ONLY when it also has at least
     * one non-terminated thread ({@link #findFirstLiveThread}). Without this a
     * thread-less server target wrongly short-circuited every client launch as
     * {@code alreadyRunning} and {@code restartIfRunning=true} then killed the server
     * and hung on its relaunch.
     *
     * <p>Best-effort and fully guarded — returns {@code null} (never throws) when the
     * target manager is unavailable (headless), the API shape differs, or nothing
     * matches.
     *
     * @param projectName the launch's target project name (may be {@code null}/empty)
     * @param resolvedAppId the delegate-resolved application id (may be {@code null}/empty)
     * @return the matching live runtime-client debug target, or {@code null}
     */
    public static IDebugTarget findRuntimeClientDebugTarget(String projectName, String resolvedAppId)
    {
        if (projectName == null || projectName.isEmpty()
            || resolvedAppId == null || resolvedAppId.isEmpty())
        {
            return null;
        }
        for (ServerTarget st : listServerTargets())
        {
            IDebugTarget target = st.target;
            if (target == null || target.isTerminated())
            {
                continue;
            }
            Object application = reflectApplication(target);
            if (application == null)
            {
                continue;
            }
            String appId = reflectString(application, "getId"); //$NON-NLS-1$
            if (!resolvedAppId.equals(appId))
            {
                continue;
            }
            if (!projectName.equals(reflectApplicationProjectName(application)))
            {
                continue;
            }
            // A server/profiling target shares the client's app id+project but carries
            // NO live thread — it is NOT the delegate's duplicate. Require a live
            // thread, mirroring findDebugTargetThreadsForSameApplication(...).isEmpty().
            if (findFirstLiveThread(target) != null)
            {
                return target;
            }
        }
        return null;
    }

    /**
     * Resolves an {@code applicationId} to a debug-server target. Accepts, in
     * order of preference:
     * <ul>
     *   <li>the minted {@code ServerApplication.<app>} id (exact match),</li>
     *   <li>the bare application name (e.g. {@code ГрафикДоставки}),</li>
     *   <li>the debug server URL,</li>
     *   <li>the id of the owning Eclipse launch (real {@code ATTR_APPLICATION_ID},
     *       {@code attach:<name>} or {@code launch:<name>}).</li>
     * </ul>
     * When more than one server target matches loosely, a target that is actually
     * suspended wins over an idle one.
     *
     * @param applicationId the id to resolve (may be {@code null})
     * @return the matching {@link ServerTarget}, or {@code null} if none matches
     */
    public static ServerTarget resolve(String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return null;
        }
        String bare = applicationId.startsWith(SERVER_APP_ID_PREFIX)
            ? applicationId.substring(SERVER_APP_ID_PREFIX.length())
            : applicationId;

        ServerTarget looseMatch = null;
        for (ServerTarget st : listServerTargets())
        {
            if (st.target == null || st.target.isTerminated())
            {
                continue;
            }
            boolean exact = applicationId.equals(st.applicationId);
            boolean byName = bare.equals(st.application);
            boolean byUrl = bare.equals(st.debugServerUrl);
            // Also match by the id of the Eclipse launch that owns this server
            // debuggee (real ATTR_APPLICATION_ID / attach:<name> / launch:<name>).
            boolean byLaunchId = matchesOwningLaunchId(st.target, applicationId);
            if (exact || byName || byUrl || byLaunchId)
            {
                if (isAnyThreadSuspended(st.target))
                {
                    return st; // prefer the suspended one
                }
                if (looseMatch == null)
                {
                    looseMatch = st;
                }
            }
        }
        return looseMatch;
    }

    /**
     * Returns the lone non-terminated server target if exactly one exists,
     * otherwise {@code null}. Lets the debug tools auto-resolve the single obvious
     * server debuggee the way they already auto-resolve a single Eclipse launch.
     *
     * @return the sole server target, or {@code null}
     */
    public static ServerTarget findLoneServerTarget()
    {
        ServerTarget only = null;
        for (ServerTarget st : listServerTargets())
        {
            if (st.target == null || st.target.isTerminated())
            {
                continue;
            }
            if (only != null)
            {
                return null; // more than one — ambiguous
            }
            only = st;
        }
        return only;
    }

    /**
     * Polls a server target for a suspended thread up to {@code timeoutMs},
     * returning the first suspended {@link IThread} found, or {@code null} on
     * timeout. Used by {@code wait_for_break}/{@code step} for server targets,
     * whose SUSPEND {@code DebugEvent}s do not reliably key into the launch-based
     * suspend registry (the 1C target's {@code getLaunch()} does not carry the
     * runtime-client {@code ATTR_APPLICATION_ID}). Polling the live model directly
     * is authoritative.
     *
     * @param target the server target viewed as an Eclipse debug target
     * @param timeoutMs maximum time to wait, in milliseconds
     * @param pollIntervalMs sleep between polls, in milliseconds
     * @return a suspended thread, or {@code null} on timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public static IThread pollForSuspendedThread(IDebugTarget target, long timeoutMs, long pollIntervalMs)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true)
        {
            IThread suspended = findSuspendedThread(target);
            if (suspended != null)
            {
                return suspended;
            }
            if (target == null || target.isTerminated())
            {
                return null;
            }
            if (System.currentTimeMillis() >= deadline)
            {
                return null;
            }
            Thread.sleep(Math.min(pollIntervalMs, Math.max(1, deadline - System.currentTimeMillis())));
        }
    }

    /**
     * Finds the first suspended {@link IThread} of a server target, or
     * {@code null} if none is currently suspended.
     *
     * @param target the server target viewed as an Eclipse debug target
     * @return a suspended thread, or {@code null}
     */
    public static IThread findSuspendedThread(IDebugTarget target)
    {
        if (target == null || target.isTerminated())
        {
            return null;
        }
        try
        {
            for (IThread thread : target.getThreads())
            {
                if (thread != null && thread.isSuspended() && !isStepping(thread))
                {
                    return thread;
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort
        }
        return null;
    }

    /**
     * Finds the first non-terminated {@link IThread} of a target, or {@code null}
     * if it has none. Unlike {@link #findSuspendedThread}, this does NOT require the
     * thread to be suspended — mere liveness is the discriminator EDT's launch
     * delegate uses to tell a thin-CLIENT debug session (≥1 live thread) from a 1C
     * standalone-SERVER / profiling target (0 threads). Used by
     * {@link #findRuntimeClientDebugTarget} so a thread-less server target no longer
     * blocks a client launch (Bitrix 20074). Best-effort and fully guarded — never
     * throws.
     *
     * @param target the target viewed as an Eclipse debug target
     * @return a non-terminated thread, or {@code null}
     */
    public static IThread findFirstLiveThread(IDebugTarget target)
    {
        if (target == null || target.isTerminated())
        {
            return null;
        }
        try
        {
            for (IThread thread : target.getThreads())
            {
                if (thread != null && !thread.isTerminated())
                {
                    return thread;
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort
        }
        return null;
    }

    /**
     * @param thread the thread to test
     * @return {@code true} if the thread is mid-step (so its {@code isSuspended()}
     *     would otherwise transiently report the pre-step stop). Threads that do
     *     not implement {@code IStep} are never stepping.
     */
    private static boolean isStepping(IThread thread)
    {
        if (thread instanceof org.eclipse.debug.core.model.IStep)
        {
            try
            {
                return ((org.eclipse.debug.core.model.IStep)thread).isStepping();
            }
            catch (Exception ex)
            {
                return false;
            }
        }
        return false;
    }

    /**
     * @param target the server target viewed as an Eclipse debug target
     * @return {@code true} if any of its threads reports suspended
     */
    public static boolean isAnyThreadSuspended(IDebugTarget target)
    {
        return findSuspendedThread(target) != null;
    }

    /**
     * Builds the diagnostic DTO for a server target used by {@code debug_status}.
     * Walks the target's threads (via the Eclipse {@link IThread} interface),
     * reporting the real suspend state, thread count, and the top suspended frame.
     *
     * @param st the server target
     * @return a JSON-friendly map describing the target
     */
    public static Map<String, Object> describe(ServerTarget st)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", st.applicationId); //$NON-NLS-1$
        m.put("targetClass", st.target.getClass().getSimpleName()); //$NON-NLS-1$
        if (st.debugServerUrl != null)
        {
            m.put("debugServerUrl", st.debugServerUrl); //$NON-NLS-1$
        }
        if (st.application != null)
        {
            m.put("application", st.application); //$NON-NLS-1$
        }

        int threadCount = 0;
        boolean anySuspended = false;
        String suspendedAt = null;
        String suspendedThreadName = null;
        try
        {
            for (IThread th : st.target.getThreads())
            {
                if (th == null)
                {
                    continue;
                }
                threadCount++;
                if (th.isSuspended())
                {
                    anySuspended = true;
                    if (suspendedAt == null)
                    {
                        suspendedThreadName = safe(th.getName());
                        IStackFrame top = th.getTopStackFrame();
                        if (top != null)
                        {
                            suspendedAt = safe(top.getName()) + " @ " + top.getLineNumber(); //$NON-NLS-1$
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort; report what we have
        }
        m.put("threadCount", threadCount); //$NON-NLS-1$
        m.put("suspended", anySuspended); //$NON-NLS-1$
        if (suspendedAt != null)
        {
            m.put("suspendedAt", suspendedAt); //$NON-NLS-1$
        }
        if (suspendedThreadName != null)
        {
            m.put("suspendedThread", suspendedThreadName); //$NON-NLS-1$
        }
        return m;
    }

    /**
     * @param target the server target viewed as an Eclipse debug target
     * @param applicationId the id to compare against the owning launch's id
     * @return {@code true} if the Eclipse launch that owns {@code target} resolves to
     *     the same {@code applicationId} (real {@code ATTR_APPLICATION_ID},
     *     {@code attach:<name>} or {@code launch:<name>}). Best-effort; never throws.
     */
    private static boolean matchesOwningLaunchId(IDebugTarget target, String applicationId)
    {
        if (target == null || applicationId == null)
        {
            return false;
        }
        try
        {
            String launchId = LaunchConfigUtils.getApplicationIdFor(target.getLaunch());
            return applicationId.equals(launchId);
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    /** Reflectively invokes a no-arg getter and returns its {@code toString()}; null on any failure. */
    private static String reflectString(Object target, String getter)
    {
        try
        {
            Method m = target.getClass().getMethod(getter);
            m.setAccessible(true);
            Object v = m.invoke(target);
            return v != null ? v.toString() : null;
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /** Reflectively resolves {@code getApplication()} (possibly an Optional) to its name. */
    private static String reflectApplicationName(Object target)
    {
        Object app = reflectApplication(target);
        if (app == null)
        {
            return null;
        }
        String name = reflectString(app, "getName"); //$NON-NLS-1$
        return name != null ? name : app.toString();
    }

    /**
     * Reflectively resolves the target's bound {@code IApplication}
     * ({@code getApplication()}, unwrapping an {@link java.util.Optional}); null on
     * any failure or when no application is bound.
     */
    private static Object reflectApplication(Object target)
    {
        try
        {
            Method m = target.getClass().getMethod("getApplication"); //$NON-NLS-1$
            m.setAccessible(true);
            Object app = m.invoke(target);
            if (app instanceof java.util.Optional<?>)
            {
                app = ((java.util.Optional<?>)app).orElse(null);
            }
            return app;
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /**
     * Reflectively resolves an {@code IApplication}'s project name
     * ({@code getProject().getName()}); null on any failure. The
     * {@code com.e1c.g5.dt.applications.IApplication} interface is in a different
     * bundle, so this stays reflective like the rest of this support class.
     */
    private static String reflectApplicationProjectName(Object application)
    {
        try
        {
            Method m = application.getClass().getMethod("getProject"); //$NON-NLS-1$
            m.setAccessible(true);
            Object project = m.invoke(application);
            if (project == null)
            {
                return null;
            }
            return reflectString(project, "getName"); //$NON-NLS-1$
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    private static String safe(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }
}
