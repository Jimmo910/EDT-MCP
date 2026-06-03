/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.mcore.AutoColor;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StyleElementType;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to set the type and value of a style item ({@link StyleItem}).
 * <p>
 * A style item created by {@code create_metadata_object} has no type and no
 * value yet. This tool assigns the element type ({@link StyleElementType#COLOR}
 * or {@link StyleElementType#FONT}) and builds the matching mcore value object:
 * <ul>
 * <li><b>Color</b>: a {@link ColorValue} wrapping a {@link ColorDef} (explicit
 * RGB, 0-255 each) or an {@link AutoColor} (the platform "automatic" color)
 * when {@code auto=true};</li>
 * <li><b>Font</b>: a {@link FontValue} wrapping a {@link FontDef} with optional
 * face name, height and bold/italic/underline/strikeout flags.</li>
 * </ul>
 * The mutation runs on the UI thread inside a BM write transaction, re-fetching
 * the style item by its {@code bmId} (see {@link AbstractMetadataWriteTool}).
 * After the transaction commits, the style item top object is force-exported to
 * its {@code .mdo} file (the BM commit only updates the in-memory model; the
 * model-to-file serialization runs asynchronously otherwise), so the new value is
 * persisted on disk. When that export cannot be forced the change is still
 * committed in memory and a {@code persistWarning} is returned.
 */
public class SetStyleItemValueTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "set_style_item_value"; //$NON-NLS-1$

    /** Minimum allowed RGB component value. */
    private static final int RGB_MIN = 0;
    /** Maximum allowed RGB component value. */
    private static final int RGB_MAX = 255;
    /** Sentinel meaning "argument not provided" for optional integer inputs. */
    private static final int UNSET = Integer.MIN_VALUE;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set the type and value of a style item (StyleItem). " //$NON-NLS-1$
            + "Use styleType 'Color' to assign an RGB color (red/green/blue 0-255) " //$NON-NLS-1$
            + "or an automatic color (auto=true). Use styleType 'Font' to assign a font " //$NON-NLS-1$
            + "(faceName, height and bold/italic/underline/strikeout flags). " //$NON-NLS-1$
            + "The style item must already exist (create it with create_metadata_object). " //$NON-NLS-1$
            + "Example: {projectName: 'MyProject', objectFqn: 'StyleItem.MyColor', " //$NON-NLS-1$
            + "styleType: 'Color', red: 255, green: 128, blue: 0}. " //$NON-NLS-1$
            + "Russian type names are also supported for objectFqn."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the style item (required), e.g. 'StyleItem.MyColor'. " //$NON-NLS-1$
                + "Russian type names are also supported.", true) //$NON-NLS-1$
            .stringProperty("styleType", //$NON-NLS-1$
                "Style element type (required): 'Color' or 'Font'.", true) //$NON-NLS-1$
            .booleanProperty("auto", //$NON-NLS-1$
                "Color only: when true, sets the platform automatic color instead of explicit RGB. " //$NON-NLS-1$
                + "Ignored for Font.") //$NON-NLS-1$
            .integerProperty("red", //$NON-NLS-1$
                "Color only: red component 0-255. Required for an explicit RGB color (auto=false).") //$NON-NLS-1$
            .integerProperty("green", //$NON-NLS-1$
                "Color only: green component 0-255. Required for an explicit RGB color (auto=false).") //$NON-NLS-1$
            .integerProperty("blue", //$NON-NLS-1$
                "Color only: blue component 0-255. Required for an explicit RGB color (auto=false).") //$NON-NLS-1$
            .stringProperty("faceName", //$NON-NLS-1$
                "Font only: font face name, e.g. 'Arial'.") //$NON-NLS-1$
            .integerProperty("height", //$NON-NLS-1$
                "Font only: font height (size) in points, a positive integer.") //$NON-NLS-1$
            .booleanProperty("bold", //$NON-NLS-1$
                "Font only: bold flag.") //$NON-NLS-1$
            .booleanProperty("italic", //$NON-NLS-1$
                "Font only: italic flag.") //$NON-NLS-1$
            .booleanProperty("underline", //$NON-NLS-1$
                "Font only: underline flag.") //$NON-NLS-1$
            .booleanProperty("strikeout", //$NON-NLS-1$
                "Font only: strikeout flag.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String styleType = JsonUtils.extractStringArgument(params, "styleType"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " //$NON-NLS-1$
                + "Usage: {projectName: 'MyProject', objectFqn: 'StyleItem.MyColor', " //$NON-NLS-1$
                + "styleType: 'Color', red: 255, green: 0, blue: 0}").toJson(); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("objectFqn is required. " //$NON-NLS-1$
                + "Example: 'StyleItem.MyColor'.").toJson(); //$NON-NLS-1$
        }
        if (styleType == null || styleType.isEmpty())
        {
            return ToolResult.error("styleType is required. Supported values: 'Color', 'Font'.").toJson(); //$NON-NLS-1$
        }

        StyleElementType elementType = parseStyleType(styleType);
        if (elementType == null)
        {
            return ToolResult.error("Unknown styleType '" + styleType //$NON-NLS-1$
                + "'. Supported values: 'Color', 'Font'.").toJson(); //$NON-NLS-1$
        }

        if (elementType == StyleElementType.COLOR)
        {
            return setColor(projectName, objectFqn, params);
        }
        return setFont(projectName, objectFqn, params);
    }

    private String setColor(String projectName, String objectFqn, Map<String, String> params)
    {
        boolean auto = JsonUtils.extractBooleanArgument(params, "auto", false); //$NON-NLS-1$

        final int red;
        final int green;
        final int blue;
        if (auto)
        {
            red = green = blue = 0;
        }
        else
        {
            red = JsonUtils.extractIntArgument(params, "red", UNSET); //$NON-NLS-1$
            green = JsonUtils.extractIntArgument(params, "green", UNSET); //$NON-NLS-1$
            blue = JsonUtils.extractIntArgument(params, "blue", UNSET); //$NON-NLS-1$
            if (red == UNSET || green == UNSET || blue == UNSET)
            {
                return ToolResult.error("For an explicit Color, red, green and blue (0-255) are required. " //$NON-NLS-1$
                    + "Alternatively set auto=true for the automatic color.").toJson(); //$NON-NLS-1$
            }
            String rangeError = validateRgb(red, green, blue);
            if (rangeError != null)
            {
                return ToolResult.error(rangeError).toJson();
            }
        }

        return mutate(projectName, objectFqn, item -> {
            item.setType(StyleElementType.COLOR);
            ColorValue colorValue = McoreFactory.eINSTANCE.createColorValue();
            if (auto)
            {
                AutoColor autoColor = McoreFactory.eINSTANCE.createAutoColor();
                colorValue.setValue(autoColor);
            }
            else
            {
                ColorDef colorDef = McoreFactory.eINSTANCE.createColorDef();
                colorDef.setRed(red);
                colorDef.setGreen(green);
                colorDef.setBlue(blue);
                colorValue.setValue(colorDef);
            }
            item.setValue(colorValue);
        }, summarizeColor(auto, red, green, blue));
    }

    private String setFont(String projectName, String objectFqn, Map<String, String> params)
    {
        final String faceName = JsonUtils.extractStringArgument(params, "faceName"); //$NON-NLS-1$
        final int height = JsonUtils.extractIntArgument(params, "height", UNSET); //$NON-NLS-1$
        final boolean hasFaceName = faceName != null && !faceName.isEmpty();
        final boolean hasHeight = height != UNSET;
        final boolean bold = JsonUtils.extractBooleanArgument(params, "bold", false); //$NON-NLS-1$
        final boolean italic = JsonUtils.extractBooleanArgument(params, "italic", false); //$NON-NLS-1$
        final boolean underline = JsonUtils.extractBooleanArgument(params, "underline", false); //$NON-NLS-1$
        final boolean strikeout = JsonUtils.extractBooleanArgument(params, "strikeout", false); //$NON-NLS-1$
        final boolean hasStyleFlag = hasFlag(params, "bold") || hasFlag(params, "italic") //$NON-NLS-1$ //$NON-NLS-2$
            || hasFlag(params, "underline") || hasFlag(params, "strikeout"); //$NON-NLS-1$ //$NON-NLS-2$

        if (!hasFaceName && !hasHeight && !hasStyleFlag)
        {
            return ToolResult.error("For a Font at least one of faceName, height, bold, italic, " //$NON-NLS-1$
                + "underline or strikeout must be provided.").toJson(); //$NON-NLS-1$
        }
        if (hasHeight && height <= 0)
        {
            return ToolResult.error("Font height must be a positive integer, got " + height + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return mutate(projectName, objectFqn, item -> {
            item.setType(StyleElementType.FONT);
            FontValue fontValue = McoreFactory.eINSTANCE.createFontValue();
            FontDef fontDef = McoreFactory.eINSTANCE.createFontDef();
            if (hasFaceName)
            {
                fontDef.setFaceName(faceName);
            }
            if (hasHeight)
            {
                fontDef.setHeight((float)height);
            }
            fontDef.setBold(bold);
            fontDef.setItalic(italic);
            fontDef.setUnderline(underline);
            fontDef.setStrikeout(strikeout);
            fontValue.setValue(fontDef);
            item.setValue(fontValue);
        }, summarizeFont(hasFaceName ? faceName : null, hasHeight ? height : UNSET,
            bold, italic, underline, strikeout));
    }

    /**
     * Resolves the project, locates the style item, then applies {@code mutation}
     * to a fresh copy inside a BM write transaction. Centralizes the shared
     * project/BM/transaction boilerplate for the Color and Font paths.
     *
     * @param projectName the EDT project name
     * @param objectFqn the style item FQN (e.g. {@code StyleItem.MyColor})
     * @param mutation the model change to apply to the re-fetched style item
     * @param appliedSummary a short human-readable summary of the applied value
     * @return the JSON tool result
     */
    private String mutate(String projectName, String objectFqn, StyleItemMutation mutation, String appliedSummary)
    {
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        String normalizedFqn = MetadataTypeUtils.normalizeFqn(objectFqn);
        String[] parts = normalizedFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Invalid FQN: " + objectFqn + ". Expected 'StyleItem.Name'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MdObject object = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (object == null)
        {
            return ToolResult.error("Style item not found: " + normalizedFqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Create it first with create_metadata_object, " //$NON-NLS-1$
                + "or use get_metadata_objects to list existing style items.").toJson(); //$NON-NLS-1$
        }
        if (!(object instanceof StyleItem))
        {
            return ToolResult.error("Object '" + normalizedFqn + "' is not a StyleItem (it is a " //$NON-NLS-1$ //$NON-NLS-2$
                + object.eClass().getName() + "). This tool only applies to style items.").toJson(); //$NON-NLS-1$
        }
        if (!(object instanceof IBmObject))
        {
            return ToolResult.error("Style item is not a BM object").toJson(); //$NON-NLS-1$
        }
        final long bmId = ((IBmObject)object).bmGetId();

        try
        {
            bmModel.execute(new AbstractBmTask<Void>("SetStyleItemValue") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject fresh = (MdObject)tx.getObjectById(bmId);
                    if (fresh == null)
                    {
                        throw new RuntimeException("Style item not found in transaction"); //$NON-NLS-1$
                    }
                    if (!(fresh instanceof StyleItem))
                    {
                        throw new RuntimeException("Object is no longer a StyleItem in transaction"); //$NON-NLS-1$
                    }
                    mutation.apply((StyleItem)fresh);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error setting style item value", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set style item value: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // The BM transaction commits the new value into the in-memory model, but the
        // model-to-file serialization runs asynchronously, so the style item's .mdo
        // file is not updated until that background export completes. Drive the export
        // and the stale-marker refresh synchronously through the shared metadata-write
        // helper (the same path used by add_enum_value / set_object_property), so the
        // value is persisted on disk and queryable immediately. The object was already
        // verified to be an IBmObject above, so topObjectFqnOf resolves the FQN.
        String persistError = persistAndRevalidate(project, topObjectFqnOf(object));

        ToolResult result = ToolResult.success()
            .put("objectFqn", normalizedFqn) //$NON-NLS-1$
            .put("applied", appliedSummary); //$NON-NLS-1$
        if (persistError != null)
        {
            result.put("persistWarning", "Value set in the in-memory model but the export to the " //$NON-NLS-1$ //$NON-NLS-2$
                + ".mdo file could not be forced: " + persistError); //$NON-NLS-1$
        }
        return result
            .put("message", "Style item '" + normalizedFqn + "' value set (" + appliedSummary //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "). Run get_metadata_details or export_configuration_to_xml to verify.") //$NON-NLS-1$
            .toJson();
    }

    private static String validateRgb(int red, int green, int blue)
    {
        if (outOfRange(red))
        {
            return "red must be in range " + RGB_MIN + "-" + RGB_MAX + ", got " + red + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        if (outOfRange(green))
        {
            return "green must be in range " + RGB_MIN + "-" + RGB_MAX + ", got " + green + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        if (outOfRange(blue))
        {
            return "blue must be in range " + RGB_MIN + "-" + RGB_MAX + ", got " + blue + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return null;
    }

    private static boolean outOfRange(int component)
    {
        return component < RGB_MIN || component > RGB_MAX;
    }

    private static StyleElementType parseStyleType(String styleType)
    {
        String normalized = styleType.trim();
        if ("Color".equalsIgnoreCase(normalized)) //$NON-NLS-1$
        {
            return StyleElementType.COLOR;
        }
        if ("Font".equalsIgnoreCase(normalized)) //$NON-NLS-1$
        {
            return StyleElementType.FONT;
        }
        return null;
    }

    private static boolean hasFlag(Map<String, String> params, String name)
    {
        String value = JsonUtils.extractStringArgument(params, name);
        return value != null && !value.isEmpty();
    }

    private static String summarizeColor(boolean auto, int red, int green, int blue)
    {
        if (auto)
        {
            return "Color=Auto"; //$NON-NLS-1$
        }
        return "Color RGB(" + red + ", " + green + ", " + blue + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static String summarizeFont(String faceName, int height, boolean bold, boolean italic,
        boolean underline, boolean strikeout)
    {
        StringBuilder sb = new StringBuilder("Font"); //$NON-NLS-1$
        if (faceName != null)
        {
            sb.append(" face='").append(faceName).append('\''); //$NON-NLS-1$
        }
        if (height != UNSET)
        {
            sb.append(" height=").append(height); //$NON-NLS-1$
        }
        if (bold)
        {
            sb.append(" bold"); //$NON-NLS-1$
        }
        if (italic)
        {
            sb.append(" italic"); //$NON-NLS-1$
        }
        if (underline)
        {
            sb.append(" underline"); //$NON-NLS-1$
        }
        if (strikeout)
        {
            sb.append(" strikeout"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Functional change applied to a style item that has already been re-fetched
     * inside the active BM transaction.
     */
    @FunctionalInterface
    private interface StyleItemMutation
    {
        void apply(StyleItem item);
    }
}
