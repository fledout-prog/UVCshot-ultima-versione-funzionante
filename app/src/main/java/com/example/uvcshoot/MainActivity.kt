package com.example.uvcshoot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Camera preview Activity.
 *
 * In questa versione facciamo una prova volutamente MINIMA:
 * - niente decision tree aggressivo in surfaceCreated/onResume/onServiceConnected
 * - niente hard recover immediato appena cambia il lifecycle
 * - facciamo sempre prima un riaggancio leggero della surface
 * - poi, con piccolo delay, se lo stream non è attivo, riavviamo SOLO lo stream
 *
 * Obiettivo:
 * eliminare la race causata da decisioni di recovery troppo precoci.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RESUME_RESTART_DELAY_MS = 120L
    }

    private lateinit var previewSurface: SurfaceView
    private lateinit var captureButton: Button
    private lateinit var statusText: TextView

    private var cameraService: CameraService? = null
    private var serviceBound = false

    /**
     * The currently pending debounced stream-check [Runnable], or null if none is
     * scheduled.  Replaced (with prior cancellation) by every lifecycle hook that
     * needs a delayed restart check, so at most ONE delayed check is ever pending
     * regardless of how many lifecycle events fire in quick succession.
     */
    private var pendingResumeCheck: Runnable? = null

    // -----------------------------------------------------------------------
    // Service connection
    // -----------------------------------------------------------------------

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            cameraService = (binder as CameraService.LocalBinder).getService()
            serviceBound = true

            val holder = previewSurface.holder
            val surfaceValid = holder.surface.isValid

            Log.d(
                TAG,
                "UVC_FIX: SERVICE_BIND — surfaceValid=$surfaceValid streaming=${cameraService?.isStreaming()}"
            )

            if (surfaceValid) {
                val surface = holder.surface

                // Riaggancio leggero sempre, senza partire con recovery pesanti.
                cameraService?.attachSurface(surface)

                // Single debounced check: cancels any already-pending check so that
                // rapid onServiceConnected + surfaceCreated + onResume bursts produce
                // only ONE delayed restart, not three concurrent ones.
                scheduleStreamCheck()
            } else {
                Log.d(TAG, "UVC_FIX: SERVICE_BIND — surface non ancora valida, aspetto surfaceCreated")
            }

            updateStatus("Camera service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "UVC_FIX: SERVICE_DISCONNECT — unexpected service disconnect")
            cameraService = null
            serviceBound = false
            updateStatus("Camera service disconnected")
        }
    }

    // -----------------------------------------------------------------------
    // SurfaceHolder callbacks
    // -----------------------------------------------------------------------

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val surface = holder.surface

            Log.d(
                TAG,
                "UVC_FIX: SURFACE_CREATE — serviceBound=$serviceBound streaming=${cameraService?.isStreaming()} surfaceHash=${System.identityHashCode(surface)}"
            )

            if (!serviceBound) {
                Log.d(TAG, "UVC_FIX: SURFACE_CREATE — service non ancora bound, niente azione")
                return
            }

            // PATCH MINIMA:
            // 1) riaggancia sempre la surface
            cameraService?.attachSurface(surface)

            // 2) single debounced check — cancels any already-pending restart check
            //    so onServiceConnected + surfaceCreated + onResume bursts produce only
            //    ONE delayed restart, not three concurrent ones.
            scheduleStreamCheck()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(
                TAG,
                "UVC_FIX: SURFACE_CHANGE — ${width}x${height} serviceBound=$serviceBound streaming=${cameraService?.isStreaming()} surfaceHash=${System.identityHashCode(holder.surface)}"
            )

            if (!serviceBound) return

            // Refresh leggero del binding della surface.
            cameraService?.attachSurface(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(
                TAG,
                "UVC_FIX: SURFACE_DESTROY — serviceBound=$serviceBound streaming=${cameraService?.isStreaming()} surfaceHash=${System.identityHashCode(holder.surface)}"
            )

            // Manteniamo il comportamento della baseline attuale.
            cameraService?.onSurfaceDestroyed()
        }
    }

    // -----------------------------------------------------------------------
    // Activity lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewSurface = findViewById(R.id.previewSurface)
        captureButton = findViewById(R.id.captureButton)
        statusText = findViewById(R.id.statusText)

        previewSurface.holder.addCallback(surfaceCallback)
        captureButton.setOnClickListener { triggerCapture() }

        Log.d(TAG, "UVC_FIX: ACT_CREATE — binding to CameraService")
        updateStatus("Starting camera service…")
        bindToService()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "UVC_FIX: ACT_START — serviceBound=$serviceBound")
    }

    override fun onResume() {
        super.onResume()

        val holder = previewSurface.holder
        val surfaceValid = holder.surface.isValid

        Log.d(
            TAG,
            "UVC_FIX: ACT_RESUME — serviceBound=$serviceBound surfaceValid=$surfaceValid streaming=${cameraService?.isStreaming()}"
        )

        if (serviceBound && surfaceValid) {
            val surface = holder.surface

            // Niente decision tree aggressivo qui:
            // prima riaggancio leggero.
            cameraService?.attachSurface(surface)

            // Single debounced check — cancels any already-pending restart check so that
            // onResume + surfaceCreated + onServiceConnected bursts produce only ONE
            // delayed restart, not three concurrent ones.
            scheduleStreamCheck()
        }
    }

    override fun onStop() {
        Log.d(TAG, "UVC_FIX: ACT_STOP — keeping service binding alive")
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "UVC_FIX: ACT_DESTROY — unbinding service")
        previewSurface.holder.removeCallback(surfaceCallback)

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            cameraService = null
        }

        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Hardware trigger support
    // -----------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA -> {
                Log.d(TAG, "Hardware key trigger: keyCode=$keyCode")
                triggerCapture()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun triggerCapture() {
        Log.d(TAG, "triggerCapture — serviceBound=$serviceBound")
        cameraService?.requestCapture()
            ?: Log.w(TAG, "triggerCapture: service not yet bound")
        updateStatus("Capture requested")
    }

    /**
     * Schedule a debounced stream-restart check.
     *
     * Cancels any previously pending check before scheduling a new one so that
     * rapid [onServiceConnected] + [surfaceCreated] + [onResume] bursts within the
     * same startup or resume cycle produce exactly ONE delayed restart attempt,
     * eliminating the race from three concurrent [restartPreviewStreamOnly] calls.
     */
    private fun scheduleStreamCheck() {
        pendingResumeCheck?.let { previewSurface.removeCallbacks(it) }
        val r = Runnable {
            pendingResumeCheck = null
            val service = cameraService ?: return@Runnable
            Log.d(TAG, "UVC_FIX: DEBOUNCED check — streaming=${service.isStreaming()}")
            if (!service.isStreaming()) {
                Log.d(TAG, "UVC_FIX: DEBOUNCED → restartPreviewStreamOnly()")
                service.restartPreviewStreamOnly()
            }
        }
        pendingResumeCheck = r
        previewSurface.postDelayed(r, RESUME_RESTART_DELAY_MS)
    }

    /**
     * Bind soltanto al CameraService.
     * Non facciamo partire qui un foreground service esplicito.
     */
    private fun bindToService() {
        val intent = Intent(this, CameraService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "bindToService")
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
    }
}