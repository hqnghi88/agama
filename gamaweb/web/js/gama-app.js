/**
 * GAMA Web - Main application controller
 * File explorer + CodeMirror editor + WebGL viewport + simulation control
 */

// ===== State =====
const state = {
    catalog: null,           // { categories: { name: [model] }, models: [...] }
    treeData: null,          // Built tree structure
    selectedModel: null,     // Current selected model object
    openTabs: [],            // [{ path, name, modified }]
    activeTab: null,         // path string
    editor: null,            // CodeMirror instance
    session: null,           // GamaSession
    running: false,
    paused: false,
    step: 0,
    modelCache: {},          // path -> { source, experiment }
};

// ===== Init =====
async function init() {
    try {
        // Init CodeMirror
        initEditor();

        // Init sidebar resize
        initSidebarResize();

        // Load catalog
        await loadCatalog();

        // Bind UI events
        bindEvents();

        // Register service worker
        registerServiceWorker();

        // Try connecting to local GAMA server
        tryServerConnection();
    } catch (e) {
        console.error('Init failed:', e);
        document.getElementById('file-tree').innerHTML =
            '<div style="padding:16px;color:#f44747">Init error: ' + e.message + '</div>';
    }
}

function tryServerConnection() {
    if (typeof GamaWebSocket === 'undefined') return;
    GamaWebSocket.connect(function() {
        document.getElementById('server-indicator').style.display = '';
        console.log('Connected to local GAMA server');
    });
    // Listen for SimulationOutput (agent data from write statements)
    GamaWebSocket.on('output', function(msg) {
        var content = msg.content || '';
        // Try to parse as JSON array of agent data
        if (content.startsWith('[')) {
            try {
                var agents = JSON.parse(content);
                renderServerAgents(agents);
            } catch(e) {
                // Not JSON - regular output
                console.log('[GAMA output]', content);
            }
        }
    });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}

// ===== Catalog & Tree =====
async function loadCatalog() {
    try {
        const resp = await fetch('catalog.json');
        const raw = await resp.json();
        state.catalog = buildCatalogStructure(raw);
        renderTree(state.catalog);
    } catch (e) {
        console.error('Failed to load catalog:', e);
        document.getElementById('file-tree').innerHTML =
            '<div style="padding:16px;color:#f44747">Failed to load model catalog</div>';
    }
}

function buildCatalogStructure(raw) {
    const categories = {};
    const models = [];
    for (const m of raw) {
        const parts = m.path.split('/');
        let cat = 'Other';
        if (m.path.startsWith('models/')) {
            cat = parts[1] || 'Other';
        } else if (m.path.startsWith('recipes/')) {
            cat = 'Recipes';
        } else if (m.path.startsWith('tutorials/')) {
            cat = 'Tutorials';
        }
        if (!categories[cat]) categories[cat] = [];
        categories[cat].push(m);
        models.push(m);
    }
    return { categories, models };
}

function renderTree(catalog) {
    const container = document.getElementById('file-tree');
    container.innerHTML = '';

    const sortedCats = Object.keys(catalog.categories).sort();

    for (const cat of sortedCats) {
        const models = catalog.categories[cat];
        models.sort((a, b) => {
            const na = (a.name || basename(a.path)).toLowerCase();
            const nb = (b.name || basename(b.path)).toLowerCase();
            return na.localeCompare(nb);
        });

        const folder = createTreeFolder(cat, models);
        container.appendChild(folder);
    }
}

function createTreeFolder(name, models) {
    const div = document.createElement('div');
    div.className = 'tree-folder-wrapper';

    const row = document.createElement('div');
    row.className = 'tree-item tree-folder';
    row.innerHTML = `<span class="icon">&#128193;</span><span class="label">${esc(name)}</span><span class="badge">${models.length}</span>`;

    const children = document.createElement('div');
    children.className = 'tree-children';

    for (const m of models) {
        const item = document.createElement('div');
        item.className = 'tree-item tree-model';
        item.dataset.path = m.path;

        const displayName = m.name || basename(m.path);
        const expCount = (m.experiments || '').split(',').filter(Boolean).length;
        const expBadge = expCount > 1 ? ` <span class="badge">${expCount} exp</span>` : '';

        item.innerHTML = `<span class="icon">&#128196;</span><span class="label">${esc(displayName)}</span>${expBadge}`;
        item.title = `${m.path}\n${m.experiments || 'No experiments'}\n${m.lines || '?'} lines`;

        item.addEventListener('click', () => selectModel(m));
        children.appendChild(item);
    }

    row.addEventListener('click', () => {
        children.classList.toggle('open');
        row.querySelector('.icon').innerHTML = children.classList.contains('open') ? '&#128194;' : '&#128193;';
    });

    div.appendChild(row);
    div.appendChild(children);
    return div;
}

// ===== Model Selection =====
async function selectModel(model) {
    state.selectedModel = model;

    document.querySelectorAll('.tree-model.selected').forEach(el => el.classList.remove('selected'));
    const treeItem = document.querySelector(`.tree-model[data-path="${CSS.escape(model.path)}"]`);
    if (treeItem) {
        treeItem.classList.add('selected');
        treeItem.scrollIntoView({ block: 'nearest' });
    }

    const source = await loadModelSource(model.path);
    showExperimentInfo(model, source);
    openInEditor(model, source);
    updateControlButtons();
}

async function loadModelSource(path) {
    if (state.modelCache[path]) return state.modelCache[path].source;

    try {
        const resp = await fetch('library/' + encodeURI(path));
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const text = await resp.text();
        state.modelCache[path] = { source: text };
        return text;
    } catch (e) {
        console.error('Failed to load model:', path, e);
        return `// Error loading ${path}\n// ${e.message}`;
    }
}

// ===== Editor =====
function initEditor() {
    CodeMirror.defineMode('gaml', function () {
        const keywords = new Set([
            'model', 'species', 'environment', 'experiment', 'action', 'reflex',
            'task', 'output', 'schedule', 'permanent', 'serial', 'parallel',
            'geometry', 'string', 'int', 'float', 'bool', 'list', 'map',
            'pair', 'point', 'matrix', 'rgb', 'file', 'container',
            'var', 'const', 'parameter', 'init', 'when', 'state',
        ]);

        const builtins = new Set([
            'create', 'die', 'release', 'capture', 'restore',
            'write', 'halt', 'ask', 'tell', 'do',
            'match', 'switch', 'if', 'else', 'loop', 'while',
            'as', 'with', 'such', 'that', 'where', 'in', 'over',
            'species', 'self', 'myself', 'here',
            'true', 'false', 'nil', 'NaN', 'inf',
            'length', 'sort', 'shuffle', 'first', 'last', 'reverse',
            'among', 'at', 'contains', 'empty', 'copy',
            'rotate', 'index_of', 'last_index_of',
            'matches', 'matches_regexp', 'replace', 'replace_all',
            'each', 'accumulate', 'collect', 'group_by', 'sort_by',
            'sum', 'mean', 'variance', 'std_dev', 'min', 'max',
            'count', 'any', 'all', 'none', 'product',
            'rnd', 'gauss', 'lognormal', 'poisson',
            'polygon', 'polyline', 'line', 'circle', 'rectangle',
            'square', 'triangle', 'hexagon', 'star', 'cross',
            'mesh', 'sphere', 'cone', 'cylinder',
            'distance_to', 'distance_to_line', 'distance_to_point',
            'overlap', 'intersects', 'covers', 'partially_overlaps',
            'inside', 'on', 'touching',
            'point_at', 'closest_points_with', 'points_closest_to',
            'grid_value_at', 'grid_neighbors_at',
            'color', 'draw', 'chart',
            'world', 'model', 'simulation',
            'date', 'time', 'cycle', 'machine_time',
            'seed', 'machine',
        ]);

        return {
            startState: function () {
                return { inString: false, inComment: false };
            },
            token: function (stream, state) {
                if (state.inComment) {
                    if (stream.match('*/')) {
                        state.inComment = false;
                    } else {
                        stream.next();
                    }
                    return 'comment';
                }
                if (stream.match('//')) {
                    stream.skipToEnd();
                    return 'comment';
                }
                if (stream.match('/*')) {
                    state.inComment = true;
                    return 'comment';
                }
                if (stream.match('"')) {
                    state.inString = true;
                    return 'string';
                }
                if (state.inString) {
                    if (stream.match('"')) {
                        state.inString = false;
                    } else if (stream.match('\\"')) {
                        // escaped
                    } else {
                        stream.next();
                    }
                    return 'string';
                }
                if (stream.match(/[0-9]+(\.[0-9]+)?/)) return 'number';
                if (stream.match(/[a-zA-Z_][a-zA-Z0-9_]*/)) {
                    const word = stream.current();
                    if (keywords.has(word)) return 'keyword';
                    if (builtins.has(word)) return 'builtin';
                    return 'variable';
                }
                if (stream.match(/[<>=!+\-*/%&|^~?:]+/)) return 'operator';
                if (stream.match(/[{}\[\]()]/)) return 'bracket';
                if (stream.match(';')) return 'punctuation';
                if (stream.match('.')) return 'punctuation';
                stream.next();
                return null;
            }
        };
    });

    state.editor = CodeMirror.fromTextArea(document.getElementById('code-editor'), {
        mode: 'gaml',
        theme: 'material-darker',
        lineNumbers: true,
        matchBrackets: true,
        autoCloseBrackets: true,
        indentUnit: 4,
        tabSize: 4,
        indentWithTabs: true,
        lineWrapping: false,
        gutters: ['CodeMirror-linenumbers'],
        extraKeys: {
            'Ctrl-S': () => saveCurrentModel(),
            'Cmd-S': () => saveCurrentModel(),
        }
    });

    state.editor.on('change', () => {
        if (state.activeTab) {
            const tab = state.openTabs.find(t => t.path === state.activeTab);
            if (tab && !tab.modified) {
                tab.modified = true;
                renderTabs();
            }
        }
        updateFileInfo();
    });
}

function openInEditor(model, source) {
    let tab = state.openTabs.find(t => t.path === model.path);
    if (!tab) {
        tab = { path: model.path, name: model.name || basename(model.path), modified: false };
        state.openTabs.push(tab);
    }

    state.activeTab = model.path;
    state.editor.setValue(source);
    state.editor.clearHistory();

    document.getElementById('editor-placeholder').style.display = 'none';

    renderTabs();
    updateFileInfo();
}

function renderTabs() {
    const tabsEl = document.getElementById('tabs');
    tabsEl.innerHTML = '';

    for (const t of state.openTabs) {
        const div = document.createElement('div');
        div.className = 'tab' + (t.path === state.activeTab ? ' active' : '');
        const modIcon = t.modified ? ' <span class="modified">\u25cf</span>' : '';
        div.innerHTML = `<span>${esc(t.name)}</span>${modIcon}${t.path !== '__display__' ? '<span class="close-tab">\u00d7</span>' : ''}`;

        div.addEventListener('click', (e) => {
            if (e.target.classList.contains('close-tab')) {
                closeTab(t.path);
            } else {
                activateTab(t.path);
            }
        });

        tabsEl.appendChild(div);
    }
}

async function activateTab(path) {
    state.activeTab = path;

    if (path === '__display__') {
        // Show viewport, hide editor
        document.getElementById('viewport-container').classList.remove('hidden');
        document.getElementById('editor-placeholder').style.display = 'none';
        const cm = document.querySelector('.CodeMirror');
        if (cm) cm.style.display = 'none';
        renderTabs();
        // Trigger resize so canvas gets correct dimensions
        if (state.session && state.session.renderer) {
            state.session.renderer._sized = false;
        }
        return;
    }

    // Hide viewport, show editor
    document.getElementById('viewport-container').classList.add('hidden');
    const cm = document.querySelector('.CodeMirror');
    if (cm) cm.style.display = '';

    const model = state.catalog.models.find(m => m.path === path);
    if (model) {
        const source = await loadModelSource(path);
        state.editor.setValue(source);
        state.editor.clearHistory();
        renderTabs();
        state.selectedModel = model;
        showExperimentInfo(model, source);
        updateFileInfo();
    } else {
        renderTabs();
    }
}

function closeTab(path) {
    if (path === '__display__') return; // Don't close display tab via X
    const idx = state.openTabs.findIndex(t => t.path === path);
    if (idx < 0) return;

    state.openTabs.splice(idx, 1);

    if (state.activeTab === path) {
        if (state.openTabs.length > 0) {
            const next = state.openTabs[Math.min(idx, state.openTabs.length - 1)];
            activateTab(next.path);
        } else {
            state.activeTab = null;
            state.editor.setValue('');
            document.getElementById('editor-placeholder').style.display = 'flex';
            document.getElementById('exp-info').textContent = '';
        }
    }

    renderTabs();
}

function saveCurrentModel() {
    if (!state.activeTab) return;
    const tab = state.openTabs.find(t => t.path === state.activeTab);
    if (tab) {
        const source = state.editor.getValue();
        localStorage.setItem('gama_model_' + tab.path, source);
        state.modelCache[tab.path] = { source };
        tab.modified = false;
        renderTabs();
        flashStatus('Saved', 'success');
    }
}

// ===== Experiment Info =====
function showExperimentInfo(model, source) {
    const expRegex = /experiment\s+(\w+)/g;
    const experiments = [];
    let match;
    while ((match = expRegex.exec(source)) !== null) {
        experiments.push(match[1]);
    }

    const expInfo = document.getElementById('exp-info');
    expInfo.textContent = experiments.length > 0
        ? experiments.length + ' experiment' + (experiments.length > 1 ? 's' : '') + ': ' + experiments.join(', ')
        : 'No experiments found';

    const rightPanel = document.getElementById('right-panel');
    rightPanel.classList.remove('hidden');

    const select = document.getElementById('experiment-select');
    select.innerHTML = '';
    for (const e of experiments) {
        const opt = document.createElement('option');
        opt.value = e;
        opt.textContent = e;
        select.appendChild(opt);
    }

    const paramsContainer = document.getElementById('params-container');
    const paramRegex = /parameter\s+\w+\s+<-?\s*["']([^"']+)["']\s*(?::\s*(\w+)\s*(?:<-\s*([^\n]+))?)/g;
    const params = [];
    while ((match = paramRegex.exec(source)) !== null) {
        params.push({ name: match[1], dataType: match[2], defaultVal: match[3] });
    }

    if (params.length > 0) {
        paramsContainer.innerHTML = params.map(p =>
            '<div class="config-group" style="border:none;padding:2px 0">' +
            '<label>' + esc(p.name) + (p.dataType ? ' (' + esc(p.dataType) + ')' : '') + '</label>' +
            '<input type="text" value="' + esc(p.defaultVal || '') + '" data-param="' + esc(p.name) + '">' +
            '</div>'
        ).join('');
    } else {
        paramsContainer.innerHTML = '<div style="color:var(--text-secondary);font-size:11px">No parameters found</div>';
    }

    const outputsContainer = document.getElementById('outputs-container');
    const outputRegex = /output\s*\{([^}]*)\}/g;
    const outputs = [];
    while ((match = outputRegex.exec(source)) !== null) {
        const block = match[1];
        const displayRegex = /display\s+"([^"]+)"/g;
        let dm;
        while ((dm = displayRegex.exec(block)) !== null) {
            outputs.push(dm[1]);
        }
    }

    if (outputs.length > 0) {
        outputsContainer.innerHTML = outputs.map(o =>
            '<div style="padding:2px 0;font-size:12px;color:var(--text-bright)">' + esc(o) + '</div>'
        ).join('');
    } else {
        outputsContainer.innerHTML = '<div style="color:var(--text-secondary);font-size:11px">No displays found</div>';
    }
}

// ===== Simulation Control =====
function toggleRun() {
    if (state.running) {
        stopSimulation();
    } else {
        startSimulation();
    }
}

async function startSimulation() {
    if (!state.selectedModel) return;

    state.running = true;
    state.paused = false;
    state.step = 0;

    updateControlButtons();
    setStatus('Compiling...', 'running');

    var source = (state.editor && state.editor.getValue().trim().length > 0)
        ? state.editor.getValue()
        : await loadModelSource(state.selectedModel.path);

    console.log('[GAMA] Starting simulation for:', state.selectedModel.path);
    console.log('[GAMA] GamaWebSocket defined:', typeof GamaWebSocket);
    console.log('[GAMA] GamaServerSession defined:', typeof GamaServerSession);

    // Try server mode first, fall back to JS-only
    if (typeof GamaWebSocket !== 'undefined' && typeof GamaServerSession !== 'undefined'
        && GamaWebSocket.isConnected && GamaWebSocket.isConnected()) {
        console.log('[GAMA] Using server mode');
        startServerSimulation(source);
    } else if (typeof GamaWebSocket !== 'undefined' && typeof GamaServerSession !== 'undefined') {
        // Not yet connected, try to connect first
        console.log('[GAMA] Connecting to server...');
        startServerSimulation(source);
    } else {
        console.log('[GAMA] Using local JS mode');
        startLocalSimulation(source);
    }
}

function startLocalSimulation(source) {
    state.session = new GamaSession(document.getElementById('viewport'), function (sim) {
        state.step = sim.cycle;
        document.getElementById('step-counter').textContent = 'Step: ' + sim.cycle;
        document.getElementById('fps').textContent = 'Agents: ' + countAgents(sim);
    });

    try {
        state.session.loadAndRun(source);
        setStatus('Running', 'running');
        if (!state.openTabs.find(t => t.path === '__display__')) {
            state.openTabs.push({ path: '__display__', name: 'Display', modified: false });
        }
        activateTab('__display__');
    } catch (e) {
        console.error('Model compilation failed:', e);
        setStatus('Compile Error', 'error');
        state.running = false;
        updateControlButtons();
        return;
    }
}

function startServerSimulation(source) {
    setStatus('Connecting to server...', 'running');

    GamaWebSocket.connect(function() {
        console.log('[GAMA] Connected, uploading model...');
        setStatus('Uploading model...', 'running');
        var tmpPath = '/tmp/gama_model_' + Date.now() + '.gaml';
        GamaWebSocket.upload(tmpPath, source, function(err) {
            if (err) {
                console.error('[GAMA] Upload failed:', err);
                setStatus('Upload Error: ' + err, 'error');
                state.running = false;
                updateControlButtons();
                return;
            }
            console.log('[GAMA] Uploaded, loading...');
            setStatus('Loading model...', 'running');
            tryServerLoad(tmpPath, ['main', 'experiment', 'exp', 'test'], 0, source);
        });
    });
}

function tryServerLoad(path, names, idx, source) {
    if (idx >= names.length) {
        // Fallback: try to get experiment from describe
        GamaWebSocket.describe(function(err, desc) {
            var expName = '';
            if (desc && desc.experiments && desc.experiments.length > 0) {
                expName = desc.experiments[0];
            }
            if (expName) {
                GamaWebSocket.load(path, expName, function(err2) {
                    if (err2) {
                        setStatus('Load Error: ' + err2, 'error');
                        state.running = false;
                        updateControlButtons();
                        return;
                    }
                    onServerModelLoaded(expName);
                });
            } else {
                setStatus('No experiment found', 'error');
                state.running = false;
                updateControlButtons();
            }
        });
        return;
    }
    GamaWebSocket.load(path, names[idx], function(err) {
        if (err) {
            tryServerLoad(path, names, idx + 1, source);
        } else {
            onServerModelLoaded(names[idx]);
        }
    });
}

function onServerModelLoaded(expName) {
    state.serverExperiment = expName;
    state.serverSpeciesName = 'cell'; // Default
    setStatus('Running (server)', 'running');
    document.getElementById('step-counter').textContent = 'Step: 0';
    document.getElementById('exp-info').textContent = expName;

    if (!state.openTabs.find(t => t.path === '__display__')) {
        state.openTabs.push({ path: '__display__', name: 'Display', modified: false });
    }
    activateTab('__display__');

    // Wait for layout to settle, then detect species and start
    requestAnimationFrame(function() {
        GamaWebSocket.evaluate('length(cell)', function(err, content) {
            if (!err && typeof content === 'number' && content > 0) {
                state.serverSpeciesName = 'cell';
            }
            console.log('Using species:', state.serverSpeciesName, 'count:', content);
            // One more frame to ensure canvas is sized
            requestAnimationFrame(function() {
                serverStepLoop();
            });
        });
    });
}

function serverStepLoop() {
    if (!state.running || state.paused) return;
    GamaWebSocket.step(1, function(err) {
        if (err) {
            console.error('Step error:', err);
            if (state.running) {
                setStatus('Step Error', 'error');
                state.running = false;
                updateControlButtons();
            }
            return;
        }
        state.step++;
        document.getElementById('step-counter').textContent = 'Step: ' + state.step;

        // Fetch agent data via expression evaluation
        serverFetchAndRender(function() {
            if (state.running && !state.paused) {
                requestAnimationFrame(serverStepLoop);
            }
        });
    });
}

function serverFetchAndRender(callback) {
    var speciesName = state.serverSpeciesName || 'cell';

    // Get locations
    GamaWebSocket.evaluate(speciesName + ' collect each.location', function(err, content) {
        if (err || !content) {
            console.log('[GAMA] Location fetch failed:', err);
            if (callback) callback();
            return;
        }
        var locations = content;

        // Get colors
        GamaWebSocket.evaluate(speciesName + ' collect each.mycolor', function(err2, content2) {
            var agents = [];
            var locs = Array.isArray(locations) ? locations : [];
            var cols = (!err2 && content2 && Array.isArray(content2)) ? content2 : [];

            for (var i = 0; i < locs.length; i++) {
                var loc = locs[i];
                var col = cols[i];
                var agent = {
                    x: loc.x || 0,
                    y: loc.y || 0,
                    r: col ? (col.red || 100) : 100,
                    g: col ? (col.green || 100) : 100,
                    b: col ? (col.blue || 100) : 100
                };
                agents.push(agent);
            }

            if (agents.length > 0) {
                renderServerAgents(agents);
            } else {
                console.log('[GAMA] No agents to render');
            }
            if (callback) callback();
        });
    });
}

function renderServerAgents(agents) {
    var canvas = document.getElementById('viewport');
    if (!canvas) return;
    var container = document.getElementById('viewport-container');
    if (container && container.classList.contains('hidden')) return;
    var rect = canvas.parentElement.getBoundingClientRect();
    if (rect.width < 10 || rect.height < 10) return; // not laid out yet
    var dpr = window.devicePixelRatio || 1;
    var w = Math.floor(rect.width * dpr);
    var h = Math.floor(rect.height * dpr);
    if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
    }
    var ctx = canvas.getContext('2d');
    var ENV = 3000;
    var sW = rect.width, sH = rect.height;
    var sScale = Math.min(sW, sH) / ENV;
    var offX = (sW - ENV * sScale) / 2;
    var offY = (sH - ENV * sScale) / 2;

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.fillStyle = '#0e0e12';
    ctx.fillRect(0, 0, sW, sH);

    var radius = Math.max(2, sScale * 20);
    for (var i = 0; i < agents.length; i++) {
        var a = agents[i];
        var sx = offX + a.x * sScale;
        var sy = offY + (ENV - a.y) * sScale;
        ctx.fillStyle = 'rgb(' + (a.r || 100) + ',' + (a.g || 100) + ',' + (a.b || 100) + ')';
        ctx.beginPath();
        ctx.arc(sx, sy, radius, 0, Math.PI * 2);
        ctx.fill();
    }
    ctx.restore();

    document.getElementById('fps').textContent = 'Agents: ' + agents.length;
}

function countAgents(sim) {
    var total = 0;
    for (var sp in sim.populations) {
        if (sp !== 'world') total += sim.populations[sp].length;
    }
    return total;
}

function stopSimulation() {
    state.running = false;
    state.paused = false;
    if (state.session) {
        state.session.stop();
        state.session = null;
    }
    // Also stop server if running
    if (state.serverExperiment && typeof GamaWebSocket !== 'undefined') {
        GamaWebSocket.stop(function() {});
        state.serverExperiment = null;
    }
    // Remove display tab
    state.openTabs = state.openTabs.filter(t => t.path !== '__display__');
    document.getElementById('viewport-container').classList.add('hidden');
    const cm = document.querySelector('.CodeMirror');
    if (cm) cm.style.display = '';
    if (state.activeTab === '__display__') {
        const codeTab = state.openTabs.find(t => t.path !== '__display__');
        if (codeTab) {
            state.activeTab = codeTab.path;
            state.selectedModel = state.catalog.models.find(m => m.path === codeTab.path) || state.selectedModel;
        } else {
            state.activeTab = null;
        }
    }
    renderTabs();
    updateControlButtons();
    setStatus('Ready');
}

function togglePause() {
    if (!state.running) return;
    state.paused = !state.paused;
    if (state.serverExperiment) {
        // Server mode: pause/resume via WebSocket
        if (state.paused) {
            GamaWebSocket.pause(function() {});
        } else {
            serverStepLoop();
        }
    } else if (state.session) {
        // Local JS mode
        if (state.paused) {
            state.session.pause();
        } else {
            state.session.resume();
        }
    }
    updateControlButtons();
    setStatus(state.paused ? 'Paused' : 'Running', state.paused ? 'paused' : 'running');
}

function stepOnce() {
    if (!state.running) return;
    if (state.serverExperiment) {
        GamaWebSocket.step(1, function(err) {
            if (!err) {
                state.step++;
                document.getElementById('step-counter').textContent = 'Step: ' + state.step;
                serverFetchAndRender(function() {});
            }
        });
    } else if (state.session) {
        state.session.step();
        state.step = state.session.session.cycle;
        document.getElementById('step-counter').textContent = 'Step: ' + state.step;
    }
}

function updateControlButtons() {
    document.getElementById('btn-run').disabled = !state.selectedModel;
    document.getElementById('btn-run').innerHTML = state.running
        ? '&#9632; Stop' : '&#9654; Run';
    document.getElementById('btn-pause').disabled = !state.running;
    document.getElementById('btn-step').disabled = !state.running;
    document.getElementById('btn-stop').disabled = !state.running;
    document.getElementById('btn-save').disabled = !state.activeTab;
}

// ===== UI Helpers =====
function setStatus(text, cls) {
    const badge = document.getElementById('status');
    badge.textContent = text;
    badge.className = 'status-badge' + (cls ? ' ' + cls : '');
}

function flashStatus(text, cls) {
    setStatus(text, cls);
    setTimeout(() => setStatus('Ready'), 2000);
}

function updateFileInfo() {
    if (!state.activeTab) {
        document.getElementById('file-info').textContent = '';
        return;
    }
    const model = state.catalog.models.find(m => m.path === state.activeTab);
    if (model) {
        document.getElementById('file-info').textContent = model.path + ' (' + (model.lines || '?') + ' lines)';
    }
}

function basename(path) {
    return path.split('/').pop().replace('.gaml', '');
}

function esc(str) {
    const d = document.createElement('div');
    d.textContent = str || '';
    return d.innerHTML;
}

// ===== Events =====
function bindEvents() {
    document.getElementById('btn-run').addEventListener('click', toggleRun);
    document.getElementById('btn-pause').addEventListener('click', togglePause);
    document.getElementById('btn-step').addEventListener('click', stepOnce);
    document.getElementById('btn-stop').addEventListener('click', stopSimulation);
    document.getElementById('btn-save').addEventListener('click', saveCurrentModel);

    document.getElementById('btn-close-right').addEventListener('click', () => {
        document.getElementById('right-panel').classList.add('hidden');
    });

    document.getElementById('search-box').addEventListener('input', (e) => {
        filterTree(e.target.value);
    });

    document.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 's') {
            e.preventDefault();
            saveCurrentModel();
        }
        if (e.key === ' ' && document.activeElement.tagName !== 'INPUT'
            && document.activeElement.tagName !== 'TEXTAREA') {
            e.preventDefault();
            if (!state.running) toggleRun();
            else togglePause();
        }
    });
}

function filterTree(query) {
    const q = query.toLowerCase().trim();
    document.querySelectorAll('.tree-folder-wrapper').forEach(folder => {
        let hasVisible = false;
        folder.querySelectorAll('.tree-model').forEach(item => {
            const label = item.querySelector('.label').textContent.toLowerCase();
            const path = (item.dataset.path || '').toLowerCase();
            const show = !q || label.includes(q) || path.includes(q);
            item.style.display = show ? '' : 'none';
            if (show) hasVisible = true;
        });
        if (q) {
            folder.querySelector('.tree-children').classList.add('open');
            folder.querySelector('.tree-folder .icon').innerHTML = '&#128194;';
        }
        folder.style.display = hasVisible ? '' : 'none';
    });
}

// ===== Sidebar resize =====
function initSidebarResize() {
    const sidebar = document.getElementById('sidebar');
    const handle = document.getElementById('sidebar-resize');
    let dragging = false;

    handle.addEventListener('mousedown', () => {
        dragging = true;
        document.body.style.cursor = 'col-resize';
        document.body.style.userSelect = 'none';
    });

    document.addEventListener('mousemove', (e) => {
        if (!dragging) return;
        const newWidth = Math.max(180, Math.min(500, e.clientX));
        sidebar.style.width = newWidth + 'px';
    });

    document.addEventListener('mouseup', () => {
        if (dragging) {
            dragging = false;
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
        }
    });
}

// ===== Service Worker =====
function registerServiceWorker() {
    if ('serviceWorker' in navigator) {
        navigator.serviceWorker.register('sw.js').then(reg => {
            console.log('SW registered:', reg.scope);
        }).catch(err => {
            console.warn('SW registration failed:', err);
        });
    }
}
