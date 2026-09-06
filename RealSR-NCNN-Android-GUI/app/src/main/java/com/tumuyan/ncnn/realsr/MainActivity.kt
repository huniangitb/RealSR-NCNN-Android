package com.tumuyan.ncnn.realsr

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tumuyan.ncnn.realsr.ui.AppTheme
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.extended.More
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.HashSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主界面: 完全重新设计, 采用 Miuix / HyperOS 设计语言。
 * 保留原有全部业务逻辑(选图、放大、导出、目录批量、基准测试等),
 * UI 层全部替换为 Compose + Miuix 组件。
 */

class MainActivity : ComponentActivity() {
    internal var selectCommand by mutableStateOf(0)
    internal var log by mutableStateOf("")
    internal var progressText by mutableStateOf("")
    internal var busy by mutableStateOf(false)
    /** 图片处理成功后才可分享(之前置灰) */
    internal var shareEnabled by mutableStateOf(false)
    /** 全屏预览(类似视频全屏:隐藏系统栏, 覆盖全窗口) */
    internal var previewFullscreen by mutableStateOf(false)
    /** 调优管理页是否打开(全屏二级页, 由设置页入口/页面返回控制) */
    internal var showTunePage by mutableStateOf(false)
    internal var imagePath by mutableStateOf<String?>(null)
    internal var showImagePreview by mutableStateOf(false)

    internal val galleryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        .toString() + File.separator + "RealSR"
    internal var outputFile: File? = null
    internal var outputGif: File? = null
    internal var inputFile: File? = null
    internal var titleFile: File? = null

    /** dir 来自应用缓存目录, 参数来源可信, 不存在注入风险 */
    internal var dir = ""
    internal var cache_dir = ""
    internal var modelName = "SR"
    internal var command: Array<String>? = null
    internal var commandListManager: CommandListManager? = null
    internal var progressLogHelper: ProgressLogHelper? = null
    internal var inputFileName = ""
    internal var outputSavePath = ""
    internal var inputIsGifAnimation = false
    internal var inputGifDelay = 0

    internal var format = 0
    internal var name = 0
    internal var name2 = 0
    internal var name3 = 0
    internal var notify = 0
    internal var dirOutputFormat = 0
    internal var tileSize = 0
    internal var maxTileSize = 256
    internal var decensor = false
    internal var useCPU = false
    internal var mnnBackend = 3
    internal var mnnsrLoadOpt = 0
    // 开启 GPU 调优的模型名(逗号分隔子串, 匹配到即对该模型追加 -T; 空=全部跳过调优)
    internal var tuneModels = ""
    internal var keepScreen = false
    internal var useMultFiles = false
    internal var prePng = true
    internal var preFrame = true
    internal var autoSave = false
    internal var showFinalCommand = false
    internal var savePath = galleryPath
    internal var formats: Array<String> = emptyArray()
    internal var displayLabels: Array<String> = emptyArray()

    internal var processingService: ProcessingService? = null
    internal var isBound = false

    internal val snackbarHostState = SnackbarHostState()

    /** Miuix Snackbar 提示(替代 Toast) */
    internal fun showSnackbar(text: String) {
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
        maxTileSize = sp.getInt("maxTileSize", 256)
        decensor = sp.getBoolean("decensor", false)
        keepScreen = sp.getBoolean("keepScreen", false)
        useMultFiles = sp.getBoolean("useMultFiles", false)
        prePng = sp.getBoolean("PrePng", true)
        preFrame = sp.getBoolean("PreFrame", true)
        useCPU = sp.getBoolean("useCPU", false)
        mnnBackend = sp.getInt("mnnBackend", 3)
        mnnsrLoadOpt = sp.getInt("mnnsrLoadOpt", 0)
        tuneModels = sp.getString("tuneModels", "") ?: ""
        autoSave = sp.getBoolean("autoSave", false)
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

    /** 执行 More 菜单中的命令(兼容 runFakeCommand 伪命令) */
    internal fun runMenuCommand(q: String) {
        if (!runFakeCommand(q)) {
            stopCommand()
            // 重置缓存是独立命令, 不拼接保存输出命令(saveOutputCmd 按输出格式生成
            // magick/cp 命令, 无输出文件时产生 shell 语法错误如 "unexpected ':'")
            val needSave = q != CMD_RESET_CACHE
            run20(q, false, needSave)
        }
    }

    /**
     * 用 Android 文件 API 清空缓存目录(context.cacheDir, 按实际包名动态定位,
     * 如 /data/user/0/com.tumuyan.ncnn.realsr/cache/), 替代 shell 命令——
     * 之前 sh 执行 "rm -f *.cache" 报 "syntax error: unexpected ':'"。
     * 清空 cacheDir 下所有文件与子目录(探针 .probe.cache、外载模型副本、
     * 处理临时文件等), 目录本身保留。
     */
    private fun resetCacheByApi() {
        try {
            val root = cacheDir
            var count = 0
            if (root.exists()) {
                root.listFiles()?.forEach { f ->
                    if (f.deleteRecursively()) count++
                }
            }
            log = getString(R.string.menu_reset_cache) + ": 已清空 ${root.path} ($count 项)"
        } catch (e: Exception) {
            e.printStackTrace()
            log = "重置缓存失败: ${e.message}"
        }
    }

    /** 基准测试: mnnsr 为 JNI 调用(无独立可执行二进制), 不能用 shell 多命令; 逐条走 JNI 并串行执行 */
    internal fun runBenchmark() {
        stopCommand()
        startBenchmarkStep(0)
    }

    private fun startBenchmarkStep(index: Int) {
        if (index >= BENCH_MARK_COMMANDS.size || processingService == null || !isBound) return
        // 统一注入参数(后端/切块/加载优化/调优等)
        val cmd = buildMnnsrCommand(BENCH_MARK_COMMANDS[index])
        processingService?.startTask(cmd, dir, notify, object : ImageProcessor.ProcessCallback {
            override fun onProgress(line: String) {
                runOnUiThread { log = line }
            }
            override fun onCompleted(result: String, success: Boolean) {
                runOnUiThread { log = "benchmark 第 ${index + 1} 步: " + if (success) "完成" else "失败" }
                startBenchmarkStep(index + 1)
            }
            override fun onError(error: String) {
                runOnUiThread { log = "benchmark 错误: $error" }
            }
        })
    }

    internal fun extractModelName(cmd: String): String = CommandParams.extractModelName(cmd)

    /** 当前界面状态对应的 mnnsr 注入参数(后端/切块/加载优化/去马赛克/模型级调优) */
    internal fun mnnsrOptions() = CommandParams.MnnsrOptions(
        mnnBackend = mnnBackend,
        maxTileSize = maxTileSize,
        decensor = decensor,
        loadOpt = mnnsrLoadOpt,
        tuneModels = tuneModels,
    )

    /** 统一注入 mnnsr 公共参数(委托 CommandParams, 规则见该类文档) */
    private fun buildMnnsrCommand(cmd: String): String =
        CommandParams.buildMnnsrCommand(cmd, mnnsrOptions())

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val url = data.data
            if (requestCode == SELECT_IMAGE && url != null) {
                // 选择新图片时清理旧预览/处理状态, 避免上一张的结果残留
                shareEnabled = false
                showImagePreview = false
                imagePath = null
                previewFullscreen = false
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
                    // 选择新图片时清理旧预览/处理状态
                    shareEnabled = false
                    showImagePreview = false
                    imagePath = null
                    previewFullscreen = false
                    val imageUris = ArrayList<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        imageUris.add(clipData.getItemAt(i).uri)
                    }
                    handleSelectedImages(imageUris)
                }
            }
        }
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

    internal fun stopCommand() {
        if (isBound && processingService != null) {
            processingService?.cancelTask()
            busy = false
            progressText = ""
        }
    }

    // ==================== 命令执行 ====================

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
            // 点击运行时立即清除上一次处理完成后的输出预览状态,
            // 避免切换模型后预览仍停留在上一次处理结果(与选择图片时的清理逻辑一致)
            showImagePreview = false
            imagePath = null
            previewFullscreen = false
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
            // 确保服务为"已启动"状态: 应用销毁(解绑)后服务仍运行, 后台推理不中断
            try {
                startService(Intent(this, ProcessingService::class.java))
            } catch (e: Exception) {
                Log.w("run20", "startService failed: ${e.message}")
            }
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
                // 用 Android 文件 API 清理缓存, 替代 shell 命令:
                // 之前用 sh 执行 "rm -f *.cache" 等, 在拼接保存命令后报
                // "syntax error: unexpected ':'"; 文件 API 无 shell 解析问题。
                resetCacheByApi()
                showImage(null, getString(R.string.menu_reset_cache))
                true
            }
            else -> false
        }
    }

    companion object {
        internal const val SELECT_IMAGE = 1
        internal const val SELECT_MULTI_IMAGE = 2
        private const val MY_PERMISSIONS_REQUEST = 100
        private val BENCH_MARK_COMMANDS = arrayOf(
            "./mnnsr-ncnn -i img/PM5544.jpeg -o input.png  -m models-Real-ESRGAN/x4.mnn -P 10",
            "./mnnsr-ncnn -i input.png -o output.png  -m models-Real-ESRGANv3-anime/x4.mnn -s 4 -P 10",
        )
        internal const val CMD_RESET_CACHE =
            ";rm -f *.cache;rm -f */*.cache;chmod +x *; echo Cache has been reset.;ls"
        private const val NOTIFY_ID = 1
        private const val CHANNEL_ID_RESULT = "channel_result"
    }
}
