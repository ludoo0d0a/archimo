(() => {
  let index = null;
  let selectedDiagram = null;
  let showingSource = false;
  let panZoomInstance = null;
  let endpointCoverageFilter = 'ALL';
  let endpointMethodFilter = 'ALL';
  let initialSearchTerm = '';
  let initialDiagramId = null;

  function encodeKroki(str) {
    if (typeof pako === 'undefined') return null;
    const data = new TextEncoder().encode(str);
    const compressed = pako.deflate(data, { level: 9 });
    const bin = Array.from(compressed);
    let binary = '';
    const chunk = 8192;
    for (let i = 0; i < bin.length; i += chunk) {
      binary += String.fromCharCode.apply(null, bin.slice(i, i + chunk));
    }
    const base64 = btoa(binary).replace(/\+/g, '-').replace(/\//g, '_');
    return base64;
  }

  const LS_C4_OVERLAY = 'archimo.userC4Overlay';

  function loadC4Overlay() {
    try {
      const raw = localStorage.getItem(LS_C4_OVERLAY);
      if (!raw) return { elements: [] };
      const o = JSON.parse(raw);
      if (!o || !Array.isArray(o.elements)) return { elements: [] };
      return o;
    } catch (e) {
      return { elements: [] };
    }
  }

  function saveC4Overlay(overlay) {
    localStorage.setItem(LS_C4_OVERLAY, JSON.stringify(overlay));
  }

  function deepClone(o) {
    return o == null ? o : JSON.parse(JSON.stringify(o));
  }

  function getMergedC4ReportTree() {
    const base = index && index.c4ReportTree ? deepClone(index.c4ReportTree) : { levelSections: [] };
    if (!base.levelSections) base.levelSections = [];
    const overlay = loadC4Overlay();
    for (const item of overlay.elements || []) {
      const level = Number(item.level);
      const groupId = item.groupId;
      const el = item.element;
      if (!el || !el.id || !Number.isFinite(level) || !groupId) continue;
      let sec = base.levelSections.find(s => s.level === level);
      if (!sec) {
        sec = { level, title: 'Level ' + level, groups: [] };
        base.levelSections.push(sec);
        base.levelSections.sort((a, b) => a.level - b.level);
      }
      if (!sec.groups) sec.groups = [];
      let grp = sec.groups.find(g => g.groupId === groupId);
      if (!grp) {
        grp = { groupId, title: groupId, sortOrder: 0, elements: [] };
        sec.groups.push(grp);
      }
      if (!grp.elements) grp.elements = [];
      const merged = { ...el, origin: 'MANUAL' };
      const idx = grp.elements.findIndex(e => e.id === merged.id);
      if (idx >= 0) grp.elements[idx] = merged;
      else grp.elements.push(merged);
    }
    return base;
  }

  function pumlEscape(s) {
    if (s == null || s === '') return '';
    return String(s).replace(/\\/g, '\\\\').replace(/"/g, "'");
  }

  function collectDeclaredPlantUmlIds(src) {
    const ids = new Set();
    if (!src) return ids;
    const re = /\b(?:Person_Ext|System|System_Ext|Container|ContainerDb)\(\s*([a-zA-Z0-9_]+)/g;
    let m;
    while ((m = re.exec(src)) !== null) ids.add(m[1]);
    return ids;
  }

  function plantUmlLabelWithManual(el) {
    const base = el.label != null ? el.label : '';
    const manual = el.origin === 'MANUAL' || (el.id && String(el.id).startsWith('user_'));
    const withTag = manual ? base + '\\n<size:10>[Manual]</size>' : base;
    return pumlEscape(withTag);
  }

  function plantUmlLineL1(el) {
    const label = plantUmlLabelWithManual(el);
    const tech = pumlEscape(el.technology || '');
    const k = el.kind;
    if (k === 'PERSON') return `Person_Ext(${el.id}, "${label}", "${tech}")`;
    if (k === 'SOFTWARE_SYSTEM') return `System(${el.id}, "${label}", "${tech}")`;
    if (k === 'EXTERNAL_SYSTEM' || k === 'MESSAGE_BROKER') return `System_Ext(${el.id}, "${label}", "${tech}")`;
    return '';
  }

  function plantUmlRelLines(fromId, links) {
    if (!links || !links.length) return [];
    return links.map(l => {
      const lab = pumlEscape(l.label || '');
      const t = l.technology;
      if (t != null && String(t).trim() !== '') {
        return `Rel(${fromId}, ${l.targetElementId}, "${lab}", "${pumlEscape(t)}")`;
      }
      return `Rel(${fromId}, ${l.targetElementId}, "${lab}")`;
    });
  }

  function insertBeforeClosingSystemBoundary(src, insertion) {
    const start = src.indexOf('System_Boundary');
    if (start < 0) return src + insertion;
    const open = src.indexOf('{', start);
    if (open < 0) return src + insertion;
    let depth = 0;
    for (let i = open; i < src.length; i++) {
      const c = src[i];
      if (c === '{') depth++;
      else if (c === '}') {
        depth--;
        if (depth === 0) {
          return src.slice(0, i) + insertion + src.slice(i);
        }
      }
    }
    return src + insertion;
  }

  /**
   * Injects localStorage user overlay elements into PlantUML (L1/L2) before Kroki.
   */
  function injectUserPlantUml(source, diagramId, userOverlayElements) {
    if (!source || !userOverlayElements || !userOverlayElements.length) return source;
    const declared = collectDeclaredPlantUmlIds(source);
    const toAdd = [];
    const rels = [];
    if (diagramId === 'system-context') {
      for (const u of userOverlayElements) {
        if (Number(u.level) !== 1 || u.groupId !== 'l1-context') continue;
        const el = u.element;
        if (!el || declared.has(el.id)) continue;
        const line = plantUmlLineL1(el);
        if (line) {
          toAdd.push(line);
          declared.add(el.id);
        }
        rels.push(...plantUmlRelLines(el.id, el.links));
      }
      if (!toAdd.length && !rels.length) return source;
      const block = [...toAdd, ...rels].join('\n') + '\n';
      return source.replace(/@enduml/gi, block + '@enduml');
    }
    if (diagramId === 'c4-containers') {
      const persons = [];
      const inside = [];
      for (const u of userOverlayElements) {
        if (Number(u.level) !== 2 || u.groupId !== 'l2-containers') continue;
        const el = u.element;
        if (!el || declared.has(el.id)) continue;
        const label = plantUmlLabelWithManual(el);
        const tech = pumlEscape(el.technology || '');
        const k = el.kind;
        if (k === 'PERSON') {
          persons.push(`Person_Ext(${el.id}, "${label}", "${tech}")`);
          declared.add(el.id);
          rels.push(...plantUmlRelLines(el.id, el.links));
        } else if (k === 'CONTAINER') {
          inside.push(`  Container(${el.id}, "${label}", "${tech}")`);
          declared.add(el.id);
          rels.push(...plantUmlRelLines(el.id, el.links));
        } else if (k === 'DATABASE') {
          inside.push(`  ContainerDb(${el.id}, "${label}", "${tech}")`);
          declared.add(el.id);
          rels.push(...plantUmlRelLines(el.id, el.links));
        }
      }
      let s = source;
      if (persons.length) {
        const bi = s.indexOf('System_Boundary');
        if (bi >= 0) s = s.slice(0, bi) + persons.join('\n') + '\n' + s.slice(bi);
        else s = persons.join('\n') + '\n' + s;
      }
      if (inside.length) s = insertBeforeClosingSystemBoundary(s, inside.join('\n') + '\n');
      if (rels.length) s = s.replace(/@enduml/gi, rels.join('\n') + '\n@enduml');
      return s;
    }
    return source;
  }

  function effectivePlantUmlSource(d) {
    if (!d || d.format !== 'plantuml' || !d.source) return d ? d.source : '';
    const id = d.id != null ? String(d.id) : '';
    if (id !== 'system-context' && id !== 'c4-containers') return d.source;
    return injectUserPlantUml(d.source, id, loadC4Overlay().elements || []);
  }

  function mergeManifestForExport(original, overlay) {
    const base =
      original && typeof original === 'object'
        ? deepClone(original)
        : { levelSections: [] };
    if (!base.levelSections) base.levelSections = [];
    for (const item of overlay.elements || []) {
      const level = Number(item.level);
      const groupId = item.groupId;
      const el = item.element;
      if (!el || !el.id || !Number.isFinite(level) || !groupId) continue;
      let sec = base.levelSections.find(s => s.level === level);
      if (!sec) {
        sec = { level, title: 'Level ' + level, groups: [] };
        base.levelSections.push(sec);
        base.levelSections.sort((a, b) => a.level - b.level);
      }
      if (!sec.groups) sec.groups = [];
      let grp = sec.groups.find(g => g.groupId === groupId);
      if (!grp) {
        grp = { groupId, title: groupId, sortOrder: 0, elements: [] };
        sec.groups.push(grp);
      }
      if (!grp.elements) grp.elements = [];
      const clean = { ...el };
      delete clean.origin;
      const idx = grp.elements.findIndex(e => e.id === clean.id);
      if (idx >= 0) grp.elements[idx] = clean;
      else grp.elements.push(clean);
    }
    return base;
  }

  function refreshC4ModelPanel() {
    const tree = getMergedC4ReportTree();
    const containers = {
      1: document.getElementById('elementListL1'),
      2: document.getElementById('elementListL2'),
      3: document.getElementById('elementListL3'),
      4: document.getElementById('elementListL4'),
      other: document.getElementById('elementListOther')
    };

    Object.values(containers).forEach(c => { if (c) c.innerHTML = ''; });

    if (!tree.levelSections || !tree.levelSections.length) return;

    for (const sec of tree.levelSections) {
      const lvl = Number(sec.level);
      const list = (lvl >= 1 && lvl <= 4) ? containers[lvl] : containers.other;
      if (!list) continue;

      for (const g of sec.groups || []) {
        for (const e of g.elements || []) {
          const origin = e.origin === 'MANUAL' ? 'manual' : 'auto';
          const li = document.createElement('li');
          li.className = 'c4-element-item';
          li.innerHTML =
            '<span class="c4-el-label">' +
              escapeHtml(e.label || e.id || '') +
            '</span> <span class="c4-origin c4-origin-' +
              origin +
            '">' +
              origin +
            '</span> <code class="c4-el-kind">' +
              escapeHtml(e.kind || '') +
            '</code>';
          list.appendChild(li);
        }
      }
    }
  }

  function openC4AddModal() {
    const modal = document.getElementById('c4AddModal');
    if (!modal) return;
    syncC4KindOptions();
    modal.classList.remove('hidden');
    document.getElementById('c4AddLabel').value = '';
    document.getElementById('c4AddTech').value = '';
    document.getElementById('c4AddLinkTarget').value = '';
    document.getElementById('c4AddLinkLabel').value = '';
  }

  function closeC4Modal() {
    const modal = document.getElementById('c4AddModal');
    if (modal) modal.classList.add('hidden');
  }

  function syncC4KindOptions() {
    const level = Number(document.getElementById('c4AddLevel').value);
    const sel = document.getElementById('c4AddKind');
    if (!sel) return;
    const l1 = ['PERSON', 'SOFTWARE_SYSTEM', 'EXTERNAL_SYSTEM', 'MESSAGE_BROKER'];
    const l2 = ['PERSON', 'CONTAINER', 'DATABASE'];
    const opts = level === 2 ? l2 : l1;
    sel.innerHTML = opts.map(k => `<option value="${k}">${k}</option>`).join('');
    const gid = document.getElementById('c4AddGroupId');
    if (gid) {
      gid.value = level === 2 ? 'l2-containers' : 'l1-context';
    }
  }

  function exportArchimoManifest() {
    const merged = mergeManifestForExport(index && index.archimoManifestOriginal, loadC4Overlay());
    const blob = new Blob([JSON.stringify(merged, null, 2)], { type: 'application/json;charset=utf-8' });
    if (typeof saveAs !== 'undefined') {
      saveAs(blob, 'archimo.mf');
    } else {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'archimo.mf';
      a.click();
      URL.revokeObjectURL(a.href);
    }
  }

  function hideDiagramProvenance() {
    const el = document.getElementById('diagramProvenance');
    if (el) {
      el.classList.add('hidden');
      el.innerHTML = '';
    }
  }

  /**
   * Uses site-index provenance when present; otherwise infers from diagram id/format (older reports).
   */
  function provenanceForDiagram(d) {
    if (!d) return null;
    if (d.provenanceSourceLabel && d.provenanceNotationLabel && d.provenanceRendererLabel) {
      return {
        source: d.provenanceSourceLabel,
        notation: d.provenanceNotationLabel,
        renderer: d.provenanceRendererLabel
      };
    }
    const id = d.id != null ? String(d.id) : '';
    if (d.format === 'mermaid') {
      return { source: 'Archimo', notation: 'Mermaid', renderer: 'Mermaid (browser)' };
    }
    if (d.format === 'plantuml') {
      const modulith = id === 'components' || id.startsWith('module-');
      const c4 =
        modulith ||
        id === 'system-context' ||
        id === 'c4-containers' ||
        id === 'architecture-layers' ||
        id === 'deployment-diagram' ||
        id === 'messaging-flows';
      return {
        source: modulith ? 'Spring Modulith' : 'Archimo',
        notation: c4 ? 'C4 · PlantUML' : 'PlantUML',
        renderer: 'Kroki (PlantUML)'
      };
    }
    return null;
  }

  function updateDiagramProvenance(d) {
    const el = document.getElementById('diagramProvenance');
    if (!el) return;
    const p = provenanceForDiagram(d);
    if (!p) {
      hideDiagramProvenance();
      return;
    }
    el.classList.remove('hidden');
    const chip = (k, v) =>
      '<span class="prov-chip"><span class="prov-k">' + escapeHtml(k) + '</span> ' + escapeHtml(v) + '</span>';
    el.innerHTML =
      chip('Source', p.source) +
      '<span class="prov-sep" aria-hidden="true">·</span>' +
      chip('Notation', p.notation) +
      '<span class="prov-sep" aria-hidden="true">·</span>' +
      chip('Render', p.renderer);
  }

  function showError(msg) {
    hideDiagramProvenance();
    const titleEl = document.getElementById('diagramViewerTitle');
    const viewerEl = document.getElementById('diagramViewer');
    if (titleEl) titleEl.textContent = 'Error';
    if (viewerEl) viewerEl.innerHTML = '<p class="no-diagram">' + escapeHtml(msg) + '</p>';
  }

  async function loadIndex() {
    try {
      initTheme();
      let res;
      try {
        res = await fetch('site-index.json');
        if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
        index = await res.json();
      } catch (e) {
        console.error('Failed to load site-index.json', e);
        index = { diagrams: [], modules: [], classes: [], events: [], endpoints: [], endpointSequences: [], commands: [], openApiSpecs: [], externalHttpClients: [], deploymentFiles: [], deploymentContainers: [], deploymentK8sServices: [], deploymentIngresses: [], deploymentExternalSystems: [], c4ReportTree: null, archimoManifestOriginal: null };
      }
      if (!index.diagrams) index.diagrams = [];
      if (!index.modules) index.modules = [];
      if (!index.classes) index.classes = [];
      if (!index.events) index.events = [];
      if (!index.endpoints) index.endpoints = [];
      if (!index.endpointSequences) index.endpointSequences = [];
      if (!index.commands) index.commands = [];
      if (!index.openApiSpecs) index.openApiSpecs = [];
      if (!index.externalHttpClients) index.externalHttpClients = [];
      if (!index.deploymentFiles) index.deploymentFiles = [];
      if (!index.deploymentContainers) index.deploymentContainers = [];
      if (!index.deploymentK8sServices) index.deploymentK8sServices = [];
      if (!index.deploymentIngresses) index.deploymentIngresses = [];
      if (!index.deploymentExternalSystems) index.deploymentExternalSystems = [];
      if (!index.c4ReportTree) index.c4ReportTree = { levelSections: [], diagramSlots: [] };
      if (!('archimoManifestOriginal' in index)) index.archimoManifestOriginal = null;
      applyStateFromUrl();
      if (typeof mermaid !== 'undefined') {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: 'loose',
          theme: document.body.classList.contains('dark-mode') ? 'dark' : 'default'
        });
      }
      bindUI();
      renderDiagramLists();
      refreshC4ModelPanel();
      renderEndpointList();
      renderSearch(initialSearchTerm);
      await selectInitialDiagram();
    } catch (err) {
      console.error('Report init error', err);
      showError('Could not initialize report: ' + (err.message || String(err)));
    }
  }

  function initTheme() {
    const savedTheme = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    if (savedTheme === 'dark' || (!savedTheme && prefersDark)) {
      document.body.classList.add('dark-mode');
    }
  }

  function toggleTheme() {
    const isDark = document.body.classList.toggle('dark-mode');
    localStorage.setItem('theme', isDark ? 'dark' : 'light');
    if (typeof mermaid !== 'undefined') {
      mermaid.initialize({
        startOnLoad: false,
        theme: isDark ? 'dark' : 'default'
      });
      if (selectedDiagram && selectedDiagram.format === 'mermaid') {
        void selectDiagram(selectedDiagram);
      }
    }
  }

  function bindUI() {
    const searchInput = document.getElementById('searchInput');
    const viewSourceBtn = document.getElementById('viewSourceBtn');
    const themeToggle = document.getElementById('themeToggle');
    const copyLinkBtn = document.getElementById('copyLinkBtn');
    const fitBtn = document.getElementById('fitBtn');
    const resetBtn = document.getElementById('resetBtn');

    if (searchInput) {
      searchInput.value = initialSearchTerm;
      searchInput.addEventListener('input', (e) => {
        renderSearch(e.target.value.trim());
        syncUrlState();
      });
    }
    document.addEventListener('keydown', handleGlobalShortcuts);
    if (viewSourceBtn) viewSourceBtn.addEventListener('click', toggleSource);
    if (themeToggle) themeToggle.addEventListener('click', toggleTheme);
    if (copyLinkBtn) {
      copyLinkBtn.addEventListener('click', () => copyDeepLink(copyLinkBtn));
    }
    if (fitBtn) fitBtn.addEventListener('click', () => {
      if (panZoomInstance) {
        panZoomInstance.fit();
        panZoomInstance.center();
      }
    });
    if (resetBtn) resetBtn.onclick = () => {
      if (panZoomInstance) {
        panZoomInstance.resetZoom();
        panZoomInstance.center();
      }
    };

    const exportPngBtn = document.getElementById('exportPngBtn');
    const exportPdfBtn = document.getElementById('exportPdfBtn');
    const exportAllZipBtn = document.getElementById('exportAllZipBtn');
    const printAllBtn = document.getElementById('printAllBtn');

    if (exportPngBtn) exportPngBtn.onclick = () => void exportCurrentDiagram('png');
    if (exportPdfBtn) exportPdfBtn.onclick = () => void exportCurrentDiagram('pdf');
    if (exportAllZipBtn) exportAllZipBtn.onclick = () => exportAllAsZip();
    if (printAllBtn) printAllBtn.onclick = () => printAllDiagrams();

    const c4Level = document.getElementById('c4AddLevel');
    if (c4Level) c4Level.addEventListener('change', () => syncC4KindOptions());
    const addC4Btn = document.getElementById('addC4ElementBtn');
    const exportMfBtn = document.getElementById('exportManifestBtn');
    if (addC4Btn) addC4Btn.addEventListener('click', () => openC4AddModal());
    if (exportMfBtn) exportMfBtn.addEventListener('click', () => exportArchimoManifest());
    const c4Modal = document.getElementById('c4AddModal');
    if (c4Modal) {
      c4Modal.addEventListener('click', e => {
        if (e.target.dataset.closeModal) closeC4Modal();
      });
    }
    const c4Form = document.getElementById('c4AddForm');
    if (c4Form) {
      c4Form.addEventListener('submit', e => {
        e.preventDefault();
        const level = Number(document.getElementById('c4AddLevel').value);
        const groupId = document.getElementById('c4AddGroupId').value;
        const kind = document.getElementById('c4AddKind').value;
        const label = document.getElementById('c4AddLabel').value.trim();
        const technology = document.getElementById('c4AddTech').value.trim();
        const linkTarget = document.getElementById('c4AddLinkTarget').value.trim();
        const linkLabel = document.getElementById('c4AddLinkLabel').value.trim();
        const uid =
          typeof crypto !== 'undefined' && crypto.randomUUID
            ? 'user_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16)
            : 'user_' + Math.random().toString(36).slice(2) + Date.now().toString(36);
        const links = [];
        if (linkTarget) {
          links.push({ targetElementId: linkTarget, label: linkLabel || 'Relates to', technology: null });
        }
        const element = { id: uid, kind, label, technology, attributes: {}, links, origin: 'MANUAL' };
        const overlay = loadC4Overlay();
        overlay.elements.push({ level, groupId, element });
        saveC4Overlay(overlay);
        closeC4Modal();
        refreshC4ModelPanel();
        if (
          selectedDiagram &&
          selectedDiagram.format === 'plantuml' &&
          (selectedDiagram.id === 'system-context' || selectedDiagram.id === 'c4-containers')
        ) {
          void selectDiagram(selectedDiagram);
        }
      });
    }

    const coverageFilters = document.getElementById('endpointCoverageFilters');
    if (coverageFilters) {
      coverageFilters.addEventListener('click', (e) => {
        const resetBtn = e.target.closest('button[data-reset-endpoint-filters]');
        if (resetBtn) {
          endpointCoverageFilter = 'ALL';
          endpointMethodFilter = 'ALL';
          renderEndpointList();
          syncUrlState();
          return;
        }
        const btn = e.target.closest('button[data-coverage]');
        if (!btn) return;
        endpointCoverageFilter = btn.dataset.coverage || 'ALL';
        setActiveFilterButton(coverageFilters, 'coverage', endpointCoverageFilter);
        renderEndpointList();
        syncUrlState();
      });
    }

    const methodFilters = document.getElementById('endpointMethodFilters');
    if (methodFilters) {
      methodFilters.addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-method]');
        if (!btn) return;
        endpointMethodFilter = btn.dataset.method || 'ALL';
        setActiveFilterButton(methodFilters, 'method', endpointMethodFilter);
        renderEndpointList();
        syncUrlState();
      });
    }
  }

  function byC4Level(d) {
    if (d.c4Level != null) return Number(d.c4Level);
    // Supporting diagrams / mermaid often don't have a level
    return 0;
  }

  /** Sort key from site-index: level, then c4Order (canonical tree), then label. */
  function compareDiagrams(a, b) {
    const la = byC4Level(a);
    const lb = byC4Level(b);
    if (la !== lb && la !== 0 && lb !== 0) return la - lb;
    const oa = a.c4Order != null && a.c4Order !== '' ? Number(a.c4Order) : 1000;
    const ob = b.c4Order != null && b.c4Order !== '' ? Number(b.c4Order) : 1000;
    if (oa !== ob) return oa - ob;
    return String(a.navLabel || a.title || a.id || '').localeCompare(String(b.navLabel || b.title || b.id || ''));
  }

  function renderDiagramLists() {
    const diagrams = (index && index.diagrams) ? index.diagrams : [];
    const lists = {
      1: document.getElementById('diagramListL1'),
      2: document.getElementById('diagramListL2'),
      3: document.getElementById('diagramListL3'),
      4: document.getElementById('diagramListL4'),
      other: document.getElementById('diagramListOther')
    };
    const levelHeads = { 1: 'c4Level1', 2: 'c4Level2', 3: 'c4Level3', 4: 'c4Level4', other: 'c4Other' };

    Object.values(lists).forEach(list => {
      if (list) list.innerHTML = '';
    });

    diagrams
      .sort(compareDiagrams)
      .forEach(d => {
        const lvl = byC4Level(d);
        const list = (lvl >= 1 && lvl <= 4) ? lists[lvl] : lists.other;
        if (!list) return;
        const li = document.createElement('li');
        li.className = 'diagram-item';
        const a = document.createElement('a');
        a.href = '#';
        a.textContent = d.navLabel || d.title || d.id || 'Diagram';
        a.addEventListener('click', (e) => { e.preventDefault(); void selectDiagram(d); });
        a.dataset.diagramId = String(d.id || '');
        li.appendChild(a);
        list.appendChild(li);
      });

    // Always show C4 L1–L4 in the sidebar; use a placeholder when nothing was generated for that level.
    [1, 2, 3, 4].forEach(lvl => {
      const list = lists[lvl];
      if (list && list.children.length === 0) {
        const li = document.createElement('li');
        li.className = 'diagram-item diagram-item-empty';
        const span = document.createElement('span');
        span.textContent = 'No diagrams at this level.';
        li.appendChild(span);
        list.appendChild(li);
      }
    });

    [1, 2, 3, 4, 'other'].forEach(lvl => {
      const container = document.getElementById(levelHeads[lvl]);
      if (container) container.classList.remove('hidden');
    });
  }

  function renderEndpointList() {
    const endpointList = document.getElementById('endpointList');
    const endpointNav = document.getElementById('endpointNav');
    if (!endpointList || !endpointNav) return;

    endpointList.innerHTML = '';
    const endpoints = (index && index.endpoints) ? index.endpoints : [];
    const coverageFilters = document.getElementById('endpointCoverageFilters');
    if (coverageFilters) setActiveFilterButton(coverageFilters, 'coverage', endpointCoverageFilter);
    renderEndpointMethodFilters(endpoints);

    endpoints
      .slice()
      .filter(ep => endpointMatchesFilters(ep))
      .sort((a, b) => {
        const ka = `${a.path || ''} ${a.httpMethod || ''}`.toLowerCase();
        const kb = `${b.path || ''} ${b.httpMethod || ''}`.toLowerCase();
        return ka.localeCompare(kb);
      })
      .forEach(ep => {
        const li = document.createElement('li');
        li.className = 'diagram-item endpoint-item';
        const a = document.createElement('a');
        a.href = '#';
        const label = `${ep.httpMethod || 'REQUEST'} ${ep.path || '/'}`;
        const coverage = endpointCoverage(ep);
        const hasData = endpointHasDataLineage(ep);
        const dataBadge = hasData ? ` <span class="endpoint-badge endpoint-badge-data" title="Entity lineage">DATA</span>` : '';
        a.innerHTML = `${escapeHtml(label)} <span class="endpoint-badge ${coverage === 'SEQ' ? 'endpoint-badge-seq' : 'endpoint-badge-flow'}">${coverage}</span>${dataBadge}`;
        a.title = `${label}\n${ep.controllerClass || ''}#${ep.controllerMethod || ''}`;
        a.addEventListener('click', (e) => {
          e.preventDefault();
          const targetBadge = e.target && e.target.closest ? e.target.closest('.endpoint-badge-data') : null;
          if (targetBadge) {
            openEndpointDataLineage(ep);
            return;
          }
          openEndpointSequence(ep);
        });
        li.appendChild(a);
        endpointList.appendChild(li);
      });

    endpointNav.classList.toggle('hidden', endpointList.children.length === 0);
  }

  function endpointMatchesFilters(endpoint) {
    const coverage = endpointCoverage(endpoint);
    const method = String(endpoint && endpoint.httpMethod ? endpoint.httpMethod : 'REQUEST').toUpperCase();
    const coverageOk = endpointCoverageFilter === 'ALL' || coverage === endpointCoverageFilter;
    const methodOk = endpointMethodFilter === 'ALL' || method === endpointMethodFilter;
    return coverageOk && methodOk;
  }

  function renderEndpointMethodFilters(endpoints) {
    const methodFilters = document.getElementById('endpointMethodFilters');
    if (!methodFilters) return;
    const methods = Array.from(new Set((endpoints || [])
      .map(ep => String(ep.httpMethod || 'REQUEST').toUpperCase())
      .filter(Boolean)))
      .sort();
    const allMethods = ['ALL'].concat(methods);
    if (!allMethods.includes(endpointMethodFilter)) {
      endpointMethodFilter = 'ALL';
    }
    methodFilters.innerHTML = '';
    allMethods.forEach(method => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'endpoint-filter-btn' + (method === endpointMethodFilter ? ' active' : '');
      btn.dataset.method = method;
      btn.textContent = method;
      methodFilters.appendChild(btn);
    });
  }

  function setActiveFilterButton(container, kind, value) {
    if (!container) return;
    const selector = kind === 'coverage' ? 'button[data-coverage]' : 'button[data-method]';
    container.querySelectorAll(selector).forEach(btn => {
      const btnValue = kind === 'coverage' ? btn.dataset.coverage : btn.dataset.method;
      btn.classList.toggle('active', String(btnValue) === String(value));
    });
  }

  function endpointSequenceDiagramId(endpoint) {
    if (!endpoint) return null;
    const raw = `${endpoint.httpMethod || 'REQUEST'}_${endpoint.path || '/'}_${endpoint.controllerMethod || ''}`;
    const slug = String(raw).replace(/[^a-zA-Z0-9_]/g, '_');
    return `endpoint-sequence-${slug}`;
  }

  function endpointCoverage(endpoint) {
    if (!index || !endpoint) return 'FLOW';
    let hasSequence = false;
    if (index.endpointSequences && index.endpointSequences.length) {
      hasSequence = index.endpointSequences.some(es =>
        String(es.httpMethod || '') === String(endpoint.httpMethod || '') &&
        String(es.path || '') === String(endpoint.path || '') &&
        String(es.controllerMethod || '') === String(endpoint.controllerMethod || '')
      );
    } else if (index.diagrams && index.diagrams.length) {
      const seqId = endpointSequenceDiagramId(endpoint);
      hasSequence = index.diagrams.some(d => String(d.id || '') === seqId);
    }
    return hasSequence ? 'SEQ' : 'FLOW';
  }

  function endpointDataLineageDiagramId(endpoint) {
    if (!endpoint) return null;
    const raw = `${endpoint.httpMethod || 'REQUEST'}_${endpoint.path || '/'}_${endpoint.controllerMethod || ''}`;
    const slug = String(raw).replace(/[^a-zA-Z0-9_]/g, '_');
    return `endpoint-data-lineage-${slug}`;
  }

  function endpointHasDataLineage(endpoint) {
    if (!index || !endpoint) return false;
    const id = endpointDataLineageDiagramId(endpoint);
    if (!id) return false;
    return index.diagrams && index.diagrams.some(d => String(d.id || '') === String(id));
  }

  function openEndpointDataLineage(endpoint) {
    if (!index || !index.diagrams || !index.diagrams.length) return;
    const id = endpointDataLineageDiagramId(endpoint);
    if (!id) return;
    const direct = index.diagrams.find(d => String(d.id || '') === String(id));
    if (direct) void selectDiagram(direct);
  }

  function openEndpointSequence(endpoint) {
    if (!index || !index.diagrams || !index.diagrams.length) return;
    let seqId = endpointSequenceDiagramId(endpoint);
    if (index.endpointSequences && index.endpointSequences.length) {
      const match = index.endpointSequences.find(es =>
        String(es.httpMethod || '') === String(endpoint.httpMethod || '') &&
        String(es.path || '') === String(endpoint.path || '') &&
        String(es.controllerMethod || '') === String(endpoint.controllerMethod || '')
      );
      if (match && match.plantumlPath) {
        const fileName = String(match.plantumlPath).split('/').pop();
        seqId = fileName && fileName.endsWith('.puml') ? fileName.substring(0, fileName.length - '.puml'.length) : seqId;
      }
    }
    const direct = index.diagrams.find(d => String(d.id || '') === seqId);
    if (direct) {
      void selectDiagram(direct);
      return;
    }

    const fallback = index.diagrams.find(d => String(d.id || '').startsWith('endpoint-sequence-'));
    if (fallback) {
      void selectDiagram(fallback);
      return;
    }
    const endpointFlow = index.diagrams.find(d => String(d.id || '') === 'endpoint-flow');
    if (endpointFlow) {
      void selectDiagram(endpointFlow);
    }
  }

  function firstSelectableDiagram() {
    if (!index || !index.diagrams.length) return null;
    const l1 = index.diagrams.find(d => byC4Level(d) === 1);
    if (l1) return l1;
    return index.diagrams[0];
  }

  async function selectFirstDiagram() {
    const d = firstSelectableDiagram();
    const titleEl = document.getElementById('diagramViewerTitle');
    const viewerEl = document.getElementById('diagramViewer');
    if (d) {
      await selectDiagram(d);
    } else {
      hideDiagramProvenance();
      if (titleEl) titleEl.textContent = 'No diagrams';
      const msg = (index && index.diagrams && index.diagrams.length === 0)
        ? 'No diagrams in this report. Ensure the report was generated with diagram output (PlantUML and Mermaid).'
        : 'Report data could not be loaded. If you opened this page from the file system (file://), serve it over HTTP (e.g. run a local server or use GitHub Pages).';
      if (viewerEl) viewerEl.innerHTML = '<p class="no-diagram">' + escapeHtml(msg) + '</p>';
    }
  }

  async function selectInitialDiagram() {
    if (initialDiagramId && index && index.diagrams && index.diagrams.length) {
      const initial = index.diagrams.find(d => String(d.id || '') === String(initialDiagramId));
      if (initial) {
        await selectDiagram(initial);
        return;
      }
    }
    await selectFirstDiagram();
  }

  async function ensureDiagramSource(d) {
    if (!d || d.source) return d;
    if (!d.sourcePath) return d;
    try {
      const res = await fetch(d.sourcePath);
      if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
      d.source = await res.text();
    } catch (err) {
      console.error('Failed to load diagram source from ' + d.sourcePath, err);
    }
    return d;
  }

  async function selectDiagram(d) {
    selectedDiagram = d;
    showingSource = false;
    document.querySelectorAll('.diagram-item a').forEach(a => a.classList.remove('selected'));
    const titleEl = document.getElementById('diagramViewerTitle');
    const viewerEl = document.getElementById('diagramViewer');
    const sourceBlock = document.getElementById('diagramSourceBlock');
    const sourceText = document.getElementById('diagramSourceText');
    const viewSourceBtn = document.getElementById('viewSourceBtn');
    if (!titleEl || !viewerEl) return;

    titleEl.textContent = d.navLabel || d.title;
    updateDiagramProvenance(d);
    const diagramId = (d.id != null) ? String(d.id) : '';
    const activeLink = diagramId
      ? Array.from(document.querySelectorAll('.diagram-item a')).find(a => a.dataset.diagramId === diagramId)
      : null;
    if (activeLink) activeLink.classList.add('selected');
    if (sourceBlock) sourceBlock.classList.add('hidden');

    if (d.sourcePath && !d.source) {
      viewerEl.innerHTML = '<p class="no-diagram">Loading diagram…</p>';
    } else {
      viewerEl.innerHTML = '';
    }

    await ensureDiagramSource(d);

    let renderSource = d.source;
    if (d.source && d.format === 'plantuml') {
      renderSource = effectivePlantUmlSource(d);
    }

    if (d.source) {
      if (viewSourceBtn) viewSourceBtn.classList.remove('hidden');
      if (sourceText) sourceText.textContent = renderSource;
    } else {
      if (viewSourceBtn) viewSourceBtn.classList.add('hidden');
    }

    if (!d.source) {
      viewerEl.textContent = 'Diagram source not available.';
      syncUrlState();
      return;
    }

    viewerEl.innerHTML = '';
    if (d.format === 'mermaid') {
      await renderMermaid(viewerEl, d.source);
      const searchInput = document.getElementById('searchInput');
      if (searchInput && searchInput.value) highlightInDiagram(searchInput.value.trim());
    } else if (d.format === 'plantuml') {
      await renderPlantUmlKroki(viewerEl, renderSource);
      const searchInput = document.getElementById('searchInput');
      if (searchInput && searchInput.value) highlightInDiagram(searchInput.value.trim());
    } else {
      viewerEl.textContent = 'Unknown diagram format.';
    }
    syncUrlState();
  }

  async function toggleSource() {
    if (!selectedDiagram) return;
    await ensureDiagramSource(selectedDiagram);
    if (!selectedDiagram.source) return;
    showingSource = !showingSource;
    const sourceBlock = document.getElementById('diagramSourceBlock');
    const viewSourceBtn = document.getElementById('viewSourceBtn');
    const viewerContainer = document.getElementById('diagramViewerContainer');
    if (showingSource) {
      sourceBlock.classList.remove('hidden');
      viewSourceBtn.textContent = 'Hide source';
      viewerContainer.classList.add('hidden');
    } else {
      sourceBlock.classList.add('hidden');
      viewSourceBtn.textContent = 'View source';
      viewerContainer.classList.remove('hidden');
    }
  }

  function initPanZoom(svgElement) {
    if (panZoomInstance) {
      panZoomInstance.destroy();
      panZoomInstance = null;
    }
    if (typeof svgPanZoom === 'undefined') return;

    svgElement.style.width = '100%';
    svgElement.style.height = '100%';

    panZoomInstance = svgPanZoom(svgElement, {
      zoomEnabled: true,
      controlIconsEnabled: false,
      fit: true,
      center: true,
      minZoom: 0.1,
      maxZoom: 10
    });

    addSvgInteractivity(svgElement);
  }

  function addSvgInteractivity(svgElement) {
    if (!index || !index.diagrams) return;

    const groups = svgElement.querySelectorAll('g');
    groups.forEach(group => {
      const textEls = Array.from(group.querySelectorAll('text'));
      if (textEls.length === 0) return;

      const label = textEls.map(t => t.textContent.trim()).join(' ');

      const target = index.diagrams.find(d => {
        const navLabel = (d.navLabel || '').toLowerCase();
        const id = (d.id || '').toLowerCase().replace('module-', '');
        const cleanLabel = label.toLowerCase();

        return (navLabel && cleanLabel === navLabel) ||
               (id && cleanLabel === id) ||
               (id && cleanLabel.includes(id));
      });

      if (target && target.id !== (selectedDiagram ? selectedDiagram.id : null)) {
        group.style.cursor = 'pointer';
        group.classList.add('interactive-node');
        group.addEventListener('click', (e) => {
          e.preventDefault();
          e.stopPropagation();
          void selectDiagram(target);
        });
      }
    });
  }

  async function exportCurrentDiagram(format) {
    if (!selectedDiagram) return;
    await ensureDiagramSource(selectedDiagram);
    const src =
      selectedDiagram.format === 'plantuml'
        ? effectivePlantUmlSource(selectedDiagram)
        : selectedDiagram.source;
    const encoded = encodeKroki(src);
    if (!encoded) return;
    const type = selectedDiagram.format === 'mermaid' ? 'mermaid' : 'plantuml';
    const url = `https://kroki.io/${type}/${format}/${encoded}`;
    const filename = `${selectedDiagram.id || 'diagram'}.${format}`;

    fetch(url)
      .then(res => res.blob())
      .then(blob => {
        if (typeof saveAs !== 'undefined') {
          saveAs(blob, filename);
        } else {
          const a = document.createElement('a');
          a.href = URL.createObjectURL(blob);
          a.download = filename;
          a.click();
        }
      })
      .catch(err => console.error('Export error', err));
  }

  async function exportAllAsZip() {
    if (!index || !index.diagrams || !index.diagrams.length) return;
    if (typeof JSZip === 'undefined') {
      alert('JSZip library not loaded');
      return;
    }

    const zip = new JSZip();
    const folder = zip.folder('diagrams');
    const btn = document.getElementById('exportAllZipBtn');
    const originalText = btn.textContent;
    btn.textContent = 'Generating...';
    btn.disabled = true;

    try {
      for (const d of index.diagrams) {
        await ensureDiagramSource(d);
        const src = d.format === 'plantuml' ? effectivePlantUmlSource(d) : d.source;
        const encoded = encodeKroki(src);
        if (!encoded) continue;
        const type = d.format === 'mermaid' ? 'mermaid' : 'plantuml';
        const url = `https://kroki.io/${type}/png/${encoded}`;
        const response = await fetch(url);
        const blob = await response.blob();
        folder.file(`${d.id || d.title}.png`, blob);
      }

      const content = await zip.generateAsync({ type: 'blob' });
      if (typeof saveAs !== 'undefined') {
        saveAs(content, 'all-diagrams.zip');
      } else {
        const a = document.createElement('a');
        a.href = URL.createObjectURL(content);
        a.download = 'all-diagrams.zip';
        a.click();
      }
    } catch (err) {
      console.error('ZIP export error', err);
      alert('Failed to generate ZIP');
    } finally {
      btn.textContent = originalText;
      btn.disabled = false;
    }
  }

  async function printAllDiagrams() {
    if (!index || !index.diagrams || !index.diagrams.length) return;
    const printArea = document.getElementById('printArea');
    const mainContainer = document.getElementById('mainContainer');
    if (!printArea || !mainContainer) return;

    printArea.innerHTML = '<h1>All Diagrams</h1>';
    printArea.classList.remove('hidden');
    mainContainer.classList.add('hidden');
    document.querySelector('header').classList.add('hidden');
    document.querySelector('footer').classList.add('hidden');

    for (const d of index.diagrams) {
      await ensureDiagramSource(d);
      const section = document.createElement('section');
      section.className = 'print-diagram-section';
      section.innerHTML = `<h2>${d.navLabel || d.title}</h2><div class="print-svg-container"></div>`;
      printArea.appendChild(section);
      const container = section.querySelector('.print-svg-container');

      if (!d.source) {
        container.innerHTML = '<p class="no-diagram">Source not available.</p>';
        continue;
      }
      if (d.format === 'mermaid') {
        const id = 'print-mermaid-' + Math.random().toString(36).substr(2, 9);
        try {
          const { svg } = await mermaid.render(id, d.source);
          container.innerHTML = svg;
        } catch (e) { console.error(e); }
      } else {
        const pumlSrc = d.format === 'plantuml' ? effectivePlantUmlSource(d) : d.source;
        const encoded = encodeKroki(pumlSrc);
        if (encoded) {
          try {
            const res = await fetch(`https://kroki.io/plantuml/svg/${encoded}`);
            const svg = await res.text();
            container.innerHTML = svg;
          } catch (e) { console.error(e); }
        }
      }
    }

    setTimeout(() => {
      window.print();
      printArea.classList.add('hidden');
      mainContainer.classList.remove('hidden');
      document.querySelector('header').classList.remove('hidden');
      document.querySelector('footer').classList.remove('hidden');
    }, 1000);
  }

  function renderMermaid(container, source) {
    if (typeof mermaid === 'undefined') {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre>';
      return Promise.resolve();
    }
    const id = 'mermaid-' + Math.random().toString(36).substr(2, 9);
    return mermaid.render(id, source).then(({ svg }) => {
      container.innerHTML = svg;
      const svgElement = container.querySelector('svg');
      if (svgElement) initPanZoom(svgElement);
    }).catch((err) => {
      console.error('Mermaid render error', err);
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre>';
    });
  }

  async function renderPlantUmlKroki(container, source) {
    const encoded = encodeKroki(source);
    if (!encoded) {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre><p class="diagram-fallback">Kroki encoding not available. Showing source.</p>';
      return;
    }
    const url = 'https://kroki.io/plantuml/svg/' + encoded;
    try {
      const response = await fetch(url);
      if (!response.ok) throw new Error('Kroki request failed');
      const svgText = await response.text();
      // Ensure we only inject the SVG part if there's any junk
      const svgStart = svgText.indexOf('<svg');
      if (svgStart !== -1) {
        container.innerHTML = svgText.substring(svgStart);
      } else {
        container.innerHTML = svgText;
      }
      const svgElement = container.querySelector('svg');
      if (svgElement) initPanZoom(svgElement);
    } catch (err) {
      console.error('PlantUML render error', err);
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre><p class="diagram-fallback">Could not load diagram image. Showing source.</p>';
    }
  }

  function highlightInDiagram(term) {
    const viewerEl = document.getElementById('diagramViewer');
    if (!viewerEl) return;
    const svg = viewerEl.querySelector('svg');
    if (!svg) return;

    svg.querySelectorAll('.highlight').forEach(el => el.classList.remove('highlight'));
    if (!term || term.length < 2) return;

    const q = term.toLowerCase();
    const texts = svg.querySelectorAll('text');
    texts.forEach(textEl => {
      if (textEl.textContent.toLowerCase().includes(q)) {
        textEl.classList.add('highlight');
        let parent = textEl.parentElement;
        if (parent && parent.tagName === 'g') {
          parent.classList.add('highlight');
        }
      }
    });
  }

  function escapeHtml(s) {
    const div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
  }

  function renderSearch(term) {
    highlightInDiagram(term);
    const searchPanel = document.getElementById('searchResultsPanel');
    const modulesEl = document.getElementById('modulesResults');
    const classesEl = document.getElementById('classesResults');
    const eventsEl = document.getElementById('eventsResults');
    const endpointsEl = document.getElementById('endpointsResults');
    const commandsEl = document.getElementById('commandsResults');
    const architectureEl = document.getElementById('architectureResults');
    const messagingEl = document.getElementById('messagingResults');
    const bpmnEl = document.getElementById('bpmnResults');
    const openApiEl = document.getElementById('openApiResults');
    const externalHttpEl = document.getElementById('externalHttpResults');
    const deploymentInfraEl = document.getElementById('deploymentInfraResults');
    const deploymentExternalEl = document.getElementById('deploymentExternalResults');
    const summaryEl = document.getElementById('resultsSummary');

    if (!modulesEl || !classesEl || !eventsEl || !endpointsEl || !commandsEl || !architectureEl || !messagingEl || !bpmnEl || !openApiEl || !externalHttpEl || !deploymentInfraEl || !deploymentExternalEl || !summaryEl || !searchPanel) return;

    if (!term || term.length < 2) {
      searchPanel.classList.remove('active');
      return;
    }
    searchPanel.classList.add('active');

    modulesEl.innerHTML = '';
    classesEl.innerHTML = '';
    eventsEl.innerHTML = '';
    endpointsEl.innerHTML = '';
    commandsEl.innerHTML = '';
    architectureEl.innerHTML = '';
    messagingEl.innerHTML = '';
    bpmnEl.innerHTML = '';
    openApiEl.innerHTML = '';
    externalHttpEl.innerHTML = '';
    deploymentInfraEl.innerHTML = '';
    deploymentExternalEl.innerHTML = '';

    if (!index) return;
    const q = term.toLowerCase();
    const match = (s) => (s && s.toLowerCase().includes(q));
    const modules = index.modules.filter(m => match(m.name) || match(m.basePackage));
    const classes = index.classes.filter(c => match(c.className) || match(c.module));
    const events = index.events.filter(e =>
      match(e.eventType) || match(e.publisherModule) || (e.listenerModules || []).some(l => match(l)));
    const endpoints = (index.endpoints || []).filter(e =>
      (match(e.httpMethod) || match(e.path) || match(e.controllerClass) || match(e.controllerMethod)) && endpointMatchesFilters(e));
    const commands = (index.commands || []).filter(c => match(c.commandType) || match(c.targetModule));
    const architecture = (index.architecture || []).filter(a => match(a.className) || match(a.layer) || match(a.type));
    const messaging = (index.messaging || []).filter(m => match(m.technology) || match(m.destination) || match(m.publisher) || (m.subscribers || []).some(s => match(s)));
    const bpmn = (index.bpmn || []).filter(b => match(b.processId) || match(b.stepName) || match(b.delegate));
    const openApiSpecs = (index.openApiSpecs || []).filter(s => match(s.relativePath) || match(s.specKind));
    const extHttp = (index.externalHttpClients || []).filter(h =>
      match(h.clientKind) || match(h.declaringClass) || match(h.detail));

    const depFiles = (index.deploymentFiles || []).filter(f => match(f.relativePath) || match(f.kind));
    const depContainers = (index.deploymentContainers || []).filter(c =>
      match(c.name) || match(c.image) || match(c.sourcePath) || match(c.context) || (c.ports || []).some(p => match(String(p))));
    const depK8sSvc = (index.deploymentK8sServices || []).filter(s =>
      match(s.name) || match(s.namespace) || match(s.type) || match(s.sourcePath) || (s.ports || []).some(p => match(String(p))));
    const depIng = (index.deploymentIngresses || []).filter(i =>
      match(i.name) || match(i.namespace) || match(i.ingressClassName) || match(i.sourcePath)
      || (i.hosts || []).some(h => match(h)) || (i.pathHints || []).some(p => match(p)));
    const depExt = (index.deploymentExternalSystems || []).filter(x =>
      match(x.category) || match(x.label) || match(x.evidence) || match(x.sourcePath));

    summaryEl.textContent = `Found ${modules.length} modules, ${classes.length} classes, ${events.length} events, ${endpoints.length} endpoints, ${commands.length} commands, ${architecture.length} arch, ${messaging.length} msg, ${bpmn.length} bpmn, ${openApiSpecs.length} OpenAPI, ${extHttp.length} HTTP clients, ${depFiles.length + depContainers.length + depK8sSvc.length + depIng.length} infra hits, ${depExt.length} external systems for "${term}"`;

    modules.forEach(m => {
      const li = document.createElement('li');
      li.textContent = `${m.name} (${m.basePackage})`;
      modulesEl.appendChild(li);
    });
    classes.forEach(c => {
      const li = document.createElement('li');
      li.textContent = `${c.className} [${c.kind}] in ${c.module}`;
      classesEl.appendChild(li);
    });
    events.forEach(e => {
      const li = document.createElement('li');
      const listeners = (e.listenerModules || []).join(', ') || '—';
      li.textContent = `${e.eventType} — pub: ${e.publisherModule}, subs: ${listeners}`;
      eventsEl.appendChild(li);
    });
    endpoints.forEach(e => {
      const li = document.createElement('li');
      li.className = 'endpoint-search-item';
      const label = `${e.httpMethod || 'REQUEST'} ${e.path || '/'} — ${shortClassName(e.controllerClass)}#${e.controllerMethod || ''}`;
      const coverage = endpointCoverage(e);
      const hasData = endpointHasDataLineage(e);
      const dataBadge = hasData ? ` <span class="endpoint-badge endpoint-badge-data" title="Entity lineage">DATA</span>` : '';
      li.innerHTML = `${escapeHtml(label)} <span class="endpoint-badge ${coverage === 'SEQ' ? 'endpoint-badge-seq' : 'endpoint-badge-flow'}">${coverage}</span>${dataBadge}`;
      li.onclick = (evt) => {
        const targetBadge = evt && evt.target && evt.target.closest ? evt.target.closest('.endpoint-badge-data') : null;
        if (targetBadge) {
          openEndpointDataLineage(e);
          return;
        }
        openEndpointSequence(e);
      };
      endpointsEl.appendChild(li);
    });
    commands.forEach(c => {
      const li = document.createElement('li');
      li.textContent = `${c.commandType} → ${c.targetModule}`;
      commandsEl.appendChild(li);
    });
    architecture.forEach(a => {
      const li = document.createElement('li');
      li.textContent = `${a.className} — ${a.layer} [${a.type}]`;
      architectureEl.appendChild(li);
    });
    messaging.forEach(m => {
      const li = document.createElement('li');
      li.textContent = `[${m.technology}] ${m.destination} — pub: ${m.publisher}, subs: ${(m.subscribers || []).join(', ')}`;
      messagingEl.appendChild(li);
    });
    bpmn.forEach(b => {
      const li = document.createElement('li');
      li.textContent = `${b.processId} : ${b.stepName} (delegate: ${b.delegate})`;
      bpmnEl.appendChild(li);
    });
    openApiSpecs.forEach(s => {
      const li = document.createElement('li');
      li.textContent = `[${s.specKind}] ${s.relativePath}`;
      openApiEl.appendChild(li);
    });
    extHttp.forEach(h => {
      const li = document.createElement('li');
      li.textContent = `[${h.clientKind}] ${h.declaringClass} — ${h.detail}`;
      externalHttpEl.appendChild(li);
    });

    depFiles.forEach(f => {
      const li = document.createElement('li');
      li.textContent = `[${f.kind}] ${f.relativePath}`;
      deploymentInfraEl.appendChild(li);
    });
    depContainers.forEach(c => {
      const li = document.createElement('li');
      const ports = (c.ports || []).join(', ');
      li.textContent = `${c.name}: ${c.image} (${c.context})${ports ? ' ports: ' + ports : ''}`;
      deploymentInfraEl.appendChild(li);
    });
    depK8sSvc.forEach(s => {
      const li = document.createElement('li');
      li.textContent = `Service ${s.name}${s.namespace ? ' ns=' + s.namespace : ''} [${s.type}] — ${(s.ports || []).join(', ')}`;
      deploymentInfraEl.appendChild(li);
    });
    depIng.forEach(i => {
      const li = document.createElement('li');
      li.textContent = `Ingress ${i.name} class=${i.ingressClassName || '—'} hosts=${(i.hosts || []).join(', ')}`;
      deploymentInfraEl.appendChild(li);
    });

    depExt.forEach(x => {
      const li = document.createElement('li');
      li.textContent = `[${x.category}] ${x.label} — ${x.evidence}`;
      deploymentExternalEl.appendChild(li);
    });
  }

  function applyStateFromUrl() {
    try {
      const params = new URLSearchParams(window.location.search || '');
      const coverage = String(params.get('epCoverage') || 'ALL').toUpperCase();
      const method = String(params.get('epMethod') || 'ALL').toUpperCase();
      const q = params.get('q');
      const diagramId = params.get('diagram');

      if (coverage === 'ALL' || coverage === 'SEQ' || coverage === 'FLOW') {
        endpointCoverageFilter = coverage;
      }
      endpointMethodFilter = method || 'ALL';
      initialSearchTerm = q || '';
      initialDiagramId = diagramId || null;
    } catch (e) {
      console.warn('Could not read URL state', e);
    }
  }

  function syncUrlState() {
    try {
      const params = new URLSearchParams(window.location.search || '');
      const searchInput = document.getElementById('searchInput');
      const q = searchInput ? searchInput.value.trim() : '';
      if (q) params.set('q', q); else params.delete('q');

      if (selectedDiagram && selectedDiagram.id != null) params.set('diagram', String(selectedDiagram.id));
      else params.delete('diagram');

      if (endpointCoverageFilter && endpointCoverageFilter !== 'ALL') params.set('epCoverage', endpointCoverageFilter);
      else params.delete('epCoverage');

      if (endpointMethodFilter && endpointMethodFilter !== 'ALL') params.set('epMethod', endpointMethodFilter);
      else params.delete('epMethod');

      const newQuery = params.toString();
      const newUrl = newQuery ? `${window.location.pathname}?${newQuery}${window.location.hash || ''}` : `${window.location.pathname}${window.location.hash || ''}`;
      window.history.replaceState({}, '', newUrl);
    } catch (e) {
      console.warn('Could not sync URL state', e);
    }
  }

  function handleGlobalShortcuts(e) {
    const searchInput = document.getElementById('searchInput');
    if (!searchInput) return;
    const target = e.target;
    const tagName = target && target.tagName ? target.tagName.toLowerCase() : '';
    const isTypingContext = tagName === 'input' || tagName === 'textarea' || (target && target.isContentEditable);

    if (e.key === '/' && !e.metaKey && !e.ctrlKey && !e.altKey && !isTypingContext) {
      e.preventDefault();
      searchInput.focus();
      searchInput.select();
      return;
    }

    if (e.key === 'Escape') {
      if (document.activeElement === searchInput || searchInput.value.trim().length > 0) {
        searchInput.value = '';
        renderSearch('');
        syncUrlState();
        if (document.activeElement === searchInput) {
          searchInput.blur();
        }
      }
      return;
    }

    if (!isTypingContext) {
      const key = String(e.key || '').toLowerCase();
      if (e.key === 'ArrowRight' || key === 'j') {
        e.preventDefault();
        navigateDiagram(1);
        return;
      }
      if (e.key === 'ArrowLeft' || key === 'k') {
        e.preventDefault();
        navigateDiagram(-1);
      }
    }
  }

  function sortedDiagrams() {
    if (!index || !index.diagrams) return [];
    return index.diagrams.slice().sort(compareDiagrams);
  }

  function navigateDiagram(step) {
    const diagrams = sortedDiagrams();
    if (!diagrams.length) return;
    if (!selectedDiagram || selectedDiagram.id == null) {
      void selectDiagram(diagrams[0]);
      return;
    }
    const currentId = String(selectedDiagram.id);
    const idx = diagrams.findIndex(d => String(d.id || '') === currentId);
    if (idx === -1) {
      void selectDiagram(diagrams[0]);
      return;
    }
    const nextIdx = (idx + step + diagrams.length) % diagrams.length;
    void selectDiagram(diagrams[nextIdx]);
  }

  async function copyDeepLink(buttonEl) {
    const url = window.location.href;
    let copied = false;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      try {
        await navigator.clipboard.writeText(url);
        copied = true;
      } catch (e) {
        copied = false;
      }
    }
    if (!copied) {
      copied = legacyCopy(url);
    }
    if (buttonEl) {
      const initial = buttonEl.textContent;
      buttonEl.textContent = copied ? 'Copied' : 'Copy failed';
      buttonEl.classList.toggle('copied', copied);
      setTimeout(() => {
        buttonEl.textContent = initial;
        buttonEl.classList.remove('copied');
      }, 1100);
    }
  }

  function legacyCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    let ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (e) {
      ok = false;
    }
    document.body.removeChild(ta);
    return ok;
  }

  if (document.readyState === 'loading') {
    window.addEventListener('DOMContentLoaded', loadIndex);
  } else {
    loadIndex();
  }
})();
