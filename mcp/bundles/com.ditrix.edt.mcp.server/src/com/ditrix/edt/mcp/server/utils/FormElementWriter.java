/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormChildrenGroup;
import com._1c.g5.v8.dt.form.model.FormCommandInterface;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.ItemHorizontalAlignment;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Shared writer for the editable FORM CONTENT model ({@code com._1c.g5.v8.dt.form.model.Form}, a
 * separate top object reached from a {@code BasicForm} mdo via {@code getForm()}).
 *
 * <p>Form-MEMBER editing (adding a form attribute, command or visual item, and binding event
 * handlers) is performed REFLECTIVELY (by feature / classifier name) so those paths need no
 * compile-time dependency on the form model - the same technique the form-editing tools use. Form-
 * OBJECT creation ({@link #createForm}), in contrast, uses the typed {@code com._1c.g5.v8.dt.form.model}
 * API ({@code Form}, {@code AutoCommandBar}, {@code FormFactory}, ...) to build the renderable content
 * form with EDT's default structure.</p>
 *
 * <p>This is the canonical home for the form-write logic that {@code create_metadata} (and, until
 * they are removed, the {@code add_form_*} tools) use. Mutation MUST run inside a BM write transaction
 * on the re-fetched content form; the shared scaffold ({@link #resolveForEdit} +
 * {@link #writeEditableForm} / {@link #readEditableForm}) owns the resolve -&gt; transact -&gt;
 * force-export pipeline, so tools only supply the per-call work.</p>
 */
public final class FormElementWriter
{
    // Form-model feature names (reflective).
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    private static final String FEATURE_ATTRIBUTES = "attributes"; //$NON-NLS-1$
    private static final String FEATURE_FORM_COMMANDS = "formCommands"; //$NON-NLS-1$
    private static final String FEATURE_TITLE = "title"; //$NON-NLS-1$
    private static final String FEATURE_VALUE_TYPE = "valueType"; //$NON-NLS-1$
    private static final String FEATURE_TYPE = "type"; //$NON-NLS-1$
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$
    private static final String FEATURE_ID = "id"; //$NON-NLS-1$
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    private static final String FEATURE_VISIBLE = "visible"; //$NON-NLS-1$

    // Concrete form-model classifier names (resolved on the form EPackage).
    private static final String ECLASS_FORM_GROUP = "FormGroup"; //$NON-NLS-1$
    private static final String ECLASS_DECORATION = "Decoration"; //$NON-NLS-1$
    private static final String ECLASS_FORM_ITEM = "FormItem"; //$NON-NLS-1$
    private static final String ECLASS_USUAL_GROUP_EXT_INFO = "UsualGroupExtInfo"; //$NON-NLS-1$
    private static final String ECLASS_LABEL_DECORATION_EXT_INFO = "LabelDecorationExtInfo"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_USUAL_GROUP = "UsualGroup"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_LABEL = "Label"; //$NON-NLS-1$

    /** A supported form-element kind, resolved from a (bilingual) FQN kind token. */
    public enum Kind { ATTRIBUTE, COMMAND, GROUP, DECORATION, FIELD, BUTTON }

    /** A parsed form-member FQN: the form path (for {@code resolveMdForm}) + the leaf kind/name. */
    public static final class FormMemberRef
    {
        /** The owning form path, normalized to the {@code Type.Object.forms.FormName} /
         * {@code CommonForm.Name} shape that {@code FormStructureReader.resolveMdForm} expects. */
        public final String formPath;
        /** The raw element kind token (English or Russian); resolve via {@link #kindForToken}. */
        public final String kindToken;
        /** The element's programmatic name (for a handler FQN, the EVENT name). */
        public final String name;
        /** For an ITEM-LEVEL handler FQN, the owning item's kind token; {@code null} for a form-level
         * member or handler. */
        public final String itemKindToken;
        /** For an ITEM-LEVEL handler FQN, the owning item's name; {@code null} otherwise. */
        public final String itemName;

        FormMemberRef(String formPath, String kindToken, String name, String itemKindToken,
            String itemName)
        {
            this.formPath = formPath;
            this.kindToken = kindToken;
            this.name = name;
            this.itemKindToken = itemKindToken;
            this.itemName = itemName;
        }

        /** Whether the FQN addresses an event handler on a form ITEM (vs the form root). */
        public boolean isItemLevel()
        {
            return itemName != null;
        }
    }

    private FormElementWriter()
    {
        // utility class
    }

    /**
     * Parses a form-member FQN into its form path + leaf kind/name, or returns {@code null} when the
     * FQN does not address a form member. The recognized shapes are:
     * <ul>
     *   <li>{@code Type.Object.Form.FormName.Kind.Name} (form-level member/handler; the {@code Form}
     *       token may be {@code Form}/{@code Forms}/{@code Форма}/{@code Формы})</li>
     *   <li>{@code CommonForm.FormName.Kind.Name} (a CommonForm IS a form)</li>
     *   <li>{@code Type.Object.Form.FormName.ItemKind.ItemName.Handler.Event} (an event handler on a
     *       form ITEM) and its {@code CommonForm.FormName.ItemKind.ItemName.Handler.Event} variant</li>
     * </ul>
     * The form-element kind tokens are NOT confused with the mdclass member tokens because a mdclass
     * member FQN never carries a form token at position 2 nor starts with {@code CommonForm} followed
     * by a kind pair.
     */
    public static FormMemberRef parse(String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        String formPath;
        int rem; // index where the kind/name remainder begins
        if (p.length >= 6 && isFormToken(p[2]))
        {
            formPath = p[0] + "." + p[1] + ".forms." + p[3]; //$NON-NLS-1$ //$NON-NLS-2$
            rem = 4;
        }
        else if (p.length >= 4 && "CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(p[0]))) //$NON-NLS-1$
        {
            formPath = p[0] + "." + p[1]; //$NON-NLS-1$
            rem = 2;
        }
        else
        {
            return null;
        }
        int tail = p.length - rem;
        if (tail == 2)
        {
            // Form-level member or handler: Kind.Name.
            return new FormMemberRef(formPath, p[rem], p[rem + 1], null, null);
        }
        if (tail == 4 && isHandlerToken(p[rem + 2]))
        {
            // Item-level handler: ItemKind.ItemName.Handler.Event.
            return new FormMemberRef(formPath, p[rem + 2], p[rem + 3], p[rem], p[rem + 1]);
        }
        return null;
    }

    /**
     * Whether {@code token} is a recognized FORM segment of an FQN / form path:
     * {@code Form} / {@code Forms} and their Russian equivalents (singular / plural), case-insensitive.
     * This is THE form-token predicate - every consumer that parses a form path (this writer,
     * {@link MetadataPathResolver}) must share it so a form addressed one way (e.g. created via
     * {@code Catalog.X.Form.Y}) stays addressable everywhere (screenshot / layout snapshot).
     */
    public static boolean isFormToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        String s = token.toLowerCase();
        return "form".equals(s) || "forms".equals(s) //$NON-NLS-1$ //$NON-NLS-2$
            || RU_FORM.equals(s) || RU_FORMS.equals(s);
    }

    /**
     * If {@code normFqn} addresses a FORM ITSELF (not a member) - {@code Type.Object.Form(s).FormName}
     * (4 parts, form token at position 2) or {@code CommonForm.FormName} (2 parts) - returns the form
     * path normalized to the {@code Type.Object.forms.FormName} / {@code CommonForm.Name} shape that
     * {@code FormStructureReader.resolveMdForm} expects; otherwise {@code null}. Used to render a
     * form's structure from {@code get_metadata_details}.
     */
    public static String parseFormPath(String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        if (p.length == 4 && isFormToken(p[2]))
        {
            return p[0] + "." + p[1] + ".forms." + p[3]; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (p.length == 2 && "CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(p[0]))) //$NON-NLS-1$
        {
            return p[0] + "." + p[1]; //$NON-NLS-1$
        }
        return null;
    }

    /** A parsed form-OBJECT create FQN: the owner type/name + the new form's Name. */
    public static final class FormObjectRef
    {
        /** Owner metadata TYPE token, as supplied (English or Russian), e.g. {@code Catalog}. */
        public final String ownerType;
        /** Owner metadata object Name, e.g. {@code Products}. */
        public final String ownerName;
        /** Programmatic Name of the form to create, e.g. {@code ItemForm}. */
        public final String formName;

        FormObjectRef(String ownerType, String ownerName, String formName)
        {
            this.ownerType = ownerType;
            this.ownerName = ownerName;
            this.formName = formName;
        }

        /** The {@code Type.Object} owner FQN of the new form. */
        public String ownerFqn()
        {
            return ownerType + "." + ownerName; //$NON-NLS-1$
        }
    }

    /**
     * If {@code normFqn} addresses a FORM OBJECT to CREATE on a metadata object -
     * {@code Type.Object.Form(s).FormName} (exactly 4 parts, a form token at position 2) - returns the
     * parsed owner + form name; otherwise {@code null}. This is the create counterpart of
     * {@link #parse} (which addresses a form MEMBER, 6+ parts) and of {@link #parseFormPath} (which
     * resolves an EXISTING form for reading): a 4-part form FQN is neither a member nor a top object, so
     * it is handled by {@code create_metadata}'s dedicated form-object branch.
     * <p>
     * A {@code CommonForm.Name} (2 parts) is NOT returned here: a CommonForm IS a top object and is
     * created through the normal top-level create path.
     */
    public static FormObjectRef parseFormObjectCreate(String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        if (p.length == 4 && isFormToken(p[2]))
        {
            return new FormObjectRef(p[0], p[1], p[3]);
        }
        return null;
    }

    /**
     * Resolves a form-member FQN kind token (English or Russian, case-insensitive) to a {@link Kind},
     * or {@code null} if it is not a supported form-element kind.
     */
    // Russian kind / form tokens, built from code points so this source stays pure ASCII (the same
    // non-UTF-8 Tycho-build guard the rest of the project uses; no raw Cyrillic literals).
    private static final String RU_ATTRIBUTE = cp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442); // rekvizit
    private static final String RU_COMMAND = cp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430); // komanda
    private static final String RU_GROUP = cp(0x0433, 0x0440, 0x0443, 0x043f, 0x043f, 0x0430); // gruppa
    private static final String RU_DECORATION = cp(0x0434, 0x0435, 0x043a, 0x043e, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f); // dekoraciya
    private static final String RU_FIELD = cp(0x043f, 0x043e, 0x043b, 0x0435); // pole
    private static final String RU_BUTTON = cp(0x043a, 0x043d, 0x043e, 0x043f, 0x043a, 0x0430); // knopka
    private static final String RU_FORM = cp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430); // forma
    private static final String RU_FORMS = cp(0x0444, 0x043e, 0x0440, 0x043c, 0x044b); // formy
    private static final String RU_HANDLER = cp(0x043e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x0447, 0x0438, 0x043a); // obrabotchik

    /** Whether a kind token addresses an event Handler (English or Russian, case-insensitive). */
    public static boolean isHandlerToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        String t = token.trim().toLowerCase();
        return "handler".equals(t) || RU_HANDLER.equals(t); //$NON-NLS-1$
    }

    public static Kind kindForToken(String token)
    {
        if (token == null)
        {
            return null;
        }
        String t = token.trim().toLowerCase();
        if ("attribute".equals(t) || "attributes".equals(t) || RU_ATTRIBUTE.equals(t)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return Kind.ATTRIBUTE;
        }
        if ("command".equals(t) || "commands".equals(t) || RU_COMMAND.equals(t)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return Kind.COMMAND;
        }
        if ("group".equals(t) || RU_GROUP.equals(t)) //$NON-NLS-1$
        {
            return Kind.GROUP;
        }
        if ("decoration".equals(t) || RU_DECORATION.equals(t)) //$NON-NLS-1$
        {
            return Kind.DECORATION;
        }
        if ("field".equals(t) || RU_FIELD.equals(t)) //$NON-NLS-1$
        {
            return Kind.FIELD;
        }
        if ("button".equals(t) || RU_BUTTON.equals(t)) //$NON-NLS-1$
        {
            return Kind.BUTTON;
        }
        return null;
    }

    /** Builds a string from BMP code points (keeps this source pure ASCII). Delegates to the shared
     * {@link MetadataLanguageUtils#cp}. */
    private static String cp(int... codePoints)
    {
        return MetadataLanguageUtils.cp(codePoints);
    }

    /**
     * Reads the editable form content model from a {@code BasicForm} mdo via {@code getForm()}
     * (reflective). Returns {@code null} if the form has no managed-form content (empty / legacy /
     * not yet built), recognized by the presence of the {@code items} feature.
     *
     * @param txMdForm the transaction-bound {@code BasicForm} EObject
     * @return the editable form content EObject, or {@code null}
     */
    public static EObject getEditableForm(EObject txMdForm)
    {
        try
        {
            Method getForm = txMdForm.getClass().getMethod("getForm"); //$NON-NLS-1$
            Object form = getForm.invoke(txMdForm);
            if (form instanceof EObject
                && ((EObject)form).eClass().getEStructuralFeature(FEATURE_ITEMS) != null)
            {
                return (EObject)form;
            }
        }
        catch (ReflectiveOperationException e)
        {
            // No getForm() / inaccessible - treated as "no editable model".
        }
        return null;
    }

    // ---- shared form write-transaction scaffold ---------------------------------------------------
    //
    // Every form-editing tool repeats the same ~40-line pipeline: resolve the MD-form from a form
    // path, null-check the BM services, capture the bmId, re-fetch the MD-form inside a BM
    // transaction, hop to the editable content form, run the work, then force-export the content
    // form's own FQN (it serializes to Form.form). The scaffold below owns that pipeline ONCE;
    // tools supply only the per-call work and their user-visible "form not found" message. Every
    // scaffold-level failure that carries an actionable message is thrown as a
    // FormValidationException with the READY error JSON, so callers surface it verbatim
    // (FormValidationException.jsonOf) from one catch block.

    /** Work executed on the re-fetched editable content form inside a BM WRITE transaction. */
    @FunctionalInterface
    public interface FormWork
    {
        /**
         * @param formModel the transaction-bound editable content form
         * @param tx the active BM write transaction
         */
        void run(EObject formModel, IBmTransaction tx);
    }

    /** Read work executed on the re-fetched editable content form inside a BM READ transaction. */
    @FunctionalInterface
    public interface FormRead<T>
    {
        /**
         * @param formModel the transaction-bound editable content form
         * @param tx the active BM read transaction
         * @return the read result (must not leak transaction-bound EObjects)
         */
        T run(EObject formModel, IBmTransaction tx);
    }

    /** Work executed on the re-fetched MD-form ({@code BasicForm}) inside a BM WRITE transaction. */
    @FunctionalInterface
    public interface MdFormWork
    {
        /**
         * @param txMdForm the transaction-bound {@code BasicForm} mdo
         * @param tx the active BM write transaction
         */
        void run(EObject txMdForm, IBmTransaction tx);
    }

    /**
     * A resolved form-edit context: the project, its BM model and the MD-form (pre-transaction
     * snapshot - re-fetched by {@link #mdFormBmId} inside the transaction for any mutation).
     */
    public static final class FormEditContext
    {
        /** The workspace project owning the form. */
        public final IProject project;
        /** The project's BM model. */
        public final IBmModel bmModel;
        /** Pre-transaction snapshot of the MD-form (safe for reads like {@code getName()}). */
        public final MdObject mdForm;
        /** The MD-form's bmId, used to re-fetch it inside the transaction. */
        final long mdFormBmId;
        /** The resolved form path (for error messages), or {@code null} for a pre-resolved form. */
        final String formPath;

        FormEditContext(IProject project, IBmModel bmModel, MdObject mdForm, long mdFormBmId,
            String formPath)
        {
            this.project = project;
            this.bmModel = bmModel;
            this.mdForm = mdForm;
            this.mdFormBmId = mdFormBmId;
            this.formPath = formPath;
        }
    }

    /**
     * Resolves the form addressed by {@code formPath} (the {@code Type.Object.forms.FormName} /
     * {@code CommonForm.Name} shape) and the BM services needed to edit it. Every failure is thrown
     * as a {@link FormValidationException} carrying the ready error JSON ({@code formNotFoundMessage}
     * for a missing form), so the caller's single catch block surfaces it verbatim.
     *
     * @param project the workspace project
     * @param config the project configuration
     * @param formPath the form path to resolve
     * @param formNotFoundMessage the user-visible message when the form does not resolve
     * @return the resolved context
     */
    public static FormEditContext resolveForEdit(IProject project, Configuration config,
        String formPath, String formNotFoundMessage)
    {
        MdObject mdForm = FormStructureReader.resolveMdForm(config, formPath);
        if (mdForm == null)
        {
            throw new FormValidationException(ToolResult.error(formNotFoundMessage).toJson());
        }
        return editContext(project, mdForm, formPath);
    }

    /**
     * Builds a {@link FormEditContext} for an ALREADY-RESOLVED MD-form (a caller with its own
     * resolution / error wording, e.g. the owned-form delete). Throws {@link FormValidationException}
     * with the ready error JSON when the BM services are unavailable.
     *
     * @param project the workspace project
     * @param mdForm the resolved MD-form
     * @return the context
     */
    public static FormEditContext editContextFor(IProject project, MdObject mdForm)
    {
        return editContext(project, mdForm, null);
    }

    private static FormEditContext editContext(IProject project, MdObject mdForm, String formPath)
    {
        if (!(mdForm instanceof IBmObject))
        {
            throw new FormValidationException(ToolResult.error("Form is not a BM object").toJson()); //$NON-NLS-1$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            throw new FormValidationException(
                ToolResult.error("IBmModelManager not available").toJson()); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            throw new FormValidationException(ToolResult.error("BM model not available for project: " //$NON-NLS-1$
                + project.getName()).toJson());
        }
        return new FormEditContext(project, bmModel, mdForm, ((IBmObject)mdForm).bmGetId(), formPath);
    }

    /**
     * Runs {@code work} against the editable content form inside ONE BM WRITE transaction, then
     * force-exports the content form's OWN top-object FQN (forms serialize to {@code Form.form}).
     * The MD-form is re-fetched by bmId inside the transaction; a missing editable content model is
     * thrown as a {@link FormValidationException} (rolling the transaction back), so an exception
     * from {@code work} (including a {@code FormValidationException} carrying a ready JSON error)
     * leaves no partial mutation.
     *
     * @param ctx the resolved context (see {@link #resolveForEdit})
     * @param taskName a short BM task name for diagnostics
     * @param work the mutation to run on the content form
     * @return whether the export persisted the change to disk
     */
    public static boolean writeEditableForm(FormEditContext ctx, String taskName, FormWork work)
    {
        String contentFormFqn = BmTransactions.<String>write(ctx.bmModel, taskName, (tx, pm) ->
        {
            EObject formModel = editableFormInTx(ctx, tx);
            work.run(formModel, tx);
            // The content Form is a separate top object serialized to Form.form - export ITS fqn.
            return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
        });
        return contentFormFqn != null && !contentFormFqn.isEmpty()
            && BmTransactions.forceExportToDisk(ctx.project, contentFormFqn);
    }

    /**
     * Runs {@code work} against the editable content form inside ONE BM READ transaction (no
     * mutation, nothing exported). Scaffold failures are thrown like {@link #writeEditableForm}.
     *
     * @param ctx the resolved context (see {@link #resolveForEdit})
     * @param taskName a short BM task name for diagnostics
     * @param work the read to run on the content form
     * @param <T> the read result type
     * @return the read result
     */
    public static <T> T readEditableForm(FormEditContext ctx, String taskName, FormRead<T> work)
    {
        return BmTransactions.read(ctx.bmModel, taskName,
            (tx, pm) -> work.run(editableFormInTx(ctx, tx), tx));
    }

    /**
     * Runs {@code work} against the re-fetched MD-form ({@code BasicForm}) itself inside ONE BM
     * WRITE transaction - the variant for work that mutates the MD-form / its owner rather than the
     * content form (e.g. deleting an owned form). No editable-content check is applied and nothing
     * is exported; the caller exports whichever top object(s) it dirtied.
     *
     * @param ctx the resolved context (see {@link #editContextFor})
     * @param taskName a short BM task name for diagnostics
     * @param work the mutation to run on the MD-form
     */
    public static void writeMdForm(FormEditContext ctx, String taskName, MdFormWork work)
    {
        BmTransactions.<Void>write(ctx.bmModel, taskName, (tx, pm) ->
        {
            work.run(mdFormInTx(ctx, tx), tx);
            return null;
        });
    }

    /** Re-fetches the MD-form inside the transaction, failing clearly when it has gone. */
    private static EObject mdFormInTx(FormEditContext ctx, IBmTransaction tx)
    {
        EObject txMdForm = (EObject)tx.getObjectById(ctx.mdFormBmId);
        if (txMdForm == null)
        {
            throw new RuntimeException("Form object not found in transaction"); //$NON-NLS-1$
        }
        return txMdForm;
    }

    /** Re-fetches the MD-form and hops to its editable content form, failing on either gap. */
    private static EObject editableFormInTx(FormEditContext ctx, IBmTransaction tx)
    {
        EObject formModel = getEditableForm(mdFormInTx(ctx, tx));
        if (formModel == null)
        {
            throw new FormValidationException(noEditableContentError(ctx.formPath));
        }
        return formModel;
    }

    /** The canonical "no editable content model" error JSON (with the form path when known). */
    private static String noEditableContentError(String formPath)
    {
        String suffix = (formPath != null && !formPath.isEmpty()) ? ": " + formPath : ""; //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.error("the form has no editable content model (it may be empty, an " //$NON-NLS-1$
            + "ordinary/legacy form, or not yet built)" + suffix).toJson(); //$NON-NLS-1$
    }

    /**
     * Creates a form member of {@code kind} named {@code name} on the editable {@code formModel}.
     * For a visual item (group / decoration) the optional {@code parentName} nests it under an
     * existing item (form root when {@code null}); {@code title} (with its language CODE) is applied
     * when given. Runs INSIDE a BM write transaction on the re-fetched content form.
     *
     * @return {@code null} on success, or a human-readable error message (the caller wraps it in
     *     {@code ToolResult.error}); the created element's concrete EClass name is returned via
     *     {@code createdKind} when non-null.
     */
    public static String createMember(EObject formModel, Kind kind, String name, String parentName,
        String bindTarget, String titleLanguage, String title, String[] createdKind)
    {
        switch (kind)
        {
            case ATTRIBUTE:
                return createAttribute(formModel, name, titleLanguage, title, createdKind);
            case COMMAND:
                return createCommand(formModel, name, titleLanguage, title, createdKind);
            case FIELD:
                return createField(formModel, name, parentName, bindTarget, titleLanguage, title, createdKind);
            case BUTTON:
                return createButton(formModel, name, parentName, bindTarget, titleLanguage, title, createdKind);
            case GROUP:
            case DECORATION:
            default:
                return createItem(formModel, kind, name, parentName, titleLanguage, title, createdKind);
        }
    }

    // ---- form-OBJECT creation (the BasicForm mdo + its renderable content Form) ------------------

    /**
     * Creates a managed form OBJECT on {@code owner} inside an active BM write transaction: the
     * MD-form ({@link BasicForm}, added to the owner's {@code forms} collection) AND an empty,
     * renderable content {@link Form}, linked both ways, with the content form registered as a BM top
     * object under the canonical external-property FQN. Mirrors the EDT "New form" wizard.
     * <p>
     * The content form is built by the FORM model factory ({@code formFactory}, the same
     * {@code FormObjectFactory} the wizard uses) so it gets the predefined {@code autoCommandBar} the
     * WYSIWYG layout generator requires - without it {@code HippoGenerator.readElement} ->
     * {@code findHGClass(null)} throws and the form never renders. As a guard against the factory not
     * resolving in this environment (or a future change), the render-critical {@code autoCommandBar}
     * and the standard form-level flags are also applied explicitly here.
     * <p>
     * The content form is attached under {@code ITopObjectFqnGenerator.generateExternalPropertyFqn(
     * mdForm, BASIC_FORM__FORM)} - the SAME FQN EDT's own form infrastructure uses - so the BM
     * namespace assigns it a store and later look-ups resolve; any other FQN leaves it store-less and
     * access fails with "No store … assigned to namespace".
     *
     * @param tx the active BM write transaction
     * @param owner the owner metadata object, re-fetched inside {@code tx}
     * @param formName the programmatic Name of the new form (already validated)
     * @param synonymLanguage the resolved synonym language CODE, or {@code null} when no synonym
     * @param synonym the synonym text, or {@code null}
     * @param comment the comment text to set on the MD-form, or {@code null}
     * @param setAsDefault when {@code true}, registers the form as the owner's default object form
     * @param mdFactory the MD model-object factory (creates the BasicForm)
     * @param formFactory the FORM model-object factory (creates the content Form), may be {@code null}
     * @param fqnGenerator the top-object FQN generator (computes the content form's canonical FQN)
     * @param version the platform version
     * @return the content form's own top-object FQN (serialized to {@code Form.form}), for force-export
     */
    public static String createForm(IBmTransaction tx, MdObject owner, String formName,
        String synonymLanguage, String synonym, String comment, boolean setAsDefault,
        IModelObjectFactory mdFactory, IModelObjectFactory formFactory,
        ITopObjectFqnGenerator fqnGenerator, Version version)
    {
        EStructuralFeature formsFeature = owner.eClass().getEStructuralFeature("forms"); //$NON-NLS-1$
        if (formsFeature == null || !(formsFeature.getEType() instanceof EClass))
        {
            throw new RuntimeException("Object type '" + owner.eClass().getName() //$NON-NLS-1$
                + "' does not support forms."); //$NON-NLS-1$
        }
        if (findOwnedFormByName(owner, formsFeature, formName) != null)
        {
            throw new RuntimeException("Form already exists: " + formName); //$NON-NLS-1$
        }
        EClass mdFormEClass = (EClass)formsFeature.getEType();

        // (1) The MD-form via the standard MD factory (wizard-equivalent).
        BasicForm mdForm = (BasicForm)mdFactory.create(mdFormEClass, version);
        if (mdForm == null)
        {
            throw new RuntimeException("Factory returned null for form type: " + mdFormEClass.getName()); //$NON-NLS-1$
        }
        mdForm.setName(formName);
        mdForm.setUuid(UUID.randomUUID());
        if (synonym != null && !synonym.isEmpty() && synonymLanguage != null)
        {
            mdForm.getSynonym().put(synonymLanguage, synonym);
        }
        if (comment != null && !comment.isEmpty())
        {
            mdForm.setComment(comment);
        }

        // (2) The content form, built by the FORM factory so it gets EDT's default structure
        // (autoCommandBar, command interface, form flags). Falls back to a manual minimal-but-
        // renderable build if the factory is unavailable.
        Form content = createContentForm(formFactory, owner, version);

        // (3) Link MD-form <-> content form (both directions).
        mdForm.setForm(content);
        content.setMdForm(mdForm);

        // (4) Add the MD-form to the owner's forms collection BEFORE generating the content FQN, so the
        // MD-form has a resolvable parent chain (owner -> configuration) and therefore a resolvable FQN.
        addToList(owner, "forms", mdForm); //$NON-NLS-1$

        // (5) Register the content form as a BM top object under the canonical external-property FQN.
        String contentFqn = fqnGenerator.generateExternalPropertyFqn(mdForm,
            MdClassPackage.Literals.BASIC_FORM__FORM);
        if (contentFqn == null || contentFqn.isEmpty())
        {
            throw new RuntimeException("Could not generate the content-form FQN for: " + formName); //$NON-NLS-1$
        }
        tx.attachTopObject((IBmObject)content, contentFqn);

        // (6) Fill default references / usePurposes as the wizard does.
        mdFactory.fillDefaultReferences(mdForm);

        // (7) Optionally set as the owner's default object form.
        if (setAsDefault)
        {
            setDefaultObjectForm(owner, mdForm);
        }
        return contentFqn;
    }

    /**
     * Builds the content {@link Form} with EDT's default structure. Prefers the FORM model factory
     * ({@code FormObjectFactory}) - {@code create(FormPackage.Literals.FORM, owner, version)} produces
     * exactly what the "New form" wizard builds (predefined {@code autoCommandBar}, command interface,
     * form flags). Falls back to a bare {@code FormFactory.createForm()} when the factory is absent.
     * In both cases the render-critical {@code autoCommandBar} and the standard form-level defaults are
     * applied explicitly afterwards, so the form renders whether or not the factory ran.
     */
    private static Form createContentForm(IModelObjectFactory formFactory, MdObject owner, Version version)
    {
        Form content = null;
        if (formFactory != null)
        {
            content = formFactory.create(FormPackage.Literals.FORM, owner, version);
        }
        if (content == null)
        {
            content = FormFactory.eINSTANCE.createForm();
        }
        // Guard: the factory may not run in this environment (its injector may be absent), or a future
        // change may stop seeding the command bar. Ensure the render-critical element is present.
        if (content.getAutoCommandBar() == null)
        {
            content.setAutoCommandBar(createDefaultAutoCommandBar());
        }
        applyFormDefaults(content);
        return content;
    }

    /**
     * Sets the standard default form-level properties a managed form authored in EDT has - the eight
     * form flags ({@code saveWindowSettings}, {@code autoTitle}, {@code autoUrl}, {@code autoFillCheck},
     * {@code allowFormCustomize}, {@code enabled}, {@code showTitle}, {@code showCloseButton}) true, the
     * children grouping {@link FormChildrenGroup#VERTICAL}, and an (empty) {@link FormCommandInterface}
     * holding an empty navigation panel and command bar - so an MCP-created form matches the reference
     * regardless of whether {@code FormObjectFactory} resolved and ran. The {@code autoCommandBar} is
     * created separately (it is render-critical); this method does not touch it.
     */
    private static void applyFormDefaults(Form form)
    {
        form.setSaveWindowSettings(true);
        form.setAutoTitle(true);
        form.setAutoUrl(true);
        form.setAutoFillCheck(true);
        form.setAllowFormCustomize(true);
        form.setEnabled(true);
        form.setShowTitle(true);
        form.setShowCloseButton(true);
        form.setGroup(FormChildrenGroup.VERTICAL);

        FormCommandInterface commandInterface = FormFactory.eINSTANCE.createFormCommandInterface();
        commandInterface.setNavigationPanel(FormFactory.eINSTANCE.createFormCommandInterfaceItems());
        commandInterface.setCommandBar(FormFactory.eINSTANCE.createFormCommandInterfaceItems());
        form.setCommandInterface(commandInterface);
    }

    /**
     * Builds the form's predefined automatic command bar, mirroring
     * {@code FormObjectFactory.newAutoCommandBar}: {@code autoFill = true}, {@code horizontalAlign =
     * LEFT}, id {@code -1} (the sentinel EDT persists for a form's own predefined command bar, keeping
     * it out of the regular element id space). A name is assigned so the element is well-formed; EDT
     * renames predefined items to their canonical names on the next form sync.
     */
    private static AutoCommandBar createDefaultAutoCommandBar()
    {
        AutoCommandBar bar = FormFactory.eINSTANCE.createAutoCommandBar();
        bar.setAutoFill(true);
        bar.setHorizontalAlign(ItemHorizontalAlignment.LEFT);
        bar.setId(-1);
        bar.setName(RU_FORM_COMMAND_BAR);
        return bar;
    }

    /** ru "ФормаКоманднаяПанель" - the canonical predefined-command-bar name (pure-ASCII source). */
    private static final String RU_FORM_COMMAND_BAR = cp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430,
        0x041a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x043d, 0x0430, 0x044f,
        0x041f, 0x0430, 0x043d, 0x0435, 0x043b, 0x044c);

    /**
     * Sets the owner's default object form via {@code setDefaultObjectForm(...)} when present. Uses
     * reflection because that setter is declared per owner type without a common interface; a missing
     * setter is reported clearly rather than failing silently.
     */
    private static void setDefaultObjectForm(MdObject owner, BasicForm mdForm)
    {
        for (Method method : owner.getClass().getMethods())
        {
            if (!"setDefaultObjectForm".equals(method.getName())) //$NON-NLS-1$
            {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 1 && paramTypes[0].isInstance(mdForm))
            {
                try
                {
                    method.invoke(owner, mdForm);
                    return;
                }
                catch (ReflectiveOperationException e)
                {
                    throw new RuntimeException("Failed to set default object form", e); //$NON-NLS-1$
                }
            }
        }
        throw new RuntimeException("Owner type '" + owner.eClass().getName() //$NON-NLS-1$
            + "' has no compatible setDefaultObjectForm(...) method; create the form without " //$NON-NLS-1$
            + "setAsDefault and assign it manually."); //$NON-NLS-1$
    }

    /** Finds a form by Name in the owner's {@code forms} collection (case-insensitive), or null. */
    private static EObject findOwnedFormByName(EObject owner, EStructuralFeature formsFeature, String name)
    {
        Object value = owner.eGet(formsFeature);
        if (value instanceof EList<?>)
        {
            for (Object form : (EList<?>)value)
            {
                if (form instanceof MdObject && name.equalsIgnoreCase(((MdObject)form).getName()))
                {
                    return (EObject)form;
                }
            }
        }
        return null;
    }

    private static String createAttribute(EObject formModel, String name, String titleLanguage,
        String title, String[] createdKind)
    {
        if (findByName(referenceList(formModel, FEATURE_ATTRIBUTES), name) != null)
        {
            return "Form attribute already exists: " + name; //$NON-NLS-1$
        }
        EObject attr = createFromFeatureType(formModel, FEATURE_ATTRIBUTES);
        if (attr == null)
        {
            return "Cannot create a form attribute for this form model."; //$NON-NLS-1$
        }
        setStringFeature(attr, FEATURE_NAME, name);
        setDefaultValueType(attr);
        applyTitle(attr, titleLanguage, title);
        addToList(formModel, FEATURE_ATTRIBUTES, attr);
        recordKind(attr, createdKind);
        return null;
    }

    private static String createCommand(EObject formModel, String name, String titleLanguage,
        String title, String[] createdKind)
    {
        if (findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), name) != null)
        {
            return "Form command already exists: " + name; //$NON-NLS-1$
        }
        EObject cmd = createFromFeatureType(formModel, FEATURE_FORM_COMMANDS);
        if (cmd == null)
        {
            return "Cannot create a form command for this form model."; //$NON-NLS-1$
        }
        setStringFeature(cmd, FEATURE_NAME, name);
        applyTitle(cmd, titleLanguage, title);
        addToList(formModel, FEATURE_FORM_COMMANDS, cmd);
        recordKind(cmd, createdKind);
        return null;
    }

    private static String createItem(EObject formModel, Kind kind, String name, String parentName,
        String titleLanguage, String title, String[] createdKind)
    {
        if (findItem(formModel, name) != null)
        {
            return "Form item already exists: " + name; //$NON-NLS-1$
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        String classifier = kind == Kind.GROUP ? ECLASS_FORM_GROUP : ECLASS_DECORATION;
        EObject item = createFromClassifier(formModel, classifier);
        if (item == null)
        {
            return "Cannot create a form " + classifier + " for this form model."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        setStringFeature(item, FEATURE_NAME, name);
        setBooleanFeature(item, FEATURE_VISIBLE, true);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        initManagedItem(formModel, item, kind);
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        recordKind(item, createdKind);
        return null;
    }

    /** A FormField bound to a form attribute via its dataPath (a generic InputField the user can refine). */
    @SuppressWarnings("unchecked")
    private static String createField(EObject formModel, String name, String parentName,
        String attrName, String titleLanguage, String title, String[] createdKind)
    {
        if (attrName == null || attrName.isEmpty())
        {
            return "A form field needs a 'dataPath' property naming the form attribute it shows " //$NON-NLS-1$
                + "(e.g. {name:'dataPath', value:'Price'})."; //$NON-NLS-1$
        }
        if (findByName(referenceList(formModel, FEATURE_ATTRIBUTES), attrName) == null)
        {
            return "Form attribute '" + attrName + "' not found - create it first, then bind the field " //$NON-NLS-1$ //$NON-NLS-2$
                + "to it (so the data path resolves)."; //$NON-NLS-1$
        }
        if (findItem(formModel, name) != null)
        {
            return "Form item already exists: " + name; //$NON-NLS-1$
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        EObject item = createFromClassifier(formModel, "FormField"); //$NON-NLS-1$
        if (item == null)
        {
            return "Cannot create a form field for this form model."; //$NON-NLS-1$
        }
        setStringFeature(item, FEATURE_NAME, name);
        setBooleanFeature(item, FEATURE_VISIBLE, true);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        // dataPath: a contained DataPath with segments=[attrName] (objects is transient - left empty,
        // the form's derived data recomputes it).
        EStructuralFeature dpFeat = item.eClass().getEStructuralFeature("dataPath"); //$NON-NLS-1$
        EObject dataPath = createFromClassifier(formModel, "DataPath"); //$NON-NLS-1$
        if (dpFeat instanceof EReference && dataPath != null)
        {
            EStructuralFeature segFeat = dataPath.eClass().getEStructuralFeature("segments"); //$NON-NLS-1$
            if (segFeat != null && dataPath.eGet(segFeat) instanceof EList<?>)
            {
                ((EList<String>)dataPath.eGet(segFeat)).add(attrName);
            }
            item.eSet(dpFeat, dataPath);
        }
        // Pure-model default field type (InputField + a fresh InputFieldExtInfo), as the platform's
        // own factory does before the value type is known.
        setEnumFeature(item, FEATURE_TYPE, "InputField"); //$NON-NLS-1$
        setExtInfoClassifier(formModel, item, "InputFieldExtInfo"); //$NON-NLS-1$
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        recordKind(item, createdKind);
        return null;
    }

    /** A Button bound to a form command (FormCommand is-a mcore Command, so the reference is direct). */
    private static String createButton(EObject formModel, String name, String parentName,
        String cmdName, String titleLanguage, String title, String[] createdKind)
    {
        if (cmdName == null || cmdName.isEmpty())
        {
            return "A form button needs a 'command' property naming the form command it runs " //$NON-NLS-1$
                + "(e.g. {name:'command', value:'Refresh'})."; //$NON-NLS-1$
        }
        EObject command = findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), cmdName);
        if (command == null)
        {
            return "Form command '" + cmdName + "' not found - create it first, then bind the button " //$NON-NLS-1$ //$NON-NLS-2$
                + "to it."; //$NON-NLS-1$
        }
        if (findItem(formModel, name) != null)
        {
            return "Form item already exists: " + name; //$NON-NLS-1$
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        EObject item = createFromClassifier(formModel, "Button"); //$NON-NLS-1$
        if (item == null)
        {
            return "Cannot create a form button for this form model."; //$NON-NLS-1$
        }
        setStringFeature(item, FEATURE_NAME, name);
        setBooleanFeature(item, FEATURE_VISIBLE, true);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        // A standalone button; buttons have no extInfo (unlike fields/groups/decorations).
        setEnumFeature(item, FEATURE_TYPE, "UsualButton"); //$NON-NLS-1$
        EStructuralFeature cmdFeat = item.eClass().getEStructuralFeature("commandName"); //$NON-NLS-1$
        if (cmdFeat instanceof EReference)
        {
            item.eSet(cmdFeat, command);
        }
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        recordKind(item, createdKind);
        return null;
    }

    /** The form root for a blank parent, the named item otherwise, or {@code null} if not found. */
    private static EObject containerFor(EObject formModel, String parentName)
    {
        if (parentName == null || parentName.isEmpty())
        {
            return formModel;
        }
        return findItem(formModel, parentName);
    }

    private static String parentNotFound(String parentName)
    {
        return "Parent form item not found: " + parentName //$NON-NLS-1$
            + ". Create the parent group first, or omit 'parent' to add at the form root."; //$NON-NLS-1$
    }

    /** Attaches a fresh extInfo of the named concrete classifier to an item (best-effort). */
    private static void setExtInfoClassifier(EObject formModel, EObject item, String classifier)
    {
        EStructuralFeature feature = item.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (!(feature instanceof EReference))
        {
            return;
        }
        EClass extInfoClass = formEClass(formModel, classifier);
        if (extInfoClass != null && extInfoClass.getEPackage() != null)
        {
            item.eSet(feature, extInfoClass.getEPackage().getEFactoryInstance().create(extInfoClass));
        }
    }

    // ---- event handlers -------------------------------------------------------------------------

    /**
     * Binds an event {@code Handler} to {@code container} (the form itself or a form item): resolves
     * the requested {@code eventName} against the element's AVAILABLE events; on no match returns an
     * error LISTING the available events localized to {@code langCode} (the user-required advisory).
     * The {@code procName} is the BSL handler procedure name (defaults to the event name when blank).
     *
     * @param version the platform version (to resolve the element's platform Type and its events)
     * @return {@code null} on success, or a human-readable error message
     */
    public static String createHandler(EObject container, String eventName, String procName,
        Version version, String langCode, String[] createdKind)
    {
        EStructuralFeature handlersFeat = container.eClass().getEStructuralFeature("handlers"); //$NON-NLS-1$
        if (!(handlersFeat instanceof EReference) || !handlersFeat.isMany())
        {
            return "The form element '" + container.eClass().getName() //$NON-NLS-1$
                + "' cannot hold event handlers."; //$NON-NLS-1$
        }
        List<EObject> events = availableEvents(container, version);
        if (events.isEmpty())
        {
            return "Could not resolve the available events for this form element."; //$NON-NLS-1$
        }
        EObject matched = null;
        for (EObject ev : events)
        {
            if (eventName.equalsIgnoreCase(eventNameOf(ev, false))
                || eventName.equalsIgnoreCase(eventNameOf(ev, true)))
            {
                matched = ev;
                break;
            }
        }
        if (matched == null)
        {
            boolean ru = "ru".equals(langCode); //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            for (EObject ev : events)
            {
                String n = eventNameOf(ev, ru);
                if (n == null || n.isEmpty())
                {
                    n = eventNameOf(ev, !ru);
                }
                if (n != null && !n.isEmpty())
                {
                    if (sb.length() > 0)
                    {
                        sb.append(", "); //$NON-NLS-1$
                    }
                    sb.append(n);
                }
            }
            return "Event '" + eventName + "' is not valid for " + container.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Available events: " + sb; //$NON-NLS-1$
        }
        EStructuralFeature evFeat = handlerEventFeature(handlersFeat);
        for (EObject existing : referenceList(container, "handlers")) //$NON-NLS-1$
        {
            if (evFeat != null && existing.eGet(evFeat) == matched)
            {
                return "An event handler for '" + eventName + "' already exists on this element."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        EClass ehType = ((EReference)handlersFeat).getEReferenceType();
        if (ehType == null || ehType.getEPackage() == null)
        {
            return "Cannot create an event handler for this form model."; //$NON-NLS-1$
        }
        EObject handler = ehType.getEPackage().getEFactoryInstance().create(ehType);
        setStringFeature(handler, FEATURE_NAME, (procName == null || procName.isEmpty()) ? eventName : procName);
        if (evFeat != null)
        {
            handler.eSet(evFeat, matched);
        }
        addToList(container, "handlers", handler); //$NON-NLS-1$
        recordKind(handler, createdKind);
        return null;
    }

    /** The {@code event} EReference on the EventHandler EClass held by the {@code handlers} feature. */
    private static EStructuralFeature handlerEventFeature(EStructuralFeature handlersFeat)
    {
        EClass ehType = ((EReference)handlersFeat).getEReferenceType();
        return ehType != null ? ehType.getEStructuralFeature("event") : null; //$NON-NLS-1$
    }

    private static String eventNameOf(EObject event, boolean russian)
    {
        return stringFeature(event, russian ? "nameRu" : "name"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The available platform events for a form element (the form root OR a form item), replicating
     * {@code FormItemInformationService.getAllowedEvents}'s pure-model logic (no form-service
     * dependency): the union of the events of the element's platform BASE type and, when present, its
     * {@code extInfo} SUB-type. The base/ext type name comes from {@link #PLATFORM_TYPE_BY_ECLASS}
     * (the same mapping the platform's {@code BASE_TYPES_OF_FORM_ITEMS_AND_EXT} holds); each name is
     * resolved to its {@code Type} via {@link IEObjectProvider} and its {@code events} collected.
     * <p>Unioning the ext-info type matters for items: e.g. an input field's {@code OnChange} lives on
     * {@code FormFieldExtensionForATextBox} (its {@code InputFieldExtInfo}), not on the bare
     * {@code FormField} base type.</p>
     */
    private static List<EObject> availableEvents(EObject element, Version version)
    {
        if (version == null)
        {
            return Collections.emptyList();
        }
        IEObjectProvider provider =
            IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
        if (provider == null)
        {
            return Collections.emptyList();
        }
        List<EObject> events = new ArrayList<>();
        addTypeEvents(provider, element, PLATFORM_TYPE_BY_ECLASS.get(element.eClass().getName()), events);
        EStructuralFeature extInfoFeat = element.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (extInfoFeat instanceof EReference)
        {
            Object ext = element.eGet(extInfoFeat);
            if (ext instanceof EObject)
            {
                addTypeEvents(provider, element,
                    PLATFORM_TYPE_BY_ECLASS.get(((EObject)ext).eClass().getName()), events);
            }
        }
        return events;
    }

    /** Resolves {@code typeName} to a platform {@code Type} and appends its {@code events} to the list. */
    @SuppressWarnings("unchecked")
    private static void addTypeEvents(IEObjectProvider provider, EObject context, String typeName,
        List<EObject> accumulator)
    {
        EObject type = resolveTypeName(provider, context, typeName);
        if (type == null)
        {
            return;
        }
        EStructuralFeature eventsFeat = type.eClass().getEStructuralFeature("events"); //$NON-NLS-1$
        Object value = eventsFeat != null ? type.eGet(eventsFeat) : null;
        if (value instanceof List<?>)
        {
            accumulator.addAll((List<EObject>)value);
        }
    }

    /**
     * Resolves a platform type by name, swapping {@code ManagedForm} &harr; {@code ClientApplication
     * Form} the way the platform does (the managed form's type is {@code ClientApplicationForm} on
     * modern platforms and {@code ManagedForm} on legacy ones).
     */
    private static EObject resolveTypeName(IEObjectProvider provider, EObject context, String typeName)
    {
        if (typeName == null)
        {
            return null;
        }
        EObject type = resolveType(provider, context, typeName);
        if (type == null && "ManagedForm".equals(typeName)) //$NON-NLS-1$
        {
            type = resolveType(provider, context, "ClientApplicationForm"); //$NON-NLS-1$
        }
        else if (type == null && "ClientApplicationForm".equals(typeName)) //$NON-NLS-1$
        {
            type = resolveType(provider, context, "ManagedForm"); //$NON-NLS-1$
        }
        return type;
    }

    private static EObject resolveType(IEObjectProvider provider, EObject context, String typeName)
    {
        try
        {
            // createProxy THROWS for a name the provider does not know (it does not return null), so
            // an unknown legacy/modern type name must not abort the lookup - we try the alternative.
            EObject proxy = provider.createProxy(typeName);
            if (proxy == null)
            {
                return null;
            }
            EObject resolved = EcoreUtil.resolve(proxy, context);
            return (resolved == null || resolved.eIsProxy()) ? null : resolved;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Form-element / ext-info EClass name &rarr; platform base-type name, a faithful copy of
     * {@code FormItemInformationService.BASE_TYPES_OF_FORM_ITEMS_AND_EXT} (keyed by EClass NAME so this
     * bundle needs no compile-time form-model dependency). The events of an element are the union over
     * its base EClass and its current {@code extInfo} EClass.
     */
    private static final Map<String, String> PLATFORM_TYPE_BY_ECLASS = buildPlatformTypeMap();

    private static Map<String, String> buildPlatformTypeMap()
    {
        Map<String, String> m = new HashMap<>();
        // Element base types.
        m.put("Form", "ManagedForm"); // modern: ClientApplicationForm (resolveTypeName swaps) //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Table", "FormTable"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Decoration", "FormDecoration"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FormField", "FormField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Button", "FormButton"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FormGroup", "FormGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Addition", "FormItemAddition"); //$NON-NLS-1$ //$NON-NLS-2$
        // Form ext-infos.
        m.put("CatalogFormExtInfo", "ManagedFormExtensionForCatalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DocumentFormExtInfo", "ManagedFormExtensionForDocuments"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartOfCharacteristicTypesFormExtInfo", //$NON-NLS-1$
            "ManagedFormExtensionForChartOfCharacteristicsTypes"); //$NON-NLS-1$
        m.put("ReportFormExtInfo", "ManagedFormExtensionForReports"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ConstantsFormExtInfo", "ManagedFormExtensionForConstants"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("InformationRegisterManagerFormExtInfo", //$NON-NLS-1$
            "ManagedFormExtensionForInformationRegisterRecords"); //$NON-NLS-1$
        m.put("BusinessProcesFormExtInfo", "ManagedFormExtensionForBusinessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TaskFormExtInfo", "ManagedFormExtensionForTasks"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SettingsComposerFormExtInfo", "ManagedFormExtensionForSettingsComposer"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("RecordSetFormExtInfo", "ManagedFormExtensionForRecordSet"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ObjectFormExtInfo", "ManagedFormExtensionForObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TableObjectFormExtInfo", "ManagedFormExtensionForExternalDataSourceTableObject"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TableRecordFormExtInfo", "ManagedFormExtensionForExternalDataSourceTableRecord"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CubeRecordFormExtInfo", "ManagedFormExtensionForExternalDataSourceCubeRecord"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CubeRecordSetFormExtInfo", "ManagedFormExtensionForExternalDataSourceCubeRecordSet"); //$NON-NLS-1$ //$NON-NLS-2$
        // Table / decoration ext-infos.
        m.put("DynamicListTableExtInfo", "FormTableExtensionForDynamicList"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("LabelDecorationExtInfo", "FormDecorationExtensionForALabel"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PictureDecorationExtInfo", "FormDecorationExtensionForAPicture"); //$NON-NLS-1$ //$NON-NLS-2$
        // Field ext-infos.
        m.put("LabelFieldExtInfo", "FormFieldExtensionForALabelField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("InputFieldExtInfo", "FormFieldExtensionForATextBox"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CheckBoxFieldExtInfo", "FormFieldExtensionForACheckBoxField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ImageFieldExtInfo", "FormFieldExtensionForAPictureField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("RadioButtonsFieldExtInfo", "FormFieldExtensionForARadioButtonField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SpreadSheetDocFieldExtInfo", "FormFieldExtensionForASpreadsheetDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TextDocFieldExtInfo", "FormFieldExtensionForATextDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CalendarFieldExtInfo", "FormFieldExtensionForACalendarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ProgressBarFieldExtInfo", "FormFieldExtensionForAProgressBarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TrackBarFieldExtInfo", "FormFieldExtensionForATrackBarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartFieldExtInfo", "FormFieldExtensionForAChartField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("GanttChartFieldExtInfo", "FormFieldExtensionForAGanttChartField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DendrogramFieldExtInfo", "FormFieldExtensionForADendrogramField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FlowchartFieldExtInfo", "FormFieldExtensionForAGraphicalSchemaField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("HtmlFieldExtInfo", "FormExtensionForAHTMLDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("GeographicalMapFieldExtInfo", "FormFieldExtensionForAGeographicalSchemaField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FormattedDocFieldExtInfo", "FormFieldExtensionForAFormattedDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PDFDocumentFieldExtInfo", "FormExtensionForAPDFDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PlannerFieldExtInfo", "FormFieldExtensionForAPlanner"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PeriodFieldExtInfo", "FormFieldExtensionForAPeriodField"); //$NON-NLS-1$ //$NON-NLS-2$
        // Group ext-infos.
        m.put("ColumnGroupExtInfo", "FormGroupExtensionForAGroupOfColumns"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PagesGroupExtInfo", "FormGroupExtensionForPages"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PageGroupExtInfo", "FormGroupExtensionForAPage"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PopupGroupExtInfo", "FormGroupExtensionForAPopup"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommandBarExtInfo", "FormGroupExtensionForACommandBar"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("UsualGroupExtInfo", "FormGroupExtensionForAUsualGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        // Addition ext-infos.
        m.put("SearchStringAdditionExtInfo", "FormItemAdditionExtensionForSearchString"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ViewStatusAdditionExtInfo", "FormItemAdditionExtensionForViewStatus"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SearchControlAdditionExtInfo", "FormItemAdditionExtensionForSearchControl"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(m);
    }

    // ---- element factories (reflective, via the form EPackage) ----------------------------------

    /** Creates an instance of a mono-typed collection's element EType (attributes / formCommands). */
    private static EObject createFromFeatureType(EObject formModel, String featureName)
    {
        EStructuralFeature feature = formModel.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference))
        {
            return null;
        }
        EClass type = ((EReference)feature).getEReferenceType();
        if (type == null || type.getEPackage() == null)
        {
            return null;
        }
        return type.getEPackage().getEFactoryInstance().create(type);
    }

    /** Creates an instance of a concrete form classifier (FormGroup / Decoration) by name. */
    private static EObject createFromClassifier(EObject formModel, String classifierName)
    {
        EClass itemClass = formEClass(formModel, classifierName);
        if (itemClass == null || itemClass.getEPackage() == null)
        {
            return null;
        }
        return itemClass.getEPackage().getEFactoryInstance().create(itemClass);
    }

    /** Sets the attribute's valueType to a fresh empty TypeDescription (the form default type). */
    private static void setDefaultValueType(EObject attribute)
    {
        EStructuralFeature feature = attribute.eClass().getEStructuralFeature(FEATURE_VALUE_TYPE);
        if (!(feature instanceof EReference))
        {
            return;
        }
        EClass typeClass = ((EReference)feature).getEReferenceType();
        if (typeClass == null || typeClass.getEPackage() == null)
        {
            return;
        }
        attribute.eSet(feature, typeClass.getEPackage().getEFactoryInstance().create(typeClass));
    }

    /** Sets the managed item's type enum + a default extInfo, the way FormObjectFactory does. */
    private static void initManagedItem(EObject formModel, EObject item, Kind kind)
    {
        String typeLiteral = kind == Kind.GROUP ? TYPE_LITERAL_USUAL_GROUP : TYPE_LITERAL_LABEL;
        String extInfoClassifier =
            kind == Kind.GROUP ? ECLASS_USUAL_GROUP_EXT_INFO : ECLASS_LABEL_DECORATION_EXT_INFO;
        setEnumFeature(item, FEATURE_TYPE, typeLiteral);
        EStructuralFeature feature = item.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (feature instanceof EReference)
        {
            EClass extInfoClass = formEClass(formModel, extInfoClassifier);
            if (extInfoClass != null && extInfoClass.getEPackage() != null)
            {
                item.eSet(feature, extInfoClass.getEPackage().getEFactoryInstance().create(extInfoClass));
            }
        }
    }

    private static EClass formEClass(EObject formModel, String classifierName)
    {
        EPackage pkg = formModel.eClass().getEPackage();
        if (pkg == null)
        {
            return null;
        }
        EClassifier classifier = pkg.getEClassifier(classifierName);
        return (classifier instanceof EClass) ? (EClass)classifier : null;
    }

    // ---- the form-wide id allocation ------------------------------------------------------------

    /** The next free form-item id = max existing {@code FormItem} id across the whole form + 1. */
    private static int nextItemId(EObject formModel)
    {
        EClassifier formItem = formModel.eClass().getEPackage().getEClassifier(ECLASS_FORM_ITEM);
        boolean filter = formItem instanceof EClass;
        int max = 0;
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (filter && !((EClass)formItem).isInstance(obj))
            {
                continue;
            }
            EStructuralFeature idFeature = obj.eClass().getEStructuralFeature(FEATURE_ID);
            if (idFeature != null && obj.eGet(idFeature) instanceof Integer)
            {
                max = Math.max(max, ((Integer)obj.eGet(idFeature)).intValue());
            }
        }
        return max + 1;
    }

    // ---- reflective helpers ---------------------------------------------------------------------

    /** Writes the title for a language CODE into the object's {@code title} EMap (never the name). */
    private static void applyTitle(EObject object, String languageCode, String title)
    {
        if (languageCode == null || title == null || title.isEmpty())
        {
            return;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(FEATURE_TITLE);
        if (feature == null)
        {
            return;
        }
        Object value = object.eGet(feature);
        if (value instanceof EMap<?, ?>)
        {
            @SuppressWarnings("unchecked")
            EMap<String, String> map = (EMap<String, String>)value;
            map.put(languageCode, title);
        }
    }

    /**
     * Finds a form item by its (form-wide unique) programmatic name anywhere in the {@code items}
     * tree, or {@code null}. Used to resolve the owner of an item-level event handler. Must be called
     * on the transaction-bound form model.
     */
    public static EObject findFormItem(EObject formModel, String name)
    {
        return findItem(formModel, name);
    }

    /** Finds a form ATTRIBUTE by programmatic name, or {@code null}. Call on the tx-bound form model. */
    public static EObject findFormAttribute(EObject formModel, String name)
    {
        return findByName(referenceList(formModel, FEATURE_ATTRIBUTES), name);
    }

    /** Finds a form COMMAND by programmatic name, or {@code null}. Call on the tx-bound form model. */
    public static EObject findFormCommand(EObject formModel, String name)
    {
        return findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), name);
    }

    /**
     * Resolves a form member EObject from a parsed member ref on the tx-bound form model: ATTRIBUTE
     * &rarr; the attributes list, COMMAND &rarr; the formCommands list, anything else (Field / Button /
     * Group / Decoration / Table / ...) &rarr; the items tree by name. Returns {@code null} if no such
     * member exists. A handler ref is NOT a member - resolve it via {@link #findFormHandler} on the
     * appropriate container.
     */
    public static EObject resolveFormMember(EObject formModel, FormMemberRef ref)
    {
        Kind kind = kindForToken(ref.kindToken);
        if (kind == Kind.ATTRIBUTE)
        {
            return findFormAttribute(formModel, ref.name);
        }
        if (kind == Kind.COMMAND)
        {
            return findFormCommand(formModel, ref.name);
        }
        return findFormItem(formModel, ref.name);
    }

    /**
     * Finds the event handler bound to {@code eventName} (English or Russian, case-insensitive) on
     * {@code container} (the form root or a form item), or {@code null}. Used to delete a handler by
     * the event its FQN names. Call on the tx-bound form model.
     */
    public static EObject findFormHandler(EObject container, String eventName)
    {
        EStructuralFeature handlersFeat = container.eClass().getEStructuralFeature("handlers"); //$NON-NLS-1$
        if (!(handlersFeat instanceof EReference) || !handlersFeat.isMany())
        {
            return null;
        }
        EClass ehType = ((EReference)handlersFeat).getEReferenceType();
        EStructuralFeature evFeat = ehType != null ? ehType.getEStructuralFeature("event") : null; //$NON-NLS-1$
        for (EObject handler : referenceList(container, "handlers")) //$NON-NLS-1$
        {
            Object ev = evFeat != null ? handler.eGet(evFeat) : null;
            if (ev instanceof EObject
                && (eventName.equalsIgnoreCase(stringFeature((EObject)ev, "name")) //$NON-NLS-1$
                    || eventName.equalsIgnoreCase(stringFeature((EObject)ev, "nameRu")))) //$NON-NLS-1$
            {
                return handler;
            }
        }
        return null;
    }

    // ---- rebind (F3): change an EXISTING handler's procedure / a button's command ---------------

    /**
     * Re-points an EXISTING event handler on {@code container} (the form root or a form item) to a
     * different BSL procedure: finds the handler bound to {@code eventName} (English or Russian,
     * case-insensitive) and overwrites its procedure {@code name}. Does NOT bind a new event (that is
     * {@code create_metadata} via {@link #createHandler}); a missing handler is reported so the caller
     * can steer the user to create it. Reflective, so no compile-time form-model dependency. Call on
     * the tx-bound form model.
     *
     * @param container the form root or the owning form item (already resolved on the tx-bound model)
     * @param eventName the event whose handler to rebind (e.g. {@code OnChange})
     * @param procName the new BSL handler procedure name (must be non-blank)
     * @return {@code null} on success, or a human-readable error message
     */
    public static String rebindHandler(EObject container, String eventName, String procName)
    {
        EStructuralFeature handlersFeat = container.eClass().getEStructuralFeature("handlers"); //$NON-NLS-1$
        if (!(handlersFeat instanceof EReference) || !handlersFeat.isMany())
        {
            return "The form element '" + container.eClass().getName() //$NON-NLS-1$
                + "' cannot hold event handlers."; //$NON-NLS-1$
        }
        if (procName == null || procName.isEmpty())
        {
            return "Provide the new handler procedure name in the 'procedure' property " //$NON-NLS-1$
                + "(e.g. {name:'procedure', value:'PriceOnChange'})."; //$NON-NLS-1$
        }
        EObject handler = findFormHandler(container, eventName);
        if (handler == null)
        {
            return "No event handler for '" + eventName + "' exists on this element to rebind. Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "create_metadata on the handler FQN to bind it first."; //$NON-NLS-1$
        }
        setStringFeature(handler, FEATURE_NAME, procName);
        return null;
    }

    /**
     * Re-points an EXISTING button at a different (existing) form command: validates that
     * {@code button} carries a {@code commandName} reference and that a {@link
     * com._1c.g5.v8.dt.form.model.FormCommand} named {@code commandName} exists on {@code formModel},
     * then sets the reference. A button's {@code commandName} targets a FormCommand (a form-model
     * object, not an mdclass object), so it is not introspector-assignable and is rebound here.
     * Reflective, so no compile-time form-model dependency. Call on the tx-bound form model.
     *
     * @param formModel the editable form content model (tx-bound)
     * @param button the button form item (already resolved on the tx-bound model)
     * @param commandName the name of the existing form command to point the button at
     * @return {@code null} on success, or a human-readable error message
     */
    public static String rebindButtonCommand(EObject formModel, EObject button, String commandName)
    {
        EStructuralFeature cmdFeat = button.eClass().getEStructuralFeature("commandName"); //$NON-NLS-1$
        if (!(cmdFeat instanceof EReference))
        {
            return "The form item '" + button.eClass().getName() //$NON-NLS-1$
                + "' has no 'commandName' reference; only a Button runs a form command."; //$NON-NLS-1$
        }
        if (commandName == null || commandName.isEmpty())
        {
            return "Provide the form command to point the button at in the 'command' property " //$NON-NLS-1$
                + "(e.g. {name:'command', value:'Refresh'})."; //$NON-NLS-1$
        }
        EObject command = findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), commandName);
        if (command == null)
        {
            return "Form command '" + commandName + "' not found - create it first " //$NON-NLS-1$ //$NON-NLS-2$
                + "(create_metadata on the form's Command FQN), then re-point the button at it."; //$NON-NLS-1$
        }
        button.eSet(cmdFeat, command);
        return null;
    }

    // ---- move / reorder (F2) --------------------------------------------------------------------

    /** Position spec prefixes (the integer / {@code first} / {@code last} forms have no prefix). */
    private static final String POS_FIRST = "first"; //$NON-NLS-1$
    private static final String POS_LAST = "last"; //$NON-NLS-1$
    private static final String POS_BEFORE = "before:"; //$NON-NLS-1$
    private static final String POS_AFTER = "after:"; //$NON-NLS-1$

    /**
     * Moves / reorders a form ITEM (a field, group, decoration, button or table - anything in the
     * {@code items} containment tree) on the tx-bound {@code formModel}. The item is re-parented into
     * {@code targetParent} (a group name, or the FORM name / blank for the form root) and/or reordered
     * to {@code position} among the destination's children. The destination's {@code items} list is the
     * SAME list the source is removed from when reordering in place, so the integer index is the desired
     * FINAL 0-based position "as you see it" (see {@link #resolveMovePosition}). Reuses the reflective
     * {@code items}-tree access the rest of this writer uses - no compile-time form-model dependency.
     *
     * <p>Rejections (thrown as a {@link RuntimeException} with a user-facing message, so the calling
     * write lambda rolls back with no partial mutation): an item / target-group name that is missing or
     * AMBIGUOUS (matches more than one element), a {@code targetParent} that is not a group, and moving a
     * group INTO ITSELF OR ITS OWN DESCENDANT (a containment cycle).</p>
     *
     * @param formModel the editable form content model (tx-bound)
     * @param itemName the programmatic name of the item to move
     * @param targetParent the destination group name; blank or equal to {@code formName} means the form
     *     root; {@code null} keeps the item in its current container (reorder in place)
     * @param position the destination position spec ({@code first} / {@code last} / {@code before:<n>} /
     *     {@code after:<n>} / a 0-based integer index), or {@code null} to append at the end
     * @param formName the MD-form Name (matching it as {@code targetParent} means the form root)
     * @return a human-readable description of where the item ended up (e.g. {@code "group 'Main' at index 1"})
     */
    public static String moveItem(EObject formModel, String itemName, String targetParent,
        String position, String formName)
    {
        EObject item = findUniqueItem(formModel, itemName);
        if (item == null)
        {
            throw new RuntimeException("Form item not found: '" + itemName //$NON-NLS-1$
                + "'. Use get_metadata_details on the form to inspect its items."); //$NON-NLS-1$
        }
        EObject sourceContainer = item.eContainer();
        if (sourceContainer == null || sourceContainer.eClass().getEStructuralFeature(FEATURE_ITEMS) == null)
        {
            throw new RuntimeException("Form item '" + itemName //$NON-NLS-1$
                + "' has no parent container and cannot be moved."); //$NON-NLS-1$
        }

        // Resolve the destination container. A non-null targetParent is a RE-PARENT: blank or the
        // form name means the form root (per the contract above); only null keeps the current
        // container (reorder in place).
        EObject destContainer;
        String destLabel;
        boolean reparent = targetParent != null;
        boolean toRoot = reparent && (targetParent.isEmpty() || targetParent.equalsIgnoreCase(formName));
        if (reparent && !toRoot)
        {
            EObject group = findUniqueGroup(formModel, targetParent);
            if (group == null)
            {
                throw new RuntimeException("Target group not found: '" + targetParent //$NON-NLS-1$
                    + "'. The parent must be an existing group (or the form name for the form root)."); //$NON-NLS-1$
            }
            if (group == item || isDescendant(item, group))
            {
                throw new RuntimeException("Cannot move group '" + itemName //$NON-NLS-1$
                    + "' into itself or one of its own descendants."); //$NON-NLS-1$
            }
            destContainer = group;
            destLabel = "group '" + stringFeature(group, FEATURE_NAME) + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (reparent)
        {
            // targetParent is blank or equals the form name -> the form root.
            destContainer = formModel;
            destLabel = "the form root"; //$NON-NLS-1$
        }
        else
        {
            // No targetParent (null) -> reorder within the current container.
            destContainer = sourceContainer;
            destLabel = (sourceContainer == formModel) ? "the form root" //$NON-NLS-1$
                : "group '" + stringFeature(sourceContainer, FEATURE_NAME) + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        @SuppressWarnings("unchecked")
        EList<EObject> sourceItems = (EList<EObject>)sourceContainer
            .eGet(sourceContainer.eClass().getEStructuralFeature(FEATURE_ITEMS));
        @SuppressWarnings("unchecked")
        EList<EObject> destItems = (EList<EObject>)destContainer
            .eGet(destContainer.eClass().getEStructuralFeature(FEATURE_ITEMS));

        // Remove from the source first (it may BE the destination list when reordering in place). The
        // requested integer position is the desired FINAL 0-based index in the resulting list ("as you
        // see it"): inserting at that index into the POST-removal list lands the item at exactly that
        // index in both directions, so no off-by-one compensation is applied.
        sourceItems.remove(item);
        int index = resolveMovePosition(position, namesOf(destItems), itemName);
        if (index < 0 || index > destItems.size())
        {
            index = destItems.size();
        }
        destItems.add(index, item);
        return destLabel + " at index " + index; //$NON-NLS-1$
    }

    /**
     * Resolves a requested {@code position} into a 0-based insertion index in a destination list whose
     * sibling names are {@code destNames} (already EXCLUDING the moved item). The {@code first} /
     * {@code last} / {@code before:<name>} / {@code after:<name>} forms are name-relative; a plain
     * integer is the desired FINAL index as-is. Pure (no model dependency) so it is unit-testable.
     *
     * @param position the position spec, or {@code null} / blank / {@code last} for the end
     * @param destNames the destination sibling names in order (without the moved item)
     * @param movedName the moved item's name (a {@code before:}/{@code after:} reference to it is rejected)
     * @return the 0-based insertion index
     * @throws RuntimeException with a user-facing message on a malformed spec or unknown sibling
     */
    public static int resolveMovePosition(String position, List<String> destNames, String movedName)
    {
        if (position == null || position.isEmpty() || POS_LAST.equalsIgnoreCase(position))
        {
            return destNames.size();
        }
        if (POS_FIRST.equalsIgnoreCase(position))
        {
            return 0;
        }
        String lower = position.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith(POS_BEFORE))
        {
            return indexOfSibling(destNames, position.substring(POS_BEFORE.length()).trim(), movedName);
        }
        if (lower.startsWith(POS_AFTER))
        {
            return indexOfSibling(destNames, position.substring(POS_AFTER.length()).trim(), movedName) + 1;
        }
        try
        {
            int idx = Integer.parseInt(position.trim());
            if (idx < 0)
            {
                throw new RuntimeException("Invalid position index '" + position //$NON-NLS-1$
                    + "': must be zero or positive."); //$NON-NLS-1$
            }
            return idx;
        }
        catch (NumberFormatException e)
        {
            throw new RuntimeException("Invalid position '" + position //$NON-NLS-1$
                + "'. Expected an integer index, 'first', 'last', 'before:<name>' or 'after:<name>'."); //$NON-NLS-1$
        }
    }

    /** The 0-based index of {@code sibling} in {@code destNames} (case-insensitive), or throws. */
    private static int indexOfSibling(List<String> destNames, String sibling, String movedName)
    {
        if (sibling.isEmpty())
        {
            throw new RuntimeException("Position reference is missing a sibling name " //$NON-NLS-1$
                + "(use 'before:<name>' or 'after:<name>')."); //$NON-NLS-1$
        }
        if (sibling.equalsIgnoreCase(movedName))
        {
            throw new RuntimeException("Position cannot reference the moved item itself: '" //$NON-NLS-1$
                + sibling + "'."); //$NON-NLS-1$
        }
        for (int i = 0; i < destNames.size(); i++)
        {
            if (sibling.equalsIgnoreCase(destNames.get(i)))
            {
                return i;
            }
        }
        throw new RuntimeException("Sibling '" + sibling //$NON-NLS-1$
            + "' not found in the destination container."); //$NON-NLS-1$
    }

    /** The programmatic names of the items in {@code list}, in order (for position resolution). */
    private static List<String> namesOf(EList<EObject> list)
    {
        List<String> names = new ArrayList<>(list.size());
        for (EObject item : list)
        {
            names.add(stringFeature(item, FEATURE_NAME));
        }
        return names;
    }

    /**
     * Finds a form item by name anywhere in the {@code items} tree, rejecting an AMBIGUOUS name (more
     * than one match) with a clear error rather than silently moving the first match. Returns the unique
     * match, or {@code null} when none exists.
     */
    private static EObject findUniqueItem(EObject formModel, String name)
    {
        List<EObject> matches = new ArrayList<>();
        collectItemsByName(formModel, name, matches);
        if (matches.size() > 1)
        {
            throw new RuntimeException("Form item name '" + name //$NON-NLS-1$
                + "' is ambiguous (it matches more than one item)."); //$NON-NLS-1$
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Finds a form GROUP (a {@code FormGroup}) by name anywhere in the {@code items} tree, rejecting an
     * ambiguous name. Returns the unique matching group, or {@code null} when no group has that name (a
     * non-group item with the name is treated as "no group", matching the v1 semantics).
     */
    private static EObject findUniqueGroup(EObject formModel, String name)
    {
        List<EObject> matches = new ArrayList<>();
        collectItemsByName(formModel, name, matches);
        EObject group = null;
        for (EObject match : matches)
        {
            if (ECLASS_FORM_GROUP.equals(match.eClass().getName()))
            {
                if (group != null)
                {
                    throw new RuntimeException("Target group name '" + name //$NON-NLS-1$
                        + "' is ambiguous (it matches more than one group)."); //$NON-NLS-1$
                }
                group = match;
            }
        }
        return group;
    }

    /** Collects every item in the {@code items} tree whose name matches (case-insensitive). */
    private static void collectItemsByName(EObject container, String name, List<EObject> out)
    {
        for (EObject item : referenceList(container, FEATURE_ITEMS))
        {
            if (name.equalsIgnoreCase(stringFeature(item, FEATURE_NAME)))
            {
                out.add(item);
            }
            collectItemsByName(item, name, out);
        }
    }

    /** Whether {@code candidate} is {@code ancestor} itself or nested anywhere inside it (cycle guard). */
    private static boolean isDescendant(EObject ancestor, EObject candidate)
    {
        for (EObject parent = candidate.eContainer(); parent != null; parent = parent.eContainer())
        {
            if (parent == ancestor)
            {
                return true;
            }
        }
        return false;
    }

    /** Depth-first search of the whole {@code items} tree for an item by programmatic name. */
    private static EObject findItem(EObject container, String name)
    {
        for (EObject item : referenceList(container, FEATURE_ITEMS))
        {
            if (name.equalsIgnoreCase(stringFeature(item, FEATURE_NAME)))
            {
                return item;
            }
            EObject nested = findItem(item, name);
            if (nested != null)
            {
                return nested;
            }
        }
        return null;
    }

    private static EObject findByName(EList<EObject> list, String name)
    {
        for (EObject e : list)
        {
            if (name.equalsIgnoreCase(stringFeature(e, FEATURE_NAME)))
            {
                return e;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static EList<EObject> referenceList(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            Object value = owner.eGet(feature);
            if (value instanceof EList<?>)
            {
                return (EList<EObject>)value;
            }
        }
        return org.eclipse.emf.common.util.ECollections.emptyEList();
    }

    @SuppressWarnings("unchecked")
    private static void addToList(EObject container, String featureName, EObject element)
    {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || !feature.isMany())
        {
            throw new RuntimeException("Form feature '" + featureName + "' is not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EList<EObject>)container.eGet(feature)).add(element);
    }

    private static void recordKind(EObject element, String[] createdKind)
    {
        if (createdKind != null && createdKind.length > 0)
        {
            createdKind[0] = element.eClass().getName();
        }
    }

    private static String stringFeature(EObject object, String featureName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        Object value = feature != null ? object.eGet(feature) : null;
        return value instanceof String ? (String)value : null;
    }

    private static void setStringFeature(EObject object, String featureName, String value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, value);
        }
    }

    private static void setBooleanFeature(EObject object, String featureName, boolean value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Boolean.valueOf(value));
        }
    }

    private static void setIntFeature(EObject object, String featureName, int value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Integer.valueOf(value));
        }
    }

    private static void setEnumFeature(EObject object, String featureName, String literal)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EAttribute))
        {
            return;
        }
        EClassifier type = ((EAttribute)feature).getEAttributeType();
        if (!(type instanceof EEnum))
        {
            return;
        }
        EEnumLiteral enumLiteral = ((EEnum)type).getEEnumLiteralByLiteral(literal);
        if (enumLiteral != null)
        {
            object.eSet(feature, enumLiteral.getInstance());
        }
    }
}
