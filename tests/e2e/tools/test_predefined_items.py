"""
e2e tests for PREDEFINED-item authoring (#293): create_metadata / modify_metadata /
delete_metadata / get_metadata_details on a dedicated FQN grammar

    <OwnerType>.<OwnerName>.Predefined.<ItemName>

OwnerType in {Catalog, ChartOfCharacteristicTypes}. This is a deliberately CROSS-TOOL
file (the documented one-file-per-tool exception, like test_extension_coverage.py /
test_modify_metadata_dcs.py): the feature spans four existing tools, each of which
already has its own primary per-tool file — this file's tool= tags match those real,
already-covered tool names, so it adds DEPTH for this one feature rather than
duplicating each tool's general contract tests.

ARCHITECTURE (see the frozen spec, .claude/work/293-predefined/spec.md,
"PLAN-REVIEW CORRECTIONS"): the predefined content is a PLAIN EMF CONTAINMENT on the
owner (`MdClass.xcore`: `contains CatalogPredefined predefined`), not a separate
external-property top object. There is therefore no separate content resource to
attach; the owner's OWN FQN is force-exported. The owner's xcore comment notes the
original XML layout "is held in another resource" — the platform serializer MAY still
split the predefined items into a sibling `Predefined.xml` next to the owner's `.mdo`,
or inline them — this is the serializer's business. The disk assertion below therefore
does NOT hardcode a filename: it walks the owner's whole `src/Catalogs/<Name>/`
subtree and accepts a hit in ANY file (tolerates either layout).

No autonumbering: an omitted `code` is left UNSET (never invented). `code` is STRICT
JSON-typed, matched to the owner's code type (a Catalog's String/Number `codeType`; a
ChartOfCharacteristicTypes' plain String). A fresh Catalog created via create_metadata
gets the SAME "New"-wizard defaults create_metadata's top-level path always produces
(codeType=String, codeLength=9) — the happy-path code value below is chosen to fit.

Fixture: NO Catalog / ChartOfCharacteristicTypes with predefined items ships in
TestConfiguration, so every test SEEDS its own fresh top object with create_metadata
first (reverted by the write-metadata reset like every other seeded-object test in
this suite).

reset: kind="write-metadata" -> the orchestrator runs reset_fixture()+reset_model()
after each test.
"""

import os
import time

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    assert_tree_unchanged,
    poll_diff_contains,
    tree_snapshot,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
    _fail,
)


def _seed_catalog(name):
    """Creates a fresh Catalog top object (the EDT 'New'-wizard defaults: codeType=String,
    codeLength=9) and waits for the derived-data rebuild to settle, so the predefined-item
    creates right after it do not hit the transient BUILDING write-guard."""
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + name})
    assert_ok(r, "seed catalog Catalog." + name)
    wait_for_project_ready()
    return "Catalog." + name


def _seed_characteristic_types(name):
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "ChartOfCharacteristicTypes." + name})
    assert_ok(r, "seed ChartOfCharacteristicTypes." + name)
    wait_for_project_ready()
    return "ChartOfCharacteristicTypes." + name


def _poll_name_under_catalog_dir(catalog_name, needle, timeout=15):
    """Polls the owner's WHOLE on-disk subtree (src/Catalogs/<Name>/) for `needle` in ANY
    file — tolerates the predefined content landing inline in the owner's own .mdo OR split
    into a sibling Predefined.xml (the serializer's choice, per the xcore comment); the item
    name must appear in SOME file either way. Never hardcodes a filename."""
    base = os.path.join(PROJECT_DIR, "src", "Catalogs", catalog_name)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.isdir(base):
            for root, _dirs, files in os.walk(base):
                for fn in files:
                    full = os.path.join(root, fn)
                    try:
                        with open(full, encoding="utf-8", errors="replace") as f:
                            if needle in f.read():
                                return
                    except OSError:
                        pass
        time.sleep(0.5)
    _fail("expected %r to appear in SOME file under src/Catalogs/%s/ but it did not "
          "(checked for %ds)" % (needle, catalog_name, timeout))


# ══════════════════════════════════════════════════════════════════════════════
# Happy path: seed -> create folder -> create item (parent + code + description) ->
# details (owner section + item FQN) -> modify -> delete -> details no longer shows it
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_full_predefined_item_cycle():
    catalog = "E2EPredefCatalog"
    owner = _seed_catalog(catalog)

    # 1) Create a FOLDER.
    rf = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Warm",
        "properties": [{"name": "isFolder", "value": True}],
    })
    assert_ok(rf, "create predefined FOLDER Warm")
    assert rf.structured.get("action") == "created", "must report created: %r" % (rf.structured,)
    assert rf.structured.get("kind") == "CatalogPredefinedItem", \
        "kind must be the concrete predefined-item EClass: %r" % (rf.structured,)

    # 2) Create an item INTO that folder, with code + description.
    rc = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Red",
        "properties": [
            {"name": "parent", "value": "Warm"},
            {"name": "code", "value": "001"},
            {"name": "description", "value": "Bright red"},
        ],
    })
    assert_ok(rc, "create predefined item Red under Warm")
    assert rc.structured.get("action") == "created", "must report created: %r" % (rc.structured,)

    # The predefined content is a PLAIN containment on the owner (#293) -> only the OWNER's
    # own .mdo/on-disk subtree is dirtied; force-export targets the owner FQN. Tolerates either
    # an inlined .mdo or a sibling Predefined.xml (the serializer's choice).
    poll_diff_contains("Warm", ctx="the new folder must land on disk somewhere under the owner")
    _poll_name_under_catalog_dir(catalog, "Red")
    _poll_name_under_catalog_dir(catalog, "001")

    # 3) get_metadata_details on the OWNER renders the "Predefined items" section (both items,
    # the child's Parent column pointing at the folder).
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details on the owner")
    assert_contains(details.text, "Predefined items", "owner details must render the section header")
    assert_contains(details.text, "Warm", "owner details must list the folder")
    assert_contains(details.text, "Red", "owner details must list the nested item")
    assert_contains(details.text, "Bright red", "owner details must show the item's description")

    # Full mode must NOT carry less (issue #288 lesson) - the section renders there too.
    details_full = call("get_metadata_details",
                        {"projectName": PROJECT, "objectFqns": [owner], "full": True})
    assert_ok(details_full, "get_metadata_details full mode")
    assert_contains(details_full.text, "Predefined items", "full mode must still render the section")

    # 4) get_metadata_details on the ITEM FQN renders just that one item's properties.
    item_fqn = owner + ".Predefined.Red"
    item_details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_details, "get_metadata_details on the item FQN")
    # The exact single-item HEADING, not the bare words: "Predefined item" is a substring of the
    # owner section's "Predefined items", so a dispatch regression to the owner render would
    # otherwise slip through this assertion.
    assert_contains(item_details.text, "## Predefined item:", "must render the single-item view")
    assert_contains(item_details.text, "001", "must show the item's code")
    assert_contains(item_details.text, "Bright red", "must show the item's description")
    assert_contains(item_details.text, "Warm", "must show the item's parent")

    # 5) modify_metadata changes the description.
    rm = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": item_fqn,
        "properties": [{"name": "description", "value": "Crimson red"}],
    })
    assert_ok(rm, "modify predefined item description")
    assert rm.structured.get("action") == "modified", "must report modified: %r" % (rm.structured,)

    item_details_after_modify = call("get_metadata_details",
                                     {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_details_after_modify, "get_metadata_details after modify")
    assert_contains(item_details_after_modify.text, "Crimson red",
                    "the modified description must be visible")
    assert_not_contains(item_details_after_modify.text, "Bright red",
                        "the OLD description must be gone")

    # 6) delete_metadata: preview first (no mutation), then confirm. The tree is already dirty
    # from the seeding above, so no-mutation is proven by a before/after SNAPSHOT around the
    # preview call, not by assert_no_diff (which demands a fully clean tree).
    snap_before_preview = tree_snapshot()
    preview = call("delete_metadata", {"projectName": PROJECT, "fqn": item_fqn})
    assert_ok(preview, "preview delete of the predefined item")
    assert preview.structured.get("action") == "preview", \
        "a bare call must preview, not execute: %r" % (preview.structured,)
    assert_tree_unchanged(snap_before_preview,
                          "a preview (confirm absent) must not mutate the model")

    confirm = call("delete_metadata", {"projectName": PROJECT, "fqn": item_fqn, "confirm": True})
    assert_ok(confirm, "confirm delete of the predefined item")
    assert confirm.structured.get("action") == "executed", \
        "confirm=true must execute: %r" % (confirm.structured,)

    # 7) get_metadata_details on the owner no longer shows the deleted item, but the folder
    # (a sibling, untouched) survives.
    details_after_delete = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details_after_delete, "get_metadata_details after delete")
    assert_not_contains(details_after_delete.text, "Red",
                        "the deleted item must no longer be listed")
    assert_contains(details_after_delete.text, "Warm",
                    "the sibling folder must survive a targeted item delete")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_folder_delete_cascades_children_preview_reports_count():
    catalog = "E2EPredefCascade"
    owner = _seed_catalog(catalog)

    call("create_metadata", {
        "projectName": PROJECT, "fqn": owner + ".Predefined.Group",
        "properties": [{"name": "isFolder", "value": True}],
    })
    for name in ("Item1", "Item2"):
        r = call("create_metadata", {
            "projectName": PROJECT, "fqn": owner + ".Predefined." + name,
            "properties": [{"name": "parent", "value": "Group"}],
        })
        assert_ok(r, "seed child " + name)

    preview = call("delete_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Group"})
    assert_ok(preview, "preview folder delete")
    assert preview.structured.get("action") == "preview", \
        "a bare call must preview: %r" % (preview.structured,)
    # The preview message must warn about the cascade with the exact count PHRASE, anchored on
    # both sides ("its 2 nested item(s)") - a bare "2" would match the '2' in this owner's own
    # name, and an unanchored "2 nested item" would still match a wrong "12 nested item(s)".
    msg = (preview.structured or {}).get("message", "")
    assert_contains(msg, "its 2 nested item(s)",
                    "the preview must report the exact nested-item cascade count")
    # And the structured items must list the folder AND every cascaded descendant by name.
    item_names = [row.get("name") for row in (preview.structured or {}).get("items", [])]
    assert item_names == ["Group", "Item1", "Item2"], \
        "preview items must list the folder and both cascaded children: %r" % (item_names,)

    confirm = call("delete_metadata",
                   {"projectName": PROJECT, "fqn": owner + ".Predefined.Group", "confirm": True})
    assert_ok(confirm, "confirm folder delete cascades children")

    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details after cascade delete")
    assert_not_contains(details.text, "Item1", "cascaded child Item1 must be gone")
    assert_not_contains(details.text, "Item2", "cascaded child Item2 must be gone")
    assert_not_contains(details.text, "Group", "the deleted folder itself must be gone")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_characteristic_types_predefined_item():
    # ChartOfCharacteristicTypes' code is a plain String (no codeType matching needed).
    types = "E2EPredefTypes"
    owner = _seed_characteristic_types(types)

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Weight",
        "properties": [{"name": "code", "value": "W1"}],
    })
    assert_ok(r, "create predefined item on ChartOfCharacteristicTypes")
    assert r.structured.get("kind") == "ChartOfCharacteristicTypesPredefinedItem", \
        "kind must be the concrete EClass: %r" % (r.structured,)

    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details on ChartOfCharacteristicTypes owner")
    assert_contains(details.text, "Weight", "must list the new item")
    assert_contains(details.text, "W1", "must show its code")


# ══════════════════════════════════════════════════════════════════════════════
# Negatives
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_chart_of_accounts_predefined_is_deferred_not_faked():
    # The owner-TYPE gate runs before any owner-existence lookup, so a non-existent
    # ChartOfAccounts name still gets the actionable "not yet supported" refusal (never a
    # false "owner not found", and never silently faked support).
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "ChartOfAccounts.NoSuchChart.Predefined.Cash",
    })
    err = assert_error(r, "ChartOfAccounts predefined item must be refused")
    assert_error_quality(err, suggests=["not yet supported"])
    assert_no_diff("a refused create must not mutate the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_document_owner_kind_is_rejected():
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Document.NoSuchDoc.Predefined.X",
    })
    err = assert_error(r, "a Document owner must be rejected (no predefined items)")
    assert_error_quality(err, suggests=["predefined items"])
    assert_no_diff("a refused create must not mutate the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_duplicate_predefined_item_is_rejected():
    catalog = "E2EPredefDup"
    owner = _seed_catalog(catalog)
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})
    assert_ok(r1, "seed the first item")

    r2 = call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})
    err = assert_error(r2, "an exact-name duplicate must be rejected")
    assert_error_quality(err, names=["Blue"], suggests=["already exists"])


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_bad_parent_is_rejected():
    catalog = "E2EPredefBadParent"
    owner = _seed_catalog(catalog)

    # The tree is already dirty from the seeded catalog - prove the refused create added nothing
    # via a before/after snapshot (assert_no_diff would fail on the seed's own diff).
    snap = tree_snapshot()
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Child",
        "properties": [{"name": "parent", "value": "NoSuchFolder"}],
    })
    err = assert_error(r, "an unknown parent folder must be rejected")
    assert_error_quality(err, names=["NoSuchFolder"], suggests=["not found"])
    assert_tree_unchanged(snap, "a refused create must not mutate the project")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_rename_via_modify_is_refused():
    catalog = "E2EPredefRename"
    owner = _seed_catalog(catalog)
    call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})

    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Blue",
        "properties": [{"name": "name", "value": "Navy"}],
    })
    err = assert_error(r, "renaming a predefined item must be refused")
    assert_error_quality(err, suggests=["not supported"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_parent_move_via_modify_is_refused():
    catalog = "E2EPredefMove"
    owner = _seed_catalog(catalog)
    call("create_metadata", {
        "projectName": PROJECT, "fqn": owner + ".Predefined.Group",
        "properties": [{"name": "isFolder", "value": True}],
    })
    call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})

    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Blue",
        "properties": [{"name": "parent", "value": "Group"}],
    })
    err = assert_error(r, "moving a predefined item via modify must be refused")
    assert_error_quality(err, suggests=["not yet supported"])


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_nonexistent_predefined_item_is_rejected():
    catalog = "E2EPredefDelMissing"
    owner = _seed_catalog(catalog)

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.NoSuchItem"})
    err = assert_error(r, "deleting a non-existent predefined item must fail")
    assert_error_quality(err, names=["NoSuchItem"], suggests=["not found"])
