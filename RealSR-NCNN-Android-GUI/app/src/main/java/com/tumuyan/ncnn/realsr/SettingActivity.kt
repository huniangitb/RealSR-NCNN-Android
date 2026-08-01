package com.tumuyan.ncnn.realsr

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.io.File
import java.util.HashSet
import kotlinx.coroutines.launch

/** 设置界面: 采用 Miuix 设计语言重写, 保留原有全部配置项与 SharedPreferences 键。 */
class SettingActivity : ComponentActivity() {

    private lateinit var mySharePerferences: SharedPreferences
    private val snackbarHostState = SnackbarHostState()
    private val galleryPath = android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DCIM
    ).toString() + File.separator + "RealSR"

    /** Miuix Snackbar 提示(替代 Toast) */
    private fun showSnackbar(text: String) {
        lifecycleScope.launch { snackbarHostState.showSnackbar(text) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mySharePerferences = getSharedPreferences("config", Activity.MODE_PRIVATE)
        setContent {
            AppTheme {
                SettingsScreen()
            }
        }
    }

    @Composable
    fun SettingsScreen() {
        val focusManager = LocalFocusManager.current

        // ---------- 读取已有配置 ----------
        var selectCommand by remember { mutableIntStateOf(mySharePerferences.getInt("selectCommand", 0)) }
        var tileSize by remember { mutableStateOf(mySharePerferences.getInt("tileSize", 0).toString()) }
        var extraCommand by remember { mutableStateOf(mySharePerferences.getString("extraCommand", "") ?: "") }
        var defaultCommand by remember {
            mutableStateOf(
                mySharePerferences.getString(
                    "defaultCommand",
                    "./realsr-ncnn -i input.png -o output.png -m models-Real-ESRGANv3-anime -s 2",
                ) ?: ""
            )
        }
        var classicalFilters by remember {
            mutableStateOf(mySharePerferences.getString("classicalFilters", getString(R.string.default_classical_filters)) ?: "")
        }
        var magickFilters by remember {
            mutableStateOf(mySharePerferences.getString("magickFilters", getString(R.string.default_magick_filters)) ?: "")
        }
        var extraPath by remember { mutableStateOf(mySharePerferences.getString("extraPath", "") ?: "") }
        var savePath by remember { mutableStateOf(mySharePerferences.getString("savePath", "") ?: "") }
        var threadCount by remember { mutableStateOf(mySharePerferences.getString("threadCount", "") ?: "") }
        var mnnBackend by remember { mutableStateOf(mySharePerferences.getInt("mnnBackend", 7).toString()) }

        var keepScreen by remember { mutableStateOf(mySharePerferences.getBoolean("keepScreen", false)) }
        var useMultFiles by remember { mutableStateOf(mySharePerferences.getBoolean("useMultFiles", false)) }
        var prePng by remember { mutableStateOf(mySharePerferences.getBoolean("PrePng", true)) }
        var preFrame by remember { mutableStateOf(mySharePerferences.getBoolean("PreFrame", true)) }
        var autoSave by remember { mutableStateOf(mySharePerferences.getBoolean("autoSave", false)) }
        var useCPU by remember { mutableStateOf(mySharePerferences.getBoolean("useCPU", false)) }
        var showSearchView by remember { mutableStateOf(mySharePerferences.getBoolean("showSearchView", false)) }
        var showFinalCommand by remember { mutableStateOf(mySharePerferences.getBoolean("showFinalCommand", false)) }
        var useCustomLabel by remember { mutableStateOf(mySharePerferences.getBoolean("useCustomLabel", false)) }

        var format by remember { mutableIntStateOf(mySharePerferences.getInt("format", 0)) }
        var dirOutputFormat by remember { mutableIntStateOf(mySharePerferences.getInt("dirOutputFormat", 0)) }
        var name by remember { mutableIntStateOf(mySharePerferences.getInt("name", 0)) }
        var name2 by remember { mutableIntStateOf(mySharePerferences.getInt("name2", 0)) }
        var name3 by remember { mutableIntStateOf(mySharePerferences.getInt("name3", 0)) }
        var orientation by remember { mutableIntStateOf(mySharePerferences.getInt("ORIENTATION", 0)) }
        var notify by remember { mutableIntStateOf(mySharePerferences.getInt("notify", 0)) }

        val hiddenPrograms = remember {
            mySharePerferences.getStringSet("hiddenPrograms", HashSet()).orEmpty().toMutableSet()
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
            clm.loadCustomLabels(mySharePerferences.getString("customLabels", ""))
            clm.getDisplayLabels(useCustomLabel).toList()
        }

        val formatOptions = resources.getStringArray(R.array.format).toList()
        val dirFormatOptions = resources.getStringArray(R.array.dir_output_format).toList()
        val nameOptions = resources.getStringArray(R.array.name).toList()
        val name2Options = resources.getStringArray(R.array.name2).toList()
        val name3Options = resources.getStringArray(R.array.name3).toList()
        val orientationOptions = resources.getStringArray(R.array.oriental_item).toList()
        val notifyOptions = resources.getStringArray(R.array.notify_item).toList()

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = getString(R.string.setting),
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
                        onSelectedIndexChange = { selectCommand = it },
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
                        onCheckedChange = { keepScreen = it },
                    )
                    SwitchPreference(
                        title = getString(R.string.choose_mult_files),
                        checked = useMultFiles,
                        onCheckedChange = { useMultFiles = it },
                    )
                    SwitchPreference(
                        title = getString(R.string.preprocess_to_png),
                        checked = prePng,
                        onCheckedChange = { prePng = it },
                    )
                    SwitchPreference(
                        title = getString(R.string.preprocess_frames),
                        checked = preFrame,
                        onCheckedChange = { preFrame = it },
                    )
                    SwitchPreference(
                        title = getString(R.string.use_cpu),
                        checked = useCPU,
                        onCheckedChange = { useCPU = it },
                    )
                    SwitchPreference(
                        title = getString(R.string.auto_save),
                        checked = autoSave,
                        onCheckedChange = { autoSave = it },
                    )
                    SwitchPreference(
                        title = getString(R.string.show_serarch_view),
                        checked = showSearchView,
                        onCheckedChange = { showSearchView = it },
                    )
                    SwitchPreference(
                        title = getString(R.string.show_final_command),
                        checked = showFinalCommand,
                        onCheckedChange = { showFinalCommand = it },
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
                        onSelectedIndexChange = { format = it },
                    )
                    OverlayDropdownPreference(
                        items = dirFormatOptions,
                        selectedIndex = dirOutputFormat,
                        title = getString(R.string.dir_output_format_title),
                        onSelectedIndexChange = { dirOutputFormat = it },
                    )
                    OverlayDropdownPreference(
                        items = nameOptions,
                        selectedIndex = name,
                        title = getString(R.string.save_name),
                        onSelectedIndexChange = { name = it },
                    )
                    OverlayDropdownPreference(
                        items = name2Options,
                        selectedIndex = name2,
                        title = getString(R.string.save_name2),
                        onSelectedIndexChange = { name2 = it },
                    )
                    OverlayDropdownPreference(
                        items = name3Options,
                        selectedIndex = name3,
                        title = getString(R.string.save_name3),
                        onSelectedIndexChange = { name3 = it },
                    )
                    OverlayDropdownPreference(
                        items = orientationOptions,
                        selectedIndex = orientation,
                        title = getString(R.string.orientation),
                        onSelectedIndexChange = { orientation = it },
                    )
                    OverlayDropdownPreference(
                        items = notifyOptions,
                        selectedIndex = notify,
                        title = getString(R.string.notify),
                        onSelectedIndexChange = { notify = it },
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
                        onCheckedChange = { hideRealsr = it },
                    )
                    CheckboxPreference(
                        title = getString(R.string.hide_srmd),
                        checked = hideSrmd,
                        onCheckedChange = { hideSrmd = it },
                    )
                    CheckboxPreference(
                        title = getString(R.string.hide_waifu2x),
                        checked = hideWaifu2x,
                        onCheckedChange = { hideWaifu2x = it },
                    )
                    CheckboxPreference(
                        title = getString(R.string.hide_realcugan),
                        checked = hideRealcugan,
                        onCheckedChange = { hideRealcugan = it },
                    )
                    CheckboxPreference(
                        title = getString(R.string.hide_mnnsr),
                        checked = hideMnnsr,
                        onCheckedChange = { hideMnnsr = it },
                    )
                    CheckboxPreference(
                        title = getString(R.string.hide_resize),
                        checked = hideResize,
                        onCheckedChange = { hideResize = it },
                    )
                    CheckboxPreference(
                        title = getString(R.string.hide_magick),
                        checked = hideMagick,
                        onCheckedChange = { hideMagick = it },
                    )
                    CheckboxPreference(
                        title = getString(R.string.hide_anime4k),
                        checked = hideAnime4k,
                        onCheckedChange = { hideAnime4k = it },
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
                        onCheckedChange = { useCustomLabel = it },
                    )
                    ArrowPreference(
                        title = getString(R.string.label_editor_title),
                        onClick = {
                            startActivity(Intent(this@SettingActivity, LabelEditorActivity::class.java))
                        },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (save(
                                selectCommand, tileSize, defaultCommand, extraCommand,
                                classicalFilters, magickFilters, threadCount, extraPath, savePath,
                                keepScreen, useMultFiles, prePng, preFrame, autoSave, useCPU,
                                showSearchView, showFinalCommand, useCustomLabel, format,
                                dirOutputFormat, name, name2, name3, orientation, notify, mnnBackend,
                                hideRealsr, hideSrmd, hideWaifu2x, hideRealcugan, hideMnnsr,
                                hideResize, hideMagick, hideAnime4k,
                            )
                        ) {
                            showSnackbar(getString(R.string.save_succeed))
                            finish()
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
                        save(
                            selectCommand, tileSize, defaultCommand, extraCommand,
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

    private fun save(
        selectCommand: Int, tileSize: String, defaultCommand: String, extraCommand: String,
        classicalFilters: String, magickFilters: String, threadCount: String, extraPath: String,
        savePath: String, keepScreen: Boolean, useMultFiles: Boolean, prePng: Boolean,
        preFrame: Boolean, autoSave: Boolean, useCPU: Boolean, showSearchView: Boolean,
        showFinalCommand: Boolean, useCustomLabel: Boolean, format: Int, dirOutputFormat: Int,
        name: Int, name2: Int, name3: Int, orientation: Int, notify: Int, mnnBackend: String,
        hideRealsr: Boolean, hideSrmd: Boolean, hideWaifu2x: Boolean, hideRealcugan: Boolean,
        hideMnnsr: Boolean, hideResize: Boolean, hideMagick: Boolean, hideAnime4k: Boolean,
    ): Boolean {
        val editor = mySharePerferences.edit()
        editor.putInt("selectCommand", selectCommand)

        val tileSizeV = tileSize.ifEmpty { "0" }
        editor.putInt("tileSize", tileSizeV.toIntOrNull() ?: 0)
        editor.putString("defaultCommand", defaultCommand)

        val extraCommandV = extraCommand.trim().replace(Regex("\\s*\n\\s*"), "\n")
        editor.putString("extraCommand", extraCommandV)

        val classicalFiltersV = classicalFilters.trim().replace(Regex("\\s+"), " ")
        editor.putString("classicalFilters", classicalFiltersV)

        val magickFiltersV = magickFilters.trim().replace(Regex("\\s+"), " ")
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
}
