package com.tumuyan.ncnn.realsr

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.widthIn
import java.io.File

/** 设置页默认命令(初始值与"恢复默认"共用一份, 避免两处字符串漂移) */
private const val DEFAULT_COMMAND =
"./mnnsr-ncnn -i input.png -o output.png -m models-Real-ESRGANv3-anime/x4.mnn -s 2 -P 10"

/** mnnsr CLI -b 后端离散取值(下拉选择替代手填数字), 顺序按常用度 */
private val MNN_BACKEND_ITEMS = listOf(
    "OPENCL (3)", "VULKAN (7)", "AUTO (4)", "CPU (0)", "CUDA (2)", "NN (5)", "OPENGL (6)", "USER_0 (8)", "USER_1 (9)",
)
private val MNN_BACKEND_VALUES = listOf(3, 7, 4, 0, 2, 5, 6, 8, 9)

/** 设置页"隐藏程序"列表: (程序键, 标题资源), 数据驱动渲染避免逐项拷贝 */
private val HIDDEN_PROGRAM_ITEMS = listOf(
CommandListManager.PROGRAM_REALSR to R.string.hide_realsr,
CommandListManager.PROGRAM_SRMD to R.string.hide_srmd,
CommandListManager.PROGRAM_WAIFU2X to R.string.hide_waifu2x,
CommandListManager.PROGRAM_REALCUGAN to R.string.hide_realcugan,
CommandListManager.PROGRAM_MNNSR to R.string.hide_mnnsr,
CommandListManager.PROGRAM_RESIZE to R.string.hide_resize,
CommandListManager.PROGRAM_MAGICK to R.string.hide_magick,
CommandListManager.PROGRAM_ANIME4K to R.string.hide_anime4k,
)

@Composable
internal fun MainActivity.SettingsContent() {
    val activity = this
    val focusManager = LocalFocusManager.current
    val sp = getSharedPreferences("config", Activity.MODE_PRIVATE)

    // ---------- 读取已有配置 ----------
    var selectCommand by rememberSaveable { mutableIntStateOf(sp.getInt("selectCommand", 0)) }
    var tileSize by rememberSaveable { mutableStateOf(sp.getInt("tileSize", 0).toString()) }
    var maxTileSize by rememberSaveable { mutableStateOf(sp.getInt("maxTileSize", 256).let { ((it + 32) / 64) * 64 }.toString()) }
    var decensor by rememberSaveable { mutableStateOf(sp.getBoolean("decensor", false)) }
    var extraCommand by rememberSaveable { mutableStateOf(sp.getString("extraCommand", "") ?: "") }
    var defaultCommand by remember {
        mutableStateOf(sp.getString("defaultCommand", DEFAULT_COMMAND) ?: "")
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
    var extraPath by rememberSaveable { mutableStateOf(sp.getString("extraPath", "") ?: "") }
    var savePath by rememberSaveable { mutableStateOf(sp.getString("savePath", "") ?: "") }
    var mnnBackend by rememberSaveable { mutableStateOf(sp.getInt("mnnBackend", 3).toString()) }
    var mnnsrLoadOpt by rememberSaveable { mutableIntStateOf(sp.getInt("mnnsrLoadOpt", 0)) }
    var tuneModels by rememberSaveable { mutableStateOf(sp.getString("tuneModels", "") ?: "") }
    var showTunePage by rememberSaveable { mutableStateOf(false) }

    var keepScreen by rememberSaveable { mutableStateOf(sp.getBoolean("keepScreen", false)) }
    var useMultFiles by rememberSaveable { mutableStateOf(sp.getBoolean("useMultFiles", false)) }
    var prePng by rememberSaveable { mutableStateOf(sp.getBoolean("PrePng", true)) }
    var preFrame by rememberSaveable { mutableStateOf(sp.getBoolean("PreFrame", true)) }
    var autoSave by rememberSaveable { mutableStateOf(sp.getBoolean("autoSave", false)) }
    var useCPU by rememberSaveable { mutableStateOf(sp.getBoolean("useCPU", false)) }
    var showFinalCommand by rememberSaveable { mutableStateOf(sp.getBoolean("showFinalCommand", false)) }
    var useCustomLabel by rememberSaveable { mutableStateOf(sp.getBoolean("useCustomLabel", false)) }

    var format by rememberSaveable { mutableIntStateOf(sp.getInt("format", 0)) }
    var dirOutputFormat by rememberSaveable { mutableIntStateOf(sp.getInt("dirOutputFormat", 0)) }
    var name by rememberSaveable { mutableIntStateOf(sp.getInt("name", 0)) }
    var name2 by rememberSaveable { mutableIntStateOf(sp.getInt("name2", 0)) }
    var name3 by rememberSaveable { mutableIntStateOf(sp.getInt("name3", 0)) }
    var orientation by rememberSaveable { mutableIntStateOf(sp.getInt("ORIENTATION", 0)) }
    var notify by rememberSaveable { mutableIntStateOf(sp.getInt("notify", 0)) }

    // 隐藏的程序(SnapshotStateList: 增删即时触发重组), 勾选变化即时持久化
    val hiddenPrograms = remember {
        sp.getStringSet("hiddenPrograms", emptySet())?.toMutableStateList() ?: mutableStateListOf()
    }

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

    // 调优管理: 提取所有 mnnsr 模型供独立管理页选择。
    // 三元组 = (辨识显示名, 调优键=目录名, 模型相对路径)。调优键与命令注入的
    // extractModelName 返回值一致, 保证勾选后 -T 命令匹配生效。
    val mnnsrModels = remember(clm) {
        clm.commandList
            .filter { it.startsWith("./mnnsr") }
            .mapNotNull { cmd ->
                val m = Regex("-m\\s+(\\S+)").find(cmd)?.groupValues?.get(1)
                m?.let { path ->
                    val dir = path.substringBeforeLast('/')
                    val file = path.substringAfterLast('/').removeSuffix(".mnn")
                    val dirBase = if (dir.startsWith("models-")) dir.removePrefix("models-") else dir
                    val scaleTag = Regex("(x[0-9]|up[0-9])").find(file)?.groupValues?.get(1)
                    // 显示名: 通用目录(models-XXX)用 目录名/倍率; models-MNN 等用文件名主体
                    val dispName = if (dir == "models-MNN" || !dir.startsWith("models-")) file
                        else dirBase + (scaleTag?.let { "/$it" } ?: "")
                    // 调优键: 通用目录用目录名(同模型多倍率共用开关); models-MNN/自定义用文件名(精确避免误匹配)
                    val tuneKey = if (dir == "models-MNN" || !dir.startsWith("models-")) file else dirBase
                    Triple(dispName, tuneKey, path)
                }
            }
            .distinctBy { it.third }
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
        val topAppBarScrollBehavior = MiuixScrollBehavior()
        TopAppBar(
            title = getString(R.string.setting),
            scrollBehavior = topAppBarScrollBehavior,
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = WIDE_CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .weight(1f)
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
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
                title = getString(R.string.show_final_command),
                checked = showFinalCommand,
                onCheckedChange = {
                    showFinalCommand = it
                    sp.edit().putBoolean("showFinalCommand", it).apply()
                },
            )
        }

        SmallTitle(getString(R.string.mnn_settings))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // MNN 已改为 CLI 子进程调用(经 nsrun 绕开 linker namespace 加载 OpenCL),
            // 版本随 assets 内 mnnsr-ncnn/libMNN*.so 决定, 不再由 JNI 提供。
            Text(
                text = "MNN 推理: CLI 子进程(nsrun 包装, OpenCL 可用)",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            SwitchPreference(
                title = getString(R.string.decensor),
                checked = decensor,
                onCheckedChange = {
                    decensor = it
                    sp.edit().putBoolean("decensor", it).apply()
                    // 同步类级字段: 命令构建器读 this.decensor, 同前台会话内即时生效
                    activity.decensor = it
                },
            )
            OverlayDropdownPreference(
                items = MNN_BACKEND_ITEMS,
                selectedIndex = MNN_BACKEND_VALUES.indexOf(mnnBackend.toIntOrNull() ?: 3)
                    .let { if (it >= 0) it else MNN_BACKEND_VALUES.indexOf(3) },
                title = getString(R.string.mnn_backend),
                onSelectedIndexChange = { mnnBackend = MNN_BACKEND_VALUES[it].toString() },
            )
            // 调优管理入口: 点击打开独立调优管理页(列表选择模型 + 显示已调优状态)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTunePage = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(getString(R.string.mnn_tune_models))
                    Text(
                        text = if (tuneModels.isBlank()) "默认全部跳过调优(首跑快)" else "调优模型: $tuneModels",
                    )
                }
                Icon(MiuixIcons.More, contentDescription = null)
            }
            SwitchPreference(
                title = getString(R.string.mnn_load_opt),
                checked = mnnsrLoadOpt == 1,
                onCheckedChange = {
                    mnnsrLoadOpt = if (it) 1 else 0
                    sp.edit().putInt("mnnsrLoadOpt", mnnsrLoadOpt).apply()
                    // 同步类级字段: 命令构建器读 this.mnnsrLoadOpt, 同前台会话内即时生效
                    activity.mnnsrLoadOpt = mnnsrLoadOpt
                },
            )
            SliderPreference(
                title = getString(R.string.max_tile_size),
                summary = (maxTileSize.toIntOrNull() ?: 256).toString(),
                value = (maxTileSize.toIntOrNull() ?: 256).toFloat().coerceIn(128f, 512f),
                valueRange = 128f..512f,
                // 吸附到 64 的倍数(切块对齐敏感, 避免 257 这类非对齐值)
                onValueChange = { maxTileSize = (((it.toInt() + 32) / 64) * 64).coerceIn(128, 512).toString() },
                onValueChangeFinished = {
                    val v = (maxTileSize.toIntOrNull() ?: 256).coerceIn(128, 512)
                    sp.edit().putInt("maxTileSize", v).apply()
                    activity.maxTileSize = v
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
            HIDDEN_PROGRAM_ITEMS.forEach { (program, titleRes) ->
                CheckboxPreference(
                    title = getString(titleRes),
                    checked = program in hiddenPrograms,
                    onCheckedChange = { checked ->
                        if (checked) hiddenPrograms.add(program) else hiddenPrograms.remove(program)
                        sp.edit().putStringSet("hiddenPrograms", hiddenPrograms.toSet()).apply()
                    },
                )
            }
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
                    startActivity(Intent(activity, LabelEditorActivity::class.java))
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                if (saveSettings(
                        sp, SettingsSnapshot(
                            selectCommand, tileSize, decensor, defaultCommand, extraCommand,
                            classicalFilters, magickFilters, extraPath, savePath,
                            keepScreen, useMultFiles, prePng, preFrame, autoSave, useCPU,
                            showFinalCommand, useCustomLabel, format,
                            dirOutputFormat, name, name2, name3, orientation, notify,
                            mnnBackend, mnnsrLoadOpt,
                        )
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
                useCPU = false; autoSave = false
                showFinalCommand = false; useCustomLabel = false; decensor = false
                savePath = ""; tileSize = "0"
                maxTileSize = "256"
                extraPath = ""; mnnBackend = "3"
                mnnsrLoadOpt = 0
                activity.mnnsrLoadOpt = 0
                tuneModels = ""
                sp.edit().putString("tuneModels", "").apply()
                defaultCommand = DEFAULT_COMMAND
                classicalFilters = getString(R.string.default_classical_filters)
                magickFilters = getString(R.string.default_magick_filters)
                // 恢复默认: 同步持久化 maxTileSize(滑动框只写自身 onValueChangeFinished)
                sp.edit().putInt("maxTileSize", 256).apply()
                activity.maxTileSize = 256
                saveSettings(
                    sp, SettingsSnapshot(
                        selectCommand, tileSize, decensor, defaultCommand, extraCommand,
                        classicalFilters, magickFilters, extraPath, savePath,
                        keepScreen, useMultFiles, prePng, preFrame, autoSave, useCPU,
                        showFinalCommand, useCustomLabel, format,
                        dirOutputFormat, name, name2, name3, orientation, notify,
                        mnnBackend, mnnsrLoadOpt,
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 打开调优管理页时清理旧格式残留(如旧输入框时代存下的 "x4" 纯文件名, 会导致所有 x4 模型被误调优)
        LaunchedEffect(showTunePage) {
            if (showTunePage) {
                val validKeys = mnnsrModels.map { it.second }.toSet()
                val keys = tuneModels.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val cleaned = keys.filter { k -> validKeys.any { it.contains(k) } }.distinct()
                if (cleaned != keys) {
                    val newVal = cleaned.joinToString(",")
                    tuneModels = newVal
                    sp.edit().putString("tuneModels", newVal).apply()
                    activity.tuneModels = newVal
                }
            }
        }

        // 调优管理独立页(全屏 Dialog): 列表选择模型 + 显示已调优状态
        if (showTunePage) {
            TuneManageDialog(
                models = mnnsrModels,
                tuneModels = tuneModels,
                onToggle = { name ->
                    val keys = tuneModels.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                    if (name in keys) keys.remove(name) else keys.add(name)
                    val newVal = keys.joinToString(",")
                    tuneModels = newVal
                    sp.edit().putString("tuneModels", newVal).apply()
                    activity.tuneModels = newVal
                },
                onDismiss = { showTunePage = false },
            )
        }
    }
}

/** 调优管理独立页(全屏 Dialog): 列表选择要调优的模型 + 显示每个模型是否已调优 */
@Composable
private fun MainActivity.TuneManageDialog(
    models: kotlin.collections.List<Triple<String, String, String>>,
    tuneModels: String,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
        ) {
            SmallTopAppBar(
                title = getString(R.string.mnn_tune_models),
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(MiuixIcons.Back, contentDescription = null) }
                },
            )
            Text(
                text = "默认全部跳过调优(首跑快)。勾选 = 对该模型开启 WIDE 调优(首次运行较慢, 进度实时显示, 调优结果缓存后秒开)。\"已调优\" = 调优结果已缓存。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(models.size) { index ->
                    val (dispName, key, relPath) = models[index]
                    val tunedOn = tuneModels.split(',').any { it.isNotBlank() && key.contains(it.trim()) }
                    // 已调优状态 = CLI 实际完成过算子调优时写入的 <model>.mnn.tuned 标记。
                    // 不能看 .cache 文件: 调优/未调优运行时都会生成几何/权重缓存。
                    // 自定义模型(-m 绝对路径)的 .tuned 由 CLI 写在模型同目录, 直接按绝对路径查。
                    val tuned = try {
                        if (relPath.startsWith("/")) java.io.File("$relPath.tuned").exists()
                        else File(context.cacheDir, "realsr/$relPath.tuned").exists()
                    } catch (e: Exception) { false }
                    // 状态: 调优开关(勾选=对该模型附加 -T 开启调优; 未勾选=跳过调优) + 完成状态(已调优/未调优)
                    val stateText = when {
                        tunedOn && tuned -> "调优 · 已完成"
                        tunedOn -> "调优 · 待首次运行"
                        tuned -> "已完成(当前跳过)"
                        else -> "跳过调优"
                    }
                    SwitchPreference(
                        title = "$dispName · $stateText",
                        checked = tunedOn,
                        onCheckedChange = { onToggle(key) },
                    )
                }
            }
        }
    }
}

/** 设置页全部可保存项的快照: 以对象一次性传递, 替代超长位置参数列表 */
internal data class SettingsSnapshot(
    val selectCommand: Int,
    val tileSize: String,
    val decensor: Boolean,
    val defaultCommand: String,
    val extraCommand: String,
    val classicalFilters: String,
    val magickFilters: String,
    val extraPath: String,
    val savePath: String,
    val keepScreen: Boolean,
    val useMultFiles: Boolean,
    val prePng: Boolean,
    val preFrame: Boolean,
    val autoSave: Boolean,
    val useCPU: Boolean,
    val showFinalCommand: Boolean,
    val useCustomLabel: Boolean,
    val format: Int,
    val dirOutputFormat: Int,
    val name: Int,
    val name2: Int,
    val name3: Int,
    val orientation: Int,
    val notify: Int,
    val mnnBackend: String,
    val mnnsrLoadOpt: Int,
)

/** 保存设置(隐藏程序列表在勾选变化时即时持久化, 不在此重复写) */
private fun MainActivity.saveSettings(sp: SharedPreferences, s: SettingsSnapshot): Boolean {
    val editor = sp.edit()
    editor.putInt("selectCommand", s.selectCommand)

    val tileSizeV = s.tileSize.ifEmpty { "0" }
    editor.putInt("tileSize", tileSizeV.toIntOrNull() ?: 0)
    editor.putBoolean("decensor", s.decensor)
    editor.putString("defaultCommand", s.defaultCommand)

    val extraCommandV = s.extraCommand.trim().replace(Regex("\\s*\n\\s*"), "\n")
    editor.putString("extraCommand", extraCommandV)

    val classicalFiltersV = s.classicalFilters.trim().replace(Regex("\\s+"), " ")
    editor.putString("classicalFilters", classicalFiltersV)

    // 保存前清洗无效滤镜(如设备上残留的 anczos),避免生成非法 magick 命令
    val magickFiltersV = CommandListManager.sanitizeMagickFilters(
        s.magickFilters.trim().split(Regex("\\s+")).toTypedArray()
    ).joinToString(" ")
    editor.putString("magickFilters", magickFiltersV)

    val extraPathV = s.extraPath.trim()
    if (folderHasErr(extraPathV)) return false
    editor.putString("extraPath", extraPathV)

    val savePathV = s.savePath.trim()
    if (folderHasErr(savePathV)) return false
    editor.putString("savePath", savePathV)

    editor.putBoolean("keepScreen", s.keepScreen)
    editor.putBoolean("useMultFiles", s.useMultFiles)
    editor.putBoolean("PrePng", s.prePng)
    editor.putBoolean("PreFrame", s.preFrame)
    editor.putBoolean("autoSave", s.autoSave)
    editor.putBoolean("useCPU", s.useCPU)
    editor.putBoolean("showFinalCommand", s.showFinalCommand)
    editor.putBoolean("useCustomLabel", s.useCustomLabel)

    editor.putInt("format", s.format)
    editor.putInt("dirOutputFormat", s.dirOutputFormat)
    editor.putInt("name", s.name)
    editor.putInt("name2", s.name2)
    editor.putInt("name3", s.name3)
    editor.putInt("ORIENTATION", s.orientation)
    editor.putInt("notify", s.notify)
    editor.putInt("mnnBackend", s.mnnBackend.toIntOrNull() ?: 3)
    editor.putInt("mnnsrLoadOpt", s.mnnsrLoadOpt)
    editor.apply()
    return true
}

private fun MainActivity.folderHasErr(path: String): Boolean {
    if (path.isEmpty()) return false
    val file = File(path)
    if (!file.exists() || !file.isDirectory) {
        showSnackbar(getString(R.string.path_not_dir))
        return true
    }
    return false
}
