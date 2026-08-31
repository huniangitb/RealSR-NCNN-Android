package com.tumuyan.ncnn.realsr

/**
 * mnnsr (RealSR MNN) JNI 封装：在 app 进程内直接调用 libmnnsr.so。
 *
 * 相比独立可执行文件（exec 子进程），app 进程内 dlopen libMNN.so /
 * libMNN_Vulkan.so 继承 app 的 classloader namespace，且 manifest 的
 * <uses-native-library> 声明对该进程生效，可绕过 Android 10+ 的 linker
 * namespace 隔离（与 Anime4k 的 JNI 加载方式一致）。
 */
object MnnsrProcessor {
    init {
        System.loadLibrary("mnnsr")
    }

    /** 进度回调接口（Java 可通过 SAM lambda 直接实现） */
    fun interface OnProgressListener {
        fun onProgress(current: Int, total: Int, tileWidth: Int, tileHeight: Int)
    }

    /** 信息回调接口：处理开始前上报推理后端等初始信息 */
    fun interface OnInfoListener {
        fun onInfo(info: String)
    }

    /** 进度回调：current/total 表示 tile 处理进度 */
    @Volatile
    @JvmStatic
    var onProgressListener: OnProgressListener? = null

    /** 信息回调：处理开始前最先打印（推理后端） */
    @Volatile
    @JvmStatic
    var onInfoListener: OnInfoListener? = null

    /**
     * 处理单张图片。
     *
     * @param input        输入图片绝对路径
     * @param output       输出图片绝对路径
     * @param model        模型绝对路径（.mnn 文件）
     * @param scale        放大倍率（2 / 3 / 4 等）
     * @param backend      推理后端：CPU=0, OPENCL=3, AUTO=4, NN=5, OPENGL=6, VULKAN=7
     * @param gpu          设备索引（-1 表示 CPU；-2 表示未指定，保留 backend）
     * @param colorType    色彩空间类型（RGB=1, BGR=2, YCbCr=5, YUV=6, GRAY=10）
     * @param decensorMode 去码模式（-1 表示关闭）
     * @param tileSize     分块大小（0 表示按模型大小自动选择 64~256）
     * @param loadOpt      切块加载优化（0=legacy：裁剪+copyMakeBorder+convert；1=合并：矩阵+wrap=ZERO 直接从原图裁剪+padding）
     * @param prepadding   切块边界填充像素数（Real-ESRGAN=10，Real-CUGAN 2x=18/3x=14/4x=19，默认 4）
     * @param tuneMode     GPU 后端调优开关（0=跳过调优首跑快；1=WIDE 调优性能最优但首跑慢，需 GUI 对指定模型开启）
     * @return 成功返回 "OK|<backend>|<scale>"；失败返回 "ERR|<error message>"
     */
    @JvmStatic
    external fun process(
        input: String,
        output: String,
        model: String,
        scale: Int,
        backend: Int,
        gpu: Int,
        colorType: Int,
        decensorMode: Int,
        tileSize: Int,
        loadOpt: Int,
        prepadding: Int,
        tuneMode: Int,
    ): String

    /** JNI 侧回调入口：由 native 代码调用，转发给 [onProgressListener]（含当前切块像素尺寸） */
    @JvmStatic
    fun onNativeProgress(current: Int, total: Int, tileWidth: Int, tileHeight: Int) {
        onProgressListener?.onProgress(current, total, tileWidth, tileHeight)
    }

    /** JNI 侧信息入口：处理开始前上报推理后端 */
    @JvmStatic
    fun onNativeInfo(info: String) {
        onInfoListener?.onInfo(info)
    }

    /** 请求取消当前推理：置位 native 取消标志，tile 循环检查后提前退出 */
    @JvmStatic
    external fun cancel()

    /** 清除取消标志（新任务开始前调用） */
    @JvmStatic
    external fun reset()

    /** 返回 libMNN 编译版本号（如 "3.6.1"），供 GUI 显示当前 MNN 库版本 */
    @JvmStatic
    external fun getMnnVersion(): String
}
