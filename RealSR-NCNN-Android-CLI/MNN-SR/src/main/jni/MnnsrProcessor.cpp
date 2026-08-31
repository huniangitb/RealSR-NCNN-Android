// mnnsr (RealSR MNN) Android JNI 桥接
//
// 将 MNNSR (MNN 超分) 编译为 JNI 动态库 (libmnnsr.so)，供 GUI 在 app 进程内
// 直接调用。相比独立可执行文件 (exec 子进程)，app 进程内 dlopen libMNN.so /
// libMNN_Vulkan.so 时继承 app 的 classloader namespace，且 manifest 的
// <uses-native-library> 声明对该进程生效，可绕过 Android 10+ 的 linker
// namespace 隔离（与 Anime4k JNI 迁移方式一致）。
//
// 输入输出结构与 CLI (mnnsr-ncnn) 保持一致：
//   -i input  -o output  -m model  -s scale  -b backend  -g gpu  -c color  -d decensor

#include <jni.h>

#include <string>
#include <vector>
#include <atomic>

#include "mnnsr.h"

#include "MNN/MNNDefine.h"   // MNN_VERSION 宏, 供 GUI 显示 libMNN 编译版本

#include <opencv2/opencv.hpp>

// JavaVM 全局引用（JNI_OnLoad 保存），用于回调线程调用 Java 方法。
static JavaVM* gJavaVM = nullptr;

// 取消标志：Java 侧调用 cancel() 置位，tile 进度回调检查到后返回 false
// 使 MNNSR::process 提前退出（native 推理无法用线程中断强行停止）。
static std::atomic<bool> gCancelled{false};

// 保存 MnnsrProcessor 类与回调方法引用（FindClass 后缓存，
// 避免回调线程中 FindClass 失败）。
static jclass gMnnsrProcessorClass = nullptr;
static jmethodID gOnNativeProgressMethod = nullptr;
static jmethodID gOnNativeInfoMethod = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

// 确保已缓存 MnnsrProcessor 类的回调方法引用；失败返回 false。
// 注：此函数仅在调用 process 的 Java 线程执行（env 有效），
// 进度回调在 process 内部同一线程执行，env 同样有效。
static bool ensureJavaCallbacks(JNIEnv* env)
{
    if (gMnnsrProcessorClass && gOnNativeProgressMethod && gOnNativeInfoMethod)
        return true;
    jclass clazz = env->FindClass("com/tumuyan/ncnn/realsr/MnnsrProcessor");
    if (!clazz) return false;
    if (gMnnsrProcessorClass)
        env->DeleteGlobalRef(gMnnsrProcessorClass);
    gMnnsrProcessorClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    env->DeleteLocalRef(clazz);
    gOnNativeProgressMethod = env->GetStaticMethodID(gMnnsrProcessorClass, "onNativeProgress", "(IIII)V");
    gOnNativeInfoMethod = env->GetStaticMethodID(gMnnsrProcessorClass, "onNativeInfo", "(Ljava/lang/String;)V");
    return gOnNativeProgressMethod && gOnNativeInfoMethod;
}

// 处理开始前上报初始信息（推理后端），最先打印。
static void reportInitialInfo(JNIEnv* env, const std::string& backendName)
{
    if (!gJavaVM || !ensureJavaCallbacks(env)) return;
    std::string info = "推理后端 ";
    info += backendName.empty() ? "Unknown" : backendName;
    jstring jInfo = env->NewStringUTF(info.c_str());
    env->CallStaticVoidMethod(gMnnsrProcessorClass, gOnNativeInfoMethod, jInfo);
    // 清掉可能由 Java 回调链抛出的待处理异常：若残留，后续首个 tile 的
    // CallStaticVoidMethod(onNativeProgress) 会被 ART 当作无效调用而跳过，
    // 导致首次调用时进度整体丢失（"其他操作成功后再调用才实时显示"）。
    if (env->ExceptionCheck())
        env->ExceptionClear();
    env->DeleteLocalRef(jInfo);
}

// 请求取消当前推理：置位取消标志，tile 进度回调检查到后使 process 提前退出。
extern "C" JNIEXPORT void JNICALL
Java_com_tumuyan_ncnn_realsr_MnnsrProcessor_cancel(JNIEnv*, jclass)
{
    gCancelled.store(true);
}

// 清除取消标志：新任务开始前调用。
extern "C" JNIEXPORT void JNICALL
Java_com_tumuyan_ncnn_realsr_MnnsrProcessor_reset(JNIEnv*, jclass)
{
    gCancelled.store(false);
}

// 返回 libMNN 编译版本号(如 "3.6.1"), 供 GUI 显示当前使用的 MNN 库版本。
extern "C" JNIEXPORT jstring JNICALL
Java_com_tumuyan_ncnn_realsr_MnnsrProcessor_getMnnVersion(JNIEnv* env, jclass)
{
    return env->NewStringUTF(MNN_VERSION);
}

// 检查模型文件存在且大小合理(>1KB): 拦截空/截断/损坏的外部模型文件,
// 避免 MNN createFromFile 解析出异常结构后在 createSession 内部崩溃(SIGSEGV)。
static bool fileUsable(const std::string& path)
{
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return false;
    fseek(fp, 0, SEEK_END);
    long size = ftell(fp);
    fclose(fp);
    return size > 1024;
}

// 解析模型路径（与 CLI main.cpp 一致）：
// - 以 .mnn 结尾：直接使用
// - 否则视为目录，尝试 <dir>/x<scale>.mnn，并按 4/2/1/8 顺序回退修正倍率
// 返回实际可用的模型文件路径；找不到返回空字符串。
static std::string resolveModelPath(const std::string& model, int& scale)
{
    std::string path = model;
    if (model.size() >= 4 && model.compare(model.size() - 4, 4, ".mnn") == 0)
    {
        if (fileUsable(model)) return model;
        return "";
    }
    const int scales[] = { 4, 2, 1, 8 };
    for (int i = 0; i < 4; i++)
    {
        std::string cand = model + "/x" + std::to_string(scales[i]) + ".mnn";
        if (fileUsable(cand))
        {
            if (i > 0) scale = scales[i];
            return cand;
        }
    }
    return "";
}

// 外部共享存储(/storage/emulated/0/ 等 FUSE 路径)模型复制到 app 私有 cache 目录再加载:
// MNN createFromFile 直接读 FUSE 可能数据不完整/异常, 导致推理输出异常(探针 FAIL);
// 同一模型在 app 私有目录则正常(内置模型不崩不 FAIL)。已在私有目录的路径原样返回。
#include <sys/stat.h>
static std::string localizeModelPath(const std::string& modelPath)
{
    if (modelPath.rfind("/storage/", 0) != 0 && modelPath.rfind("/sdcard/", 0) != 0)
        return modelPath;
    const std::string cacheDir = "/data/user/0/com.tumuyan.ncnn.realsr/cache/model_cache";
    const std::string name = modelPath.substr(modelPath.find_last_of('/') + 1);
    if (name.empty()) return modelPath;
    const std::string dest = cacheDir + "/" + name;
    // 已复制过则直接使用
    FILE* df = fopen(dest.c_str(), "rb");
    if (df) { fclose(df); return dest; }
    mkdir(cacheDir.c_str(), 0755);
    FILE* src = fopen(modelPath.c_str(), "rb");
    if (!src) return modelPath;
    FILE* dst = fopen(dest.c_str(), "wb");
    if (!dst) { fclose(src); return modelPath; }
    char buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), src)) > 0)
        fwrite(buf, 1, n, dst);
    fclose(src);
    fclose(dst);
    fprintf(stderr, "model localized: %s -> %s\n", modelPath.c_str(), dest.c_str());
    return dest;
}

// 处理单张图片。
// 参数与 CLI 对齐：input/output 图片路径、model 模型路径、scale 倍率、
// backend 推理后端 (CPU=0,AUTO=4,OPENCL=3,OPENGL=6,VULKAN=7,NN=5)、
// gpu 设备索引 (-1=CPU)、colorType 色彩空间、decensorMode 去码模式 (-1=关闭)、
// tileSize 切块大小、loadOpt 切块加载优化 (0=legacy, 1=矩阵合并 convert)、
// prepadding 切块边界填充(Real-ESRGAN=10, Real-CUGAN=18/14/19, 默认 4)。
// 返回格式：
//   成功: "OK|<backendName>|<scale>"
//   失败: "ERR|<error message>"
extern "C" JNIEXPORT jstring JNICALL
Java_com_tumuyan_ncnn_realsr_MnnsrProcessor_process(
    JNIEnv* env, jclass,
    jstring jInput, jstring jOutput, jstring jModel,
    jint jScale, jint jBackend, jint jGpu, jint jColorType, jint jDecensorMode, jint jTileSize,
    jint jLoadOpt, jint jPrepadding, jint jTuneMode)
{
    const char* input = env->GetStringUTFChars(jInput, nullptr);
    const char* output = env->GetStringUTFChars(jOutput, nullptr);
    const char* model = env->GetStringUTFChars(jModel, nullptr);
    int scale = static_cast<int>(jScale);
    const int backend = static_cast<int>(jBackend);
    const int gpu = static_cast<int>(jGpu);
    const int colorType = static_cast<int>(jColorType);
    const int decensorMode = static_cast<int>(jDecensorMode);
    int tileSize = static_cast<int>(jTileSize);
    int loadOpt = static_cast<int>(jLoadOpt);
    int prepadding = static_cast<int>(jPrepadding);
    int tuneMode = static_cast<int>(jTuneMode);

    std::string result;
    try
    {
        // 与 CLI main.cpp 一致：-g -1 表示 CPU 后端
        int effectiveBackend = backend;
        if (gpu == -1)
            effectiveBackend = 0; // MNN_FORWARD_CPU

        // 解析并验证模型路径（目录 → x<scale>.mnn；缺失时报错，避免空指针崩溃）
        std::string modelPath = resolveModelPath(model, scale);
        // 外部共享存储模型复制到 app 私有目录再加载(绕过 FUSE 读取不完整问题)
        modelPath = localizeModelPath(modelPath);
        if (modelPath.empty())
        {
            result = "ERR|model not found: " + std::string(model);
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        MNNSR mnnsr(colorType, decensorMode);
        mnnsr.backend_type = static_cast<MNNForwardType>(effectiveBackend);
        mnnsr.scale = scale;
        mnnsr.load_opt = loadOpt;
        // GPU 后端调优开关(0=跳过调优首跑快, 1=WIDE 调优性能最优但首跑慢): 由 GUI 对指定模型开启
        mnnsr.tuneMode = tuneMode;
        // tilesize 默认逻辑与 CLI main.cpp:857-869 一致：
        // 0 → 按模型文件大小选择 256/128/96/64，最小 64，避免 0 导致死循环/崩溃
        if (tileSize == 0)
        {
            long modelSize = 0;
            FILE* mp = fopen(modelPath.c_str(), "rb");
            if (mp) { fseek(mp, 0, SEEK_END); modelSize = ftell(mp) / 1000000; fclose(mp); }
            tileSize = (modelSize < 10) ? 256 : (modelSize < 16) ? 128 : (modelSize < 24) ? 96 : 64;
        }
        if (tileSize < 64)
            tileSize = 64;
        mnnsr.tilesize = static_cast<uint>(tileSize);
        // prepadding 由调用方按模型指定(Real-ESRGAN=10, Real-CUGAN 2x=18/3x=14/4x=19),
        // 默认 4 保持旧行为
        mnnsr.prepadding = prepadding > 0 ? static_cast<uint>(prepadding) : 4;

        // 处理开始前最先上报推理后端信息（不等模型加载/处理完成）。
        // 注意：必须在 mnnsr.load 之前调用——首次调用时 createSession(尤其
        // Vulkan/OpenCL 后端)会编译 kernel / 写模型缓存，耗时可能很长，
        // 若等 load 完成再上报，首次运行在加载期间 UI 完全没有反馈，
        // 表现为"进度/结果不实时显示"；提前上报后 UI 立即显示推理后端。
        {
            std::string backendName;
            try { backendName = get_backend_name(static_cast<MNNForwardType>(effectiveBackend)); }
            catch (...) { }
            reportInitialInfo(env, backendName);
        }

        // 注册 info 回调: 转发调优进度等文本信息到 Java onNativeInfo(与 reportInitialInfo 同通道)。
        // 必须在 mnnsr.load 之前注册——load 内注册的 OpenCL 调优进度回调捕获 this->infoCallback_,
        // 若为空则调优进度无法转发到 GUI。
        mnnsr.setInfoCallback([env](const std::string& info) {
            // 清理可能残留的待处理异常, 避免首次转发被 ART 跳过
            if (env->ExceptionCheck())
                env->ExceptionClear();
            if (!gJavaVM || !ensureJavaCallbacks(env))
                return;
            jstring jInfo = env->NewStringUTF(info.c_str());
            env->CallStaticVoidMethod(gMnnsrProcessorClass, gOnNativeInfoMethod, jInfo);
            env->DeleteLocalRef(jInfo);
            if (env->ExceptionCheck())
                env->ExceptionClear();
        });

        // 注册进度回调(必须在 mnnsr.load 之前):
        // OpenCL WIDE 调优在 load 的 resizeSession 阶段就开始逐算子调优, 若 load 后才注册
        // progressCallback_, 调优阶段的回调为空, 算子调优进度无法转发到 GUI(与图片 tile 进度同通道)。
        mnnsr.setProgressCallback([env](int current, int total, int tileW, int tileH) {
            if (gCancelled.load())
                return false;
            if (env->ExceptionCheck())
                env->ExceptionClear();
            if (!gJavaVM || !ensureJavaCallbacks(env))
                return true;
            env->CallStaticVoidMethod(gMnnsrProcessorClass, gOnNativeProgressMethod, current, total, tileW, tileH);
            if (env->ExceptionCheck())
                env->ExceptionClear();
            return true;
        });

        if (mnnsr.load(modelPath, true) != 0)
        {
            result = "ERR|MNNSR load failed";
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        // 读取输入图片 (与 CLI load 线程一致：IMREAD_UNCHANGED)
        cv::Mat image = cv::imread(input, cv::IMREAD_UNCHANGED);
        if (image.empty())
        {
            result = "ERR|Failed to load image";
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        // 同步 session 输入尺寸到最终 tilesize: memBudget 在 load 之后修改了 tilesize,
        // 若不 resizeSession, process 的 paddedTile 尺寸与 session 输入不一致会导致输出错位/黑边
        if (mnnsr.setInputSize(static_cast<int>(mnnsr.tilesize)) != 0)
        {
            result = "ERR|setInputSize failed";
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        cv::Mat inimage;
        cv::Mat inalpha;
        int c = image.channels();
        if (c == 1)
        {
            cv::cvtColor(image, inimage, cv::COLOR_GRAY2BGR);
        }
        else if (c == 4)
        {
            std::vector<cv::Mat> channels;
            cv::split(image, channels);
            cv::Mat alphaChannel = channels[3];
            if (cv::countNonZero(alphaChannel != alphaChannel.at<uchar>(0, 0)) != 0)
                inalpha = alphaChannel;
            cv::merge(channels.data(), 3, inimage);
        }
        else if (c == 3)
        {
            inimage = image;
        }
        else
        {
            result = "ERR|unsupported channel count";
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        // 推理前预分配输出缓冲（与 CLI main.cpp:308 一致：
        // v.outimage = cv::Mat(rows*scale, cols*scale, CV_8UC3)），
        // 否则 MNNSR::process 内部对空 Mat 的 Rect 写入会触发 cv::Exception 崩溃
        cv::Mat outimage(inimage.rows * scale, inimage.cols * scale, CV_8UC3);
        int procRet = 0;
        if (decensorMode == -1)
            procRet = mnnsr.process(inimage, outimage);
        else
            procRet = mnnsr.decensor(inimage, outimage);
        // 取消: tile 循环提前退出, 输出数据不完整, 不写文件并返回取消
        if (gCancelled.load())
        {
            result = "ERR|cancelled";
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }
        // process(非去码)返回 -1 视为失败; decensor 无马赛克时也返回 -1 但
        // outimage 已复制有效内容, 属正常结果, 继续走保存流程(下方 empty 兜底)
        if (procRet != 0 && decensorMode == -1)
        {
            result = "ERR|process failed";
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }
        if (outimage.empty())
        {
            result = "ERR|invalid result";
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        // 保存输出 (与 CLI save 线程一致: jpg 质量 90; alpha 仅在非 jpg 时合并——
        // OpenCV JPEG 编码器不支持 4 通道图, 先合并再写 jpg 会保存失败)
        std::string ext = output;
        size_t dot = ext.find_last_of('.');
        std::string suffix = (dot == std::string::npos) ? "" : ext.substr(dot + 1);
        if (suffix == "jpg" || suffix == "JPG" || suffix == "jpeg" || suffix == "JPEG")
        {
            if (!cv::imwrite(output, outimage, { cv::IMWRITE_JPEG_QUALITY, 90 }))
            {
                result = "ERR|Failed to save image";
                env->ReleaseStringUTFChars(jInput, input);
                env->ReleaseStringUTFChars(jOutput, output);
                env->ReleaseStringUTFChars(jModel, model);
                return env->NewStringUTF(result.c_str());
            }
        }
        else
        {
            // 非 jpg: 合并缩放 alpha, 输出 4 通道 PNG/WebP
            if (!inalpha.empty())
            {
                cv::Mat scaledAlpha;
                cv::resize(inalpha, scaledAlpha, cv::Size(), scale, scale, cv::INTER_LANCZOS4);
                std::vector<cv::Mat> outChannels;
                cv::split(outimage, outChannels);
                outChannels.push_back(scaledAlpha);
                cv::merge(outChannels, outimage);
            }
            if (!cv::imwrite(output, outimage))
            {
                result = "ERR|Failed to save image";
                env->ReleaseStringUTFChars(jInput, input);
                env->ReleaseStringUTFChars(jOutput, output);
                env->ReleaseStringUTFChars(jModel, model);
                return env->NewStringUTF(result.c_str());
            }
        }

        std::string backendName = "Unknown";
        try { backendName = get_backend_name(static_cast<MNNForwardType>(effectiveBackend)); }
        catch (...) { }
        result = "OK|" + backendName + "|" + std::to_string(scale);
    }
    catch (const cv::Exception& e)
    {
        result = "ERR|cv exception: " + std::string(e.what());
    }
    catch (const std::exception& e)
    {
        result = "ERR|exception: " + std::string(e.what());
    }
    catch (...)
    {
        result = "ERR|unknown native exception";
    }

    env->ReleaseStringUTFChars(jInput, input);
    env->ReleaseStringUTFChars(jOutput, output);
    env->ReleaseStringUTFChars(jModel, model);

    return env->NewStringUTF(result.c_str());
}
