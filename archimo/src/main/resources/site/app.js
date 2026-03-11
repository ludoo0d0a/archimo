(() => {
  let index = null;
  let currentLevel = 'all';
  let selectedDiagram = null;

  async function loadIndex() {
    const res = await fetch('site-index.json');
    index = await res.json();
    if (typeof mermaid !== 'undefined') {
      mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });
    }
    bindUI();
    renderDiagrams();
    renderSearch('');
  }

  function bindUI() {
    const searchInput = document.getElementById('searchInput');
    searchInput.addEventListener('input', (e) => {
      renderSearch(e.target.value.trim());
    });

    const filters = document.querySelectorAll('#diagramFilters .chip');
    filters.forEach(btn => {
      btn.addEventListener('click', () => {
        filters.forEach(b => b.classList.remove('chip-active'));
        btn.classList.add('chip-active');
        currentLevel = btn.dataset.level;
        renderDiagrams();
      });
    });
  }

  function renderDiagrams() {
    const container = document.getElementById('diagramList');
    container.innerHTML = '';
    if (!index) return;

    const list = index.diagrams
      .filter(d => currentLevel === 'all' || d.level === currentLevel)
      .sort((a, b) => a.title.localeCompare(b.title));

    list.forEach(d => {
      const li = document.createElement('li');
      li.className = 'diagram-item';
      const link = document.createElement('a');
      link.href = '#';
      link.textContent = d.title + (d.level !== 'mermaid' ? ' (' + d.level + ')' : '');
      link.addEventListener('click', (e) => {
        e.preventDefault();
        selectDiagram(d);
      });
      li.appendChild(link);
      container.appendChild(li);
    });
  }

  function selectDiagram(d) {
    selectedDiagram = d;
    const section = document.getElementById('diagramViewerSection');
    const titleEl = document.getElementById('diagramViewerTitle');
    const viewerEl = document.getElementById('diagramViewer');
    const rawLink = document.getElementById('diagramRawLink');

    section.classList.remove('hidden');
    titleEl.textContent = d.title;
    rawLink.href = '../' + d.path;
    rawLink.textContent = 'Open raw ' + (d.format === 'plantuml' ? 'PlantUML' : 'Mermaid') + ' source';

    viewerEl.innerHTML = '';
    if (!d.source) {
      viewerEl.textContent = 'Diagram source not available.';
      return;
    }

    if (d.format === 'mermaid') {
      renderMermaid(viewerEl, d.source);
    } else if (d.format === 'plantuml') {
      renderPlantUml(viewerEl, d.source);
    } else {
      viewerEl.textContent = 'Unknown diagram format.';
    }
  }

  function renderMermaid(container, source) {
    if (typeof mermaid === 'undefined') {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre><p class="diagram-fallback">Mermaid.js not loaded. Showing raw source.</p>';
      return;
    }
    const pre = document.createElement('pre');
    pre.className = 'mermaid';
    pre.textContent = source;
    container.appendChild(pre);
    mermaid.run({ nodes: [pre], suppressErrors: true }).catch(() => {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre><p class="diagram-fallback">Mermaid render failed. Showing raw source.</p>';
    });
  }

  function renderPlantUml(container, source) {
    let encoded;
    try {
      encoded = typeof plantumlEncoder !== 'undefined' ? plantumlEncoder.encode(source) : null;
    } catch (e) {
      encoded = null;
    }
    if (!encoded) {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre><p class="diagram-fallback">PlantUML encoder not available. Showing raw source.</p>';
      return;
    }
    const img = document.createElement('img');
    img.alt = selectedDiagram ? selectedDiagram.title : 'Diagram';
    img.loading = 'lazy';
    img.src = 'https://www.plantuml.com/plantuml/svg/' + encoded;
    img.onerror = () => {
      container.innerHTML = '<pre class="diagram-source">' + escapeHtml(source) + '</pre><p class="diagram-fallback">PlantUML server unreachable or invalid diagram. Showing raw source.</p>';
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

    const modules = index.modules.filter(m =>
      match(m.name) || match(m.basePackage)
    );
    const classes = index.classes.filter(c =>
      match(c.className) || match(c.module)
    );
    const events = index.events.filter(e =>
      match(e.eventType) ||
      match(e.publisherModule) ||
      (e.listenerModules || []).some(l => match(l))
    );

    summaryEl.textContent = q
      ? `Found ${modules.length} modules, ${classes.length} classes/components, ${events.length} events for "${term}"`
      : 'Type to search modules, classes/components and events.';

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

