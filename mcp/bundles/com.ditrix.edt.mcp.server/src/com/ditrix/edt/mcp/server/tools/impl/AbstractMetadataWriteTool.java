/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.e1c.g5.v8.dt.check.ICheckScheduler;

/**
 * Base class for metadata write tools that mutate the EDT model
 * (create / add / delete) and therefore must run on the UI thread.
 * <p>
 * Centralizes the boilerplate shared by all such tools:
 * <ul>
 * <li>JSON response type;</li>
 * <li>marshalling the call onto the SWT UI thread via {@link Display#syncExec}
 * with unified error handling (logs and returns a {@link ToolResult} error);</li>
 * <li>resolving the {@link IProject} and its {@link Configuration};</li>
 * <li>unwrapping the underlying cause message thrown from a BM write task.</li>
 * </ul>
 * Subclasses implement {@link #executeOnUiThread(Map)}, which is already invoked
 * on the UI thread.
 */
public abstract class AbstractMetadataWriteTool implements IMcpTool
{
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public final String execute(Map<String, String> params)
    {
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(executeOnUiThread(params));
            }
            catch (Exception e)
            {
                Activator.logError("Error in " + getName(), e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    /**
     * Performs the tool logic. Always invoked on the SWT UI thread, so model
     * mutations are safe here. Any thrown exception is logged and converted to a
     * {@link ToolResult} error by {@link #execute(Map)}.
     *
     * @param params the tool parameters
     * @return the JSON result string
     * @throws Exception on unexpected failure
     */
    protected abstract String executeOnUiThread(Map<String, String> params) throws Exception;

    /**
     * Holds the resolved project and configuration, or a ready-to-return JSON
     * error string when resolution failed.
     */
    protected static final class ProjectContext
    {
        /** Resolved project; non-null only when {@link #error} is null. */
        public IProject project;
        /** Resolved configuration; non-null only when {@link #error} is null. */
        public Configuration config;
        /** Non-null when resolution failed: a JSON error to return verbatim. */
        public String error;

        boolean hasError()
        {
            return error != null;
        }
    }

    /**
     * Resolves the EDT project and its configuration, applying the same
     * validation and error messages used across the metadata write tools.
     *
     * @param projectName the project name from the tool parameters
     * @return a {@link ProjectContext}; check {@link ProjectContext#error} first
     */
    protected ProjectContext resolveProjectAndConfig(String projectName)
    {
        ProjectContext ctx = new ProjectContext();

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            ctx.error = ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
            return ctx;
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            ctx.error = ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
            return ctx;
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            ctx.error = ToolResult.error("Could not get configuration for project: " + projectName).toJson(); //$NON-NLS-1$
            return ctx;
        }

        ctx.project = project;
        ctx.config = config;
        return ctx;
    }

    /**
     * Returns the most specific failure message from an exception thrown by a BM
     * write task: the cause message when present, otherwise the exception's own.
     *
     * @param e the caught exception
     * @return the resolved message
     */
    protected static String unwrapCauseMessage(Exception e)
    {
        String msg = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null)
        {
            msg = e.getCause().getMessage();
        }
        return msg;
    }

    /**
     * Returns the BM top-object FQN under which a model object's {@code .mdo} file is
     * registered, or {@code null} when it cannot be derived.
     * <p>
     * For a top-level object ({@code Catalog}, {@code Document}, register, …) this is
     * the object's own FQN. For a child (an attribute, dimension or resource) this
     * resolves to the FQN of the owning top object - editing a child re-exports the
     * owner's whole {@code .mdo}, so the persist/revalidate must target the owner.
     *
     * @param object the model object (a top object or a contained child)
     * @return the owning top-object FQN, or {@code null} when {@code object} is not a
     *         BM object or has no top object
     */
    protected static String topObjectFqnOf(Object object)
    {
        if (!(object instanceof IBmObject))
        {
            return null;
        }
        IBmObject bmObject = (IBmObject)object;
        IBmObject topObject = bmObject.bmGetTopObject();
        if (topObject != null)
        {
            return topObject.bmGetFqn();
        }
        return bmObject.bmGetFqn();
    }

    /**
     * The outcome of resolving the language code for a localized value: either a
     * usable code or a clear, user-facing error message.
     */
    protected static final class LanguageResolution
    {
        /** The resolved language code; {@code null} when {@link #error} is set. */
        public final String code;
        /** A user-facing error; {@code null} when {@link #code} is set. */
        public final String error;

        private LanguageResolution(String code, String error)
        {
            this.code = code;
            this.error = error;
        }

        /** @return {@code true} when resolution failed and {@link #error} is set */
        public boolean hasError()
        {
            return error != null;
        }

        static LanguageResolution ok(String code)
        {
            return new LanguageResolution(code, null);
        }

        static LanguageResolution failed(String error)
        {
            return new LanguageResolution(null, error);
        }
    }

    /**
     * Resolves the language code for a localized value (synonym, presentation, ...).
     * <p>
     * An explicit {@code language} wins, but it is first <b>validated against the
     * configured Languages</b>: writing a synonym under a language code that the
     * configuration does not declare stores it under a key EDT never reads, so the
     * value is silently invisible in the editor. When {@code language} is not
     * configured this returns an error rather than accepting it.
     * <p>
     * When {@code language} is {@code null}/blank, the configuration default language
     * code is used, otherwise the first configured language code. The synonym map is
     * keyed by the language CODE (e.g. {@code "en"}, {@code "ru"}), not the Language
     * object's name.
     *
     * @param config the project configuration
     * @param language the requested language code, or {@code null} to use the default
     * @return a {@link LanguageResolution}; check {@link LanguageResolution#hasError()}
     */
    protected static LanguageResolution resolveLanguage(Configuration config, String language)
    {
        if (language != null && !language.isEmpty())
        {
            // Validate against the configured Languages: an unconfigured code would
            // be written to a map key EDT never looks up (silently invisible).
            for (Language lang : config.getLanguages())
            {
                if (lang != null && language.equals(lang.getLanguageCode()))
                {
                    return LanguageResolution.ok(language);
                }
            }
            return LanguageResolution.failed("Language code '" + language //$NON-NLS-1$
                + "' is not configured in this configuration. Configured languages: " //$NON-NLS-1$
                + configuredLanguageCodes(config) + ". Use one of those codes, or omit " //$NON-NLS-1$
                + "'language' to use the configuration default language."); //$NON-NLS-1$
        }
        Language defaultLanguage = config.getDefaultLanguage();
        if (defaultLanguage != null
            && defaultLanguage.getLanguageCode() != null
            && !defaultLanguage.getLanguageCode().isEmpty())
        {
            return LanguageResolution.ok(defaultLanguage.getLanguageCode());
        }
        // No default language: use the first configured language code instead of a
        // hardcoded "ru", which would be wrong for non-Russian configurations.
        for (Language lang : config.getLanguages())
        {
            if (lang != null && lang.getLanguageCode() != null && !lang.getLanguageCode().isEmpty())
            {
                return LanguageResolution.ok(lang.getLanguageCode());
            }
        }
        return LanguageResolution.failed("Cannot determine a language code for the localized value " //$NON-NLS-1$
            + "in this configuration. Specify 'language' explicitly (e.g. 'en' or 'ru')."); //$NON-NLS-1$
    }

    /** Lists the configured language codes for an error message. */
    private static String configuredLanguageCodes(Configuration config)
    {
        StringBuilder sb = new StringBuilder();
        for (Language lang : config.getLanguages())
        {
            if (lang != null && lang.getLanguageCode() != null && !lang.getLanguageCode().isEmpty())
            {
                if (sb.length() > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append('\'').append(lang.getLanguageCode()).append('\'');
            }
        }
        return sb.length() > 0 ? sb.toString() : "(none)"; //$NON-NLS-1$
    }

    /**
     * The outcome of resolving a top-level metadata object by FQN: either the object
     * or a ready-to-return JSON error string.
     */
    protected static final class ObjectResolution
    {
        /** The resolved object; non-null only when {@link #error} is null. */
        public final MdObject object;
        /** The normalized FQN that was resolved (non-null on success). */
        public final String normalizedFqn;
        /** Non-null when resolution failed: a JSON error to return verbatim. */
        public final String error;

        private ObjectResolution(MdObject object, String normalizedFqn, String error)
        {
            this.object = object;
            this.normalizedFqn = normalizedFqn;
            this.error = error;
        }

        /** @return {@code true} when resolution failed and {@link #error} is set */
        public boolean hasError()
        {
            return error != null;
        }
    }

    /**
     * Resolves a top-level metadata object from an FQN: normalizes the FQN, splits
     * the {@code Type.Name} pair, finds the object in the configuration, and verifies
     * it is of the expected EMF type and is a BM object.
     * <p>
     * Centralizes the FQN-split / {@code findObject} / type-check boilerplate shared
     * by the metadata write tools. On any failure the returned
     * {@link ObjectResolution#error} is a ready-to-return JSON string built with the
     * supplied {@code typeLabel}; on success {@link ObjectResolution#object} and
     * {@link ObjectResolution#normalizedFqn} are set.
     *
     * @param config the project configuration
     * @param fqn the object FQN from the tool parameters
     * @param expectedType the required EMF type (e.g. {@code Enum.class}); may be
     *            {@code null} to accept any {@link MdObject}
     * @param typeLabel a short human label for the expected type (e.g. {@code "Enum"})
     *            used in the error messages
     * @return an {@link ObjectResolution}; check {@link ObjectResolution#hasError()}
     */
    protected static ObjectResolution resolveTopObject(Configuration config, String fqn,
        Class<? extends MdObject> expectedType, String typeLabel)
    {
        String normalizedFqn = MetadataTypeUtils.normalizeFqn(fqn);
        String[] parts = normalizedFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return new ObjectResolution(null, null,
                ToolResult.error("Invalid FQN: " + fqn + ". Expected '" + typeLabel + ".Name'.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        MdObject object = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (object == null)
        {
            return new ObjectResolution(null, null,
                ToolResult.error(typeLabel + " not found: " + normalizedFqn //$NON-NLS-1$
                    + ". Check the FQN and use get_metadata_objects to list available objects.").toJson()); //$NON-NLS-1$
        }
        if (expectedType != null && !expectedType.isInstance(object))
        {
            return new ObjectResolution(null, null,
                ToolResult.error("Object '" + normalizedFqn + "' is not a " + typeLabel + " (it is a " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + object.eClass().getName() + ").").toJson()); //$NON-NLS-1$
        }
        if (!(object instanceof IBmObject))
        {
            return new ObjectResolution(null, null,
                ToolResult.error(typeLabel + " is not a BM object: " + normalizedFqn).toJson()); //$NON-NLS-1$
        }
        return new ObjectResolution(object, normalizedFqn, null);
    }

    /**
     * Persists an in-memory BM model change to the workspace {@code .mdo} file on
     * disk and refreshes stale validation for the changed top object.
     * <p>
     * A {@code bmModel.execute(...)} transaction commits the change into the
     * in-memory BM model, but the model-to-file serialization runs asynchronously,
     * so the on-disk {@code .mdo} does not change until that background export
     * completes. Likewise the validation markers attached to the object are not
     * recomputed by the commit, so {@code get_project_errors} can keep reporting
     * stale "not set" notes for properties that are in fact set. This method, run
     * <em>after</em> the transaction commits, drives both refreshes synchronously
     * for the changed object's <b>top-object FQN</b>:
     * <ol>
     * <li>{@link IBmModelManager#forceExport(IDtProject, java.util.List)} flushes the
     * top object to its {@code .mdo} file (the same API the form tools use); then</li>
     * <li>{@link ICheckScheduler#scheduleValidation} re-validates the changed top
     * object (recursively, so dependent objects refresh too), replacing the stale
     * markers, and {@link BuildUtils#waitForBuildAndDerivedData} waits for the
     * validation pass to settle so the fresh markers are queryable immediately.</li>
     * </ol>
     * The persist is best-effort: when it cannot be performed the model change is
     * still committed in memory, and a short diagnostic is returned so the caller can
     * surface a {@code persistWarning} instead of failing.
     *
     * @param project the workspace project owning the object
     * @param topObjectFqn the BM top-object FQN to flush and revalidate (obtain via
     *            {@link #topObjectFqnOf(Object)})
     * @return {@code null} on success, or a short diagnostic when the export could not
     *         be performed (the model change is still committed in memory)
     */
    protected String persistAndRevalidate(IProject project, String topObjectFqn)
    {
        if (topObjectFqn == null || topObjectFqn.isEmpty())
        {
            return "top-object FQN is unknown"; //$NON-NLS-1$
        }
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        if (dtProjectManager == null)
        {
            return "IDtProjectManager not available"; //$NON-NLS-1$
        }
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            return "could not resolve DT project"; //$NON-NLS-1$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return "IBmModelManager not available"; //$NON-NLS-1$
        }
        try
        {
            // Step 1: flush the in-memory change to the .mdo file on disk.
            bmModelManager.forceExport(dtProject, Collections.singletonList(topObjectFqn));
        }
        catch (Exception e)
        {
            Activator.logError("Error exporting object to disk: " + topObjectFqn, e); //$NON-NLS-1$
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        // Step 2 (best-effort): refresh stale validation markers for the changed top
        // object. A failure here must not mask the successful export, so it is logged
        // and swallowed - the change is on disk regardless of marker freshness.
        try
        {
            revalidateTopObject(project, dtProject, bmModelManager, topObjectFqn);
        }
        catch (Exception e)
        {
            Activator.logError("Error revalidating object after persist: " + topObjectFqn, e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Re-runs EDT validation for a single changed top object so its stale problem
     * markers are replaced by results computed against the fresh model.
     * <p>
     * The object's {@code bmId} is looked up by FQN inside a fresh read transaction
     * (the same transaction is handed to the scheduler as its context), then
     * {@link ICheckScheduler#scheduleValidation} is invoked with an empty
     * {@code checkIds} set (all applicable checks) and {@code isRecursive=true} so the
     * object's dependencies refresh too. The call settles asynchronously, so we then
     * wait for the build/derived-data jobs to finish before returning.
     */
    private static void revalidateTopObject(IProject project, IDtProject dtProject,
        IBmModelManager bmModelManager, String topObjectFqn)
    {
        ICheckScheduler checkScheduler = Activator.getDefault().getCheckScheduler();
        if (checkScheduler == null)
        {
            // No scheduler: the disk write still succeeded; markers stay until the
            // next build. forceExport already triggers a file-based revalidation.
            return;
        }
        IBmModel bmModel = bmModelManager.getModel(dtProject);
        if (bmModel == null)
        {
            return;
        }
        final IProgressMonitor monitor = new NullProgressMonitor();
        bmModel.executeReadonlyTask(new AbstractBmTask<Void>("PersistRevalidate") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction tx, IProgressMonitor pm)
            {
                IBmObject obj = tx.getTopObjectByFqn(topObjectFqn);
                if (obj == null)
                {
                    return null;
                }
                long bmId = obj.bmGetId();
                if (bmId <= 0)
                {
                    return null;
                }
                // Empty checkIds = all applicable checks; isRecursive=true so the
                // object's dependencies (and their markers) are refreshed as well.
                checkScheduler.scheduleValidation(project, Collections.emptySet(),
                    Collections.singletonList(Long.valueOf(bmId)), true, tx, monitor);
                return null;
            }
        });
        // Wait for the scheduled validation and any derived-data recompute to settle
        // so get_project_errors reflects the fresh markers immediately.
        BuildUtils.waitForBuildAndDerivedData(project, monitor);
    }
}
