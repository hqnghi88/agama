package com.simulation.mobile.vnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule

class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val TAG = "VncView"
        private const val LEFT_BUTTON = 1
    }

    private var rfbClient: VncRfbClient? = null
    private var frameBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var destRect = RectF()
    private var pointerButtonMask = 0

    private val keysymMap = mapOf(
        KeyEvent.KEYCODE_A to 0x0061, KeyEvent.KEYCODE_B to 0x0062,
        KeyEvent.KEYCODE_C to 0x0063, KeyEvent.KEYCODE_D to 0x0064,
        KeyEvent.KEYCODE_E to 0x0065, KeyEvent.KEYCODE_F to 0x0066,
        KeyEvent.KEYCODE_G to 0x0067, KeyEvent.KEYCODE_H to 0x0068,
        KeyEvent.KEYCODE_I to 0x0069, KeyEvent.KEYCODE_J to 0x006A,
        KeyEvent.KEYCODE_K to 0x006B, KeyEvent.KEYCODE_L to 0x006C,
        KeyEvent.KEYCODE_M to 0x006D, KeyEvent.KEYCODE_N to 0x006E,
        KeyEvent.KEYCODE_O to 0x006F, KeyEvent.KEYCODE_P to 0x0070,
        KeyEvent.KEYCODE_Q to 0x0071, KeyEvent.KEYCODE_R to 0x0072,
        KeyEvent.KEYCODE_S to 0x0073, KeyEvent.KEYCODE_T to 0x0074,
        KeyEvent.KEYCODE_U to 0x0075, KeyEvent.KEYCODE_V to 0x0076,
        KeyEvent.KEYCODE_W to 0x0077, KeyEvent.KEYCODE_X to 0x0078,
        KeyEvent.KEYCODE_Y to 0x0079, KeyEvent.KEYCODE_Z to 0x007A,
        KeyEvent.KEYCODE_0 to 0x0030, KeyEvent.KEYCODE_1 to 0x0031,
        KeyEvent.KEYCODE_2 to 0x0032, KeyEvent.KEYCODE_3 to 0x0033,
        KeyEvent.KEYCODE_4 to 0x0034, KeyEvent.KEYCODE_5 to 0x0035,
        KeyEvent.KEYCODE_6 to 0x0036, KeyEvent.KEYCODE_7 to 0x0037,
        KeyEvent.KEYCODE_8 to 0x0038, KeyEvent.KEYCODE_9 to 0x0039,
        KeyEvent.KEYCODE_SPACE to 0x0020, KeyEvent.KEYCODE_ENTER to 0xFF0D,
        KeyEvent.KEYCODE_TAB to 0xFF09, KeyEvent.KEYCODE_DEL to 0xFF08,
        KeyEvent.KEYCODE_ESCAPE to 0xFF1B, KeyEvent.KEYCODE_SHIFT_LEFT to 0xFFE1,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xFFE2, KeyEvent.KEYCODE_CTRL_LEFT to 0xFFE3,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xFFE4, KeyEvent.KEYCODE_ALT_LEFT to 0xFFE9,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xFFEA, KeyEvent.KEYCODE_META_LEFT to 0xFFEB,
        KeyEvent.KEYCODE_META_RIGHT to 0xFFEC, KeyEvent.KEYCODE_CAPS_LOCK to 0xFFE5,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xFF51, KeyEvent.KEYCODE_DPAD_UP to 0xFF52,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xFF53, KeyEvent.KEYCODE_DPAD_DOWN to 0xFF54,
        KeyEvent.KEYCODE_PAGE_UP to 0xFF55, KeyEvent.KEYCODE_PAGE_DOWN to 0xFF56,
        KeyEvent.KEYCODE_MOVE_HOME to 0xFF50, KeyEvent.KEYCODE_MOVE_END to 0xFF57,
        KeyEvent.KEYCODE_F1 to 0xFFBE, KeyEvent.KEYCODE_F2 to 0xFFBF,
        KeyEvent.KEYCODE_F3 to 0xFFC0, KeyEvent.KEYCODE_F4 to 0xFFC1,
        KeyEvent.KEYCODE_F5 to 0xFFC2, KeyEvent.KEYCODE_F6 to 0xFFC3,
        KeyEvent.KEYCODE_F7 to 0xFFC4, KeyEvent.KEYCODE_F8 to 0xFFC5,
        KeyEvent.KEYCODE_F9 to 0xFFC6, KeyEvent.KEYCODE_F10 to 0xFFC7,
        KeyEvent.KEYCODE_F11 to 0xFFC8, KeyEvent.KEYCODE_F12 to 0xFFC9,
        KeyEvent.KEYCODE_MINUS to 0x002D, KeyEvent.KEYCODE_EQUALS to 0x003D,
        KeyEvent.KEYCODE_COMMA to 0x002C, KeyEvent.KEYCODE_PERIOD to 0x002E,
        KeyEvent.KEYCODE_SEMICOLON to 0x003B, KeyEvent.KEYCODE_APOSTROPHE to 0x0027,
        KeyEvent.KEYCODE_SLASH to 0x002F, KeyEvent.KEYCODE_BACKSLASH to 0x005C,
        KeyEvent.KEYCODE_GRAVE to 0x0060, KeyEvent.KEYCODE_LEFT_BRACKET to 0x005B,
        KeyEvent.KEYCODE_RIGHT_BRACKET to 0x005D,
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    private fun viewToFb(viewX: Float, viewY: Float): Pair<Int, Int> {
        val bitmap = frameBitmap ?: return Pair(0, 0)
        val vw = width.toFloat(); val vh = height.toFloat()
        val fbw = bitmap.width.toFloat(); val fbh = bitmap.height.toFloat()
        if (vw <= 0 || vh <= 0 || fbw <= 0 || fbh <= 0) return Pair(0, 0)
        val scale = minOf(vw / fbw, vh / fbh)
        val bw = fbw * scale; val bh = fbh * scale
        val ox = (vw - bw) / 2; val oy = (vh - bh) / 2
        val fx = ((viewX - ox) / scale).toInt().coerceIn(0, fbw.toInt() - 1)
        val fy = ((viewY - oy) / scale).toInt().coerceIn(0, fbh.toInt() - 1)
        return Pair(fx, fy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.i(TAG, "View size: ${w}x${h}")
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val client = rfbClient
        if (client == null) {
            Log.w(TAG, "dispatchTouchEvent: rfbClient is null")
            return false
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        val (fx, fy) = viewToFb(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Log.d(TAG, "Touch DOWN at view=(${event.x},${event.y}) fb=($fx,$fy)")
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                pointerButtonMask = LEFT_BUTTON
                client.sendPointerEvent(fx, fy, pointerButtonMask)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                Log.d(TAG, "Touch MOVE at fb=($fx,$fy)")
                client.sendPointerEvent(fx, fy, pointerButtonMask)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Log.d(TAG, "Touch UP at fb=($fx,$fy)")
                client.sendPointerEvent(fx, fy, 0)
                pointerButtonMask = 0
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val client = rfbClient ?: return false
            val (fx, fy) = viewToFb(event.x, event.y)
            val buttons = event.buttonState
            val mask = ((buttons and MotionEvent.BUTTON_PRIMARY) shr 0) or
                ((buttons and MotionEvent.BUTTON_TERTIARY) shr 1) or
                ((buttons and MotionEvent.BUTTON_SECONDARY) shl 1)
            client.sendPointerEvent(fx, fy, mask)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || event.repeatCount > 0) return super.onKeyDown(keyCode, event)
        val keysym = keysymMap[keyCode] ?: return super.onKeyDown(keyCode, event)
        rfbClient?.sendKeyEvent(keysym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val keysym = keysymMap[keyCode] ?: return super.onKeyUp(keyCode, event)
        rfbClient?.sendKeyEvent(keysym, false)
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                for (ch in text) {
                    val keysym = if (ch.code in 0x0020..0x007E) ch.code
                    else if (ch == '\n') 0xFF0D else continue
                    rfbClient?.sendKeyEvent(keysym, true)
                    rfbClient?.sendKeyEvent(keysym, false)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { rfbClient?.sendKeyEvent(0xFF08, true); rfbClient?.sendKeyEvent(0xFF08, false) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val keysym = keysymMap[event.keyCode]
                if (keysym != null) {
                    rfbClient?.sendKeyEvent(keysym, event.action == KeyEvent.ACTION_DOWN)
                }
                return true
            }

            override fun performEditorAction(actionCode: Int) = true
        }
    }

    fun start() {
        if (rfbClient != null) return
        Log.i(TAG, "Starting VNC client")
        emitState("connecting")
        rfbClient = VncRfbClient()
        rfbClient?.start(
            onFrame = { bitmap ->
                frameBitmap = bitmap
                postInvalidate()
            },
            onStateChange = { state ->
                Log.i(TAG, "State: $state")
                when (state) {
                    VncRfbClient.VncState.CONNECTED -> emitState("connected")
                    VncRfbClient.VncState.ERROR -> emitState("error")
                    VncRfbClient.VncState.DISCONNECTED -> {
                        if (rfbClient != null) {
                            emitState("connecting")
                            start()
                        }
                    }
                    else -> {}
                }
            }
        )
    }

    fun stop() {
        Log.i(TAG, "Stopping VNC view")
        rfbClient?.stop()
        rfbClient = null
    }

    private fun emitState(state: String) {
        val map = Arguments.createMap().apply {
            putString("state", state)
        }
        try {
            val reactContext = context as ReactContext
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("VncStateChange", map)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to emit state: ${e.message}")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frameBitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val fbw = bitmap.width.toFloat()
        val fbh = bitmap.height.toFloat()
        if (fbw <= 0 || fbh <= 0) return
        val scale = minOf(vw / fbw, vh / fbh)
        val bw = fbw * scale
        val bh = fbh * scale
        destRect.set((vw - bw) / 2, (vh - bh) / 2, (vw + bw) / 2, (vh + bh) / 2)
        canvas.drawBitmap(bitmap, null, destRect, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
