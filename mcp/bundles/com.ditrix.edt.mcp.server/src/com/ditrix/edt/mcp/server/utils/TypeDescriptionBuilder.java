/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ISymbolicNameService;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Builds an mcore {@link TypeDescription} from a parsed {@link AttributeTypeSpec}
 * and resolves each type item into a {@link TypeItem} proxy.
 * <p>
 * This is the project-scoped resolution mechanism EDT uses when it imports a
 * configuration from XML: platform type names (such as {@code String},
 * {@code Number}, {@code Boolean}, {@code Date}) resolve against the platform
 * type registry, while reference type names (such as {@code CatalogRef.Products})
 * resolve against the metadata objects of the <em>current project</em> through
 * the {@link ISymbolicNameService}. The produced proxies carry the resolved URI
 * and are resolved lazily by the BM resource set when the model is read or
 * persisted.
 * <p>
 * The same logic backs both the {@code add_metadata_attribute}
 * ({@code BasicFeature.setType}) and the {@code add_form_attribute}
 * ({@code FormAttribute.setValueType}) tools, so it lives here as a single
 * source of truth.
 */
public final class TypeDescriptionBuilder
{
    private TypeDescriptionBuilder()
    {
    }

    /**
     * Builds a {@link TypeDescription} for the given parsed type spec.
     * <p>
     * Each item name is resolved into a {@link TypeItem} proxy through the
     * project-scoped {@link ISymbolicNameService}. String, Number and Date
     * qualifiers are attached when at least one item of the corresponding kind
     * carries them. The method fails loudly (throws {@link RuntimeException})
     * when a type name cannot be resolved or when a referenced object does not
     * exist in the project, rather than silently producing a dangling type.
     *
     * @param spec the parsed type specification (must not be {@code null})
     * @param version the project platform version
     * @param contextObject the object the type is being set on (resolution context)
     * @param engine the project BM engine (resolution scope for reference types)
     * @param contextTopObjectFqn FQN of the context object's top object
     * @param tx the active BM transaction (used to verify referenced objects exist)
     * @return a fully built {@link TypeDescription}
     */
    public static TypeDescription build(AttributeTypeSpec spec, Version version, EObject contextObject,
        IBmEngine engine, String contextTopObjectFqn, IBmTransaction tx)
    {
        McoreFactory mcore = McoreFactory.eINSTANCE;
        TypeDescription typeDescription = mcore.createTypeDescription();
        EList<TypeItem> typeItems = typeDescription.getTypes();

        StringQualifiers stringQualifiers = null;
        NumberQualifiers numberQualifiers = null;
        DateQualifiers dateQualifiers = null;

        for (AttributeTypeSpec.Item item : spec.getItems())
        {
            TypeItem typeItem = resolveTypeItem(item.name, version, contextObject, engine, contextTopObjectFqn, tx);
            typeItems.add(typeItem);

            if (item.isString() && hasStringQualifiers(item))
            {
                stringQualifiers = mcore.createStringQualifiers();
                stringQualifiers.setLength(item.stringLength != null ? item.stringLength : 0);
                if (item.stringFixed != null)
                {
                    stringQualifiers.setFixed(item.stringFixed);
                }
            }
            else if (item.isNumber() && hasNumberQualifiers(item))
            {
                numberQualifiers = mcore.createNumberQualifiers();
                if (item.numberPrecision != null)
                {
                    numberQualifiers.setPrecision(item.numberPrecision);
                }
                if (item.numberScale != null)
                {
                    numberQualifiers.setScale(item.numberScale);
                }
                if (item.numberNonNegative != null)
                {
                    numberQualifiers.setNonNegative(item.numberNonNegative);
                }
            }
            else if (item.isDate() && item.dateFractions != null)
            {
                dateQualifiers = mcore.createDateQualifiers();
                dateQualifiers.setDateFractions(toDateFractions(item.dateFractions));
            }
        }

        if (stringQualifiers != null)
        {
            typeDescription.setStringQualifiers(stringQualifiers);
        }
        if (numberQualifiers != null)
        {
            typeDescription.setNumberQualifiers(numberQualifiers);
        }
        if (dateQualifiers != null)
        {
            typeDescription.setDateQualifiers(dateQualifiers);
        }

        return typeDescription;
    }

    /**
     * Resolves a type name (a platform type like {@code String} or a reference
     * type like {@code CatalogRef.Products}) into a {@link TypeItem} proxy
     * through the project-scoped {@link ISymbolicNameService}.
     *
     * @param typeName the verbatim type name to resolve
     * @param version the project platform version
     * @param contextObject the object the type is being set on (resolution context)
     * @param engine the project BM engine (resolution scope for reference types)
     * @param contextTopObjectFqn FQN of the context object's top object
     * @param tx the active BM transaction (used to verify referenced objects exist)
     * @return a resolved {@link TypeItem} proxy
     */
    private static TypeItem resolveTypeItem(String typeName, Version version, EObject contextObject, IBmEngine engine,
        String contextTopObjectFqn, IBmTransaction tx)
    {
        ISymbolicNameService symbolicNameService = ServiceAccess.get(ISymbolicNameService.class);
        if (symbolicNameService == null)
        {
            throw new RuntimeException("ISymbolicNameService not available; cannot resolve type: " + typeName); //$NON-NLS-1$
        }

        URI uri = symbolicNameService.convertSymbolicNameToUri(typeName, contextObject,
            McorePackage.Literals.TYPE_DESCRIPTION__TYPES, contextTopObjectFqn, engine, version);
        if (uri == null || "unresolved".equals(uri.scheme())) //$NON-NLS-1$
        {
            throw new RuntimeException(unknownTypeMessage(typeName));
        }

        // For a reference type the URI points at a metadata object of this
        // project. The symbolic-name service builds that URI from the name
        // without checking the target exists, so a typo would yield a dangling
        // type. Verify the referenced top object is actually present and fail
        // loudly otherwise. Platform type URIs are not BM URIs and are left
        // untouched.
        if (BmUriUtil.isBmUri(uri))
        {
            String referencedTopObjectFqn = BmUriUtil.extractTopObjectFqn(uri);
            if (referencedTopObjectFqn != null && tx.getTopObjectByFqn(referencedTopObjectFqn) == null)
            {
                throw new RuntimeException(unknownTypeMessage(typeName));
            }
        }

        Type typeItem = McoreFactory.eINSTANCE.createType();
        ((InternalEObject) typeItem).eSetProxyURI(uri);
        return typeItem;
    }

    /**
     * @param object an EMF object that is also a BM object
     * @return the FQN of the object's top object, used as the resolution context
     *         for reference type names
     */
    public static String topObjectFqnOf(EObject object)
    {
        return ((IBmObject) object).bmGetTopObject().bmGetFqn();
    }

    private static String unknownTypeMessage(String typeName)
    {
        return "Unknown type name: " + typeName //$NON-NLS-1$
            + ". Use a platform type (String, Number, Boolean, Date) or a reference type to an " //$NON-NLS-1$
            + "object that already exists in this project " //$NON-NLS-1$
            + "(e.g. CatalogRef.Products, DocumentRef.SalesOrder, EnumRef.ProductKinds)."; //$NON-NLS-1$
    }

    private static boolean hasStringQualifiers(AttributeTypeSpec.Item item)
    {
        return item.stringLength != null || item.stringFixed != null;
    }

    private static boolean hasNumberQualifiers(AttributeTypeSpec.Item item)
    {
        return item.numberPrecision != null || item.numberScale != null || item.numberNonNegative != null;
    }

    private static DateFractions toDateFractions(AttributeTypeSpec.DateFraction fraction)
    {
        switch (fraction)
        {
            case DATE:
                return DateFractions.DATE;
            case TIME:
                return DateFractions.TIME;
            case DATE_TIME:
            default:
                return DateFractions.DATE_TIME;
        }
    }

    /**
     * Renders a parsed spec back into a human-readable comma-separated list of
     * type names (for the tool's success result).
     *
     * @param spec the parsed type specification
     * @return a comma-separated list of the item names, in input order
     */
    public static String describe(AttributeTypeSpec spec)
    {
        StringBuilder sb = new StringBuilder();
        for (AttributeTypeSpec.Item item : spec.getItems())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(item.name);
        }
        return sb.toString();
    }
}
