package com.gama.nativeapp.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.awt.Font;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.Envelope;

import gama.core.common.interfaces.GeneralSynchronizer;
import gama.core.common.interfaces.IDisplaySurface;
import gama.core.common.interfaces.IGraphics;
import gama.core.common.interfaces.IKeyword;
import gama.core.common.interfaces.ILayer;
import gama.core.common.interfaces.ILayerManager;
import gama.core.metamodel.agent.IAgent;
import gama.core.metamodel.shape.GamaPoint;
import gama.core.metamodel.shape.IShape;
import gama.core.outputs.LayeredDisplayData;
import gama.core.outputs.LayeredDisplayOutput;
import gama.core.outputs.display.LayerManager;
import gama.core.outputs.layers.IEventLayerListener;
import gama.core.outputs.layers.OverlayLayer;
import gama.core.runtime.GAMA;
import gama.core.runtime.IScope.IGraphicsScope;

public class AndroidDisplaySurface extends View implements IDisplaySurface {

    private final LayeredDisplayOutput output;
    private final ILayerManager layerManager;
    private AndroidDisplayGraphics androidGraphics;
    private IGraphicsScope scope;

    private final Rect viewPort = new Rect();
    private int displayWidth, displayHeight;
    private boolean zoomFit = true;
    private double zoomLevel = 1.0;
    private volatile boolean disposed = false;
    private boolean isLocked = false;
    private int frames = 0;
    private boolean rendered = false;

    private PointF mousePosition = new PointF(-1, -1);
    private final Set<IEventLayerListener> listeners = new HashSet<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private float lastTouchX, lastTouchY;
    private ScaleGestureDetector scaleDetector;
    private final Paint bgPaint = new Paint();
    private final Paint agentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private int framesSinceLastDraw = 0;

    private final RectF workRect = new RectF();
    private Bitmap cachedGridBitmap;
    private int cachedGridW, cachedGridH;

    private java.util.List<String> cachedSpeciesNames;
    private long lastSpeciesCacheTime = 0;
    private gama.core.metamodel.agent.IMacroAgent capturedSim = null;
    private int cachedAgentCount = 0;

    public AndroidDisplaySurface(Context context, LayeredDisplayOutput output) {
        super(context);
        this.output = output;
        output.setSurface(this);
        setDisplayScope(output.getScope().copyForGraphics("in android2d display"));
        // Capture simulation reference at construction time before scope becomes stale
        try {
            gama.core.runtime.IScope outScope = output.getScope();
            if (outScope != null) {
                this.capturedSim = outScope.getSimulation();
            }
        } catch (Throwable t) {
        }
        output.getData().addListener(this);
        this.layerManager = new LayerManager(this, output);
        this.androidGraphics = new AndroidDisplayGraphics();
        this.androidGraphics.setDisplaySurface(this);

        bgPaint.setColor(output.getData().getBackgroundColor().getRGB() | 0xFF000000);
        bgPaint.setStyle(Paint.Style.FILL);

        agentPaint.setColor(0xFF00FF00);
        agentPaint.setStyle(Paint.Style.FILL);
        gridPaint.setFilterBitmap(true);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (factor > 1.0f) zoomIn();
                else zoomOut();
                return true;
            }
        });

        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public AndroidDisplaySurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.output = null;
        this.layerManager = null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            if (zoomFit) {
                zoomFit();
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentW = MeasureSpec.getSize(widthMeasureSpec);
        int parentH = MeasureSpec.getSize(heightMeasureSpec);
        if (parentW <= 0 || parentH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        double envW = getEnvWidth();
        double envH = getEnvHeight();
        if (envW <= 0 || envH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        double envRatio = envW / envH;
        int measuredW, measuredH;
        if (envRatio >= (double) parentW / parentH) {
            measuredW = parentW;
            measuredH = (int) Math.round(parentW / envRatio);
        } else {
            measuredH = parentH;
            measuredW = (int) Math.round(parentH * envRatio);
        }
        setMeasuredDimension(Math.max(1, measuredW), Math.max(1, measuredH));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (disposed || output == null) return;

        canvas.drawColor(bgPaint.getColor());

        if (androidGraphics == null) {
            androidGraphics = new AndroidDisplayGraphics();
            androidGraphics.setDisplaySurface(this);
        }

        androidGraphics.setCanvas(canvas);
        androidGraphics.resetDrawnShapesCount();
        boolean drewShapes = false;
        canvas.save();
        canvas.translate(-viewPort.left, -viewPort.top);
        try {
            IGraphicsScope drawScope = scope;
            if (drawScope == null) drawScope = output.getScope().copyForGraphics("draw");
            if (drawScope != null && !drawScope.interrupted()) {
                layerManager.drawLayersOn(androidGraphics);
                drewShapes = androidGraphics.getDrawnShapesCount() > 0;
            }
        } catch (Throwable t) {
            // layerManager draw error
        }
        if (!drewShapes) {
            drawAgentsManually(canvas);
        }
        canvas.restore();

        frames++;
        rendered = true;
    }

    private void drawAgentsManually(Canvas canvas) {
        try {
            IGraphicsScope drawScope = scope;
            if (drawScope == null || drawScope.interrupted()) {
                return;
            }

            gama.core.metamodel.agent.IMacroAgent sim = null;
            // Priority 1: use cached simulation
            sim = capturedSim;
            // Priority 2-5: scope chain (usually fails for display copies)
            if (sim == null) {
                try { sim = drawScope.getSimulation(); } catch (Throwable t) {}
            }
            if (sim == null) {
                try {
                    gama.core.runtime.IScope outScope = output.getScope();
                    if (outScope != null) sim = outScope.getSimulation();
                } catch (Throwable t) {}
            }
            if (sim == null) {
                try {
                    gama.core.runtime.IScope outScope = output.getScope();
                    if (outScope != null && outScope.getRoot() != null) sim = outScope.getRoot().getSimulation();
                } catch (Throwable t) {}
            }
            if (sim == null) {
                try {
                    gama.core.runtime.IScope outScope = output.getScope();
                    if (outScope != null) {
                        Object exp = outScope.getExperiment();
                        if (exp instanceof gama.core.metamodel.agent.IMacroAgent macro) sim = macro.getSimulation();
                    }
                } catch (Throwable t) {}
            }
            // Priority 6: try GAMA.getSimulation() or iterate controllers
            if (sim == null) {
                try {
                    Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
                    Object simObj = gamaClass.getMethod("getSimulation").invoke(null);
                    if (simObj instanceof gama.core.metamodel.agent.IMacroAgent m) sim = m;
                } catch (Throwable t) {}
            }
            if (sim == null) {
                try {
                    Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
                    java.lang.reflect.Field ctrlField = gamaClass.getDeclaredField("controllers");
                    ctrlField.setAccessible(true);
                    java.util.List controllers = (java.util.List) ctrlField.get(null);
                    if (controllers != null && !controllers.isEmpty()) {
                        Object ctrl = controllers.get(controllers.size() - 1);
                        java.lang.reflect.Field agentField = ctrl.getClass().getSuperclass().getDeclaredField("agent");
                        agentField.setAccessible(true);
                        Object agent = agentField.get(ctrl);
                        if (agent instanceof gama.core.metamodel.agent.IMacroAgent macro) sim = macro.getSimulation();
                    }
                } catch (Throwable t) {}
            }
            // Cache for next time
            if (sim != null && capturedSim == null) {
                capturedSim = sim;
            }
            if (sim == null) {
                return;
            }

            double envW = getEnvWidth();
            double envH = getEnvHeight();
            if (envW <= 0 || envH <= 0) {
                return;
            }

            try {
                gama.core.common.geometry.Envelope3D env = sim.getEnvelope();
                if (env != null) {
                    double realW = env.getWidth();
                    double realH = env.getHeight();
                    env.dispose();
                    if (realW > 0 && realH > 0) {
                        envW = realW;
                        envH = realH;
                        gama.core.outputs.LayeredDisplayData data = output.getData();
                        if (data != null) {
                            data.setEnvWidth(envW);
                            data.setEnvHeight(envH);
                        }
                    }
                }
            } catch (Throwable t) { /* use fallback */ }

            double dispW = getDisplayWidth();
            double dispH = getDisplayHeight();
            double scale = Math.min(dispW / envW, dispH / envH);
            double offsetX = (dispW - envW * scale) / 2.0;
            double offsetY = (dispH - envH * scale) / 2.0;
            float radius = (float) Math.max(4, 3 * scale);

            gama.core.metamodel.agent.IAgent agent = (sim instanceof gama.core.metamodel.agent.IAgent) ? (sim) : null;
            if (agent == null) return;

            long now = System.currentTimeMillis();
            if (cachedSpeciesNames == null || (now - lastSpeciesCacheTime) > 2000) {
                try {
                    Object specObj = sim.getSpecies();
                    if (specObj instanceof gama.core.kernel.model.IModel model) {
                        java.util.Map<String, gama.gaml.species.ISpecies> allSpecies = model.getAllSpecies();
                        cachedSpeciesNames = allSpecies != null ? new java.util.ArrayList<>(allSpecies.keySet()) : null;
                    }
                } catch (Throwable t) { /* skip */ }
                lastSpeciesCacheTime = now;
            }
            if (cachedSpeciesNames == null || cachedSpeciesNames.isEmpty()) return;

            int totalDrawn = 0;
            Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint agentCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            agentCirclePaint.setColor(0xFF000000);
            agentCirclePaint.setStyle(Paint.Style.FILL);

            java.util.List<Object[]> gridAgents = new java.util.ArrayList<>();

            for (String speciesName : cachedSpeciesNames) {
                try {
                    gama.core.metamodel.population.IPopulation<? extends gama.core.metamodel.agent.IAgent> pop = agent.getPopulationFor(speciesName);
                    // For micro-species (e.g. 'ant' inside 'ants_model'), getPopulationFor returns empty.
                    // Fall back to iterating macro-agent sub-populations.
                    if (pop == null || pop.size() == 0) {
                        gama.core.metamodel.population.IPopulation microPop = tryGetMicroPopulation(sim, speciesName);
                        if (microPop != null && microPop.size() > 0) pop = microPop;
                    }
                    if (pop == null || pop.size() == 0) {
                        continue;
                    }

                    boolean isGridPop = pop instanceof gama.core.metamodel.topology.grid.GridPopulation;
                    int gridCols = 0, gridRows = 0;
                    if (isGridPop) {
                        try {
                            gama.core.metamodel.topology.grid.GridPopulation gp = (gama.core.metamodel.topology.grid.GridPopulation) pop;
                            gridCols = gp.getNbCols();
                            gridRows = gp.getNbRows();
                        } catch (Throwable t) { isGridPop = false; }
                    }

                    if (isGridPop && gridCols > 0 && gridRows > 0) {
                        int sz = pop.size();
                        double cellW = envW / gridCols;
                        double cellH = envH / gridRows;
                        for (int i = 0; i < sz; i++) {
                            Object obj = pop.get(i);
                            if (!(obj instanceof gama.core.metamodel.topology.grid.IGridAgent ga)) continue;
                            try {
                                gama.core.util.GamaColor gc = ga.getColor();
                                if (gc != null) {
                                    cellPaint.setColor(0xFF000000 | (gc.getRGB() & 0x00FFFFFF));
                                } else {
                                    cellPaint.setColor(agentPaint.getColor());
                                }
                                int cx = ga.getX();
                                int cy = ga.getY();
                                float left = (float) (cx * cellW * scale + offsetX);
                                float top = (float) ((envH - (cy + 1) * cellH) * scale + offsetY);
                                float right = (float) ((cx + 1) * cellW * scale + offsetX);
                                float bottom = (float) ((envH - cy * cellH) * scale + offsetY);
                                canvas.drawRect(left, top, right, bottom, cellPaint);
                                totalDrawn++;
                            } catch (Throwable t) { /* skip cell */ }
                        }
                    } else {
                        int sz = pop.size();
                        for (int i = 0; i < sz; i++) {
                            Object obj = pop.get(i);
                            if (!(obj instanceof gama.core.metamodel.agent.IAgent a) || a.dead()) continue;
                            gama.core.metamodel.shape.IShape loc = a.getLocation();
                            if (loc == null) continue;
                            gama.core.metamodel.shape.GamaPoint pt = loc.getLocation();
                            if (pt == null) continue;
                            float sx = (float) (pt.getX() * scale + offsetX);
                            float sy = (float) ((envH - pt.getY()) * scale + offsetY);
                            gridAgents.add(new Object[]{sx, sy});
                        }
                    }
                } catch (Throwable t) { /* skip pop */ }
            }

            for (Object[] pos : gridAgents) {
                float sx = (float) pos[0];
                float sy = (float) pos[1];
                canvas.drawCircle(sx, sy, radius, agentCirclePaint);
                totalDrawn++;
            }
        } catch (Throwable t) { /* skip draw */ }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private gama.core.metamodel.population.IPopulation tryGetMicroPopulation(
            gama.core.metamodel.agent.IMacroAgent sim, String speciesName) {
        // Approach: recursively find all micro-populations matching the species name
        try {
            gama.core.metamodel.population.IPopulation simPop = sim.getPopulation();
            if (simPop == null) return null;
            return findInPopulations(simPop, speciesName, 0);
        } catch (Throwable t) {}
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private gama.core.metamodel.population.IPopulation findInPopulations(
            gama.core.metamodel.population.IPopulation pop, String speciesName, int depth) {
        if (depth > 5 || pop == null) return null;
        try {
            for (int i = 0; i < pop.size(); i++) {
                Object agent = pop.get(i);
                if (agent instanceof gama.core.metamodel.agent.IAgent ag) {
                    if (ag instanceof gama.core.metamodel.agent.IMacroAgent macro) {
                        try {
                            gama.core.metamodel.population.IPopulation microPop = macro.getPopulationFor(speciesName);
                            if (microPop != null && microPop.size() > 0) {
                                if (frames % 120 == 0) android.util.Log.i("DispDraw", "findInPop: FOUND " + speciesName + " via getPopulationFor size=" + microPop.size() + " depth=" + depth + " agent=" + ag.getSpecies());
                                return microPop;
                            }
                        } catch (Throwable t) {}
                        try {
                            gama.core.metamodel.population.IPopulation agentPop = macro.getPopulation();
                            if (agentPop != null && agentPop.size() > 0) {
                                gama.core.metamodel.population.IPopulation found = findInPopulations(agentPop, speciesName, depth + 1);
                                if (found != null) return found;
                            }
                        } catch (Throwable t) {}
                        try {
                            gama.core.metamodel.population.IPopulation<? extends gama.core.metamodel.agent.IAgent>[] allMicro = macro.getMicroPopulations();
                            if (allMicro != null) {
                                if (frames % 120 == 0 && depth == 0) android.util.Log.i("DispDraw", "findInPop: agent " + i + " (" + ag.getSpecies() + ") has " + allMicro.length + " microPops");
                                for (Object mp : allMicro) {
                                    if (mp instanceof gama.core.metamodel.population.IPopulation subPop) {
                                        String popName = subPop.getSpecies() != null ? subPop.getSpecies().getName() : "";
                                        if (frames % 120 == 0 && depth == 0) android.util.Log.i("DispDraw", "  microPop: " + popName + " size=" + subPop.size());
                                        if (popName.equals(speciesName) && subPop.size() > 0) return subPop;
                                        gama.core.metamodel.population.IPopulation deeper = findInPopulations(subPop, speciesName, depth + 1);
                                        if (deeper != null) return deeper;
                                    }
                                }
                            }
                        } catch (Throwable t) {}
                    }
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (disposed || output == null) return false;

        scaleDetector.onTouchEvent(event);

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                mousePosition.set(x, y);
                dispatchMouseEvent(16, (int) x, (int) y);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) return scaleDetector.onTouchEvent(event);
                mousePosition.set(x, y);
                dispatchMouseEvent(6, (int) x, (int) y);
                if (!isLocked) {
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    viewPort.offset((int) dx, (int) dy);
                    invalidate();
                }
                lastTouchX = x;
                lastTouchY = y;
                return true;

            case MotionEvent.ACTION_UP:
                mousePosition.set(x, y);
                dispatchMouseEvent(17, (int) x, (int) y);
                return true;
        }
        return super.onTouchEvent(event);
    }

    // -- IDisplaySurface implementation --

    @Override
    public java.awt.image.BufferedImage getImage(int width, int height) {
        int w = width > 0 ? width : super.getWidth();
        int h = height > 0 ? height : super.getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        androidGraphics.setCanvas(c);
        layerManager.drawLayersOn(androidGraphics);
        androidGraphics.setCanvas(null);

        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        bmp.recycle();
        return image;
    }

    @Override
    public void updateDisplay(boolean force, GeneralSynchronizer synchronizer) {
        if (disposed) return;
        post(() -> {
            if (!isAttachedToWindow()) return;
            invalidate();
            if (synchronizer != null) synchronizer.release();
        });
    }

    @Override
    public void setMenuManager(Object displaySurfaceMenu) {}

    @Override
    public void zoomIn() {
        if (isLocked) return;
        float factor = 1.2f;
        int newW = (int) (getDisplayWidth() * factor);
        int newH = (int) (getDisplayHeight() * factor);
        if (resizeImage(newW, newH, false)) {
            zoomFit = false;
            updateZoomLevel();
            invalidate();
        }
    }

    @Override
    public void zoomOut() {
        if (isLocked) return;
        float factor = 0.8f;
        int newW = (int) Math.max(10, getDisplayWidth() * factor);
        int newH = (int) Math.max(10, getDisplayHeight() * factor);
        if (resizeImage(newW, newH, false)) {
            zoomFit = false;
            updateZoomLevel();
            invalidate();
        }
    }

    @Override
    public void zoomFit() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        mousePosition.set(w / 2f, h / 2f);
        if (resizeImage(w, h, false)) {
            this.zoomLevel = 1.0;
            this.zoomFit = true;
            viewPort.set(0, 0, w, h);
            invalidate();
        }
    }

    @Override
    public void toggleLock() { isLocked = !isLocked; }

    @Override
    public ILayerManager getManager() { return layerManager; }

    @Override
    public void focusOn(IShape geometry) {
        if (geometry == null) return;
        Rectangle2D r = layerManager.focusOn(geometry, this);
        if (r == null) return;
        float xScale = (float) (getWidth() / r.getWidth());
        float yScale = (float) (getHeight() / r.getHeight());
        float zf = Math.min(xScale, yScale);
        viewPort.set(0, 0, (int) (getDisplayWidth() * zf), (int) (getDisplayHeight() * zf));
        invalidate();
    }

    @Override
    public void runAndUpdate(Runnable r) {
        new Thread(() -> {
            r.run();
            uiHandler.post(this::invalidate);
        }).start();
    }

    @Override
    public void outputReloaded() {
        setDisplayScope(output.getScope().copyForGraphics("in android2d display"));
        layerManager.outputChanged();
        if (zoomFit) zoomFit();
        invalidate();
    }

    @Override
    public double getEnvWidth() { return output.getData().getEnvWidth(); }

    @Override
    public double getEnvHeight() { return output.getData().getEnvHeight(); }

    @Override
    public double getDisplayWidth() { return viewPort.width(); }

    @Override
    public double getDisplayHeight() { return viewPort.height(); }

    @Override
    public Collection<IAgent> selectAgent(int x, int y) {
        int xc = x - viewPort.left;
        int yc = y - viewPort.top;
        List<IAgent> result = new ArrayList<>();
        List<ILayer> layers = layerManager.getLayersIntersecting(xc, yc);
        for (ILayer layer : layers) {
            Collection<IAgent> agents = layer.collectAgentsAt(xc, yc, this);
            if (agents != null && !agents.isEmpty()) result.addAll(agents);
        }
        return result;
    }

    @Override
    public double getZoomLevel() { return zoomLevel; }

    @Override
    public void setSize(int x, int y) {
        viewPort.set(0, 0, x, y);
    }

    @Override
    public LayeredDisplayOutput getOutput() { return output; }

    @Override
    public LayeredDisplayData getData() { return output != null ? output.getData() : null; }

    @Override
    public void layersChanged() { invalidate(); }

    @Override
    public void addListener(IEventLayerListener e) { listeners.add(e); }

    @Override
    public void removeListener(IEventLayerListener e) { listeners.remove(e); }

    @Override
    public Collection<IEventLayerListener> getLayerListeners() { return listeners; }

    @Override
    public Envelope getVisibleRegionForLayer(ILayer currentLayer) {
        if (currentLayer instanceof OverlayLayer && scope != null) {
            return scope.getSimulation().getEnvelope();
        }
        Envelope e = new Envelope();
        e.expandToInclude(currentLayer.getModelCoordinatesFrom(0, 0, this));
        e.expandToInclude(currentLayer.getModelCoordinatesFrom((int) getWidth(), (int) getHeight(), this));
        return e;
    }

    @Override
    public int getFPS() { int r = frames; frames = 0; return r; }

    @Override
    public boolean isDisposed() { return disposed; }

    @Override
    public void getModelCoordinatesInfo(StringBuilder receiver) {
        receiver.append("Model coordinates: ").append(mousePosition.x).append(", ").append(mousePosition.y);
    }

    @Override
    public void dispatchKeyEvent(char character) {
        for (IEventLayerListener gl : listeners) gl.keyPressed(String.valueOf(character));
    }

    @Override
    public void dispatchSpecialKeyEvent(int keyCode) {
        for (IEventLayerListener gl : listeners) gl.specialKeyPressed(keyCode);
    }

    @Override
    public void dispatchMouseEvent(int swtEventType, int x, int y) {
        for (IEventLayerListener gl : listeners) {
            switch (swtEventType) {
                case 16: gl.mouseDown(x, y, 1); break;
                case 17: gl.mouseUp(x, y, 1); break;
                case 6: gl.mouseMove(x, y); break;
                case 5: gl.mouseDrag(x, y, 1); break;
                case 7: gl.mouseEnter(x, y); break;
                case 8: gl.mouseExit(x, y); break;
            }
        }
    }

    @Override
    public void setMousePosition(int x, int y) { mousePosition.set(x, y); }

    @Override
    public void draggedTo(int x, int y) {
        if (!isLocked) {
            float dx = x - mousePosition.x;
            float dy = y - mousePosition.y;
            viewPort.offset((int) dx, (int) dy);
            invalidate();
        }
        mousePosition.set(x, y);
    }

    @Override
    public void selectAgentsAroundMouse() {}

    @Override
    public IGraphicsScope getScope() { return scope; }

    @Override
    public boolean isVisible() { return getVisibility() == VISIBLE; }

    @Override
    public IGraphics getIGraphics() { return androidGraphics; }

    @Override
    public Rectangle getBoundsForRobotSnapshot() { return new Rectangle(getWidth(), getHeight()); }

    @Override
    public GamaPoint getModelCoordinates() {
        List<ILayer> layers = layerManager.getLayersIntersecting((int) mousePosition.x, (int) mousePosition.y);
        for (ILayer layer : layers) {
            if (layer.isProvidingWorldCoordinates()) {
                return layer.getModelCoordinatesFrom((int) mousePosition.x, (int) mousePosition.y, this);
            }
        }
        return new GamaPoint();
    }

    @Override
    public void changed(LayeredDisplayData.Changes property, Object value) {
        if (property == LayeredDisplayData.Changes.BACKGROUND) {
            bgPaint.setColor(((java.awt.Color) value).getRGB() | 0xFF000000);
        }
    }

    @Override
    public Font computeFont(Font f) { return f; }

    private void setDisplayScope(IGraphicsScope scope) {
        if (this.scope != null) GAMA.releaseScope(this.scope);
        this.scope = scope;
    }

    private void updateZoomLevel() {
        if (getEnvWidth() > 0 && getEnvHeight() > 0) {
            zoomLevel = Math.min(getDisplayWidth() / getEnvWidth(), getDisplayHeight() / getEnvHeight());
        }
    }

    private boolean resizeImage(int x, int y, boolean force) {
        if (!force && x == getDisplayWidth() && y == getDisplayHeight()) return true;
        if (x < 10 || y < 10) return false;

        int[] dim = computeBoundsFrom(x, y);
        displayWidth = Math.max(1, dim[0]);
        displayHeight = Math.max(1, dim[1]);
        viewPort.set(0, 0, displayWidth, displayHeight);

        if (androidGraphics == null) {
            androidGraphics = new AndroidDisplayGraphics();
            androidGraphics.setDisplaySurface(this);
        }
        return true;
    }

    private int[] computeBoundsFrom(int vwidth, int vheight) {
        if (!layerManager.stayProportional()) return new int[]{vwidth, vheight};
        double ratio = getEnvHeight() / getEnvWidth();
        int[] dim = new int[2];
        if (ratio < 1) {
            dim[1] = Math.min(vheight, (int) Math.round(vwidth * ratio));
            dim[0] = Math.min(vwidth, (int) Math.round(dim[1] / ratio));
        } else {
            dim[0] = Math.min(vwidth, (int) Math.round(vheight / ratio));
            dim[1] = Math.min(vheight, (int) Math.round(dim[0] * ratio));
        }
        return dim;
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        getData().removeListener(this);
        if (layerManager != null) layerManager.dispose();
        GAMA.releaseScope(getScope());
        setDisplayScope(null);
    }
}
