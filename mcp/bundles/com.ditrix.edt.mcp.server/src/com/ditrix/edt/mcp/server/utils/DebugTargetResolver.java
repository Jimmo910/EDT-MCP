/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.debug.core.model.IDebugTarget;

/**
 * Single, unified entry point that resolves <em>any</em> form of debug
 * {@code applicationId} the MCP debug/profiling tools accept to the one
 * underlying Eclipse {@link IDebugTarget}.
 *
 * <h2>Why this exists (#19941 / S22)</h2>
 * Before this resolver, the same running server debug session (started by
 * {@code debug_yaxunit_tests}) was addressable under several different ids, and
 * different tools accepted different subsets:
 * <ul>
 *   <li>{@code wait_for_break} / {@code get_variables} / {@code step} resolved
 *       {@code "ServerApplication.<app>"} via {@link DebugServerTargetSupport}.</li>
 *   <li>{@code start_profiling} / {@code get_profiling_results} resolved only
 *       {@code "launch:<name>"} / the real {@code ATTR_APPLICATION_ID} via
 *       {@link DebugSessionRegistry#findActiveTarget(String)} and ERRORED on
 *       {@code "ServerApplication.<app>"}.</li>
 *   <li>{@code resume(applicationId="ServerApplication.<app>")} failed because the
 *       target-level {@code canResume()} reports false even when a thread can
 *       resume.</li>
 * </ul>
 * The caller had to remember which id each tool wanted. This class removes that
 * burden: every tool resolves through {@link #resolve(String)} and accepts every
 * id form for the same session.
 *
 * <h2>The key fact that makes one target serve everything</h2>
 * A standalone-server debug session is backed by EDT's
 * {@code ProfilingRuntimeDebugClientTarget}, which implements <strong>both</strong>
 * {@code IRuntimeDebugClientTarget} (so it is enumerated by
 * {@code IRuntimeDebugClientTargetManager.listDebugTargets()} and addressable as
 * {@code "ServerApplication.<app>"}) <strong>and</strong>
 * {@code com._1c.g5.v8.dt.profiling.core.IProfileTarget} (so the very same object
 * can be profiled). It also exposes {@code getLaunch()}, so the Eclipse
 * {@link org.eclipse.debug.core.ILaunch} that owns it surfaces the same object via
 * {@code launch.getDebugTargets()} — addressable as {@code "launch:<name>"} or the
 * real {@code ATTR_APPLICATION_ID}. In other words, {@code "ServerApplication.<app>"}
 * and {@code "launch:<name>"} are two <em>views of one IDebugTarget object</em>,
 * and that object is the profiling-capable target. Resolving any id to that object
 * therefore makes wait/resume/step/variables/evaluate AND profiling all work off
 * the same session.
 *
 * <h2>Resolution order</h2>
 * {@link #resolve(String)} tries, in order:
 * <ol>
 *   <li>{@link DebugSessionRegistry#findActiveTarget(String)} — launch-based ids:
 *       the real {@code ATTR_APPLICATION_ID}, {@code "attach:<name>"},
 *       {@code "launch:<name>"}.</li>
 *   <li>{@link DebugServerTargetSupport#resolve(String)} — server-target ids:
 *       {@code "ServerApplication.<app>"}, the bare application name, the debug
 *       server URL.</li>
 *   <li>loose lone-session fallbacks when the id is blank: the single active
 *       Eclipse launch, else the single server target.</li>
 * </ol>
 * Every step is null-safe; an unresolvable id yields {@code null} (never throws).
 */
public final class DebugTargetResolver
{
    private DebugTargetResolver()
    {
        // utility class
    }

    /**
     * Classification of an {@code applicationId} string by its prefix. Pure and
     * side-effect free — used to document/route which underlying resolver is the
     * natural owner of an id form (both are still tried at runtime, this only
     * names the expected primary owner).
     */
    public enum IdForm
    {
        /** {@code null} or empty — triggers lone-session auto-resolution. */
        BLANK,
        /** {@code "ServerApplication.<app>"} — owned by {@link DebugServerTargetSupport}. */
        SERVER_APPLICATION,
        /** {@code "attach:<configName>"} — owned by {@link DebugSessionRegistry}/launch manager. */
        ATTACH,
        /** {@code "launch:<configName>"} — owned by {@link DebugSessionRegistry}/launch manager. */
        LAUNCH,
        /** Anything else — a real {@code ATTR_APPLICATION_ID} or a bare application name. */
        REAL_OR_BARE
    }

    /**
     * Classifies an id by its prefix. Pure; never throws.
     *
     * @param applicationId the id (may be {@code null})
     * @return its {@link IdForm}
     */
    public static IdForm classify(String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return IdForm.BLANK;
        }
        if (applicationId.startsWith(DebugServerTargetSupport.SERVER_APP_ID_PREFIX))
        {
            return IdForm.SERVER_APPLICATION;
        }
        if (applicationId.startsWith(LaunchConfigUtils.ATTACH_APP_ID_PREFIX))
        {
            return IdForm.ATTACH;
        }
        if (applicationId.startsWith(LaunchConfigUtils.LAUNCH_APP_ID_PREFIX))
        {
            return IdForm.LAUNCH;
        }
        return IdForm.REAL_OR_BARE;
    }

    /**
     * The outcome of resolving an {@code applicationId}: the underlying Eclipse
     * {@link IDebugTarget}, a canonical id the rest of the chain can reuse, and
     * the {@link DebugServerTargetSupport.ServerTarget} when the session was found
     * through the server-target view (so profiling can reuse the same object).
     */
    public static final class Resolution
    {
        /** The resolved debug target (never {@code null} in a returned Resolution). */
        public final IDebugTarget target;
        /**
         * A canonical, stable id for the resolved session. For a server-target
         * resolution this is the {@code "ServerApplication.<app>"} id; for a
         * launch-based resolution it is the id the launch reports (real /
         * {@code attach:} / {@code launch:}).
         */
        public final String canonicalId;
        /** The server-target view, or {@code null} if resolved purely via the launch manager. */
        public final DebugServerTargetSupport.ServerTarget serverTarget;
        /** {@code true} if the id was blank and a lone session was auto-selected. */
        public final boolean autoResolved;

        Resolution(IDebugTarget target, String canonicalId,
            DebugServerTargetSupport.ServerTarget serverTarget, boolean autoResolved)
        {
            this.target = target;
            this.canonicalId = canonicalId;
            this.serverTarget = serverTarget;
            this.autoResolved = autoResolved;
        }

        /** @return {@code true} if this resolution came through the 1C debug-server view. */
        public boolean isServerTarget()
        {
            return serverTarget != null;
        }
    }

    /**
     * Resolves any id form to a {@link Resolution}. Returns {@code null} when no
     * active session matches (or the id is blank and there is not exactly one
     * obvious session to auto-select). Never throws.
     *
     * @param applicationId the id to resolve (any form; may be {@code null}/empty)
     * @return the resolution, or {@code null} if nothing matches
     */
    public static Resolution resolve(String applicationId)
    {
        // 1) Launch-based view first for a concrete id: real ATTR_APPLICATION_ID,
        //    attach:<name>, launch:<name>. This keeps every previously-working
        //    launch/thin-client/attach path byte-for-byte identical.
        if (applicationId != null && !applicationId.isEmpty())
        {
            IDebugTarget launchTarget = DebugSessionRegistry.findActiveTarget(applicationId);
            if (launchTarget != null && !launchTarget.isTerminated())
            {
                // Even when matched via the launch manager, expose the server-target
                // view of the SAME object when one exists, so profiling can reuse it
                // and the canonical id stays consistent across tools.
                DebugServerTargetSupport.ServerTarget sameObject = serverTargetForTarget(launchTarget);
                return new Resolution(launchTarget, applicationId, sameObject, false);
            }

            // 2) Server-target view: ServerApplication.<app>, bare app name, URL.
            DebugServerTargetSupport.ServerTarget st = DebugServerTargetSupport.resolve(applicationId);
            if (st != null && st.target != null && !st.target.isTerminated())
            {
                return new Resolution(st.target, st.applicationId, st, false);
            }
            // Concrete id given but nothing matched — do NOT silently fall back to a
            // lone session; the caller asked for a specific session.
            return null;
        }

        // 3) Blank id — auto-resolve a single obvious session.
        String loneLaunchId = DebugSessionRegistry.findLoneActiveApplicationId();
        if (loneLaunchId != null)
        {
            IDebugTarget launchTarget = DebugSessionRegistry.findActiveTarget(loneLaunchId);
            if (launchTarget != null && !launchTarget.isTerminated())
            {
                DebugServerTargetSupport.ServerTarget sameObject = serverTargetForTarget(launchTarget);
                return new Resolution(launchTarget, loneLaunchId, sameObject, true);
            }
        }
        DebugServerTargetSupport.ServerTarget lone = DebugServerTargetSupport.findLoneServerTarget();
        if (lone != null && lone.target != null && !lone.target.isTerminated())
        {
            return new Resolution(lone.target, lone.applicationId, lone, true);
        }
        return null;
    }

    /**
     * Returns the {@link DebugServerTargetSupport.ServerTarget} whose underlying
     * 1C target is the SAME object as {@code target} (identity match), or
     * {@code null} when {@code target} is an ordinary Eclipse launch target. Lets a
     * launch-based resolution still expose the server view (and thus the profiling
     * capability) of the one shared {@code ProfilingRuntimeDebugClientTarget}.
     *
     * @param target the resolved debug target
     * @return the matching server-target view, or {@code null}
     */
    public static DebugServerTargetSupport.ServerTarget serverTargetForTarget(IDebugTarget target)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            for (DebugServerTargetSupport.ServerTarget st : DebugServerTargetSupport.listServerTargets())
            {
                if (st.target == target)
                {
                    return st;
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort — treat as a non-server target
        }
        return null;
    }
}
