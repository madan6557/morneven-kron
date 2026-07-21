package com.morneven.kron.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "KRON_CAMERAX"

@Composable
fun CameraCaptureScreen(
    onPhotoCaptured: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequested) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission && permissionRequested) {
        android.widget.Toast.makeText(
            context,
            "Izin kamera diperlukan untuk mengambil foto",
            android.widget.Toast.LENGTH_LONG
        ).show()
        onCancel()
        return
    }

    if (!hasCameraPermission) {
        return // waiting for permission dialog
    }

    CameraPreview(
        context = context,
        onPhotoCaptured = onPhotoCaptured,
        onCancel = onCancel
    )
}

@Composable
private fun CameraPreview(
    context: Context,
    onPhotoCaptured: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        ctx as? androidx.lifecycle.LifecycleOwner ?: return@AndroidView previewView,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind error", e)
                }

                previewView
            }
        )

        FloatingActionButton(
            onClick = {
                takePhoto(context, imageCapture, cameraExecutor) { uri ->
                    onPhotoCaptured(uri)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(72.dp),
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Ambil foto",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    cameraExecutor: ExecutorService,
    onPhotoSaved: (Uri) -> Unit
) {
    val photoFile = createReceiptFile(context)
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        cameraExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d(TAG, "Photo saved: ${photoFile.absolutePath}")
                val uri = Uri.fromFile(photoFile)
                onPhotoSaved(uri)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exception.imageCaptureError}", exception)
            }
        }
    )
}

private fun createReceiptFile(context: Context): File {
    val dir = File(context.filesDir, "receipts")
    if (!dir.exists()) dir.mkdirs()
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    return File(dir, "receipt_${timestamp}_camerax.jpg")
}
