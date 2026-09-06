package com.tumuyan.ncnn.realsr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.icu.text.SimpleDateFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.core.content.FileProvider
import top.yukonga.miuix.kmp.icon.extended.Share
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

internal fun MainActivity.openImagePicker() {
    if (useMultFiles) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, MainActivity.SELECT_MULTI_IMAGE)
    } else {
        val i = Intent(Intent.ACTION_PICK)
        i.type = "image/*"
        startActivityForResult(i, MainActivity.SELECT_IMAGE)
    }
}

internal fun MainActivity.runSelectedCommand() {
    stopCommand()
    log = ""
    val cmds = command
    val cmdHead: String = if (cmds != null && selectCommand < cmds.size) {
        cmds[selectCommand]
    } else {
        displayLabels.getOrNull(selectCommand) ?: return
    }
    // 统一参数注入(mnnsr 后端/切块/调优, ncnn -t/-g, Anime4k -p), 规则集中 in CommandParams
    val cmd = StringBuilder(CommandParams.injectParams(cmdHead, tileSize, useCPU, mnnsrOptions()))
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

internal fun MainActivity.saveOutput() {
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
    if (format == 3) convertOutputToHeic(outputSavePath)
    checkSaveOutput()
}

// HEIF 输出转码: 用 Android 原生 HEIF 编码(API 34+), 不支持时回退 PNG。
// Bitmap.CompressFormat.valueOf("HEIF") 运行时获取, 避免 compileSdk 28 编译期依赖。
private fun MainActivity.convertOutputToHeic(dst: String) {
    val srcPng = dst.replace(".heic", ".png")
    try {
        val bmp = BitmapFactory.decodeFile(srcPng) ?: return
        val heif = try {
            Bitmap.CompressFormat.valueOf("HEIF")
        } catch (e: Exception) {
            null
        }
        if (heif != null) {
            FileOutputStream(dst).use { out ->
                if (bmp.compress(heif, 95, out)) {
                    File(srcPng).delete()   // 转码成功, 删除临时 png
                } else {
                    showSnackbar("HEIF 编码失败, 已保存为 PNG")
                }
            }
        } else {
            showSnackbar("设备不支持 HEIF 编码, 已保存为 PNG")
        }
        bmp.recycle()
    } catch (e: Exception) {
        e.printStackTrace()
        showSnackbar("HEIF 保存失败: ${e.message}")
    }
}

// ==================== 文件与分享 ====================

internal fun MainActivity.readFileFromShare() {
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

private fun MainActivity.whiteFileFromUri(uri: Uri?, path: String): Boolean {
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
internal fun MainActivity.handleSelectedImages(uris: ArrayList<Uri>?) {
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

internal fun MainActivity.shareImage(path: String) {
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

internal fun MainActivity.scanFiles(filePath: Array<String>) {
    Log.i("scanFiles()", "length=${filePath.size}")
    try {
        MediaScannerConnection.scanFile(applicationContext, filePath, null) { _, _ -> }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

internal fun MainActivity.checkSaveOutput() {
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
internal fun MainActivity.saveOutputCmd(): String {
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
            // HEIF 输出: magick 无 HEIF 编码器, 先复制为临时 png, 由 convertOutputToHeic 转码为 .heic
            outputSavePath += ".heic"
            cmd = "cp " + dir + "/output.png " + ShellUtils.escapeShellArgument(outputSavePath.replace(".heic", ".png"))
            return cmd
        } else {
            outputSavePath += ".jpg"
            val q = formats[format].replace(Regex("[a-zA-Z%\\s]+"), "")
            cmd = if (q.length > 0) "./magick output.png -quality $q" else "./magick output.png"
        }
    }
    return cmd + " " + ShellUtils.escapeShellArgument(outputSavePath)
}

// 在主进程执行命令但是不刷新UI，也不被打断
private fun MainActivity.getGifFrameDelay(path: String): Int {
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

// ==================== 图片处理 ====================

/**
 * 保存文件
 *
 * @param in   输出的文件流
 * @param path 输出的文件路径，路径为空时保存为input.png
 * @return 是否保存成功
 */
internal fun MainActivity.saveInputImage(inputStream: InputStream, path: String): Boolean {
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
            } else {
                // prePng 关闭时: 仅 HEIF/AVIF 需转码(OpenCV 不支持), 其他格式直接复制
                match = PreprocessToPng.match(buffer)
                if (match >= 0 && (PreprocessToPng.isHeif(match) || PreprocessToPng.isAVIF(match))) {
                    file = File(dir + "/tmp")
                    if (file.exists()) {
                        file.delete()
                    }
                } else {
                    match = -1   // 非 HEIF/AVIF: 直接复制, 不进入预处理分支
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
                // HEIF/AVIF 转码: ImageDecoder(API 28+, 原生支持 HEIF)解码 → PNG。
                // 超大图(如 48MP heic)直接解码会 OOM(OutOfMemoryError 非 Exception, 不被捕获),
                // 故先读尺寸按 >4096 降采样; 失败原因输出到 UI 信息框便于诊断。
                var decodeError = ""
                var bitmap: Bitmap? = null
                try {
                    if (Build.VERSION.SDK_INT >= 28) {
                        val src = ImageDecoder.createSource(File(dir + "/tmp"))
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(dir + "/tmp", bounds)
                        var sample = 1
                        while ((bounds.outWidth > 0 && bounds.outWidth / sample > 4096) ||
                            (bounds.outHeight > 0 && bounds.outHeight / sample > 4096)
                        ) sample *= 2
                        bitmap = ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                            if (sample > 1) decoder.setTargetSampleSize(sample)
                        }
                    } else {
                        bitmap = BitmapFactory.decodeFile(dir + "/tmp")
                    }
                } catch (e: OutOfMemoryError) {
                    decodeError = "OutOfMemory(图片过大)"
                } catch (e: Exception) {
                    decodeError = e.message ?: e.javaClass.simpleName
                }
                if (bitmap != null) {
                    try {
                        val out = FileOutputStream(p)
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                        out.close()
                    } catch (e: IOException) {
                        decodeError = e.message ?: "compress failed"
                    }
                    bitmap.recycle()
                } else {
                    decodeError = if (decodeError.isEmpty()) "decode null" else decodeError
                }
                if (decodeError.isNotEmpty()) {
                    val msg = "HEIF 转码失败: $decodeError (tmp=$dir/tmp)"
                    Log.e("saveInputImage", msg)
                    log = msg   // 信息卡常驻显示(snackbar 短暂易被忽略)
                    showSnackbar(msg)
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
    updateImage(dir + "/input.png", getString(R.string.lr), false, true)
    return true
}

/** 选中图片后立即估算预计内存消耗(仅 MNN 显示: 模型文件 + 输入 + 输出 + 混合缓冲 + 推理峰值) */
private fun MainActivity.estimateMemorySuffix(file: File): String {
    try {
        // 仅 mnnsr 命令显示内存估算, 其他程序(realsr/waifu2x 等)不显示
        val cur = command?.getOrNull(selectCommand) ?: return ""
        if (!cur.trim().startsWith("./mnnsr-ncnn")) return ""
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return ""
        val s = Regex("\\s-s\\s+(\\d+)").find(cur)?.groupValues?.get(1)?.toIntOrNull() ?: 4
        val tile = maxTileSize.coerceIn(128, 512)
        // 模型文件大小(从 -m 解析, 相对路径基于 dir; 内置/外载模型均可)
        var modelBytes = 0L
        val m = Regex("\\s-m\\s+(\\S+)").find(cur)?.groupValues?.get(1)
        if (m != null) {
            val mp = if (m.startsWith("/")) m else dir + "/" + m
            val f = File(mp)
            if (f.exists()) modelBytes = f.length()
        }
        val inBytes = w.toLong() * h * 3
        val outBytes = w.toLong() * s * h * s * 3
        val blendBytes = w.toLong() * s * h * s * 16
        // 与 JNI 处理时预算公式一致(模型+输入+输出+混合+推理), 再乘后端运行内存放大系数:
        // 实测 480x480/模型33MB 时基础估算 116MB vs 实际峰值 1474MB(约 ×12.7)。
        // 放大主要来自 MNN GPU 后端(OpenCL/Vulkan): 权重多份拷贝、GPU Featuremaps 缓冲、
        // CPU↔GPU 数据拷贝等(阿里云实践/MNN issue #2870: GPU 后端 CPU 内存反而更高)。
        val tileBytes = tile.toLong() * tile * s * s * 3 * 4
        val totalMB = (modelBytes + inBytes + outBytes + blendBytes + tileBytes) * 12L / 1_000_000L
        return ", 预计内存约 $totalMB MB (x$s, 切块 $tile, 模型 ${modelBytes / 1_000_000L}MB)"
    } catch (e: Exception) {
        return ""
    }
}

internal fun MainActivity.updateImage(path: String, text: String, keepScreen: Boolean, withEstimate: Boolean = false) {
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
                // 仅在选中输入图片时估算内存; 处理完成后显示输出图不估算(避免对输出图重复放大)
                log = getImageResolution(file, text) + if (withEstimate) estimateMemorySuffix(file) else ""
                Log.i("saveInputImage", "finish, file")
            }
        } else {
            Log.i("saveInputImage", "skip")
        }
    }
}

internal fun MainActivity.showImage(file: File?, info: String) {
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

// 删除文件或者目录
internal fun deleteFile(f: File?) {
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
