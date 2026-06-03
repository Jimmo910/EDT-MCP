/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to create a new top-level metadata object (Catalog, Document, etc.).
 * <p>
 * Uses the EDT standard object factory ({@link IModelObjectFactory}) to create
 * the object with the same default content as the "New" wizard, then registers
 * it as a BM top object via {@link IBmTransaction#attachTopObject} and adds it
 * to the corresponding Configuration collection. EDT persists the object into a
 * new {@code .mdo} file.
 * <p>
 * Two type-specific extras are layered on top of the generic chain:
 * <ul>
 * <li>{@code CommonModule} accepts an optional {@code commonModuleKind} (plus the
 * {@code serverCall}, {@code privileged} and {@code returnValuesReuse}
 * modifiers) so the new module is given a standards-compliant flag combination
 * that the {@code common-module-type} validator accepts. See
 * {@link CommonModuleKind}.</li>
 * <li>{@code XDTOPackage} accepts an optional {@code targetNamespace} so the new
 * package has the non-empty namespace it needs to be valid.</li>
 * </ul>
 */
public class CreateMetadataObjectTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "create_metadata_object"; //$NON-NLS-1$

    /** Canonical English singular type name for the CommonModule object. */
    private static final String TYPE_COMMON_MODULE = "CommonModule"; //$NON-NLS-1$

    /** Canonical English singular type name for the XDTOPackage object. */
    private static final String TYPE_XDTO_PACKAGE = "XDTOPackage"; //$NON-NLS-1$

    /**
     * Canonical English singular type names that are <em>not</em> supported for
     * creation by this tool, each mapped to the reason it is excluded.
     * <p>
     * The supported set is derived generically from {@link MetadataTypeUtils}
     * (every type it knows how to map to a Configuration collection) minus the
     * entries below. A type is excluded when it cannot be produced by the plain
     * {@code factory.create(eClass) -> attachTopObject -> add to collection ->
     * fillDefaultReferences} chain, because it needs external/binary content, a
     * dedicated import wizard, or configuration-level handling that a blank
     * default object would leave broken. Excluded types still resolve to a clear,
     * actionable error instead of silently creating an invalid object.
     * <p>
     * Note that {@code Bot}, {@code WebSocketClient} and {@code IntegrationService}
     * are <em>not</em> listed here: they are supported when the loaded platform
     * version exposes their Configuration collection, and fall back to a clear
     * "edition-specific" error at runtime when the collection is absent (detected
     * via the {@code Configuration} EClass feature, not assumed).
     */
    private static final Map<String, String> UNSUPPORTED_TYPES;
    static
    {
        Map<String, String> unsupported = new LinkedHashMap<>();
        unsupported.put("Language", //$NON-NLS-1$
            "configuration-level object that requires a language code and changes the NLS of the whole configuration"); //$NON-NLS-1$
        unsupported.put("WSReference", //$NON-NLS-1$
            "must be imported from a WSDL location (the EDT WSDL import relies on the platform native " //$NON-NLS-1$
            + "connection loader and cannot be produced as a blank object)"); //$NON-NLS-1$
        unsupported.put("ExternalDataSource", //$NON-NLS-1$
            "requires a database connection and table definitions to be valid"); //$NON-NLS-1$
        unsupported.put("Interface", //$NON-NLS-1$
            "deprecated 8.2 ordinary-application object; not present in modern configurations"); //$NON-NLS-1$
        unsupported.put("Style", //$NON-NLS-1$
            "deprecated 8.2 ordinary-application object; create a StyleItem instead"); //$NON-NLS-1$
        UNSUPPORTED_TYPES = Collections.unmodifiableMap(unsupported);
    }

    /**
     * Canonical English singular type names supported for creation, computed as
     * all types known to {@link MetadataTypeUtils} (that map to a Configuration
     * collection) minus {@link #UNSUPPORTED_TYPES}. Order follows
     * {@link MetadataTypeUtils#getAllEnglishSingularNames()}.
     */
    private static final Set<String> SUPPORTED_TYPES;
    static
    {
        Set<String> supported = new LinkedHashSet<>();
        for (String type : MetadataTypeUtils.getAllEnglishSingularNames())
        {
            // A type can only be created here if it maps to a Configuration
            // containment collection and is not explicitly excluded.
            if (!UNSUPPORTED_TYPES.containsKey(type)
                && MetadataTypeUtils.getConfigReferenceName(type) != null)
            {
                supported.add(type);
            }
        }
        SUPPORTED_TYPES = Collections.unmodifiableSet(supported);
    }

    /** Comma-separated list for prose/error messages: {@code "Catalog, Document, …"}. */
    private static final String SUPPORTED_TYPES_LIST = String.join(", ", SUPPORTED_TYPES); //$NON-NLS-1$

    /** Quoted, comma-separated list for the JSON schema hint: {@code "'Catalog', 'Document', …"}. */
    private static final String SUPPORTED_TYPES_QUOTED = "'" + String.join("', '", SUPPORTED_TYPES) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** Quoted, comma-separated list of CommonModule kinds for schema hints. */
    private static final String COMMON_MODULE_KINDS_QUOTED = CommonModuleKind.quotedList();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a new top-level metadata object with EDT default content. " + //$NON-NLS-1$
               "Supported types: " + SUPPORTED_TYPES_LIST + ". The object is created with a properly " + //$NON-NLS-1$ //$NON-NLS-2$
               "generated UUID and default properties (same as the EDT 'New' wizard). " + //$NON-NLS-1$
               "Optionally sets synonym and comment. Russian type names are also supported. " + //$NON-NLS-1$
               "For CommonModule, 'commonModuleKind' (" + COMMON_MODULE_KINDS_QUOTED + ", default 'Server') " + //$NON-NLS-1$ //$NON-NLS-2$
               "sets a standards-compliant flag combination accepted by the common-module-type " + //$NON-NLS-1$
               "validator, with optional 'serverCall', 'privileged' and 'returnValuesReuse' modifiers. " + //$NON-NLS-1$
               "For XDTOPackage, 'targetNamespace' sets the package namespace. " + //$NON-NLS-1$
               "Bot, WebSocketClient and IntegrationService are created when the loaded platform " + //$NON-NLS-1$
               "version exposes their collection."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Metadata type to create (required): " + SUPPORTED_TYPES_QUOTED + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Russian type names are also supported.", true) //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Name for the new object (required). Must be a valid 1C identifier.", true) //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional synonym (display name). Set for the configuration default language " + //$NON-NLS-1$
                "unless 'language' is specified.") //$NON-NLS-1$
            .stringProperty("comment", //$NON-NLS-1$
                "Optional comment for the new object.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code for the synonym (e.g. 'ru', 'en'). " + //$NON-NLS-1$
                "If not specified, uses the configuration default language.") //$NON-NLS-1$
            .stringProperty("commonModuleKind", //$NON-NLS-1$
                "CommonModule only. Module kind that selects a standards-compliant flag " + //$NON-NLS-1$
                "combination (no common-module-type warning): " + COMMON_MODULE_KINDS_QUOTED + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Defaults to 'Server'. Ignored for other types.") //$NON-NLS-1$
            .booleanProperty("serverCall", //$NON-NLS-1$
                "CommonModule only. When true, the server module is callable from the client " + //$NON-NLS-1$
                "(server call). Valid only with a server kind and incompatible with 'Global'.") //$NON-NLS-1$
            .booleanProperty("privileged", //$NON-NLS-1$
                "CommonModule only. When true, the module runs with full (privileged) access. " + //$NON-NLS-1$
                "Valid only with the 'Server' kind (server-side, not a server call).") //$NON-NLS-1$
            .stringProperty("returnValuesReuse", //$NON-NLS-1$
                "CommonModule only. Reuse of return values: 'DontUse' (default), 'DuringRequest' " + //$NON-NLS-1$
                "or 'DuringSession'. 'DuringSession' yields a cached module accepted by the " + //$NON-NLS-1$
                "common-module-type validator.") //$NON-NLS-1$
            .stringProperty("targetNamespace", //$NON-NLS-1$
                "XDTOPackage only. URI namespace for the new package. Defaults to " + //$NON-NLS-1$
                "'http://example.org/<Name>' when omitted.") //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "When true (default), normalizes the Russian letter 'ё'->'е' / 'Ё'->'Е' in the " + //$NON-NLS-1$
                "name, synonym and comment so the result complies with the " + //$NON-NLS-1$
                "mdo-ru-name-unallowed-letter standard; the result reports which fields were changed. " + //$NON-NLS-1$
                "Set to false to keep the text exactly as given.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String comment = JsonUtils.extractStringArgument(params, "comment"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        // Normalize user text (ё->е / Ё->Е) at the input, before identifier
        // validation, so an identifier containing 'ё' is accepted and stored
        // already compliant with the mdo-ru-name-unallowed-letter standard.
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        MdNameNormalizer.Report yoReport = new MdNameNormalizer.Report(normalizeYo);
        name = yoReport.apply("name", name); //$NON-NLS-1$
        synonym = yoReport.apply("synonym", synonym); //$NON-NLS-1$
        comment = yoReport.apply("comment", comment); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " + //$NON-NLS-1$
                "Usage: {projectName: 'MyProject', metadataType: 'Catalog', name: 'Products'}").toJson(); //$NON-NLS-1$
        }
        if (metadataType == null || metadataType.isEmpty())
        {
            return ToolResult.error("metadataType is required. " + //$NON-NLS-1$
                "Supported: " + SUPPORTED_TYPES_LIST + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("name is required. " + //$NON-NLS-1$
                "Usage: {metadataType: 'Catalog', name: 'Products'}").toJson(); //$NON-NLS-1$
        }
        if (!isValidIdentifier(name))
        {
            return ToolResult.error("Invalid object name '" + name + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "A name must start with a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, metadataType, name, synonym, comment, language, params, yoReport);
    }

    private String executeInternal(String projectName, String metadataType, String name,
        String synonym, String comment, String language, Map<String, String> params,
        MdNameNormalizer.Report yoReport)
    {
        // Resolve and validate the metadata type
        String canonicalType = MetadataTypeUtils.toEnglishSingular(metadataType);
        if (canonicalType == null)
        {
            return ToolResult.error("Unknown metadata type: " + metadataType).toJson(); //$NON-NLS-1$
        }
        if (!SUPPORTED_TYPES.contains(canonicalType))
        {
            String reason = UNSUPPORTED_TYPES.get(canonicalType);
            if (reason != null)
            {
                return ToolResult.error("Metadata type '" + canonicalType //$NON-NLS-1$
                    + "' is not supported for creation: " + reason + ". " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Supported: " + SUPPORTED_TYPES_LIST + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return ToolResult.error("Metadata type '" + canonicalType + "' is not supported for creation. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Supported: " + SUPPORTED_TYPES_LIST + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String refName = MetadataTypeUtils.getConfigReferenceName(canonicalType);
        if (refName == null)
        {
            return ToolResult.error("No configuration collection mapping for type: " + canonicalType).toJson(); //$NON-NLS-1$
        }

        // Resolve type-specific options up front so an invalid request fails fast,
        // before any project / BM resolution work is done.
        final CommonModuleFlags commonModuleFlags;
        if (TYPE_COMMON_MODULE.equals(canonicalType))
        {
            try
            {
                commonModuleFlags = CommonModuleFlags.resolve(params);
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error(e.getMessage()).toJson();
            }
        }
        else
        {
            commonModuleFlags = null;
        }

        final String xdtoNamespace;
        if (TYPE_XDTO_PACKAGE.equals(canonicalType))
        {
            String requested = JsonUtils.extractStringArgument(params, "targetNamespace"); //$NON-NLS-1$
            xdtoNamespace = (requested != null && !requested.trim().isEmpty())
                ? requested.trim()
                : "http://example.org/" + name; //$NON-NLS-1$
        }
        else
        {
            xdtoNamespace = null;
        }

        // Get project and configuration
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        // Resolve the target collection reference and its element type. A null
        // feature here means the loaded platform version does not expose this
        // collection (e.g. an edition-specific object such as Bot in a platform
        // build that lacks it) - report it as a clear, actionable error rather
        // than crashing.
        EStructuralFeature feature = config.eClass().getEStructuralFeature(refName);
        if (feature == null || !(feature.getEType() instanceof EClass))
        {
            return ToolResult.error("Metadata type '" + canonicalType //$NON-NLS-1$
                + "' is edition-specific and its Configuration collection ('" + refName //$NON-NLS-1$
                + "') is not available in the loaded platform version, so it cannot be created here.").toJson(); //$NON-NLS-1$
        }
        final EClass eClass = (EClass)feature.getEType();

        // Check duplicate
        if (MetadataTypeUtils.findObject(config, canonicalType, name) != null)
        {
            return ToolResult.error("Object already exists: " + canonicalType + "." + name).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Get IV8Project and platform version
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (v8ProjectManager == null)
        {
            return ToolResult.error("IV8ProjectManager not available").toJson(); //$NON-NLS-1$
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            return ToolResult.error("Could not resolve V8 project for: " + projectName).toJson(); //$NON-NLS-1$
        }
        final Version version = v8Project.getVersion();

        // Get the model object factory
        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        if (factory == null)
        {
            return ToolResult.error("IModelObjectFactory not available").toJson(); //$NON-NLS-1$
        }

        // Get BM model
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

        // Resolve synonym language (only required when a synonym is supplied)
        final String synonymLanguage;
        if (synonym != null && !synonym.isEmpty())
        {
            synonymLanguage = resolveLanguage(config, language);
            if (synonymLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the synonym " + //$NON-NLS-1$
                    "in this configuration. Specify 'language' explicitly (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            synonymLanguage = null;
        }

        // bmId of the configuration to re-fetch inside the transaction
        if (!(config instanceof IBmObject))
        {
            return ToolResult.error("Configuration is not a BM object").toJson(); //$NON-NLS-1$
        }
        final long configBmId = ((IBmObject)config).bmGetId();
        final String fqn = canonicalType + "." + name; //$NON-NLS-1$
        final String collectionRefName = refName;

        try
        {
            bmModel.execute(new AbstractBmTask<Void>("CreateMetadataObject") //$NON-NLS-1$
            {
                @Override
                @SuppressWarnings("unchecked")
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    Configuration cfg = (Configuration)tx.getObjectById(configBmId);
                    if (cfg == null)
                    {
                        throw new RuntimeException("Configuration not found in transaction"); //$NON-NLS-1$
                    }

                    MdObject newObject = (MdObject)factory.create(eClass, version);
                    if (newObject == null)
                    {
                        throw new RuntimeException("Factory returned null for type: " + eClass.getName()); //$NON-NLS-1$
                    }

                    newObject.setName(name);
                    if (synonym != null && !synonym.isEmpty())
                    {
                        newObject.getSynonym().put(synonymLanguage, synonym);
                    }
                    if (comment != null && !comment.isEmpty())
                    {
                        newObject.setComment(comment);
                    }

                    // Type-specific defaults applied on top of the factory content.
                    if (commonModuleFlags != null && newObject instanceof CommonModule)
                    {
                        commonModuleFlags.applyTo((CommonModule)newObject);
                    }
                    if (xdtoNamespace != null && newObject instanceof XDTOPackage)
                    {
                        ((XDTOPackage)newObject).setNamespace(xdtoNamespace);
                    }

                    // Register as a BM top object so EDT persists it into its own .mdo file
                    tx.attachTopObject((IBmObject)newObject, fqn);

                    // Add to the configuration collection
                    Object collection = cfg.eGet(cfg.eClass().getEStructuralFeature(collectionRefName));
                    if (!(collection instanceof EList))
                    {
                        throw new RuntimeException("Configuration feature '" + collectionRefName //$NON-NLS-1$
                            + "' is not a list"); //$NON-NLS-1$
                    }
                    ((EList<MdObject>)collection).add(newObject);

                    factory.fillDefaultReferences(newObject);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating metadata object", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create object: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put("fqn", fqn) //$NON-NLS-1$
            .put("metadataType", canonicalType) //$NON-NLS-1$
            .put("name", name); //$NON-NLS-1$
        if (commonModuleFlags != null)
        {
            result.put("commonModuleKind", commonModuleFlags.kind.token()); //$NON-NLS-1$
        }
        if (xdtoNamespace != null)
        {
            result.put("targetNamespace", xdtoNamespace); //$NON-NLS-1$
        }
        if (yoReport.hasChanges())
        {
            result.put("normalized", yoReport.normalizedFields()) //$NON-NLS-1$
                .put("note", yoReport.note()); //$NON-NLS-1$
        }
        return result
            .put("message", "Object '" + fqn + "' created successfully. " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Run get_project_errors to verify, or revalidate_objects if needed.") //$NON-NLS-1$
            .toJson();
    }

    private static String resolveLanguage(Configuration config, String language)
    {
        if (language != null && !language.isEmpty())
        {
            return language;
        }
        // The synonym map is keyed by the language CODE (e.g. "en", "ru"), not by
        // the Language object's name (e.g. "English"). Using the name would store
        // the synonym under a key EDT never looks up, leaving the synonym blank in
        // the editor.
        Language defaultLanguage = config.getDefaultLanguage();
        if (defaultLanguage != null
            && defaultLanguage.getLanguageCode() != null
            && !defaultLanguage.getLanguageCode().isEmpty())
        {
            return defaultLanguage.getLanguageCode();
        }
        // No default language: use the first configured language code instead of a
        // hardcoded "ru", which would be wrong for non-Russian configurations.
        for (Language lang : config.getLanguages())
        {
            if (lang != null && lang.getLanguageCode() != null && !lang.getLanguageCode().isEmpty())
            {
                return lang.getLanguageCode();
            }
        }
        return null;
    }

    private static boolean isValidIdentifier(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_')
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Standards-compliant CommonModule kinds. Each kind corresponds to a flag
     * combination that the EDT {@code common-module-type} validator accepts. The
     * validator compares the eight flag features against a fixed set of canonical
     * combinations and reports a BLOCKER issue when none matches, so the tool must
     * pick exactly one of those combinations rather than an arbitrary subset.
     */
    enum CommonModuleKind
    {
        /** Server-side module (the default): client ordinary + external connection + server. */
        SERVER("Server"), //$NON-NLS-1$
        /** Server module callable from the client (server call). */
        SERVER_CALL("ServerCall"), //$NON-NLS-1$
        /** Managed-application client module. */
        CLIENT_MANAGED("ClientManaged"), //$NON-NLS-1$
        /** Ordinary-application client module. */
        CLIENT_ORDINARY("ClientOrdinary"), //$NON-NLS-1$
        /** Combined client and server module. */
        CLIENT_SERVER("ClientServer"), //$NON-NLS-1$
        /** Global client module (its exports are available without the module prefix). */
        GLOBAL("Global"); //$NON-NLS-1$

        private final String token;

        CommonModuleKind(String token)
        {
            this.token = token;
        }

        String token()
        {
            return token;
        }

        static CommonModuleKind fromToken(String value)
        {
            for (CommonModuleKind k : values())
            {
                if (k.token.equalsIgnoreCase(value))
                {
                    return k;
                }
            }
            return null;
        }

        static String quotedList()
        {
            StringBuilder sb = new StringBuilder();
            for (CommonModuleKind k : values())
            {
                if (sb.length() > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append('\'').append(k.token).append('\'');
            }
            return sb.toString();
        }
    }

    /**
     * Resolved, validator-approved flag combination for a new CommonModule. Built
     * from the {@code commonModuleKind} plus the {@code serverCall},
     * {@code privileged} and {@code returnValuesReuse} modifiers. Every public
     * combination produced here is one of the canonical combinations recognized by
     * the {@code common-module-type} check, so a freshly created module never
     * raises that warning.
     */
    static final class CommonModuleFlags
    {
        final CommonModuleKind kind;
        final boolean clientManagedApplication;
        final boolean clientOrdinaryApplication;
        final boolean server;
        final boolean serverCall;
        final boolean externalConnection;
        final boolean global;
        final boolean privileged;
        final ReturnValuesReuse returnValuesReuse;

        private CommonModuleFlags(CommonModuleKind kind, boolean clientManagedApplication,
            boolean clientOrdinaryApplication, boolean server, boolean serverCall,
            boolean externalConnection, boolean global, boolean privileged,
            ReturnValuesReuse returnValuesReuse)
        {
            this.kind = kind;
            this.clientManagedApplication = clientManagedApplication;
            this.clientOrdinaryApplication = clientOrdinaryApplication;
            this.server = server;
            this.serverCall = serverCall;
            this.externalConnection = externalConnection;
            this.global = global;
            this.privileged = privileged;
            this.returnValuesReuse = returnValuesReuse;
        }

        void applyTo(CommonModule module)
        {
            module.setClientManagedApplication(clientManagedApplication);
            module.setClientOrdinaryApplication(clientOrdinaryApplication);
            module.setServer(server);
            module.setServerCall(serverCall);
            module.setExternalConnection(externalConnection);
            module.setGlobal(global);
            module.setPrivileged(privileged);
            module.setReturnValuesReuse(returnValuesReuse);
        }

        /**
         * Resolves the flag combination from the tool parameters, validating that
         * the requested kind/modifier combination has a standards-compliant
         * (validator-accepted) flag combination.
         *
         * @param params the tool parameters
         * @return the resolved flags
         * @throws IllegalArgumentException with a clear English message if the
         *             requested combination is unknown or invalid
         */
        static CommonModuleFlags resolve(Map<String, String> params)
        {
            String kindToken = JsonUtils.extractStringArgument(params, "commonModuleKind"); //$NON-NLS-1$
            CommonModuleKind kind;
            if (kindToken == null || kindToken.trim().isEmpty())
            {
                kind = CommonModuleKind.SERVER;
            }
            else
            {
                kind = CommonModuleKind.fromToken(kindToken.trim());
                if (kind == null)
                {
                    throw new IllegalArgumentException("Unknown commonModuleKind '" + kindToken //$NON-NLS-1$
                        + "'. Supported: " + CommonModuleKind.quotedList() + "."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            boolean serverCall = parseBoolean(params, "serverCall"); //$NON-NLS-1$
            boolean privileged = parseBoolean(params, "privileged"); //$NON-NLS-1$
            ReturnValuesReuse reuse = parseReuse(params);

            // ServerCall kind is shorthand for the Server kind + the server-call flag.
            if (kind == CommonModuleKind.SERVER_CALL)
            {
                serverCall = true;
            }

            // --- Cross-flag validation (clear, actionable messages) ---

            boolean serverSideKind = kind == CommonModuleKind.SERVER
                || kind == CommonModuleKind.SERVER_CALL
                || kind == CommonModuleKind.CLIENT_SERVER;

            if (serverCall && !serverSideKind)
            {
                throw new IllegalArgumentException("serverCall requires a server kind " //$NON-NLS-1$
                    + "('Server', 'ServerCall' or 'ClientServer'); it is not valid for kind '" //$NON-NLS-1$
                    + kind.token() + "'."); //$NON-NLS-1$
            }
            if (serverCall && kind == CommonModuleKind.GLOBAL)
            {
                throw new IllegalArgumentException("serverCall is incompatible with the 'Global' kind."); //$NON-NLS-1$
            }
            if (privileged && kind != CommonModuleKind.SERVER)
            {
                throw new IllegalArgumentException("privileged requires the 'Server' kind " //$NON-NLS-1$
                    + "(a privileged server module that is not a server call); it is not valid for kind '" //$NON-NLS-1$
                    + kind.token() + "'."); //$NON-NLS-1$
            }
            if (privileged && serverCall)
            {
                throw new IllegalArgumentException("privileged is not valid together with serverCall."); //$NON-NLS-1$
            }
            if (privileged && reuse != ReturnValuesReuse.DONT_USE)
            {
                throw new IllegalArgumentException("privileged is not valid together with returnValuesReuse."); //$NON-NLS-1$
            }

            // returnValuesReuse only produces a validator-accepted module when it is
            // either DontUse, or DuringSession on a kind that has a cached variant
            // (Server, ServerCall, ClientManaged, ClientOrdinary). DuringRequest and
            // reuse on Global/ClientServer have no canonical combination.
            if (reuse != ReturnValuesReuse.DONT_USE)
            {
                if (reuse == ReturnValuesReuse.DURING_REQUEST)
                {
                    throw new IllegalArgumentException("returnValuesReuse 'DuringRequest' has no " //$NON-NLS-1$
                        + "standards-compliant common-module combination; use 'DuringSession' for a " //$NON-NLS-1$
                        + "cached module, or 'DontUse'."); //$NON-NLS-1$
                }
                boolean reuseKind = kind == CommonModuleKind.SERVER
                    || kind == CommonModuleKind.SERVER_CALL
                    || kind == CommonModuleKind.CLIENT_MANAGED
                    || kind == CommonModuleKind.CLIENT_ORDINARY;
                if (!reuseKind)
                {
                    throw new IllegalArgumentException("returnValuesReuse 'DuringSession' is only valid " //$NON-NLS-1$
                        + "for the 'Server', 'ServerCall', 'ClientManaged' or 'ClientOrdinary' kinds; " //$NON-NLS-1$
                        + "it is not valid for kind '" + kind.token() + "'."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            // --- Map (kind, modifiers) to a canonical, validator-accepted combo ---
            // Flags order: clientManaged, clientOrdinary, server, serverCall,
            // externalConnection, global, privileged, reuse.

            boolean cached = reuse == ReturnValuesReuse.DURING_SESSION;

            switch (kind)
            {
            case SERVER:
            case SERVER_CALL:
                if (privileged)
                {
                    // SERVER_FULL_ACCESS: server-only, privileged.
                    return new CommonModuleFlags(kind, false, false, true, false, false, false, true,
                        ReturnValuesReuse.DONT_USE);
                }
                if (serverCall)
                {
                    // SERVER_CALL / SERVER_CALL_CACHED: server + server call, no client flags.
                    return new CommonModuleFlags(kind, false, false, true, true, false, false, false,
                        cached ? ReturnValuesReuse.DURING_SESSION : ReturnValuesReuse.DONT_USE);
                }
                // SERVER / SERVER_CACHED: client ordinary + external connection + server.
                return new CommonModuleFlags(kind, false, true, true, false, true, false, false,
                    cached ? ReturnValuesReuse.DURING_SESSION : ReturnValuesReuse.DONT_USE);

            case CLIENT_MANAGED:
            case CLIENT_ORDINARY:
                // CLIENT / CLIENT_CACHED: both client flags set (the canonical client module).
                return new CommonModuleFlags(kind, true, true, false, false, false, false, false,
                    cached ? ReturnValuesReuse.DURING_SESSION : ReturnValuesReuse.DONT_USE);

            case CLIENT_SERVER:
                // CLIENT_SERVER: both client flags + server + external connection.
                return new CommonModuleFlags(kind, true, true, true, false, true, false, false,
                    ReturnValuesReuse.DONT_USE);

            case GLOBAL:
                // CLIENT_GLOBAL: both client flags + global.
                return new CommonModuleFlags(kind, true, true, false, false, false, true, false,
                    ReturnValuesReuse.DONT_USE);

            default:
                throw new IllegalArgumentException("Unsupported commonModuleKind: " + kind.token()); //$NON-NLS-1$
            }
        }

        private static boolean parseBoolean(Map<String, String> params, String key)
        {
            String value = JsonUtils.extractStringArgument(params, key);
            return value != null && Boolean.parseBoolean(value.trim());
        }

        private static ReturnValuesReuse parseReuse(Map<String, String> params)
        {
            String value = JsonUtils.extractStringArgument(params, "returnValuesReuse"); //$NON-NLS-1$
            if (value == null || value.trim().isEmpty())
            {
                return ReturnValuesReuse.DONT_USE;
            }
            String normalized = value.trim();
            if ("DontUse".equalsIgnoreCase(normalized)) //$NON-NLS-1$
            {
                return ReturnValuesReuse.DONT_USE;
            }
            if ("DuringRequest".equalsIgnoreCase(normalized)) //$NON-NLS-1$
            {
                return ReturnValuesReuse.DURING_REQUEST;
            }
            if ("DuringSession".equalsIgnoreCase(normalized)) //$NON-NLS-1$
            {
                return ReturnValuesReuse.DURING_SESSION;
            }
            throw new IllegalArgumentException("Unknown returnValuesReuse '" + value //$NON-NLS-1$
                + "'. Supported: 'DontUse', 'DuringRequest', 'DuringSession'."); //$NON-NLS-1$
        }
    }
}
