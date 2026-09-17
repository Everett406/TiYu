package com.drone.quiz.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.drone.quiz.ui.glass.AppIcons
import java.io.File

/**
 * 拍照搜题自建相机页（v2.13.0）：CameraX 取景 + 取景框引导 + 快门/闪光/相册。
 * 交互对齐搜题类产品：把整页题放进框内拍，识别率比甩开系统相机随手拍显著更高；
 * 相机权限运行时申请，拒绝后仍有「从相册选图」出路（PhotoPicker 零权限）。
 * 拍照产物落 FileProvider 的 cache/photo_search/（沿用 v2.12.0 目录与授权）。
 */
@Composable
fun PhotoCaptureScreen(
    onBack: () -> Unit,
    onCaptured: (android.net.Uri) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { u -> if (u != null) onCaptured(u) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .build()
    }
    var flashOn by remember { mutableStateOf(false) }
    LaunchedEffect(flashOn) {
        imageCapture.flashMode =
            if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }
    var capturing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // 绑定后置相机（权限就绪后一次；离开页面解绑释放）
    DisposableEffect(hasPermission) {
        var provider: ProcessCameraProvider? = null
        if (hasPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    p.unbindAll()
                    p.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                }.onFailure { cameraError = "相机启动失败，可用相册选图代替" }
            }, executor)
        }
        onDispose { runCatching { provider?.unbindAll() } }
    }

    fun capture() {
        if (capturing || !hasPermission) return
        capturing = true
        val dir = File(context.cacheDir, "photo_search").apply { mkdirs() }
        val f = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(f).build()
        imageCapture.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                capturing = false
                onCaptured(
                    FileProvider.getUriForFile(context, context.packageName + ".files", f)
                )
            }

            override fun onError(e: ImageCaptureException) {
                capturing = false
                cameraError = "拍照失败，请重试或用相册选图"
            }
        })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            // 取景框引导：框外压暗 + 白色四角 L 线（横放一页题的比例）
            ViewfinderOverlay(Modifier.fillMaxSize())

            // ---- 顶部：返回 + 标题 + 闪光 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraCircleButton(onClick = onBack) {
                    Icon(AppIcons.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("拍照搜题", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "框内对一道题，拍整页也行",
                        color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp
                    )
                }
                CameraCircleButton(onClick = { flashOn = !flashOn }) {
                    Text(
                        if (flashOn) "关" else "闪",
                        color = if (flashOn) Color(0xFFFFC94D) else Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            // ---- 底部控制条：相册 · 快门 · 空位对称 ----
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (cameraError != null) cameraError!!
                    else "对准一道题放进框内 · 横握手机也可以，会自动摆正",
                    color = if (cameraError != null) Color(0xFFFF8A8A)
                    else Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 相册
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CameraCircleButton(
                            onClick = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            diameter = 46.dp
                        ) {
                            Icon(AppIcons.Import, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            "相册", color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 快门（外环 + 内圆；拍照中降透明度）
                    Box(
                        Modifier
                            .size(78.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f))
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (capturing) 0.55f else 1f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { capture() },
                        contentAlignment = Alignment.Center
                    ) {}
                    Spacer(Modifier.weight(1f))
                    // 对称占位
                    Spacer(Modifier.width(46.dp))
                }
            }
        } else {
            // ---- 权限被拒：引导授权，相册始终可用 ----
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(AppIcons.Camera, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(44.dp))
                Text(
                    "需要相机权限才能拍照搜题",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Text(
                    "也可以不授权，直接从相册选一张题目照片",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予权限", fontSize = 13.sp)
                    }
                    androidx.compose.material3.Button(onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }) {
                        Text("从相册选图", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 相机页通用圆形按钮（黑色半透底白图标，系统相机风格；glass 组件在取景画面上不可读）。 */
@Composable
private fun CameraCircleButton(
    onClick: () -> Unit,
    diameter: Dp = 44.dp,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * 取景框 overlay：中央横向长条框（宽 86%，高约 0.45 倍框宽——一道题的典型比例，
 * v2.14.0 单题导向），框外四块半透明压暗 + 白色圆头四角 L 线。
 */
@Composable
private fun ViewfinderOverlay(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val fw = w * 0.86f
        val fh = fw * 0.45f
        val l = (w - fw) / 2f
        val t = (h - fh) / 2.2f
        val r = l + fw
        val b = t + fh

        val dim = Color.Black.copy(alpha = 0.35f)
        drawRect(dim, Offset(0f, 0f), Size(w, t))                       // 上
        drawRect(dim, Offset(0f, b), Size(w, h - b))                    // 下
        drawRect(dim, Offset(0f, t), Size(l, fh))                       // 左
        drawRect(dim, Offset(r, t), Size(w - r, fh))                    // 右

        val line = Color.White
        val corner = 42f
        val stroke = 7f
        val cap = StrokeCap.Round
        // 左上
        drawLine(line, Offset(l, t + corner), Offset(l, t), stroke, cap)
        drawLine(line, Offset(l, t), Offset(l + corner, t), stroke, cap)
        // 右上
        drawLine(line, Offset(r - corner, t), Offset(r, t), stroke, cap)
        drawLine(line, Offset(r, t), Offset(r, t + corner), stroke, cap)
        // 右下
        drawLine(line, Offset(r, b - corner), Offset(r, b), stroke, cap)
        drawLine(line, Offset(r, b), Offset(r - corner, b), stroke, cap)
        // 左下
        drawLine(line, Offset(l, b - corner), Offset(l, b), stroke, cap)
        drawLine(line, Offset(l, b), Offset(l + corner, b), stroke, cap)
    }
}
