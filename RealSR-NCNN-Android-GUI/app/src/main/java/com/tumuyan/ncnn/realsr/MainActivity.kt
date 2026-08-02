package com.tumuyan.ncnn.realsr

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.PointF
import android.graphics.Rect
import android.icu.text.SimpleDateFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.tumuyan.ncnn.realsr.ui.AppTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.menu.OverlayDropdownMenu
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.ArrayList
import java.util.Date
import java.util.HashSet
import java.util.List
import java.util.Locale
import java.util.Set
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 主界面: 完全重新设计, 采用 Miuix / HyperOS 设计语言。
 * 保留原有全部业务逻辑(选图、放大、导出、目录批量、基准测试等),
 * UI 层全部替换为 Compose + Miuix 组件。
 */
class MainActivity : ComponentActivity() {
    private var selectCommand by mutableStateOf(0)
    private var threadCount = ""
    private var log by mutableStateOf("")
    private var progressText by mutableStateOf("")
    private var busy by mutableStateOf(false)
    /** 图片处理成功后才可分享(之前置灰) */
    private var shareEnabled by mutableStateOf(false)
    /** 全屏预览(类似视频全屏:隐藏系统栏, 覆盖全窗口) */
    private var previewFullscreen by mutableStateOf(false)
    private var imagePath by mutableStateOf<String?>(null)
    private var showImagePreview by mutableStateOf(false)
    private var initProcess = false

    private val galleryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        .toString() + File.separator + "RealSR"
    private var outputFile: File? = null
    private var outputGif: File? = null
    private var inputFile: File? = null
    private var titleFile: File? = null

    /** dir 来自应用缓存目录, 参数来源可信, 不存在注入风险 */
    private var dir = ""
    private var cache_dir = ""
    private var modelName = "SR"
    private var command: Array<String>? = null
    private var commandListManager: CommandListManager? = null
    private var progressLogHelper: ProgressLogHelper? = null
    private var inputFileName = ""
    private var outputSavePath = ""
    private var inputIsGifAnimation = false
    private var inputGifDelay = 0

    private var format = 0
    private var name = 0
    private var name2 = 0
    private var name3 = 0
    private var notify = 0
    private var dirOutputFormat = 0
    private var tileSize = 0
    private var useCPU = false
    private var mnnBackend = 7
    private var keepScreen = false
    private var useMultFiles = false
    private var prePng = true
    private var preFrame = true
    private var autoSave = false
    private var showSearchView = false
    private var showFinalCommand = false
    private var savePath = galleryPath
    private var formats: Array<String> = emptyArray()
    private var displayLabels: Array<String> = emptyArray()

    private var processingService: ProcessingService? = null
    private var isBound = false

    private val snackbarHostState = SnackbarHostState()

    /** Miuix Snackbar 提示(替代 Toast) */
    private fun showSnackbar(text: String) {
        lifecycleScope.launch { snackbarHostState.showSnackbar(text) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            processingService = (service as ProcessingService.LocalBinder).service
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen()
            }
        }

        val serviceIntent = Intent(this, ProcessingService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        val sp = getSharedPreferences("config", Activity.MODE_PRIVATE)
        prePng = sp.getBoolean("PrePng", true)
        preFrame = sp.getBoolean("PreFrame", true)

        val version = sp.getInt("version", 0)
        cache_dir = cacheDir.absolutePath
        AssetsCopyer.releaseAssets(this, "realsr", cache_dir, version == BuildConfig.VERSION_CODE)
        sp.edit().putInt("version", BuildConfig.VERSION_CODE).apply()

        val orientation = sp.getInt("ORIENTATION", 0)
        if (orientation == 1) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else if (orientation == 2) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else if (orientation == 3) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        dir = cache_dir + "/realsr"
        outputFile = File(dir, "output.png")
        outputGif = File(dir, "output.gif")
        inputFile = File(dir, "input.png")
        titleFile = File(dir, "img/realsr.png")
        // 应用启动时清理上次残留的临时图片, 避免旧结果残留
        deleteFile(outputFile)
        deleteFile(outputGif)
        deleteFile(inputFile)
        showImage(titleFile, "")

        selectCommand = sp.getInt("selectCommand", 2)
        requirePermission()
        readFileFromShare()
    }

    override fun onResume() {
        super.onResume()
        formats = resources.getStringArray(R.array.format)
        val sp = getSharedPreferences("config", Activity.MODE_PRIVATE)
        tileSize = sp.getInt("tileSize", 0)
        threadCount = sp.getString("threadCount", "") ?: ""
        keepScreen = sp.getBoolean("keepScreen", false)
        useMultFiles = sp.getBoolean("useMultFiles", false)
        prePng = sp.getBoolean("PrePng", true)
        preFrame = sp.getBoolean("PreFrame", true)
        useCPU = sp.getBoolean("useCPU", false)
        mnnBackend = sp.getInt("mnnBackend", 7)
        autoSave = sp.getBoolean("autoSave", false)
        showSearchView = sp.getBoolean("showSearchView", false)
        showFinalCommand = sp.getBoolean("showFinalCommand", false)
        notify = sp.getInt("notify", 0)
        format = sp.getInt("format", 0)
        dirOutputFormat = sp.getInt("dirOutputFormat", 0)
        name = sp.getInt("name", 0)
        name2 = sp.getInt("name2", 0)
        name3 = sp.getInt("name3", 0)

        val presetLabels = resources.getStringArray(R.array.style_array)
        val useCustomLabel = sp.getBoolean("useCustomLabel", false)
        commandListManager = CommandListManager(
            presetLabels,
            (sp.getString("extraPath", "") ?: "").trim(),
            (sp.getString("extraCommand", "") ?: "").trim(),
            (sp.getString("classicalFilters", getString(R.string.default_classical_filters)) ?: "")
                .split(Regex("\\s+")).toTypedArray(),
            (sp.getString("magickFilters", getString(R.string.default_magick_filters)) ?: "")
                .split(Regex("\\s+")).toTypedArray(),
        )
        commandListManager?.loadCustomLabels(sp.getString("customLabels", ""))

        val hiddenPrograms = sp.getStringSet("hiddenPrograms", HashSet())
        command = commandListManager?.getFilteredCommands(hiddenPrograms)
        displayLabels = commandListManager?.getFilteredLabels(hiddenPrograms, useCustomLabel) ?: emptyArray()
        if (selectCommand >= (command?.size ?: 0))
            selectCommand = Math.max(0, (command?.size ?: 1) - 1)

        savePath = sp.getString("savePath", "") ?: ""
        if (savePath.isEmpty()) savePath = galleryPath
        try {
            val file = File(savePath)
            if (file.isFile) file.delete()
            if (!file.exists()) file.mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        // 应用退出时清理临时文件,避免残留
        deleteFile(outputFile)
        deleteFile(outputGif)
        deleteFile(inputFile)
    }

    @Composable
    fun MainScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
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
    private fun decodeScaledCompareBitmap(inputPath: String, outputPath: String): Pair<Bitmap, Float>? {
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
    fun FullscreenPreviewOverlay() {
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
                Icon(MiuixIcons.ExpandLess, "退出全屏", tint = Color.White)
            }
        }
    }

    /** 左对齐紧凑标题栏(替换居中放置的 SmallTopAppBar):标题靠左、actions 靠右同行 */
    @Composable
    fun LeftAlignedTopBar(
        title: String,
        subtitle: String = "",
        actions: @Composable RowScope.() -> Unit = {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .padding(horizontal = 16.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }

    /** 执行 More 菜单中的命令(兼容 runFakeCommand 伪命令) */
    private fun runMenuCommand(q: String) {
        if (!runFakeCommand(q)) {
            stopCommand()
            run20(q, false, true)
        }
    }

    /** 基准测试 */
    private fun runBenchmark() {
        var appendParam = ""
        if (tileSize > 0) appendParam = " -t " + tileSize
        if (useCPU) appendParam += " -g -1"
        appendParam += ";"
        val q = "rm -rf *.png; ls *.png; " + BENCH_MARK_COMMANDS[0] + appendParam + BENCH_MARK_COMMANDS[1] + appendParam
        stopCommand()
        run20(q, true, false)
    }

    @Composable
    fun HomeContent() {
        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            LeftAlignedTopBar(
                title = getString(R.string.app_name),
                actions = {
                    // 进度展示(位于停止/分享按钮左边)
                    if (busy || progressText.isNotEmpty()) {
                        Text(
                            text = progressText.ifEmpty { getString(R.string.busy) },
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .widthIn(max = 120.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (busy) stopCommand() else shareImage(if (inputIsGifAnimation) "output.gif" else "output.png")
                        },
                        // 处理中显示"停止"(始终可点);空闲时仅处理成功后允许分享
                        enabled = busy || shareEnabled,
                    ) {
                        if (busy) Icon(MiuixIcons.Close, "停止") else Icon(MiuixIcons.Share, "分享")
                    }
                    // More 菜单:恢复旧版缺失的工具命令
                    OverlayIconDropdownMenu(
                        entry = DropdownEntry(
                            items = listOf(
                                DropdownItem(text = getString(R.string.menu_avir2), onClick = {
                                    runMenuCommand("./resize-ncnn -i input.png -o output.png  -m avir -s 0.5")
                                }),
                                DropdownItem(text = getString(R.string.menu_nearest4), onClick = {
                                    runMenuCommand("./resize-ncnn -i input.png -o output.png  -m nearest -s 4")
                                }),
                                DropdownItem(text = getString(R.string.menu_de_nearest), onClick = {
                                    runMenuCommand("./resize-ncnn -i input.png -o output.png  -m de-nearest")
                                }),
                                DropdownItem(text = getString(R.string.menu_de_nearest2), onClick = {
                                    runMenuCommand("./resize-ncnn -i input.png -o output.png  -m de-nearest2")
                                }),
                                DropdownItem(text = getString(R.string.menu_perfectpixel), onClick = {
                                    runMenuCommand("./resize-ncnn -i input.png -o output.png  -m perfectpixel -s 0")
                                }),
                                DropdownItem(text = getString(R.string.menu_perfectpixel1), onClick = {
                                    runMenuCommand("./resize-ncnn -i input.png -o output.png  -m perfectpixel -s 1")
                                }),
                                DropdownItem(text = getString(R.string.menu_perfectpixel2), onClick = {
                                    runMenuCommand("./resize-ncnn -i input.png -o output.png  -m perfectpixel -s 5")
                                }),
                                DropdownItem(text = getString(R.string.menu_magick2), onClick = {
                                    runMenuCommand("./magick input.png -resize 50% output.png")
                                }),
                                DropdownItem(text = getString(R.string.menu_magick3), onClick = {
                                    runMenuCommand("./magick input.png -resize 33.33% output.png")
                                }),
                                DropdownItem(text = getString(R.string.menu_magick4), onClick = {
                                    runMenuCommand("./magick input.png -resize 25% output.png")
                                }),
                                DropdownItem(text = getString(R.string.menu_out2in), onClick = {
                                    if (inputIsGifAnimation) {
                                        showSnackbar(getString(R.string.not_support_animation))
                                    } else {
                                        runCommand("cp output.png input.png")
                                        showImage(inputFile, getString(R.string.lr))
                                    }
                                }),
                                DropdownItem(text = getString(R.string.menu_bench_mark), onClick = { runBenchmark() }),
                                DropdownItem(text = getString(R.string.menu_reset_cache), onClick = {
                                    runMenuCommand(CMD_RESET_CACHE)
                                }),
                                DropdownItem(text = getString(R.string.menu_in), onClick = { runMenuCommand("in") }),
                                DropdownItem(text = getString(R.string.menu_out), onClick = { runMenuCommand("out") }),
                            ),
                        ),
                    ) {
                        Icon(MiuixIcons.More, "更多")
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 命令选择卡片:按 模型/放大倍率/其他参数 三维独立选择, 组合映射回命令索引
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    val cmds = command
                    // 三维结构用"未备注"的原始标签解析(自定义备注只影响模型名称显示,
                    // 不污染 x<数字> 锚点划分倍率/参数)
                    val baseLabels = commandListManager?.getDisplayLabels(false) ?: displayLabels
                    val dims = remember(baseLabels, cmds) {
                        baseLabels.mapIndexed { i, l -> parseLabelDims(l, cmds?.getOrNull(i) ?: "") }
                    }
                    val models = remember(dims) { dims.map { it.first }.distinct() }
                    // 模型显示名: 该模型存在自定义备注时显示备注, 否则显示硬编码模型名
                    val modelDisplayNames = remember(models, dims, displayLabels, baseLabels) {
                        models.map { m ->
                            val idx = dims.indexOfFirst { it.first == m }
                            if (idx >= 0 && displayLabels.getOrNull(idx) != baseLabels.getOrNull(idx)) {
                                displayLabels[idx] ?: m
                            } else m
                        }
                    }
                    var selModelIdx by remember { mutableStateOf(0) }
                    var selScaleIdx by remember { mutableStateOf(0) }
                    var selParamsIdx by remember { mutableStateOf(0) }
                    // 每个模型对应的 -m 模型路径集合(用于扫描模型文件判断可用倍率)
                    val assets = LocalContext.current.assets
                    val modelPathsByModel = remember(dims, cmds) {
                        val map = mutableMapOf<String, MutableSet<String>>()
                        dims.forEachIndexed { i, d ->
                            Regex("-m\\s+(\\S+)").find(cmds?.getOrNull(i) ?: "")?.groupValues?.get(1)
                                ?.let { path -> map.getOrPut(d.first) { mutableSetOf() }.add(path) }
                        }
                        map
                    }
                    // 倍率: 优先按模型文件名扫描(up2x/up3x/up4x 或 x2/x3/x4), 扫描为空回退标签锚点;
                    // 注意 Magick 等命令无 -m 路径, 此时 modelPaths 为空, 扫描失败直接回退标签锚点
                    val scalesByModel = remember(dims, modelPathsByModel, assets) {
                        val result = mutableMapOf<String, kotlin.collections.List<String>>()
                        for (model in dims.map { it.first }.distinct()) {
                            val scanned = scanModelScales(assets, modelPathsByModel[model] ?: emptySet())
                            result[model] = if (scanned.isNotEmpty()) scanned
                                else dims.filter { it.first == model }.map { it.second }
                                    .filter { it.isNotEmpty() }.distinct()
                        }
                        result
                    }
                    // 外部 selectCommand 变化(设置页/恢复)时同步三维选择
                    LaunchedEffect(selectCommand, dims) {
                        val d = dims.getOrNull(selectCommand) ?: return@LaunchedEffect
                        selModelIdx = models.indexOf(d.first).coerceAtLeast(0)
                        val scales = scalesByModel[d.first] ?: emptyList()
                        selScaleIdx = scales.indexOf(d.second).coerceAtLeast(0)
                        val ps = dims.filter { it.first == d.first && it.second == d.second }.map { it.third }.distinct()
                        selParamsIdx = ps.indexOf(d.third).coerceAtLeast(0)
                    }
                    val selModel = models.getOrElse(selModelIdx) { "" }
                    val scales = remember(dims, selModel, scalesByModel) {
                        scalesByModel[selModel] ?: emptyList()
                    }
                    val selScale = scales.getOrElse(selScaleIdx) { "" }
                    val paramsList = remember(dims, selModel, selScale) {
                        dims.filter { it.first == selModel && it.second == selScale }.map { it.third }.distinct()
                    }
                    // 当前模型+倍率下是否带第三参数(降噪/Anime4k 模型变体等)
                    val hasParams = paramsList.isNotEmpty() && paramsList.any { it.isNotEmpty() }
                    // 动态判断第三参数名称: 含 noise/denoise → 降噪; 含 ACNet/ARNet/ArtCNN/FSRCNNX(Anime4k 模型变体) → 算法; 其他 → 参数
                    val paramsTitle = when {
                        paramsList.any { it.contains("noise") || it.contains("denoise") } -> getString(R.string.select_noise)
                        paramsList.any { it.contains("ACNet") || it.contains("ARNet") || it.contains("ArtCNN") || it.contains("FSRCNNX") } -> getString(R.string.select_processor)
                        else -> getString(R.string.select_params)
                    }
                    val selParams = paramsList.getOrNull(selParamsIdx) ?: ""
                    // 根据点击的三维值直接计算目标命令索引(不依赖重组前旧状态, 避免切换两次才生效)
                    fun indexOfDims(model: String, scale: String, params: String): Int =
                        dims.indexOfFirst { it.first == model && it.second == scale && it.third == params }
                    // 模型
                    OverlayDropdownMenu(
                        entry = DropdownEntry(
                            items = models.mapIndexed { i, m ->
                                DropdownItem(
                                    text = modelDisplayNames.getOrElse(i) { m },
                                    selected = i == selModelIdx,
                                    onClick = {
                                        val newScales = scalesByModel[m] ?: emptyList()
                                        val newScale = newScales.firstOrNull() ?: ""
                                        val newParamsList = dims.filter { it.first == m && it.second == newScale }
                                            .map { it.third }.distinct()
                                        val newParams = newParamsList.firstOrNull() ?: ""
                                        val idx = indexOfDims(m, newScale, newParams)
                                        selModelIdx = i
                                        selScaleIdx = 0
                                        selParamsIdx = 0
                                        if (idx >= 0 && idx != selectCommand) selectCommand = idx
                                    },
                                )
                            },
                        ),
                        title = getString(R.string.current_model),
                        summary = modelDisplayNames.getOrElse(selModelIdx) { selModel },
                        startAction = {
                            Icon(
                                modifier = Modifier.padding(end = 16.dp),
                                imageVector = MiuixIcons.Image,
                                contentDescription = getString(R.string.current_model),
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        },
                    )
                    // 放大倍率
                    OverlayDropdownMenu(
                        entry = DropdownEntry(
                            items = scales.mapIndexed { i, s ->
                                DropdownItem(
                                    text = s.ifEmpty { "-" },
                                    selected = i == selScaleIdx,
                                    onClick = {
                                        val newParamsList = dims.filter { it.first == selModel && it.second == s }
                                            .map { it.third }.distinct()
                                        val newParams = newParamsList.firstOrNull() ?: ""
                                        val idx = indexOfDims(selModel, s, newParams)
                                        selScaleIdx = i
                                        selParamsIdx = 0
                                        if (idx >= 0 && idx != selectCommand) selectCommand = idx
                                    },
                                )
                            },
                        ),
                        title = getString(R.string.select_scale),
                        summary = selScale.ifEmpty { "-" },
                        startAction = {
                            Icon(
                                modifier = Modifier.padding(end = 16.dp),
                                imageVector = MiuixIcons.ExpandMore,
                                contentDescription = getString(R.string.select_scale),
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        },
                    )
                    // 其他参数(仅当该模型+倍率存在第三参数时显示, 如降噪/处理器)
                    if (hasParams) {
                        OverlayDropdownMenu(
                            entry = DropdownEntry(
                                items = paramsList.mapIndexed { i, p ->
                                    DropdownItem(
                                        text = p.ifEmpty { "-" },
                                        selected = i == selParamsIdx,
                                        onClick = {
                                            val idx = indexOfDims(selModel, selScale, p)
                                            selParamsIdx = i
                                            if (idx >= 0 && idx != selectCommand) selectCommand = idx
                                        },
                                    )
                                },
                            ),
                            title = paramsTitle,
                            summary = selParams.ifEmpty { "-" },
                            startAction = {
                                Icon(
                                    modifier = Modifier.padding(end = 16.dp),
                                    imageVector = MiuixIcons.ExpandLess,
                                    contentDescription = paramsTitle,
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            },
                        )
                    }
                }

                // 操作按钮行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { openImagePicker() },
                        modifier = Modifier.weight(1f),
                    ) { Text(getString(R.string.open)) }
                    Button(
                        onClick = { runSelectedCommand() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text(getString(R.string.run)) }
                    Button(
                        onClick = { saveOutput() },
                        modifier = Modifier.weight(1f),
                        // 图片处理成功后才可保存, 之前禁用
                        enabled = shareEnabled,
                    ) { Text(getString(R.string.save)) }
                }

                // 图片预览(非全屏固定高度; 全屏由 MainScreen 顶层覆盖层接管)
                if (showImagePreview && imagePath != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clipToBounds()
                            .height(320.dp),
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                SubsamplingScaleImageView(ctx).apply {
                                    setMinimumDpi(40)
                                }
                            },
                            update = { view ->
                                val path = imagePath
                                if (path != null && File(path).exists()) {
                                    view.setImage(ImageSource.uri(path))
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .matchParentSize(),
                        )
                        // 全屏按钮(仅图片处理成功后出现)
                        if (shareEnabled) {
                            IconButton(
                                onClick = { previewFullscreen = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp),
                                backgroundColor = MiuixTheme.colorScheme.surface.copy(alpha = 0.7f),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.ExpandMore,
                                    contentDescription = "全屏预览",
                                )
                            }
                        }
                    }
                }

                // 日志卡片
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = log,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        style = MiuixTheme.textStyles.button,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    @Composable
    fun AboutContent() {
        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            LeftAlignedTopBar(
                title = getString(R.string.app_name),
                subtitle = "v" + BuildConfig.VERSION_NAME,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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

    @Composable
    fun DirProcessContent() {
        val sp = getSharedPreferences("config", Activity.MODE_PRIVATE)
        var inputPath by remember { mutableStateOf("") }
        var outputPath by remember { mutableStateOf("") }
        var autoOutput by remember { mutableStateOf(false) }
        var selectedModel by remember { mutableIntStateOf(0) }
        var logText by remember { mutableStateOf(getString(R.string.dir_log_hint)) }
        var progressLog by remember { mutableStateOf(ProgressLogHelper()) }

        val inputDirLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri -> uri?.let { takePersistableUriPermission(it) } }
        val outputDirLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri -> uri?.let { takePersistableUriPermission(it) } }

        val presetLabels = resources.getStringArray(R.array.style_array)
        val clm = remember {
            CommandListManager(
                presetLabels,
                (sp.getString("extraPath", "") ?: "").trim(),
                (sp.getString("extraCommand", "") ?: "").trim(),
                (sp.getString("classicalFilters", getString(R.string.default_classical_filters)) ?: "")
                    .split(Regex("\\s+")).toTypedArray(),
                (sp.getString("magickFilters", getString(R.string.default_magick_filters)) ?: "")
                    .split(Regex("\\s+")).toTypedArray(),
            ).also { it.loadCustomLabels(sp.getString("customLabels", "")) }
        }
        val hiddenPrograms = remember { sp.getStringSet("hiddenPrograms", HashSet()) }
        val commandList = remember(clm) { clm.getDirectorySupportedCommands(hiddenPrograms) }
        val displayLabels = remember(clm) {
            clm.getDirectorySupportedLabels(sp.getBoolean("useCustomLabel", false), hiddenPrograms)
        }
        val name3Options = resources.getStringArray(R.array.name3)

        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            LeftAlignedTopBar(title = getString(R.string.dir_process_title))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SmallTitle(getString(R.string.dir_input_label))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                TextField(
                    value = inputPath,
                    onValueChange = {
                        inputPath = it
                        if (autoOutput) updateAutoOutputPath(it, selectedModel, commandList, name3Options) { outputPath = it }
                    },
                    label = getString(R.string.dir_input_hint),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Button(
                    onClick = { inputDirLauncher.launch(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(getString(R.string.dir_select_btn)) }
            }

            SmallTitle(getString(R.string.dir_output_label))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                TextField(
                    value = outputPath,
                    onValueChange = { outputPath = it },
                    label = getString(R.string.dir_output_hint),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Button(
                    onClick = { outputDirLauncher.launch(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(getString(R.string.dir_select_btn)) }
                CheckboxPreference(
                    title = getString(R.string.dir_auto_output_format, name3Options.getOrNull(name3) ?: ""),
                    checked = autoOutput,
                    onCheckedChange = {
                        autoOutput = it
                        if (it && inputPath.isNotEmpty()) {
                            updateAutoOutputPath(inputPath, selectedModel, commandList, name3Options) { outputPath = it }
                        }
                    },
                )
            }

            SmallTitle(getString(R.string.dir_model_label))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                OverlayDropdownPreference(
                    items = displayLabels.toList(),
                    selectedIndex = if (selectedModel < displayLabels.size) selectedModel else 0,
                    title = getString(R.string.dir_model_label),
                    onSelectedIndexChange = {
                        selectedModel = it
                        if (autoOutput && inputPath.isNotEmpty()) {
                            updateAutoOutputPath(inputPath, it, commandList, name3Options) { outputPath = it }
                        }
                    },
                )
            }

            SmallTitle(getString(R.string.menu_out))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = logText,
                    style = MiuixTheme.textStyles.button,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    startBatch(
                        inputPath, outputPath, selectedModel, commandList, displayLabels,
                        progressLog, name3Options,
                    ) { text, log -> logText = text; progressLog = log }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                enabled = !busy && inputPath.isNotEmpty() && outputPath.isNotEmpty(),
            ) { Text(getString(R.string.dir_start_btn)) }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                text = getString(R.string.dir_stop_btn),
                onClick = {
                    if (busy && processingService != null) {
                        processingService?.cancelTask()
                        logText += "\n--- Process stopped by user ---"
                        busy = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    private fun takePersistableUriPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun updateAutoOutputPath(
        inputPath: String,
        modelIndex: Int,
        commandList: Array<String>,
        name3Options: Array<String>,
        onResult: (String) -> Unit,
    ) {
        var dirName = File(inputPath).name.ifEmpty { "output" }
        val commandName = if (modelIndex in commandList.indices) {
            extractModelName(commandList[modelIndex])
        } else ""
        val timeStr = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        when (name3) {
            1 -> dirName = dirName + "-" + commandName
            2 -> dirName = dirName + "-" + commandName + "-" + timeStr
            3 -> dirName = dirName + "-" + timeStr
        }
        onResult(savePath + File.separator + dirName)
    }

    private fun startBatch(
        inputPath: String,
        outputPath: String,
        modelIndex: Int,
        commandList: Array<String>,
        displayLabels: Array<String>,
        progressLog: ProgressLogHelper,
        name3Options: Array<String>,
        onLog: (String, ProgressLogHelper) -> Unit,
    ) {
        if (!isBound || processingService == null) {
            showSnackbar(getString(R.string.dir_service_error))
            return
        }
        if (inputPath.isEmpty()) {
            showSnackbar(getString(R.string.dir_input_path_error))
            return
        }
        val inputDir = File(inputPath)
        if (!inputDir.exists() || !inputDir.isDirectory) {
            showSnackbar(getString(R.string.dir_input_invalid))
            return
        }
        if (outputPath.isEmpty()) {
            showSnackbar(getString(R.string.dir_output_path_error))
            return
        }
        if (modelIndex !in commandList.indices) {
            showSnackbar(getString(R.string.dir_model_error))
            return
        }

        val baseCommand = commandList[modelIndex]
        val cmdBuilder = StringBuilder(baseCommand)

        if (baseCommand.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.+".toRegex())) {
            if (tileSize > 0 && !baseCommand.contains(" -t "))
                cmdBuilder.append(" -t ").append(tileSize)
            if (!threadCount.isEmpty() && !baseCommand.contains(" -j "))
                cmdBuilder.append(" -j ").append(threadCount)
            if (useCPU && !baseCommand.startsWith("./srmd") && !baseCommand.startsWith("./mnnsr")
                && !baseCommand.contains(" -g ")
            )
                cmdBuilder.append(" -g -1")
            if (baseCommand.startsWith("./mnnsr") && !baseCommand.contains(" -b ")) {
                cmdBuilder.append(" -b ").append(mnnBackend)
            }
            val dirFormats = resources.getStringArray(R.array.dir_output_format)
            if (dirOutputFormat > 0 && dirOutputFormat < dirFormats.size && !baseCommand.contains(" -f ")) {
                cmdBuilder.append(" -f ").append(dirFormats[dirOutputFormat])
            }
        } else if (baseCommand.startsWith("./Anime4k")) {
            // Anime4KCPP v3.2.0：处理器由 -p 参数控制，跟随 GUI 的 useCPU 设置
            val proc = if (useCPU) "cpu" else "opencl"
            if (baseCommand.contains(" -p ")) {
                cmdBuilder.setLength(0)
                cmdBuilder.append(baseCommand.replace(Regex("\\s-p\\s+\\S+"), " -p $proc"))
            } else {
                cmdBuilder.append(" -p ").append(proc)
            }
        }

        val finalCmd = cmdBuilder.toString()
        val safeInputPath = ShellUtils.escapeShellArgument(inputPath + "/")
        val safeOutputPath = ShellUtils.escapeShellArgument(outputPath)
        val execCmd = finalCmd.replace("input.png", safeInputPath)
            .replace("output.png", safeOutputPath)

        val newLog = ProgressLogHelper()
        newLog.reset()
        newLog.appendLine(getString(R.string.dir_log_starting, inputPath))
        newLog.appendLine(getString(R.string.dir_log_output_to, outputPath))
        newLog.appendLine("Command: $execCmd")
        onLog(newLog.getDisplayText(), newLog)

        processingService?.startTask(execCmd, dir, notify, object : ImageProcessor.ProcessCallback {
            override fun onProgress(line: String) {
                runOnUiThread {
                    newLog.appendLine(line)
                    onLog(newLog.getDisplayText(), newLog)
                }
            }

            override fun onCompleted(result: String, success: Boolean) {
                runOnUiThread {
                    busy = false
                    val modelName = extractModelName(finalCmd)
                    val isNcnn = finalCmd.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.*".toRegex())
                    newLog.appendLine(newLog.getCompletionSummary(success, modelName, isNcnn))
                    onLog(newLog.getDisplayText(), newLog)
                    if (success) {
                        showSnackbar(getString(R.string.save_succeed) + "\n" + outputPath)
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    busy = false
                    newLog.appendLine("Error: $error")
                    onLog(newLog.getDisplayText(), newLog)
                }
            }
        })
        busy = true
    }

    private fun extractModelName(cmd: String): String {
        if (cmd.matches(".+\\s-m(\\s+)\\S*models-.+".toRegex())) {
            return cmd.replaceFirst(".+\\s-m(\\s+)\\S*models-(\\S+).*".toRegex(), "$2")
        } else if (cmd.startsWith("./Anime4k")) {
            val m = Regex(".+\\s-m\\s+(\\S+).*").find(cmd)?.groupValues?.get(1)
            return if (m != null) "Anime4k-$m" else "Anime4k"
        } else if (cmd.startsWith("./realcugan-ncnn")) {
            return "Real-CUGAN"
        } else if (cmd.matches(".+\\s-m(\\s+)(bicubic|bilinear|nearest|avir|de-nearest).*".toRegex())) {
            return cmd.replaceFirst(".+\\s-m(\\s+)(bicubic|bilinear|nearest|lancir|avir|de-nearest).*".toRegex(), "Classical-$2")
        } else if (cmd.startsWith("./magick input")) {
            return "Magick"
        } else if (cmd.startsWith("./resize-ncnn")) {
            return "Resize"
        }
        return ""
    }

    @Composable
    fun SettingsContent() {
        val focusManager = LocalFocusManager.current
        val sp = getSharedPreferences("config", Activity.MODE_PRIVATE)

        // ---------- 读取已有配置 ----------
        var selectCommand by remember { mutableIntStateOf(sp.getInt("selectCommand", 0)) }
        var tileSize by remember { mutableStateOf(sp.getInt("tileSize", 0).toString()) }
        var extraCommand by remember { mutableStateOf(sp.getString("extraCommand", "") ?: "") }
        var defaultCommand by remember {
            mutableStateOf(
                sp.getString(
                    "defaultCommand",
                    "./realsr-ncnn -i input.png -o output.png -m models-Real-ESRGANv3-anime -s 2",
                ) ?: ""
            )
        }
        var classicalFilters by remember {
            mutableStateOf(sp.getString("classicalFilters", getString(R.string.default_classical_filters)) ?: "")
        }
        var magickFilters by remember {
            mutableStateOf(
                CommandListManager.sanitizeMagickFilters(
                    (sp.getString("magickFilters", getString(R.string.default_magick_filters)) ?: "")
                        .split(Regex("\\s+")).toTypedArray()
                ).joinToString(" ")
            )
        }
        var extraPath by remember { mutableStateOf(sp.getString("extraPath", "") ?: "") }
        var savePath by remember { mutableStateOf(sp.getString("savePath", "") ?: "") }
        var threadCount by remember { mutableStateOf(sp.getString("threadCount", "") ?: "") }
        var mnnBackend by remember { mutableStateOf(sp.getInt("mnnBackend", 7).toString()) }

        var keepScreen by remember { mutableStateOf(sp.getBoolean("keepScreen", false)) }
        var useMultFiles by remember { mutableStateOf(sp.getBoolean("useMultFiles", false)) }
        var prePng by remember { mutableStateOf(sp.getBoolean("PrePng", true)) }
        var preFrame by remember { mutableStateOf(sp.getBoolean("PreFrame", true)) }
        var autoSave by remember { mutableStateOf(sp.getBoolean("autoSave", false)) }
        var useCPU by remember { mutableStateOf(sp.getBoolean("useCPU", false)) }
        var showSearchView by remember { mutableStateOf(sp.getBoolean("showSearchView", false)) }
        var showFinalCommand by remember { mutableStateOf(sp.getBoolean("showFinalCommand", false)) }
        var useCustomLabel by remember { mutableStateOf(sp.getBoolean("useCustomLabel", false)) }

        var format by remember { mutableIntStateOf(sp.getInt("format", 0)) }
        var dirOutputFormat by remember { mutableIntStateOf(sp.getInt("dirOutputFormat", 0)) }
        var name by remember { mutableIntStateOf(sp.getInt("name", 0)) }
        var name2 by remember { mutableIntStateOf(sp.getInt("name2", 0)) }
        var name3 by remember { mutableIntStateOf(sp.getInt("name3", 0)) }
        var orientation by remember { mutableIntStateOf(sp.getInt("ORIENTATION", 0)) }
        var notify by remember { mutableIntStateOf(sp.getInt("notify", 0)) }

        val hiddenPrograms = remember {
            sp.getStringSet("hiddenPrograms", HashSet()).orEmpty().toMutableSet()
        }
        var hideRealsr by remember { mutableStateOf(CommandListManager.PROGRAM_REALSR in hiddenPrograms) }
        var hideSrmd by remember { mutableStateOf(CommandListManager.PROGRAM_SRMD in hiddenPrograms) }
        var hideWaifu2x by remember { mutableStateOf(CommandListManager.PROGRAM_WAIFU2X in hiddenPrograms) }
        var hideRealcugan by remember { mutableStateOf(CommandListManager.PROGRAM_REALCUGAN in hiddenPrograms) }
        var hideMnnsr by remember { mutableStateOf(CommandListManager.PROGRAM_MNNSR in hiddenPrograms) }
        var hideResize by remember { mutableStateOf(CommandListManager.PROGRAM_RESIZE in hiddenPrograms) }
        var hideMagick by remember { mutableStateOf(CommandListManager.PROGRAM_MAGICK in hiddenPrograms) }
        var hideAnime4k by remember { mutableStateOf(CommandListManager.PROGRAM_ANIME4K in hiddenPrograms) }

        val presetLabels = resources.getStringArray(R.array.style_array)
        val clm = remember(extraPath, extraCommand, classicalFilters, magickFilters) {
            CommandListManager(
                presetLabels,
                extraPath.trim(),
                extraCommand.trim(),
                classicalFilters.split(Regex("\\s+")).toTypedArray(),
                magickFilters.split(Regex("\\s+")).toTypedArray(),
            )
        }
        val displayLabels = remember(clm, useCustomLabel) {
            clm.loadCustomLabels(sp.getString("customLabels", ""))
            clm.getDisplayLabels(useCustomLabel).toList()
        }

        val formatOptions = resources.getStringArray(R.array.format).toList()
        val dirFormatOptions = resources.getStringArray(R.array.dir_output_format).toList()
        val nameOptions = resources.getStringArray(R.array.name).toList()
        val name2Options = resources.getStringArray(R.array.name2).toList()
        val name3Options = resources.getStringArray(R.array.name3).toList()
        val orientationOptions = resources.getStringArray(R.array.oriental_item).toList()
        val notifyOptions = resources.getStringArray(R.array.notify_item).toList()

        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            LeftAlignedTopBar(title = getString(R.string.setting))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SmallTitle("命令")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                OverlayDropdownPreference(
                    items = displayLabels,
                    selectedIndex = if (selectCommand < displayLabels.size) selectCommand else 0,
                    title = getString(R.string.default_select_command),
                    onSelectedIndexChange = {
                        selectCommand = it
                        sp.edit().putInt("selectCommand", it).apply()
                    },
                )
                TextField(
                    value = defaultCommand,
                    onValueChange = { defaultCommand = it },
                    label = getString(R.string.input_command),
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    value = extraCommand,
                    onValueChange = { extraCommand = it },
                    label = getString(R.string.extra_command),
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            SmallTitle("通用")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                TextField(
                    value = tileSize,
                    onValueChange = { tileSize = it },
                    label = getString(R.string.tile_size),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    value = threadCount,
                    onValueChange = { threadCount = it },
                    label = getString(R.string.thread_count),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    value = mnnBackend,
                    onValueChange = { mnnBackend = it },
                    label = getString(R.string.mnn_backend),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                SwitchPreference(
                    title = getString(R.string.keep_screen),
                    checked = keepScreen,
                    onCheckedChange = {
                        keepScreen = it
                        sp.edit().putBoolean("keepScreen", it).apply()
                    },
                )
                SwitchPreference(
                    title = getString(R.string.choose_mult_files),
                    checked = useMultFiles,
                    onCheckedChange = {
                        useMultFiles = it
                        sp.edit().putBoolean("useMultFiles", it).apply()
                    },
                )
                SwitchPreference(
                    title = getString(R.string.preprocess_to_png),
                    checked = prePng,
                    onCheckedChange = {
                        prePng = it
                        sp.edit().putBoolean("PrePng", it).apply()
                    },
                )
                SwitchPreference(
                    title = getString(R.string.preprocess_frames),
                    checked = preFrame,
                    onCheckedChange = {
                        preFrame = it
                        sp.edit().putBoolean("PreFrame", it).apply()
                    },
                )
                SwitchPreference(
                    title = getString(R.string.use_cpu),
                    checked = useCPU,
                    onCheckedChange = {
                        useCPU = it
                        sp.edit().putBoolean("useCPU", it).apply()
                    },
                )
                SwitchPreference(
                    title = getString(R.string.auto_save),
                    checked = autoSave,
                    onCheckedChange = {
                        autoSave = it
                        sp.edit().putBoolean("autoSave", it).apply()
                    },
                )
                SwitchPreference(
                    title = getString(R.string.show_serarch_view),
                    checked = showSearchView,
                    onCheckedChange = {
                        showSearchView = it
                        sp.edit().putBoolean("showSearchView", it).apply()
                    },
                )
                SwitchPreference(
                    title = getString(R.string.show_final_command),
                    checked = showFinalCommand,
                    onCheckedChange = {
                        showFinalCommand = it
                        sp.edit().putBoolean("showFinalCommand", it).apply()
                    },
                )
            }

            SmallTitle(getString(R.string.save_setting))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                OverlayDropdownPreference(
                    items = formatOptions,
                    selectedIndex = format,
                    title = getString(R.string.save_format),
                    onSelectedIndexChange = {
                        format = it
                        sp.edit().putInt("format", it).apply()
                    },
                )
                OverlayDropdownPreference(
                    items = dirFormatOptions,
                    selectedIndex = dirOutputFormat,
                    title = getString(R.string.dir_output_format_title),
                    onSelectedIndexChange = {
                        dirOutputFormat = it
                        sp.edit().putInt("dirOutputFormat", it).apply()
                    },
                )
                OverlayDropdownPreference(
                    items = nameOptions,
                    selectedIndex = name,
                    title = getString(R.string.save_name),
                    onSelectedIndexChange = {
                        name = it
                        sp.edit().putInt("name", it).apply()
                    },
                )
                OverlayDropdownPreference(
                    items = name2Options,
                    selectedIndex = name2,
                    title = getString(R.string.save_name2),
                    onSelectedIndexChange = {
                        name2 = it
                        sp.edit().putInt("name2", it).apply()
                    },
                )
                OverlayDropdownPreference(
                    items = name3Options,
                    selectedIndex = name3,
                    title = getString(R.string.save_name3),
                    onSelectedIndexChange = {
                        name3 = it
                        sp.edit().putInt("name3", it).apply()
                    },
                )
                OverlayDropdownPreference(
                    items = orientationOptions,
                    selectedIndex = orientation,
                    title = getString(R.string.orientation),
                    onSelectedIndexChange = {
                        orientation = it
                        sp.edit().putInt("ORIENTATION", it).apply()
                    },
                )
                OverlayDropdownPreference(
                    items = notifyOptions,
                    selectedIndex = notify,
                    title = getString(R.string.notify),
                    onSelectedIndexChange = {
                        notify = it
                        sp.edit().putInt("notify", it).apply()
                    },
                )
                TextField(
                    value = savePath,
                    onValueChange = { savePath = it },
                    label = getString(R.string.save_path),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            SmallTitle(getString(R.string.interpolation))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                TextField(
                    value = classicalFilters,
                    onValueChange = { classicalFilters = it },
                    label = getString(R.string.classical_interpolation),
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    value = magickFilters,
                    onValueChange = { magickFilters = it },
                    label = getString(R.string.magick_filters),
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                TextField(
                    value = extraPath,
                    onValueChange = { extraPath = it },
                    label = getString(R.string.extra_path),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            SmallTitle(getString(R.string.hide_programs_title))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                CheckboxPreference(
                    title = getString(R.string.hide_realsr),
                    checked = hideRealsr,
                    onCheckedChange = {
                        hideRealsr = it
                        saveHiddenPrograms(sp, hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr, hideResize, hideMagick, hideAnime4k)
                    },
                )
                CheckboxPreference(
                    title = getString(R.string.hide_srmd),
                    checked = hideSrmd,
                    onCheckedChange = {
                        hideSrmd = it
                        saveHiddenPrograms(sp, hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr, hideResize, hideMagick, hideAnime4k)
                    },
                )
                CheckboxPreference(
                    title = getString(R.string.hide_waifu2x),
                    checked = hideWaifu2x,
                    onCheckedChange = {
                        hideWaifu2x = it
                        saveHiddenPrograms(sp, hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr, hideResize, hideMagick, hideAnime4k)
                    },
                )
                CheckboxPreference(
                    title = getString(R.string.hide_realcugan),
                    checked = hideRealcugan,
                    onCheckedChange = {
                        hideRealcugan = it
                        saveHiddenPrograms(sp, hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr, hideResize, hideMagick, hideAnime4k)
                    },
                )
                CheckboxPreference(
                    title = getString(R.string.hide_mnnsr),
                    checked = hideMnnsr,
                    onCheckedChange = {
                        hideMnnsr = it
                        saveHiddenPrograms(sp, hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr, hideResize, hideMagick, hideAnime4k)
                    },
                )
                CheckboxPreference(
                    title = getString(R.string.hide_resize),
                    checked = hideResize,
                    onCheckedChange = {
                        hideResize = it
                        saveHiddenPrograms(sp, hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr, hideResize, hideMagick, hideAnime4k)
                    },
                )
                CheckboxPreference(
                    title = getString(R.string.hide_magick),
                    checked = hideMagick,
                    onCheckedChange = {
                        hideMagick = it
                        saveHiddenPrograms(sp, hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr, hideResize, hideMagick, hideAnime4k)
                    },
                )
                CheckboxPreference(
                    title = getString(R.string.hide_anime4k),
                    checked = hideAnime4k,
                    onCheckedChange = {
                        hideAnime4k = it
                        saveHiddenPrograms(sp, hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr, hideResize, hideMagick, hideAnime4k)
                    },
                )
            }

            SmallTitle(getString(R.string.label_editor_title))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                SwitchPreference(
                    title = getString(R.string.use_custom_label),
                    checked = useCustomLabel,
                    onCheckedChange = {
                        useCustomLabel = it
                        sp.edit().putBoolean("useCustomLabel", it).apply()
                    },
                )
                ArrowPreference(
                    title = getString(R.string.label_editor_title),
                    onClick = {
                        startActivity(Intent(this@MainActivity, LabelEditorActivity::class.java))
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    if (saveSettings(
                            sp, selectCommand, tileSize, defaultCommand, extraCommand,
                            classicalFilters, magickFilters, threadCount, extraPath, savePath,
                            keepScreen, useMultFiles, prePng, preFrame, autoSave, useCPU,
                            showSearchView, showFinalCommand, useCustomLabel, format,
                            dirOutputFormat, name, name2, name3, orientation, notify, mnnBackend,
                            hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr,
                            hideResize, hideMagick, hideAnime4k,
                        )
                    ) {
                        showSnackbar(getString(R.string.save_succeed))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) { Text(getString(R.string.save)) }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                text = getString(R.string.reset),
                onClick = {
                    selectCommand = 2; format = 0; dirOutputFormat = 0
                    name = 0; name2 = 0; name3 = 0
                    useCPU = false; autoSave = false; showSearchView = false
                    showFinalCommand = false; useCustomLabel = false
                    savePath = ""; tileSize = "0"; threadCount = ""
                    extraPath = ""; mnnBackend = "7"
                    defaultCommand = "./realsr-ncnn -i input.png -o output.png -m models-Real-ESRGANv3-anime -s 2"
                    classicalFilters = getString(R.string.default_classical_filters)
                    magickFilters = getString(R.string.default_magick_filters)
                    saveSettings(
                        sp, selectCommand, tileSize, defaultCommand, extraCommand,
                        classicalFilters, magickFilters, threadCount, extraPath, savePath,
                        keepScreen, useMultFiles, prePng, preFrame, autoSave, useCPU,
                        showSearchView, showFinalCommand, useCustomLabel, format,
                        dirOutputFormat, name, name2, name3, orientation, notify, mnnBackend,
                        hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr,
                        hideResize, hideMagick, hideAnime4k,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    private fun saveHiddenPrograms(
        sp: SharedPreferences, hideRealsr: Boolean, hideSrmd: Boolean, hideWaifu2x: Boolean,
        hideRealcugan: Boolean, hideMnnsr: Boolean, hideResize: Boolean, hideMagick: Boolean,
        hideAnime4k: Boolean,
    ) {
        val hidden = HashSet<String>()
        if (hideRealsr) hidden.add(CommandListManager.PROGRAM_REALSR)
        if (hideSrmd) hidden.add(CommandListManager.PROGRAM_SRMD)
        if (hideWaifu2x) hidden.add(CommandListManager.PROGRAM_WAIFU2X)
        if (hideRealcugan) hidden.add(CommandListManager.PROGRAM_REALCUGAN)
        if (hideMnnsr) hidden.add(CommandListManager.PROGRAM_MNNSR)
        if (hideResize) hidden.add(CommandListManager.PROGRAM_RESIZE)
        if (hideMagick) hidden.add(CommandListManager.PROGRAM_MAGICK)
        if (hideAnime4k) hidden.add(CommandListManager.PROGRAM_ANIME4K)
        sp.edit().putStringSet("hiddenPrograms", hidden).apply()
    }

    private fun saveSettings(
        sp: SharedPreferences, selectCommand: Int, tileSize: String, defaultCommand: String,
        extraCommand: String, classicalFilters: String, magickFilters: String, threadCount: String,
        extraPath: String, savePath: String, keepScreen: Boolean, useMultFiles: Boolean,
        prePng: Boolean, preFrame: Boolean, autoSave: Boolean, useCPU: Boolean,
        showSearchView: Boolean, showFinalCommand: Boolean, useCustomLabel: Boolean,
        format: Int, dirOutputFormat: Int, name: Int, name2: Int, name3: Int,
        orientation: Int, notify: Int, mnnBackend: String,
        hideRealsr: Boolean, hideSrmd: Boolean, hideWaifu2x: Boolean, hideRealcugan: Boolean,
        hideMnnsr: Boolean, hideResize: Boolean, hideMagick: Boolean, hideAnime4k: Boolean,
    ): Boolean {
        val editor = sp.edit()
        editor.putInt("selectCommand", selectCommand)

        val tileSizeV = tileSize.ifEmpty { "0" }
        editor.putInt("tileSize", tileSizeV.toIntOrNull() ?: 0)
        editor.putString("defaultCommand", defaultCommand)

        val extraCommandV = extraCommand.trim().replace(Regex("\\s*\n\\s*"), "\n")
        editor.putString("extraCommand", extraCommandV)

        val classicalFiltersV = classicalFilters.trim().replace(Regex("\\s+"), " ")
        editor.putString("classicalFilters", classicalFiltersV)

        // 保存前清洗无效滤镜(如设备上残留的 anczos),避免生成非法 magick 命令
        val magickFiltersV = CommandListManager.sanitizeMagickFilters(
            magickFilters.trim().split(Regex("\\s+")).toTypedArray()
        ).joinToString(" ")
        editor.putString("magickFilters", magickFiltersV)

        val threadCountV = threadCount.trim().replace(Regex("[\\s/]+"), ":")
        if (threadCountV.isNotEmpty() && !threadCountV.matches(Regex("(\\d+):(\\d+):(\\d+)"))) {
            showSnackbar(getString(R.string.thread_count_err))
            return false
        }

        val extraPathV = extraPath.trim()
        if (folderHasErr(extraPathV)) return false
        editor.putString("extraPath", extraPathV)

        val savePathV = savePath.trim()
        if (folderHasErr(savePathV)) return false
        editor.putString("savePath", savePathV)

        editor.putString("threadCount", threadCountV)
        editor.putBoolean("keepScreen", keepScreen)
        editor.putBoolean("useMultFiles", useMultFiles)
        editor.putBoolean("PrePng", prePng)
        editor.putBoolean("PreFrame", preFrame)
        editor.putBoolean("autoSave", autoSave)
        editor.putBoolean("useCPU", useCPU)
        editor.putBoolean("showSearchView", showSearchView)
        editor.putBoolean("showFinalCommand", showFinalCommand)
        editor.putBoolean("useCustomLabel", useCustomLabel)

        val hidden = HashSet<String>()
        if (hideRealsr) hidden.add(CommandListManager.PROGRAM_REALSR)
        if (hideSrmd) hidden.add(CommandListManager.PROGRAM_SRMD)
        if (hideWaifu2x) hidden.add(CommandListManager.PROGRAM_WAIFU2X)
        if (hideRealcugan) hidden.add(CommandListManager.PROGRAM_REALCUGAN)
        if (hideMnnsr) hidden.add(CommandListManager.PROGRAM_MNNSR)
        if (hideResize) hidden.add(CommandListManager.PROGRAM_RESIZE)
        if (hideMagick) hidden.add(CommandListManager.PROGRAM_MAGICK)
        if (hideAnime4k) hidden.add(CommandListManager.PROGRAM_ANIME4K)
        editor.putStringSet("hiddenPrograms", hidden)

        editor.putInt("format", format)
        editor.putInt("dirOutputFormat", dirOutputFormat)
        editor.putInt("name", name)
        editor.putInt("name2", name2)
        editor.putInt("name3", name3)
        editor.putInt("ORIENTATION", orientation)
        editor.putInt("notify", notify)
        editor.putInt("mnnBackend", mnnBackend.toIntOrNull() ?: 7)
        editor.apply()
        return true
    }

    private fun folderHasErr(path: String): Boolean {
        if (path.isEmpty()) return false
        val file = File(path)
        if (!file.exists() || !file.isDirectory) {
            showSnackbar(getString(R.string.path_not_dir))
            return true
        }
        return false
    }

    private fun openImagePicker() {
        if (useMultFiles) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(intent, SELECT_MULTI_IMAGE)
        } else {
            val i = Intent(Intent.ACTION_PICK)
            i.type = "image/*"
            startActivityForResult(i, SELECT_IMAGE)
        }
    }

    private fun runSelectedCommand() {
        stopCommand()
        log = ""
        val cmds = command
        val cmdHead: String = if (cmds != null && selectCommand < cmds.size) {
            cmds[selectCommand]
        } else {
            displayLabels.getOrNull(selectCommand) ?: return
        }
        val cmd = StringBuilder(cmdHead)
        if (cmdHead.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.+".toRegex())) {
            if (tileSize > 0 && !cmdHead.contains(" -t "))
                cmd.append(" -t ").append(tileSize)
            if (!threadCount.isEmpty() && !cmdHead.contains(" -j "))
                cmd.append(" -j ").append(threadCount)
            if (useCPU && !cmdHead.startsWith("./srmd") && !cmdHead.startsWith("./mnnsr")
                && !cmdHead.contains(" -g ")
            )
                cmd.append(" -g -1")
            if (cmdHead.startsWith("./mnnsr") && !cmdHead.contains(" -b ")) {
                cmd.append(" -b ").append(mnnBackend)
            }
        } else if (cmdHead.startsWith("./Anime4k")) {
            // Anime4KCPP v3.2.0：处理器由 -p 参数控制，跟随 GUI 的 useCPU 设置
            val proc = if (useCPU) "cpu" else "opencl"
            if (cmdHead.contains(" -p ")) {
                cmd.setLength(0)
                cmd.append(cmdHead.replace(Regex("\\s-p\\s+\\S+"), " -p $proc"))
            } else {
                cmd.append(" -p ").append(proc)
            }
        }
        deleteFile(outputFile)
        if (inputIsGifAnimation) {
            outputGif?.delete()
            outputFile?.mkdir()
        }
        if (showFinalCommand) {
            showSnackbar(cmd.toString())
        }
        run20(cmd.toString(), false, true)
    }

    private fun saveOutput() {
        val f = if (inputIsGifAnimation) outputGif else outputFile
        if (f == null || !f.exists()) {
            showSnackbar(getString(R.string.output_not_exits))
            return
        } else if (f.isDirectory) {
            val files = f.listFiles()
            if (files == null || files.size == 0) {
                showSnackbar(getString(R.string.output_not_exits))
            } else {
                showSnackbar(getString(R.string.output_is_dir))
            }
            return
        }
        runCommand(saveOutputCmd())
        checkSaveOutput()
    }

    private fun requirePermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), MY_PERMISSIONS_REQUEST
            )
        } else {
            val file = File(savePath)
            if (file.isFile) file.delete()
            if (!file.exists()) file.mkdirs()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == MY_PERMISSIONS_REQUEST) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showSnackbar("Permission Denied")
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ==================== 文件与分享 ====================

    private fun readFileFromShare() {
        val intent = intent
        val action = intent.action
        if (Intent.ACTION_SEND == action) {
            deleteFile(inputFile)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            inputFileName = UriUntils.getFileName(uri, this)?.replaceFirst("\\.[^.]+$", "") ?: ""
            Log.i("input file name", inputFileName)
            whiteFileFromUri(uri, "")
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            handleSelectedImages(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        }
    }

    private fun whiteFileFromUri(uri: Uri?, path: String): Boolean {
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    saveInputImage(inputStream, path)
                } else {
                    showSnackbar(getString(R.string.share_is_null))
                }
                return true
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return false
    }

    // 处理选中的多个文件
    private fun handleSelectedImages(uris: ArrayList<Uri>?) {
        if (uris == null || uris.isEmpty()) return
        deleteFile(inputFile)
        if (uris.size == 1) {
            val url = uris[0]
            inputFileName = UriUntils.getFileName(url, this)?.replaceFirst("\\.[^.]+$", "") ?: ""
            Log.i("input file name", inputFileName)
            try {
                val inputStream = contentResolver.openInputStream(url)
                if (inputStream != null) {
                    saveInputImage(inputStream, "")
                } else {
                    showSnackbar("input == null")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return
            }
            return
        }

        inputFile?.mkdirs()
        outputFile?.delete()

        val f = SimpleDateFormat("MMdd_HHmmss")
        val time = f.format(Date())
        for (i in uris.indices) {
            val uri = uris[i]
            inputFileName = UriUntils.getFileName(uri, this)?.replaceFirst("\\.[^.]+$", "") ?: ""
            when (name2) {
                0 -> inputFileName = String.format("%s_%s", inputFileName, time)
                1 -> inputFileName = String.format("%s_%d", inputFileName, i)
                2 -> inputFileName = String.format("%s_%d", time, i)
                3 -> inputFileName = time + "_" + inputFileName
            }
            var inputFilePath = String.format("%s/input.png/%s.png", dir, inputFileName)
            var j = 0
            while (File(inputFilePath).exists()) {
                j++
                inputFilePath = dir + "/input.png/" + inputFileName + "_" + j + ".png"
            }
            whiteFileFromUri(uri, inputFilePath)
        }
        val inputFileSize = inputFile?.listFiles()?.size ?: 0
        log = String.format(getString(R.string.input_file_size), inputFileSize)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val url = data.data
            if (requestCode == SELECT_IMAGE && url != null) {
                deleteFile(inputFile)
                inputFileName = UriUntils.getFileName(url, this)?.replaceFirst("\\.[^.]+$", "") ?: ""
                Log.i("input file name", inputFileName)
                try {
                    val inputStream = contentResolver.openInputStream(url)
                    if (inputStream != null) {
                        saveInputImage(inputStream, "")
                    } else {
                        showSnackbar("input == null")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    return
                }
            } else if (requestCode == SELECT_MULTI_IMAGE) {
                val clipData = data.clipData
                if (clipData != null) {
                    val imageUris = ArrayList<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        imageUris.add(clipData.getItemAt(i).uri)
                    }
                    handleSelectedImages(imageUris)
                }
            }
        }
    }

    fun shareImage(path: String) {
        val shareIntent = Intent()
        var contentUri: Uri? = null
        var file: File? = null
        if (outputSavePath.isNotEmpty()) {
            file = File(outputSavePath)
            if (file.exists()) {
                contentUri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", file
                )
            }
        }

        if (contentUri == null) {
            file = File(dir, path)
            if (file.exists()) {
                contentUri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", file
                )
            }
        }

        if (contentUri != null) {
            val suffix = file?.name?.replaceFirst(".+\\.([^.]+)$".toRegex(), "$1")
                ?.lowercase(Locale.ROOT)
            shareIntent.type = when (suffix) {
                "png" -> "image/png"
                "jpg" -> "image/jpg"
                "webp" -> "image/webp"
                "heif" -> "image/heif"
                "gif" -> "image/gif"
                else -> "image/*"
            }
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri)
            Log.i("shareImage()", "uri = $contentUri")
            startActivity(Intent.createChooser(shareIntent, "Share"))
        } else {
            showSnackbar(getString(R.string.output_not_exits))
        }
    }

    private fun scanFiles(filePath: Array<String>) {
        Log.i("scanFiles()", "length=${filePath.size}")
        try {
            MediaScannerConnection.scanFile(applicationContext, filePath, null) { _, _ -> }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkSaveOutput() {
        val file = File(outputSavePath)
        if (file.exists()) {
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(file)
            sendBroadcast(intent)
            showSnackbar(getString(R.string.save_succeed) + "\n" + outputSavePath)
        } else {
            showSnackbar(getString(R.string.save_fail))
        }
    }

    // 生成输出图片的保存命令。采用"延迟转义"策略：在字符串构建阶段不进行转义，仅在最终返回时统一转义。
    private fun saveOutputCmd(): String {
        val f = SimpleDateFormat("MMdd_HHmmss")
        outputSavePath = savePath + File.separator
        when (name) {
            0 -> outputSavePath += modelName + "_" + f.format(Date())
            1 -> outputSavePath += inputFileName + "_" + modelName + "_" + f.format(Date())
            2 -> outputSavePath += inputFileName + "_" + modelName
            3 -> outputSavePath += inputFileName + "_" + f.format(Date())
            4 -> outputSavePath += inputFileName
            else -> outputSavePath += "output"
        }

        var cmd: String
        if (inputIsGifAnimation) {
            outputSavePath += ".gif"
            cmd = "cp " + dir + "/output.gif"
        } else if (format == 0) {
            outputSavePath += ".png"
            cmd = "cp " + dir + "/output.png"
        } else {
            // 其他格式需要使用image magic进行转换，会额外消耗时间。但是为了方便，没有写到新线程上。
            if (format == 1) {
                outputSavePath += ".webp"
                cmd = "./magick output.png"
            } else if (format == 2) {
                outputSavePath += ".gif"
                cmd = "./magick output.png"
            } else if (format == 3) {
                outputSavePath += ".heic"
                cmd = "./magick output.png"
            } else {
                outputSavePath += ".jpg"
                val q = formats[format].replace(Regex("[a-zA-Z%\\s]+"), "")
                cmd = if (q.length > 0) "./magick output.png -quality $q" else "./magick output.png"
            }
        }
        return cmd + " " + ShellUtils.escapeShellArgument(outputSavePath)
    }

    private fun sendNotification(mContext: Context, text: String?, force: Boolean) {
        // New Logic: 0=Silent, 1=Result, 2=Detailed, 3=Detailed(AutoDismiss).
        if (!force && (notify == 0 || notify == 3)) return

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (text == null) {
            notificationManager.cancel(NOTIFY_ID)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_RESULT,
                getString(R.string.notification_channel_result),
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.description = "Shows result of image processing tasks"
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val builder = NotificationCompat.Builder(mContext, CHANNEL_ID_RESULT)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
        notificationManager.notify(NOTIFY_ID, builder.build())
    }

    private fun stopCommand() {
        if (isBound && processingService != null) {
            processingService?.cancelTask()
            busy = false
            progressText = ""
        }
    }

    // ==================== 命令执行 ====================

    // 在主进程执行命令但是不刷新UI，也不被打断
    fun getGifFrameDelay(path: String): Int {
        val con = StringBuilder()
        var result: String?
        try {
            val processBuilder = ProcessBuilder("sh")
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            val os = process.outputStream
            // dir 来自应用缓存目录，参数来源可信；path 为用户文件需转义
            val cmd = "cd $dir; export LD_LIBRARY_PATH=$dir" +
                "; ./magick identify -format \"%T \" " + ShellUtils.escapeShellArgument(path) + " "
            os.write((cmd + "\n").toByteArray())
            os.write("exit\n".toByteArray())
            os.flush()
            os.close()
            val br = BufferedReader(InputStreamReader(process.inputStream))
            while (br.readLine().also { result = it } != null) {
                con.append(result).append('\n')
            }
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d("get_gif_frame_delay()", "crash; result=$con")
            return -1
        }

        val data = con.toString().trim().split("\\s+".toRegex())
        if (data.size < 2) return 0

        var avg = data[1].toInt()
        var dif = 0
        for (s in data) {
            dif += (s.toInt() - avg)
        }
        avg = avg + dif / data.size
        Log.d("get_gif_frame_delay()", "finish; result=$con")
        return avg
    }

    // 在主进程执行命令但是不刷新UI，也不被打断
    fun runCommand(command: String): Boolean {
        if (command.trim().length < 1) {
            Log.d("run_command", "command=$command; break")
            return false
        }

        val con = StringBuilder()
        var result: String?
        try {
            val processBuilder = ProcessBuilder("sh")
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            val os = process.outputStream
            if (command.startsWith("./magick")) {
                // dir 来自应用缓存目录，参数来源可信
                val magickCmd = "cd $dir; export LD_LIBRARY_PATH=$dir; $command"
                os.write((magickCmd + "\n").toByteArray())
            } else {
                os.write((command + "\n").toByteArray())
            }
            os.write("exit\n".toByteArray())
            os.flush()
            os.close()
            val br = BufferedReader(InputStreamReader(process.inputStream))
            while (br.readLine().also { result = it } != null) {
                con.append(result).append('\n')
            }
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d("run_command", "command=$command; crash; result=$con")
            return false
        }
        Log.d("run_command", "command=$command; finish; result=$con")
        return true
    }

    // 主要的运行命令的方式
    @Synchronized
    fun run20(cmd: String, benchMarkMode: Boolean, sr: Boolean): Boolean {
        Log.i("run20", "cmd = $cmd")
        val timeStart = System.currentTimeMillis()
        var exportDir = false

        var finalCmd = cmd
        if (cmd.startsWith("./realsr-ncnn")
            || cmd.startsWith("./mnnsr-ncnn")
            || cmd.startsWith("./srmd-ncnn")
            || cmd.startsWith("./realcugan-ncnn")
            || cmd.startsWith("./resize-ncnn")
            || cmd.startsWith("./waifu2x-ncnn")
            || cmd.startsWith("./magick input")
            || cmd.startsWith("./Anime4k")
        ) {
            if (cmd.contains(" input.png ") && cmd.contains(" output.png ")) {
                if (inputFile?.isDirectory == true && !inputIsGifAnimation) {
                    exportDir = true
                    val safeSavePath = ShellUtils.escapeShellArgument(savePath)
                    finalCmd = cmd.replace(" output.png ", " $safeSavePath ")
                    val dirFormats = resources.getStringArray(R.array.dir_output_format)
                    if (dirOutputFormat > 0 && dirOutputFormat < dirFormats.size) {
                        if (!finalCmd.contains(" -f ") &&
                            finalCmd.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.*".toRegex())
                        ) {
                            finalCmd += " -f " + dirFormats[dirOutputFormat]
                        }
                    }
                }
                if (cmd.startsWith("./magick input.png") || cmd.startsWith("./resize-ncnn -i input.png")) {
                    Log.i("run20", "deleteFile $outputFile")
                    deleteFile(outputFile)
                }
            }

            busy = true
            shareEnabled = false
            progressText = ""
            modelName = "Real-ESRGAN-anime"
            if (cmd.matches(".+\\s-m(\\s+)\\S*models-.+".toRegex())) {
                modelName = cmd.replaceFirst(".+\\s-m(\\s+)\\S*models-(\\S+).*".toRegex(), "$2")
            }
            if (cmd.startsWith("./Anime4k")) {
                val m = Regex(".+\\s-m\\s+(\\S+).*").find(cmd)?.groupValues?.get(1)
                modelName = if (m != null) "Anime4k-$m" else "Anime4k"
            } else if (modelName.matches("(se|nose|pro)".toRegex())) {
                modelName = "Real-CUGAN-" + modelName
            } else if (cmd.startsWith("./realcugan-ncnn")) {
                modelName = "Real-CUGAN"
                if (cmd.contains(" -c "))
                    modelName += cmd.replaceFirst(".+\\s-c(\\s+)(\\S+)\\s.*".toRegex(), "-C$2")
                if (cmd.contains(" -n "))
                    modelName += cmd.replaceFirst(".+\\s-n(\\s+)(\\S+)\\s.*".toRegex(), "-Noise$2")
            } else if (cmd.matches(".+\\s-m(\\s+)(bicubic|bilinear|nearest|avir|de-nearest).*".toRegex())) {
                modelName = cmd.replaceFirst(
                    ".+\\s-m(\\s+)(bicubic|bilinear|nearest|lancir|avir|de-nearest).*".toRegex(),
                    "Classical-$2",
                )
            } else if (cmd.matches(".*waifu2x.+models-(cugan|cunet|upconv).*".toRegex())) {
                modelName = cmd.replaceFirst(
                    ".*waifu2x.+models-(cugan|cunet|upconv_7_photo|upconv_7_anime).*".toRegex(),
                    "Waifu2x-$1",
                )
            } else if (cmd.startsWith("./magick input")) {
                modelName = if (cmd.contains("-filter"))
                    cmd.replaceFirst(".*-filter\\s+(\\w+).+".toRegex(), "Magick-$1") else "Magick"
            } else if (cmd.startsWith("./mnnsr")) {
                if (cmd.matches(".+\\s-d\\s+\\d+\\s.*".toRegex())) {
                    modelName = "MNNSR-Decensor" + cmd.replaceFirst(".+\\s-d\\s+(\\d+)\\s.*".toRegex(), "$1")
                } else {
                    val v = CommandListManager.getNameFromModelPath(
                        cmd.replaceFirst(".+\\s-m(\\s+)(\\S+)\\s.*".toRegex(), "$2"), "MNNSR"
                    )
                    modelName = v[0]
                }
            }
        } else {
            modelName = "SR"
        }

        val runNcnn = benchMarkMode || modelName != "SR"
        var exportOneFile = runNcnn && (autoSave || (inputFile?.isDirectory == true && inputIsGifAnimation))
            && cmd.contains("output.png")
        if (benchMarkMode) {
            exportOneFile = false
            busy = true
            progressText = ""
        }
        val save = exportOneFile

        val builder = CommandBuilder()
        builder.append(finalCmd)
        if (save) {
            val exportCmd = saveOutputCmd()
            if (inputIsGifAnimation)
                builder.append(
                    ";./magick -delay $inputGifDelay output.png/* -loop 0 " +
                        ShellUtils.escapeShellArgument(outputSavePath)
                )
            else
                builder.append(";" + exportCmd)
        } else {
            outputSavePath = ""
        }

        val executionCmd = builder.build()
        val effectivelyFinalCmd = finalCmd
        val finalExportDir = exportDir

        progressLogHelper = ProgressLogHelper()

        if (isBound && processingService != null) {
            progressLogHelper?.reset()
            processingService?.startTask(executionCmd, dir, notify, object : ImageProcessor.ProcessCallback {
                override fun onProgress(line: String) {
                    progressLogHelper?.appendLine(line)
                    runOnUiThread {
                        log = progressLogHelper?.getDisplayText() ?: ""
                        if (progressLogHelper?.hasProgress() == true) {
                            progressText = progressLogHelper?.getProgressText() ?: ""
                        }
                    }
                }

                override fun onCompleted(result: String, success: Boolean) {
                    var logResult = progressLogHelper?.getCompletionSummary(success, modelName, runNcnn) ?: ""
                    if (benchMarkMode) {
                        logResult = logResult.replace(
                            "\n",
                            ", Benchmark run on ${DeviceInfo.getConfigStr(useCPU, tileSize)}\n" +
                                DeviceInfo.getInfo(this@MainActivity),
                        )
                    }
                    progressLogHelper?.appendLine(logResult)
                    val finalLog = progressLogHelper?.getFullLog() ?: ""
                    log = finalLog

                    runOnUiThread {
                        busy = false
                        shareEnabled = success
                        progressText = if (success) getString(R.string.done) else getString(R.string.notification_fail)
                        val forceShow = !success && notify == 3
                        sendNotification(
                            this@MainActivity,
                            if (success) getString(R.string.done) else getString(R.string.notification_fail),
                            forceShow,
                        )

                        if (success) {
                            if (save) {
                                if (!outputFile?.exists()!!) {
                                    showSnackbar(getString(R.string.output_not_exits))
                                } else {
                                    checkSaveOutput()
                                }
                            } else if (finalExportDir) {
                                showSnackbar(getString(R.string.save_succeed) + "\n" + outputSavePath)
                            }

                            if (!save && inputFile?.isDirectory == true) {
                                if (inputIsGifAnimation) {
                                    scanFiles(arrayOf(outputSavePath))
                                } else {
                                    val files = inputFile?.listFiles()
                                    if (files != null) {
                                        val outputPaths = ArrayList<String>()
                                        for (file in files) {
                                            outputPaths.add(savePath + File.separator + file.name)
                                        }
                                        scanFiles(outputPaths.toTypedArray())
                                    }
                                }
                            }

                            val showImgView = effectivelyFinalCmd.contains("output.png")
                            if (showImgView) {
                                if (outputFile?.exists() == true && outputFile?.isFile == true) {
                                    updateImage(dir + "/output.png", "${getString(R.string.hr)}\n$log", false)
                                } else if (inputIsGifAnimation && outputFile?.exists() == true
                                    && outputFile?.isDirectory == true && (outputFile?.listFiles()?.size ?: 0) > 1
                                ) {
                                    updateImage(outputFile?.listFiles()!![0].path, "${getString(R.string.hr)}\n$log", false)
                                } else {
                                    updateImage(dir + "/input.png", "${getString(R.string.lr)}\n$log", false)
                                }
                            }
                            if (!effectivelyFinalCmd.contains("output.png")) showImagePreview = false
                        } else {
                            if (!effectivelyFinalCmd.contains("output.png")) showImagePreview = false
                        }
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        busy = false
                        shareEnabled = false
                        log += "\nError: $error"
                        sendNotification(this@MainActivity, getString(R.string.notification_fail), true)
                    }
                }
            })
        } else {
            showSnackbar("Service not bound")
            return false
        }
        return true
    }

    // ==================== 图片处理 ====================

    /**
     * 保存文件
     *
     * @param in   输出的文件流
     * @param path 输出的文件路径，路径为空时保存为input.png
     * @return 是否保存成功
     */
    private fun saveInputImage(inputStream: InputStream, path: String): Boolean {
        Log.i("saveInputImage", "start ")
        inputIsGifAnimation = false
        var inputOneImage = false
        var p = path
        if (p.isEmpty()) {
            inputOneImage = true
            p = dir + "/input.png"
        }
        var file = File(p)
        if (file.exists()) {
            file.delete()
        }
        try {
            val buffer = ByteArray(4112)
            var read: Int
            var match = -1
            read = inputStream.read(buffer)
            if (read != -1) {
                if (prePng) {
                    match = PreprocessToPng.match(buffer)
                    if (match >= 0) {
                        file = File(dir + "/tmp")
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                }
            }

            file.createNewFile()
            val outStream = FileOutputStream(file)
            outStream.write(buffer, 0, read)
            while (inputStream.read(buffer).also { read = it } != -1) {
                outStream.write(buffer, 0, read)
            }
            outStream.flush()
            outStream.close()

            if (match >= 0) {
                if (PreprocessToPng.isHeif(match) || PreprocessToPng.isAVIF(match)) {
                    val bitmap = BitmapFactory.decodeFile(dir + "/tmp")
                    try {
                        val out = FileOutputStream(p)
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                } else if (preFrame && inputOneImage && PreprocessToPng.isGIF(match)) {
                    // 如果输入一个文件，且文件为多帧gif，则预处理为多个图片
                    inputGifDelay = getGifFrameDelay(dir + "/tmp")
                    inputIsGifAnimation = inputGifDelay > 0
                    Log.i("inputGifDelay", "$inputGifDelay, $inputIsGifAnimation")
                    if (inputIsGifAnimation) {
                        deleteFile(inputFile)
                        inputFile?.mkdirs()
                        runCommand("./magick tmp -coalesce -delay 0 input.png/%04d.png")
                    } else {
                        runCommand("./magick tmp " + ShellUtils.escapeShellArgument(p))
                    }
                } else {
                    runCommand("./magick tmp " + ShellUtils.escapeShellArgument(p))
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
        try {
            inputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updateImage(dir + "/input.png", getString(R.string.lr), false)
        return true
    }

    private fun updateImage(path: String, text: String, keepScreen: Boolean) {
        Log.i("saveInputImage", "runOnUiThread")
        val file = File(path)
        runOnUiThread {
            if (file.exists()) {
                if (file.isDirectory) {
                    if ((file.listFiles()?.size ?: 0) > 0) {
                        showImagePreview = true
                        imagePath = file.listFiles()!![0].path
                        Log.i("saveInputImage", "finish, directory")
                    } else {
                        showImagePreview = false
                        Log.i("saveInputImage", "finish, empty directory")
                    }
                    log = text
                } else {
                    showImagePreview = true
                    imagePath = path
                    log = getImageResolution(file, text)
                    Log.i("saveInputImage", "finish, file")
                }
            } else {
                Log.i("saveInputImage", "skip")
            }
        }
    }

    private fun showImage(file: File?, info: String) {
        if (file == null) {
            showImagePreview = false
            log = info
        } else if (file.exists() && !file.isDirectory) {
            showImagePreview = true
            imagePath = file.absolutePath
            log = getImageResolution(file, info)
        } else if (file.isDirectory) {
            showImagePreview = false
            val files = file.listFiles()
            if (files?.size ?: 0 < 1) {
                log = getString(R.string.image_not_exists)
            } else {
                log = getString(R.string.image_is_directory)
            }
        } else {
            showImagePreview = false
            log = getString(R.string.image_not_exists)
        }
    }

    private fun runFakeCommand(q: String?): Boolean {
        if (q == null) return true
        if (q.isEmpty()) return true
        return when {
            q == "help" -> {
                showImage(titleFile, getString(R.string.default_log))
                true
            }
            q == "in" -> {
                showImage(inputFile, getString(R.string.lr))
                true
            }
            q == "out" -> {
                showImage(outputFile, getString(R.string.hr))
                true
            }
            q.startsWith("show ") -> {
                var path = q.replaceFirst("\\s*show\\s+(\\S+)\\s*".toRegex(), "$1")
                var file = File(path)
                if (!file.exists()) {
                    path = dir + "/" + path
                    file = File(path)
                }
                showImage(file, getString(R.string.show) + path)
                true
            }
            q == "none" -> {
                showImage(null, getString(R.string.menu_reset_cache))
                true
            }
            q == CMD_RESET_CACHE -> {
                showImage(null, getString(R.string.menu_reset_cache) + "...")
                false
            }
            else -> false
        }
    }

    companion object {
        private const val SELECT_IMAGE = 1
        private const val SELECT_MULTI_IMAGE = 2
        private const val MY_PERMISSIONS_REQUEST = 100
        /** 对比位图最长边上限(硬件纹理安全值), 超过则降采样, 避免 "trying to draw too large bitmap" 崩溃 */
        private const val MAX_COMPARE_BITMAP_SIZE = 2048
        private val BENCH_MARK_COMMANDS = arrayOf(
            "./realsr-ncnn -c 46 -i img/PM5544.jpeg -o input.png  -m models-Real-ESRGAN",
            "./realsr-ncnn -c 46 -i input.png -o output.png  -m models-Real-ESRGANv3-anime -s 4",
        )
        private const val CMD_RESET_CACHE =
            ";rm -f *.cache;rm -f */*.cache;chmod +x *; echo Cache has been reset.;ls"
        private const val NOTIFY_ID = 1
        private const val CHANNEL_ID_RESULT = "channel_result"

        /**
         * 扫描模型目录下的模型文件名, 智能提取该模型支持的放大倍率列表。
         * 命名规则(见 assets/realsr/models-* 目录):
         *  - models-pro/se/nose: up2x-conservative.bin 等, 倍率在前缀 up&lt;N&gt;x
         *  - Real-ESRGAN 系列: x2.bin / x3.bin / x4.bin, 倍率为 x&lt;N&gt;
         *  - MNN 模型: ESRGAN-MoeSR-jp_Illustration-x4.mnn, 倍率内嵌文件名
         * 优先匹配 up&lt;N&gt;x, 其次 x&lt;N&gt;, 结果按数字升序去重; 扫描失败返回空列表
         * (GUI 回退到标签锚点解析的倍率)。
         */
        fun scanModelScales(assets: AssetManager?, modelPaths: Collection<String>): kotlin.collections.List<String> {
            if (assets == null || modelPaths.isEmpty()) return emptyList()
            val scaleSet = HashSet<Int>()
            for (path in modelPaths) {
                val files = try {
                    assets.list("realsr/$path") ?: continue
                } catch (e: Exception) {
                    continue
                }
                for (f in files) {
                    if (!f.endsWith(".bin") && !f.endsWith(".mnn") && !f.endsWith(".param")) continue
                    val n = Regex("up(\\d+)x", RegexOption.IGNORE_CASE).find(f)?.groupValues?.get(1)
                        ?: Regex("[xX](\\d+)").find(f)?.groupValues?.get(1)
                        ?: continue
                    n.toIntOrNull()?.let { scaleSet.add(it) }
                }
            }
            return scaleSet.sorted().map { "x$it" }
        }

        /**
         * 将标签拆分为 (模型, 放大倍率, 参数) 三维, 供主页下拉独立选择。
         * 划分规则: 以第一个 "x<数字>" 段为倍率锚点, 锚点前的段用 "-" 连接为模型名,
         * 锚点后的段用 "-" 连接为参数名(降噪等级等)。
         * 例: real-cugan-x2-noise1 → (real-cugan, x2, noise1)
         *     Anime4k-x2-ACNet-F8B8-HDN → (Anime4k, x2, ACNet-F8B8-HDN)
         * 标签无倍率锚点(如 realsr-general-v3): 模型名保持整段硬编码原样,
         * 倍率从命令参数 -s/-f 兜底解析(命令为 null/空时倍率为空)。
         * 兼容自定义命令标签: 不满足 x<数字> 约定时整段作为模型名, 不报错。
         */
        fun parseLabelDims(label: String, cmd: String = ""): Triple<String, String, String> {
            if (label.isBlank()) return Triple("", "", "")
            val parts = label.split("-")
            val anchor = parts.indexOfFirst { it.matches(Regex("x\\d+")) }
            if (anchor < 0) {
                // 无倍率锚点: 模型名保持整段原样, 倍率从命令 -s/-f 兜底并统一为 xN 格式
                val raw = Regex("-s\\s+(\\S+)").find(cmd)?.groupValues?.get(1)
                    ?: Regex("-f\\s+(\\S+)").find(cmd)?.groupValues?.get(1) ?: ""
                val scale = if (raw.matches(Regex("x\\d+"))) raw
                    else if (raw.matches(Regex("\\d+"))) "x$raw" else raw
                return Triple(label, scale, "")
            }
            // 模型名称保持硬编码原样(不清理前缀、不改名), 锚点前段用 "-" 连接
            val model = parts.take(anchor).joinToString("-")
            val scale = parts[anchor]
            val params = parts.drop(anchor + 1).joinToString("-")
            return Triple(model, scale, params)
        }

        // 删除文件或者目录
        fun deleteFile(f: File?) {
            if (f == null) return
            if (f.isDirectory) {
                val files = f.listFiles()
                if (files != null) {
                    for (file in files) {
                        deleteFile(file)
                    }
                }
            }
            f.delete()
        }

        private fun getImageResolution(file: File, info: String): String {
            if (info.trim().contains("\n")) return info
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
            val width = options.outWidth
            val height = options.outHeight
            return if (width > 0 && height > 0) info + " " + width + "x" + height else info
        }
    }
}
