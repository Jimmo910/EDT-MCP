/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Auto-confirms EDT's blocking <em>"Application update"</em> launch modal, but
 * ONLY while one of the YAXUnit tools is spawning a launch via
 * {@code workingCopy.launch()}.
 *
 * <h2>Why this is needed</h2>
 * When a launch configuration's infobase is not byte-for-byte equal to the
 * project, EDT's runtime launch delegate routes through
 * {@code ApplicationUpdateStatusHandler} (status code {@code 1006}) which calls
 * {@code IApplicationUiSupport.ensureUpdated}. If
 * {@code IApplicationManager.getUpdateState(application)} is anything other than
 * {@code UPDATED}, that method pops an <b>application-modal</b> dialog titled
 * "Application update" with the choices "Update then run" / "Run without update"
 * / "Cancel" and blocks the launch thread until a human answers it.
 *
 * <p>For YAXUnit runs the blocker is structural: the dependent <em>test
 * extension</em> reports {@code INCREMENTAL_UPDATE_REQUIRED}, which
 * {@code InfobaseApplicationProvisionDelegate.getUpdateState} propagates to the
 * whole application. A plain {@code IApplicationManager.update} (the same path
 * as {@code update_database} and the EDT "Update then run" button) publishes the
 * configuration but does <b>not</b> durably bring the extension to {@code EQUAL}
 * — the state reverts to {@code INCREMENTAL_UPDATE_REQUIRED} immediately — so the
 * modal returns on every launch and there is no launch-config attribute or
 * preference to suppress it. The MCP call then hangs until the user clicks
 * through, which defeats unattended runs.
 *
 * <h2>What it does</h2>
 * While armed, a {@link Display} filter watches for the activation of a shell
 * whose title is exactly {@link #APPLICATION_UPDATE_TITLE} and programmatically
 * presses its <em>default</em> button ("Update then run", the same choice a
 * careful user would pick), letting the launch proceed without human input. The
 * preceding pre-launch DB update (see {@code LaunchLifecycleUtils}) has already
 * published the configuration, so the auto-pressed update is a fast no-op and
 * does not cascade into a second structural-changes dialog.
 *
 * <h2>Scope &amp; safety</h2>
 * <ul>
 *   <li>The filter is installed only between {@link #arm()} and {@link #disarm()}
 *       (use try/finally around the single {@code launch()} call), so manual EDT
 *       launches outside an MCP tool still prompt normally.</li>
 *   <li>{@link #arm()}/{@link #disarm()} are reentrant via a counter; concurrent
 *       YAXUnit launches share one filter and the last {@code disarm()} removes
 *       it.</li>
 *   <li>Only the exact "Application update" title — in either of EDT's two
 *       shipped locales (English / Russian) — is matched, so unrelated dialogs
 *       that happen to appear during the window are left untouched.</li>
 *   <li>Headless (no running workbench, hence no pumped {@link Display}) is a
 *       no-op — no dialog can appear there anyway, and the probe never CREATES
 *       a display (see {@link #safeDisplay()}).</li>
 * </ul>
 *
 * <h2>Locale</h2>
 * The modal title is the localized {@code ApplicationUiSupport_Application_update}
 * string. EDT ships exactly two NL variants of the {@code com.e1c.g5.dt.applications.ui}
 * bundle — English ("Application update") and Russian ("Обновление приложения") —
 * so the filter matches BOTH. An English-only match (the previous behaviour)
 * silently fails on a Russian-locale stand: the unattended launch then hangs on
 * the un-dismissed modal (Bitrix 20074). The default button is the same choice in
 * both locales ("Update then run" / "Обновить и запустить", button index 0), so
 * {@link #pressDefaultButton(Shell)} stays locale-agnostic.
 */
public final class LaunchUpdateDialogAutoConfirmer
{
    /**
     * English title of EDT's launch-delegate "update infobase before launch?"
     * modal ({@code ApplicationUiSupport_Application_update}).
     */
    static final String APPLICATION_UPDATE_TITLE = "Application update"; //$NON-NLS-1$

    /**
     * Russian title of the same modal ({@code messages_ru.properties}:
     * "Обновление приложения"). The target stand that hit Bitrix 20074 runs a
     * Russian EDT, where the English-only match never fired. Kept as a
     * {@code \\uXXXX}-escaped literal (copied verbatim from EDT's own
     * {@code messages_ru.properties}) so it compiles identically regardless of the
     * source-file encoding the Tycho compiler picks up.
     */
    static final String APPLICATION_UPDATE_TITLE_RU =
        "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u0438\u0435 \u043F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u044F"; //$NON-NLS-1$

    /**
     * Every shipped localized title of the "Application update" modal. EDT ships
     * only the English and Russian NL variants of
     * {@code com.e1c.g5.dt.applications.ui}, so this set is exhaustive; matching is
     * still an exact, whole-title compare so no unrelated dialog is touched.
     */
    static final Set<String> APPLICATION_UPDATE_TITLES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(APPLICATION_UPDATE_TITLE, APPLICATION_UPDATE_TITLE_RU)));

    /**
     * English message-body prefix of EDT's "Debug session already exists" launch
     * modal (status code {@code 1003}, handler {@code DebugSessionCheckStatusHandler}).
     * The full text is "Debug session for project \"{0}\" and application \"{1}\" has
     * already been started.\nShould it be stopped?" — we match only the stable leading
     * prefix so the two interpolated names don't break the comparison (Bitrix 20074).
     */
    static final String DEBUG_SESSION_EXISTS_BODY_PREFIX = "Debug session for project"; //$NON-NLS-1$

    /**
     * Russian message-body prefix of the same modal (decodes to
     * "Сессия отладки для проекта"). Kept {@code \\uXXXX}-escaped (no raw Cyrillic in
     * source) so it compiles identically whatever encoding the Tycho compiler picks.
     */
    static final String DEBUG_SESSION_EXISTS_BODY_PREFIX_RU =
        "\u0421\u0435\u0441\u0441\u0438\u044F \u043E\u0442\u043B\u0430\u0434\u043A\u0438 " //$NON-NLS-1$
            + "\u0434\u043B\u044F \u043F\u0440\u043E\u0435\u043A\u0442\u0430"; //$NON-NLS-1$

    /**
     * Every shipped localized message-body prefix of the "Debug session already
     * exists" code-1003 modal. The shell TITLE is the generic "Question"/"Вопрос",
     * which would catch every question dialog — so this modal is matched on the
     * BODY prefix instead.
     */
    static final Set<String> DEBUG_SESSION_EXISTS_BODY_PREFIXES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            DEBUG_SESSION_EXISTS_BODY_PREFIX, DEBUG_SESSION_EXISTS_BODY_PREFIX_RU)));

    /** Cap on the widget-tree walk depth when reading a dialog's message body. */
    private static final int MAX_BODY_SCAN_DEPTH = 6;

    private static final Object LOCK = new Object();

    private static int armCount;
    private static Display filterDisplay;
    private static Listener filter;

    private LaunchUpdateDialogAutoConfirmer()
    {
        // Utility class
    }

    /**
     * Pure decision used by the {@link Display} filter (and by tests): is the
     * given shell title the "Application update" modal we auto-confirm, in any of
     * EDT's shipped locales (English / Russian)?
     */
    static boolean isTargetTitle(String shellTitle)
    {
        return shellTitle != null && APPLICATION_UPDATE_TITLES.contains(shellTitle);
    }

    /**
     * Pure decision (and test seam): is the given dialog message BODY the
     * "Debug session already exists" code-1003 modal? The modal's shell title is the
     * generic "Question"/"Вопрос", so it is matched on the localized body PREFIX
     * (the two interpolated project/application names follow it) — never on the
     * generic title, which would catch every question dialog (Bitrix 20074).
     *
     * @param body a dialog message-body string (may be {@code null})
     * @return {@code true} when {@code body} starts with a known 1003 body prefix
     */
    static boolean isDebugSessionExistsBody(String body)
    {
        if (body == null)
        {
            return false;
        }
        for (String prefix : DEBUG_SESSION_EXISTS_BODY_PREFIXES)
        {
            if (body.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Arms the auto-confirmer. MUST be paired with {@link #disarm()} in a
     * {@code finally} block around the {@code workingCopy.launch()} call.
     * Reentrant: nested/concurrent launches share a single filter.
     *
     * <p>No-op in a headless environment (no SWT display). Never throws — a
     * display disposed mid-call (workbench shutdown) is swallowed, so a launch
     * {@code finally} chain is never broken by the confirmer itself.
     *
     * <p>Threading: only the arm counter is touched under {@code LOCK}; the
     * filter (un)install is marshalled to the UI thread OUTSIDE the monitor.
     * Blocking on {@link Display#syncExec} while holding {@code LOCK} would
     * deadlock: an MCP worker would wait for the UI thread while the UI thread
     * (running another tool's launch lambda) waits for {@code LOCK}.
     */
    public static void arm()
    {
        Display display = safeDisplay();
        if (display == null)
        {
            return;
        }
        synchronized (LOCK)
        {
            armCount++;
        }
        reconcileOnUiThread(display);
    }

    /**
     * Disarms the auto-confirmer. The underlying {@link Display} filter is
     * removed only once the last paired {@link #arm()} has been released.
     *
     * <p>Never throws (see {@link #arm()}): callers invoke this from
     * {@code finally} blocks, where an exception would mask the original
     * launch failure.
     */
    public static void disarm()
    {
        Display display;
        synchronized (LOCK)
        {
            if (armCount > 0)
            {
                armCount--;
            }
            display = filterDisplay;
        }
        if (display == null)
        {
            // No filter was ever installed (headless no-op arm, or a concurrent
            // arm() whose UI-thread install has not run yet — that install then
            // sees the decremented counter and is skipped).
            return;
        }
        reconcileOnUiThread(display);
    }

    /**
     * Marshals {@link #reconcileFilter(Display)} to the UI thread. Called
     * WITHOUT holding {@code LOCK} (the blocking {@code syncExec} under the
     * monitor was a deadlock, R1). Never throws: a display disposed between
     * the check and the {@code syncExec} (workbench shutdown race) is benign —
     * the filter dies with the display and the counter stays consistent.
     */
    private static void reconcileOnUiThread(Display display)
    {
        if (display.isDisposed())
        {
            return;
        }
        try
        {
            display.syncExec(() -> reconcileFilter(display));
        }
        catch (SWTException e)
        {
            // ERROR_DEVICE_DISPOSED race on shutdown — nothing to (un)install.
        }
    }

    /**
     * Brings the installed {@link Display} filter in line with the current arm
     * count. Runs on the UI thread only; takes {@code LOCK} just for the state
     * decision (never blocks inside the monitor), then performs the actual
     * {@code addFilter}/{@code removeFilter} outside it. Because every install
     * and removal funnels through here on the UI thread against the live
     * counter, a concurrent arm/disarm pair can never leave a filter installed
     * with no armed owner (or vice versa).
     */
    private static void reconcileFilter(Display display)
    {
        Listener toInstall = null;
        Listener toRemove = null;
        synchronized (LOCK)
        {
            // A filter whose display died with the workbench is already gone —
            // drop the stale reference so a future arm() can reinstall.
            if (filter != null && (filterDisplay == null || filterDisplay.isDisposed()))
            {
                filter = null;
                filterDisplay = null;
            }
            if (armCount > 0 && filter == null)
            {
                toInstall = createFilterListener();
                filter = toInstall;
                filterDisplay = display;
            }
            else if (armCount == 0 && filter != null)
            {
                toRemove = filter;
                filter = null;
                filterDisplay = null;
            }
        }
        if (toInstall != null)
        {
            display.addFilter(SWT.Activate, toInstall);
            display.addFilter(SWT.Show, toInstall);
        }
        if (toRemove != null)
        {
            display.removeFilter(SWT.Activate, toRemove);
            display.removeFilter(SWT.Show, toRemove);
        }
    }

    /**
     * Creates the {@link Display} filter that watches for the modals we auto-confirm
     * and schedules the default-button auto-press. Two matchers share this single
     * filter (one global filter, reconciled under {@code LOCK} — no second filter,
     * no deadlock):
     * <ul>
     *   <li>the D4 "Application update" modal — matched on the exact shell TITLE
     *       ({@link #isTargetTitle});</li>
     *   <li>the code-1003 "Debug session already exists" modal — matched on the
     *       message BODY prefix ({@link #isDebugSessionExistsBody}), because its
     *       shell title is the generic "Question"/"Вопрос" (Bitrix 20074).</li>
     * </ul>
     * Pressing the default button (index 0) of either dialog completes it
     * non-interactively: "Update then run" for the update modal, "Stop existing and
     * start new" for the 1003 modal — and we only reach config.launch (where this
     * filter is armed) after primary detection already decided to launch, so
     * stopping the old session honors that intent.
     */
    private static Listener createFilterListener()
    {
        return event -> {
            if (!(event.widget instanceof Shell))
            {
                return;
            }
            Shell shell = (Shell)event.widget;
            String title;
            try
            {
                title = shell.getText();
            }
            catch (RuntimeException e)
            {
                return;
            }
            boolean matches = isTargetTitle(title);
            if (!matches)
            {
                // Title is NOT the update modal — try the 1003 body matcher. The
                // generic "Question" title can't be matched (it would dismiss every
                // question dialog), so read the dialog's message body instead.
                matches = isDebugSessionExistsBody(readDialogBody(shell));
            }
            if (!matches)
            {
                return;
            }
            // Defer so the modal finishes building its button bar and enters
            // its event loop; the press then runs inside that loop.
            shell.getDisplay().asyncExec(() -> pressDefaultButton(shell));
        };
    }

    /**
     * Reads the message-body text of a JFace dialog shell by walking its widget
     * tree and returning the first non-blank {@link Label}/{@link CLabel}/
     * {@link Text}/{@link Link} text that matches a known 1003 body prefix — or, if
     * none matches, the first non-blank label-like text found. JFace
     * {@code MessageDialog} renders its message in such a control inside the dialog
     * area; the shell title alone is too generic to key on. Bounded depth and fully
     * guarded — never throws onto the UI thread.
     *
     * @param shell the dialog shell (may be {@code null}/disposed)
     * @return a candidate message-body string, or {@code null} if none was found
     */
    static String readDialogBody(Shell shell)
    {
        if (shell == null || shell.isDisposed())
        {
            return null;
        }
        try
        {
            return findBodyText(shell, 0);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Depth-bounded pre-order walk of a control tree collecting label-like text.
     * Returns the first text that already matches a 1003 prefix (so the caller's
     * decision is unambiguous); failing that, the first non-blank label-like text it
     * sees (a best-effort fallback that the prefix check then rejects for unrelated
     * dialogs).
     */
    private static String findBodyText(Control control, int depth)
    {
        if (control == null || control.isDisposed() || depth > MAX_BODY_SCAN_DEPTH)
        {
            return null;
        }
        String firstSeen = null;
        String text = labelLikeText(control);
        if (text != null && !text.trim().isEmpty())
        {
            if (isDebugSessionExistsBody(text))
            {
                return text;
            }
            firstSeen = text;
        }
        if (control instanceof Composite)
        {
            for (Control child : ((Composite)control).getChildren())
            {
                String childText = findBodyText(child, depth + 1);
                if (childText != null)
                {
                    if (isDebugSessionExistsBody(childText))
                    {
                        return childText;
                    }
                    if (firstSeen == null)
                    {
                        firstSeen = childText;
                    }
                }
            }
        }
        return firstSeen;
    }

    /** @return the text of a {@link Label}/{@link CLabel}/{@link Text}/{@link Link}, else {@code null}. */
    private static String labelLikeText(Control control)
    {
        try
        {
            if (control instanceof Label)
            {
                return ((Label)control).getText();
            }
            if (control instanceof CLabel)
            {
                return ((CLabel)control).getText();
            }
            if (control instanceof Text)
            {
                return ((Text)control).getText();
            }
            if (control instanceof Link)
            {
                return ((Link)control).getText();
            }
        }
        catch (RuntimeException e)
        {
            return null;
        }
        return null;
    }

    /**
     * Presses the default (index 0) button of the given dialog shell — "Update then
     * run" for the D4 update modal, or "Stop existing and start new" /
     * "Завершить старую и запустить новую" for the code-1003 "Debug session already
     * exists" modal. Guarded against disposal and never throws onto the UI thread.
     */
    private static void pressDefaultButton(Shell shell)
    {
        try
        {
            if (shell == null || shell.isDisposed())
            {
                return;
            }
            Button button = shell.getDefaultButton();
            if (button == null || button.isDisposed())
            {
                return;
            }
            // Distinguish the two modals in the log so an unattended run's trail shows
            // exactly which dialog was auto-completed (Bitrix 20074).
            boolean debugSessionDialog = isDebugSessionExistsBody(readDialogBody(shell));
            if (debugSessionDialog)
            {
                Activator.logInfo("Auto-confirming debug-session dialog '" //$NON-NLS-1$
                    + safeShellText(shell) + "' via button '" + safeText(button) //$NON-NLS-1$
                    + "' (stop existing and start new)"); //$NON-NLS-1$
            }
            else
            {
                Activator.logInfo("Auto-confirming launch dialog '" + safeShellText(shell) //$NON-NLS-1$
                    + "' via button '" + safeText(button) + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Event event = new Event();
            event.widget = button;
            // Mirrors a user click: JFace dialog buttons fire buttonPressed() on
            // SWT.Selection, which sets the return code and closes the dialog.
            button.notifyListeners(SWT.Selection, event);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to auto-confirm the launch update dialog", e); //$NON-NLS-1$
        }
    }

    private static String safeText(Button button)
    {
        try
        {
            return button.getText();
        }
        catch (RuntimeException e)
        {
            return "<unknown>"; //$NON-NLS-1$
        }
    }

    private static String safeShellText(Shell shell)
    {
        try
        {
            return shell.getText();
        }
        catch (RuntimeException e)
        {
            return "<unknown>"; //$NON-NLS-1$
        }
    }

    /**
     * Returns the workbench {@link Display} or {@code null} when no workbench is
     * running (headless CI / EDT CLI), via
     * {@link LaunchLifecycleUtils#workbenchDisplayOrNull()} — the probe NEVER
     * creates a display.
     *
     * <p>The previous {@code Display.getDefault()} probe did exactly that on the
     * headless synchronous launch path: the first {@link #arm()} created a stray
     * display owned by its MCP worker thread, and a later {@code arm()} /
     * {@code disarm()} from a different worker would then {@code syncExec} onto
     * that never-pumped display and hang forever (rv1 review follow-up).
     */
    private static Display safeDisplay()
    {
        Display display = LaunchLifecycleUtils.workbenchDisplayOrNull();
        return display != null && !display.isDisposed() ? display : null;
    }
}
