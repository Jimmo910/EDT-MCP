/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

/**
 * Tool to capture a screenshot of a form WYSIWYG editor as PNG.
 * Can automatically open and activate a form by its metadata FQN path.
 */
public class GetFormScreenshotTool implements IMcpTool
{
    public static final String NAME = "get_form_screenshot"; //$NON-NLS-1$
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Capture a screenshot of the active form WYSIWYG editor as PNG. " + //$NON-NLS-1$
            "Can open and activate a form automatically by metadata FQN path."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required when formPath is specified.") //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Metadata FQN path to the form. " + //$NON-NLS-1$
                "Format: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'. " + //$NON-NLS-1$
                "Examples: 'Catalog.Products.Forms.ItemForm', 'Document.SalesOrder.Forms.DocumentForm', " + //$NON-NLS-1$
                "'CommonForm.MyForm'. If not specified, captures the currently active form editor.") //$NON-NLS-1$
            .booleanProperty("refresh", "Force WYSIWYG refresh before capture (default: false)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.IMAGE;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String formPath = params.get("formPath"); //$NON-NLS-1$
        if (formPath != null && !formPath.isEmpty())
        {
            String[] parts = formPath.split("\\."); //$NON-NLS-1$
            if (parts.length > 0)
            {
                return parts[parts.length - 1] + ".png"; //$NON-NLS-1$
            }
        }
        return "form.png"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        boolean refresh = "true".equalsIgnoreCase(JsonUtils.extractStringArgument(params, "refresh")); //$NON-NLS-1$ //$NON-NLS-2$

        if (formPath != null && !formPath.isEmpty()
            && (projectName == null || projectName.isEmpty()))
        {
            return ToolResult.error("projectName is required when formPath is specified").toJson(); //$NON-NLS-1$
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return ToolResult.error("Display is not available").toJson(); //$NON-NLS-1$
        }

        AtomicReference<CaptureResult> resultRef = new AtomicReference<>();
        display.syncExec(() -> resultRef.set(captureScreenshot(projectName, formPath, refresh)));

        CaptureResult result = resultRef.get();
        if (!result.isSuccess())
        {
            return result.getError();
        }

        return result.getBase64Data();
    }

    /**
     * Main capture logic. Runs on the UI thread.
     */
    private CaptureResult captureScreenshot(String projectName, String formPath, boolean refresh)
    {
        try
        {
            Object editorPage;

            if (formPath != null && !formPath.isEmpty())
            {
                EditorScreenshotHelper.ensureBufferedNativeRenderMode();

                // Open the requested form and keep a direct handle on the editor that was opened
                // for it. The image MUST come from this editor's own WYSIWYG representation, not
                // from the globally active form editor: previously this tool resolved the page via
                // FormEditor.getActiveFormEditorPage(), which returns whatever form editor currently
                // has workbench focus, so a previously rendered/active form (e.g. DataProcessor.X)
                // was captured instead of the requested one (Bitrix #19889).
                EditorScreenshotHelper.OpenFormResult openResult =
                    EditorScreenshotHelper.openForm(projectName, formPath);
                if (!openResult.isSuccess())
                {
                    return CaptureResult.error(openResult.getError());
                }

                IEditorPart editorPart = openResult.getEditorPart();

                // Let UI settle after activation
                Display display = Display.getCurrent();
                for (int i = 0; i < 5; i++)
                {
                    EditorScreenshotHelper.processEvents(display);
                    Thread.sleep(100);
                }

                // Resolve the WYSIWYG page from THIS editor part (findPage), not the global active
                // page, so the page is guaranteed to belong to the requested form.
                editorPage = EditorScreenshotHelper.waitForFormEditorPageOf(editorPart);
                if (editorPage == null)
                {
                    return CaptureResult.error(ToolResult.error(
                        "Form editor opened but WYSIWYG page is not available. " + //$NON-NLS-1$
                        "The form may still be loading.").toJson()); //$NON-NLS-1$
                }

                // Identity guard: confirm the opened editor actually corresponds to the requested
                // form before reading its image. If it does not match, fail explicitly rather than
                // return another form's PNG (the silent wrong-form defect, Bitrix #19889).
                String actualFqn = EditorScreenshotHelper.getFormEditorFqn(editorPart);
                if (actualFqn != null && !EditorScreenshotHelper.fqnMatchesFormPath(actualFqn, formPath))
                {
                    return CaptureResult.error(ToolResult.error(
                        "Captured form editor does not match the requested form. Requested '" //$NON-NLS-1$
                        + formPath + "' but the active editor is '" + actualFqn //$NON-NLS-1$
                        + "'. No screenshot was taken to avoid returning the wrong form's image; " //$NON-NLS-1$
                        + "try again once the requested form's editor is fully open.").toJson()); //$NON-NLS-1$
                }
            }
            else
            {
                editorPage = EditorScreenshotHelper.getActiveFormEditorPage();
                if (editorPage == null)
                {
                    return CaptureResult.error(ToolResult.error(
                        "No active form editor page found. " + //$NON-NLS-1$
                        "Specify formPath parameter to open a form automatically.").toJson()); //$NON-NLS-1$
                }
            }

            Object wysiwygViewer = ReflectionUtils.getFieldValue(editorPage, WYSIWYG_VIEWER_FIELD);
            if (wysiwygViewer == null)
            {
                return CaptureResult.error(ToolResult.error("WYSIWYG viewer is not available").toJson()); //$NON-NLS-1$
            }

            if (refresh)
            {
                EditorScreenshotHelper.refreshViewer(wysiwygViewer);
            }

            // The form layout is produced by an asynchronous native render; on the first call
            // right after opening (or changing) a form the image is not ready yet. Wait until the
            // render produces a non-empty image instead of returning an empty/blank result.
            final Object viewer = wysiwygViewer;
            Object representation = EditorScreenshotHelper.getRepresentation(viewer);
            boolean rendered = EditorScreenshotHelper.waitUntilRendered(viewer,
                () -> EditorScreenshotHelper.readFormImageData(representation) != null);

            // Primary method: extract image from representation (rebuild + read).
            ImageData imageData = EditorScreenshotHelper.extractFormImageData(wysiwygViewer);

            // Fallback: capture control via print
            if (imageData == null)
            {
                imageData = EditorScreenshotHelper.captureControlImageData(wysiwygViewer);
            }

            if (imageData == null || imageData.width <= 0 || imageData.height <= 0)
            {
                if (!rendered)
                {
                    return CaptureResult.error(ToolResult.error(
                        "Form did not finish rendering in time, so no image could be captured. " + //$NON-NLS-1$
                        "Ensure EDT runs with buffered native render " + //$NON-NLS-1$
                        "(VM option -DnativeFormBufferedLayoutRender=true) and try again.").toJson()); //$NON-NLS-1$
                }
                return CaptureResult.error(ToolResult.error("Form image data is not available").toJson()); //$NON-NLS-1$
            }

            String base64 = EditorScreenshotHelper.encodePng(imageData);
            return CaptureResult.success(base64);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to capture form screenshot", e); //$NON-NLS-1$
            return CaptureResult.error(
                ToolResult.error("Failed to capture form screenshot: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }
}
