package com.example.omrcapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: A4OverlayView
    private lateinit var serverEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var captureBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var saveBtn: Button

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var scanningQr = false
    private var serverUrl: String = ""

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera(false) else setStatus("Ứng dụng cần quyền camera để chụp phiếu.", true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        serverUrl = getPreferences(Context.MODE_PRIVATE).getString("server_url", "") ?: ""
        serverEdit.setText(serverUrl)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(false)
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = A4OverlayView(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 12)
            setBackgroundColor(Color.argb(170, 20, 30, 40))
        }

        val title = TextView(this).apply {
            text = "OMR Capture - Căn phiếu A4/A5 trong khung"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        serverEdit = EditText(this).apply {
            hint = "VD: http://192.168.1.10:5000"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            singleLine = true
            textSize = 14f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanBtn = Button(this).apply {
            text = "Quét QR"
            setOnClickListener { toggleQrScan() }
        }
        saveBtn = Button(this).apply {
            text = "Lưu IP"
            setOnClickListener { saveServerUrl() }
        }
        row.addView(scanBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1f))
        topPanel.addView(title)
        topPanel.addView(serverEdit)
        topPanel.addView(row)
        root.addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 22)
            setBackgroundColor(Color.argb(170, 20, 30, 40))
        }
        statusText = TextView(this).apply {
            text = "Đưa phiếu nằm gọn trong khung xanh, đủ 4 góc, không quá xa."
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        captureBtn = Button(this).apply {
            text = "📸 CHỤP VÀ GỬI VỀ MÁY TÍNH"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { takePhotoAndUpload() }
        }
        bottomPanel.addView(statusText)
        bottomPanel.addView(captureBtn)
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun saveServerUrl() {
        val normalized = normalizeServerUrl(serverEdit.text.toString())
        if (normalized.isBlank()) {
            setStatus("Chưa có địa chỉ PC Flask.", true)
            return
        }
        serverUrl = normalized
        serverEdit.setText(serverUrl)
        getPreferences(Context.MODE_PRIVATE).edit().putString("server_url", serverUrl).apply()
        setStatus("Đã lưu: $serverUrl", false)
    }

    private fun normalizeServerUrl(raw: String): String {
        var s = raw.trim()
        if (s.isBlank()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s.trimEnd('/')
    }

    private fun startCamera(enableQr: Boolean) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setTargetResolution(Size(1920, 2560))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .build()

            imageAnalysis = if (enableQr) {
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis -> analysis.setAnalyzer(cameraExecutor, QrAnalyzer { qrText -> onQrFound(qrText) }) }
            } else null

            try {
                cameraProvider.unbindAll()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (enableQr && imageAnalysis != null) {
                    cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, imageAnalysis)
                } else {
                    cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
                }
            } catch (e: Exception) {
                setStatus("Không mở được camera: ${e.message}", true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleQrScan() {
        scanningQr = !scanningQr
        scanBtn.text = if (scanningQr) "Dừng quét" else "Quét QR"
        setStatus(if (scanningQr) "Đưa QR chứa địa chỉ Flask vào khung." else "Đã dừng quét QR.", false)
        startCamera(scanningQr)
    }

    private fun onQrFound(text: String) {
        if (!scanningQr) return
        runOnUiThread {
            scanningQr = false
            scanBtn.text = "Quét QR"
            val normalized = normalizeServerUrl(text)
            serverUrl = normalized
            serverEdit.setText(serverUrl)
            getPreferences(Context.MODE_PRIVATE).edit().putString("server_url", serverUrl).apply()
            setStatus("Đã nhận QR: $serverUrl", false)
            startCamera(false)
        }
    }

    private fun takePhotoAndUpload() {
        saveServerUrl()
        if (serverUrl.isBlank()) return
        val capture = imageCapture ?: return
        captureBtn.isEnabled = false
        setStatus("Đang chụp ảnh...", false)

        val photoFile = File(cacheDir, "omr_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                uploadFile(photoFile)
            }
            override fun onError(exception: ImageCaptureException) {
                captureBtn.isEnabled = true
                setStatus("Chụp lỗi: ${exception.message}", true)
            }
        })
    }

    private fun uploadFile(file: File) {
        setStatus("Đang gửi ảnh về PC...", false)
        val client = OkHttpClient.Builder().build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder()
            .url("$serverUrl/api/upload_image")
            .post(body)
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    runOnUiThread {
                        captureBtn.isEnabled = true
                        if (response.isSuccessful) setStatus("✅ Đã gửi thành công. PC phản hồi:\n$text", false)
                        else setStatus("❌ PC báo lỗi ${response.code}:\n$text", true)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    captureBtn.isEnabled = true
                    setStatus("❌ Không kết nối được PC Flask. Kiểm tra cùng Wi-Fi/IP/tường lửa.\n${e.message}", true)
                }
            }
        }.start()
    }

    private fun setStatus(msg: String, error: Boolean) {
        statusText.text = msg
        statusText.setTextColor(if (error) Color.rgb(255, 170, 160) else Color.WHITE)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

class A4OverlayView(context: Context) : View(context) {
    private val dimPaint = Paint().apply { color = Color.argb(95, 0, 0, 0); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(46, 204, 113)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val frameW = w * 0.78f
        val frameH = frameW * 1.414f
        val left = (w - frameW) / 2f
        val top = (h - frameH) / 2f
        val rect = RectF(left, top, left + frameW, top + frameH)

        val path = Path().apply {
            addRect(0f, 0f, w, h, Path.Direction.CW)
            addRoundRect(rect, 18f, 18f, Path.Direction.CCW)
        }
        canvas.drawPath(path, dimPaint)
        canvas.drawRoundRect(rect, 18f, 18f, linePaint)

        val corner = 60f
        val p = linePaint
        canvas.drawLine(left, top, left + corner, top, p); canvas.drawLine(left, top, left, top + corner, p)
        canvas.drawLine(rect.right, top, rect.right - corner, top, p); canvas.drawLine(rect.right, top, rect.right, top + corner, p)
        canvas.drawLine(left, rect.bottom, left + corner, rect.bottom, p); canvas.drawLine(left, rect.bottom, left, rect.bottom - corner, p)
        canvas.drawLine(rect.right, rect.bottom, rect.right - corner, rect.bottom, p); canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - corner, p)
        canvas.drawText("Căn phiếu A4/A5 vào khung", w/2f, rect.bottom + 52f, textPaint)
    }
}

class QrAnalyzer(private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                val value = codes.firstOrNull { it.rawValue != null && (it.format == Barcode.FORMAT_QR_CODE || it.rawValue!!.contains("http")) }?.rawValue
                if (!value.isNullOrBlank()) onQr(value)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
