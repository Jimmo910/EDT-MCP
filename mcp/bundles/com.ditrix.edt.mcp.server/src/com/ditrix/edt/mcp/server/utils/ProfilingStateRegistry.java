/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-plugin tracker for the on/off state of 1C performance measurement (замер
 * производительности) per debug application.
 *
 * <p>The EDT profiling API ({@code IProfilingService} / {@code IProfileTarget})
 * exposes only a stateless {@code toggleProfiling(...)} call — there is no
 * {@code isProfiling()} query. Since the MCP plugin is the only component issuing
 * those toggles for the MCP workflow, it can authoritatively track the resulting
 * state itself. This lets {@code start_profiling} report whether it just switched
 * profiling ON or OFF, and lets {@code get_profiling_results} produce an accurate
 * message when no results are available yet because a measurement is still active.
 *
 * <p>The registry holds the set of applicationIds that are currently measuring
 * (toggled ON). It is a process-wide singleton and is thread-safe.
 */
public final class ProfilingStateRegistry
{
    private static final ProfilingStateRegistry INSTANCE = new ProfilingStateRegistry();

    /** applicationIds whose profiling is currently ON (used as a concurrent set). */
    private final Set<String> activeApplicationIds = ConcurrentHashMap.newKeySet();

    private ProfilingStateRegistry()
    {
    }

    /**
     * Returns the process-wide singleton instance.
     *
     * @return the shared registry
     */
    public static ProfilingStateRegistry get()
    {
        return INSTANCE;
    }

    /**
     * Flips the profiling state for the given application and returns the NEW
     * state: {@code true} if profiling is now ON (measurement started),
     * {@code false} if it is now OFF (measurement stopped).
     *
     * <p>Call this AFTER the underlying {@code toggleProfiling(...)} succeeds so
     * that the tracked state stays consistent with the platform.
     *
     * @param applicationId the debug application id (must not be {@code null})
     * @return {@code true} if profiling is now ON, {@code false} if now OFF
     */
    public synchronized boolean toggle(String applicationId)
    {
        if (applicationId == null)
        {
            return false;
        }
        if (activeApplicationIds.contains(applicationId))
        {
            activeApplicationIds.remove(applicationId);
            return false;
        }
        activeApplicationIds.add(applicationId);
        return true;
    }

    /**
     * Returns {@code true} if profiling is currently ON for the given application.
     *
     * @param applicationId the debug application id
     * @return whether a measurement is active for that application
     */
    public boolean isActive(String applicationId)
    {
        return applicationId != null && activeApplicationIds.contains(applicationId);
    }

    /**
     * Returns {@code true} if profiling is currently ON for any application.
     *
     * @return whether any measurement is active
     */
    public boolean anyActive()
    {
        return !activeApplicationIds.isEmpty();
    }

    /**
     * Returns a snapshot list of the applicationIds whose profiling is currently ON.
     *
     * @return an immutable copy of the active application ids (never {@code null})
     */
    public List<String> activeApplicationIds()
    {
        return Collections.unmodifiableList(new ArrayList<>(activeApplicationIds));
    }

    /**
     * Forces the given application's profiling state to OFF, regardless of the
     * previously tracked value. Idempotent.
     *
     * @param applicationId the debug application id
     */
    public void setInactive(String applicationId)
    {
        if (applicationId != null)
        {
            activeApplicationIds.remove(applicationId);
        }
    }

    /** For tests only — drops all tracked state. */
    public synchronized void clear()
    {
        activeApplicationIds.clear();
    }
}
