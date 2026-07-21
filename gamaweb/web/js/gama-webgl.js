/**
 * GAMA Web - Display Renderer
 * Tries THREE.js WebGL first, falls back to Canvas 2D.
 * Uses global THREE (loaded via <script> tag).
 */

function GamaWebGLRenderer(canvas) {
    this.canvas = canvas;
    this.useWebGL = false;
    this.scene = null;
    this.camera = null;
    this.renderer = null;
    this.controls = null;
    this.layerGroup = null;
    this.demoMode = false;
    this.demoFrameId = null;
    this.agents = [];
    this.gridHelper = null;
    this._animId = null;

    // Canvas 2D fallback state
    this.ctx2d = null;
    this.cameraX = 0;
    this.cameraY = 40;
    this.cameraZ = 70;
    this.lookAtX = 0;
    this.lookAtZ = 0;
    this.zoom = 1;

    this._init();
}

GamaWebGLRenderer.prototype._init = function () {
    var w = this.canvas.clientWidth || 600;
    var h = this.canvas.clientHeight || 400;

    // Test WebGL support
    var gl = null;
    try {
        var testCanvas = document.createElement('canvas');
        gl = testCanvas.getContext('webgl2') || testCanvas.getContext('webgl') || testCanvas.getContext('experimental-webgl');
    } catch (e) { /* no webgl */ }

    if (gl && typeof THREE !== 'undefined' && THREE.WebGLRenderer) {
        this._initWebGL(w, h);
    } else {
        this._initCanvas2D(w, h);
    }
};

// ===== WebGL path =====
GamaWebGLRenderer.prototype._initWebGL = function (w, h) {
    this.useWebGL = true;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x1a1a2e);

    this.camera = new THREE.PerspectiveCamera(45, w / h, 0.1, 10000);
    this.camera.position.set(0, 50, 100);

    var attrs = { alpha: true, antialias: true, preserveDrawingBuffer: false };
    this.renderer = new THREE.WebGLRenderer(Object.assign({ canvas: this.canvas }, attrs));
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setSize(w, h);

    if (THREE.OrbitControls) {
        this.controls = new THREE.OrbitControls(this.camera, this.canvas);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.1;
        this.controls.screenSpacePanning = true;
    }

    var ambient = new THREE.AmbientLight(0xffffff, 0.6);
    this.scene.add(ambient);
    var directional = new THREE.DirectionalLight(0xffffff, 0.8);
    directional.position.set(50, 100, 50);
    this.scene.add(directional);

    this.layerGroup = new THREE.Group();
    this.scene.add(this.layerGroup);

    if (this.canvas.parentElement) {
        var self = this;
        new ResizeObserver(function () { self._onResizeWebGL(); }).observe(this.canvas.parentElement);
    }

    this._onResizeWebGL();
    console.log('Using THREE.js WebGL renderer');
};

GamaWebGLRenderer.prototype._onResizeWebGL = function () {
    var w = this.canvas.clientWidth || 600;
    var h = this.canvas.clientHeight || 400;
    if (this.camera) {
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
    }
    if (this.renderer) {
        this.renderer.setSize(w, h);
    }
};

// ===== Canvas 2D fallback =====
GamaWebGLRenderer.prototype._initCanvas2D = function (w, h) {
    this.useWebGL = false;
    this.canvas.width = w * (window.devicePixelRatio || 1);
    this.canvas.height = h * (window.devicePixelRatio || 1);
    this.canvas.style.width = w + 'px';
    this.canvas.style.height = h + 'px';
    this.ctx2d = this.canvas.getContext('2d');
    this.ctx2d.scale(window.devicePixelRatio || 1, window.devicePixelRatio || 1);
    this._canvasW = w;
    this._canvasH = h;
    console.log('Using Canvas 2D fallback renderer');
};

// ===== Public API =====
GamaWebGLRenderer.prototype.start = function () {
    if (this.useWebGL) {
        this._startWebGL();
    }
    // Canvas 2D doesn't need a continuous loop until demo runs
};

GamaWebGLRenderer.prototype._startWebGL = function () {
    var self = this;
    function animate() {
        self._animId = requestAnimationFrame(animate);
        if (self.controls) self.controls.update();
        self.renderer.render(self.scene, self.camera);
    }
    animate();
};

GamaWebGLRenderer.prototype.stop = function () {
    if (this._animId) {
        cancelAnimationFrame(this._animId);
        this._animId = null;
    }
};

// ===== Demo Mode =====
GamaWebGLRenderer.prototype.startDemo = function () {
    this.stopDemo();
    this.demoMode = true;

    if (this.useWebGL) {
        this._buildDemo3D();
        this._animateDemo3D();
    } else {
        this._buildDemo2D();
        this._animateDemo2D();
    }
};

GamaWebGLRenderer.prototype.stopDemo = function () {
    this.demoMode = false;
    if (this.demoFrameId) {
        cancelAnimationFrame(this.demoFrameId);
        this.demoFrameId = null;
    }

    if (this.useWebGL) {
        for (var i = 0; i < this.agents.length; i++) {
            this.scene.remove(this.agents[i].mesh);
        }
        this.agents = [];
        if (this.gridHelper) {
            this.scene.remove(this.gridHelper);
            this.gridHelper = null;
        }
        this.camera.position.set(0, 50, 100);
        if (this.controls) this.controls.target.set(0, 0, 0);
    } else {
        this.agents = [];
        if (this.ctx2d) {
            var ctx = this.ctx2d;
            ctx.clearRect(0, 0, this._canvasW, this._canvasH);
        }
    }
};

// ===== 3D Demo (WebGL) =====
GamaWebGLRenderer.prototype._buildDemo3D = function () {
    var size = 60;
    var numAgents = 80;
    var colors = [
        0x4ec9b0, 0x569cd6, 0xdcdcaa, 0xf44747,
        0xc586c0, 0x4ec9b0, 0x6a9955, 0xce9178,
        0x608b4e, 0x808080,
    ];

    this.gridHelper = new THREE.GridHelper(size, size, 0x444466, 0x222244);
    this.scene.add(this.gridHelper);

    for (var i = 0; i < numAgents; i++) {
        var color = colors[i % colors.length];
        var geo = new THREE.BoxGeometry(0.8, 0.8, 0.8);
        var mat = new THREE.MeshPhongMaterial({ color: color, emissive: color, emissiveIntensity: 0.2 });
        var mesh = new THREE.Mesh(geo, mat);
        var x = (Math.random() - 0.5) * size;
        var z = (Math.random() - 0.5) * size;
        var y = Math.random() * 2;
        mesh.position.set(x, y, z);
        this.scene.add(mesh);
        this.agents.push({
            mesh: mesh, vx: (Math.random() - 0.5) * 0.3, vz: (Math.random() - 0.5) * 0.3,
            baseY: y, phase: Math.random() * Math.PI * 2,
        });
    }

    this.camera.position.set(0, 40, 70);
    if (this.controls) this.controls.target.set(0, 0, 0);
};

GamaWebGLRenderer.prototype._animateDemo3D = function () {
    if (!this.demoMode) return;
    var self = this;
    var time = performance.now() * 0.001;
    var halfSize = 30;

    for (var i = 0; i < this.agents.length; i++) {
        var a = this.agents[i];
        a.vx += (Math.random() - 0.5) * 0.05;
        a.vz += (Math.random() - 0.5) * 0.05;
        a.vx *= 0.98;
        a.vz *= 0.98;
        a.mesh.position.x += a.vx;
        a.mesh.position.z += a.vz;
        if (Math.abs(a.mesh.position.x) > halfSize) { a.vx *= -1; a.mesh.position.x = Math.sign(a.mesh.position.x) * halfSize; }
        if (Math.abs(a.mesh.position.z) > halfSize) { a.vz *= -1; a.mesh.position.z = Math.sign(a.mesh.position.z) * halfSize; }
        a.mesh.position.y = a.baseY + Math.sin(time * 2 + a.phase) * 0.3;
        a.mesh.rotation.y += 0.02;
    }

    this.demoFrameId = requestAnimationFrame(function () { self._animateDemo3D(); });
};

// ===== 2D Demo (Canvas fallback) =====
GamaWebGLRenderer.prototype._buildDemo2D = function () {
    var numAgents = 80;
    var colors = [
        '#4ec9b0', '#569cd6', '#dcdcaa', '#f44747',
        '#c586c0', '#4ec9b0', '#6a9955', '#ce9178',
        '#608b4e', '#808080',
    ];

    for (var i = 0; i < numAgents; i++) {
        this.agents.push({
            x: Math.random() * this._canvasW,
            y: Math.random() * this._canvasH,
            vx: (Math.random() - 0.5) * 2,
            vy: (Math.random() - 0.5) * 2,
            size: 4 + Math.random() * 4,
            color: colors[i % colors.length],
            phase: Math.random() * Math.PI * 2,
        });
    }
};

GamaWebGLRenderer.prototype._animateDemo2D = function () {
    if (!this.demoMode) return;
    var self = this;
    var ctx = this.ctx2d;
    var w = this._canvasW;
    var h = this._canvasH;
    var time = performance.now() * 0.001;

    // Background
    ctx.fillStyle = '#1a1a2e';
    ctx.fillRect(0, 0, w, h);

    // Grid
    ctx.strokeStyle = '#222244';
    ctx.lineWidth = 0.5;
    var gridSize = 20;
    for (var gx = 0; gx < w; gx += gridSize) {
        ctx.beginPath(); ctx.moveTo(gx, 0); ctx.lineTo(gx, h); ctx.stroke();
    }
    for (var gy = 0; gy < h; gy += gridSize) {
        ctx.beginPath(); ctx.moveTo(0, gy); ctx.lineTo(w, gy); ctx.stroke();
    }

    // Agents
    for (var i = 0; i < this.agents.length; i++) {
        var a = this.agents[i];
        a.vx += (Math.random() - 0.5) * 0.15;
        a.vy += (Math.random() - 0.5) * 0.15;
        a.vx *= 0.98;
        a.vy *= 0.98;
        a.x += a.vx;
        a.y += a.vy;
        if (a.x < 0 || a.x > w) a.vx *= -1;
        if (a.y < 0 || a.y > h) a.vy *= -1;
        a.x = Math.max(0, Math.min(w, a.x));
        a.y = Math.max(0, Math.min(h, a.y));

        var bobY = Math.sin(time * 2 + a.phase) * 2;
        var sz = a.size + Math.sin(time * 3 + a.phase) * 1;

        // Glow
        ctx.globalAlpha = 0.3;
        ctx.fillStyle = a.color;
        ctx.fillRect(a.x - sz - 2, a.y + bobY - sz - 2, sz * 2 + 4, sz * 2 + 4);

        // Agent square
        ctx.globalAlpha = 1;
        ctx.fillStyle = a.color;
        ctx.fillRect(a.x - sz, a.y + bobY - sz, sz * 2, sz * 2);

        // Border
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 0.5;
        ctx.strokeRect(a.x - sz, a.y + bobY - sz, sz * 2, sz * 2);
    }

    this.demoFrameId = requestAnimationFrame(function () { self._animateDemo2D(); });
};

// ===== GeoJSON Layer Rendering =====
GamaWebGLRenderer.prototype.clearLayers = function () {
    if (this.useWebGL) {
        while (this.layerGroup.children.length > 0) {
            var child = this.layerGroup.children[0];
            this.layerGroup.remove(child);
            if (child.geometry) child.geometry.dispose();
            if (child.material) {
                if (Array.isArray(child.material)) child.material.forEach(function (m) { m.dispose(); });
                else child.material.dispose();
            }
        }
    }
};

GamaWebGLRenderer.prototype.loadLayers = function (layersJSON) {
    this.clearLayers();
    if (!layersJSON) return;

    var layers;
    try {
        layers = typeof layersJSON === 'string' ? JSON.parse(layersJSON) : layersJSON;
    } catch (e) {
        console.error('Failed to parse layers JSON:', e);
        return;
    }
    if (!Array.isArray(layers)) layers = [layers];

    if (this.useWebGL) {
        for (var i = 0; i < layers.length; i++) this._renderLayer3D(layers[i]);
        this._fitCamera();
    } else {
        for (var j = 0; j < layers.length; j++) this._renderLayer2D(layers[j]);
    }
};

GamaWebGLRenderer.prototype._renderLayer3D = function (layer) {
    if (!layer.features) return;
    for (var i = 0; i < layer.features.length; i++) {
        var feature = layer.features[i];
        var geom = feature.geometry;
        var props = feature.properties || {};
        var color = this._parseColor(props.color) || 0x4ec9b0;

        if (geom.type === 'Point') {
            var size = props.size || 1;
            var mesh = new THREE.Mesh(
                new THREE.SphereGeometry(size * 0.5, 8, 8),
                new THREE.MeshPhongMaterial({ color: color, emissive: color, emissiveIntensity: 0.3 })
            );
            mesh.position.set(geom.coordinates[0], geom.coordinates[2] || 0, geom.coordinates[1] || 0);
            this.layerGroup.add(mesh);
        } else if (geom.type === 'LineString' && geom.coordinates.length >= 2) {
            var points = geom.coordinates.map(function (c) { return new THREE.Vector3(c[0], c[2] || 0, c[1] || 0); });
            this.layerGroup.add(new THREE.Line(
                new THREE.BufferGeometry().setFromPoints(points),
                new THREE.LineBasicMaterial({ color: color })
            ));
        } else if (geom.type === 'Polygon') {
            this._addPolygon3D(geom.coordinates, color);
        } else if (geom.type === 'MultiPolygon') {
            for (var j = 0; j < geom.coordinates.length; j++) this._addPolygon3D(geom.coordinates[j], color);
        }
    }
};

GamaWebGLRenderer.prototype._addPolygon3D = function (coords, color) {
    if (!coords || !coords[0] || coords[0].length < 3) return;
    var shape = new THREE.Shape();
    var pts = coords[0].map(function (c) { return new THREE.Vector2(c[0], c[1]); });
    shape.setFromPoints(pts);
    var mesh = new THREE.Mesh(
        new THREE.ShapeGeometry(shape),
        new THREE.MeshPhongMaterial({ color: color, side: THREE.DoubleSide, transparent: true, opacity: 0.7 })
    );
    mesh.rotation.x = -Math.PI / 2;
    this.layerGroup.add(mesh);
};

GamaWebGLRenderer.prototype._renderLayer2D = function (layer) {
    // Basic 2D rendering for GeoJSON layers — draw onto canvas
    if (!layer.features || !this.ctx2d) return;
    var ctx = this.ctx2d;
    for (var i = 0; i < layer.features.length; i++) {
        var feature = layer.features[i];
        var coords = feature.geometry.coordinates;
        var color = (feature.properties && feature.properties.color) || '#4ec9b0';
        ctx.fillStyle = color;
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 1;
        if (feature.geometry.type === 'Polygon' && coords[0]) {
            ctx.beginPath();
            ctx.moveTo(coords[0][0][0], coords[0][0][1]);
            for (var j = 1; j < coords[0].length; j++) ctx.lineTo(coords[0][j][0], coords[0][j][1]);
            ctx.closePath();
            ctx.fill();
            ctx.stroke();
        }
    }
};

GamaWebGLRenderer.prototype._fitCamera = function () {
    if (!this.useWebGL) return;
    var box = new THREE.Box3().setFromObject(this.layerGroup);
    if (box.isEmpty()) return;
    var center = box.getCenter(new THREE.Vector3());
    var size = box.getSize(new THREE.Vector3());
    var maxDim = Math.max(size.x, size.y, size.z);
    var fov = this.camera.fov * (Math.PI / 180);
    var dist = maxDim / (2 * Math.tan(fov / 2)) * 1.5;
    this.camera.position.set(center.x, center.y + dist * 0.5, center.z + dist);
    if (this.controls) this.controls.target.copy(center);
};

GamaWebGLRenderer.prototype._parseColor = function (color) {
    if (typeof color === 'number') return color;
    if (typeof color === 'string') {
        if (color.charAt(0) === '#') return parseInt(color.slice(1), 16);
        if (color.indexOf('0x') === 0) return parseInt(color, 16);
    }
    return null;
};
