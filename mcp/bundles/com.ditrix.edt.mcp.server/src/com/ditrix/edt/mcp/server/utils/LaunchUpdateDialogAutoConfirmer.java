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
import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

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
 *   <li>Headless (no SWT {@link Display}) is a no-op — no dialog can appear
 *       there anyway.</li>
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
     * Arms the auto-confirmer. MUST be paired with {@link #disarm()} in a
     * {@code finally} block around the {@code workingCopy.launch()} call.
     * Reentrant: nested/concurrent launches share a single filter.
     *
     * <p>No-op in a headless environment (no SWT display).
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
            if (filter != null)
            {
                return;
            }
            Listener listener = event -> {
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
                if (!isTargetTitle(title))
                {
                    return;
                }
                // Defer so the modal finishes building its button bar and enters
                // its event loop; the press then runs inside that loop.
                shell.getDisplay().asyncExec(() -> pressDefaultButton(shell));
            };
            // Display filters must be (un)installed on the UI thread.
            display.syncExec(() -> {
                display.addFilter(SWT.Activate, listener);
                display.addFilter(SWT.Show, listener);
            });
            filter = listener;
            filterDisplay = display;
        }
    }

    /**
     * Disarms the auto-confirmer. The underlying {@link Display} filter is
     * removed only once the last paired {@link #arm()} has been released.
     */
    public static void disarm()
    {
        Listener toRemove;
        Display display;
        synchronized (LOCK)
        {
            if (armCount > 0)
            {
                armCount--;
            }
            if (armCount > 0 || filter == null)
            {
                return;
            }
            toRemove = filter;
            display = filterDisplay;
            filter = null;
            filterDisplay = null;
        }
        if (display != null && !display.isDisposed())
        {
            display.syncExec(() -> {
                display.removeFilter(SWT.Activate, toRemove);
                display.removeFilter(SWT.Show, toRemove);
            });
        }
    }

    /**
     * Presses the default ("Update then run") button of the given dialog shell.
     * Guarded against disposal and never throws onto the UI thread.
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
            Activator.logInfo("Auto-confirming launch dialog '" + safeShellText(shell) //$NON-NLS-1$
                + "' via button '" + safeText(button) + "'"); //$NON-NLS-1$ //$NON-NLS-2$
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
     * Returns the default {@link Display} or {@code null} when SWT cannot be
     * initialised (headless CI / no UI), mirroring
     * {@code LaunchLifecycleUtils.grabActiveShell}.
     */
    private static Display safeDisplay()
    {
        try
        {
            Display display = Display.getDefault();
            return display != null && !display.isDisposed() ? display : null;
        }
        catch (SWTError | UnsatisfiedLinkError e)
        {
            return null;
        }
    }
}
