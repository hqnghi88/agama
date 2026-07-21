/*******************************************************************************************************
 *
 * WebGLDisplaySurface.java, in gama.web, is part of the source code of the GAMA modeling and simulation platform
 * (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.web.display;

import java.awt.Point;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import gama.annotations.display;
import gama.annotations.doc;
import gama.annotations.constants.IKeyword;
import gama.api.GAMA;
import gama.api.kernel.agent.IAgent;
import gama.api.runtime.GeneralSynchronizer;
import gama.api.types.geometry.GamaPointFactory;
import gama.api.types.geometry.IPoint;
import gama.api.types.geometry.IShape;
import gama.api.types.list.GamaListFactory;
import gama.api.types.list.IList;
import gama.api.ui.IOutput;
import gama.api.ui.displays.IDisplayData;
import gama.api.ui.displays.IDisplayData.Changes;
import gama.api.ui.displays.IDisplaySurface;
import gama.api.ui.displays.IGraphics;
import gama.api.ui.displays.IGraphicsScope;
import gama.api.ui.layers.IEventLayerListener;
import gama.api.ui.layers.ILayer;
import gama.api.ui.layers.ILayerManager;
import gama.api.utils.geometry.IEnvelope;
import gama.api.utils.prefs.GamaPreferences;
import gama.core.outputs.display.LayerManager;
import gama.web.graphics.WebGLDisplayGraphics;
import gama.dev.DEBUG;

/**
 * A display surface that serializes layer data to JSON for WebGL rendering in the browser.
 * Instead of drawing to a BufferedImage, it collects all drawing commands as JSON objects
 * and sends them to JavaScript via postMessage for rendering with Three.js/WebGL.
 *
 * @author GAMA Team
 */
@display(value = "webgl")
@doc("A display surface that renders to WebGL via JSON serialization for browser-based rendering")
public class WebGLDisplaySurface implements IDisplaySurface {

    /** The output this surface is attached to. */
    private final IOutput.Display output;

    /** The display data (env dimensions, camera, colors, etc.). */
    private final IDisplayData data;

    /** The layer manager. */
    private ILayerManager manager;

    /** The graphics context for capturing drawing commands. */
    private WebGLDisplayGraphics displayGraphics;

    /** The scope for this surface. */
    protected IGraphicsScope scope;

    /** Surface dimensions. */
    private int width = 800;
    private int height = 600;

    /** Listeners for rendered frames. */
    private final List<FrameListener> frameListeners = new CopyOnWriteArrayList<>();

    /** Whether the surface has been disposed. */
    private volatile boolean disposed = false;

    /** Current frame number. */
    private long frameNumber = 0;

    /**
     * Listener interface for receiving rendered frame data.
     */
    @FunctionalInterface
    public interface FrameListener {
        /**
         * Called when a frame has been rendered and serialized to JSON.
         * @param frameData the JSON string containing all layer data
         * @param frameNum the frame number
         */
        void onFrameRendered(String frameData, long frameNum);
    }

    /**
     * Creates a new WebGL display surface.
     * @param output the display output
     * @param uiComponent unused (for API compatibility)
     */
    public WebGLDisplaySurface(final IOutput.Display output, final Object uiComponent) {
        this.output = output;
        this.data = output.getData();
        DEBUG.LOG("WebGL Display Surface created for simulation " + output.getScope().getSimulation());
    }

    @Override
    public void outputReloaded() {
        this.scope = output.getScope().copyForGraphics("in webgl surface of " + output.getName());
        if (!GamaPreferences.Runtime.ERRORS_IN_DISPLAYS.getValue()) { scope.disableErrorReporting(); }
        if (manager == null) {
            manager = new LayerManager(this, output);
        } else {
            manager.outputChanged();
        }
        // Create or recreate the graphics context
        if (displayGraphics != null) { displayGraphics.dispose(); }
        displayGraphics = new WebGLDisplayGraphics(width, height);
        displayGraphics.setDisplaySurface(this);
    }

    @Override
    public IGraphicsScope getScope() { return scope; }

    @Override
    public ILayerManager getManager() { return manager; }

    @Override
    public void updateDisplay(final boolean force, final GeneralSynchronizer synchronizer) {
        renderFrame();
        if (synchronizer != null) { synchronizer.release(); }
    }

    /**
     * Renders the current state by serializing all layers to JSON.
     */
    private void renderFrame() {
        if (displayGraphics == null || disposed) return;

        // Reset the graphics for a new frame
        displayGraphics.beginFrame(data.getBackgroundColor());

        // Let the layer manager draw all layers (which captures to JSON instead of rendering)
        if (manager != null) {
            manager.drawLayersOn(displayGraphics);
        }

        // Get the serialized JSON and notify listeners
        String frameJson = displayGraphics.getFrameJson();
        frameNumber++;

        for (FrameListener listener : frameListeners) {
            listener.onFrameRendered(frameJson, frameNumber);
        }
    }

    /**
     * Adds a listener for rendered frames.
     * @param listener the listener to add
     */
    public void addFrameListener(FrameListener listener) {
        frameListeners.add(listener);
    }

    /**
     * Removes a frame listener.
     * @param listener the listener to remove
     */
    public void removeFrameListener(FrameListener listener) {
        frameListeners.remove(listener);
    }

    @Override
    public void dispose() {
        disposed = true;
        if (displayGraphics != null) { displayGraphics.dispose(); }
        if (manager != null) { manager.dispose(); }
        frameListeners.clear();
        GAMA.releaseScope(scope);
    }

    @Override
    public int getWidth() { return width; }

    @Override
    public int getHeight() { return height; }

    @Override
    public void setSize(final int x, final int y) {
        this.width = x;
        this.height = y;
        if (displayGraphics != null) {
            displayGraphics.resize(x, y);
        }
    }

    @Override
    public double getEnvWidth() { return data.getEnvWidth(); }

    @Override
    public double getEnvHeight() { return data.getEnvHeight(); }

    @Override
    public double getDisplayWidth() { return width; }

    @Override
    public double getDisplayHeight() { return height; }

    @Override
    public double getZoomLevel() { return data.getZoomLevel(); }

    @Override
    public IOutput.Display getOutput() { return output; }

    @Override
    public IDisplayData getData() { return data; }

    @Override
    public boolean isVisible() { return true; }

    @Override
    public IGraphics getIGraphics() { return displayGraphics; }

    @Override
    public boolean shouldWaitToBecomeRendered() { return false; }

    @Override
    public boolean isDisposed() { return disposed; }

    // --- Methods below are no-ops for headless/web rendering ---

    @Override
    public void addListener(final IEventLayerListener e) {}

    @Override
    public void removeListener(final IEventLayerListener e) {}

    @Override
    public Collection<IEventLayerListener> getLayerListeners() { return Collections.emptyList(); }

    @Override
    public void layersChanged() {}

    @Override
    public void changed(final Changes property, final Object value) {}

    @Override
    public void zoomIn() {}

    @Override
    public void zoomOut() {}

    @Override
    public void zoomFit() {}

    @Override
    public void toggleLock() {}

    @Override
    public void focusOn(final IShape geometry) {}

    @Override
    public void setMenuManager(final Object displaySurfaceMenu) {}

    @Override
    public IPoint getModelCoordinatesFrom(final int xOnScreen, final int yOnScreen, final Point sizeInPixels,
            final Point positionInPixels) {
        return GamaPointFactory.getNullPoint();
    }

    @Override
    public IList<IAgent> selectAgent(final int xc, final int yc) {
        return GamaListFactory.getEmptyList();
    }

    @Override
    public void runAndUpdate(final Runnable r) { r.run(); }

    @Override
    public IEnvelope getVisibleRegionForLayer(final ILayer currentLayer) { return null; }

    @Override
    public int getFPS() { return 0; }

    @Override
    public void getModelCoordinatesInfo(final StringBuilder sb) {}

    @Override
    public void dispatchKeyEvent(final char character) {}

    @Override
    public void dispatchSpecialKeyEvent(final int e) {}

    @Override
    public void dispatchMouseEvent(final int swtEventType, final int x, final int y) {}

    @Override
    public void setMousePosition(final int x, final int y) {}

    @Override
    public void draggedTo(final int x, final int y) {}

    @Override
    public void selectAgentsAroundMouse() {}

    @Override
    public java.awt.image.BufferedImage getImage(final int w, final int h) {
        setSize(w, h);
        renderFrame();
        return null; // No BufferedImage in WebGL mode
    }

}
