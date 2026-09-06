package com.tumuyan.ncnn.realsr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.ui.input.nestedscroll.nestedScroll
import java.io.File

@Composable
internal fun MainActivity.MainScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val view = LocalView.current
    // 全屏时隐藏系统栏(类似视频全屏), 退出时恢复
    DisposableEffect(previewFullscreen) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        if (previewFullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 外层不再叠加系统栏 padding,由各页面内的 SmallTopAppBar 自行处理状态栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = MiuixIcons.Home,
                    label = getString(R.string.nav_home),
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = MiuixIcons.Folder,
                    label = getString(R.string.dir_menu_entry),
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = MiuixIcons.Settings,
                    label = getString(R.string.setting),
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = MiuixIcons.Info,
                    label = getString(R.string.nav_about),
                )
            }
        },
    ) { padding ->
        AnimatedContent<Int>(
            targetState = selectedTab,
            transitionSpec = {
                // 参考 miuix demo:按导航方向滑入滑出 + 淡入淡出
                val forward = targetState > initialState
                ContentTransform(
                    targetContentEnter = slideInHorizontally(
                        animationSpec = tween<androidx.compose.ui.unit.IntOffset>(300),
                    ) { if (forward) it else -it } + fadeIn(animationSpec = tween(300)),
                    initialContentExit = slideOutHorizontally(
                        animationSpec = tween<androidx.compose.ui.unit.IntOffset>(300),
                    ) { if (forward) -it else it } + fadeOut(animationSpec = tween(300)),
                    targetContentZIndex = 0f,
                    // 关闭尺寸动画,避免以无限约束测量滚动内容导致崩溃
                    sizeTransform = null,
                )
            },
            label = "main_tab",
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(padding),
        ) { tab ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> HomeContent()
                    1 -> DirProcessContent()
                    2 -> SettingsContent()
                    else -> AboutContent()
                }
            }
        }
        }
        // 全屏预览覆盖层(类似视频全屏:覆盖整个窗口)
        if (previewFullscreen) {
            FullscreenPreviewOverlay()
        }
    }
}

/**
 * 解码对比用的处理前位图: 按处理后图片尺寸等比缩放, 并降采样限制最长边不超过
 * MAX_COMPARE_BITMAP_SIZE(硬件纹理上限), 避免 "trying to draw too large bitmap" 崩溃。
 * @return (位图, 降采样比例 = 位图宽 / 原处理后图片宽)
 */
private fun MainActivity.decodeScaledCompareBitmap(inputPath: String, outputPath: String): Pair<Bitmap, Float>? {
    // 读取处理后图片尺寸(仅边界, 不分配内存)
    val outOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(outputPath, outOpts)
    val outW = outOpts.outWidth
    val outH = outOpts.outHeight
    if (outW <= 0 || outH <= 0) return null
    // 降采样比例: 最长边超过纹理上限时等比缩小
    val maxDim = MAX_COMPARE_BITMAP_SIZE
    val ratio = if (maxOf(outW, outH) > maxDim) maxDim.toFloat() / maxOf(outW, outH) else 1f
    val targetW = (outW * ratio).toInt().coerceAtLeast(1)
    val targetH = (outH * ratio).toInt().coerceAtLeast(1)
    // 读取处理前图片尺寸
    val inOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(inputPath, inOpts)
    if (inOpts.outWidth <= 0 || inOpts.outHeight <= 0) return null
    // inSampleSize 粗解码避免 OOM, 再精确缩放到目标尺寸
    var sampleSize = 1
    while (inOpts.outWidth / (sampleSize * 2) >= targetW * 2 &&
        inOpts.outHeight / (sampleSize * 2) >= targetH * 2
    ) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val inBmp = BitmapFactory.decodeFile(inputPath, decodeOpts) ?: return null
    val scaled = Bitmap.createScaledBitmap(inBmp, targetW, targetH, true)
    if (scaled !== inBmp) inBmp.recycle()
    return Pair(scaled, ratio)
}

/** 全屏预览覆盖层:黑底全窗口显示处理结果, 支持左右拖动虚线对比处理前后图片 */
@Composable
private fun MainActivity.FullscreenPreviewOverlay() {
    var dividerX by remember { mutableFloatStateOf(-1f) }
    var previewWidth by remember { mutableIntStateOf(0) }
    var previewHeight by remember { mutableIntStateOf(0) }
    // 记录底层 view 的缩放/平移, 用于 Canvas 同步绘制处理前图片(同步拖动)
    var bottomScale by remember { mutableFloatStateOf(1f) }
    var bottomCenter by remember { mutableStateOf(PointF(0f, 0f)) }
    // 底层 view 引用: Canvas 绘制时实时读取 scale/center, 避免依赖回调时序导致错位
    var bottomView by remember { mutableStateOf<SubsamplingScaleImageView?>(null) }
    // 静态降采样对比位图(≤2048, 防超大位图崩溃) + 降采样比例
    val compareData = remember(dir, outputFile, imagePath) {
        val inFile = File(dir + "/input.png")
        val outFile = File(dir + "/output.png")
        if (inFile.exists() && outFile.exists()) {
            decodeScaledCompareBitmap(inFile.absolutePath, outFile.absolutePath)
        } else null
    }
    val compareBitmap = compareData?.first
    val compareRatio = compareData?.second ?: 1f
    val imgW = compareBitmap?.width ?: 0
    val imgH = compareBitmap?.height ?: 0
    // 处理后图片原始尺寸(用于 fit 缩放换算)
    val outW = if (compareRatio > 0f) (imgW / compareRatio).toInt() else 0
    val outH = if (compareRatio > 0f) (imgH / compareRatio).toInt() else 0
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                previewWidth = it.width
                previewHeight = it.height
                if (dividerX < 0f) dividerX = it.width / 2f
            },
    ) {
        // 底层:处理后图片, SubsamplingScaleImageView 分块渲染(支持超大图), 可交互缩放平移;
        // 通过 OnStateChangedListener 暴露 scale+center, 供对比层同步拖动
        AndroidView(
            factory = { ctx ->
                SubsamplingScaleImageView(ctx).apply {
                    setMinimumDpi(40)
                }
            },
            update = { view ->
                bottomView = view
                // 图片加载完成后主动同步一次初始 scale/center,
                // 避免进入全屏时对比层因初始状态未同步而显示空白
                view.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
                    override fun onReady() {
                        bottomScale = view.getScale()
                        view.getCenter()?.let { bottomCenter = it }
                    }

                    override fun onImageLoaded() {
                        bottomScale = view.getScale()
                        view.getCenter()?.let { bottomCenter = it }
                    }

                    override fun onPreviewLoadError(e: Exception?) {}
                    override fun onImageLoadError(e: Exception?) {}
                    override fun onTileLoadError(e: Exception?) {}
                    override fun onPreviewReleased() {}
                })
                val path = imagePath
                if (path != null && File(path).exists()) {
                    view.setImage(ImageSource.uri(path))
                }
                // 底层缩放/平移变化时同步到状态, 供 Canvas 绘制处理前图片
                view.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        bottomScale = newScale
                        view.getCenter()?.let { bottomCenter = it }
                    }

                    override fun onCenterChanged(newCenter: PointF, origin: Int) {
                        bottomScale = view.getScale()
                        bottomCenter = newCenter
                    }
                })
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (compareBitmap != null && outW > 0 && outH > 0) {
            // 对比层:处理前图片用 Compose Canvas 绘制(无触摸拦截, 触摸穿透到底层),
            // 按底层 view 的 scale/center 同步缩放平移, 保证两图始终对齐(同步拖动);
            // 绘制时按可见区域裁剪(降采样位图 ≤2048, 不会触发超大位图崩溃)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(right = dividerX.coerceAtLeast(0f)) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                // getScale() 是绝对缩放(源图像素→屏幕像素比率, fit 时即为 fitScale);
                // 优先实时读取底层 view 的 scale/center; view 未就绪时用 fit 理论值兜底
                // (scale = fitScale, center = 图片中心), 避免初始阶段对比层绘制错位
                val liveScale = bottomView?.getScale()?.takeIf { it > 0f }
                    ?: minOf(size.width / outW, size.height / outH)
                val liveCenter = bottomView?.getCenter()
                    ?: PointF(outW / 2f, outH / 2f)
                // 对比位图已降采样(尺寸 = output * compareRatio), 绘制缩放需除以 compareRatio,
                // 使显示尺寸与处理后图片完全一致(等比对齐)
                val scale = liveScale / compareRatio
                val canvasCx = liveCenter.x * compareRatio
                val canvasCy = liveCenter.y * compareRatio
                val left = size.width / 2f - canvasCx * scale
                val top = size.height / 2f - canvasCy * scale
                // 可见区域对应的源图片范围, 只绘制可见部分
                val srcLeft = ((0f - left) / scale).coerceIn(0f, imgW.toFloat())
                val srcTop = ((0f - top) / scale).coerceIn(0f, imgH.toFloat())
                val srcRight = ((size.width - left) / scale).coerceIn(0f, imgW.toFloat())
                val srcBottom = ((size.height - top) / scale).coerceIn(0f, imgH.toFloat())
                if (srcRight > srcLeft && srcBottom > srcTop) {
                    drawImage(
                        image = compareBitmap.asImageBitmap(),
                        srcOffset = IntOffset(srcLeft.toInt(), srcTop.toInt()),
                        srcSize = IntSize(
                            (srcRight - srcLeft).toInt(),
                            (srcBottom - srcTop).toInt(),
                        ),
                        dstOffset = IntOffset(
                            (left + srcLeft * scale).toInt(),
                            (top + srcTop * scale).toInt(),
                        ),
                        dstSize = IntSize(
                            ((srcRight - srcLeft) * scale).toInt().coerceAtLeast(1),
                            ((srcBottom - srcTop) * scale).toInt().coerceAtLeast(1),
                        ),
                    )
                }
            }
            // 虚线 + 拖动手柄(窄条, 只拦截水平拖动, 不干扰底层缩放)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(40.dp)
                    .offset { IntOffset(dividerX.roundToInt() - 20.dp.toPx().roundToInt(), 0) }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            dividerX = (dividerX + dragAmount).coerceIn(
                                0f, previewWidth.toFloat()
                            )
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.9f)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp)
                        .background(Color.White, CircleShape),
                )
            }
        }
        // 退出全屏按钮(右上角)
        IconButton(
            onClick = { previewFullscreen = false },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            backgroundColor = Color.Black.copy(alpha = 0.5f),
        ) {
            Icon(MiuixIcons.Close, "退出全屏", tint = Color.White)
        }
    }
}

@Composable
private fun MainActivity.AboutContent() {
    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        val topAppBarScrollBehavior = MiuixScrollBehavior()
        TopAppBar(
            title = getString(R.string.app_name),
            subtitle = "v" + BuildConfig.VERSION_NAME,
            scrollBehavior = topAppBarScrollBehavior,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                val linkColor = MiuixTheme.colorScheme.primary
                val uriHandler = LocalUriHandler.current
                val annotated = buildAnnotatedString {
                    val text = getString(R.string.default_log)
                    val urlRegex = Regex("""https?://[^\s<>"']+""")
                    var last = 0
                    for (m in urlRegex.findAll(text)) {
                        append(text.substring(last, m.range.first))
                        val url = m.value
                        pushStringAnnotation("URL", url)
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) { append(url) }
                        pop()
                        last = m.range.last + 1
                    }
                    append(text.substring(last))
                }
                ClickableText(
                    text = annotated,
                    onClick = { offset ->
                        annotated.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()
                            ?.let { uriHandler.openUri(it.item) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    style = MiuixTheme.textStyles.button.copy(
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    ),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            // 添加新模型方法说明
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        text = getString(R.string.about_add_model_title),
                        style = MiuixTheme.textStyles.title3,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = getString(R.string.about_add_model_body),
                        style = MiuixTheme.textStyles.body2.copy(
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        ),
                    )
                }
            }
        }
    }
}

/** 对比位图最长边上限(硬件纹理安全值), 超过则降采样, 避免 "trying to draw too large bitmap" 崩溃 */
private const val MAX_COMPARE_BITMAP_SIZE = 2048
