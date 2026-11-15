package com.example.reellimit.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.example.reellimit.R

class OverlayAlertManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val isShowing: Boolean
        get() = overlayView != null

    @SuppressLint("InflateParams")
    fun showAlert(appName: String, customMessage: String? = null, onDismiss: () -> Unit) {
        if (overlayView != null) {
            // Already showing
            return
        }

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120 // Top margin offset from status bar
        }

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.layout_overlay_alert, null)

        val tvMessage = view.findViewById<TextView>(R.id.tvAlertMessage)
        val btnOk = view.findViewById<Button>(R.id.btnOk)

        tvMessage.text = customMessage ?: "Usage of 5 mins for $appName exceeded!"

        btnOk.setOnClickListener {
            dismiss()
            onDismiss()
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dismiss() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            overlayView = null
        }
    }
}
