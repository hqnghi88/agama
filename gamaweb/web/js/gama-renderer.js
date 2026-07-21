/**
 * GAMA Renderer — Canvas 2D
 * Reads simulation state and renders agents on a Canvas 2D context.
 * Inspired by NetLogo Galapagos: layered rendering, coordinate conversion.
 */

function GamaRenderer(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.padding = 20;
    this._patchSize = 1;
    this._offsetX = 0;
    this._offsetY = 0;
}

GamaRenderer.prototype.clear = function () {
    this.ctx.fillStyle = '#1e1e1e';
    this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
};

GamaRenderer.prototype.render = function (sim) {
    if (!sim) return;

    // Auto-size canvas on first render
    if (!this._sized) {
        var rect = this.canvas.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) {
            this.canvas.width = Math.floor(rect.width);
            this.canvas.height = Math.floor(rect.height);
        } else {
            this.canvas.width = this.canvas.parentElement ? this.canvas.parentElement.clientWidth : 600;
            this.canvas.height = this.canvas.parentElement ? this.canvas.parentElement.clientHeight - 30 : 270;
        }
        this._sized = true;
    }

    var ctx = this.ctx;
    var cw = this.canvas.width;
    var ch = this.canvas.height;

    // Background
    ctx.fillStyle = '#1e1e1e';
    ctx.fillRect(0, 0, cw, ch);

    // Calculate world bounds and patch size
    var worldWidth = 50;
    var worldHeight = 50;
    if (sim.world) {
        worldWidth = sim.world.width || 50;
        worldHeight = sim.world.height || 50;
    }

    var availW = cw - 2 * this.padding;
    var availH = ch - 2 * this.padding;
    this._patchSize = Math.min(availW / worldWidth, availH / worldHeight);
    this._offsetX = (cw - worldWidth * this._patchSize) / 2;
    this._offsetY = (ch - worldHeight * this._patchSize) / 2;

    // Draw grid lines (light)
    ctx.strokeStyle = '#333';
    ctx.lineWidth = 0.5;
    for (var gx = 0; gx <= worldWidth; gx++) {
        var px = this._offsetX + gx * this._patchSize;
        ctx.beginPath();
        ctx.moveTo(px, this._offsetY);
        ctx.lineTo(px, this._offsetY + worldHeight * this._patchSize);
        ctx.stroke();
    }
    for (var gy = 0; gy <= worldHeight; gy++) {
        var py = this._offsetY + gy * this._patchSize;
        ctx.beginPath();
        ctx.moveTo(this._offsetX, py);
        ctx.lineTo(this._offsetX + worldWidth * this._patchSize, py);
        ctx.stroke();
    }

    // Draw agents for each species
    var speciesOrder = sim.speciesOrder;
    for (var s = 0; s < speciesOrder.length; s++) {
        var spName = speciesOrder[s];
        if (spName === 'world') continue;
        var pop = sim.populations[spName] || [];
        var def = sim.species[spName];
        var isGrid = def && def.isGrid;

        for (var a = 0; a < pop.length; a++) {
            var agent = pop[a];
            if (agent._dead) continue;

            var screenX, screenY, agentSize;

            if (isGrid) {
                // Grid agents: position by grid coordinates
                screenX = this._offsetX + (agent.x + 0.5) * this._patchSize;
                screenY = this._offsetY + (agent.y + 0.5) * this._patchSize;
                agentSize = this._patchSize * 0.9;
            } else {
                // Continuous agents: position by world coordinates
                screenX = this._offsetX + (agent.x + worldWidth / 2) * this._patchSize;
                screenY = this._offsetY + (worldHeight / 2 - agent.y) * this._patchSize;
                agentSize = (agent.size || 1) * this._patchSize;
            }

            ctx.fillStyle = gamaResolveColor(agent.color) || '#4ec9b0';
            var shape = agent.shape || 'circle';
            if (shape === 'square') {
                ctx.fillRect(screenX - agentSize, screenY - agentSize, agentSize * 2, agentSize * 2);
            } else if (shape === 'rectangle') {
                ctx.fillRect(screenX - agentSize, screenY - agentSize * 0.6, agentSize * 2, agentSize * 1.2);
            } else {
                ctx.beginPath();
                ctx.arc(screenX, screenY, Math.max(1, agentSize), 0, Math.PI * 2);
                ctx.fill();
            }
        }
    }

    // Draw info overlay
    ctx.fillStyle = '#888';
    ctx.font = '11px monospace';
    ctx.fillText('Cycle: ' + sim.cycle, 5, ch - 5);

    var totalAgents = 0;
    for (var sp in sim.populations) {
        if (sp !== 'world') totalAgents += sim.populations[sp].length;
    }
    ctx.fillText('Agents: ' + totalAgents, 5, ch - 18);
};

GamaRenderer.prototype.resize = function (width, height) {
    this.canvas.width = width;
    this.canvas.height = height;
};

// Export
window.GamaRenderer = GamaRenderer;
