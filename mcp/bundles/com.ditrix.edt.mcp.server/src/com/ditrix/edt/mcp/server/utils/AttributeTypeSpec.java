/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (EDT-runtime independent) parser for a {@code type} parameter shared by
 * the {@code add_metadata_attribute} and {@code add_form_attribute} tools.
 * <p>
 * A type specification is a comma-separated list of one or more single type
 * items (a single item means a simple type, several items mean a composite
 * type). Commas that occur inside qualifier parentheses are <b>not</b> item
 * separators. Each item is one of:
 * <ul>
 * <li>a platform type, optionally followed by qualifiers in parentheses:
 *     <ul>
 *     <li>{@code String}, {@code String(50)}, {@code String(50,fixed)};
 *         {@code String(0)} means an <b>unlimited</b>-length string</li>
 *     <li>{@code Number}, {@code Number(15,2)}, {@code Number(15,2,nonnegative)}</li>
 *     <li>{@code Date}, {@code Date(Date)}, {@code Date(Time)}, {@code Date(DateTime)}</li>
 *     <li>{@code Boolean}</li>
 *     </ul></li>
 * <li>a reference type by its full platform name, e.g.
 *     {@code CatalogRef.Products}, {@code DocumentRef.SalesOrder},
 *     {@code EnumRef.ProductKinds} (Russian object names are allowed).</li>
 * </ul>
 * The name of every item is preserved verbatim and is later resolved into a
 * {@code TypeItem} proxy by the platform type registry; this class only parses
 * the textual form and the qualifier values, it never touches the EDT model.
 */
public final class AttributeTypeSpec
{
    /** Date composition value for a {@link Item#dateFractions}. */
    public enum DateFraction
    {
        DATE, TIME, DATE_TIME
    }

    /**
     * A single parsed type item: a type name plus the qualifier values that
     * were specified for it (only the qualifier matching the type name is
     * meaningful, the rest stay at their default).
     */
    public static final class Item
    {
        /** Verbatim type name to resolve, e.g. {@code "String"} or {@code "CatalogRef.Products"}. */
        public final String name;
        /**
         * String length (&gt;=0) when explicitly given, otherwise {@code null}.
         * A length of {@code 0} denotes an <b>unlimited</b>-length string, which is
         * the standard 1C representation (e.g. a document {@code Comment} attribute).
         */
        public Integer stringLength;
        /** Fixed-length flag for a string type; {@code null} when not given. */
        public Boolean stringFixed;
        /** Number precision (>=0) when explicitly given, otherwise {@code null}. */
        public Integer numberPrecision;
        /** Number scale (>=0) when explicitly given, otherwise {@code null}. */
        public Integer numberScale;
        /** Non-negative flag for a number type; {@code null} when not given. */
        public Boolean numberNonNegative;
        /** Date composition; {@code null} when not given. */
        public DateFraction dateFractions;

        Item(String name)
        {
            this.name = name;
        }

        /** @return {@code true} if the item name is the platform String type (case-insensitive) */
        public boolean isString()
        {
            return "String".equalsIgnoreCase(name); //$NON-NLS-1$
        }

        /** @return {@code true} if the item name is the platform Number type (case-insensitive) */
        public boolean isNumber()
        {
            return "Number".equalsIgnoreCase(name); //$NON-NLS-1$
        }

        /** @return {@code true} if the item name is the platform Date type (case-insensitive) */
        public boolean isDate()
        {
            return "Date".equalsIgnoreCase(name); //$NON-NLS-1$
        }
    }

    private final List<Item> items;

    private AttributeTypeSpec(List<Item> items)
    {
        this.items = items;
    }

    /** @return the parsed items, in input order; never empty for a successfully parsed spec */
    public List<Item> getItems()
    {
        return items;
    }

    /** @return {@code true} when the spec contains more than one type item (composite type) */
    public boolean isComposite()
    {
        return items.size() > 1;
    }

    /**
     * Parses a type specification string.
     *
     * @param spec the raw {@code type} parameter value
     * @return the parsed specification
     * @throws IllegalArgumentException if the spec is null/blank or malformed
     */
    public static AttributeTypeSpec parse(String spec)
    {
        if (spec == null || spec.trim().isEmpty())
        {
            throw new IllegalArgumentException("type is empty"); //$NON-NLS-1$
        }

        List<String> rawItems = splitTopLevel(spec);
        List<Item> parsed = new ArrayList<>(rawItems.size());
        for (String raw : rawItems)
        {
            parsed.add(parseItem(raw.trim()));
        }
        if (parsed.isEmpty())
        {
            throw new IllegalArgumentException("type does not contain any type item"); //$NON-NLS-1$
        }
        return new AttributeTypeSpec(parsed);
    }

    /**
     * Splits a spec into top-level items on commas, ignoring commas nested
     * inside parentheses (qualifier lists).
     */
    private static List<String> splitTopLevel(String spec)
    {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < spec.length(); i++)
        {
            char c = spec.charAt(i);
            if (c == '(')
            {
                depth++;
            }
            else if (c == ')')
            {
                depth--;
                if (depth < 0)
                {
                    throw new IllegalArgumentException("Unbalanced ')' in type: " + spec); //$NON-NLS-1$
                }
            }
            else if (c == ',' && depth == 0)
            {
                String part = spec.substring(start, i).trim();
                if (!part.isEmpty())
                {
                    result.add(part);
                }
                start = i + 1;
            }
        }
        if (depth != 0)
        {
            throw new IllegalArgumentException("Unbalanced '(' in type: " + spec); //$NON-NLS-1$
        }
        String tail = spec.substring(start).trim();
        if (!tail.isEmpty())
        {
            result.add(tail);
        }
        return result;
    }

    private static Item parseItem(String raw)
    {
        int paren = raw.indexOf('(');
        if (paren < 0)
        {
            if (raw.isEmpty())
            {
                throw new IllegalArgumentException("Empty type item"); //$NON-NLS-1$
            }
            return new Item(raw);
        }

        if (!raw.endsWith(")")) //$NON-NLS-1$
        {
            throw new IllegalArgumentException("Type item must end with ')': " + raw); //$NON-NLS-1$
        }
        String name = raw.substring(0, paren).trim();
        if (name.isEmpty())
        {
            throw new IllegalArgumentException("Type item has qualifiers but no type name: " + raw); //$NON-NLS-1$
        }
        String inside = raw.substring(paren + 1, raw.length() - 1).trim();
        Item item = new Item(name);

        if (item.isString())
        {
            parseStringQualifiers(item, inside, raw);
        }
        else if (item.isNumber())
        {
            parseNumberQualifiers(item, inside, raw);
        }
        else if (item.isDate())
        {
            parseDateQualifiers(item, inside, raw);
        }
        else
        {
            throw new IllegalArgumentException(
                "Qualifiers are only supported for String, Number and Date, not for: " + name); //$NON-NLS-1$
        }
        return item;
    }

    private static void parseStringQualifiers(Item item, String inside, String raw)
    {
        if (inside.isEmpty())
        {
            return;
        }
        String[] parts = inside.split(","); //$NON-NLS-1$
        // part[0] = length. A length of 0 is valid and means an unlimited-length
        // string (the standard 1C representation, e.g. a document Comment attribute).
        String lengthStr = parts[0].trim();
        if (!lengthStr.isEmpty())
        {
            item.stringLength = parseNonNegativeInt(lengthStr, "String length", raw); //$NON-NLS-1$
        }
        if (parts.length > 1)
        {
            String flag = parts[1].trim().toLowerCase();
            if ("fixed".equals(flag)) //$NON-NLS-1$
            {
                item.stringFixed = Boolean.TRUE;
            }
            else if ("variable".equals(flag) || flag.isEmpty()) //$NON-NLS-1$
            {
                item.stringFixed = Boolean.FALSE;
            }
            else
            {
                throw new IllegalArgumentException(
                    "Unknown String qualifier '" + parts[1].trim() + "', expected 'fixed' or 'variable': " + raw); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (parts.length > 2)
        {
            throw new IllegalArgumentException("Too many String qualifiers: " + raw); //$NON-NLS-1$
        }
    }

    private static void parseNumberQualifiers(Item item, String inside, String raw)
    {
        if (inside.isEmpty())
        {
            return;
        }
        String[] parts = inside.split(","); //$NON-NLS-1$
        String precisionStr = parts[0].trim();
        if (!precisionStr.isEmpty())
        {
            item.numberPrecision = parseNonNegativeInt(precisionStr, "Number precision", raw); //$NON-NLS-1$
        }
        if (parts.length > 1)
        {
            String scaleStr = parts[1].trim();
            if (!scaleStr.isEmpty())
            {
                item.numberScale = parseNonNegativeInt(scaleStr, "Number scale", raw); //$NON-NLS-1$
            }
        }
        if (parts.length > 2)
        {
            String flag = parts[2].trim().toLowerCase();
            if ("nonnegative".equals(flag) || "non-negative".equals(flag)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                item.numberNonNegative = Boolean.TRUE;
            }
            else if ("any".equals(flag) || flag.isEmpty()) //$NON-NLS-1$
            {
                item.numberNonNegative = Boolean.FALSE;
            }
            else
            {
                throw new IllegalArgumentException(
                    "Unknown Number qualifier '" + parts[2].trim() //$NON-NLS-1$
                        + "', expected 'nonnegative' or 'any': " + raw); //$NON-NLS-1$
            }
        }
        if (parts.length > 3)
        {
            throw new IllegalArgumentException("Too many Number qualifiers: " + raw); //$NON-NLS-1$
        }
    }

    private static void parseDateQualifiers(Item item, String inside, String raw)
    {
        if (inside.isEmpty())
        {
            return;
        }
        String value = inside.trim().toLowerCase();
        switch (value)
        {
            case "date": //$NON-NLS-1$
                item.dateFractions = DateFraction.DATE;
                break;
            case "time": //$NON-NLS-1$
                item.dateFractions = DateFraction.TIME;
                break;
            case "datetime": //$NON-NLS-1$
            case "date_time": //$NON-NLS-1$
            case "dateandtime": //$NON-NLS-1$
                item.dateFractions = DateFraction.DATE_TIME;
                break;
            default:
                throw new IllegalArgumentException(
                    "Unknown Date composition '" + inside + "', expected Date, Time or DateTime: " + raw); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static int parseNonNegativeInt(String value, String what, String raw)
    {
        try
        {
            int result = Integer.parseInt(value);
            if (result < 0)
            {
                throw new IllegalArgumentException(what + " must not be negative: " + raw); //$NON-NLS-1$
            }
            return result;
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException(what + " must be an integer: " + raw); //$NON-NLS-1$
        }
    }
}
