package com.simulation.mobile.vnc

import android.graphics.Color
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext

class VncViewManager : SimpleViewManager<VncView>() {

    companion object {
        const val REACT_CLASS = "VncView"
    }

    override fun getName(): String = REACT_CLASS

    override fun createViewInstance(context: ThemedReactContext): VncView {
        val view = VncView(context)
        view.setBackgroundColor(Color.BLACK)
        return view
    }
}
