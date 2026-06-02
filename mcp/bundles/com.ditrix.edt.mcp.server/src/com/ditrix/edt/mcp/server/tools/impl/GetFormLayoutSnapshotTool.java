/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

/**
 * Tool to extract the structure tree of an EDT form, with best-effort WYSIWYG
 * element bounds.
 * <p>
 * <b>Why structure first, bounds best-effort.</b> EDT renders form layout
 * through two distinct pipelines:
 * <ul>
 * <li>the <i>native render</i> pipeline ({@code NativeRenderService}) produces
 * an offscreen pixel image plus the overall form size; this is what
 * {@code get_form_screenshot} reads and it is always on when buffered native
 * render is enabled; and</li>
 * <li>a legacy <i>projection</i> pipeline (model / layout / view projections)
 * that exposes per-element {@code Layout} rectangles and light-control bounds.</li>
 * </ul>
 * The projection pipeline is only populated when the form is rebuilt with
 * {@code NativeRenderService.isNativeRender() == false}: in
 * {@code FormWysiwygRepresentation}'s rebuild task the
 * {@code modelProjection.changeRoot(...)} / {@code layoutProjection.changeRoot(...)}
 * calls live in the {@code else} branch of an {@code if (isNativeRender()) return;}
 * guard. Because buffered native render (required for the screenshot) implies
 * native render, those projections are never rooted, so per-element rectangles
 * are not exposed and the native form data carries only the form-level size. The
 * previous implementation read those empty projections and hard-failed with
 * "the form layout did not finish rendering" even though the form was fine.
 * <p>
 * This tool therefore returns the form's element <b>structure</b> tree (name,
 * type, hierarchy and display-affecting properties) read directly from the form
 * model ({@code com._1c.g5.v8.dt.form.model.Form}), which needs no render and is
 * always available, and attaches per-element pixel bounds on a best-effort basis
 * (present only when the projection pipeline happens to be populated, e.g. in
 * non-native render mode). It never hard-fails on a real form.
 */
public class GetFormLayoutSnapshotTool implements IMcpTool
{
    public static final String NAME = "get_form_layout_snapshot"; //$NON-NLS-1$

    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$
    private static final String WYSIWYG_REPRESENTATION_FIELD = "wysiwygRepresentation"; //$NON-NLS-1$
    private static final String FORM_FIELD = "form"; //$NON-NLS-1$
    private static final String MODE_COMPACT = "compact"; //$NON-NLS-1$
    private static final String MODE_FULL = "full"; //$NON-NLS-1$
    private static final int RENDER_WAIT_TIMEOUT_MS = 10000;

    /**
     * Display-affecting properties read from form-model items in compact mode.
     * These are the properties that change what a user sees on the form. In full
     * mode every non-containment feature is emitted instead.
     */
    private static final List<String> DISPLAY_PROPERTY_NAMES = List.of(
        "visible", "userVisible", "enabled", "readOnly", "skipOnInput", "defaultItem", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "title", "titleLocation", "titleHeight", "dataPath", "type", "kind", "viewMode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "group", "groupHorizontalAlign", "groupVerticalAlign", "showTitle", "showInHeader", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "representation", "width", "height", "autoMaxWidth", "autoMaxHeight", "maxWidth", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "maxHeight", "horizontalStretch", "verticalStretch", "horizontalAlign", "displayImportance"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Return a YAML snapshot of a form's element STRUCTURE tree: element name, type, hierarchy " + //$NON-NLS-1$
            "and display-affecting properties (visible/enabled/title/dataPath/group/...), read from the form " + //$NON-NLS-1$
            "model. Per-element pixel bounds are attached best-effort: they are present only when EDT runs the " + //$NON-NLS-1$
            "legacy projection layout pipeline (non-native render mode); under the default buffered native " + //$NON-NLS-1$
            "render the per-element projection bounds are not exposed, so bounds are omitted while the structure " + //$NON-NLS-1$
            "is still returned. Use get_form_screenshot for a pixel-accurate visual. Opens/activates the form " + //$NON-NLS-1$
            "automatically when formPath is given."; //$NON-NLS-1$
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
                "'CommonForm.MyForm'. If not specified, uses the currently active form editor.") //$NON-NLS-1$
            .booleanProperty("refresh", "Force WYSIWYG refresh before snapshot (default: true). " + //$NON-NLS-1$ //$NON-NLS-2$
                "Only affects best-effort bounds; the structure tree is read regardless.") //$NON-NLS-1$
            .stringProperty("mode", "Output mode: 'compact' (default) or 'full'. Compact returns the element " + //$NON-NLS-1$ //$NON-NLS-2$
                "tree with selected display properties. Full returns all non-containment model properties.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.TEXT;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        String refreshParam = JsonUtils.extractStringArgument(params, "refresh"); //$NON-NLS-1$
        String rawMode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        String mode = normalizeMode(rawMode);
        boolean refresh = refreshParam == null || "true".equalsIgnoreCase(refreshParam); //$NON-NLS-1$

        if (mode == null)
        {
            return errorYaml("Invalid mode: " + rawMode + ". Expected 'compact' or 'full'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (formPath != null && !formPath.isEmpty()
            && (projectName == null || projectName.isEmpty()))
        {
            return errorYaml("projectName is required when formPath is specified"); //$NON-NLS-1$
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return errorYaml("Display is not available"); //$NON-NLS-1$
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        display.syncExec(() -> resultRef.set(captureLayoutSnapshot(projectName, formPath, refresh, mode)));
        return resultRef.get();
    }

    private String captureLayoutSnapshot(String projectName, String formPath, boolean refresh, String mode)
    {
        List<String> warnings = new ArrayList<>();
        boolean fullMode = MODE_FULL.equals(mode);

        try
        {
            Object editorPage = resolveEditorPage(projectName, formPath);
            if (editorPage == null)
            {
                if (formPath != null && !formPath.isEmpty())
                {
                    return errorYaml("Form editor opened but WYSIWYG page is not available. " + //$NON-NLS-1$
                        "The form may still be loading or rendering; try again."); //$NON-NLS-1$
                }
                return errorYaml("No active form editor page found. Specify formPath to open a form automatically."); //$NON-NLS-1$
            }

            Object wysiwygViewer = ReflectionUtils.getFieldValue(editorPage, WYSIWYG_VIEWER_FIELD);
            if (wysiwygViewer == null)
            {
                return errorYaml("WYSIWYG viewer is not available"); //$NON-NLS-1$
            }

            if (refresh)
            {
                EditorScreenshotHelper.refreshViewer(wysiwygViewer);
            }

            Object representation = ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
            if (representation == null)
            {
                return errorYaml("WYSIWYG representation is not available"); //$NON-NLS-1$
            }

            // Drive the native render so that (a) the form image is available (used for formSize) and
            // (b) the legacy projection pipeline gets a chance to populate if EDT happens to run in
            // non-native render mode (then best-effort bounds become available). The structure tree
            // itself does not need this, so a render that never produces bounds is not an error.
            EditorScreenshotHelper.waitUntilRendered(wysiwygViewer,
                () -> EditorScreenshotHelper.readFormImageData(representation) != null
                    || boundsProbe(representation) != null,
                RENDER_WAIT_TIMEOUT_MS);

            // The content form model holds the authoritative element tree. It is read directly from
            // the representation (a com._1c.g5.v8.dt.form.model.Form) and needs no render to inspect.
            Object form = ReflectionUtils.getFieldValue(representation, FORM_FIELD);
            if (!(form instanceof EObject))
            {
                return errorYaml("Form model is not available from the editor. " + //$NON-NLS-1$
                    "The form may still be loading; try again."); //$NON-NLS-1$
            }

            List<Map<String, Object>> elements = collectFormItems((EObject)form, representation, fullMode);
            int elementCount = countElements(elements);
            int elementsWithBounds = countElementsWithBounds(elements);
            boolean boundsAvailable = elementsWithBounds > 0;

            if (!boundsAvailable)
            {
                warnings.add("Per-element bounds are unavailable: EDT is rendering with the buffered native " //$NON-NLS-1$
                    + "pipeline, which does not expose per-element projection rectangles (only the overall form " //$NON-NLS-1$
                    + "image). The element structure tree below is complete; use get_form_screenshot for a " //$NON-NLS-1$
                    + "pixel-accurate visual."); //$NON-NLS-1$
            }

            Map<String, Object> formSize = getFormSize(wysiwygViewer, refresh);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true); //$NON-NLS-1$
            result.put("projectName", projectName); //$NON-NLS-1$
            result.put("formPath", formPath); //$NON-NLS-1$
            result.put("mode", mode); //$NON-NLS-1$
            result.put("formName", getName(form)); //$NON-NLS-1$
            result.put("formTitle", getStringValue(form, "getTitle")); //$NON-NLS-1$ //$NON-NLS-2$
            if (formSize != null)
            {
                result.put("formSize", formSize); //$NON-NLS-1$
            }
            result.put("elementCount", elementCount); //$NON-NLS-1$
            result.put("boundsAvailable", boundsAvailable); //$NON-NLS-1$
            result.put("elementsWithBounds", elementsWithBounds); //$NON-NLS-1$
            result.put("boundsNote", boundsAvailable //$NON-NLS-1$
                ? "Per-element bounds are pixel rectangles relative to the form WYSIWYG root." //$NON-NLS-1$
                : "Per-element bounds are best-effort and omitted in buffered native render mode (see warnings)."); //$NON-NLS-1$
            result.put("warnings", warnings); //$NON-NLS-1$
            result.put("elements", elements); //$NON-NLS-1$
            return dumpYaml(result);
        }
        catch (IllegalStateException e)
        {
            return errorYaml(e.getMessage());
        }
        catch (Exception e)
        {
            Activator.logError("Failed to capture form layout snapshot", e); //$NON-NLS-1$
            return errorYaml("Failed to capture form layout snapshot: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private Object resolveEditorPage(String projectName, String formPath) throws Exception
    {
        if (formPath != null && !formPath.isEmpty())
        {
            // Ensure buffered native render is on, matching the screenshot tool, so the form opens and
            // renders the same way (and the form model becomes resolvable through the editor).
            EditorScreenshotHelper.ensureBufferedNativeRenderMode();

            String openError = EditorScreenshotHelper.openAndActivateForm(projectName, formPath);
            if (openError != null)
            {
                throw new IllegalStateException(extractToolErrorMessage(openError));
            }

            Display display = Display.getCurrent();
            for (int i = 0; i < 5; i++)
            {
                EditorScreenshotHelper.processEvents(display);
                Thread.sleep(100);
            }

            return EditorScreenshotHelper.waitForFormEditorPage();
        }

        return EditorScreenshotHelper.getActiveFormEditorPage();
    }

    /**
     * Best-effort probe used while waiting for a render: returns a non-null marker once the legacy
     * projection pipeline can resolve a light control for any top-level form item (i.e. bounds are
     * obtainable), or {@code null} otherwise. Never throws.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @return a non-null marker if bounds are obtainable, {@code null} otherwise
     */
    private Object boundsProbe(Object representation)
    {
        try
        {
            Object form = ReflectionUtils.getFieldValue(representation, FORM_FIELD);
            if (!(form instanceof EObject))
            {
                return null;
            }
            for (EObject item : getItems((EObject)form))
            {
                if (getElementBounds(representation, item) != null)
                {
                    return Boolean.TRUE;
                }
            }
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private String normalizeMode(String mode)
    {
        if (mode == null || mode.isEmpty() || MODE_COMPACT.equalsIgnoreCase(mode))
        {
            return MODE_COMPACT;
        }
        if (MODE_FULL.equalsIgnoreCase(mode))
        {
            return MODE_FULL;
        }
        return null;
    }

    private String extractToolErrorMessage(String errorJson)
    {
        try
        {
            JsonObject object = JsonParser.parseString(errorJson).getAsJsonObject();
            if (object.has("error")) //$NON-NLS-1$
            {
                return object.get("error").getAsString(); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            return errorJson;
        }
        return errorJson;
    }

    /**
     * Collects the structure items for the children of a form-model container (the form root or a
     * group), descending recursively. The container's own item is created by the caller.
     *
     * @param container the form-model container ({@code Form} or {@code FormGroup}/{@code FormTable}…)
     * @param representation the WYSIWYG representation, used for best-effort bounds
     * @param fullMode whether to emit all non-containment properties
     * @return the list of child element items
     */
    private List<Map<String, Object>> collectFormItems(EObject container, Object representation, boolean fullMode)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        for (EObject child : getItems(container))
        {
            Map<String, Object> item = createElementItem(child, representation, fullMode);
            List<Map<String, Object>> grandChildren = collectFormItems(child, representation, fullMode);
            if (!grandChildren.isEmpty())
            {
                item.put("children", grandChildren); //$NON-NLS-1$
            }
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> createElementItem(EObject element, Object representation, boolean fullMode)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", getEType(element)); //$NON-NLS-1$
        putIfNotNull(item, "name", getName(element)); //$NON-NLS-1$
        Object id = invokeNoArg(element, "getId"); //$NON-NLS-1$
        if (id instanceof Number)
        {
            item.put("id", ((Number)id).intValue()); //$NON-NLS-1$
        }
        putIfNotNull(item, "title", getStringValue(element, "getTitle")); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> bounds = getElementBounds(representation, element);
        if (bounds != null)
        {
            item.put("bounds", bounds); //$NON-NLS-1$
        }

        Map<String, Object> properties = collectProperties(element, fullMode);
        if (!properties.isEmpty())
        {
            item.put("properties", properties); //$NON-NLS-1$
        }
        return item;
    }

    /**
     * Best-effort per-element pixel bounds via the public representation API:
     * {@code getRelatedControl(FormVisualEntity)} resolves the light control through the view
     * projection and {@code getBoundsRelativeWysiwygRoot(ILightControl)} returns its rectangle.
     * Returns {@code null} when the view projection is not populated (the buffered native render
     * case), so callers treat bounds as optional.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @param formEntity a form-model item ({@code FormVisualEntity})
     * @return a bounds map with positive size, or {@code null} when unavailable
     */
    private Map<String, Object> getElementBounds(Object representation, Object formEntity)
    {
        Object control = invokeOneArg(representation, "getRelatedControl", formEntity); //$NON-NLS-1$
        if (control == null)
        {
            return null;
        }
        Object rect = invokeOneArg(representation, "getBoundsRelativeWysiwygRoot", control); //$NON-NLS-1$
        if (!(rect instanceof Rectangle))
        {
            return null;
        }
        Rectangle rectangle = (Rectangle)rect;
        if (rectangle.width <= 0 || rectangle.height <= 0)
        {
            return null;
        }
        return boundsMap(rectangle.x, rectangle.y, rectangle.width, rectangle.height);
    }

    @SuppressWarnings("unchecked")
    private List<EObject> getItems(EObject container)
    {
        Object items = invokeNoArg(container, "getItems"); //$NON-NLS-1$
        if (items instanceof List<?>)
        {
            return (List<EObject>)items;
        }
        return List.of();
    }

    private Map<String, Object> boundsMap(int left, int top, int width, int height)
    {
        Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("left", left); //$NON-NLS-1$
        bounds.put("top", top); //$NON-NLS-1$
        bounds.put("width", width); //$NON-NLS-1$
        bounds.put("height", height); //$NON-NLS-1$
        bounds.put("right", left + width); //$NON-NLS-1$
        bounds.put("bottom", top + height); //$NON-NLS-1$
        return bounds;
    }

    private Map<String, Object> collectProperties(EObject object, boolean fullMode)
    {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures())
        {
            if (!fullMode && !DISPLAY_PROPERTY_NAMES.contains(feature.getName()))
            {
                continue;
            }
            if (feature.isMany() && !object.eIsSet(feature))
            {
                continue;
            }
            if (feature instanceof EReference && ((EReference)feature).isContainment())
            {
                continue;
            }

            Object value = object.eGet(feature, false);
            Object converted = convertFeatureValue(value);
            if (converted != null)
            {
                properties.put(feature.getName(), converted);
            }
        }
        return properties;
    }

    private String dumpYaml(Map<String, Object> result)
    {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        return new Yaml(options).dump(result);
    }

    private String errorYaml(String message)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false); //$NON-NLS-1$
        result.put("error", message); //$NON-NLS-1$
        return dumpYaml(result);
    }

    private Object convertFeatureValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Enum<?>)
        {
            return String.valueOf(value);
        }
        if (value instanceof Number || value instanceof Boolean)
        {
            return value;
        }
        if (value instanceof String)
        {
            return value;
        }
        if (value instanceof EObject)
        {
            return describeEObject(value);
        }
        if (value instanceof org.eclipse.emf.common.util.EMap<?, ?>)
        {
            return convertEMap((org.eclipse.emf.common.util.EMap<?, ?>)value);
        }
        if (value instanceof Collection<?>)
        {
            List<Object> values = new ArrayList<>();
            for (Object item : (Collection<?>)value)
            {
                Object converted = convertFeatureValue(item);
                if (converted != null)
                {
                    values.add(converted);
                }
                if (values.size() >= 50)
                {
                    values.add("..."); //$NON-NLS-1$
                    break;
                }
            }
            return values.isEmpty() ? null : values;
        }

        // Data paths and similar value objects expose a meaningful toString().
        String text = String.valueOf(value);
        return text.contains("@") ? value.getClass().getSimpleName() : text; //$NON-NLS-1$
    }

    private Object convertEMap(org.eclipse.emf.common.util.EMap<?, ?> map)
    {
        if (map.isEmpty())
        {
            return null;
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object key = entry.getKey();
            Object converted2 = convertFeatureValue(entry.getValue());
            if (converted2 != null)
            {
                converted.put(key == null || String.valueOf(key).isEmpty() ? "_" : String.valueOf(key), //$NON-NLS-1$
                    converted2);
            }
        }
        return converted.isEmpty() ? null : converted;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value)
    {
        if (value != null)
        {
            map.put(key, value);
        }
    }

    private Map<String, Object> describeEObject(Object value)
    {
        if (!(value instanceof EObject))
        {
            return null;
        }

        EObject object = (EObject)value;
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", getEType(object)); //$NON-NLS-1$
        String name = getName(object);
        if (name != null && !name.isBlank())
        {
            description.put("name", name); //$NON-NLS-1$
        }
        // Data paths render to a readable string (e.g. "Object.Description").
        String text = String.valueOf(object);
        if (!text.contains("@")) //$NON-NLS-1$
        {
            description.put("value", text); //$NON-NLS-1$
        }
        Object fqn = invokeNoArg(object, "bmGetFqn"); //$NON-NLS-1$
        if (fqn != null)
        {
            description.put("fqn", String.valueOf(fqn)); //$NON-NLS-1$
        }
        return description;
    }

    private String getEType(Object value)
    {
        if (value instanceof EObject)
        {
            EObject object = (EObject)value;
            String packageName = object.eClass().getEPackage() != null
                ? object.eClass().getEPackage().getName() : null;
            if (packageName != null && !packageName.isBlank())
            {
                return packageName + ":" + object.eClass().getName(); //$NON-NLS-1$
            }
            return object.eClass().getName();
        }
        return value != null ? value.getClass().getName() : null;
    }

    private String getName(Object value)
    {
        return firstNonBlank(getStringValue(value, "getName"), getStringValue(value, "getId")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String getStringValue(Object value, String methodName)
    {
        Object result = invokeNoArg(value, methodName);
        if (result == null)
        {
            return null;
        }
        if (result instanceof org.eclipse.emf.common.util.EMap<?, ?>)
        {
            return localizedValue((org.eclipse.emf.common.util.EMap<?, ?>)result);
        }
        String text = String.valueOf(result);
        return text.isEmpty() ? null : text;
    }

    /**
     * Returns a representative localized value from a {@code locale -> text} EMap (used for the form
     * {@code title}, which is multilingual). Prefers the first non-blank entry.
     *
     * @param map the localized values map
     * @return the first non-blank value, or {@code null}
     */
    private String localizedValue(org.eclipse.emf.common.util.EMap<?, ?> map)
    {
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            if (entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank())
            {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private String firstNonBlank(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isBlank())
            {
                return value;
            }
        }
        return null;
    }

    private Object invokeNoArg(Object target, String methodName)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private Object invokeOneArg(Object target, String methodName, Object argument)
    {
        if (target == null || argument == null)
        {
            return null;
        }
        try
        {
            for (Method method : target.getClass().getMethods())
            {
                if (method.getName().equals(methodName) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(argument))
                {
                    method.setAccessible(true);
                    return method.invoke(target, argument);
                }
            }
        }
        catch (Exception e)
        {
            return null;
        }
        return null;
    }

    private Map<String, Object> getFormSize(Object wysiwygViewer, boolean refresh) throws Exception
    {
        if (refresh)
        {
            ImageData imageData = EditorScreenshotHelper.extractFormImageData(wysiwygViewer);
            if (imageData != null)
            {
                return boundsMap(0, 0, imageData.width, imageData.height);
            }
        }

        ImageData controlImageData = EditorScreenshotHelper.captureControlImageData(wysiwygViewer);
        if (controlImageData != null)
        {
            return boundsMap(0, 0, controlImageData.width, controlImageData.height);
        }

        return null;
    }

    private int countElementsWithBounds(List<Map<String, Object>> elements)
    {
        int count = 0;
        for (Map<String, Object> element : elements)
        {
            if (element.get("bounds") != null) //$NON-NLS-1$
            {
                count++;
            }
            count += countElementsWithBounds(getChildren(element));
        }
        return count;
    }

    private int countElements(List<Map<String, Object>> elements)
    {
        int count = 0;
        for (Map<String, Object> element : elements)
        {
            count++;
            count += countElements(getChildren(element));
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getChildren(Map<String, Object> element)
    {
        Object children = element.get("children"); //$NON-NLS-1$
        if (children instanceof List<?>)
        {
            return (List<Map<String, Object>>)children;
        }
        return List.of();
    }
}
