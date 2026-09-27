package com.securebrowser.app.ui.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.securebrowser.app.R
import com.securebrowser.app.databinding.ActivityQrScannerBinding
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * قارئ رموز QR المدمج (v1.9.0) — طلب المستخدم: قراءة روابط المواقع
 * ونصوص البحث بشكل افتراضي واحترافي (بدون أي تطبيق خارجي).
 *
 * المعمارية:
 * - CameraX Preview (معاينة حية) + ImageAnalysis (إطارات YUV_420_888).
 * - الفك عبر ZXing MultiFormatReader (QR_CODE + DATA_MATRIX + AZTEC) على
 *   منفّذ خلفي، مع اقتصاص نافذة الإطار المرئية فقط (سرعة + دقة) وتصحيح
 *   دوران الجهاز (90/180/270 على مخطط الإضاءة Y).
 * - النتيجة عبر [QrResultPolicy] (رابط/بحث) — ثم يعود النص إلى
 *   BrowserActivity نفس مسار شريط العنوان تمامًا؛ **المسح لا يتجاوز
 *   SecurityEngine ولا القائمة البيضاء إطلاقًا**.
 * - الصلاحية: طلب وقت التشغيل مع شاشة تفسير واضحة عند الرفض — الكاميرا
 *   للمسح فقط، لا تصوير ولا تخزين أي إطار.
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding

    private val reader = MultiFormatReader()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var torchOn = false
    private var handlingResult = false
    private var lastDecodeAt = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.AZTEC
                ),
                DecodeHintType.TRY_HARDER to true
            )
        )

        binding.btnQrClose.setOnClickListener { finish() }
        binding.btnQrTorch.setOnClickListener { toggleTorch() }
        binding.btnQrGrant.setOnClickListener {
            binding.permissionState.isVisible = false
            requestPermission()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission()
        }
    }

    private fun requestPermission() {
        runCatching {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }.onFailure { showPermissionDenied() }
    }

    private fun showPermissionDenied() {
        binding.cameraSurface.isVisible = false
        binding.scanOverlay.isVisible = false
        binding.btnQrTorch.isVisible = false
        binding.qrHint.isVisible = false
        binding.permissionState.isVisible = true
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraSurface.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                // تعذر تشغيل الكاميرا — رسالة ودودة دون تعرض تفاصيل تقنية
                showErrorState()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        camera?.cameraControl?.enableTorch(torchOn)
        binding.btnQrTorch.alpha = if (torchOn) 1f else 0.55f
    }

    // ————————————————— التحليل الحي —————————————————

    private fun analyzeFrame(proxy: ImageProxy) {
        if (handlingResult) {
            proxy.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastDecodeAt < DECODE_THROTTLE_MS) {
            proxy.close()
            return
        }
        lastDecodeAt = now
        try {
            val text = decode(proxy)
            if (!text.isNullOrBlank() && !handlingResult) {
                handlingResult = true
                runOnUiThread { onDecoded(text) }
            }
        } catch (e: Exception) {
            // أي إطار غير قابل للفك يُتجاهل — المسح مستمر
        } finally {
            proxy.close()
        }
    }

    /**
     * فك إطار YUV: مستوى الإضاءة (Y) فقط يكفي QR — تدوير حسب الجهاز
     * ثم اقتصاص نافذة الطبقة المرئية ثم Binarizer محسّن للإضاءة المختلطة.
     */
    private fun decode(proxy: ImageProxy): String? {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        var data = bytes
        var width = proxy.width
        var height = proxy.height
        val rotation = proxy.imageInfo.rotationDegrees
        when (rotation) {
            90 -> {
                data = rotateY90(data, width, height)
                val t = width; width = height; height = t
            }
            180 -> data = rotateY180(data)
            270 -> {
                data = rotateY270(data, width, height)
                val t = width; width = height; height = t
            }
        }

        val overlay = binding.scanOverlay
        val crop = overlay.windowRect(width, height)
        var left = max(0, crop.left.toInt())
        var top = max(0, crop.top.toInt())
        var cropW = min(width, crop.width().toInt())
        var cropH = min(height, crop.height().toInt())
        if (cropW < MIN_CROP_PX || cropH < MIN_CROP_PX) {
            // نافذة أصغر من اللازم (شاشة ضيقة) — فك الإطار كاملًا
            left = 0; top = 0; cropW = width; cropH = height
        }
        left = min(left, width - cropW)
        top = min(top, height - cropH)

        val source = PlanarYUVLuminanceSource(data, width, height, left, top, cropW, cropH, false)
        return try {
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            result.text
        } catch (e: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    /** تدوير مخطط الإضاءة 90° باتجاه عقارب الساعة (البُعدان يتبادلان). */
    private fun rotateY90(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        var index = 0
        for (x in 0 until w) {
            for (y in h - 1 downTo 0) {
                out[index++] = src[y * w + x]
            }
        }
        return out
    }

    /** تدوير 180° — انعكاس كامل. */
    private fun rotateY180(src: ByteArray): ByteArray {
        val out = ByteArray(src.size)
        for (i in src.indices) out[i] = src[src.size - 1 - i]
        return out
    }

    /** تدوير 270° (أي 90° عكس عقارب الساعة). */
    private fun rotateY270(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        var index = 0
        for (x in w - 1 downTo 0) {
            for (y in 0 until h) {
                out[index++] = src[y * w + x]
            }
        }
        return out
    }

    // ————————————————— النتيجة —————————————————

    private fun onDecoded(raw: String) {
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val action = QrResultPolicy.handle(raw)
        if (action == null) {
            // رمز فارغ بعد التنظيف — استئناف المسح
            handlingResult = false
            return
        }
        val cleaned = when (action) {
            is QrResultPolicy.QrAction.OpenUrl -> action.url
            is QrResultPolicy.QrAction.Search -> action.query
        }
        val isUrl = action is QrResultPolicy.QrAction.OpenUrl

        val preview = TextView(this).apply {
            text = cleaned
            setTextIsSelectable(true)
            textSize = 14f
            maxLines = 10
            setVerticalScrollBarEnabled(true)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            movementMethod = android.text.method.ScrollingMovementMethod()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    if (isUrl) R.string.qr_type_link else R.string.qr_type_text
                )
            )
            .setView(preview)
            .setPositiveButton(
                if (isUrl) R.string.qr_action_open else R.string.qr_action_search
            ) { _, _ ->
                deliver(cleaned)
            }
            .setNeutralButton(R.string.qr_action_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("qr", cleaned))
                android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
                handlingResult = false
            }
            .setNegativeButton(R.string.qr_action_rescan) { _, _ ->
                handlingResult = false
            }
            .setOnCancelListener { handlingResult = false }
            .show()
    }

    private fun deliver(text: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_TEXT, text))
        finish()
    }

    private fun showErrorState() {
        binding.qrHint.text = getString(R.string.qr_camera_failed)
        binding.qrHint.isVisible = true
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_TEXT = "extra_qr_result_text"

        /** مهلة بين محاولتي فك — إبطاء خفيف يوفر بطارية/معالجًا دون إحساس بالتأخير. */
        private const val DECODE_THROTTLE_MS = 110L

        /** أدنى نافذة اقتصاص معقولة (px). */
        private const val MIN_CROP_PX = 48

        fun intent(context: Context): Intent = Intent(context, QrScannerActivity::class.java)
    }
}
