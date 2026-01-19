package com.sentinel.ai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.sentinel.ai.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScannerActivity : BaseLocalizedActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var barcodeScanner: BarcodeScanner? = null
    private var isProcessing = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.qr_camera_permission_required), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private var camera: androidx.camera.core.Camera? = null
    private var isFlashOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        previewView = findViewById(R.id.previewView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        
        val btnFlash = findViewById<android.widget.ImageView>(R.id.btnFlash)
        btnFlash.setOnClickListener {
            toggleFlash(btnFlash)
        }

        // Start laser animation
        val laserLine = findViewById<android.view.View>(R.id.laserLine)
        val animation = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.qr_laser_anim)
        laserLine.startAnimation(animation)

        // Initialize barcode scanner
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_ALL_FORMATS)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleFlash(btnFlash: android.widget.ImageView) {
        camera?.let {
            isFlashOn = !isFlashOn
            it.cameraControl.enableTorch(isFlashOn)
            btnFlash.setImageResource(R.drawable.ic_flash)
            // Just use different tint if possible, or different icon if available
            btnFlash.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isFlashOn) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.qr_camera_start_failed_format, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessing = true
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner?.process(inputImage)
            ?.addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrBlank()) {
                        // Found a barcode, return the result
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_SCANNED_CONTENT, rawValue)
                            putExtra(EXTRA_BARCODE_TYPE, barcode.valueType)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                        return@addOnSuccessListener
                    }
                }
                isProcessing = false
            }
            ?.addOnFailureListener {
                isProcessing = false
            }
            ?.addOnCompleteListener {
                imageProxy.close()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner?.close()
    }

    companion object {
        const val EXTRA_SCANNED_CONTENT = "scanned_content"
        const val EXTRA_BARCODE_TYPE = "barcode_type"
    }
}
