package com.tumuyan.ncnn.realsr

import android.content.res.AssetManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.menu.OverlayDropdownMenu
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.HashSet

@Composable
internal fun MainActivity.HomeContent() {
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
                                runMenuCommand(MainActivity.CMD_RESET_CACHE)
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
                    key(imagePath ?: "", inputFileName) {
                        AndroidView(
                            // 切换图片时强制重建预览 view: 所有图片复制为 input.png,
                            // imagePath 路径不变, SubsamplingScaleImageView 对相同 URI 的
                            // setImage 不刷新(预览停留上一张); key 随文件名变化触发重建。
                            // 处理期间 imagePath/inputFileName 不变, view 不重建, 缩放保持。
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
                    }
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

/**
 * 扫描模型目录下的模型文件名, 智能提取该模型支持的放大倍率列表。
 * 命名规则(见 assets/realsr/models-* 目录):
 *  - models-pro/se/nose: up2x-conservative.bin 等, 倍率在前缀 up<N>x
 *  - Real-ESRGAN 系列: x2.bin / x3.bin / x4.bin, 倍率为 x<N>
 *  - MNN 模型: ESRGAN-MoeSR-jp_Illustration-x4.mnn, 倍率内嵌文件名
 * 优先匹配 up<N>x, 其次 x<N>, 结果按数字升序去重; 扫描失败返回空列表
 * (GUI 回退到标签锚点解析的倍率)。
 */
internal fun scanModelScales(assets: AssetManager?, modelPaths: Collection<String>): List<String> {
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
internal fun parseLabelDims(label: String, cmd: String = ""): Triple<String, String, String> {
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
