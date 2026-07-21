/**
 * GAMA Web - GAML-to-JavaScript Transpiler + Runtime
 * Parses .gaml files and executes them as JavaScript agent-based simulations.
 *
 * Supports: model/global/species/grid/experiment, variables, init, reflex,
 *           create/ask/do/die, if/else/loop, basic expressions, display rendering.
 */

// ============================================================
// RUNTIME — Agent-based simulation engine
// ============================================================

function GamaRuntime() {
    this.world = null;
    this.species = {};      // name -> SpeciesDef
    this.speciesOrder = []; // declaration order
    this.grid = null;       // grid species (if any)
    this.simulation = null;
    this.cycle = 0;
    this.step_value = 1;
    this.rng_seed = Date.now();
    this._rng_state = this.rng_seed;
    this.outputCallback = null;  // function(species, agents) for rendering
}

// Seeded RNG (mulberry32)
GamaRuntime.prototype.random = function () {
    var t = this._rng_state += 0x6D2B79F5;
    t = Math.imul(t ^ t >>> 15, t | 1);
    t ^= t + Math.imul(t ^ t >>> 7, t | 61);
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
};

GamaRuntime.prototype.flip = function (p) { return this.random() < p; };

GamaRuntime.prototype.rnd = function (a, b) {
    if (b === undefined) { b = a; a = 0; }
    return a + Math.floor(this.random() * (b - a + 1));
};

GamaRuntime.prototype.rndFloat = function (a, b) {
    if (b === undefined) { b = a; a = 0; }
    return a + this.random() * (b - a);
};

GamaRuntime.prototype.one_of = function (list) {
    if (!list || list.length === 0) return null;
    return list[Math.floor(this.random() * list.length)];
};

GamaRuntime.prototype.shuffle = function (list) {
    var arr = list.slice();
    for (var i = arr.length - 1; i > 0; i--) {
        var j = Math.floor(this.random() * (i + 1));
        var tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
    }
    return arr;
};

GamaRuntime.prototype.min_list = function (list) {
    var m = Infinity;
    for (var i = 0; i < list.length; i++) if (list[i] < m) m = list[i];
    return m;
};

GamaRuntime.prototype.max_list = function (list) {
    var m = -Infinity;
    for (var i = 0; i < list.length; i++) if (list[i] > m) m = list[i];
    return m;
};

// ===== Simulation =====
function Simulation(runtime) {
    this.runtime = runtime;
    this.world = null;      // single world agent
    this.populations = {};  // speciesName -> [Agent]
    this.toRemove = [];
    this.toCreate = [];
    this.stopped = false;
    this.cycle = 0;
    this.displayData = null; // output for rendering
}

Simulation.prototype.addAgent = function (speciesName, agent) {
    if (!this.populations[speciesName]) this.populations[speciesName] = [];
    this.populations[speciesName].push(agent);
    agent._species = speciesName;
    agent._sim = this;
    agent._runtime = this.runtime;
};

Simulation.prototype.removeAgent = function (speciesName, agent) {
    this.toRemove.push({ species: speciesName, agent: agent });
};

Simulation.prototype.createAgents = function (speciesName, count, initFn) {
    for (var i = 0; i < count; i++) {
        var def = this.runtime.species[speciesName];
        if (!def) { console.warn('Unknown species:', speciesName); continue; }
        var agent = def.createAgent(this);
        if (initFn) initFn.call(agent, agent);
        this.toCreate.push({ species: speciesName, agent: agent });
    }
};

Simulation.prototype.step = function () {
    this.cycle++;

    // Flush pending creates
    for (var c = 0; c < this.toCreate.length; c++) {
        this.addAgent(this.toCreate[c].species, this.toCreate[c].agent);
    }
    this.toCreate = [];

    // Execute world reflexes first
    if (this.world) {
        this.world._executeReflexes();
    }

    // Execute species reflexes in declaration order
    var order = this.runtime.speciesOrder;
    for (var s = 0; s < order.length; s++) {
        var spName = order[s];
        if (spName === 'world' || spName === this.runtime.grid?.name) continue;
        var pop = this.populations[spName] || [];
        // Iterate over a copy since agents may die during iteration
        var agents = pop.slice();
        for (var a = 0; a < agents.length; a++) {
            if (agents[a]._dead) continue;
            agents[a]._executeReflexes();
        }
    }

    // Flush removes
    for (var r = 0; r < this.toRemove.length; r++) {
        var pop2 = this.populations[this.toRemove[r].species];
        if (pop2) {
            var idx = pop2.indexOf(this.toRemove[r].agent);
            if (idx >= 0) pop2.splice(idx, 1);
        }
    }
    this.toRemove = [];

    // Update derived variables (->)
    this._updateDerived();

    // Build display data
    this._buildDisplayData();
};

Simulation.prototype._updateDerived = function () {
    var worldDef = this.runtime.species['world'];
    if (worldDef) {
        for (var i = 0; i < worldDef.derivedVars.length; i++) {
            var dv = worldDef.derivedVars[i];
            this.world[dv.name] = dv.compute.call(this.world);
        }
    }
    for (var s = 0; s < this.runtime.speciesOrder.length; s++) {
        var sp = this.runtime.speciesOrder[s];
        var def = this.runtime.species[sp];
        if (def.derivedVars.length > 0) {
            var pop = this.populations[sp] || [];
            for (var a = 0; a < pop.length; a++) {
                for (var d = 0; d < def.derivedVars.length; d++) {
                    var dv2 = def.derivedVars[d];
                    pop[a][dv2.name] = dv2.compute.call(pop[a]);
                }
            }
        }
    }
};

Simulation.prototype._buildDisplayData = function () {
    var result = {};
    for (var sp in this.populations) {
        var agents = this.populations[sp];
        var items = [];
        for (var i = 0; i < agents.length; i++) {
            var ag = agents[i];
            items.push({
                x: ag.location ? ag.location.x : 0,
                y: ag.location ? ag.location.y : 0,
                shape: ag._shape || 'circle',
                size: ag.size || 3,
                color: ag.color || '#4ec9b0',
            });
        }
        result[sp] = items;
    }
    this.displayData = result;
};

Simulation.prototype.pause = function () { this.stopped = true; };

// ===== Agent =====
function Agent() {
    this.location = { x: 0, y: 0 };
    this.color = '#4ec9b0';
    this.size = 3;
    this.speed = 1;
    this._species = null;
    this._sim = null;
    this._runtime = null;
    this._dead = false;
    this._shape = 'circle';
    this._reflexFns = [];
}

Agent.prototype._executeReflexes = function () {
    for (var i = 0; i < this._reflexFns.length; i++) {
        var rx = this._reflexFns[i];
        if (rx.whenFn && !rx.whenFn.call(this)) continue;
        rx.fn.call(this);
    }
};

Agent.prototype.die = function () {
    this._dead = true;
    if (this._sim) this._sim.removeAgent(this._species, this);
};

Agent.prototype.wander = function () {
    var angle = this._runtime.random() * Math.PI * 2;
    var dist = this.speed;
    this.location.x += Math.cos(angle) * dist;
    this.location.y += Math.sin(angle) * dist;
    // Clamp to world bounds
    var world = this._sim.world;
    if (world && world.shape) {
        var halfW = world.shape.width / 2;
        var halfH = world.shape.height / 2;
        this.location.x = Math.max(-halfW, Math.min(halfW, this.location.x));
        this.location.y = Math.max(-halfH, Math.min(halfH, this.location.y));
    }
};

Agent.prototype.distance_to = function (other) {
    var dx = this.location.x - other.location.x;
    var dy = this.location.y - other.location.y;
    return Math.sqrt(dx * dx + dy * dy);
};

// ===== Species Definition =====
function SpeciesDef(name) {
    this.name = name;
    this.isGrid = false;
    this.parentName = null;
    this.varDefs = {};       // name -> {type, default}
    this.derivedVars = [];   // [{name, compute}]
    this.initFn = null;
    this.reflexDefs = [];    // [{name, whenFn, fn}]
    this.actionDefs = {};    // name -> fn
    this.aspectDefs = {};    // name -> fn
    this.gridWidth = 0;
    this.gridHeight = 0;
    this.gridNeighbors = 4;
    this.skills = [];
    this.shapeWidth = 50;
    this.shapeHeight = 50;
}

SpeciesDef.prototype.createAgent = function (sim) {
    var agent = new Agent();
    // Copy variable defaults
    for (var vn in this.varDefs) {
        var vd = this.varDefs[vn];
        agent[vn] = typeof vd.default === 'function' ? vd.default.call(agent) : vd.default;
    }
    // Copy reflexes
    agent._reflexFns = this.reflexDefs.slice();
    // Copy actions
    for (var an in this.actionDefs) {
        agent[an] = this.actionDefs[an];
    }
    // Copy aspects
    for (var asp in this.aspectDefs) {
        agent[asp] = this.aspectDefs[asp];
    }
    agent._species = this.name;
    agent._sim = sim;
    agent._runtime = sim.runtime;
    return agent;
};


// ============================================================
// PARSER — Tokenizer + recursive descent
// ============================================================

function GamlTokenizer(source) {
    this.source = source;
    this.pos = 0;
    this.tokens = [];
    this._tokenize();
}

GamlTokenizer.prototype._tokenize = function () {
    var s = this.source;
    var i = 0;
    var keywords = new Set([
        'model', 'import', 'as', 'global', 'species', 'grid', 'experiment',
        'init', 'reflex', 'abort', 'do', 'invoke', 'create', 'ask', 'die', 'pause',
        'if', 'else', 'loop', 'while', 'over', 'from', 'to', 'step', 'times',
        'var', 'let', 'action', 'returns', 'return', 'break', 'continue',
        'true', 'false', 'nil', 'NaN', 'inf', 'self', 'myself', 'each', 'super',
        'int', 'float', 'bool', 'string', 'rgb', 'list', 'map', 'point',
        'geometry', 'file', 'container', 'pair', 'matrix',
        'parameter', 'output', 'display', 'monitor', 'layout',
        'type', 'parent', 'skills', 'neighbors', 'width', 'height',
        'number', 'when', 'update', 'max', 'min', 'category',
        'color', 'draw', 'circle', 'square', 'rectangle', 'line',
        'border', 'antialias', 'style', 'background', 'data', 'value',
        'chart', 'series', 'refresh', 'every', 'cycles',
        'among', 'at_distance', 'overlapping', 'inside', 'on',
        'accumulate', 'among', 'count', 'length', 'flip', 'rnd', 'one_of',
        'empty', 'copy_of', 'reverse', 'sort_by', 'min', 'max',
        'neighbors_at', 'neighbors', 'closest_points_with',
        'write', 'save', 'to', 'format', 'rewrite', 'header',
        'species', 'simulations', 'among',
        'envelope', 'shape', 'location', 'speed',
        'method', 'tabu', 'maximize', 'minimize', 'iter_max', 'tabu_list_size',
        'until', 'repeat', 'keep_seed', 'batch',
        'diff', 'solve', 'equation',
        'add', 'remove', 'put', 'match', 'switch', 'default', 'try', 'catch',
        'assert', 'error', 'warn', 'status', 'focus_on', 'highlight',
        'capture', 'restore', 'release', 'migrate', 'diffuse',
        'data', 'text', 'setup',
    ]);

    while (i < s.length) {
        // Skip whitespace
        if (/\s/.test(s[i])) { i++; continue; }

        // Line comment
        if (s[i] === '/' && s[i + 1] === '/') {
            while (i < s.length && s[i] !== '\n') i++;
            continue;
        }

        // Block comment
        if (s[i] === '/' && s[i + 1] === '*') {
            i += 2;
            while (i < s.length - 1 && !(s[i] === '*' && s[i + 1] === '/')) i++;
            i += 2;
            continue;
        }

        // String
        if (s[i] === '"') {
            i++;
            var str = '';
            while (i < s.length && s[i] !== '"') {
                if (s[i] === '\\') { str += s[++i]; } else { str += s[i]; }
                i++;
            }
            i++; // skip closing "
            this.tokens.push({ type: 'STRING', value: str });
            continue;
        }

        // Single-quoted string
        if (s[i] === "'") {
            i++;
            var str2 = '';
            while (i < s.length && s[i] !== "'") {
                if (s[i] === '\\') { str2 += s[++i]; } else { str2 += s[i]; }
                i++;
            }
            i++;
            this.tokens.push({ type: 'STRING', value: str2 });
            continue;
        }

        // Color literal (#name)
        if (s[i] === '#' && /[a-zA-Z]/.test(s[i + 1] || '')) {
            i++;
            var colorName = '';
            while (i < s.length && /[a-zA-Z0-9]/.test(s[i])) { colorName += s[i]; i++; }
            this.tokens.push({ type: 'COLOR', value: '#' + colorName });
            continue;
        }

        // Number
        if (/[0-9]/.test(s[i]) || (s[i] === '.' && /[0-9]/.test(s[i + 1] || ''))) {
            var num = '';
            while (i < s.length && /[0-9.]/.test(s[i])) { num += s[i]; i++; }
            this.tokens.push({ type: 'NUMBER', value: parseFloat(num) });
            continue;
        }

        // Identifier or keyword
        if (/[a-zA-Z_]/.test(s[i])) {
            var id = '';
            while (i < s.length && /[a-zA-Z0-9_]/.test(s[i])) { id += s[i]; i++; }
            if (keywords.has(id)) {
                this.tokens.push({ type: 'KW', value: id });
            } else {
                this.tokens.push({ type: 'ID', value: id });
            }
            continue;
        }

        // Multi-char operators
        if (s[i] === '<' && s[i + 1] === '-') { this.tokens.push({ type: 'OP', value: '<-' }); i += 2; continue; }
        if (s[i] === '-' && s[i + 1] === '>') { this.tokens.push({ type: 'OP', value: '->' }); i += 2; continue; }
        if (s[i] === ':' && s[i + 1] === ':') { this.tokens.push({ type: 'OP', value: '::' }); i += 2; continue; }
        if (s[i] === '<' && s[i + 1] === '=') { this.tokens.push({ type: 'OP', value: '<=' }); i += 2; continue; }
        if (s[i] === '>' && s[i + 1] === '=') { this.tokens.push({ type: 'OP', value: '>=' }); i += 2; continue; }
        if (s[i] === '!' && s[i + 1] === '=') { this.tokens.push({ type: 'OP', value: '!=' }); i += 2; continue; }
        if (s[i] === '=' && s[i + 1] === '=') { this.tokens.push({ type: 'OP', value: '==' }); i += 2; continue; }

        // Single-char tokens
        var single = '{}[]();,?.:+-*/^<>=!%';
        if (single.indexOf(s[i]) >= 0) {
            this.tokens.push({ type: s[i], value: s[i] });
            i++;
            continue;
        }

        // Skip unknown
        i++;
    }

    this.tokens.push({ type: 'EOF', value: null });
};

// ===== Parser =====
function GamlParser(tokens) {
    this.tokens = tokens;
    this.pos = 0;
}

GamlParser.prototype.peek = function () { return this.tokens[this.pos]; };
GamlParser.prototype.next = function () { return this.tokens[this.pos++]; };

GamlParser.prototype.expect = function (type, value) {
    var t = this.next();
    if (t.type !== type || (value !== undefined && t.value !== value)) {
        throw new Error('Parse error: expected ' + type + ' "' + value + '" but got ' + t.type + ' "' + t.value + '" at token ' + (this.pos - 1));
    }
    return t;
};

GamlParser.prototype.match = function (type, value) {
    var t = this.peek();
    if (t.type === type && (value === undefined || t.value === value)) {
        this.pos++;
        return t;
    }
    return null;
};

GamlParser.prototype.matchKW = function (kw) {
    var t = this.peek();
    if (t.type === 'KW' && t.value === kw) { this.pos++; return t; }
    return null;
};

GamlParser.prototype.parse = function () {
    var model = { type: 'Model', name: '', imports: [], sections: [] };

    // Skip pragmas (@...)
    while (this.peek().type === '@') { this.next(); }

    // model name
    if (this.matchKW('model')) {
        model.name = this.next().value;
    }

    // imports
    while (this.matchKW('import')) {
        var imp = { path: this.next().value };
        if (this.matchKW('as')) { imp.alias = this.next().value; }
        model.imports.push(imp);
    }

    // sections
    while (this.peek().type !== 'EOF') {
        if (this.matchKW('global')) {
            model.sections.push(this.parseBlock('global'));
        } else if (this.peek().type === 'KW' && (this.peek().value === 'species' || this.peek().value === 'grid')) {
            model.sections.push(this.parseSpecies());
        } else if (this.matchKW('experiment')) {
            model.sections.push(this.parseExperiment());
        } else {
            this.next(); // skip unknown
        }
    }

    return model;
};

GamlParser.prototype.parseBlock = function (type) {
    var block = { type: type, facets: [], statements: [] };
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
            block.statements.push(this.parseStatement());
        }
        this.match('}');
    } else if (this.peek().type === ';') {
        this.next();
    }
    return block;
};

GamlParser.prototype.parseSpecies = function () {
    var isGrid = this.peek().value === 'grid';
    this.next(); // skip species/grid keyword
    var sp = { type: isGrid ? 'grid' : 'species', name: this.next().value, facets: {}, statements: [] };

    // Parse facets until { or ;
    while (this.peek().type !== '{' && this.peek().type !== ';' && this.peek().type !== 'EOF') {
        var key = this.next().value || this.peek().value;
        // Handle facet keys that are identifiers
        if (this.peek().type === 'ID' || this.peek().type === 'KW') {
            var fk = this.next().value;
            if (this.match(':')) {
                sp.facets[fk] = this.parseExpression();
            } else if (fk === 'parent') {
                // species X parent: Y
                // Actually parent is the facet key, value is next
            }
        } else if (this.match(':')) {
            // facet value after key we already consumed
        } else {
            break;
        }
    }

    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
            sp.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return sp;
};

GamlParser.prototype.parseExperiment = function () {
    var exp = { type: 'experiment', name: this.next().value, facets: {}, statements: [] };

    // Parse facets until { or ;
    while (this.peek().type !== '{' && this.peek().type !== ';' && this.peek().type !== 'EOF') {
        if ((this.peek().type === 'ID' || this.peek().type === 'KW') && this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === ':') {
            var fk = this.next().value;
            this.next(); // skip :
            exp.facets[fk] = this.parseExpression();
        } else {
            break;
        }
    }

    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
            exp.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return exp;
};

GamlParser.prototype.parseStatement = function () {
    var t = this.peek();

    // Variable definition: type name <- expr;
    if (t.type === 'KW' && ['int', 'float', 'bool', 'string', 'rgb', 'list', 'geometry', 'point', 'file', 'container', 'pair', 'matrix', 'map'].indexOf(t.value) >= 0) {
        return this.parseVarDef();
    }

    // var/let name <- expr;
    if (this.matchKW('var') || this.peek().type === 'KW' && this.peek().value === 'let') {
        this.next(); // skip let
        return this.parseVarDef();
    }

    // init block
    if (this.matchKW('init')) {
        return this.parseBlock('init');
    }

    // reflex
    if (this.matchKW('reflex') || this.matchKW('abort')) {
        var rx = { type: 'reflex', name: null, when: null, statements: [] };
        if (this.peek().type === 'ID') { rx.name = this.next().value; }
        if (this.matchKW('when')) {
            this.expect(':', ':');
            rx.when = this.parseExpression();
        }
        if (this.peek().type === '{') {
            this.next();
            while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
                rx.statements.push(this.parseStatement());
            }
            this.match('}');
        } else if (this.peek().type === ';') {
            this.next();
        }
        return rx;
    }

    // if
    if (this.matchKW('if')) {
        var stmt = { type: 'if', condition: null, then: [], elseStmts: [] };
        this.expect('(', '(');
        stmt.condition = this.parseExpression();
        this.expect(')', ')');
        if (this.peek().type === '{') {
            this.next();
            while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
                stmt.then.push(this.parseStatement());
            }
            this.match('}');
        } else {
            stmt.then.push(this.parseStatement());
        }
        if (this.matchKW('else')) {
            if (this.peek().type === '{') {
                this.next();
                while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
                    stmt.elseStmts.push(this.parseStatement());
                }
                this.match('}');
            } else {
                stmt.elseStmts.push(this.parseStatement());
            }
        }
        return stmt;
    }

    // loop
    if (this.matchKW('loop')) {
        var loop = { type: 'loop', name: null, over: null, from: null, to: null, step: null, while_: null, times: null, statements: [] };
        if (this.peek().type === 'ID' && this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value !== ':') {
            loop.name = this.next().value;
        }
        while (this.peek().type !== '{' && this.peek().type !== 'EOF') {
            if (this.matchKW('over')) { this.expect(':', ':'); loop.over = this.parseExpression(); }
            else if (this.matchKW('from')) { this.expect(':', ':'); loop.from = this.parseExpression(); }
            else if (this.matchKW('to')) { this.expect(':', ':'); loop.to = this.parseExpression(); }
            else if (this.matchKW('step')) { this.expect(':', ':'); loop.step = this.parseExpression(); }
            else if (this.matchKW('while')) { this.expect(':', ':'); loop.while_ = this.parseExpression(); }
            else if (this.matchKW('times')) { this.expect(':', ':'); loop.times = this.parseExpression(); }
            else { this.next(); }
        }
        if (this.peek().type === '{') {
            this.next();
            while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
                loop.statements.push(this.parseStatement());
            }
            this.match('}');
        }
        return loop;
    }

    // do
    if (this.matchKW('do') || this.matchKW('invoke')) {
        var doStmt = { type: 'do', target: this.parseExpression(), facets: [] };
        // Parse facets
        while (this.peek().type !== ';' && this.peek().type !== 'EOF') {
            if (this.peek().type === 'ID' && this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === ':') {
                var fk2 = this.next().value;
                this.next();
                doStmt.facets.push({ key: fk2, value: this.parseExpression() });
            } else {
                break;
            }
        }
        this.match(';');
        return doStmt;
    }

    // create
    if (this.matchKW('create')) {
        var create = { type: 'create', species: this.parseExpression(), number: null, facets: [], initStmts: [] };
        while (this.peek().type !== '{' && this.peek().type !== ';' && this.peek().type !== 'EOF') {
            if (this.matchKW('number')) {
                this.expect(':', ':');
                create.number = this.parseExpression();
            } else {
                break;
            }
        }
        if (this.peek().type === '{') {
            this.next();
            while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
                create.initStmts.push(this.parseStatement());
            }
            this.match('}');
        }
        this.match(';');
        return create;
    }

    // ask
    if (this.matchKW('ask')) {
        var ask = { type: 'ask', target: this.parseExpression(), statements: [] };
        if (this.peek().type === '{') {
            this.next();
            while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
                ask.statements.push(this.parseStatement());
            }
            this.match('}');
        } else if (this.peek().type === ';') {
            this.next();
        }
        return ask;
    }

    // return
    if (this.matchKW('return')) {
        var ret = { type: 'return', value: this.parseExpression() };
        this.match(';');
        return ret;
    }

    // write
    if (this.matchKW('write')) {
        var wr = { type: 'write', value: this.parseExpression() };
        this.match(';');
        return wr;
    }

    // action
    if (this.matchKW('action')) {
        var act = { type: 'action', name: this.next().value, params: [], statements: [] };
        if (this.match('(')) {
            while (this.peek().type !== ')' && this.peek().type !== 'EOF') {
                var pType = this.next().value;
                var pName = this.next().value;
                act.params.push({ type: pType, name: pName });
                this.match(',');
            }
            this.expect(')', ')');
        }
        if (this.peek().type === '{') {
            this.next();
            while (this.peek().type !== '}' && this.peek().type !== 'EOF') {
                act.statements.push(this.parseStatement());
            }
            this.match('}');
        }
        return act;
    }

    // display / output / parameter / monitor / layout / chart — skip for transpilation
    if (this.matchKW('display') || this.matchKW('output') || this.matchKW('parameter') ||
        this.matchKW('monitor') || this.matchKW('layout') || this.matchKW('chart') ||
        this.matchKW('data') || this.matchKW('save') || this.matchKW('equation') ||
        this.matchKW('solve') || this.matchKW('method') || this.matchKW('switch')) {
        // Skip to matching { or ;
        var depth = 0;
        while (this.peek().type !== 'EOF') {
            if (this.peek().type === '{') { depth++; }
            if (this.peek().type === '}') { if (depth === 0) { this.next(); break; } depth--; }
            if (this.peek().type === ';' && depth === 0) { this.next(); break; }
            this.next();
        }
        return { type: 'skip' };
    }

    // Expression statement (e.g., assignment: var <- expr)
    var expr = this.parseExpression();
    if (this.peek().type === 'OP' && this.peek().value === '<-') {
        this.next();
        var val = this.parseExpression();
        this.match(';');
        return { type: 'assign', target: expr, value: val };
    }
    if (this.peek().type === 'OP' && this.peek().value === '<<') {
        this.next();
        var val2 = this.parseExpression();
        this.match(';');
        return { type: 'add_to', target: expr, value: val2 };
    }
    // Could be a facet key followed by colon
    if (this.peek().type === ':') {
        // This is actually a facet in a context we shouldn't be parsing expressions
        // Skip it
        this.next();
        this.parseExpression();
        return { type: 'skip' };
    }
    this.match(';');
    return { type: 'expr_stmt', expr: expr };
};

GamlParser.prototype.parseVarDef = function () {
    var typeTok = this.next(); // type keyword

    // Skip generic type parameters: list<si_grid>, map<string,int>, etc.
    if ((typeTok.value === 'list' || typeTok.value === 'map' || typeTok.value === 'pair') && this.peek().type === '<') {
        var depth = 0;
        while (this.peek().type !== 'EOF') {
            if (this.peek().type === '<') depth++;
            if (this.peek().type === '>') { depth--; if (depth === 0) { this.next(); break; } }
            this.next();
        }
    }

    var name = this.next().value;
    var def = { type: 'vardef', varType: typeTok.value, name: name, init: null, derived: false, update: null, max: null };

    if (this.match('-') && this.peek().type === '>') {
        // Actually this is the <- operator being parsed as - and >
    }

    // Check for <- (assignment)
    if (this.peek().type === 'OP' && this.peek().value === '<-') {
        this.next();
        def.init = this.parseExpression();
    } else if (this.peek().type === 'OP' && this.peek().value === '->') {
        // Derived variable
        this.next();
        def.init = this.parseExpression();
        def.derived = true;
    }

    // Parse facets: update:, max:, min:, etc.
    while (this.peek().type === 'ID' || this.peek().type === 'KW') {
        var peekVal = this.peek().value;
        if (peekVal === 'update' || peekVal === 'max' || peekVal === 'min') {
            this.next();
            if (this.match(':')) {
                var facetVal = this.parseExpression();
                if (peekVal === 'update') def.update = facetVal;
                if (peekVal === 'max') def.max = facetVal;
                if (peekVal === 'min') def.min = facetVal;
            }
        } else {
            break;
        }
    }

    this.match(';');
    return def;
};

GamlParser.prototype.parseExpression = function () {
    return this.parseTernary();
};

GamlParser.prototype.parseTernary = function () {
    var expr = this.parseOr();
    if (this.peek().type === '?') {
        this.next();
        var trueExpr = this.parseExpression();
        this.expect(':', ':');
        var falseExpr = this.parseExpression();
        return { type: 'ternary', cond: expr, trueExpr: trueExpr, falseExpr: falseExpr };
    }
    return expr;
};

GamlParser.prototype.parseOr = function () {
    var left = this.parseAnd();
    while (this.peek().type === 'KW' && this.peek().value === 'or') {
        this.next();
        left = { type: 'binary', op: 'or', left: left, right: this.parseAnd() };
    }
    return left;
};

GamlParser.prototype.parseAnd = function () {
    var left = this.parseComparison();
    while (this.peek().type === 'KW' && this.peek().value === 'and') {
        this.next();
        left = { type: 'binary', op: 'and', left: left, right: this.parseComparison() };
    }
    return left;
};

GamlParser.prototype.parseComparison = function () {
    var left = this.parseAddSub();
    while (this.peek().type === 'OP' && ['==', '!=', '<', '>', '<=', '>='].indexOf(this.peek().value) >= 0) {
        var op = this.next().value;
        left = { type: 'binary', op: op, left: left, right: this.parseAddSub() };
    }
    return left;
};

GamlParser.prototype.parseAddSub = function () {
    var left = this.parseMulDiv();
    while (this.peek().type === 'OP' && (this.peek().value === '+' || this.peek().value === '-')) {
        var op = this.next().value;
        left = { type: 'binary', op: op, left: left, right: this.parseMulDiv() };
    }
    return left;
};

GamlParser.prototype.parseMulDiv = function () {
    var left = this.parsePower();
    while (this.peek().type === 'OP' && (this.peek().value === '*' || this.peek().value === '/')) {
        var op = this.next().value;
        left = { type: 'binary', op: op, left: left, right: this.parsePower() };
    }
    return left;
};

GamlParser.prototype.parsePower = function () {
    var left = this.parseUnary();
    if (this.peek().type === 'OP' && this.peek().value === '^') {
        this.next();
        return { type: 'binary', op: '^', left: left, right: this.parseUnary() };
    }
    return left;
};

GamlParser.prototype.parseUnary = function () {
    if (this.peek().type === 'OP' && this.peek().value === '-') {
        this.next();
        return { type: 'unary', op: '-', expr: this.parseUnary() };
    }
    if (this.peek().type === 'OP' && this.peek().value === '!') {
        this.next();
        return { type: 'unary', op: '!', expr: this.parseUnary() };
    }
    if (this.peek().type === 'KW' && this.peek().value === 'not') {
        this.next();
        return { type: 'unary', op: 'not', expr: this.parseUnary() };
    }
    return this.parseAccess();
};

GamlParser.prototype.parseAccess = function () {
    var base = this.parsePrimary();
    while (true) {
        if (this.peek().type === '[') {
            this.next();
            var idx = this.parseExpression();
            this.expect(']', ']');
            base = { type: 'access', base: base, index: idx };
        } else if (this.peek().type === '.') {
            this.next();
            var field = this.next().value;
            if (this.peek().type === '(') {
                // Method call
                this.next();
                var args = [];
                if (this.peek().type !== ')') {
                    args.push(this.parseExpression());
                    while (this.match(',')) { args.push(this.parseExpression()); }
                }
                this.expect(')', ')');
                base = { type: 'methodcall', base: base, method: field, args: args };
            } else {
                base = { type: 'field', base: base, field: field };
            }
        } else if (this.peek().type === 'KW' && this.peek().value === 'count') {
            // GAML pattern: Species count (predicate) -> count(species, predicate)
            this.next();
            this.expect('(', '(');
            var predicate = this.parseExpression();
            this.expect(')', ')');
            base = { type: 'func', name: 'count_filtered', args: [base, predicate] };
        } else if (this.peek().type === 'KW' && this.peek().value === 'as') {
            // Type cast: expr as type -> just return the expr (type info not needed at runtime)
            this.next();
            this.next(); // skip type name
            // Also handle list<type> after as
            if (this.peek().type === '<') {
                var d2 = 0;
                while (this.peek().type !== 'EOF') {
                    if (this.peek().type === '<') d2++;
                    if (this.peek().type === '>') { d2--; if (d2 === 0) { this.next(); break; } }
                    this.next();
                }
            }
            // base stays the same — just ignore the cast
        } else if (this.peek().type === 'KW' && this.peek().value === 'accumulate') {
            // accumulate(expr)
            this.next();
            this.expect('(', '(');
            var accExpr = this.parseExpression();
            this.expect(')', ')');
            base = { type: 'func', name: 'accumulate', args: [base, accExpr] };
        } else {
            break;
        }
    }
    return base;
};

GamlParser.prototype.parsePrimary = function () {
    var t = this.peek();

    // Number
    if (t.type === 'NUMBER') {
        this.next();
        return { type: 'number', value: t.value };
    }

    // String
    if (t.type === 'STRING') {
        this.next();
        return { type: 'string', value: t.value };
    }

    // Color
    if (t.type === 'COLOR') {
        this.next();
        return { type: 'color', value: t.value };
    }

    // Boolean / nil / self / etc.
    if (t.type === 'KW' && t.value === 'true') { this.next(); return { type: 'bool', value: true }; }
    if (t.type === 'KW' && t.value === 'false') { this.next(); return { type: 'bool', value: false }; }
    if (t.type === 'KW' && t.value === 'nil') { this.next(); return { type: 'nil' }; }
    if (t.type === 'KW' && t.value === 'self') { this.next(); return { type: 'self' }; }
    if (t.type === 'KW' && t.value === 'myself') { this.next(); return { type: 'myself' }; }
    if (t.type === 'KW' && t.value === 'each') { this.next(); return { type: 'each' }; }

    // Parenthesized expression
    if (t.type === '(') {
        this.next();
        var expr = this.parseExpression();
        this.expect(')', ')');
        return expr;
    }

    // Array [expr, expr, ...]
    if (t.type === '[') {
        this.next();
        var items = [];
        if (this.peek().type !== ']') {
            items.push(this.parseExpression());
            while (this.match(',')) { items.push(this.parseExpression()); }
        }
        this.expect(']', ']');
        return { type: 'array', items: items };
    }

    // Point {expr, expr}
    if (t.type === '{') {
        this.next();
        var coords = [];
        if (this.peek().type !== '}') {
            coords.push(this.parseExpression());
            while (this.match(',')) { coords.push(this.parseExpression()); }
        }
        this.expect('}', '}');
        return { type: 'point', coords: coords };
    }

    // Function call or variable ref
    if (t.type === 'ID' || (t.type === 'KW' && ['length', 'flip', 'rnd', 'one_of', 'empty', 'among',
        'min', 'max', 'copy_of', 'reverse', 'sort_by', 'accumulate',
        'envelope', 'square', 'circle', 'rgb', 'list',
        'at_distance', 'overlapping', 'inside', 'count',
        'neighbors_at', 'neighbors', 'closest_points_with',
        'distance_to', 'abs', 'sqrt', 'sin', 'cos', 'tan', 'exp', 'ln', 'log',
        'round', 'floor', 'ceil', 'int', 'float', 'string',
        'date', 'time', 'cycle', 'current_date',
        'write', 'save', 'to', 'format', 'rewrite', 'header',
    ].indexOf(t.value) >= 0)) {
        this.next();
        var name = t.value;

        // Check for list<type> cast
        if (name === 'list' && this.peek().type === '<') {
            this.next(); // skip <
            this.next(); // skip type
            this.expect('>', '>');
        }

        // Function call
        if (this.peek().type === '(') {
            this.next();
            var args2 = [];
            if (this.peek().type !== ')') {
                args2.push(this.parseExpression());
                while (this.match(',')) { args2.push(this.parseExpression()); }
            }
            this.expect(')', ')');

            // Handle 'as' postfix: expr as type
            if (this.peek().type === 'KW' && this.peek().value === 'as') {
                this.next();
                this.next(); // skip type name
                return { type: 'func', name: name, args: args2 };
            }

            return { type: 'func', name: name, args: args2 };
        }

        // Variable reference
        return { type: 'var', name: name };
    }

    // Fallback: skip token
    this.next();
    return { type: 'unknown', value: t.value };
};


// ============================================================
// TRANSPILER — AST -> JavaScript code
// ============================================================

function GamlTranspiler() {
    this.speciesCount = 0;
}

GamlTranspiler.prototype.transpile = function (ast) {
    var js = '';

    // Find global block
    var globalBlock = null;
    var species = [];
    var experiments = [];

    for (var i = 0; i < ast.sections.length; i++) {
        var s = ast.sections[i];
        if (s.type === 'global') globalBlock = s;
        else if (s.type === 'species' || s.type === 'grid') species.push(s);
        else if (s.type === 'experiment') experiments.push(s);
    }

    // Generate species definitions
    for (var j = 0; j < species.length; j++) {
        js += this._genSpecies(species[j]) + '\n';
    }

    // Generate global/world
    js += this._genGlobal(globalBlock) + '\n';

    // Generate init function
    js += 'function runSimulation(runtime) {\n';
    js += '  var sim = new Simulation(runtime);\n';
    js += '  sim.world = runtime.species["world"].createAgent(sim);\n';
    js += '  sim.addAgent("world", sim.world);\n';

    // Execute world init
    if (globalBlock) {
        for (var k = 0; k < globalBlock.statements.length; k++) {
            var st = globalBlock.statements[k];
            if (st.type === 'init') {
                js += '  // world init\n';
                for (var m = 0; m < st.statements.length; m++) {
                    js += '  ' + this._genStatement(st.statements[m]) + '\n';
                }
            }
        }
    }

    js += '  return sim;\n';
    js += '}\n';

    return js;
};

GamlTranspiler.prototype._genSpecies = function (sp) {
    var name = sp.name;
    var isGrid = sp.type === 'grid';
    var indent = '  ';
    var code = '';

    code += indent + '// Species: ' + name + '\n';
    code += indent + '(function() {\n';
    code += indent + '  var def = new SpeciesDef("' + name + '");\n';
    code += indent + '  def.isGrid = ' + isGrid + ';\n';

    // Grid dimensions
    if (isGrid) {
        var w = sp.facets['width'] || sp.facets['width:'] || 50;
        var h = sp.facets['height'] || sp.facets['height:'] || 50;
        code += indent + '  def.gridWidth = ' + (typeof w === 'object' && w.type === 'number' ? w.value : 50) + ';\n';
        code += indent + '  def.gridHeight = ' + (typeof h === 'object' && h.type === 'number' ? h.value : 50) + ';\n';
    }

    // Parent
    var parentFacet = sp.facets['parent'];
    if (parentFacet) {
        code += indent + '  def.parentName = ' + JSON.stringify(this._exprToJS(parentFacet)) + ';\n';
    }

    // Process statements
    for (var i = 0; i < sp.statements.length; i++) {
        var stmt = sp.statements[i];
        if (stmt.type === 'vardef') {
            code += indent + '  def.varDefs[' + JSON.stringify(stmt.name) + '] = { default: ' + this._varDefaultJS(stmt) + ' };\n';
            if (stmt.derived) {
                code += indent + '  def.derivedVars.push({ name: ' + JSON.stringify(stmt.name) + ', compute: function() { return ' + this._exprToJS(stmt.init) + '; } });\n';
            }
        } else if (stmt.type === 'init') {
            code += indent + '  def.initFn = function() {\n';
            for (var j = 0; j < stmt.statements.length; j++) {
                code += indent + '    ' + this._genStatement(stmt.statements[j]) + '\n';
            }
            code += indent + '  };\n';
        } else if (stmt.type === 'reflex') {
            code += indent + '  def.reflexDefs.push({ name: ' + JSON.stringify(stmt.name || 'anon') + ', ';
            code += 'whenFn: ' + (stmt.when ? 'function() { return ' + this._exprToJS(stmt.when) + '; }' : 'null') + ', ';
            code += 'fn: function() {\n';
            for (var k = 0; k < stmt.statements.length; k++) {
                code += indent + '    ' + this._genStatement(stmt.statements[k]) + '\n';
            }
            code += indent + '  } });\n';
        } else if (stmt.type === 'action') {
            code += indent + '  def.actionDefs[' + JSON.stringify(stmt.name) + '] = function(';
            code += stmt.params.map(function (p) { return p.name; }).join(', ');
            code += ') {\n';
            for (var l = 0; l < stmt.statements.length; l++) {
                code += indent + '    ' + this._genStatement(stmt.statements[l]) + '\n';
            }
            code += indent + '  };\n';
        } else if (stmt.type === 'skip' || stmt.type === 'display' || stmt.type === 'output' || stmt.type === 'parameter') {
            // Skip display/output/parameter blocks
        }
    }

    // Register the species
    code += indent + '  runtime.species[' + JSON.stringify(name) + '] = def;\n';
    code += indent + '  runtime.speciesOrder.push(' + JSON.stringify(name) + ');\n';
    code += indent + '})();\n';

    return code;
};

GamlTranspiler.prototype._genGlobal = function (globalBlock) {
    var indent = '  ';
    var code = '';

    code += '// Global / World\n';
    code += '(function() {\n';
    code += indent + 'var def = new SpeciesDef("world");\n';

    if (globalBlock) {
        for (var i = 0; i < globalBlock.statements.length; i++) {
            var stmt = globalBlock.statements[i];
            if (stmt.type === 'vardef') {
                if (!stmt.derived) {
                    code += indent + 'def.varDefs[' + JSON.stringify(stmt.name) + '] = { default: ' + this._varDefaultJS(stmt) + ' };\n';
                } else {
                    code += indent + 'def.derivedVars.push({ name: ' + JSON.stringify(stmt.name) + ', compute: function() { return ' + this._exprToJS(stmt.init) + '; } });\n';
                }
            } else if (stmt.type === 'reflex') {
                code += indent + 'def.reflexDefs.push({ name: ' + JSON.stringify(stmt.name || 'anon') + ', ';
                code += 'whenFn: ' + (stmt.when ? 'function() { return ' + this._exprToJS(stmt.when) + '; }' : 'null') + ', ';
                code += 'fn: function() {\n';
                for (var j = 0; j < stmt.statements.length; j++) {
                    code += indent + '  ' + this._genStatement(stmt.statements[j]) + '\n';
                }
                code += indent + '} });\n';
            }
        }
    }

    code += indent + 'runtime.species["world"] = def;\n';
    code += indent + 'runtime.speciesOrder.push("world");\n';
    code += '})();\n';

    return code;
};

GamlTranspiler.prototype._varDefaultJS = function (stmt) {
    if (stmt.init) return this._exprToJS(stmt.init);
    // Default values by type
    switch (stmt.varType) {
        case 'int': return '0';
        case 'float': return '0.0';
        case 'bool': return 'false';
        case 'string': return '""';
        case 'rgb': return '"#808080"';
        default: return 'null';
    }
};

GamlTranspiler.prototype._genStatement = function (stmt) {
    switch (stmt.type) {
        case 'vardef':
            return 'var ' + stmt.name + ' = ' + (stmt.init ? this._exprToJS(stmt.init) : 'null') + ';';
        case 'assign':
            return this._exprToJS(stmt.target) + ' = ' + this._exprToJS(stmt.value) + ';';
        case 'if':
            var code = 'if (' + this._exprToJS(stmt.condition) + ') {\n';
            for (var i = 0; i < stmt.then.length; i++) {
                code += '  ' + this._genStatement(stmt.then[i]) + '\n';
            }
            code += '}';
            if (stmt.elseStmts.length > 0) {
                code += ' else {\n';
                for (var j = 0; j < stmt.elseStmts.length; j++) {
                    code += '  ' + this._genStatement(stmt.elseStmts[j]) + '\n';
                }
                code += '}';
            }
            return code;
        case 'loop':
            return this._genLoop(stmt);
        case 'do':
            var target = this._exprToJS(stmt.target);
            // Handle built-in actions
            if (stmt.target && stmt.target.type === 'var') {
                if (stmt.target.name === 'die') return 'this.die();';
                if (stmt.target.name === 'pause') return 'this._sim.pause();';
                if (stmt.target.name === 'wander') return 'this.wander();';
            }
            // Method call on self
            return 'if (this.' + target + ') this.' + target + '(); else { /* do ' + target + ' */ }';
        case 'create':
            var spExpr = this._exprToJS(stmt.species);
            var numExpr = stmt.number ? this._exprToJS(stmt.number) : '1';
            var code2 = 'this._sim.createAgents(' + spExpr + ', ' + numExpr + ', function(ag) {\n';
            for (var k = 0; k < stmt.initStmts.length; k++) {
                code2 += '  ag.' + this._genStatement(stmt.initStmts[k]) + '\n';
            }
            code2 += '});';
            return code2;
        case 'ask':
            var targetExpr = this._exprToJS(stmt.target);
            // Handle "ask X among Y" or "ask Y at_distance Z"
            var code3 = '{ ';
            code3 += 'var _askTarget = ' + targetExpr + ';\n';
            code3 += 'var _askList = _askTarget;\n';
            code3 += 'if (typeof _askTarget === "number") { _askList = this._sim.populations[this._species] || []; }\n';
            code3 += 'var _askAgents = Array.isArray(_askList) ? _askList : (_askList ? [_askList] : []);\n';
            code3 += 'for (var _ai = 0; _ai < _askAgents.length; _ai++) {\n';
            code3 += '  var _a = _askAgents[_ai];\n';
            for (var l = 0; l < stmt.statements.length; l++) {
                code3 += '  ' + this._genStatement(stmt.statements[l]) + '\n';
            }
            code3 += '} }';
            return code3;
        case 'return':
            return 'return ' + (stmt.value ? this._exprToJS(stmt.value) : '') + ';';
        case 'write':
            return 'console.log(' + this._exprToJS(stmt.value) + ');';
        case 'expr_stmt':
            return this._exprToJS(stmt.expr) + ';';
        case 'skip':
            return '';
        default:
            return '/* unknown: ' + stmt.type + ' */';
    }
};

GamlTranspiler.prototype._genLoop = function (stmt) {
    var code = '';
    if (stmt.over) {
        // loop X over: collection
        var loopVar = stmt.name || '_loopVar';
        var coll = this._exprToJS(stmt.over);
        code += 'var _coll = ' + coll + ';\n';
        code += 'for (var _li = 0; _li < _coll.length; _li++) {\n';
        code += '  var ' + loopVar + ' = _coll[_li];\n';
        for (var i = 0; i < stmt.statements.length; i++) {
            code += '  ' + this._genStatement(stmt.statements[i]) + '\n';
        }
        code += '}';
    } else if (stmt.while_) {
        code += 'while (' + this._exprToJS(stmt.while_) + ') {\n';
        for (var j = 0; j < stmt.statements.length; j++) {
            code += '  ' + this._genStatement(stmt.statements[j]) + '\n';
        }
        code += '}';
    } else if (stmt.times) {
        var timesExpr = this._exprToJS(stmt.times);
        code += 'for (var _ti = 0; _ti < ' + timesExpr + '; _ti++) {\n';
        for (var k = 0; k < stmt.statements.length; k++) {
            code += '  ' + this._genStatement(stmt.statements[k]) + '\n';
        }
        code += '}';
    } else if (stmt.from !== null && stmt.to !== null) {
        var loopVar2 = stmt.name || '_i';
        var fromExpr = this._exprToJS(stmt.from);
        var toExpr = this._exprToJS(stmt.to);
        var stepExpr = stmt.step ? this._exprToJS(stmt.step) : '1';
        code += 'for (var ' + loopVar2 + ' = ' + fromExpr + '; ' + loopVar2 + ' <= ' + toExpr + '; ' + loopVar2 + ' += ' + stepExpr + ') {\n';
        for (var l = 0; l < stmt.statements.length; l++) {
            code += '  ' + this._genStatement(stmt.statements[l]) + '\n';
        }
        code += '}';
    }
    return code;
};

GamlTranspiler.prototype._exprToJS = function (expr) {
    if (!expr) return 'null';
    switch (expr.type) {
        case 'number': return String(expr.value);
        case 'string': return JSON.stringify(expr.value);
        case 'bool': return expr.value ? 'true' : 'false';
        case 'color': return JSON.stringify(expr.value);
        case 'nil': return 'null';
        case 'self': return 'this';
        case 'myself': return 'this'; // simplified
        case 'each': return '_each';
        case 'var': return this._varRef(expr.name);
        case 'binary':
            var l = this._exprToJS(expr.left);
            var r = this._exprToJS(expr.right);
            if (expr.op === 'and') return '(' + l + ' && ' + r + ')';
            if (expr.op === 'or') return '(' + l + ' || ' + r + ')';
            if (expr.op === '=') return '(' + l + ' === ' + r + ')';
            return '(' + l + ' ' + expr.op + ' ' + r + ')';
        case 'unary':
            if (expr.op === 'not') return '(!' + this._exprToJS(expr.expr) + ')';
            return '(' + expr.op + this._exprToJS(expr.expr) + ')';
        case 'ternary':
            return '(' + this._exprToJS(expr.cond) + ' ? ' + this._exprToJS(expr.trueExpr) + ' : ' + this._exprToJS(expr.falseExpr) + ')';
        case 'func': return this._funcToJS(expr);
        case 'field':
            var base = this._exprToJS(expr.base);
            return base + '.' + expr.field;
        case 'access':
            return this._exprToJS(expr.base) + '[' + this._exprToJS(expr.index) + ']';
        case 'array':
            return '[' + expr.items.map(this._exprToJS.bind(this)).join(', ') + ']';
        case 'point':
            return '{ x: ' + expr.coords.map(this._exprToJS.bind(this)).join(', y: ') + ' }';
        case 'methodcall':
            var mb = this._exprToJS(expr.base);
            var margs = expr.args.map(this._exprToJS.bind(this)).join(', ');
            return mb + '.' + expr.method + '(' + margs + ')';
        case 'actioncall':
            return this._exprToJS(expr.base) + '.' + expr.method + '()';
        default:
            return '/* ?' + expr.type + ' */';
    }
};

GamlTranspiler.prototype._varRef = function (name) {
    // Map GAML built-in variables to runtime
    if (name === 'self') return 'this';
    if (name === 'myself') return 'this';
    if (name === 'cycle') return 'this._sim.cycle';
    if (name === 'time') return 'this._sim.cycle';
    if (name === 'location') return 'this.location';
    if (name === 'color') return 'this.color';
    if (name === 'size') return 'this.size';
    if (name === 'speed') return 'this.speed';
    if (name === 'shape') return 'this._sim.world.shape';
    // Species name as collection reference -> population
    if (this._currentSpecies !== name) {
        // Could be a species reference used as collection
        return 'this._sim.populations[' + JSON.stringify(name) + '] || []';
    }
    return 'this.' + name;
};

GamlTranspiler.prototype._funcToJS = function (expr) {
    var name = expr.name;
    var args = expr.args.map(this._exprToJS.bind(this));

    // Resolve species name arguments to populations
    var resolveSpecies = function(argStr) {
        return '(typeof ' + argStr + ' === "string" ? (this._sim.populations[' + argStr + '] || []) : ' + argStr + ')';
    };

    // Built-in functions
    switch (name) {
        case 'length': return '(' + args[0] + ').length';
        case 'flip': return 'this._runtime.flip(' + args[0] + ')';
        case 'rnd': return args.length > 1 ? 'this._runtime.rnd(' + args.join(', ') + ')' : 'this._runtime.rnd(' + args[0] + ')';
        case 'one_of': return 'this._runtime.one_of(' + args[0] + ')';
        case 'empty': return '(!' + args[0] + ' || ' + args[0] + '.length === 0)';
        case 'among': return 'this._runtime.shuffle(' + args[1] + ').slice(0, ' + args[0] + ')';
        case 'count':
            // count(expr) counts truthy items in current species population
            return '(this._sim.populations[this._species] || []).filter(function(a) { return ' + args[0] + '; }).length';
        case 'count_filtered':
            // Species count (predicate) -> count items in species matching predicate
            return '(function() { var _pop = ' + args[0] + '; if (!Array.isArray(_pop)) _pop = []; return _pop.filter(function(a) { return ' + args[1] + '; }).length; })()';
        case 'accumulate':
            return '(function() { var _b = ' + args[0] + '; if (!Array.isArray(_b)) _b = [_b]; return _b; })()';
        case 'min': return args.length > 1 ? 'Math.min(' + args.join(', ') + ')' : 'this._runtime.min_list(' + args[0] + ')';
        case 'max': return args.length > 1 ? 'Math.max(' + args.join(', ') + ')' : 'this._runtime.max_list(' + args[0] + ')';
        case 'abs': return 'Math.abs(' + args[0] + ')';
        case 'sqrt': return 'Math.sqrt(' + args[0] + ')';
        case 'sin': return 'Math.sin(' + args[0] + ')';
        case 'cos': return 'Math.cos(' + args[0] + ')';
        case 'tan': return 'Math.tan(' + args[0] + ')';
        case 'exp': return 'Math.exp(' + args[0] + ')';
        case 'ln': return 'Math.log(' + args[0] + ')';
        case 'round': return 'Math.round(' + args[0] + ')';
        case 'floor': return 'Math.floor(' + args[0] + ')';
        case 'ceil': return 'Math.ceil(' + args[0] + ')';
        case 'int': return 'Math.floor(' + args[0] + ')';
        case 'float': return 'parseFloat(' + args[0] + ')';
        case 'string': return 'String(' + args[0] + ')';
        case 'copy_of': return 'JSON.parse(JSON.stringify(' + args[0] + '))';
        case 'reverse': return '(' + args[0] + ').slice().reverse()';
        case 'sort_by': return '(' + args[0] + ').slice().sort(function(a,b) { return ' + args[1] + ' - ' + args[1].replace(/this/g, 'b') + '; })';
        case 'envelope': return '{ width: 500, height: 500 }';
        case 'square': return '{ width: ' + args[0] + ', height: ' + args[0] + ' }';
        case 'circle': return '{ radius: ' + args[0] + ' }';
        case 'rgb': return '"rgb(" + ' + args.join(' + "," + ') + ' + ")"';
        case 'at_distance':
            // Filter agents within distance
            return '(function() { var _d = ' + args[1] + '; var _pop = this._sim.populations[this._species] || []; return _pop.filter(function(a) { var dx = this.location.x - a.location.x; var dy = this.location.y - a.location.y; return Math.sqrt(dx*dx+dy*dy) <= _d; }.bind(this)); }.bind(this))()';
        case 'overlapping':
            return '(function() { var _s = ' + args[0] + '; return Array.isArray(_s) ? _s.slice() : (this._sim.populations[String(_s)] || []).slice(); }.bind(this))()';
        case 'inside':
            return '(function() { var _s = ' + args[0] + '; return Array.isArray(_s) ? _s.slice() : (this._sim.populations[String(_s)] || []).slice(); }.bind(this))()';
        case 'neighbors_at':
            return '(this._sim.populations[this._species] || []).slice(0, ' + args[0] + ')';
        case 'write': return 'console.log(' + args.join(', ') + ')';
        default:
            // Check if it's a species reference used as function (count-like)
            if (this._currentSpecies === name) {
                return '(this._sim.populations[this._species] || [])';
            }
            return '/* ' + name + '(' + args.join(', ') + ') */';
    }
};

// ============================================================
// PUBLIC API — Parse, transpile, and run a GAML model
// ============================================================

function compileGaml(source) {
    // Tokenize
    var tokenizer = new GamlTokenizer(source);

    // Parse
    var parser = new GamlParser(tokenizer.tokens);
    var ast = parser.parse();

    // Transpile
    var transpiler = new GamlTranspiler();
    var jsCode = transpiler.transpile(ast);

    return { ast: ast, jsCode: jsCode };
}

function runGamlModel(source, outputCallback) {
    var runtime = new GamaRuntime();

    // Compile
    var compiled = compileGaml(source);

    // Execute the generated JS to register species
    try {
        var fn = new Function('runtime', 'Simulation', 'SpeciesDef', 'Agent',
            compiled.jsCode + '\nreturn runSimulation(runtime);');
        var sim = fn(runtime, Simulation, SpeciesDef, Agent);
        runtime.simulation = sim;
        runtime.outputCallback = outputCallback;
        return sim;
    } catch (e) {
        console.error('GAML transpilation/runtime error:', e);
        console.error('Generated JS:\n', compiled.jsCode);
        throw e;
    }
}

// Make available globally
window.GamaRuntime = GamaRuntime;
window.Simulation = Simulation;
window.Agent = Agent;
window.SpeciesDef = SpeciesDef;
window.GamlTokenizer = GamlTokenizer;
window.GamlParser = GamlParser;
window.GamlTranspiler = GamlTranspiler;
window.compileGaml = compileGaml;
window.runGamlModel = runGamlModel;
