package com.example.colorpicker

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.palette.graphics.Palette
import com.example.colorpicker.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val RATIO_4_3_VALUE = 4.0 / 3.0
    private val RATIO_16_9_VALUE = 16.0 / 9.0
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var targetResolution: Size? = null
    private var scanAreaTop = 0
    private var scanAreaLeft = 0
    private var scanAreaBottom = 0
    private var scanAreaRight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initialize()
    }

    private fun initialize() {
        // Set initial target resolution based on the root layout size
        targetResolution = Size(binding.root.width, binding.root.height)
        // Listen for layout changes to update target resolution
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            targetResolution = Size(binding.root.width, binding.root.height)
        }

        // Calculate scan area position of crossMark
        binding.crossMark.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scanAreaLeft = binding.crossMark.left
                scanAreaTop = binding.crossMark.top
                scanAreaRight = binding.crossMark.right
                scanAreaBottom = binding.crossMark.bottom

                // Remove the listener to prevent being called again unnecessarily
                binding.crossMark.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })

        cameraPermissions()

        // Set click listener for capture button
        binding.buttonCapture.setOnClickListener { takePhoto() }
    }

    private fun cameraPermissions() {
        if (arePermissionsGranted()) {
            openCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun arePermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // The ratio for the output image and preview
            val metrics = Resources.getSystem().displayMetrics
            val aspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)

            var rotation = Surface.ROTATION_0
            // Ensure previewView and its display are initialized
            binding.previewView.display?.let { display ->
                rotation = display.rotation
            }
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                .build()

            // Preview
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
            binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

            // Image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(rotation)
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                Log.d("Camera", "Camera setup successfully")

            } catch (e: Exception) {
                Log.e("Camera", "Error setting up camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
                @OptIn(ExperimentalGetImage::class)
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    super.onCaptureSuccess(imageProxy)

                    // Process the captured image
                    val capturedImage = imageProxy.image
                    if (capturedImage != null) {
                        // Convert Image to Bitmap
                        val bitmap: Bitmap =
                            imageToBitmap(capturedImage, imageProxy.imageInfo.rotationDegrees)

                        // Close the image proxy
                        imageProxy.close()

                        // Process the captured image
                        processCapturedImage(bitmap)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("Camera", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(this@MainActivity, "Photo capture failed", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun getColorUnderCrossMark(bitmap: Bitmap): Int {
        // Calculate the center coordinates of the cross mark
        val crossMarkCenterX = (scanAreaLeft + scanAreaRight) / 2
        val crossMarkCenterY = (scanAreaTop + scanAreaBottom) / 2

        // Ensure the center coordinates are within the bounds of the bitmap
        if (crossMarkCenterX < 0 || crossMarkCenterY < 0 || crossMarkCenterX >= bitmap.width || crossMarkCenterY >= bitmap.height) {
            Log.w("ColorPicker", "Cross mark coordinates are out of bounds.")
            return Color.TRANSPARENT
        }

        // Calculate half the size of the cross mark (in pixels)
        val crossMarkSizePx = dpToPx(25)

        // Calculate the coordinates for the Rect around the cross mark
        val left = max(0, crossMarkCenterX - crossMarkSizePx / 2)
        val top = max(0, crossMarkCenterY - crossMarkSizePx / 2)
        val right = min(bitmap.width, crossMarkCenterX + crossMarkSizePx / 2)
        val bottom = min(bitmap.height, crossMarkCenterY + crossMarkSizePx / 2)

        // Create a Rect around the cross mark
        val rect = Rect(left, top, right, bottom)

        // Create a cropped bitmap using the Rect
        val croppedBitmap = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())

        // Generate Palette from the cropped bitmap
        val palette = Palette.from(croppedBitmap).generate()

        // Get colors from all swatches
        val lightVibrantColor = palette.getLightVibrantColor(Color.TRANSPARENT)
        val vibrantColor = palette.getVibrantColor(Color.TRANSPARENT)
        val darkVibrantColor = palette.getDarkVibrantColor(Color.TRANSPARENT)
        val lightMutedColor = palette.getLightMutedColor(Color.TRANSPARENT)
        val mutedColor = palette.getMutedColor(Color.TRANSPARENT)
        val darkMutedColor = palette.getDarkMutedColor(Color.TRANSPARENT)

        // Determine which color to return
        val selectedColor = when {
            vibrantColor != Color.TRANSPARENT -> vibrantColor
            lightVibrantColor != Color.TRANSPARENT -> lightVibrantColor
            darkVibrantColor != Color.TRANSPARENT -> darkVibrantColor
            mutedColor != Color.TRANSPARENT -> mutedColor
            lightMutedColor != Color.TRANSPARENT -> lightMutedColor
            darkMutedColor != Color.TRANSPARENT -> darkMutedColor
            else ->
                Color.TRANSPARENT
        }
        if (selectedColor == Color.TRANSPARENT) {
            showToast()
        }

        // Log the selected color for debugging
        Log.d("ColorPicker", "Selected Color: $selectedColor")

        // Return the selected color
        return selectedColor
    }
    private fun showToast() {
        Toast.makeText(this, "No suitable color found", Toast.LENGTH_SHORT).show()
    }

    // Function to convert dp to pixels
    private fun dpToPx(dp: Int): Int {
        return (dp * Resources.getSystem().displayMetrics.density).toInt()
    }

    private fun processCapturedImage(bitmap: Bitmap) {
        // Extract color under cross mark
        val colorUnderCrossMark = getColorUnderCrossMark(bitmap)
        applyColorToUI(colorUnderCrossMark)
    }

    private fun applyColorToUI(color: Int) {
        // Set background color of a view
        binding.capturedColor.setBackgroundColor(color)
        val hexColor = String.format("#%06X", 0xFFFFFF and color)
        binding.hexColor.text = hexColor
        Log.e("ColorPicker", "Captured Color: $hexColor")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun imageToBitmap(image: Image, orientation: Int): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer[bytes]

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix()
        matrix.postRotate(orientation.toFloat())
        val rotateBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return Bitmap.createScaledBitmap(rotateBitmap, binding.previewView.width, binding.previewView.height, true)
    }
}


