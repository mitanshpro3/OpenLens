package org.openlens.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var captureBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var flashBtn: Button
    private lateinit var exposure: SeekBar

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var useBack = true
    private var videoMode = false
    private var flashOn = false

    private val perms = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()

    private val askPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.CAMERA] == true) start()
            else { toast("Camera permission is required"); finish() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        captureBtn = findViewById(R.id.capture)
        modeBtn = findViewById(R.id.mode)
        flashBtn = findViewById(R.id.flash)
        exposure = findViewById(R.id.exposure)

        captureBtn.setOnClickListener { if (videoMode) toggleRecording() else takePhoto() }
        modeBtn.setOnClickListener {
            if (recording == null) { videoMode = !videoMode; updateLabels(); bind() }
        }
        findViewById<Button>(R.id.flip).setOnClickListener {
            if (recording == null) { useBack = !useBack; bind() }
        }
        flashBtn.setOnClickListener { flashOn = !flashOn; applyFlash() }
        exposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val c = camera ?: return
                if (fromUser) c.cameraControl.setExposureCompensationIndex(
                    p + c.cameraInfo.exposureState.exposureCompensationRange.lower
                )
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        setupGestures()
        updateLabels()
        applyFlash()
        if (perms.all { has(it) }) start() else askPerms.launch(perms)
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun updateLabels() {
        captureBtn.text = if (videoMode) "Record" else "Shoot"
        modeBtn.text = if (videoMode) "Photo" else "Video"
    }

    private fun start() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val selector =
            if (useBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        p.unbindAll()
        imageCapture = null
        videoCapture = null
        try {
            camera = if (videoMode) {
                val vc = VideoCapture.withOutput(
                    Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
                )
                videoCapture = vc
                p.bindToLifecycle(this, selector, prev, vc)
            } else {
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                imageCapture = ic
                p.bindToLifecycle(this, selector, prev, ic)
            }
        } catch (e: Exception) {
            toast("Camera error: ${e.message}")
            return
        }
        val range = camera!!.cameraInfo.exposureState.exposureCompensationRange
        exposure.visibility = if (range.upper == 0 && range.lower == 0) View.GONE else View.VISIBLE
        exposure.max = range.upper - range.lower
        exposure.progress = -range.lower
        applyFlash()
    }

    private fun applyFlash() {
        flashBtn.text = if (flashOn) "Flash: on" else "Flash: off"
        imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        camera?.cameraControl?.enableTorch(flashOn && videoMode)
    }

    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = camera ?: return true
                val z = c.cameraInfo.zoomState.value ?: return true
                c.cameraControl.setZoomRatio(
                    (z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                )
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val point = preview.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                return true
            }
        })
        preview.setOnTouchListener { _, e -> scale.onTouchEvent(e); tap.onTouchEvent(e); true }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/OpenLens")
        }
        val out = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        ic.takePicture(out, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) = toast("Photo saved")
            override fun onError(e: ImageCaptureException) = toast("Error: ${e.message}")
        })
    }

    private fun toggleRecording() {
        val vc = videoCapture ?: return
        recording?.let { it.stop(); recording = null; return }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/OpenLens")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, opts)
        if (has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Start) captureBtn.text = "Stop"
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                updateLabels()
                toast(if (ev.hasError()) "Recording error" else "Video saved")
            }
        }
    }
}
