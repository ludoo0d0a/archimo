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

  function showError(msg) {
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
        index = { diagrams: [], modules: [], classes: [], events: [], endpoints: [], endpointSequences: [], commands: [], openApiSpecs: [], externalHttpClients: [], deploymentFiles: [], deploymentContainers: [], deploymentK8sServices: [], deploymentIngresses: [], deploymentExternalSystems: [] };
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
    return d.level === 'mermaid' ? 0 : 3;
  }

  /** Sort key from site-index: level, then c4Order (canonical tree), then label. */
  function compareDiagrams(a, b) {
    const la = byC4Level(a);
    const lb = byC4Level(b);
    if (la !== lb) return la - lb;
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
      0: document.getElementById('diagramListMermaid')
    };
    const levelHeads = { 1: 'c4Level1', 2: 'c4Level2', 3: 'c4Level3', 4: 'c4Level4', 0: 'c4Mermaid' };
    [1, 2, 3, 4, 0].forEach(lvl => {
      if (lists[lvl]) lists[lvl].innerHTML = '';
    });
    diagrams
      .sort(compareDiagrams)
      .forEach(d => {
        const lvl = byC4Level(d);
        const list = lists[lvl];
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
    [1, 2, 3, 4].forEach(lvl => {
      const container = document.getElementById(levelHeads[lvl]);
      if (container) container.classList.remove('hidden');
    });
    const mermaidSection = document.getElementById(levelHeads[0]);
    if (mermaidSection) {
      mermaidSection.classList.toggle('hidden', !lists[0] || lists[0].children.length === 0);
    }
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

    if (d.source) {
      if (viewSourceBtn) viewSourceBtn.classList.remove('hidden');
      if (sourceText) sourceText.textContent = d.source;
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
      await renderPlantUmlKroki(viewerEl, d.source);
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
    const encoded = encodeKroki(selectedDiagram.source);
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
        const encoded = encodeKroki(d.source);
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
        const encoded = encodeKroki(d.source);
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
