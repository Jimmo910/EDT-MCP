/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Reusable helper for capturing screenshots from EDT visual editors (forms, print forms, etc.).
 * <p>
 * All UI-modifying methods must be called from the SWT UI thread (via {@code Display.syncExec}).
 */
public final class EditorScreenshotHelper
{
    private static final String FORM_EDITOR_CLASS = "com._1c.g5.v8.dt.form.ui.editor.FormEditor"; //$NON-NLS-1$
    private static final String FORM_EDITOR_ID = "com._1c.g5.v8.dt.form.ui.formEditor"; //$NON-NLS-1$
    private static final String FORM_MAIN_PAGE_ID = "editors.form.pages.main"; //$NON-NLS-1$
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$
    private static final String WYSIWYG_REPRESENTATION_FIELD = "wysiwygRepresentation"; //$NON-NLS-1$
    private static final String FORM_IMAGE_METHOD = "getFormImageData"; //$NON-NLS-1$
    private static final String GET_CONTROL_METHOD = "getControl"; //$NON-NLS-1$
    private static final String REFRESH_METHOD = "refresh"; //$NON-NLS-1$
    private static final String REBUILD_METHOD = "rebuild"; //$NON-NLS-1$
    private static final int WYSIWYG_WAIT_RETRIES = 15;
    private static final int WYSIWYG_WAIT_INTERVAL_MS = 500;
    private static final int RENDER_WAIT_TIMEOUT_MS = 8000;
    private static final int RENDER_WAIT_POLL_INTERVAL_MS = 200;

    private EditorScreenshotHelper()
    {
        // Utility class
    }

    // ==================== Result container ====================

    /**
     * Result of a screenshot capture — either base64 PNG data or an error JSON string.
     */
    public static class CaptureResult
    {
        private final String base64Data;
        private final String error;

        private CaptureResult(String base64Data, String error)
        {
            this.base64Data = base64Data;
            this.error = error;
        }

        public static CaptureResult success(String base64)
        {
            return new CaptureResult(base64, null);
        }

        public static CaptureResult error(String errorJson)
        {
            return new CaptureResult(null, errorJson);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public String getBase64Data()
        {
            return base64Data;
        }

        public String getError()
        {
            return error;
        }
    }

    // ==================== Native render mode ====================

    /**
     * Ensures that the native buffered render mode is enabled so that
     * {@code getFormImageData()} returns valid image data.
     * Should be called before opening a form editor.
     */
    public static void ensureBufferedNativeRenderMode()
    {
        final String nativeRenderServiceClass = "com._1c.g5.v8.dt.form.layout.service.NativeRenderService"; //$NON-NLS-1$
        final String bufferedFlagField = "NATIVE_FORM_BUFFERED_LAYOUT_RENDER"; //$NON-NLS-1$
        final String propertyName = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$

        try
        {
            System.setProperty(propertyName, "true"); //$NON-NLS-1$

            Class<?> serviceClass = Class.forName(nativeRenderServiceClass);
            Method isNativeRenderMethod = serviceClass.getMethod("isNativeRender"); //$NON-NLS-1$
            Method isBufferedRenderMethod = serviceClass.getMethod("isBufferedRender"); //$NON-NLS-1$

            boolean nativeRender = (Boolean)isNativeRenderMethod.invoke(null);
            boolean bufferedBefore = (Boolean)isBufferedRenderMethod.invoke(null);

            if (nativeRender && !bufferedBefore)
            {
                try
                {
                    Field bufferedField = serviceClass.getDeclaredField(bufferedFlagField);
                    bufferedField.setAccessible(true);
                    bufferedField.setBoolean(null, true);
                }
                catch (Exception e)
                {
                    ReflectionUtils.forceStaticFinalBoolean(serviceClass, bufferedFlagField, true);
                }
            }

            boolean bufferedAfter = (Boolean)isBufferedRenderMethod.invoke(null);
            if (!bufferedAfter)
            {
                Activator.logWarning("Buffered native render is still disabled. " + //$NON-NLS-1$
                    "Restart EDT with VM option: -DnativeFormBufferedLayoutRender=true"); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to ensure buffered native render mode: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    // ==================== Editor opening ====================

    /**
     * Opens a form file in the editor and activates the WYSIWYG (main) page.
     * Must be called on the UI thread.
     *
     * @param projectName EDT project name
     * @param formPath FQN path like "Catalog.Products.Forms.ItemForm" or "CommonForm.MyForm"
     * @return {@code null} on success, error JSON string on failure
     */
    public static String openAndActivateForm(String projectName, String formPath)
    {
        String relativePath = MetadataPathResolver.resolveFormFilePath(formPath);
        if (relativePath == null)
        {
            return ToolResult.error(
                "Cannot resolve form path: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected format: 'MetadataType.ObjectName.Forms.FormName' " + //$NON-NLS-1$
                "or 'CommonForm.FormName'.").toJson(); //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        IFile formFile = project.getFile(new Path(relativePath));
        if (!formFile.exists())
        {
            return ToolResult.error(
                "Form file not found: " + relativePath + " in project " + projectName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IWorkbenchPage page = getWorkbenchPage();
        if (page == null)
        {
            return ToolResult.error("No active workbench page").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Close existing editor so we apply current render mode
            IEditorPart existingEditor = page.findEditor(new FileEditorInput(formFile));
            if (existingEditor != null)
            {
                page.closeEditor(existingEditor, false);
            }

            IEditorPart editorPart = IDE.openEditor(page, formFile, FORM_EDITOR_ID, true);
            if (editorPart == null)
            {
                return ToolResult.error("Could not open form editor for: " + formPath).toJson(); //$NON-NLS-1$
            }

            activateFormMainPage(editorPart);
            return null; // success
        }
        catch (Exception e)
        {
            Activator.logError("Failed to open form editor for: " + formPath, e); //$NON-NLS-1$
            return ToolResult.error("Failed to open form editor: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Gets the active workbench page, trying all available windows.
     */
    public static IWorkbenchPage getWorkbenchPage()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            if (windows.length > 0)
            {
                window = windows[0];
            }
        }
        if (window == null)
        {
            return null;
        }
        return window.getActivePage();
    }

    // ==================== WYSIWYG page detection ====================

    /**
     * Waits for the form editor WYSIWYG page to become available.
     * Processes UI events while waiting to allow the editor to initialize.
     * Must be called on the UI thread.
     *
     * @return the FormEditorPage, or {@code null} if not available after timeout
     */
    public static Object waitForFormEditorPage()
    {
        Display display = Display.getCurrent();
        for (int i = 0; i < WYSIWYG_WAIT_RETRIES; i++)
        {
            processEvents(display);

            try
            {
                Object page = getActiveFormEditorPage();
                if (page != null)
                {
                    Object viewer = ReflectionUtils.getFieldValue(page, WYSIWYG_VIEWER_FIELD);
                    if (viewer != null)
                    {
                        return page;
                    }
                }
            }
            catch (Exception e)
            {
                // Editor still initializing, keep waiting
            }

            sleep(WYSIWYG_WAIT_INTERVAL_MS);
            processEvents(display);
        }

        // Final attempt
        try
        {
            return getActiveFormEditorPage();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get form editor page after waiting", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Gets the active form editor page via the static FormEditor API.
     */
    public static Object getActiveFormEditorPage() throws Exception
    {
        Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
        Method method = editorClass.getMethod("getActiveFormEditorPage"); //$NON-NLS-1$
        return method.invoke(null);
    }

    // ==================== Image capture ====================

    /**
     * Extracts the form image data from the WYSIWYG representation.
     * This is the primary (preferred) capture method using {@code getFormImageData()}.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return image data, or {@code null} if not available
     */
    public static ImageData extractFormImageData(Object wysiwygViewer) throws Exception
    {
        Object representation = ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
        if (representation == null)
        {
            return null;
        }

        // Trigger rebuild so the native render produces an up-to-date image, then read it.
        rebuildRepresentation(representation);
        return readFormImageData(representation);
    }

    /**
     * Reads the currently rendered form image from the representation without triggering a
     * rebuild. Returns {@code null} when the native render has not produced an image yet.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @return image data, or {@code null} if not available
     */
    public static ImageData readFormImageData(Object representation)
    {
        if (representation == null)
        {
            return null;
        }
        try
        {
            Method method = representation.getClass().getDeclaredMethod(FORM_IMAGE_METHOD);
            method.setAccessible(true);
            ImageData data = (ImageData)method.invoke(representation);
            if (data != null && data.width > 0 && data.height > 0)
            {
                return data;
            }
        }
        catch (NoSuchMethodException e)
        {
            Activator.logWarning("Method " + FORM_IMAGE_METHOD + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not read form image data: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Fallback capture method: captures the WYSIWYG control image via {@code Control.print()}.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return image data, or {@code null} if the control is not available or has invalid bounds
     */
    public static ImageData captureControlImageData(Object wysiwygViewer) throws Exception
    {
        Control control = (Control)ReflectionUtils.invokeMethod(wysiwygViewer, GET_CONTROL_METHOD);
        if (control == null || control.isDisposed())
        {
            return null;
        }

        Rectangle bounds = control.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
        {
            return null;
        }

        control.update();

        Image image = new Image(control.getDisplay(), bounds.width, bounds.height);
        GC gc = new GC(image);
        try
        {
            gc.setBackground(control.getDisplay().getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, bounds.width, bounds.height);
            control.print(gc);
            return image.getImageData();
        }
        finally
        {
            gc.dispose();
            image.dispose();
        }
    }

    /**
     * Refreshes the WYSIWYG viewer and waits for it to complete.
     * Must be called on the UI thread.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     */
    public static void refreshViewer(Object wysiwygViewer)
    {
        try
        {
            ReflectionUtils.invokeMethod(wysiwygViewer, REFRESH_METHOD);
            Display display = Display.getCurrent();
            if (display != null)
            {
                for (int i = 0; i < 3; i++)
                {
                    processEvents(display);
                    sleep(100);
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to refresh WYSIWYG viewer: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    // ==================== Render readiness ====================

    /**
     * Returns the {@code FormWysiwygRepresentation} backing the given viewer, or {@code null}.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return the representation object, or {@code null} if not available
     */
    public static Object getRepresentation(Object wysiwygViewer)
    {
        try
        {
            return ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Triggers a full rebuild of the WYSIWYG representation so the native layout/render
     * pipeline recomputes element bounds and the form image. Pumps a few UI cycles so the
     * asynchronous native render can deliver its result.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     */
    public static void rebuildRepresentation(Object representation)
    {
        if (representation == null)
        {
            return;
        }
        try
        {
            Method rebuildMethod = representation.getClass().getDeclaredMethod(REBUILD_METHOD, boolean.class);
            rebuildMethod.setAccessible(true);
            rebuildMethod.invoke(representation, true);

            Display display = Display.getCurrent();
            for (int i = 0; i < 5; i++)
            {
                processEvents(display);
                sleep(RENDER_WAIT_POLL_INTERVAL_MS);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not rebuild WYSIWYG representation: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Waits until the form WYSIWYG layout has actually finished rendering.
     * <p>
     * The EDT form layout is produced by an asynchronous, event-driven native render
     * ({@code NativeRenderService}); right after a form is opened or its structure changes,
     * the first attempt to read the layout or the image returns nothing because rendering has
     * not completed yet. This method triggers a rebuild and then polls the supplied readiness
     * predicate, pumping the SWT event loop between polls, until the predicate is satisfied or
     * the timeout elapses.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @param renderedCheck predicate that returns {@code true} once the rendered content
     *            (calculated bounds or form image) is available
     * @return {@code true} if the form rendered within the timeout, {@code false} otherwise
     */
    public static boolean waitUntilRendered(Object wysiwygViewer, BooleanSupplier renderedCheck)
    {
        return waitUntilRendered(wysiwygViewer, renderedCheck, RENDER_WAIT_TIMEOUT_MS);
    }

    /**
     * Same as {@link #waitUntilRendered(Object, BooleanSupplier)} but with an explicit timeout.
     * Package-visible so tests can exercise the polling loop quickly.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @param renderedCheck predicate that returns {@code true} once the rendered content is available
     * @param timeoutMs maximum time to wait, in milliseconds
     * @return {@code true} if the form rendered within the timeout, {@code false} otherwise
     */
    static boolean waitUntilRendered(Object wysiwygViewer, BooleanSupplier renderedCheck, int timeoutMs)
    {
        Object representation = getRepresentation(wysiwygViewer);
        Display display = Display.getCurrent();

        if (safeCheck(renderedCheck))
        {
            return true;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean rebuilt = false;
        while (System.currentTimeMillis() < deadline)
        {
            if (!rebuilt && representation != null)
            {
                rebuildRepresentation(representation);
                rebuilt = true;
            }

            processEvents(display);
            if (safeCheck(renderedCheck))
            {
                return true;
            }
            sleep(RENDER_WAIT_POLL_INTERVAL_MS);
            processEvents(display);
            if (safeCheck(renderedCheck))
            {
                return true;
            }
        }

        return safeCheck(renderedCheck);
    }

    private static boolean safeCheck(BooleanSupplier renderedCheck)
    {
        try
        {
            return renderedCheck.getAsBoolean();
        }
        catch (Exception e)
        {
            // A transient failure while the model is still being built is treated as "not ready".
            return false;
        }
    }

    // ==================== Encoding ====================

    /**
     * Encodes {@link ImageData} as a base64 PNG string.
     *
     * @param imageData the image data to encode
     * @return base64-encoded PNG string
     */
    public static String encodePng(ImageData imageData)
    {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { imageData };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        loader.save(output, SWT.IMAGE_PNG);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    // ==================== Internal helpers ====================

    /**
     * Activates the main (WYSIWYG) page of the form editor via reflection.
     */
    private static void activateFormMainPage(IEditorPart editorPart)
    {
        try
        {
            Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
            if (!editorClass.isInstance(editorPart))
            {
                return;
            }

            Method setActivePageMethod =
                ReflectionUtils.findMethod(editorPart.getClass(), "setActivePage", String.class); //$NON-NLS-1$
            if (setActivePageMethod != null)
            {
                setActivePageMethod.setAccessible(true);
                setActivePageMethod.invoke(editorPart, FORM_MAIN_PAGE_ID);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not activate form main page: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Processes all pending SWT events.
     */
    public static void processEvents(Display display)
    {
        if (display != null)
        {
            while (display.readAndDispatch())
            {
                // drain event queue
            }
        }
    }

    /**
     * Sleeps with interrupt handling.
     */
    private static void sleep(int millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
