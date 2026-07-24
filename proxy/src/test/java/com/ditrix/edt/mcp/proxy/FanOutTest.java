/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Unit tests for {@link FanOut#mergeListProjects}: merging usable {@code list_projects}
 * responses in the given order, rewriting the {@code id} to the caller's request id,
 * skipping unusable responses (unparseable body, JSON-RPC error, tool-level error), and the
 * zero-usable-responses JSON-RPC error.
 */
public class FanOutTest
{
    private static String listProjectsResponse(Object id, String... projectNames)
    {
        JsonArray projects = new JsonArray();
        for (String name : projectNames)
        {
            JsonObject project = new JsonObject();
            project.addProperty("name", name); //$NON-NLS-1$
            projects.add(project);
        }
        JsonObject structured = new JsonObject();
        structured.addProperty("success", true); //$NON-NLS-1$
        structured.add("projects", projects); //$NON-NLS-1$

        JsonObject textItem = new JsonObject();
        textItem.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        textItem.addProperty("text", "projects listed"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray content = new JsonArray();
        content.add(textItem);

        JsonObject result = new JsonObject();
        result.add("content", content); //$NON-NLS-1$
        result.addProperty("isError", false); //$NON-NLS-1$
        result.add("structuredContent", structured); //$NON-NLS-1$

        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        FanOut.writeId(envelope, id);
        envelope.add("result", result); //$NON-NLS-1$
        return Json.compact(envelope);
    }

    private static List<String> projectNamesOf(JsonObject response)
    {
        JsonObject structured = Json.obj(Json.obj(response, "result"), "structuredContent"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> names = new ArrayList<>();
        for (JsonElement project : structured.getAsJsonArray("projects")) //$NON-NLS-1$
        {
            names.add(project.getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$
        }
        return names;
    }

    // ---- merging 2 valid responses ----

    @Test
    public void testMergesTwoValidResponsesInGivenOrder()
    {
        String responseA = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        String responseB = listProjectsResponse(2, "ProjectB", "ProjectC"); //$NON-NLS-1$ //$NON-NLS-2$

        String merged = FanOut.mergeListProjects(Arrays.asList(responseA, responseB), 99);

        assertEquals(List.of("ProjectA", "ProjectB", "ProjectC"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            projectNamesOf(Json.parseObject(merged)));
    }

    @Test
    public void testMergedContentReflectsAllBackendsNotJustFirst()
    {
        // The human `content` channel must show ALL merged projects, not only the first backend's
        // envelope (issue #302): the rebuilt table's total and rows cover every backend.
        String responseA = listProjectsResponse(1, "ProjectA", "ProjectB"); //$NON-NLS-1$ //$NON-NLS-2$
        String responseB = listProjectsResponse(2, "ProjectC"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(responseA, responseB), 99);

        String content = contentTextOf(Json.parseObject(merged));
        assertTrue("total must reflect all backends: " + content, //$NON-NLS-1$
            content.contains("**Total:** 3 projects")); //$NON-NLS-1$
        for (String name : new String[] { "ProjectA", "ProjectB", "ProjectC" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("content must list " + name + ": " + content, //$NON-NLS-1$ //$NON-NLS-2$
                content.contains("| " + name + " |")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String contentTextOf(JsonObject response)
    {
        JsonObject item = Json.obj(response, "result").getAsJsonArray("content") //$NON-NLS-1$ //$NON-NLS-2$
            .get(0).getAsJsonObject();
        JsonObject resource = Json.obj(item, "resource"); //$NON-NLS-1$
        return resource != null ? Json.str(resource, "text") : Json.str(item, "text"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testContentOnlyBackendProjectsAreMergedAndItsContentPreserved()
    {
        // A content-only (legacy) backend's projects must STILL appear in the merged
        // structuredContent.projects (recovered from its Markdown table) so the proxy does not hide
        // live projects the registry can already route to. Its richer human table is preserved - the
        // content is NOT rebuilt (which would downgrade its columns to name-only).
        String legacyTable = "## Workspace Projects\n\n**Total:** 1 projects\n\n" //$NON-NLS-1$
            + "| Name | State | Path | Open | EDT Project | Natures |\n" //$NON-NLS-1$
            + "|------|-------|------|------|-------------|--------|\n" //$NON-NLS-1$
            + "| LegacyProj | ready | /ws/Legacy | Yes | Yes | V8ConfigurationNature |\n"; //$NON-NLS-1$
        String legacy = legacyContentOnlyResponse(1, legacyTable);
        String structured = listProjectsResponse(2, "ProjectA"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(legacy, structured), 99);

        JsonObject parsed = Json.parseObject(merged);
        // The legacy backend's project is no longer hidden - it merges with the structured one.
        assertEquals(List.of("LegacyProj", "ProjectA"), projectNamesOf(parsed)); //$NON-NLS-1$ //$NON-NLS-2$
        // The legacy backend's OWN rich table (first content) is preserved, not rebuilt to name-only.
        String content = contentTextOf(parsed);
        assertTrue("legacy rich content preserved: " + content, content.contains("/ws/Legacy")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRebuildAttachesFreshResourceForIncompatibleContentItem()
    {
        // When the first content item is incompatible (an image), the rebuild must NOT stamp a text
        // property onto it; it attaches a fresh embedded resource instead.
        String withImage = listProjectsResponseWithImageContent(7, "ProjectA"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(List.of(withImage), 7);

        JsonObject item = Json.obj(Json.parseObject(merged), "result") //$NON-NLS-1$
            .getAsJsonArray("content").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("resource", Json.str(item, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject resource = Json.obj(item, "resource"); //$NON-NLS-1$
        assertTrue("fresh resource must be attached", resource != null); //$NON-NLS-1$
        assertTrue("rebuilt table must list the project", //$NON-NLS-1$
            Json.str(resource, "text").contains("ProjectA")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A usable list_projects response with a human content table but NO structuredContent. */
    private static String legacyContentOnlyResponse(Object id, String text)
    {
        JsonObject textItem = new JsonObject();
        textItem.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        textItem.addProperty("text", text); //$NON-NLS-1$
        JsonArray content = new JsonArray();
        content.add(textItem);
        JsonObject result = new JsonObject();
        result.add("content", content); //$NON-NLS-1$
        result.addProperty("isError", false); //$NON-NLS-1$
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        FanOut.writeId(envelope, id);
        envelope.add("result", result); //$NON-NLS-1$
        return Json.compact(envelope);
    }

    @Test
    public void testRebuildAttachesFreshResourceForBlobResource()
    {
        // A BLOB embedded resource (type:"resource" with resource.blob + a binary mimeType) shares the
        // resource shape but is NOT text-compatible; the rebuild must attach a fresh text resource,
        // not stamp Markdown text onto the blob resource.
        String withBlob = listProjectsResponseWithBlobResource(3, "ProjectA"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(List.of(withBlob), 3);

        JsonObject item = Json.obj(Json.parseObject(merged), "result") //$NON-NLS-1$
            .getAsJsonArray("content").get(0).getAsJsonObject(); //$NON-NLS-1$
        JsonObject resource = Json.obj(item, "resource"); //$NON-NLS-1$
        assertTrue("fresh resource must be attached", resource != null); //$NON-NLS-1$
        assertTrue("rebuilt table must list the project", //$NON-NLS-1$
            Json.str(resource, "text").contains("ProjectA")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the image blob must not be reused/mixed in", //$NON-NLS-1$
            !resource.has("blob")); //$NON-NLS-1$
    }

    /** A structured list_projects response whose content[0] is a BLOB (image) embedded resource. */
    private static String listProjectsResponseWithBlobResource(Object id, String... projectNames)
    {
        JsonObject structured = new JsonObject();
        JsonArray projects = new JsonArray();
        for (String name : projectNames)
        {
            JsonObject project = new JsonObject();
            project.addProperty("name", name); //$NON-NLS-1$
            projects.add(project);
        }
        structured.add("projects", projects); //$NON-NLS-1$

        JsonObject resource = new JsonObject();
        resource.addProperty("uri", "embedded://chart.png"); //$NON-NLS-1$ //$NON-NLS-2$
        resource.addProperty("mimeType", "image/png"); //$NON-NLS-1$ //$NON-NLS-2$
        resource.addProperty("blob", "AAAA"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject item = new JsonObject();
        item.addProperty("type", "resource"); //$NON-NLS-1$ //$NON-NLS-2$
        item.add("resource", resource); //$NON-NLS-1$
        JsonArray content = new JsonArray();
        content.add(item);

        JsonObject result = new JsonObject();
        result.add("content", content); //$NON-NLS-1$
        result.addProperty("isError", false); //$NON-NLS-1$
        result.add("structuredContent", structured); //$NON-NLS-1$

        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        FanOut.writeId(envelope, id);
        envelope.add("result", result); //$NON-NLS-1$
        return Json.compact(envelope);
    }

    /** A structured list_projects response whose content[0] is an IMAGE (not text/resource). */
    private static String listProjectsResponseWithImageContent(Object id, String... projectNames)
    {
        JsonArray projects = new JsonArray();
        for (String name : projectNames)
        {
            JsonObject project = new JsonObject();
            project.addProperty("name", name); //$NON-NLS-1$
            projects.add(project);
        }
        JsonObject structured = new JsonObject();
        structured.add("projects", projects); //$NON-NLS-1$

        JsonObject imageItem = new JsonObject();
        imageItem.addProperty("type", "image"); //$NON-NLS-1$ //$NON-NLS-2$
        imageItem.addProperty("data", "AAAA"); //$NON-NLS-1$ //$NON-NLS-2$
        imageItem.addProperty("mimeType", "image/png"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray content = new JsonArray();
        content.add(imageItem);

        JsonObject result = new JsonObject();
        result.add("content", content); //$NON-NLS-1$
        result.addProperty("isError", false); //$NON-NLS-1$
        result.add("structuredContent", structured); //$NON-NLS-1$

        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        FanOut.writeId(envelope, id);
        envelope.add("result", result); //$NON-NLS-1$
        return Json.compact(envelope);
    }

    @Test
    public void testMergePreservesTheOrderOfTheGivenResponseList()
    {
        String responseA = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        String responseB = listProjectsResponse(2, "ProjectB"); //$NON-NLS-1$

        // Reversed input order -> reversed merged order: the merge is order-preserving,
        // not sorting - callers (the handler) are responsible for passing port order.
        String merged = FanOut.mergeListProjects(Arrays.asList(responseB, responseA), 1);

        assertEquals(List.of("ProjectB", "ProjectA"), projectNamesOf(Json.parseObject(merged))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMergeRewritesIdToTheGivenNumericRequestId()
    {
        String responseA = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(List.of(responseA), 42);

        assertEquals(42, Json.parseObject(merged).get("id").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void testMergeRewritesIdToNullRequestId()
    {
        String responseA = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(List.of(responseA), null);

        // writeId stamps JsonNull for a null request id, but the shared Gson (no
        // serializeNulls) drops an explicit JsonNull member on serialization - so the
        // key is either absent or JSON null. Both are acceptable: the live call path
        // always carries a real id (a tools/call request); what matters is that the
        // ORIGINAL backend id (1) never leaks through.
        com.google.gson.JsonElement id = Json.parseObject(merged).get("id"); //$NON-NLS-1$
        assertTrue(id == null || id.isJsonNull());
    }

    // ---- skipping one broken/unusable response ----

    @Test
    public void testMergeSkipsAnUnparseableResponse()
    {
        String good = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        String broken = "this is not json"; //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(good, broken), 1);

        assertEquals(List.of("ProjectA"), projectNamesOf(Json.parseObject(merged))); //$NON-NLS-1$
    }

    @Test
    public void testMergeSkipsAJsonRpcErrorResponse()
    {
        String good = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        String errorResponse = FanOut.jsonRpcError(-32000, "boom", 2); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(good, errorResponse), 1);

        assertEquals(List.of("ProjectA"), projectNamesOf(Json.parseObject(merged))); //$NON-NLS-1$
    }

    @Test
    public void testMergeSkipsAToolLevelErrorResponse()
    {
        String good = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        JsonObject result = new JsonObject();
        result.addProperty("isError", true); //$NON-NLS-1$
        JsonObject toolError = new JsonObject();
        toolError.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        toolError.addProperty("id", 2); //$NON-NLS-1$
        toolError.add("result", result); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(good, Json.compact(toolError)), 1);

        assertEquals(List.of("ProjectA"), projectNamesOf(Json.parseObject(merged))); //$NON-NLS-1$
    }

    // ---- zero usable responses -> the -32000 "No running EDT backends" error ----

    @Test
    public void testZeroUsableResponsesYieldsNoBackendsError()
    {
        assertNoBackendsError(FanOut.mergeListProjects(List.of("garbage", "also garbage"), 7), 7); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullResponseListYieldsNoBackendsError()
    {
        assertNoBackendsError(FanOut.mergeListProjects(null, 1), 1);
    }

    @Test
    public void testEmptyResponseListYieldsNoBackendsError()
    {
        assertNoBackendsError(FanOut.mergeListProjects(List.of(), 1), 1);
    }

    private static void assertNoBackendsError(String merged, int expectedId)
    {
        JsonObject parsed = Json.parseObject(merged);
        assertTrue(parsed.has("error")); //$NON-NLS-1$
        JsonObject error = parsed.getAsJsonObject("error"); //$NON-NLS-1$
        assertEquals(FanOut.ERROR_NO_BACKENDS, error.get("code").getAsInt()); //$NON-NLS-1$
        assertEquals(-32000, error.get("code").getAsInt()); //$NON-NLS-1$
        assertEquals(FanOut.MSG_NO_BACKENDS, error.get("message").getAsString()); //$NON-NLS-1$
        assertEquals("No running EDT backends", error.get("message").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expectedId, parsed.get("id").getAsInt()); //$NON-NLS-1$
    }
}
