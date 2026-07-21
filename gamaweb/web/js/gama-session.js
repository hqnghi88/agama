/**
 * GAMA Session — Orchestrates compilation, simulation, and rendering.
 * Inspired by NetLogo Galapagos SessionLite: event loop, speed control, start/stop/step.
 */

function GamaSession(canvas, outputCallback) {
    this.canvas = canvas;
    this.renderer = new GamaRenderer(canvas);
    this.session = null;    // current Simulation
    this._running = false;
    this._rafId = null;
    this._speed = 0.5;     // 0 = paused, 0.5 = normal, 1 = fast
    this._lastTime = 0;
    this._accumTime = 0;
    this._maxUpdatesPerFrame = 1;
    this._outputCallback = outputCallback || null;
    this._compiledJS = null;
}

// === Compilation ===

GamaSession.prototype.compile = function (source) {
    var result = compileGaml(source);
    this._compiledJS = result.jsCode;
    return result;
};

// === Simulation lifecycle ===

GamaSession.prototype.createSimulation = function () {
    if (!this._compiledJS) throw new Error('No compiled model. Call compile() first.');

    var sim = new Simulation();

    // Register species from compiled code, then initialize
    var code = this._compiledJS + '\nreturn _initSimulation(sim);';
    try {
        var fn = new Function('sim', 'SpeciesDef', 'Agent', 'World', 'Simulation', 'GamaRNG', code);
        sim = fn(sim, SpeciesDef, Agent, World, Simulation, GamaRNG);
    } catch (e) {
        console.error('GAML runtime error:', e);
        console.error('Generated JS:\n', this._compiledJS);
        throw e;
    }

    this.session = sim;
    return sim;
};

GamaSession.prototype.step = function () {
    if (this.session && !this.session.stopped) {
        this.session.step();
        this.renderer.render(this.session);
        if (this._outputCallback) this._outputCallback(this.session);
    }
};

GamaSession.prototype.start = function () {
    if (!this.session) this.createSimulation();
    this._running = true;
    this._lastTime = performance.now();
    this._accumTime = 0;
    this._eventLoop(performance.now());
};

GamaSession.prototype.stop = function () {
    this._running = false;
    if (this._rafId) {
        cancelAnimationFrame(this._rafId);
        this._rafId = null;
    }
};

GamaSession.prototype.pause = function () {
    this._running = false;
    if (this.session) this.session.stopped = true;
};

GamaSession.prototype.resume = function () {
    if (this.session) this.session.stopped = false;
    this.start();
};

GamaSession.prototype.reset = function () {
    this.stop();
    this.session = null;
    this.renderer.clear();
};

GamaSession.prototype.setSpeed = function (speed) {
    this._speed = Math.max(0, Math.min(1, speed));
    this._maxUpdatesPerFrame = Math.max(1, Math.floor(Math.pow(10, speed * 2)));
};

GamaSession.prototype.getSpeed = function () {
    return this._speed;
};

// === Event loop (like NetLogo Galapagos) ===

GamaSession.prototype._eventLoop = function (timestamp) {
    if (!this._running) return;

    var self = this;
    this._rafId = requestAnimationFrame(function (ts) { self._eventLoop(ts); });

    var elapsed = timestamp - this._lastTime;
    this._lastTime = timestamp;
    this._accumTime += elapsed;

    // Calculate update delay based on speed
    var updateDelay = 1000 / Math.pow(10, this._speed * 2 + 1); // ~10ms at speed=1, ~1000ms at speed=0

    var updates = 0;
    while (this._accumTime >= updateDelay && updates < this._maxUpdatesPerFrame) {
        if (this.session && !this.session.stopped) {
            this.session.step();
            updates++;
        }
        this._accumTime -= updateDelay;
    }

    // Render
    if (updates > 0 || this._accumTime > 0) {
        this.renderer.render(this.session);
        if (this._outputCallback) this._outputCallback(this.session);
    }

    // Debug: log once after first successful render
    if (!this._debugLogged && updates > 0) {
        var total = 0;
        for (var sp in this.session.populations) { if (sp !== 'world') total += this.session.populations[sp].length; }
        console.log('GAMA first render: cycle=' + this.session.cycle + ' agents=' + total +
            ' canvas=' + this.canvas.width + 'x' + this.canvas.height);
        this._debugLogged = true;
    }
};

// === Convenience ===

GamaSession.prototype.loadAndRun = function (source) {
    var result = this.compile(source);
    console.log('GAMA compile OK, JS length:', result.jsCode.length);
    console.log('Generated JS (first 500):', result.jsCode.substring(0, 500));
    this.createSimulation();
    console.log('Simulation created, world:', this.session ? this.session.world : 'null');
    console.log('Populations:', this.session ? JSON.stringify(Object.keys(this.session.populations).map(k => k + ':' + (this.session.populations[k]||[]).length)) : 'null');
    this.start();
};

// Export
window.GamaSession = GamaSession;
