package com.tumuyan.ncnn.realsr

/**
 * Anime4KCPP JNI 封装：在 app 进程内直接调用 libanime4k.so。
 *
 * 相比独立可执行文件（exec 子进程），app 进程内 dlopen libOpenCL.so
 * 继承 app 的 classloader namespace，且 manifest 的 <uses-native-library>
 * 声明（libOpenCL.so / libGLES_mali.so）对该进程生效，可绕过
 * Android 10+ 的 linker namespace 隔离（与 MNN Chat 的 JNI 加载方式一致）。
 */
object Anime4kProcessor {
    init {
        System.loadLibrary("anime4k")
    }

    /** 进度回调接口（Java 可通过 SAM lambda 直接实现） */
    fun interface OnProgressListener {
        fun onProgress(current: Int, total: Int)
    }

    /** 信息回调接口：处理开始前上报推理后端/GPU 型号等初始信息 */
    fun interface OnInfoListener {
        fun onInfo(info: String)
    }

    /** 进度回调：current/total 表示 2x 放大阶段进度 */
    @Volatile
    @JvmStatic
    var onProgressListener: OnProgressListener? = null

    /** 信息回调：处理开始前最先打印（推理后端 / GPU 型号） */
    @Volatile
    @JvmStatic
    var onInfoListener: OnInfoListener? = null

    /**
     * 处理单张图片。
     *
     * @param input      输入图片绝对路径
     * @param output     输出图片绝对路径
     * @param model      模型名（如 acnet-f8b8，可用 --models 查询）
     * @param processor  处理器类型：auto / cpu / opencl
     * @param device     设备索引（-1 表示自动）
     * @param factor     放大倍率（2 / 4 等）
     * @return 成功返回 "OK|<backend>|<gpuName>"；失败返回 "ERR|<error message>"
     */
    @JvmStatic
    external fun process(
        input: String,
        output: String,
        model: String,
        processor: String,
        device: Int,
        factor: Double,
    ): String

    /** JNI 侧回调入口：由 native 代码调用，转发给 [onProgressListener] */
    @JvmStatic
    fun onNativeProgress(current: Int, total: Int) {
        onProgressListener?.onProgress(current, total)
    }

    /** JNI 侧信息入口：处理开始前上报推理后端/GPU 型号 */
    @JvmStatic
    fun onNativeInfo(info: String) {
        onInfoListener?.onInfo(info)
    }

    /** 请求取消当前推理：置位 native 取消标志，进度回调检查后不再转发 */
    @JvmStatic
    external fun cancel()

    /** 清除取消标志（新任务开始前调用） */
    @JvmStatic
    external fun reset()
}
