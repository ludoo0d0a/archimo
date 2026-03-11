(() => {
  let index = null;
  let selectedDiagram = null;
  let showingSource = false;

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

  async function loadIndex() {
    const res = await fetch('site-index.json');
    index = await res.json();
    if (typeof mermaid !== 'undefined') {
      mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });
    }
    bindUI();
    renderDiagramLists();
    renderSearch('');
    selectFirstDiagram();
  }

  function bindUI() {
    document.getElementById('searchInput').addEventListener('input', (e) => {
      renderSearch(e.target.value.trim());
    });
    document.getElementById('viewSourceBtn').addEventListener('click', toggleSource);
  }

  function byC4Level(d) {
    const l = d.c4Level != null ? d.c4Level : (d.level === 'mermaid' ? 0 : 3);
    return l;
  }

  function renderDiagramLists() {
    if (!index || !index.diagrams) return;
    const lists = {
      1: document.getElementById('diagramListL1'),
      2: document.getElementById('diagramListL2'),
      3: document.getElementById('diagramListL3'),
      4: document.getElementById('diagramListL4'),
      0: document.getElementById('diagramListMermaid')
    };
    [1, 2, 3, 4, 0].forEach(lvl => { lists[lvl].innerHTML = ''; });
    const levelHeads = { 1: 'c4Level1', 2: 'c4Level2', 3: 'c4Level3', 4: 'c4Level4', 0: 'c4Mermaid' };
    index.diagrams
      .sort((a, b) => {
        const la = byC4Level(a);
        const lb = byC4Level(b);
        if (la !== lb) return la - lb;
        return (a.navLabel || a.title).localeCompare(b.navLabel || b.title);
      })
      .forEach(d => {
        const lvl = byC4Level(d);
        const list = lists[lvl];
        if (!list) return;
        const li = document.createElement('li');
        li.className = 'diagram-item';
        const a = document.createElement('a');
        a.href = '#';
        a.textContent = d.navLabel || d.title;
        a.addEventListener('click', (e) => { e.preventDefault(); selectDiagram(d); });
        a.dataset.diagramId = d.id;
        li.appendChild(a);
        list.appendChild(li);
      });
    [1, 2, 3, 4, 0].forEach(lvl => {
      const container = document.getElementById(levelHeads[lvl]);
      if (container) container.classList.toggle('hidden', lists[lvl].children.length === 0);
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
    if (d) selectDiagram(d);
    else {
      document.getElementById('diagramViewerTitle').textContent = 'No diagrams';
      document.getElementById('diagramViewer').innerHTML = '<p class="no-diagram">No diagrams in this report.</p>';
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

    titleEl.textContent = d.navLabel || d.title;
    const activeLink = document.querySelector('.diagram-item a[data-diagram-id="' + d.id + '"]');
    if (activeLink) activeLink.classList.add('selected');
    sourceBlock.classList.add('hidden');
    if (d.source) {
      viewSourceBtn.classList.remove('hidden');
      sourceText.textContent = d.source;
    } else {
      viewSourceBtn.classList.add('hidden');
    }

    viewerEl.innerHTML = '';
    if (!d.source) {
      viewerEl.textContent = 'Diagram source not available.';
      return;
    }

    if (d.format === 'mermaid') {
      renderMermaid(viewerEl, d.source);
    } else if (d.format === 'plantuml') {
      renderPlantUmlKroki(viewerEl, d.source);
    } else {
      viewerEl.textContent = 'Unknown diagram format.';
    }
  }

  function toggleSource() {
    if (!selectedDiagram || !selectedDiagram.source) return;
    showingSource = !showingSource;
    const sourceBlock = document.getElementById('diagramSourceBlock');
    const viewSourceBtn = document.getElementById('viewSourceBtn');
    const viewerEl = document.getElementById('diagramViewer');
    if (showingSource) {
      sourceBlock.classList.remove('hidden');
      viewSourceBtn.textContent = 'Hide source';
      viewerEl.classList.add('hidden');
    } else {
      sourceBlock.classList.add('hidden');
      viewSourceBtn.textContent = 'View source';
      viewerEl.classList.remove('hidden');
    }
  }

  function renderMermaid(container, source) {
    if (typeof mermaid === 'undefined') {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre>';
      return;
    }
    const pre = document.createElement('pre');
    pre.className = 'mermaid';
    pre.textContent = source;
    container.appendChild(pre);
    mermaid.run({ nodes: [pre], suppressErrors: true }).catch(() => {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre>';
    });
  }

  function renderPlantUmlKroki(container, source) {
    const encoded = encodeKroki(source);
    if (!encoded) {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre><p class="diagram-fallback">Kroki encoding not available. Showing source.</p>';
      return;
    }
    const img = document.createElement('img');
    img.alt = selectedDiagram ? (selectedDiagram.navLabel || selectedDiagram.title) : 'Diagram';
    img.loading = 'lazy';
    img.src = 'https://kroki.io/plantuml/svg/' + encoded;
    img.onerror = () => {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre><p class="diagram-fallback">Could not load diagram image. Showing source.</p>';
    };
    container.appendChild(img);
  }

  function escapeHtml(s) {
    const div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
  }

  function renderSearch(term) {
    const modulesEl = document.getElementById('modulesResults');
    const classesEl = document.getElementById('classesResults');
    const eventsEl = document.getElementById('eventsResults');
    const summaryEl = document.getElementById('resultsSummary');
    modulesEl.innerHTML = '';
    classesEl.innerHTML = '';
    eventsEl.innerHTML = '';
    if (!index) return;
    const q = term.toLowerCase();
    const match = (s) => q === '' || (s && s.toLowerCase().includes(q));
    const modules = index.modules.filter(m => match(m.name) || match(m.basePackage));
    const classes = index.classes.filter(c => match(c.className) || match(c.module));
    const events = index.events.filter(e =>
      match(e.eventType) || match(e.publisherModule) || (e.listenerModules || []).some(l => match(l)));
    summaryEl.textContent = q
      ? `Found ${modules.length} modules, ${classes.length} classes, ${events.length} events for "${term}"`
      : 'Type to search modules, classes and events.';
    modules.forEach(m => {
      const li = document.createElement('li');
      li.textContent = `${m.name}  (${m.basePackage})`;
      modulesEl.appendChild(li);
    });
    classes.forEach(c => {
      const li = document.createElement('li');
      li.textContent = `${c.className}  [${c.kind}] in ${c.module}`;
      classesEl.appendChild(li);
    });
    events.forEach(e => {
      const li = document.createElement('li');
      const listeners = (e.listenerModules || []).join(', ') || '—';
      li.textContent = `${e.eventType}  — publisher: ${e.publisherModule}, listeners: ${listeners}`;
      eventsEl.appendChild(li);
    });
  }

  window.addEventListener('DOMContentLoaded', loadIndex);
})();
