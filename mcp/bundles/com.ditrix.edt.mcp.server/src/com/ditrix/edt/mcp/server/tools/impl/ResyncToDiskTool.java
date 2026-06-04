/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Bulk re-synchronizes the in-memory BM model to the on-disk {@code src/}
 * {@code .mdo} files and reports any pre-existing BM&harr;disk desync.
 * <p>
 * <b>Why this exists.</b> A metadata write commits into the in-memory BM model
 * and {@link AbstractMetadataWriteTool#persistAndRevalidate} flushes only the
 * <em>single</em> changed top object to disk. Objects created in earlier
 * sessions (before that per-object flush existed) live in the BM model and the
 * {@code Configuration.mdo} object list, yet have no {@code .mdo} on disk. The
 * desync is invisible until {@code update_database} / XML import fails with
 * "object file does not exist - /Subsystems/X.mdo; /Roles/Y.mdo; ...".
 * {@code clean_project} re-imports disk&rarr;BM (the wrong direction) and cannot
 * recreate the missing files.
 * <p>
 * This tool walks every top object of the project's BM model
 * ({@link IBmTransaction#getTopObjectIterator()}, which catches all kinds, not
 * just the collections an enumeration would special-case) and keeps only the
 * real metadata objects ({@link MdObject} - the ones that map to a {@code .mdo}
 * file), then collects their FQNs and calls
 * {@link IBmModelManager#forceExport(IDtProject, List)} so each object's
 * {@code .mdo} is (re)written under {@code src/}. Internal BM top objects that
 * are not {@link MdObject} and therefore have no {@code .mdo} - content forms
 * ({@code com._1c.g5.v8.dt.form.model.Form}, persisted as {@code .form}) and BSL
 * module reference/context index objects ({@code Module.bsl.mRIdx} /
 * {@code Module.bsl.mCtxIdx}) - are excluded so they are not mis-reported as a
 * missing-{@code .mdo} desync. The same
 * {@code IDtProject}/{@code forceExport} path used by
 * {@link AbstractMetadataWriteTool#persistAndRevalidate} is reused, just over
 * the full FQN list instead of one entry.
 * <p>
 * <b>Integrity report.</b> Before exporting, the tool computes the expected
 * {@code .mdo} path for each top object ({@code src/<TypeDir>/<Name>/<Name>.mdo})
 * and records the ones that are missing on disk - that set is the actual desync.
 * After the export it re-checks and reports anything still missing (normally
 * none). The operation is read-safe and idempotent: when everything is already
 * in sync it simply re-exports (no model change) and reports {@code 0} missing.
 */
public class ResyncToDiskTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "resync_to_disk"; //$NON-NLS-1$

    /** Cap on how many FQNs are listed back in the JSON to keep responses bounded. */
    private static final int MAX_LISTED_FQNS = 500;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Bulk re-synchronize the in-memory BM model to the on-disk src/ .mdo files " //$NON-NLS-1$
            + "and report BM-to-disk desync. Walks EVERY top metadata object of the " //$NON-NLS-1$
            + "configuration (all kinds) and force-exports each object's .mdo, so objects " //$NON-NLS-1$
            + "that exist in the model / Configuration.mdo but have no .mdo on disk are " //$NON-NLS-1$
            + "written out. Fixes 'object file does not exist' failures from update_database " //$NON-NLS-1$
            + "/ XML import caused by an accumulated desync (objects created before per-object " //$NON-NLS-1$
            + "export existed). Read-safe and idempotent: when already in sync it re-exports " //$NON-NLS-1$
            + "harmlessly and reports 0 missing. Reports objectsExported, the missing-before " //$NON-NLS-1$
            + "set (the real desync) and anything still missing after export. " //$NON-NLS-1$
            + "Optionally revalidates the project afterwards (revalidate=false by default)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required).", true) //$NON-NLS-1$
            .booleanProperty("revalidate", //$NON-NLS-1$
                "When true, schedule a full project revalidation after the export so stale " //$NON-NLS-1$
                    + "markers refresh. Default: false (export only).") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        boolean revalidate = JsonUtils.extractBooleanArgument(params, "revalidate", false); //$NON-NLS-1$

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }

        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        if (dtProjectManager == null)
        {
            return ToolResult.error("IDtProjectManager not available").toJson(); //$NON-NLS-1$
        }
        IDtProject dtProject = dtProjectManager.getDtProject(ctx.project);
        if (dtProject == null)
        {
            return ToolResult.error("Not an EDT project: " + projectName).toJson(); //$NON-NLS-1$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(dtProject);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Step 1: enumerate the real metadata top objects of the configuration via
        // the BM model. getTopObjectIterator() yields every IBmObject top object
        // regardless of kind (so nothing is missed the way a per-collection walk
        // could), and the collector keeps only MdObject instances - the ones that
        // map to a .mdo file - filtering out internal non-metadata top objects
        // (content forms, BSL module index objects) that have no .mdo by design.
        List<String> allFqns = collectMetadataTopObjectFqns(bmModel);

        // Step 2: integrity check BEFORE the export - which registered objects have
        // no .mdo on disk. This pre-export set is the real desync we are catching up.
        List<String> missingBefore = findMissingMdoFiles(ctx.project, allFqns);

        // Step 3: bulk flush every top object's .mdo to disk (same path the
        // per-object persist uses, just over the full list). forceExport accepts
        // the whole list, so a large configuration is handled in one call.
        String exportError = null;
        try
        {
            bmModelManager.forceExport(dtProject, allFqns);
        }
        catch (Exception e)
        {
            Activator.logError("Error force-exporting BM model to disk for project: " + projectName, e); //$NON-NLS-1$
            exportError = unwrapCauseMessage(e);
        }

        // Step 4: re-check on disk. After a successful export the missing-before set
        // should be empty; anything still missing is surfaced so the caller knows
        // the desync was not fully resolved (e.g. a type with no src/ layout).
        List<String> stillMissing = exportError == null
            ? findMissingMdoFiles(ctx.project, missingBefore)
            : missingBefore;

        // Step 5 (optional, best-effort): refresh stale validation markers.
        String revalidateWarning = null;
        if (revalidate && exportError == null)
        {
            try
            {
                CleanProjectTool.cleanProject(projectName);
            }
            catch (Exception e)
            {
                Activator.logError("Error revalidating after resync_to_disk: " + projectName, e); //$NON-NLS-1$
                revalidateWarning = unwrapCauseMessage(e);
            }
        }

        ToolResult result = exportError == null ? ToolResult.success() : ToolResult.error(exportError);
        result.put("projectName", projectName) //$NON-NLS-1$
            .put("objectsExported", exportError == null ? allFqns.size() : 0) //$NON-NLS-1$
            .put("totalTopObjects", allFqns.size()) //$NON-NLS-1$
            .put("missingBeforeCount", missingBefore.size()) //$NON-NLS-1$
            .put("missingBefore", limit(missingBefore)) //$NON-NLS-1$
            .put("stillMissingCount", stillMissing.size()) //$NON-NLS-1$
            .put("stillMissing", limit(stillMissing)); //$NON-NLS-1$
        if (revalidateWarning != null)
        {
            result.put("revalidateWarning", revalidateWarning); //$NON-NLS-1$
        }
        result.put("message", buildMessage(allFqns.size(), missingBefore.size(), //$NON-NLS-1$
            stillMissing.size(), exportError));
        return result.toJson();
    }

    /**
     * Collects the FQNs of the real metadata top objects managed by the project's
     * BM model. Runs inside a read-only BM transaction and iterates
     * {@link IBmTransaction#getTopObjectIterator()}, keeping only {@link MdObject}
     * instances (the objects that map to a {@code .mdo} file). Internal BM top
     * objects that are not metadata and have no {@code .mdo} - content forms
     * ({@code com._1c.g5.v8.dt.form.model.Form}) and BSL module reference/context
     * index objects ({@code Module.bsl.mRIdx} / {@code Module.bsl.mCtxIdx}) - are
     * skipped so they are not flagged as a missing-{@code .mdo} desync.
     *
     * @param bmModel the project BM model
     * @return the metadata top-object FQNs (never {@code null}; may be empty)
     */
    private static List<String> collectMetadataTopObjectFqns(IBmModel bmModel)
    {
        List<String> fqns = new ArrayList<>();
        bmModel.executeReadonlyTask(new AbstractBmTask<Void>("CollectTopObjectsForResync") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction tx, IProgressMonitor pm)
            {
                Iterator<IBmObject> it = tx.getTopObjectIterator();
                while (it.hasNext())
                {
                    IBmObject obj = it.next();
                    if (obj == null)
                    {
                        continue;
                    }
                    // getTopObjectIterator() returns EVERY BM top object, including
                    // internal ones that are not metadata and have no .mdo file:
                    // content forms (com._1c.g5.v8.dt.form.model.Form, persisted as
                    // .form) and BSL module reference/context index objects
                    // (Module.bsl.mRIdx / Module.bsl.mCtxIdx, internal derived data).
                    // Only real metadata objects implement MdObject and map to a .mdo
                    // file (Catalog/Document/CommonModule/Subsystem/Role/StyleItem/...),
                    // so filter out everything else to avoid false-positive "missing
                    // .mdo" reports for these non-MdObject top objects.
                    if (!(obj instanceof MdObject))
                    {
                        continue;
                    }
                    String fqn = obj.bmGetFqn();
                    if (fqn != null && !fqn.isEmpty())
                    {
                        fqns.add(fqn);
                    }
                }
                return null;
            }
        });
        return fqns;
    }

    /**
     * Returns the subset of the given FQNs whose expected {@code .mdo} file does
     * not exist on disk under {@code src/}.
     * <p>
     * The expected path is {@code src/<TypeDir>/<Name>/<Name>.mdo}, where
     * {@code <TypeDir>} comes from {@link MetadataTypeUtils#getDirectoryName(String)}.
     * FQNs whose type has no {@code src/} directory layout (e.g. {@code Language},
     * {@code Style}, or the {@code Configuration} root) are skipped rather than
     * reported as missing, since they are not stored as an own {@code .mdo} under a
     * type directory. The on-disk filesystem is checked directly (not the possibly
     * stale workspace resource tree) so the result reflects reality immediately.
     *
     * @param project the workspace project
     * @param fqns the FQNs to check
     * @return the FQNs with a missing {@code .mdo} (never {@code null})
     */
    private static List<String> findMissingMdoFiles(IProject project, List<String> fqns)
    {
        List<String> missing = new ArrayList<>();
        IPath location = project.getLocation();
        if (location == null)
        {
            return missing;
        }
        File srcRoot = location.append("src").toFile(); //$NON-NLS-1$
        for (String fqn : fqns)
        {
            String relative = mdoRelativePath(fqn);
            if (relative == null)
            {
                // Type has no own .mdo file under a type directory: not a desync candidate.
                continue;
            }
            File mdoFile = new File(srcRoot, relative);
            if (!mdoFile.isFile())
            {
                missing.add(fqn);
            }
        }
        return missing;
    }

    /**
     * Computes the {@code .mdo} path of a top object relative to {@code src/}, or
     * {@code null} when the object's type has no own {@code .mdo} file under a type
     * directory.
     *
     * @param fqn the top-object FQN (e.g. {@code "Catalog.Products"})
     * @return relative path like {@code "Catalogs/Products/Products.mdo"}, or
     *         {@code null}
     */
    private static String mdoRelativePath(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot >= fqn.length() - 1)
        {
            // Dotless FQN (e.g. the Configuration root) - not a type-directory object.
            return null;
        }
        String type = fqn.substring(0, dot);
        String name = fqn.substring(dot + 1);
        String dir = MetadataTypeUtils.getDirectoryName(type);
        if (dir == null)
        {
            return null;
        }
        return dir + "/" + name + "/" + name + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Caps a list to {@link #MAX_LISTED_FQNS} entries for a bounded JSON response. */
    private static List<String> limit(List<String> values)
    {
        if (values.size() <= MAX_LISTED_FQNS)
        {
            return values;
        }
        return new ArrayList<>(values.subList(0, MAX_LISTED_FQNS));
    }

    /** Builds a concise human-readable summary of the outcome. */
    private static String buildMessage(int exported, int missingBefore, int stillMissing,
        String exportError)
    {
        if (exportError != null)
        {
            return "Export failed: " + exportError + ". " + missingBefore //$NON-NLS-1$ //$NON-NLS-2$
                + " object(s) were missing on disk before the attempt."; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Re-exported ").append(exported).append(" top object(s) to src/. "); //$NON-NLS-1$ //$NON-NLS-2$
        if (missingBefore == 0)
        {
            sb.append("Already in sync: no .mdo files were missing."); //$NON-NLS-1$
        }
        else
        {
            sb.append(missingBefore).append(" object(s) had no .mdo on disk before and were written out"); //$NON-NLS-1$
            if (stillMissing == 0)
            {
                sb.append("; all are present now."); //$NON-NLS-1$
            }
            else
            {
                sb.append("; ").append(stillMissing).append(" still missing after export."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return sb.toString();
    }
}
