/**
 * GAMA Runtime — Simulation Engine
 * Manages world state, agents, populations, and simulation lifecycle.
 * Inspired by NetLogo Tortoise engine (World, Turtle, Patch, Updater).
 */

// ============================================================
// RNG — Seeded random number generator (mulberry32)
// ============================================================
var GAMA_COLORS = {
    '#red': '#ff0000', '#blue': '#0000ff', '#green': '#00ff00', '#yellow': '#ffff00',
    '#orange': '#ffa500', '#purple': '#800080', '#pink': '#ffc0cb', '#brown': '#a52a2a',
    '#white': '#ffffff', '#black': '#000000', '#gray': '#808080', '#grey': '#808080',
    '#cyan': '#00ffff', '#magenta': '#ff00ff', '#lime': '#00ff00', '#maroon': '#800000',
    '#navy': '#000080', '#teal': '#008080', '#olive': '#808000', '#silver': '#c0c0c0',
};

function gamaResolveColor(c) {
    if (!c) return '#808080';
    if (GAMA_COLORS[c]) return GAMA_COLORS[c];
    return c; // pass through CSS colors like #ff0000
}

// ============================================================
function GamaRNG(seed) {
    this.seed = seed || Date.now();
    this._state = this.seed;
}
GamaRNG.prototype.next = function () {
    var t = this._state += 0x6D2B79F5;
    t = Math.imul(t ^ t >>> 15, t | 1);
    t ^= t + Math.imul(t ^ t >>> 7, t | 61);
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
};
GamaRNG.prototype.between = function (a, b) { return a + this.next() * (b - a); };
GamaRNG.prototype.rnd = function (a, b) {
    if (b === undefined) { b = a; a = 0; }
    return a + Math.floor(this.next() * (b - a + 1));
};
GamaRNG.prototype.flip = function (p) { return this.next() < p; };
GamaRNG.prototype.one_of = function (list) {
    if (!list || list.length === 0) return null;
    return list[Math.floor(this.next() * list.length)];
};
GamaRNG.prototype.shuffle = function (list) {
    var arr = list.slice();
    for (var i = arr.length - 1; i > 0; i--) {
        var j = Math.floor(this.next() * (i + 1));
        var tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
    }
    return arr;
};
GamaRNG.prototype.min_list = function (list) {
    var m = Infinity;
    for (var i = 0; i < list.length; i++) if (list[i] < m) m = list[i];
    return m;
};
GamaRNG.prototype.max_list = function (list) {
    var m = -Infinity;
    for (var i = 0; i < list.length; i++) if (list[i] > m) m = list[i];
    return m;
};

// ============================================================
// Agent — Base agent class
// ============================================================
function Agent(speciesName) {
    this._species = speciesName;
    this._sim = null;
    this._rng = null;
    this._dead = false;
    this._reflexFns = [];
    this.x = 0;
    this.y = 0;
    this.color = '#4ec9b0';
    this.size = 1;
    this.speed = 1;
    this.shape = 'circle';
    this.location = { x: 0, y: 0 };
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
    if (this._sim) this._sim._scheduleRemove(this._species, this);
};

Agent.prototype.wander = function (speed) {
    var dist = (typeof speed === 'number') ? speed : this.speed;
    var angle = this._rng.next() * Math.PI * 2;
    this.x += Math.cos(angle) * dist;
    this.y += Math.sin(angle) * dist;
    this.location.x = this.x;
    this.location.y = this.y;
    // Clamp to world bounds
    if (this._sim && this._sim.world) {
        var w = this._sim.world.width || 50;
        var h = this._sim.world.height || 50;
        var halfW = w / 2;
        var halfH = h / 2;
        this.x = Math.max(-halfW, Math.min(halfW, this.x));
        this.y = Math.max(-halfH, Math.min(halfH, this.y));
        this.location.x = this.x;
        this.location.y = this.y;
    }
};

Agent.prototype.distance_to = function (other) {
    var dx = this.x - other.x;
    var dy = this.y - other.y;
    return Math.sqrt(dx * dx + dy * dy);
};

Agent.prototype.set_location = function (loc) {
    if (loc && typeof loc.x === 'number') {
        this.x = loc.x;
        this.y = loc.y;
        this.location.x = loc.x;
        this.location.y = loc.y;
    }
};

// ============================================================
// World — Global agent (holds global variables, like NetLogo Observer)
// ============================================================
function World() {
    this._sim = null;
    this._rng = null;
    this._reflexFns = [];
    this.width = 50;
    this.height = 50;
    this.shape = null; // will be set to { width, height }
    this.name = '';
}

World.prototype._executeReflexes = function () {
    for (var i = 0; i < this._reflexFns.length; i++) {
        var rx = this._reflexFns[i];
        if (rx.whenFn && !rx.whenFn.call(this)) continue;
        rx.fn.call(this);
    }
};

// ============================================================
// Updater — Tracks state changes (like NetLogo's Updater)
// ============================================================
function Updater() {
    this._updates = [];
    this._hasUpdates = false;
}

Updater.prototype.add = function (type, id, data) {
    this._updates.push({ type: type, id: id, data: data });
    this._hasUpdates = true;
};

Updater.prototype.collectUpdates = function () {
    var updates = this._updates;
    this._updates = [];
    this._hasUpdates = false;
    return updates;
};

Updater.prototype.hasUpdates = function () {
    return this._hasUpdates;
};

// ============================================================
// Simulation — Main simulation container
// ============================================================
function Simulation() {
    this.rng = new GamaRNG();
    this.world = null;
    this.species = {};       // name -> SpeciesDef
    this.speciesOrder = [];  // declaration order
    this.populations = {};   // name -> Agent[]
    this.cycle = 0;
    this.stopped = false;
    this._toRemove = [];
    this._toCreate = [];
    this._updater = new Updater();
}

Simulation.prototype.addAgent = function (speciesName, agent) {
    if (!this.populations[speciesName]) this.populations[speciesName] = [];
    this.populations[speciesName].push(agent);
    agent._sim = this;
    agent._rng = this.rng;
};

Simulation.prototype._scheduleRemove = function (speciesName, agent) {
    this._toRemove.push({ species: speciesName, agent: agent });
};

Simulation.prototype.createAgents = function (speciesName, count, initFn) {
    for (var i = 0; i < count; i++) {
        var def = this.species[speciesName];
        if (!def) continue;
        var agent = def.createAgent(this);
        if (initFn) initFn.call(agent, agent);
        this._toCreate.push({ species: speciesName, agent: agent });
    }
};

Simulation.prototype.step = function () {
    this.cycle++;

    // Flush pending creates
    for (var c = 0; c < this._toCreate.length; c++) {
        this.addAgent(this._toCreate[c].species, this._toCreate[c].agent);
    }
    this._toCreate = [];

    // Execute world reflexes
    if (this.world) this.world._executeReflexes();

    // Execute species reflexes in declaration order
    for (var s = 0; s < this.speciesOrder.length; s++) {
        var spName = this.speciesOrder[s];
        if (spName === 'world') continue;
        var pop = this.populations[spName] || [];
        var agents = pop.slice(); // copy since die() modifies array
        for (var a = 0; a < agents.length; a++) {
            if (!agents[a]._dead) agents[a]._executeReflexes();
        }
    }

    // Flush removes
    for (var r = 0; r < this._toRemove.length; r++) {
        var pop2 = this.populations[this._toRemove[r].species];
        if (pop2) {
            var idx = pop2.indexOf(this._toRemove[r].agent);
            if (idx >= 0) pop2.splice(idx, 1);
        }
    }
    this._toRemove = [];

    // Update derived variables
    this._updateDerived();

    // Update agent visuals from aspect/draw definitions
    this._updateVisuals();
};

Simulation.prototype._updateDerived = function () {
    for (var s = 0; s < this.speciesOrder.length; s++) {
        var sp = this.speciesOrder[s];
        var def = this.species[sp];
        if (!def || def.derivedVars.length === 0) continue;
        var agents = (sp === 'world') ? [this.world] : (this.populations[sp] || []);
        for (var a = 0; a < agents.length; a++) {
            for (var d = 0; d < def.derivedVars.length; d++) {
                var dv = def.derivedVars[d];
                agents[a][dv.name] = dv.compute.call(agents[a]);
            }
        }
    }
};

Simulation.prototype._updateVisuals = function () {
    for (var s = 0; s < this.speciesOrder.length; s++) {
        var sp = this.speciesOrder[s];
        if (sp === 'world') continue;
        var def = this.species[sp];
        if (!def || !def._drawFn) continue;
        var pop = this.populations[sp] || [];
        for (var a = 0; a < pop.length; a++) {
            if (!pop[a]._dead) def._drawFn(pop[a]);
        }
    }
};

Simulation.prototype.pause = function () { this.stopped = true; };

// ============================================================
// SpeciesDef — Defines a species (like NetLogo's Breed)
// ============================================================
function SpeciesDef(name) {
    this.name = name;
    this.isGrid = false;
    this.parentName = null;
    this.varDefs = {};       // name -> { default: value|function }
    this.derivedVars = [];   // [{ name, compute }]
    this.initFn = null;
    this.reflexDefs = [];    // [{ name, whenFn, fn }]
    this.actionDefs = {};    // name -> fn
    this.gridWidth = 50;
    this.gridHeight = 50;
    this.gridNeighbors = 4;
}

SpeciesDef.prototype.createAgent = function (sim) {
    var agent = new Agent(this.name);
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
    // Set up location
    if (this.isGrid) {
        agent.x = Math.floor(sim.rng.next() * this.gridWidth);
        agent.y = Math.floor(sim.rng.next() * this.gridHeight);
        agent.location.x = agent.x;
        agent.location.y = agent.y;
    } else {
        var hw = (sim.world ? sim.world.width : 50) / 2;
        var hh = (sim.world ? sim.world.height : 50) / 2;
        agent.x = sim.rng.between(-hw, hw);
        agent.y = sim.rng.between(-hh, hh);
        agent.location.x = agent.x;
        agent.location.y = agent.y;
    }
    // Execute species init block
    if (this.initFn) this.initFn.call(agent);
    return agent;
};

// Export globals
window.GamaRNG = GamaRNG;
window.Agent = Agent;
window.World = World;
window.Updater = Updater;
window.Simulation = Simulation;
window.SpeciesDef = SpeciesDef;
