/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.model.IDebugTarget;
import org.osgi.framework.Bundle;

/**
 * Resolves the profiling-capable target ({@code IProfileTarget}) for a debug
 * session, reusing {@link DebugTargetResolver} so it accepts every
 * {@code applicationId} form the other debug tools accept (real
 * {@code ATTR_APPLICATION_ID}, {@code attach:<name>}, {@code launch:<name>},
 * {@code ServerApplication.<app>}, the bare application name, the debug server URL).
 *
 * <h2>Why a dedicated resolver</h2>
 * Profiling is the one debug capability that is NOT on the generic
 * {@code org.eclipse.debug.core.model.IDebugTarget} interface — it lives on
 * {@code com._1c.g5.v8.dt.profiling.core.IProfileTarget}. For a standalone-server
 * debug session, EDT's {@code ProfilingRuntimeDebugClientTarget} implements both
 * {@code IRuntimeDebugClientTarget} (the server target {@code wait_for_break} et al.
 * speak to) AND {@code IProfileTarget}, so the SAME object resolved by
 * {@link DebugTargetResolver} is already profile-capable. This class performs the
 * reflective {@code IProfileTarget} check/adaptation in one place so both
 * {@code start_profiling} and {@code get_profiling_results} resolve identically and
 * accept the canonical id the rest of the chain uses.
 *
 * <h2>Reflection boundary</h2>
 * The plugin must not add an {@code Import-Package} on
 * {@code com._1c.g5.v8.dt.profiling.core}; the existing profiling tools already
 * load {@code IProfileTarget} reflectively via {@code Platform.getBundle(...)}.
 * This resolver keeps that convention.
 */
public final class ProfilingTargetResolver
{
    /** Bundle that owns the profiling core API. */
    public static final String PROFILING_CORE_BUNDLE = "com._1c.g5.v8.dt.profiling.core"; //$NON-NLS-1$

    /** Fully qualified name of the profiling-target interface (loaded reflectively). */
    public static final String IPROFILE_TARGET_CLASS = "com._1c.g5.v8.dt.profiling.core.IProfileTarget"; //$NON-NLS-1$

    private ProfilingTargetResolver()
    {
        // utility class
    }

    /** Outcome of resolving a profiling target: enough to act and to report well. */
    public static final class Result
    {
        /** The {@code IProfileTarget} (typed as {@link Object} — loaded reflectively), or {@code null}. */
        public final Object profileTarget;
        /** The class object for {@code IProfileTarget}, for the {@code toggleProfiling} reflection. */
        public final Class<?> profileTargetClass;
        /** The canonical id of the resolved session (what callers should echo back). */
        public final String canonicalId;
        /** The underlying debug target that was resolved (may be non-null even if profiling not supported). */
        public final IDebugTarget debugTarget;
        /** {@code true} if the id was blank and a single session was auto-selected. */
        public final boolean autoResolved;
        /** A human-readable reason when {@link #profileTarget} is {@code null}. */
        public final String error;

        private Result(Object profileTarget, Class<?> profileTargetClass, String canonicalId,
            IDebugTarget debugTarget, boolean autoResolved, String error)
        {
            this.profileTarget = profileTarget;
            this.profileTargetClass = profileTargetClass;
            this.canonicalId = canonicalId;
            this.debugTarget = debugTarget;
            this.autoResolved = autoResolved;
            this.error = error;
        }

        static Result ok(Object profileTarget, Class<?> cls, String canonicalId, IDebugTarget dt,
            boolean autoResolved)
        {
            return new Result(profileTarget, cls, canonicalId, dt, autoResolved, null);
        }

        static Result fail(String error)
        {
            return new Result(null, null, null, null, false, error);
        }

        static Result fail(String error, String canonicalId, IDebugTarget dt, boolean autoResolved)
        {
            return new Result(null, null, canonicalId, dt, autoResolved, error);
        }

        /** @return {@code true} if a usable {@code IProfileTarget} was found. */
        public boolean isResolved()
        {
            return profileTarget != null;
        }
    }

    /**
     * Resolves the {@code IProfileTarget} for the given {@code applicationId} (any
     * id form). Tries, in order: the directly-resolved debug target itself (the
     * common standalone-server case — {@code ProfilingRuntimeDebugClientTarget} IS
     * an {@code IProfileTarget}); then the same debuggee's server-target view; then
     * the Eclipse adapter ({@code IDebugTarget.getAdapter(IProfileTarget.class)}).
     * Never throws.
     *
     * @param applicationId any accepted id form (may be {@code null}/empty)
     * @return a {@link Result}; {@link Result#isResolved()} is {@code false} with a
     *     populated {@link Result#error} when no profiling-capable target was found
     */
    public static Result resolve(String applicationId)
    {
        Bundle profilingBundle = Platform.getBundle(PROFILING_CORE_BUNDLE);
        if (profilingBundle == null)
        {
            return Result.fail("Profiling core bundle not found"); //$NON-NLS-1$
        }
        Class<?> profileTargetClass;
        try
        {
            profileTargetClass = profilingBundle.loadClass(IPROFILE_TARGET_CLASS);
        }
        catch (ClassNotFoundException e)
        {
            return Result.fail("IProfileTarget class not found in profiling bundle"); //$NON-NLS-1$
        }

        DebugTargetResolver.Resolution res = DebugTargetResolver.resolve(applicationId);
        if (res == null)
        {
            if (applicationId == null || applicationId.isEmpty())
            {
                return Result.fail("No active debug session to profile. Start one first " //$NON-NLS-1$
                    + "(debug_launch or debug_yaxunit_tests), or pass an explicit applicationId.");
            }
            return Result.fail("No active debug target for applicationId: " + applicationId //$NON-NLS-1$
                + ". Start a debug session first (debug_launch or debug_yaxunit_tests).");
        }

        String canonicalId = res.canonicalId;
        IDebugTarget primary = res.target;

        // 1) The resolved target itself (standalone-server: it IS an IProfileTarget).
        Object profileTarget = adaptToProfileTarget(primary, profileTargetClass);

        // 2) The same debuggee's server-target view, when distinct from the primary.
        if (profileTarget == null && res.serverTarget != null
            && res.serverTarget.target != null && res.serverTarget.target != primary)
        {
            profileTarget = adaptToProfileTarget(res.serverTarget.target, profileTargetClass);
        }

        // 3) If the primary was a launch target, the matching server target (same
        //    object normally; this covers the case where it is a sibling object).
        if (profileTarget == null && res.serverTarget == null)
        {
            DebugServerTargetSupport.ServerTarget sibling =
                DebugTargetResolver.serverTargetForTarget(primary);
            if (sibling != null && sibling.target != null)
            {
                profileTarget = adaptToProfileTarget(sibling.target, profileTargetClass);
            }
        }

        if (profileTarget == null)
        {
            return Result.fail("Debug target does not support profiling. Target class: " //$NON-NLS-1$
                + (primary != null ? primary.getClass().getName() : "null"), //$NON-NLS-1$
                canonicalId, primary, res.autoResolved);
        }
        return Result.ok(profileTarget, profileTargetClass, canonicalId, primary, res.autoResolved);
    }

    /**
     * Adapts an {@link IDebugTarget} to {@code IProfileTarget}: a direct instance
     * check first, then the Eclipse adapter mechanism. Returns {@code null} when
     * the target is not profiling-capable. Never throws.
     *
     * @param target the debug target (may be {@code null})
     * @param profileTargetClass the reflectively-loaded {@code IProfileTarget} class
     * @return the {@code IProfileTarget} (as {@link Object}), or {@code null}
     */
    public static Object adaptToProfileTarget(IDebugTarget target, Class<?> profileTargetClass)
    {
        if (target == null || profileTargetClass == null)
        {
            return null;
        }
        try
        {
            if (profileTargetClass.isInstance(target))
            {
                return target;
            }
            return target.getAdapter(profileTargetClass);
        }
        catch (Exception ex)
        {
            return null;
        }
    }
}
