(() => {
  let index = null;
  let currentLevel = 'all';

  async function loadIndex() {
    const res = await fetch('site-index.json');
    index = await res.json();
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

    index.diagrams
      .filter(d => currentLevel === 'all' || d.level === currentLevel)
      .sort((a, b) => a.title.localeCompare(b.title))
      .forEach(d => {
        const li = document.createElement('li');
        li.className = 'diagram-item';
        const link = document.createElement('a');
        link.textContent = d.title + ' (' + d.level + ')';
        // Link to the raw PlantUML file so users can open it in their tooling
        link.href = '../' + d.path;
        link.target = '_blank';
        li.appendChild(link);
        container.appendChild(li);
      });
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

