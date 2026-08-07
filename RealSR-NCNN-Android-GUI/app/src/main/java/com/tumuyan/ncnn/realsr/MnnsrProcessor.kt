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

    /**
     * 处理单张图片。
     *
     * @param input        输入图片绝对路径
     * @param output       输出图片绝对路径
     * @param model        模型绝对路径（.mnn 文件）
     * @param scale        放大倍率（2 / 3 / 4 等）
     * @param backend      推理后端：CPU=0, OPENCL=3, AUTO=4, NN=5, OPENGL=6, VULKAN=7
     * @param gpu          设备索引（-1 表示 CPU）
     * @param colorType    色彩空间类型（RGB=1, BGR=2, YCbCr=5, YUV=6, GRAY=10）
     * @param decensorMode 去码模式（-1 表示关闭）
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
    ): String
}
