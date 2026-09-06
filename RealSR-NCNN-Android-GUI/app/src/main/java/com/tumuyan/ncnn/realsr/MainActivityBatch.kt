package com.tumuyan.ncnn.realsr

import android.app.Activity
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Date
import java.util.HashSet
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun MainActivity.DirProcessContent() {
    val sp = getSharedPreferences("config", Activity.MODE_PRIVATE)
    var inputPath by rememberSaveable { mutableStateOf("") }
    var outputPath by rememberSaveable { mutableStateOf("") }
    var autoOutput by rememberSaveable { mutableStateOf(false) }
    var selectedModel by rememberSaveable { mutableIntStateOf(0) }
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
                    if (autoOutput) updateAutoOutputPath(it, selectedModel, commandList) { outputPath = it }
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
                        updateAutoOutputPath(inputPath, selectedModel, commandList) { outputPath = it }
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
                        updateAutoOutputPath(inputPath, it, commandList) { outputPath = it }
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
                    inputPath, outputPath, selectedModel, commandList, progressLog,
                ) { text, log -> logText = text; progressLog = log }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            enabled = !busy && inputPath.isNotEmpty() && outputPath.isNotEmpty(),
        ) { Text(getString(R.string.dir_start_btn)) }

        if (busy) {
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
        }
        Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun MainActivity.takePersistableUriPermission(uri: Uri) {
    runCatching {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

private fun MainActivity.updateAutoOutputPath(
    inputPath: String,
    modelIndex: Int,
    commandList: Array<String>,
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

private fun MainActivity.startBatch(
    inputPath: String,
    outputPath: String,
    modelIndex: Int,
    commandList: Array<String>,
    progressLog: ProgressLogHelper,
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
    // 统一参数注入(mnnsr 后端/切块/调优, ncnn -t/-g, Anime4k -p), 规则集中 in CommandParams
    var finalCmd = CommandParams.injectParams(baseCommand, tileSize, useCPU, mnnsrOptions())
    if (finalCmd.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.+".toRegex())) {
        val dirFormats = resources.getStringArray(R.array.dir_output_format)
        if (dirOutputFormat > 0 && dirOutputFormat < dirFormats.size && !baseCommand.contains(" -f ")) {
            finalCmd += " -f " + dirFormats[dirOutputFormat]
        }
    }
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

    // 确保服务为"已启动"状态: 应用销毁(解绑)后服务仍运行, 后台推理不中断
    try {
        startService(Intent(this, ProcessingService::class.java))
    } catch (e: Exception) {
        Log.w("run20", "startService failed: ${e.message}")
    }
    processingService?.startTask(execCmd, dir, notify, object : ImageProcessor.ProcessCallback {
        override fun onProgress(line: String) {
            runOnUiThread {
                // 进度行(如 "PROGRESS:3/10|256x256")转为标题栏百分比显示, 不追加日志刷屏
                val m = PROGRESS_REGEX.find(line)
                if (m != null) {
                    val cur = m.groupValues[1].toIntOrNull() ?: 0
                    val total = m.groupValues[2].toIntOrNull() ?: 0
                    val tileW = m.groupValues[3].toIntOrNull()
                    val tileH = m.groupValues[4].toIntOrNull()
                    progressText = buildString {
                        if (total > 0 && cur >= 0 && cur <= total)
                            append("${cur * 100 / total}%")
                        else append(line)
                        if (tileW != null && tileH != null && tileW > 0 && tileH > 0)
                            append("  ${tileW}x${tileH}")
                    }
                    return@runOnUiThread
                }
                // CLI 原生百分比行(如 " 45.23%\t[ 12.34s / 20.00 ETA]")同样更新标题栏进度,
                // 仅不进日志(覆盖式, 避免每个 tile 刷一行)
                if (ProgressLogHelper.isProgressLine(line)) {
                    progressText = ProgressLogHelper.getProgressTextFor(line)
                    return@runOnUiThread
                }
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

/** JNI 进度机器格式正则: "PROGRESS:3/10" 或 "PROGRESS:3/10|256x256" (常量避免每行进度重复编译) */
private val PROGRESS_REGEX = Regex("PROGRESS[:：]?\\s*(\\d+)\\s*/\\s*(\\d+)(?:\\|(\\d+)x(\\d+))?")
