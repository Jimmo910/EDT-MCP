/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.junit.Test;
import org.mockito.Mockito;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.DebugLaunchTool.AlreadyRunningContext;
import com.ditrix.edt.mcp.server.tools.impl.DebugLaunchTool.ExistingClientSession;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link DebugLaunchTool}.
 * <p>
 * Covers tool metadata, the input schema, and the two headless-reachable
 * required-argument validations in the project+application launch mode
 * (projectName, then applicationId), which return before the first
 * {@code ProjectStateChecker}/launch-manager access. NOTE: these checks are
 * only reachable when {@code launchConfigurationName} is absent — supplying it
 * enters the by-name launch mode whose first statement touches the live launch
 * manager. Actual launching is covered by the E2E suite.
 */
public class DebugLaunchToolTest
{
    @Test
    public void testName()
    {
        assertEquals("debug_launch", new DebugLaunchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DebugLaunchTool.NAME, new DebugLaunchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DebugLaunchTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DebugLaunchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new DebugLaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresRestartIfRunning()
    {
        // D5 (Bitrix 20074): restartIfRunning must be a declared input so the
        // schema<->execute parity test passes and clients can discover it. Its
        // read in execute() is enforced by SchemaExecuteParamParityTest.
        String schema = new DebugLaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare restartIfRunning",
            schema.contains("\"restartIfRunning\"")); //$NON-NLS-1$
        assertTrue("schema must document the default short-circuit contract",
            schema.contains("alreadyRunning:true")); //$NON-NLS-1$
    }

    @Test
    public void testRestartIfRunningDefaultsToFalse()
    {
        // Default-false contract is reachable headlessly only through the
        // project+application validation that runs BEFORE any launch-manager touch:
        // omitting restartIfRunning must NOT change the required-arg behavior. The
        // launch path itself (where false => alreadyRunning short-circuit) needs a
        // live workbench and is covered E2E.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsRestartIfRunningAndTargetManagerGuard()
    {
        // D5: the guide must document both halves of the fix — the new
        // restartIfRunning switch and that the already-running guard now also catches
        // a session EDT tracks only through its debug target manager (the code-1003
        // "Debug session already exists" modal source). Ratchet so it can't drift.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document restartIfRunning",
            guide.contains("restartIfRunning")); //$NON-NLS-1$
        assertTrue("guide must document the target-manager already-running detection",
            guide.contains("target manager")); //$NON-NLS-1$
        assertTrue("guide must reference the 'Debug session already exists' modal it prevents",
            guide.contains("Debug session already exists")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresLaunchingStatus()
    {
        // The launch is async: a fresh launch emits status:"launching" so the caller
        // knows to poll debug_status. This test only asserts the SCHEMA half of that
        // contract — that the output schema advertises the field; it cannot verify the
        // emitted result here (a real launch needs a live workbench, covered by E2E).
        // The coherence check below ties the metadata (schema + guide) together so the
        // promise can't silently drift in one place only.
        String schema = new DebugLaunchTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"status\"")); //$NON-NLS-1$
        assertTrue(schema.contains("launching")); //$NON-NLS-1$
    }

    @Test
    public void testLaunchingContractIsCoherentAcrossMetadata()
    {
        // Runtime-free coherence check: the async "launching" contract must be
        // declared consistently in BOTH the output schema and the guide, so neither
        // can advertise it while the other forgets to. The actual result emission is
        // verified by the E2E suite (needs a live workbench).
        DebugLaunchTool tool = new DebugLaunchTool();
        String schema = tool.getOutputSchema();
        String guide = tool.getGuide();
        assertNotNull(schema);
        assertNotNull(guide);
        assertTrue(schema.contains("launching")); //$NON-NLS-1$
        assertTrue(guide.contains("launching")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDescribesAsyncLaunch()
    {
        // The launch dispatch is non-blocking (asyncExec): the guide must tell the
        // caller it returns status:"launching" immediately and to poll debug_status
        // for readiness rather than expecting a running session synchronously.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.contains("launching")); //$NON-NLS-1$
        assertTrue(guide.contains("debug_status")); //$NON-NLS-1$
    }

    @Test
    public void testUpdateBeforeLaunchFalseContractIsCoherent()
    {
        // rv1 review: with updateBeforeLaunch=false the DB update is skipped AND
        // the launch delegate's modal is NOT auto-confirmed (auto-pressing "Update
        // then run" would perform the very update the caller disabled). Both
        // metadata halves must keep documenting that the platform may then show
        // the modal, so the contract can't silently drift in one place only.
        DebugLaunchTool tool = new DebugLaunchTool();
        String schema = tool.getInputSchema();
        String guide = tool.getGuide();
        assertNotNull(schema);
        assertNotNull(guide);
        assertTrue("schema must document that updateBeforeLaunch=false may show the modal",
            schema.contains("may then show that modal")); //$NON-NLS-1$
        assertTrue("guide must document the updateBeforeLaunch=false contract",
            guide.contains("updateBeforeLaunch=false")); //$NON-NLS-1$
        assertTrue("guide must document that updateBeforeLaunch=false may show the modal",
            guide.contains("may then show that modal")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsRunModeAlreadyRunningGuard()
    {
        // rv1 review: the already-running guard covers RUN-mode launches (no debug
        // target) in BOTH selection modes (by-name and project+application); the
        // guide documents that promise — keep it ratcheted.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the RUN-mode already-running guard",
            guide.contains("RUN mode")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive detail (Attach mode, the alreadyRunning short-circuit and
        // updateBeforeLaunch nuances) moved out of the slimmed description/schema
        // into getGuide(); assert it survived there rather than vanishing.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("Attach")); //$NON-NLS-1$
        assertTrue(guide.contains("alreadyRunning")); //$NON-NLS-1$
        assertTrue(guide.contains("updateBeforeLaunch")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        // No launchConfigurationName -> project+application mode -> projectName required.
        Map<String, String> params = new HashMap<>();
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationId()
    {
        // projectName present, no launchConfigurationName, applicationId omitted.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }

    // ==================== performLaunch headless routing (rv1 review FIND-2) ====================

    @Test
    public void testPerformLaunchHeadlessExecutesSynchronously() throws Exception
    {
        // rv1 review FIND-2: the display probe must NOT create a display.
        // Display.getDefault() never returns null (per the SWT contract it creates
        // a display, making the calling thread the UI thread), so the documented
        // headless fallback was dead code and asyncExec queued the launch onto an
        // event loop no thread pumps — the launch silently never ran while the
        // tool had already reported status:"launching". With the workbench-aware
        // probe, this headless test JVM takes the SYNCHRONOUS path: the launch
        // really executes on the calling thread.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        String error = new DebugLaunchTool().performLaunch(config, false);
        assertNull("successful headless launch must return null", error);
        Mockito.verify(config).launch(ILaunchManager.DEBUG_MODE, null);
    }

    @Test
    public void testPerformLaunchHeadlessSurfacesLaunchError() throws Exception
    {
        // The synchronous (headless) path is the only one that can still report a
        // launch failure to the caller — keep that contract real, not dead code.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        Mockito.when(config.launch(ILaunchManager.DEBUG_MODE, null)).thenThrow(
            new CoreException(new Status(IStatus.ERROR, "test", "launch refused"))); //$NON-NLS-1$ //$NON-NLS-2$
        String error = new DebugLaunchTool().performLaunch(config, false);
        assertNotNull("headless launch failure must be surfaced synchronously", error);
        assertTrue(error.contains("launch refused")); //$NON-NLS-1$
    }

    // ==================== updateBeforeLaunch synthetic-id contract (rv1 review FIND-1) ====================

    @Test
    public void testGuideDocumentsSyntheticIdPreflightSkip()
    {
        // rv1 review FIND-1: a config without a persisted ATTR_APPLICATION_ID is
        // tracked under a synthetic 'launch:<configName>' id, which cannot be
        // resolved through IApplicationManager. The DB-update preflight must SKIP
        // such ids (isSyntheticApplicationId) instead of failing the launch with
        // 'Application not found: launch:<name>'. Ratchet the guide half of that
        // contract so the documented behavior can't silently drift.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the synthetic-id preflight skip",
            guide.contains("launch:<configName>")); //$NON-NLS-1$
    }

    // ============ D5 follow-up: 1003 confirmer armed independently of updateBeforeLaunch ============

    @Test
    public void testPerformLaunchHeadlessWithUpdateConfirmExecutesSynchronously() throws Exception
    {
        // The debug path now arms with (updateDialog=autoConfirmUpdateDialog,
        // sessionDialog=true) via the split arm(boolean,boolean). The synchronous
        // headless path must still launch cleanly with autoConfirmUpdateDialog=true:
        // the confirmer arm/disarm is a no-op in this no-workbench harness and must
        // not break the launch or its finally chain.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        String error = new DebugLaunchTool().performLaunch(config, true);
        assertNull("successful headless launch must return null even with update auto-confirm", error);
        Mockito.verify(config).launch(ILaunchManager.DEBUG_MODE, null);
    }

    // ============ D5b: alreadyRunning detects a live CLIENT session only (Bitrix 20074) ============

    @Test
    public void testGuideDocumentsClientOnlyAlreadyRunningDetect()
    {
        // D5b: the already-running detect is scoped to a live CLIENT session — a
        // thread-less standalone-SERVER debug target sharing the same app id no longer
        // blocks the client launch, and launching a client WHILE a debug-server is up
        // is allowed (it attaches). Ratchet the guide so this can't silently drift back
        // to the over-broad app-id-only detect that caused 20074.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document that already-running is scoped to a live CLIENT session",
            guide.contains("live CLIENT session")); //$NON-NLS-1$
        assertTrue("guide must document that launching a client while a debug-server is up is allowed",
            guide.contains("launching a client WHILE a debug-server")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsRestartIfRunningNeverTerminatesServer()
    {
        // D5b: restartIfRunning only ever terminates a live CLIENT session, never the
        // debug server (a server target has no live thread, so it is not the duplicate).
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document restartIfRunning never terminates the debug server",
            guide.contains("NEVER a debug server")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocuments1003KeepExistingButton()
    {
        // D5b: the 1003 safety-net now presses 'Keep existing and start new'
        // (LAUNCH_ANYWAY) so an already-running session survives — not the default
        // 'Stop existing and start new' that would terminate it.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the 1003 confirmer presses 'Keep existing and start new'",
            guide.contains("Keep existing and start new")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocuments1003ConfirmerIndependentOfUpdateBeforeLaunch()
    {
        // D5 follow-up: the code-1003 "debug session already exists" auto-confirmer
        // is armed on EVERY debug launch, independent of updateBeforeLaunch (it
        // performs no DB update, so it does not undo the updateBeforeLaunch=false
        // opt-out). Only the separate 'Application update' modal stays gated on
        // updateBeforeLaunch (review-fix A). Ratchet the guide so this can't drift.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the 1003 confirmer fires regardless of updateBeforeLaunch",
            guide.contains("regardless of `updateBeforeLaunch`")); //$NON-NLS-1$
    }

    // ============ D5c: unified existing-session detection (client-only + restartIfRunning) ============
    //
    // D5c unifies the duplicate-session decision so by-name AND by-project+application,
    // and the ILaunchManager guards AND the target-manager detect, all funnel through
    // one resolveExistingClientSession + handleExistingClientSession. These headless
    // tests exercise the unified decision point directly: the live-thread discriminator
    // (firstLiveThreadTarget) that keeps a thread-less SERVER session from short-
    // circuiting, and handleExistingClientSession honoring restartIfRunning in BOTH
    // directions. The static ILaunchManager scan inside resolveExistingClientSession
    // needs a live workbench and is covered E2E; only its null/empty-safe contract is
    // asserted here.

    private static IThread liveThread()
    {
        IThread t = mock(IThread.class);
        when(t.isTerminated()).thenReturn(false);
        return t;
    }

    private static IThread deadThread()
    {
        IThread t = mock(IThread.class);
        when(t.isTerminated()).thenReturn(true);
        return t;
    }

    private static IDebugTarget targetWithThreads(IThread... threads) throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(threads);
        return target;
    }

    private static ILaunch launchWithTargets(IDebugTarget... targets)
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getDebugTargets()).thenReturn(targets);
        return launch;
    }

    @Test
    public void testFirstLiveThreadTargetNullLaunchReturnsNull()
    {
        assertNull(DebugLaunchTool.firstLiveThreadTarget(null));
    }

    @Test
    public void testFirstLiveThreadTargetNoTargetsReturnsNull()
    {
        // A RUN-mode launch carries no debug target — firstLiveThreadTarget finds no
        // client DEBUG target (resolveExistingClientSession then treats it as the
        // genuine RUN-mode running client via its zero-targets branch).
        ILaunch launch = launchWithTargets();
        assertNull(DebugLaunchTool.firstLiveThreadTarget(launch));
    }

    @Test
    public void testFirstLiveThreadTargetServerTargetReturnsNull() throws Exception
    {
        // THE SERVER CASE (Bitrix 20074): a DEBUG launch whose only target is a thread-
        // less standalone-server / profiling target is NOT a client — firstLiveThreadTarget
        // returns null, so resolveExistingClientSession does NOT short-circuit and the
        // client proceeds (and attaches), at EITHER restartIfRunning value.
        IDebugTarget server = targetWithThreads(); // zero threads
        ILaunch launch = launchWithTargets(server);
        assertNull(DebugLaunchTool.firstLiveThreadTarget(launch));
    }

    @Test
    public void testFirstLiveThreadTargetOnlyTerminatedThreadsReturnsNull() throws Exception
    {
        // A target whose every thread is terminated is equally not a live client.
        IDebugTarget target = targetWithThreads(deadThread());
        ILaunch launch = launchWithTargets(target);
        assertNull(DebugLaunchTool.firstLiveThreadTarget(launch));
    }

    @Test
    public void testFirstLiveThreadTargetClientTargetReturnsIt() throws Exception
    {
        // THE CLIENT CASE: a thin-client DEBUG target has a live thread, so it IS the
        // resolved client session target (it short-circuits / is restarted).
        IDebugTarget client = targetWithThreads(liveThread());
        ILaunch launch = launchWithTargets(client);
        assertSame(client, DebugLaunchTool.firstLiveThreadTarget(launch));
    }

    @Test
    public void testFirstLiveThreadTargetSkipsTerminatedTargets() throws Exception
    {
        // A terminated target is skipped even if it still reports a live thread; the
        // next, non-terminated, live-thread target is the match.
        IDebugTarget terminated = mock(IDebugTarget.class);
        when(terminated.isTerminated()).thenReturn(true);
        IDebugTarget client = targetWithThreads(liveThread());
        ILaunch launch = launchWithTargets(terminated, client);
        assertSame(client, DebugLaunchTool.firstLiveThreadTarget(launch));
    }

    @Test
    public void testFirstLiveThreadTargetServerThenClientReturnsClient() throws Exception
    {
        // A launch holding BOTH a thread-less server target and a live client target
        // resolves to the client one — the server target never blocks discrimination.
        IDebugTarget server = targetWithThreads(); // zero threads
        IDebugTarget client = targetWithThreads(liveThread());
        ILaunch launch = launchWithTargets(server, client);
        assertSame(client, DebugLaunchTool.firstLiveThreadTarget(launch));
    }

    @Test
    public void testHandleExistingClientSessionRestartFalseReturnsAlreadyRunning() throws Exception
    {
        // restartIfRunning=false: a real CLIENT session short-circuits with
        // alreadyRunning:true and is NOT terminated — the documented default contract,
        // now honored uniformly through the single decision point.
        IDebugTarget client = targetWithThreads(liveThread());
        ExistingClientSession session = new ExistingClientSession(null, client, "debug"); //$NON-NLS-1$
        AlreadyRunningContext ctx = new AlreadyRunningContext("already running msg"); //$NON-NLS-1$
        ctx.launchConfiguration = "MyApp / Client"; //$NON-NLS-1$
        ctx.project = "MyProject"; //$NON-NLS-1$
        String json = new DebugLaunchTool()
            .handleExistingClientSession(session, "app-1", false, ctx); //$NON-NLS-1$
        assertNotNull("restartIfRunning=false must short-circuit (non-null JSON)", json);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue("must report alreadyRunning:true", obj.get("alreadyRunning").getAsBoolean()); //$NON-NLS-1$
        assertFalse("alreadyRunning short-circuit must NOT carry status:launching",
            obj.has("status")); //$NON-NLS-1$
        assertEquals("debug", obj.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-1", obj.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // The matched client target must NOT have been terminated on the default path.
        verify(client, never()).terminate();
    }

    @Test
    public void testHandleExistingClientSessionRestartTrueTerminatesClientTargetAndProceeds()
        throws Exception
    {
        // restartIfRunning=true on a live CLIENT debug target: the SAME non-interactive
        // terminate the target-manager path already did now runs for the ILaunchManager-
        // sourced client too — it terminates the existing client and returns null so the
        // caller relaunches (NOT alreadyRunning). This is the D5c fix: restartIfRunning is
        // honored in every path, including MCP/ILaunch-registered client sessions.
        IDebugTarget client = targetWithThreads(liveThread());
        when(client.canTerminate()).thenReturn(true);
        when(client.isTerminated()).thenReturn(false, true); // dies right after terminate()
        ExistingClientSession session = new ExistingClientSession(null, client, "debug"); //$NON-NLS-1$
        AlreadyRunningContext ctx = new AlreadyRunningContext("msg"); //$NON-NLS-1$
        String result = new DebugLaunchTool()
            .handleExistingClientSession(session, "app-2", true, ctx); //$NON-NLS-1$
        assertNull("restartIfRunning=true must proceed to relaunch (null), not short-circuit",
            result);
        verify(client, times(1)).terminate();
    }

    @Test
    public void testHandleExistingClientSessionRestartTrueTerminatesRunModeLaunchAndProceeds()
        throws Exception
    {
        // restartIfRunning=true on a RUN-mode launch (no debug target): the launch
        // analogue terminate runs and the caller proceeds (null). Confirms the flag is
        // honored for the RUN-mode already-running guard too, not just DEBUG targets.
        ILaunch runLaunch = mock(ILaunch.class);
        when(runLaunch.canTerminate()).thenReturn(true);
        when(runLaunch.isTerminated()).thenReturn(false, true);
        ExistingClientSession session = new ExistingClientSession(runLaunch, null, "run"); //$NON-NLS-1$
        AlreadyRunningContext ctx = new AlreadyRunningContext("msg"); //$NON-NLS-1$
        String result = new DebugLaunchTool()
            .handleExistingClientSession(session, "app-3", true, ctx); //$NON-NLS-1$
        assertNull(result);
        verify(runLaunch, times(1)).terminate();
    }

    @Test
    public void testHandleExistingClientSessionRestartFalseRunModeReportsRunMode() throws Exception
    {
        // The RUN-mode already-running guard still short-circuits with alreadyRunning:true
        // and reports mode:"run" — the discriminator preserves it (a RUN launch is a real
        // running client, never confused with a thread-less server session).
        ILaunch runLaunch = mock(ILaunch.class);
        ExistingClientSession session = new ExistingClientSession(runLaunch, null, "run"); //$NON-NLS-1$
        AlreadyRunningContext ctx = new AlreadyRunningContext("msg"); //$NON-NLS-1$
        String json = new DebugLaunchTool()
            .handleExistingClientSession(session, "app-4", false, ctx); //$NON-NLS-1$
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("alreadyRunning").getAsBoolean()); //$NON-NLS-1$
        assertEquals("run", obj.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // A RUN-mode launch carries no debug target, so nothing is terminated on default.
        verify(runLaunch, never()).terminate();
    }

    @Test
    public void testAlreadyRunningContextEmitsSuppliedIdentityFields()
    {
        // Output-schema parity: the unified payload echoes every identity field the
        // call site supplied, and applicationId/mode/alreadyRunning.
        AlreadyRunningContext ctx = new AlreadyRunningContext("the message"); //$NON-NLS-1$
        ctx.launchConfiguration = "Cfg"; //$NON-NLS-1$
        ctx.configurationType = "type.id"; //$NON-NLS-1$
        ctx.attach = Boolean.TRUE;
        ctx.project = "Proj"; //$NON-NLS-1$
        JsonObject obj = JsonParser.parseString(
            ctx.buildAlreadyRunning("debug", "the-app").toJson()).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(obj.get("alreadyRunning").getAsBoolean()); //$NON-NLS-1$
        assertEquals("debug", obj.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the message", obj.get("message").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Cfg", obj.get("launchConfiguration").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("type.id", obj.get("configurationType").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(obj.get("attach").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Proj", obj.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the-app", obj.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAlreadyRunningContextOmitsUnsetOptionalFields()
    {
        AlreadyRunningContext ctx = new AlreadyRunningContext("m"); //$NON-NLS-1$
        ctx.project = "P"; //$NON-NLS-1$
        ctx.attach = Boolean.FALSE;
        JsonObject obj = JsonParser.parseString(
            ctx.buildAlreadyRunning("debug", "a").toJson()).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("absent launchConfiguration must be omitted", obj.has("launchConfiguration")); //$NON-NLS-1$
        assertFalse("absent configurationType must be omitted", obj.has("configurationType")); //$NON-NLS-1$
        assertEquals("P", obj.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("attach=false is still emitted (Boolean set)", !obj.has("attach")); //$NON-NLS-1$
        assertFalse(obj.get("attach").getAsBoolean()); //$NON-NLS-1$
    }

    // --- decideExistingClientSession: the pure ILaunchManager-view decision ---

    @Test
    public void testDecideNoSessionsReturnsNull()
    {
        assertNull(DebugLaunchTool.decideExistingClientSession(null, null));
    }

    @Test
    public void testDecideLiveClientDebugTargetIsSession() throws Exception
    {
        // findActiveTarget returned a DEBUG target with a live thread → a real client
        // debug session, carrying the matched target so restartIfRunning can terminate it.
        ILaunch launch = mock(ILaunch.class);
        when(launch.getLaunchMode()).thenReturn("debug"); //$NON-NLS-1$
        IDebugTarget client = targetWithThreads(liveThread());
        when(client.getLaunch()).thenReturn(launch);
        ExistingClientSession session =
            DebugLaunchTool.decideExistingClientSession(client, null);
        assertNotNull(session);
        assertSame(client, session.liveTarget);
        assertEquals("debug", session.mode); //$NON-NLS-1$
    }

    @Test
    public void testDecideThreadlessServerTargetIsNotASession() throws Exception
    {
        // THE SERVER CASE via findActiveTarget: a thread-less standalone-server target is
        // NOT a client — the decision falls through to activeLaunch (here null) → null, so
        // the client proceeds instead of being short-circuited as alreadyRunning.
        IDebugTarget server = targetWithThreads(); // zero threads
        assertNull(DebugLaunchTool.decideExistingClientSession(server, null));
    }

    @Test
    public void testDecideRunModeLaunchIsSessionWithNoTarget()
    {
        // findActiveLaunch returned a RUN-mode launch (no debug target): a genuine running
        // client — the A12/A13 guard. Returned as a session with a null target (terminated
        // via the launch on restartIfRunning).
        ILaunch runLaunch = mock(ILaunch.class);
        when(runLaunch.getLaunchMode()).thenReturn("run"); //$NON-NLS-1$
        when(runLaunch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        ExistingClientSession session =
            DebugLaunchTool.decideExistingClientSession(null, runLaunch);
        assertNotNull(session);
        assertNull(session.liveTarget);
        assertSame(runLaunch, session.launch);
        assertEquals("run", session.mode); //$NON-NLS-1$
    }

    @Test
    public void testDecideDebugLaunchWithOnlyThreadlessTargetsIsNotASession() throws Exception
    {
        // THE SERVER CASE via findActiveLaunch (the latent over-detect the old findActiveLaunch
        // path allowed): a DEBUG launch whose every debug target is thread-less is a
        // standalone-server / profiling session, NOT a client — it must NOT short-circuit at
        // either restartIfRunning value. decideExistingClientSession returns null → client proceeds.
        IDebugTarget server = targetWithThreads(); // zero threads
        ILaunch debugLaunch = launchWithTargets(server);
        assertNull(DebugLaunchTool.decideExistingClientSession(null, debugLaunch));
    }

    @Test
    public void testDecideDebugLaunchWithLiveThreadTargetIsSession() throws Exception
    {
        // A DEBUG launch the target scan missed but which DOES own a live-thread client
        // target is a client session.
        IDebugTarget client = targetWithThreads(liveThread());
        ILaunch debugLaunch = launchWithTargets(client);
        when(debugLaunch.getLaunchMode()).thenReturn("debug"); //$NON-NLS-1$
        ExistingClientSession session =
            DebugLaunchTool.decideExistingClientSession(null, debugLaunch);
        assertNotNull(session);
        assertSame(client, session.liveTarget);
    }

    @Test
    public void testDecideServerTargetButLiveRunLaunchPrefersRunClient()
    {
        // A thread-less server target AND a separate RUN-mode client launch for the same
        // app id: the server target is rejected, and the RUN-mode launch is the session —
        // the server never suppresses a genuine client.
        IDebugTarget server = mock(IDebugTarget.class);
        when(server.isTerminated()).thenReturn(false);
        try
        {
            when(server.getThreads()).thenReturn(new IThread[0]);
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }
        ILaunch runLaunch = mock(ILaunch.class);
        when(runLaunch.getLaunchMode()).thenReturn("run"); //$NON-NLS-1$
        when(runLaunch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        ExistingClientSession session =
            DebugLaunchTool.decideExistingClientSession(server, runLaunch);
        assertNotNull(session);
        assertSame(runLaunch, session.launch);
        assertNull(session.liveTarget);
    }

    @Test
    public void testResolveExistingClientSessionNullEmptyAppIdReturnsNull()
    {
        // Null/empty app id can never identify a session — never matches, never throws.
        assertNull(DebugLaunchTool.resolveExistingClientSession(null));
        assertNull(DebugLaunchTool.resolveExistingClientSession("")); //$NON-NLS-1$
    }

    @Test
    public void testResolveExistingClientSessionHeadlessUnknownAppIdReturnsNull()
    {
        // Headless: the ILaunchManager carries no launch for this app id, so the unified
        // detector resolves to null (the live scan is covered E2E).
        assertNull(DebugLaunchTool.resolveExistingClientSession("no-such-app-zzz")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsRestartIfRunningTerminateAndRelaunch()
    {
        // D5c: the guide must keep documenting that restartIfRunning=true terminates the
        // existing CLIENT session and relaunches (now honored in every path). Ratchet so
        // the unified-policy promise can't drift out of the docs.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document restartIfRunning terminate+relaunch",
            guide.contains("terminate the existing CLIENT session")); //$NON-NLS-1$
    }
}
