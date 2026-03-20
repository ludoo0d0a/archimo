# Archimo manifest (`archimo.mf`)

The manifest is an **optional JSON file** at the **root of the project** Archimo scans (`--project-dir`). It describes **C4-style architecture elements** that reverse engineering cannot infer reliably: **people**, **external systems**, **third-party APIs**, **message brokers**, and similar nodes you want to appear in the report **before** (and merged with) data from bytecode and project scans.

- **File name:** `archimo.mf`  
- **Format:** JSON (UTF-8)  
- **Shape:** Same as the exported **`architecture.json`** (and `json/c4-report-tree.json`): a **`C4ReportTree`** document.  
- **Partial documents are valid:** omit any property you do not need; unknown JSON properties are ignored.

---

## When Archimo loads it

The manifest is read only when extraction runs with a **project directory** (e.g. `--project-dir`, or the internal child process in project mode). Classpath-only runs without a project root do not load `archimo.mf`.

If the file is missing, behavior is unchanged from a manifest-less run.

If the file is present but **empty of content** (no `levelSections` and no `diagramSlots`), it is treated as absent.

---

## Top-level structure

| Property | Type | Description |
|----------|------|-------------|
| `applicationShortName` | string | Optional display name. If it differs from the name inferred from the main class, Archimo **warns** and keeps the **scanned** name in the merged tree. |
| `applicationMainClassFqcn` | string | Optional FQCN. If it disagrees with the discovered main class, Archimo **warns** and keeps the **scanned** FQCN. |
| `levelSections` | array | C4 levels (1–4 in practice), each with **groups** of **elements**. This is where most manifest content lives. |
| `diagramSlots` | array | Optional ordering/metadata entries for diagrams in the static site index (see below). |

---

## `levelSections`

Each item:

| Property | Type | Description |
|----------|------|-------------|
| `level` | number | C4 level (e.g. `1` context, `2` containers, `3` composition, `4` code). |
| `title` | string | Section title shown in the report model. |
| `groups` | array | Named buckets of elements. |

### `groups`

| Property | Type | Description |
|----------|------|-------------|
| `groupId` | string | Stable id (e.g. `l1-context`, `l2-containers`, `l3-modules`). Merging matches the scanner’s groups by this id when possible. |
| `title` | string | Human-readable group title. |
| `sortOrder` | number | Ordering within the level (lower first). |
| `elements` | array | Nodes in this group. |

### `elements`

| Property | Type | Description |
|----------|------|-------------|
| `id` | string | **Stable unique id** across the whole tree. The scanner uses its own ids (`user`, `app`, `mod_…`, class-based ids, `extHttp_0`, …). Use **distinct** ids for manifest-only nodes (e.g. `partner_acme`, `auditor_jane`). |
| `kind` | string | One of the **`C4ElementKind`** enum names (see below). |
| `label` | string | Short title on diagrams / UI. |
| `technology` | string | Optional technology or free-text description. |
| `attributes` | object | String map for extra metadata (optional). |
| `links` | array | Outbound **relationships** to other elements by id. |

### `links` (on an element)

| Property | Type | Description |
|----------|------|-------------|
| `targetElementId` | string | Id of the other element. |
| `label` | string | Relationship label (e.g. `Uses`, `HTTPS`, `Messaging`). |
| `technology` | string | Optional (e.g. protocol). |

Use `targetElementId` / `id` consistently. The scan defines **`user`** and **`app`** in L1. You can link manifest **PERSON** or **EXTERNAL_SYSTEM** nodes **to** `app` (see the compliance example below). If you declare an element with id **`app`** in the manifest mainly to add outbound links, scalar fields are still compared with the scan and may produce **warnings**; **links are unioned** with the scanned `app` element.

---

## `C4ElementKind` values (JSON)

Use the **enum name** as a JSON string:

`PERSON`, `SOFTWARE_SYSTEM`, `EXTERNAL_SYSTEM`, `CONTAINER`, `DATABASE`, `MODULE`, `COMPONENT`, `CLASS`, `MESSAGE_BROKER`, `SUPPORTING`

Typical manifest additions:

- **`PERSON`** — stakeholders, auditors, support roles.  
- **`EXTERNAL_SYSTEM`** — SaaS, partner APIs, legacy systems.  
- **`MESSAGE_BROKER`** — brokers or buses not fully visible from code.  
- **`SUPPORTING`** — anything that does not fit the other kinds.

---

## `diagramSlots` (optional)

Each slot describes one diagram entry for the report UI:

| Property | Type | Description |
|----------|------|-------------|
| `diagramId` | string | File stem (e.g. `system-context`, `c4-containers`). |
| `format` | string | `plantuml` or `mermaid`. |
| `c4Level` | number | Navigation bucket (0 = supporting / non-static-C4). |
| `c4Order` | number | Order within the level. |
| `navLabel` | string | Sidebar label. |
| `levelKey` | string | e.g. `system`, `container`, `component`, `code`. |
| `category` | string | e.g. `overview`, `module`, `flow`. |

**Merge rule:** manifest slots come **first**. If the scan defines the same `(diagramId, format)` pair, Archimo **warns** and **keeps the manifest** slot.

---

## How merge works (summary)

1. The **manifest** tree is loaded and normalized (empty lists where omitted).  
2. The **scanner** builds a full tree from Modulith + bytecode + project scans.  
3. The two trees are **merged**:
   - **Element ids** are global.  
   - Elements **only in the manifest** stay in the merged result.  
   - Elements **only in the scan** are added (including new levels/groups if needed).  
   - For the **same id**, **label, kind, technology, and attributes** are taken from the **scan**; if they differ from the manifest, Archimo logs a **warning** on stderr.  
   - **Links** are **unioned** (same `targetElementId` + `label` deduped). If only **technology** differs on a duplicate link, Archimo **warns** and keeps the **scanned** link.  
4. The merged tree drives **PlantUML**, **Mermaid**, **`architecture.json`** (with `-o json`), **`json/c4-report-tree.json`**, and **`site/site-index.json`**.

---

## Suggested workflow

1. Run Archimo on your project with JSON export, e.g.  
   `-o plantuml,mermaid,json`  
   (or copy `json/c4-report-tree.json` after a normal run).
2. Copy the relevant parts into **`archimo.mf`** at the project root and **trim** to what you want to maintain by hand (externals, people, slots, …).
3. Use **stable `id` values** you control; avoid colliding with scanner ids unless you intend to override merged fields (and accept warnings when they disagree).
4. Re-run extraction and fix warnings or align the manifest with the scan.

---

## Minimal examples

### External system on L1 (dedicated group)

```json
{
  "levelSections": [
    {
      "level": 1,
      "title": "System context (L1)",
      "groups": [
        {
          "groupId": "l1-context",
          "title": "Actors, system, externals",
          "sortOrder": 0,
          "elements": [
            {
              "id": "payment_provider",
              "kind": "EXTERNAL_SYSTEM",
              "label": "Payment provider",
              "technology": "HTTPS / REST",
              "attributes": {},
              "links": []
            }
          ]
        }
      ]
    }
  ]
}
```

After merge, scanner elements in the same group (e.g. `user`, `app`, inferred HTTP clients) are **added** alongside `payment_provider`. To show a relationship from the app to this node in the diagram model, you may need a link on **`app`** with `targetElementId`: `payment_provider` once your manifest and scan share consistent ids for `app` (the scan creates `app`).

### Person stakeholder

```json
{
  "levelSections": [
    {
      "level": 1,
      "title": "System context (L1)",
      "groups": [
        {
          "groupId": "l1-context",
          "title": "Actors, system, externals",
          "sortOrder": 0,
          "elements": [
            {
              "id": "compliance_officer",
              "kind": "PERSON",
              "label": "Compliance officer",
              "technology": "Reviews audit exports",
              "attributes": {},
              "links": [
                {
                  "targetElementId": "app",
                  "label": "Reviews reports from",
                  "technology": null
                }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

---

## Reference

- Loader: `fr.geoking.archimo.extract.ArchimoManifestLoader`  
- Merge: `fr.geoking.archimo.extract.C4ReportTreeMerger`  
- Model: `fr.geoking.archimo.extract.model.report.*`  

CLI overview: **[USAGE.md](../USAGE.md)**.
