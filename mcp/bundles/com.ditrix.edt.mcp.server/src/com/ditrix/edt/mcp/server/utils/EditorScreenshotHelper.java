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
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

import com._1c.g5.v8.dt.ui.util.ContentUtil;

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
    private static final String FIND_PAGE_METHOD = "findPage"; //$NON-NLS-1$
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$
    private static final String FORM_CONTROLS_CREATED_FIELD = "formControlsCreated"; //$NON-NLS-1$
    private static final String WYSIWYG_REPRESENTATION_FIELD = "wysiwygRepresentation"; //$NON-NLS-1$
    private static final String FORM_IMAGE_METHOD = "getFormImageData"; //$NON-NLS-1$
    private static final String GET_CONTROL_METHOD = "getControl"; //$NON-NLS-1$
    private static final String REFRESH_METHOD = "refresh"; //$NON-NLS-1$
    private static final String REBUILD_METHOD = "rebuild"; //$NON-NLS-1$
    private static final int WYSIWYG_WAIT_RETRIES = 15;
    private static final int WYSIWYG_WAIT_INTERVAL_MS = 500;
    private static final int RENDER_WAIT_TIMEOUT_MS = 8000;
    private static final int RENDER_WAIT_POLL_INTERVAL_MS = 200;
    private static final int EDITOR_INPUT_RESOLVE_RETRIES = 20;
    private static final int EDITOR_INPUT_RESOLVE_INTERVAL_MS = 250;

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
     * Result of opening a form editor: either the opened {@code FormEditor} part plus the
     * resolved form {@code IFile}, or an error JSON string.
     */
    public static final class OpenFormResult
    {
        private final IEditorPart editorPart;
        private final IFile formFile;
        private final String error;

        private OpenFormResult(IEditorPart editorPart, IFile formFile, String error)
        {
            this.editorPart = editorPart;
            this.formFile = formFile;
            this.error = error;
        }

        static OpenFormResult success(IEditorPart editorPart, IFile formFile)
        {
            return new OpenFormResult(editorPart, formFile, null);
        }

        static OpenFormResult error(String errorJson)
        {
            return new OpenFormResult(null, null, errorJson);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public IEditorPart getEditorPart()
        {
            return editorPart;
        }

        public IFile getFormFile()
        {
            return formFile;
        }

        public String getError()
        {
            return error;
        }
    }

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
        OpenFormResult result = openForm(projectName, formPath);
        return result.isSuccess() ? null : result.getError();
    }

    /**
     * Opens a form file in the editor, activates its WYSIWYG (main) page and returns the opened
     * editor part. Unlike {@link #openAndActivateForm(String, String)}, callers get a direct
     * handle on the editor that was opened for the requested {@code formPath}, so they can read
     * the WYSIWYG page from <i>that</i> specific editor instead of the globally active one. This
     * is the fix for Bitrix #19889, where {@code get_form_screenshot} captured the previously
     * active form because it resolved the page via the global "active form editor page" lookup
     * ({@code FormEditor.getActiveFormEditorPage()}), which returns whatever editor currently has
     * workbench focus rather than the one just opened. Must be called on the UI thread.
     *
     * @param projectName EDT project name
     * @param formPath FQN path like "Catalog.Products.Forms.ItemForm" or "CommonForm.MyForm"
     * @return an {@link OpenFormResult} with the opened editor part on success, or an error
     */
    public static OpenFormResult openForm(String projectName, String formPath)
    {
        String relativePath = MetadataPathResolver.resolveFormFilePath(formPath);
        if (relativePath == null)
        {
            return OpenFormResult.error(ToolResult.error(
                "Cannot resolve form path: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected format: 'MetadataType.ObjectName.Forms.FormName' " + //$NON-NLS-1$
                "or 'CommonForm.FormName'.").toJson()); //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return OpenFormResult.error(ToolResult.error("Project not found: " + projectName).toJson()); //$NON-NLS-1$
        }

        IFile formFile = project.getFile(new Path(relativePath));
        if (!formFile.exists())
        {
            return OpenFormResult.error(ToolResult.error(
                "Form file not found: " + relativePath + " in project " + projectName).toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IWorkbenchPage page = getWorkbenchPage();
        if (page == null)
        {
            return OpenFormResult.error(ToolResult.error("No active workbench page").toJson()); //$NON-NLS-1$
        }

        // Resolve the typed DT editor input (model + feature) BEFORE opening. Opening the form
        // editor with a raw FileEditorInput goes through DtGranularEditor's bridge init, which
        // calls ContentUtil.createGranularEditorInput(file); if the form model is not yet
        // resolvable from the BM model that bridge throws a PartInitException during async part
        // rendering and the editor is left half-initialized (no WYSIWYG viewer). Resolving the
        // granular input here lets us (a) wait until the model is available and (b) open with the
        // already-resolved input, and (c) report a precise error instead of a misleading one.
        IEditorInput editorInput = resolveGranularEditorInput(formFile);
        if (editorInput == null)
        {
            return OpenFormResult.error(ToolResult.error(
                "Could not resolve the form model for: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "The project may still be loading or building its model; " + //$NON-NLS-1$
                "wait for the project to finish loading and try again.").toJson()); //$NON-NLS-1$
        }

        try
        {
            // Close existing editor so we apply current render mode. The form editor matching
            // strategy recognizes a FileEditorInput for an already-open granular editor.
            IEditorPart existingEditor = page.findEditor(new FileEditorInput(formFile));
            if (existingEditor != null)
            {
                page.closeEditor(existingEditor, false);
            }

            IEditorPart editorPart = IDE.openEditor(page, editorInput, FORM_EDITOR_ID, true);
            if (editorPart == null)
            {
                return OpenFormResult.error(
                    ToolResult.error("Could not open form editor for: " + formPath).toJson()); //$NON-NLS-1$
            }

            // Bring the editor to the top and give it focus so its WYSIWYG page builds and the
            // native render targets it; without this the just-opened editor can stay behind the
            // previously active one and the capture would read the wrong form.
            page.activate(editorPart);
            activateFormMainPage(editorPart);
            return OpenFormResult.success(editorPart, formFile);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to open form editor for: " + formPath, e); //$NON-NLS-1$
            return OpenFormResult.error(
                ToolResult.error("Failed to open form editor: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the EDT granular editor input ({@code IDtEditorInput}) for a form file, retrying
     * while the project's BM model is still loading.
     * <p>
     * {@link ContentUtil#createGranularEditorInput(IFile)} returns {@code null} when the form's
     * top object cannot be resolved from the BM model yet (e.g. the project is still being built
     * after EDT startup). Right after startup this is transient, so we poll a few times, pumping
     * the SWT event loop between attempts. Must be called on the UI thread.
     *
     * @param formFile the form {@code Form.form} file
     * @return the resolved editor input, or {@code null} if it never became available
     */
    private static IEditorInput resolveGranularEditorInput(IFile formFile)
    {
        Display display = Display.getCurrent();
        for (int i = 0; i < EDITOR_INPUT_RESOLVE_RETRIES; i++)
        {
            try
            {
                IEditorInput input = ContentUtil.createGranularEditorInput(formFile);
                if (input != null)
                {
                    return input;
                }
            }
            catch (Exception e)
            {
                // Model resolution can fail transiently while the project loads; keep retrying.
                Activator.logWarning("Form model not resolvable yet: " + e.getMessage()); //$NON-NLS-1$
            }

            processEvents(display);
            sleep(EDITOR_INPUT_RESOLVE_INTERVAL_MS);
            processEvents(display);
        }
        return null;
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
                // The WYSIWYG page (FormEditorPage, id "editors.form.pages.main") builds its
                // controls only once it becomes the active page of the multi-page form editor,
                // and its wysiwygViewer is then created asynchronously (createPageControls schedules
                // it via getMappingRootAsync + Display.asyncExec, setting formControlsCreated=true
                // when done). Re-activate the main page each iteration so its createPartControl runs,
                // then accept the page only once the viewer actually exists.
                activateActiveFormMainPage();

                Object page = getActiveFormEditorPage();
                if (page != null && isWysiwygPageReady(page))
                {
                    return page;
                }
            }
            catch (Exception e)
            {
                // Editor still initializing, keep waiting
            }

            sleep(WYSIWYG_WAIT_INTERVAL_MS);
            processEvents(display);
        }

        // Final attempt: return the page only if its WYSIWYG viewer is actually available.
        try
        {
            Object page = getActiveFormEditorPage();
            return (page != null && isWysiwygPageReady(page)) ? page : null;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get form editor page after waiting", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Waits for the WYSIWYG (main) page of a <i>specific</i> form editor part to become available,
     * resolving it directly from that editor via {@code findPage("editors.form.pages.main")} rather
     * than via the global {@code FormEditor.getActiveFormEditorPage()} lookup. This guarantees the
     * returned page belongs to the editor that was opened for the requested form, even if another
     * form editor currently holds workbench focus (the Bitrix #19889 wrong-form-screenshot case).
     * Must be called on the UI thread.
     *
     * @param editorPart the {@code FormEditor} part returned by {@link #openForm(String, String)}
     * @return the {@code FormEditorPage}, or {@code null} if it does not become ready in time
     */
    public static Object waitForFormEditorPageOf(IEditorPart editorPart)
    {
        if (editorPart == null)
        {
            return null;
        }
        Display display = Display.getCurrent();
        for (int i = 0; i < WYSIWYG_WAIT_RETRIES; i++)
        {
            processEvents(display);
            try
            {
                // Re-activate the main page each iteration so its createPartControl runs and the
                // viewer is created asynchronously, then accept the page only once it is ready.
                activateFormMainPage(editorPart);
                Object pageObject = findFormMainPage(editorPart);
                if (pageObject != null && isWysiwygPageReady(pageObject))
                {
                    return pageObject;
                }
            }
            catch (Exception e)
            {
                // Editor still initializing, keep waiting
            }
            sleep(WYSIWYG_WAIT_INTERVAL_MS);
            processEvents(display);
        }

        try
        {
            Object pageObject = findFormMainPage(editorPart);
            return (pageObject != null && isWysiwygPageReady(pageObject)) ? pageObject : null;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get form editor page for the opened editor", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolves the WYSIWYG (main) {@code FormEditorPage} of a specific form editor part via the
     * Eclipse Forms {@code FormEditor.findPage(String)} API (reflectively, since the concrete type
     * is internal to EDT). Returns {@code null} when the part is not a form editor or the page does
     * not exist yet.
     *
     * @param editorPart the form editor part
     * @return the main {@code FormEditorPage}, or {@code null}
     */
    private static Object findFormMainPage(IEditorPart editorPart) throws Exception
    {
        Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
        if (!editorClass.isInstance(editorPart))
        {
            return null;
        }
        Method findPageMethod = ReflectionUtils.findMethod(editorPart.getClass(), FIND_PAGE_METHOD, String.class);
        if (findPageMethod == null)
        {
            return null;
        }
        findPageMethod.setAccessible(true);
        return findPageMethod.invoke(editorPart, FORM_MAIN_PAGE_ID);
    }

    /**
     * Reports whether the form editor WYSIWYG page has finished building its controls, i.e. the
     * {@code wysiwygViewer} field is populated. The page sets {@code formControlsCreated} to
     * {@code true} only after the asynchronous control creation (which assigns the viewer)
     * completes, so it is used as a confirming signal when present.
     *
     * @param formEditorPage the {@code FormEditorPage} instance
     * @return {@code true} when the WYSIWYG viewer is available
     */
    private static boolean isWysiwygPageReady(Object formEditorPage)
    {
        try
        {
            Object viewer = ReflectionUtils.getFieldValue(formEditorPage, WYSIWYG_VIEWER_FIELD);
            if (viewer == null)
            {
                return false;
            }
            Object controlsCreated = ReflectionUtils.getFieldValue(formEditorPage, FORM_CONTROLS_CREATED_FIELD);
            // If the flag field is missing in this EDT version, fall back to viewer presence only.
            return !(controlsCreated instanceof Boolean) || ((Boolean)controlsCreated).booleanValue();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Activates the WYSIWYG (main) page of the currently active form editor, if any.
     * Triggers lazy creation of the page controls. Must be called on the UI thread.
     */
    private static void activateActiveFormMainPage()
    {
        IWorkbenchPage page = getWorkbenchPage();
        if (page == null)
        {
            return;
        }
        IEditorPart editorPart = page.getActiveEditor();
        if (editorPart != null)
        {
            activateFormMainPage(editorPart);
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

    /**
     * Returns the FQN of the metadata model behind a form editor part, e.g.
     * {@code "Catalog.Products.Form.ItemForm"} or {@code "CommonForm.MyForm"}. The model is read
     * from the editor input ({@link ContentUtil#getModel(Object)} resolves the granular DT input or
     * a plain file input to its EObject) and its FQN is read via {@code IBmObject.bmGetFqn()}.
     * Returns {@code null} when the part is not a form editor or its model FQN cannot be resolved.
     * <p>
     * Used as the identity guard before capturing a screenshot so that, if the resolved editor/page
     * does not correspond to the requested form, the caller returns an explicit error instead of a
     * wrong-form image (Bitrix #19889).
     *
     * @param editorPart the form editor part
     * @return the model FQN, or {@code null}
     */
    public static String getFormEditorFqn(IEditorPart editorPart)
    {
        if (editorPart == null)
        {
            return null;
        }
        try
        {
            IEditorInput input = editorPart.getEditorInput();
            // ContentUtil.getModel resolves both EObject inputs and navigator-adaptable inputs.
            Object model = ContentUtil.getModel(input);
            String fqn = bmGetFqn(model);
            if (fqn != null)
            {
                return fqn;
            }
            // Fall back to the typed DT editor input's own getModel() (IDtEditorInput exposes the
            // form's metadata object directly) when the navigator adapter path yields nothing.
            Object typedModel = ReflectionUtils.invokeMethod(input, "getModel"); //$NON-NLS-1$
            return bmGetFqn(typedModel);
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not resolve form editor FQN: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reads {@code IBmObject.bmGetFqn()} from a model object via reflection (the BM interface is not
     * imported by name here). Returns {@code null} when unavailable.
     *
     * @param model the metadata model object
     * @return the FQN string, or {@code null}
     */
    private static String bmGetFqn(Object model)
    {
        if (model == null)
        {
            return null;
        }
        try
        {
            Method method = ReflectionUtils.findMethod(model.getClass(), "bmGetFqn"); //$NON-NLS-1$
            if (method == null)
            {
                return null;
            }
            method.setAccessible(true);
            Object fqn = method.invoke(model);
            return fqn != null ? fqn.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Tests whether an actual form-editor model FQN denotes the same form as a requested form FQN
     * path, tolerating the differences that are legal across the tool surface: case, English vs
     * Russian metadata type names (via {@link MetadataTypeUtils}) and the singular {@code Form} vs
     * plural {@code Forms} forms separator. For example the requested
     * {@code "Catalog.Products.Forms.ItemForm"} matches the editor model FQN
     * {@code "Catalog.Products.Form.ItemForm"}, and {@code "Справочник.Товары.Форма.X"} matches
     * {@code "Catalog.Товары.Form.X"}.
     *
     * @param actualFqn the FQN read from the opened editor's model (may be {@code null})
     * @param requestedFormPath the form FQN path the caller asked for
     * @return {@code true} when both denote the same form
     */
    public static boolean fqnMatchesFormPath(String actualFqn, String requestedFormPath)
    {
        String a = canonicalFormFqn(actualFqn);
        String b = canonicalFormFqn(requestedFormPath);
        return a != null && a.equals(b);
    }

    /**
     * Canonicalizes a form FQN/path for identity comparison: lowercases, normalizes the leading
     * metadata type segment to its English singular form, and collapses a {@code forms}/{@code form}
     * (or Russian {@code формы}/{@code форма}) separator segment in 4-part FQNs to a single token.
     * Returns {@code null} for unrecognized shapes.
     *
     * @param fqn a form FQN or path
     * @return a canonical comparison key, or {@code null}
     */
    private static String canonicalFormFqn(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length == 2)
        {
            String type = MetadataTypeUtils.toEnglishSingular(parts[0]);
            String typeKey = type != null ? type : parts[0];
            return (typeKey + "." + parts[1]).toLowerCase(); //$NON-NLS-1$
        }
        if (parts.length == 4)
        {
            String sep = parts[2].toLowerCase();
            boolean isFormsSeparator = "forms".equals(sep) || "form".equals(sep) //$NON-NLS-1$ //$NON-NLS-2$
                || "формы".equals(sep) || "форма".equals(sep); // формы / форма //$NON-NLS-1$ //$NON-NLS-2$
            if (!isFormsSeparator)
            {
                return null;
            }
            String type = MetadataTypeUtils.toEnglishSingular(parts[0]);
            String typeKey = type != null ? type : parts[0];
            return (typeKey + "." + parts[1] + ".form." + parts[3]).toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
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
     * <p>
     * {@code Control.print()} only produces a faithful image when the control is actually
     * shown on screen: for a control that is hidden, behind another editor tab, or on a
     * non-active editor it yields a blank or partial image while still reporting positive
     * dimensions, which would be reported as a successful (but empty) screenshot. To avoid
     * that false positive, this returns {@code null} unless the control is genuinely visible
     * and on top (its top-level shell is the display's active shell), so the caller falls
     * back to the explicit "form layout did not finish rendering" error instead.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return image data, or {@code null} if the control is unavailable, has invalid bounds,
     *         or is not visible/on top
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

        // Gate the print fallback on the control being shown and on top. isVisible() is
        // true only when the control and all its ancestors are visible (so a control on a
        // hidden/behind editor is excluded), and we additionally require its shell to be
        // the active shell so a background window does not yield a blank capture.
        if (!control.isVisible() || !isOnTop(control))
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
     * Returns {@code true} when the control's top-level shell is the display's active shell,
     * i.e. the control is on the window currently on top. Used to gate the {@code print()}
     * fallback so a background/non-active editor does not produce a blank capture.
     *
     * @param control the control to test
     * @return {@code true} when the control's shell is the active shell
     */
    private static boolean isOnTop(Control control)
    {
        Display display = control.getDisplay();
        if (display == null)
        {
            return false;
        }
        return control.getShell() == display.getActiveShell();
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
     * Public so callers (and tests) can choose a bounded wait, e.g. the layout-snapshot tool uses a
     * slightly longer budget than the screenshot tool while still pumping the SWT event loop.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @param renderedCheck predicate that returns {@code true} once the rendered content is available
     * @param timeoutMs maximum time to wait, in milliseconds
     * @return {@code true} if the form rendered within the timeout, {@code false} otherwise
     */
    public static boolean waitUntilRendered(Object wysiwygViewer, BooleanSupplier renderedCheck, int timeoutMs)
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
