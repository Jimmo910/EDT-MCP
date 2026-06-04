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
 * suspends on when code runs <em>server-side</em> during {@code debug_yaxunit_tests})
 * into the generic Eclipse debug model the MCP debug tools already speak.
 *
 * <h2>Why this exists</h2>
 * When YAXUnit tests run on a file-mode standalone server (rphost), a breakpoint
 * trips on a debuggee that EDT tracks through its own
 * {@code IRuntimeDebugClientTargetManager.listDebugTargets()} view — NOT through
 * the threads of the Eclipse {@link org.eclipse.debug.core.ILaunch} the runtime
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
 * (via the registered {@code BslWatchExpressionDelegate}) through the very same
 * Eclipse-interface code paths the existing tools use for thin-client launches.
 *
 * <h2>Reflection boundary</h2>
 * Only the <em>discovery</em> of the targets is reflective — obtaining the
 * {@code IRuntimeDebugClientTargetManager} (an OSGi service looked up by class
 * name in {@link Activator#getRuntimeDebugClientTargetManager()}) and calling
 * its {@code listDebugTargets()}. Each returned element is then cast straight to
 * {@link IDebugTarget}; from that point on, no reflection and no extra
 * {@code Import-Package} on {@code com._1c.g5.v8.dt.debug.core.model} is needed.
 */
public final class DebugServerTargetSupport
{
    /**
     * Prefix for the stable, addressable applicationId minted for a debug-server
     * target. Mirrors the {@code ServerApplication.<app>} form that
     * {@code debug_status} already reports for the runtime-client launch that
     * spawned the server debuggee, so the id the user sees on the launch entry
     * also resolves to the (really suspended) server target.
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
     * Resolves an {@code applicationId} to a debug-server target. Accepts, in
     * order of preference:
     * <ul>
     *   <li>the minted {@code ServerApplication.<app>} id (exact match),</li>
     *   <li>the bare application name (e.g. {@code ГрафикДоставки}),</li>
     *   <li>the {@code ServerApplication.} prefix stripped and matched against the
     *       application name (so the id reported on the launch entry also works).</li>
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
            if (exact || byName || byUrl)
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
     * otherwise {@code null}. Lets the debug tools auto-resolve the single
     * obvious server debuggee the way they already auto-resolve a single Eclipse
     * launch.
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
     * whose SUSPEND {@code DebugEvent}s do not reliably key into the
     * launch-based suspend registry (the 1C target's {@code getLaunch()} does not
     * carry the runtime-client {@code ATTR_APPLICATION_ID}), so the event-driven
     * {@code waitForSuspend} cannot catch them. Polling the live model directly is
     * authoritative.
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
        try
        {
            Method m = target.getClass().getMethod("getApplication"); //$NON-NLS-1$
            m.setAccessible(true);
            Object app = m.invoke(target);
            if (app instanceof java.util.Optional<?>)
            {
                app = ((java.util.Optional<?>)app).orElse(null);
            }
            if (app == null)
            {
                return null;
            }
            String name = reflectString(app, "getName"); //$NON-NLS-1$
            return name != null ? name : app.toString();
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
