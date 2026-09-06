package com.tumuyan.ncnn.realsr

/**
 * 命令参数注入的统一入口, 单图运行(MainActivity)与基准测试共用同一套规则,
 * 避免注入逻辑分散在多处导致行为不一致(历史上曾因拷贝分叉出现 -p/-P 混用)。
 */
object CommandParams {

    /** mnnsr 参数注入所需的 GUI 设置快照(调用方从 SharedPreferences/界面状态填充) */
    data class MnnsrOptions(
        val mnnBackend: Int = 3,
        val maxTileSize: Int = 256,
        val decensor: Boolean = false,
        val loadOpt: Int = 0,
        /** 开启 GPU 调优的模型名(逗号分隔子串, 匹配到即对该模型追加 -T; 空 = 全部跳过调优) */
        val tuneModels: String = "",
    )

    /** 从命令中提取人类可读的模型名(用于日志/完成摘要显示) */
    fun extractModelName(cmd: String): String {
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

    /**
     * 统一为 mnnsr 命令注入公共参数(与 CLI/JNI 解析对齐):
     *  -b 后端、-t 最大切块、-l 切块加载优化、-d 去马赛克、-T 模型级 GPU 调优。
     * 已存在的参数不重复注入。所有 mnnsr 调用点统一走此函数。
     * -T 语义(与 CLI 一致): -T = 开启 WIDE 调优; 默认(不带 -T) = 跳过调优首跑快。
     * tuneModels 为空 = 全部跳过调优; 非空 = 仅勾选(匹配)的模型附加 -T 开启调优。
     */
    fun buildMnnsrCommand(cmd: String, o: MnnsrOptions): String {
        val b = StringBuilder(cmd)
        if (!b.contains(" -b ")) b.append(" -b ").append(o.mnnBackend)
        if (o.decensor && !b.contains(" -d ")) b.append(" -d 0")
        if (o.maxTileSize > 0 && !b.contains(" -t ")) b.append(" -t ").append(o.maxTileSize)
        if (!b.contains(" -l ")) b.append(" -l ").append(o.loadOpt)
        if (!b.contains(" -T")) {
            val tuneKeys = o.tuneModels.split(',').map { it.trim() }.filter { it.isNotBlank() }
            if (tuneKeys.isNotEmpty()) {
                val modelName = extractModelName(cmd)
                // 自定义模型(extraCommand)可能是任意路径, 提取 -m 后的文件名兜底匹配
                val mFile = Regex(".+\\s-m\\s+(\\S+).*").find(cmd)?.groupValues?.get(1)?.substringAfterLast('/') ?: ""
                val tuned = tuneKeys.any { modelName.contains(it) || mFile.contains(it) }
                if (tuned) b.append(" -T")   // 勾选的模型 → 开启 WIDE 调优
            }
        }
        return b.toString()
    }

    /**
     * 通用参数注入: mnnsr → buildMnnsrCommand; 其他 ncnn CLI → -t 切块/-g 强制 CPU;
     * Anime4k → -p cpu/opencl 跟随 useCPU。其余命令原样返回。
     */
    fun injectParams(
        baseCommand: String,
        tileSize: Int,
        useCPU: Boolean,
        mnnsr: MnnsrOptions?,
    ): String {
        return when {
            baseCommand.matches("./(realsr|srmd|waifu2x|realcugan|mnnsr)-ncnn.+".toRegex()) -> {
                if (baseCommand.startsWith("./mnnsr") && mnnsr != null) {
                    buildMnnsrCommand(baseCommand, mnnsr)
                } else {
                    buildString {
                        append(baseCommand)
                        if (tileSize > 0 && !baseCommand.contains(" -t "))
                            append(" -t ").append(tileSize)
                        if (useCPU && !baseCommand.startsWith("./srmd") && !baseCommand.contains(" -g "))
                            append(" -g -1")
                    }
                }
            }
            baseCommand.startsWith("./Anime4k") -> {
                // Anime4KCPP v3.2.0：处理器由 -p 参数控制，跟随 GUI 的 useCPU 设置
                val proc = if (useCPU) "cpu" else "opencl"
                if (baseCommand.contains(" -p ")) {
                    baseCommand.replace(Regex("\\s-p\\s+\\S+"), " -p $proc")
                } else {
                    baseCommand + " -p " + proc
                }
            }
            else -> baseCommand
        }
    }
}
