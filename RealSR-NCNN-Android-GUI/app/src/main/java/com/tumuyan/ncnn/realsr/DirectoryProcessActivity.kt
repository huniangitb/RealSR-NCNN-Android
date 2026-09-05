package com.tumuyan.ncnn.realsr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.tumuyan.ncnn.realsr.ui.AppTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale
import kotlinx.coroutines.launch

/** 目录批量处理: 选择输入/输出目录并批量执行命令, Miuix 风格界面。 */
class DirectoryProcessActivity : ComponentActivity() {

    private var dir = ""
    private var tileSize = 0
    private var useCPU = false
    private var mnnBackend = 7
    private var mnnsrLoadOpt = 0
    private var tuneModels = ""
    private var notifySetting = 2
    private var keepScreen = false
    private var savePath = ""
    private var dirNameFormat = 0
    private var dirOutputFormat = 0

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
        dir = cacheDir.absolutePath + "/realsr"

        val sp = getSharedPreferences("config", MODE_PRIVATE)
        tileSize = sp.getInt("tileSize", 0)
        useCPU = sp.getBoolean("useCPU", false)
        mnnBackend = sp.getInt("mnnBackend", 7)
        mnnsrLoadOpt = sp.getInt("mnnsrLoadOpt", 0)
        tuneModels = sp.getString("tuneModels", "") ?: ""
        notifySetting = sp.getInt("notify", 2)
        keepScreen = sp.getBoolean("keepScreen", false)
        dirNameFormat = sp.getInt("name3", 0)
        dirOutputFormat = sp.getInt("dirOutputFormat", 0)

        val galleryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            .toString() + File.separator + "RealSR"
        savePath = sp.getString("savePath", "") ?: ""
        if (savePath.isEmpty()) savePath = galleryPath

        bindService(Intent(this, ProcessingService::class.java), connection, Context.BIND_AUTO_CREATE)

        setContent {
            AppTheme {
                DirProcessScreen(sp)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private val inputDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                takePersistableUriPermission(uri)
            }
        }

    private val outputDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                takePersistableUriPermission(uri)
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

    @Composable
    fun DirProcessScreen(sp: SharedPreferences) {
        var inputPath by remember { mutableStateOf("") }
        var outputPath by remember { mutableStateOf("") }
        var autoOutput by remember { mutableStateOf(false) }
        var selectedModel by remember { mutableIntStateOf(0) }
        var logText by remember { mutableStateOf(getString(R.string.dir_log_hint)) }
        var isProcessing by remember { mutableStateOf(false) }
        var progressLog by remember { mutableStateOf(ProgressLogHelper()) }

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
        val commandList = remember(clm) {
            clm.getDirectorySupportedCommands(hiddenPrograms)
        }
        val displayLabels = remember(clm) {
            clm.getDirectorySupportedLabels(
                sp.getBoolean("useCustomLabel", false), hiddenPrograms
            )
        }

        val name3Options = resources.getStringArray(R.array.name3).toList()

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = getString(R.string.dir_process_title),
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(MiuixIcons.Back, "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 输入目录
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
                            if (autoOutput) updateAutoOutputPath(it, selectedModel, commandList, name3Options) {
                                outputPath = it
                            }
                        },
                        label = getString(R.string.dir_input_hint),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Button(
                        onClick = {
                            inputDirLauncher.launch(null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(getString(R.string.dir_select_btn))
                    }
                }

                // 输出目录
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
                        onClick = {
                            outputDirLauncher.launch(null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(getString(R.string.dir_select_btn))
                    }
                    CheckboxPreference(
                        title = getString(R.string.dir_auto_output_format, name3Options.getOrElse(dirNameFormat) { "" }),
                        checked = autoOutput,
                        onCheckedChange = {
                            autoOutput = it
                            if (it && inputPath.isNotEmpty()) {
                                updateAutoOutputPath(inputPath, selectedModel, commandList, name3Options) { outputPath = it }
                            }
                        },
                    )
                }

                // 模型选择
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

                // 日志
                SmallTitle(getString(R.string.menu_out))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = logText,
                        style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.button,
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
                            onLog = { text, log -> logText = text; progressLog = log },
                            onProcessingChange = { isProcessing = it },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    enabled = !isProcessing && inputPath.isNotEmpty() && outputPath.isNotEmpty(),
                ) { Text(getString(R.string.dir_start_btn)) }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    text = getString(R.string.dir_stop_btn),
                    onClick = {
                        if (isProcessing && processingService != null) {
                            processingService?.cancelTask()
                            logText += "\n--- Process stopped by user ---"
                            isProcessing = false
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

    private fun updateAutoOutputPath(
        inputPath: String,
        modelIndex: Int,
        commandList: Array<String>,
        name3Options: List<String>,
        onResult: (String) -> Unit,
    ) {
        var dirName = File(inputPath).name.ifEmpty { "output" }
        val commandName = if (modelIndex in commandList.indices) {
            extractModelName(commandList[modelIndex])
        } else ""
        val timeStr = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        when (dirNameFormat) {
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
        name3Options: List<String>,
        onLog: (String, ProgressLogHelper) -> Unit,
        onProcessingChange: (Boolean) -> Unit,
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
            if (useCPU && !baseCommand.startsWith("./srmd") && !baseCommand.startsWith("./mnnsr")
                && !baseCommand.contains(" -g ")
            )
                cmdBuilder.append(" -g -1")
            if (baseCommand.startsWith("./mnnsr") && !baseCommand.contains(" -b ")) {
                cmdBuilder.append(" -b ").append(mnnBackend)
            }
            if (baseCommand.startsWith("./mnnsr") && !baseCommand.contains(" -l "))
                cmdBuilder.append(" -l ").append(mnnsrLoadOpt)
            // 模型级 GPU 调优: tuneModels 匹配当前模型(目录名或文件名)时附加 -T(开启 WIDE 调优)。
            // 与 MainActivity.buildMnnsrCommand 的匹配规则一致: 目录名(models-XXX)或模型文件名均可命中,
            // 与调优管理页生成的调优键(dirBase/文件名)对齐。
            if (baseCommand.startsWith("./mnnsr") && !baseCommand.contains(" -T") && tuneModels.isNotBlank()) {
                val mPath = Regex(".+\\s-m\\s+(\\S+).*").find(baseCommand)?.groupValues?.get(1) ?: ""
                val mFile = mPath.substringAfterLast('/')
                val dirBase = mPath.substringBeforeLast('/').substringAfterLast('/')
                    .removePrefix("models-").substringAfterLast('/')
                val tuneKeys = tuneModels.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (tuneKeys.any { dirBase.contains(it) || mFile.contains(it) })
                    cmdBuilder.append(" -T")
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

        onProcessingChange(true)
        processingService?.startTask(execCmd, dir, notifySetting, object : ImageProcessor.ProcessCallback {
            override fun onProgress(line: String) {
                runOnUiThread {
                    newLog.appendLine(line)
                    onLog(newLog.getDisplayText(), newLog)
                }
            }

            override fun onCompleted(result: String, success: Boolean) {
                runOnUiThread {
                    val modelName = extractModelName(finalCmd)
                    val isNcnn = finalCmd.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.*".toRegex())
                    newLog.appendLine(newLog.getCompletionSummary(success, modelName, isNcnn))
                    onLog(newLog.getDisplayText(), newLog)
                    if (success) {
                        showSnackbar(getString(R.string.save_succeed) + "\n" + outputPath)
                    }
                    onProcessingChange(false)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    newLog.appendLine("Error: $error")
                    onLog(newLog.getDisplayText(), newLog)
                    onProcessingChange(false)
                }
            }
        })
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
}
