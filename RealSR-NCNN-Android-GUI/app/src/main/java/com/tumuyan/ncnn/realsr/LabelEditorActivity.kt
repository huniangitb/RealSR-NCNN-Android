package com.tumuyan.ncnn.realsr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.tumuyan.ncnn.realsr.ui.AppTheme
import kotlinx.coroutines.launch
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

/** 标签编辑器: 为每条命令设置自定义显示名称, 支持复制/粘贴/清空/保存。 */
class LabelEditorActivity : ComponentActivity() {

    private lateinit var sp: SharedPreferences
    private val snackbarHostState = SnackbarHostState()

    /** Miuix Snackbar 提示(替代 Toast) */
    private fun showSnackbar(text: String) {
        lifecycleScope.launch { snackbarHostState.showSnackbar(text) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sp = getSharedPreferences("config", Activity.MODE_PRIVATE)
        setContent {
            AppTheme {
                LabelEditorScreen()
            }
        }
    }

    @Composable
    fun LabelEditorScreen() {
        val focusManager = LocalFocusManager.current
        val clm = remember {
            val extraPath = (sp.getString("extraPath", "") ?: "").trim()
            val extraCommand = (sp.getString("extraCommand", "") ?: "").trim()
            val classicalFilters = sp.getString("classicalFilters", getString(R.string.default_classical_filters)) ?: ""
            val magickFilters = sp.getString("magickFilters", getString(R.string.default_magick_filters)) ?: ""
            val presetLabels = resources.getStringArray(R.array.style_array)
            CommandListManager(
                presetLabels,
                extraPath,
                extraCommand,
                classicalFilters.split(Regex("\\s+")).toTypedArray(),
                magickFilters.split(Regex("\\s+")).toTypedArray(),
            ).also { it.loadCustomLabels(sp.getString("customLabels", "")) }
        }

        val items = remember(clm) {
            val customMap = clm.getCustomLabelMap()
            (0 until clm.getCommandCount()).map { i ->
                val cmd = clm.getCommandAt(i)
                LabelItem(
                    cmd,
                    CommandListManager.commandFingerprint(cmd),
                    clm.defaultLabels[i],
                    customMap[CommandListManager.commandFingerprint(cmd)] ?: "",
                )
            }
        }
        var edits by remember { mutableStateOf(items.associate { it.fingerprint to it.customLabel }) }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = getString(R.string.label_editor_title),
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
                    .padding(padding),
            ) {
                // 顶部操作: 复制全部 / 粘贴全部 / 清除全部
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                ) {
                    item {
                        SmallTitle(getString(R.string.label_editor_desc))
                    }
                    items(items) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = item.command,
                                style = TextStyle(
                                    fontSize = 12.sp,
                                ),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            TextField(
                                value = edits[item.fingerprint] ?: "",
                                onValueChange = { edits = edits + (item.fingerprint to it) },
                                label = getString(R.string.label_custom_hint),
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // 底部操作按钮
                RowButtons(
                    onCopyAll = {
                        clm.setCustomLabelMap(edits.filterValues { it.isNotEmpty() })
                        val text = clm.exportAllText()
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("label_config", text))
                        showSnackbar(getString(R.string.label_copied))
                    },
                    onPasteAll = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.getItemCount() > 0) {
                            val text = clip.getItemAt(0).text?.toString()
                            if (text != null) {
                                clm.setCustomLabelMap(edits.filterValues { it.isNotEmpty() })
                                val count = clm.importFromText(text)
                                val newMap = clm.getCustomLabelMap()
                                edits = items.associate { it.fingerprint to (newMap[it.fingerprint] ?: "") }
                                showSnackbar(getString(R.string.label_imported, count))
                            } else {
                                showSnackbar(getString(R.string.label_clipboard_empty))
                            }
                        } else {
                            showSnackbar(getString(R.string.label_clipboard_empty))
                        }
                    },
                    onClearAll = {
                        edits = items.associate { it.fingerprint to "" }
                        showSnackbar(getString(R.string.label_cleared))
                    },
                    onSave = {
                        clm.setCustomLabelMap(edits.filterValues { it.isNotEmpty() })
                        sp.edit().putString("customLabels", clm.toCustomLabelJson()).apply()
                        showSnackbar(getString(R.string.save_succeed))
                        finish()
                    },
                )
            }
        }
    }

    @Composable
    fun RowButtons(
        onCopyAll: () -> Unit,
        onPasteAll: () -> Unit,
        onClearAll: () -> Unit,
        onSave: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Button(
                onClick = onCopyAll,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(getString(R.string.label_copy_all)) }
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onPasteAll,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(getString(R.string.label_paste_all)) }
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onClearAll,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(getString(R.string.label_clear_all)) }
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(getString(R.string.save)) }
        }
    }

    private val Int.dp: androidx.compose.ui.unit.Dp
        get() = androidx.compose.ui.unit.Dp(this.toFloat())
    private val Float.sp: androidx.compose.ui.unit.TextUnit
        get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
}