(() => {
  let index = null;
  let selectedDiagram = null;
  let showingSource = false;
  let panZoomInstance = null;

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
        index = { diagrams: [], modules: [], classes: [], events: [], commands: [] };
      }
      if (!index.diagrams) index.diagrams = [];
      if (!index.modules) index.modules = [];
      if (!index.classes) index.classes = [];
      if (!index.events) index.events = [];
      if (!index.commands) index.commands = [];
      if (typeof mermaid !== 'undefined') {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: 'loose',
          theme: document.body.classList.contains('dark-mode') ? 'dark' : 'default'
        });
      }
      bindUI();
      renderDiagramLists();
      renderSearch('');
      selectFirstDiagram();
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
        selectDiagram(selectedDiagram);
      }
    }
  }

  function bindUI() {
    const searchInput = document.getElementById('searchInput');
    const viewSourceBtn = document.getElementById('viewSourceBtn');
    const themeToggle = document.getElementById('themeToggle');
    const fitBtn = document.getElementById('fitBtn');

    if (searchInput) searchInput.addEventListener('input', (e) => { renderSearch(e.target.value.trim()); });
    if (viewSourceBtn) viewSourceBtn.addEventListener('click', toggleSource);
    if (themeToggle) themeToggle.addEventListener('click', toggleTheme);
    if (fitBtn) fitBtn.addEventListener('click', () => {
      if (panZoomInstance) {
        panZoomInstance.fit();
        panZoomInstance.center();
      }
    });

    const exportPngBtn = document.getElementById('exportPngBtn');
    const exportPdfBtn = document.getElementById('exportPdfBtn');
    const exportAllZipBtn = document.getElementById('exportAllZipBtn');
    const printAllBtn = document.getElementById('printAllBtn');

    if (exportPngBtn) exportPngBtn.onclick = () => exportCurrentDiagram('png');
    if (exportPdfBtn) exportPdfBtn.onclick = () => exportCurrentDiagram('pdf');
    if (exportAllZipBtn) exportAllZipBtn.onclick = () => exportAllAsZip();
    if (printAllBtn) printAllBtn.onclick = () => printAllDiagrams();
  }

  function byC4Level(d) {
    if (d.c4Level != null) return Number(d.c4Level);
    return d.level === 'mermaid' ? 0 : 3;
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
      .sort((a, b) => {
        const la = byC4Level(a);
        const lb = byC4Level(b);
        if (la !== lb) return la - lb;
        return String(a.navLabel || a.title || '').localeCompare(String(b.navLabel || b.title || ''));
      })
      .forEach(d => {
        const lvl = byC4Level(d);
        const list = lists[lvl];
        if (!list) return;
        const li = document.createElement('li');
        li.className = 'diagram-item';
        const a = document.createElement('a');
        a.href = '#';
        a.textContent = d.navLabel || d.title || d.id || 'Diagram';
        a.addEventListener('click', (e) => { e.preventDefault(); selectDiagram(d); });
        a.dataset.diagramId = String(d.id || '');
        li.appendChild(a);
        list.appendChild(li);
      });
    [1, 2, 3, 4, 0].forEach(lvl => {
      const container = document.getElementById(levelHeads[lvl]);
      if (container) container.classList.toggle('hidden', !lists[lvl] || lists[lvl].children.length === 0);
    });
  }

  function firstSelectableDiagram() {
    if (!index || !index.diagrams.length) return null;
    const l1 = index.diagrams.find(d => byC4Level(d) === 1);
    if (l1) return l1;
    return index.diagrams[0];
  }

  function selectFirstDiagram() {
    const d = firstSelectableDiagram();
    const titleEl = document.getElementById('diagramViewerTitle');
    const viewerEl = document.getElementById('diagramViewer');
    if (d) {
      selectDiagram(d);
    } else {
      if (titleEl) titleEl.textContent = 'No diagrams';
      const msg = (index && index.diagrams && index.diagrams.length === 0)
        ? 'No diagrams in this report. Ensure the report was generated with diagram output (PlantUML and Mermaid).'
        : 'Report data could not be loaded. If you opened this page from the file system (file://), serve it over HTTP (e.g. run a local server or use GitHub Pages).';
      if (viewerEl) viewerEl.innerHTML = '<p class="no-diagram">' + escapeHtml(msg) + '</p>';
    }
  }

  function selectDiagram(d) {
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
    if (d.source) {
      if (viewSourceBtn) viewSourceBtn.classList.remove('hidden');
      if (sourceText) sourceText.textContent = d.source;
    } else {
      if (viewSourceBtn) viewSourceBtn.classList.add('hidden');
    }

    viewerEl.innerHTML = '';
    if (!d.source) {
      viewerEl.textContent = 'Diagram source not available.';
      return;
    }

    if (d.format === 'mermaid') {
      renderMermaid(viewerEl, d.source).then(() => {
        const searchInput = document.getElementById('searchInput');
        if (searchInput && searchInput.value) highlightInDiagram(searchInput.value.trim());
      });
    } else if (d.format === 'plantuml') {
      renderPlantUmlKroki(viewerEl, d.source).then(() => {
        const searchInput = document.getElementById('searchInput');
        if (searchInput && searchInput.value) highlightInDiagram(searchInput.value.trim());
      });
    } else {
      viewerEl.textContent = 'Unknown diagram format.';
    }
  }

  function toggleSource() {
    if (!selectedDiagram || !selectedDiagram.source) return;
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

    const zoomInBtn = document.getElementById('zoomInBtn');
    const zoomOutBtn = document.getElementById('zoomOutBtn');
    const resetBtn = document.getElementById('resetBtn');

    if (zoomInBtn) zoomInBtn.onclick = () => panZoomInstance.zoomIn();
    if (zoomOutBtn) zoomOutBtn.onclick = () => panZoomInstance.zoomOut();
    if (resetBtn) resetBtn.onclick = () => {
      panZoomInstance.resetZoom();
      panZoomInstance.center();
    };
  }

  function exportCurrentDiagram(format) {
    if (!selectedDiagram) return;
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
      const section = document.createElement('section');
      section.className = 'print-diagram-section';
      section.innerHTML = `<h2>${d.navLabel || d.title}</h2><div class="print-svg-container"></div>`;
      printArea.appendChild(section);
      const container = section.querySelector('.print-svg-container');

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
      container.innerHTML = svgText;
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
    const commandsEl = document.getElementById('commandsResults');
    const architectureEl = document.getElementById('architectureResults');
    const messagingEl = document.getElementById('messagingResults');
    const bpmnEl = document.getElementById('bpmnResults');
    const summaryEl = document.getElementById('resultsSummary');

    if (!modulesEl || !classesEl || !eventsEl || !commandsEl || !architectureEl || !messagingEl || !bpmnEl || !summaryEl || !searchPanel) return;

    if (!term || term.length < 2) {
      searchPanel.classList.remove('active');
      return;
    }
    searchPanel.classList.add('active');

    modulesEl.innerHTML = '';
    classesEl.innerHTML = '';
    eventsEl.innerHTML = '';
    commandsEl.innerHTML = '';
    architectureEl.innerHTML = '';
    messagingEl.innerHTML = '';
    bpmnEl.innerHTML = '';

    if (!index) return;
    const q = term.toLowerCase();
    const match = (s) => (s && s.toLowerCase().includes(q));
    const modules = index.modules.filter(m => match(m.name) || match(m.basePackage));
    const classes = index.classes.filter(c => match(c.className) || match(c.module));
    const events = index.events.filter(e =>
      match(e.eventType) || match(e.publisherModule) || (e.listenerModules || []).some(l => match(l)));
    const commands = (index.commands || []).filter(c => match(c.commandType) || match(c.targetModule));
    const architecture = (index.architecture || []).filter(a => match(a.className) || match(a.layer) || match(a.type));
    const messaging = (index.messaging || []).filter(m => match(m.technology) || match(m.destination) || match(m.publisher) || (m.subscribers || []).some(s => match(s)));
    const bpmn = (index.bpmn || []).filter(b => match(b.processId) || match(b.stepName) || match(b.delegate));

    summaryEl.textContent = `Found ${modules.length} modules, ${classes.length} classes, ${events.length} events, ${commands.length} commands, ${architecture.length} arch, ${messaging.length} msg, ${bpmn.length} bpmn for "${term}"`;

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
  }

  if (document.readyState === 'loading') {
    window.addEventListener('DOMContentLoaded', loadIndex);
  } else {
    loadIndex();
  }
})();
