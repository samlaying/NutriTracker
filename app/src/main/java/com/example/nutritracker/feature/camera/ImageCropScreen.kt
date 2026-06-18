package com.example.nutritracker.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smarttoolfactory.cropper.ImageCropper
import com.smarttoolfactory.cropper.settings.CropDefaults
import com.smarttoolfactory.cropper.settings.CropOutlineProperty
import com.smarttoolfactory.cropper.model.OutlineType
import com.smarttoolfactory.cropper.model.RectCropShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCropScreen(
    imageUri: Uri,
    onCropDone: (Uri) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler {
        onBack()
    }

    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var imageLoadError by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenW = with(density) { configuration.screenWidthDp.dp.toPx().toInt() }
    val screenH = with(density) { configuration.screenHeightDp.dp.toPx().toInt() }

    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            val bmp = loadBitmapSafe(context, imageUri, screenW * 2, screenH * 2)
            if (bmp != null) {
                imageBitmap = bmp.asImageBitmap()
            } else {
                imageLoadError = true
            }
        }
    }

    var crop by remember { mutableStateOf(false) }
    var isCropping by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "裁剪图片",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) { Text("取消") }
                    Button(
                        onClick = { crop = true },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Filled.Crop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("确认裁剪")
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (imageLoadError) {
                Text(
                    "图片加载失败",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (imageBitmap == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                ImageCropper(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    imageBitmap = imageBitmap!!,
                    contentDescription = "Image Cropper",
                    cropStyle = CropDefaults.style(),
                    cropProperties = CropDefaults.properties(
                        cropOutlineProperty = CropOutlineProperty(
                            OutlineType.Rect,
                            RectCropShape(0, "Rect")
                        ),
                        handleSize = with(density) { 20.dp.toPx() }
                    ),
                    crop = crop,
                    onCropStart = { isCropping = true },
                    onCropSuccess = { resultBitmap ->
                        scope.launch(Dispatchers.IO) {
                            val bmp = resultBitmap.asAndroidBitmap()
                            // Write to a new temporary file in cacheDir instead of overwriting original
                            val file = File(context.cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(file).use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            }
                            val newUri = Uri.fromFile(file)
                            withContext(Dispatchers.Main) {
                                isCropping = false
                                crop = false
                                onCropDone(newUri)
                            }
                        }
                    }
                )

                if (isCropping) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun loadBitmapSafe(context: Context, uri: Uri, maxW: Int, maxH: Int): Bitmap? {
    val contentResolver = context.contentResolver
    
    // 1. Get EXIF rotation
    var rotation = 0
    try {
        val input = contentResolver.openInputStream(uri) ?: File(uri.path!!).inputStream()
        val exif = ExifInterface(input)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        input.close()
    } catch (e: Exception) { e.printStackTrace() }

    // 2. Decode bounds
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        val input = contentResolver.openInputStream(uri) ?: File(uri.path!!).inputStream()
        BitmapFactory.decodeStream(input, null, options)
        input.close()
    } catch (e: Exception) { return null }

    // 3. Calculate sample size
    var width = options.outWidth
    var height = options.outHeight
    var inSampleSize = 1
    if (rotation == 90 || rotation == 270) {
        width = options.outHeight
        height = options.outWidth
    }
    if (height > maxH || width > maxW) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= maxH && halfWidth / inSampleSize >= maxW) {
            inSampleSize *= 2
        }
    }

    // 4. Decode actual bitmap
    options.inJustDecodeBounds = false
    options.inSampleSize = inSampleSize
    var bitmap: Bitmap? = null
    try {
        val input = contentResolver.openInputStream(uri) ?: File(uri.path!!).inputStream()
        bitmap = BitmapFactory.decodeStream(input, null, options)
        input.close()
    } catch (e: Exception) { return null }

    // 5. Apply rotation
    if (rotation != 0 && bitmap != null) {
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
            bitmap = rotated
        }
    }
    return bitmap
}
