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

// 探针缓存: 首次使用模型时测量其最大可用输入尺寸, 写入 <model>.probe.cache,
// 之后直接读取缓存, 避免每次处理都做探针推理。缓存带 scale 校验。
static int getModelMaxInputSize(const std::string& modelPath, int scale, MNNSR& mnnsr)
{
    std::string cachePath = modelPath + ".probe.cache";
    FILE* cf = fopen(cachePath.c_str(), "r");
    if (cf)
    {
        int cScale = 0, cMax = 0;
        if (fscanf(cf, "%d %d", &cScale, &cMax) == 2 && cScale == scale && cMax >= 64)
        {
            fclose(cf);
            fprintf(stderr, "probe cache hit: scale=%d maxInput=%d\n", cScale, cMax);
            return cMax;
        }
        fclose(cf);
    }
    int maxInput = mnnsr.probeMaxInputSize(scale, 512);
    if (maxInput >= 64)
    {
        FILE* wf = fopen(cachePath.c_str(), "w");
        if (wf)
        {
            fprintf(wf, "%d %d\n", scale, maxInput);
            fclose(wf);
            fprintf(stderr, "probe cached: %s -> %d\n", cachePath.c_str(), maxInput);
        }
    }
    return maxInput;
}

// 处理开始前上报初始信息（推理后端），最先打印。
static void reportInitialInfo(JNIEnv* env, const std::string& backendName)
{
    if (!gJavaVM || !ensureJavaCallbacks(env)) return;
    std::string info = "推理后端 ";
    info += backendName.empty() ? "Unknown" : backendName;
    jstring jInfo = env->NewStringUTF(info.c_str());
    env->CallStaticVoidMethod(gMnnsrProcessorClass, gOnNativeInfoMethod, jInfo);
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

// 解析模型路径（与 CLI main.cpp 一致）：
// - 以 .mnn 结尾：直接使用
// - 否则视为目录，尝试 <dir>/x<scale>.mnn，并按 4/2/1/8 顺序回退修正倍率
// 返回实际可用的模型文件路径；找不到返回空字符串。
static std::string resolveModelPath(const std::string& model, int& scale)
{
    std::string path = model;
    if (model.size() >= 4 && model.compare(model.size() - 4, 4, ".mnn") == 0)
    {
        FILE* fp = fopen(model.c_str(), "rb");
        if (fp) { fclose(fp); return model; }
        return "";
    }
    const int scales[] = { 4, 2, 1, 8 };
    for (int i = 0; i < 4; i++)
    {
        std::string cand = model + "/x" + std::to_string(scales[i]) + ".mnn";
        FILE* fp = fopen(cand.c_str(), "rb");
        if (fp)
        {
            fclose(fp);
            if (i > 0) scale = scales[i];
            return cand;
        }
    }
    return "";
}

// 处理单张图片。
// 参数与 CLI 对齐：input/output 图片路径、model 模型路径、scale 倍率、
// backend 推理后端 (CPU=0,AUTO=4,OPENCL=3,OPENGL=6,VULKAN=7,NN=5)、
// gpu 设备索引 (-1=CPU)、colorType 色彩空间、decensorMode 去码模式 (-1=关闭)。
// 返回格式：
//   成功: "OK|<backendName>|<scale>"
//   失败: "ERR|<error message>"
extern "C" JNIEXPORT jstring JNICALL
Java_com_tumuyan_ncnn_realsr_MnnsrProcessor_process(
    JNIEnv* env, jclass,
    jstring jInput, jstring jOutput, jstring jModel,
    jint jScale, jint jBackend, jint jGpu, jint jColorType, jint jDecensorMode, jint jTileSize, jint jMemBudgetMB)
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
    const int memBudgetMB = static_cast<int>(jMemBudgetMB);

    std::string result;
    try
    {
        // 与 CLI main.cpp 一致：-g -1 表示 CPU 后端
        int effectiveBackend = backend;
        if (gpu == -1)
            effectiveBackend = 0; // MNN_FORWARD_CPU

        // 解析并验证模型路径（目录 → x<scale>.mnn；缺失时报错，避免空指针崩溃）
        std::string modelPath = resolveModelPath(model, scale);
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
        // tilesize 默认逻辑与 CLI main.cpp:857-869 一致：
        // 0 → 按模型文件大小选择 256/128/96/64，最小 64，避免 0 导致死循环/崩溃
        if (tileSize == 0)
        {
            long modelSize = 0;
            FILE* mp = fopen(modelPath.c_str(), "rb");
            if (mp) { fseek(mp, 0, SEEK_END); modelSize = ftell(mp) / 1000000; fclose(mp); }
            tileSize = (modelSize < 10) ? 256 : (modelSize < 16) ? 128 : (modelSize < 24) ? 96 : 64;
        }
        // 内存预算模式(默认关闭, memBudgetMB=0)的 tilesize 反推在主图片解码后
        // 复用其尺寸计算(见下方 applyMemBudgetTilesize), 避免二次解码。
        if (tileSize < 64)
            tileSize = 64;
        mnnsr.tilesize = static_cast<uint>(tileSize);
        mnnsr.prepadding = 4;    // 与 CLI 默认一致 (tilesize>0 时为 4)

        if (mnnsr.load(modelPath, true) != 0)
        {
            result = "ERR|MNNSR load failed";
            env->ReleaseStringUTFChars(jInput, input);
            env->ReleaseStringUTFChars(jOutput, output);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        // 探针: 测量模型最大可用输入尺寸(首次探测写缓存, 后续直接读缓存),
        // 结果用于钳制 tilesize, 避免模型在超出支持的输入尺寸下输出异常(黑边/丢像素)
        int modelMaxInput = getModelMaxInputSize(modelPath, scale, mnnsr);
        if (modelMaxInput > 0)
            fprintf(stderr, "model max input size: %d\n", modelMaxInput);
        else
            fprintf(stderr, "probe failed: model does not produce in*scale output at min size\n");

        // 处理开始前上报推理后端信息（不等处理完成）
        {
            std::string backendName;
            try { backendName = get_backend_name(static_cast<MNNForwardType>(effectiveBackend)); }
            catch (...) { }
            reportInitialInfo(env, backendName);
        }

        // 注册进度回调：每个 tile 完成后转发到 Java 的 onNativeProgress(含当前切块尺寸)；
        // 若已请求取消则返回 false，使 MNNSR::process 提前退出
        if (ensureJavaCallbacks(env))
        {
            mnnsr.setProgressCallback([env](int current, int total, int tileW, int tileH) {
                if (gCancelled.load())
                    return false;
                if (gJavaVM)
                {
                    env->CallStaticVoidMethod(gMnnsrProcessorClass, gOnNativeProgressMethod, current, total, tileW, tileH);
                    if (env->ExceptionCheck())
                        env->ExceptionClear();
                }
                return true;
            });
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

        // 内存预算模式(默认关闭, memBudgetMB=0): 复用已解码的主图片尺寸,
        // 按预算反推更大输入 tilesize 提升质量(避免二次解码)。
        // 预算分配: 输出整图(最大项) + 重叠混合缓冲(accum 12B/px + weightMap 4B/px) +
        //           模型 + 输入整图 + 推理峰值(tilesize²×scale²×3×4B 系数) + 20% 余量
        if (memBudgetMB > 0)
        {
            long budgetBytes = static_cast<long>(memBudgetMB) * 1000000L;
            long modelSizeBytes = 0;
            FILE* mp2 = fopen(modelPath.c_str(), "rb");
            if (mp2) { fseek(mp2, 0, SEEK_END); modelSizeBytes = ftell(mp2); fclose(mp2); }
            long inBytes = static_cast<long>(image.cols) * image.rows * 3L;
            long outPixels = static_cast<long>(image.cols) * scale *
                             static_cast<long>(image.rows) * scale;
            long outBytes = outPixels * 3L;
            // 重叠区加权混合的整图缓冲: accum(CV_32FC3, 12B/px) + weightMap(CV_32FC1, 4B/px),
            // 不随 tilesize 变化, 必须计入预算否则大图会 OOM
            long blendBytes = outPixels * 16L;
            long available = budgetBytes - outBytes - blendBytes - modelSizeBytes - inBytes;
            available = (available * 8L) / 10L; // 20% 安全余量
            if (available > 0)
            {
                // 推理峰值 ≈ tilesize² × scale² × 3ch × 4B(浮点) × 2(中间缓冲系数)
                long perTile = static_cast<long>(scale) * scale * 3L * 4L * 2L;
                long maxTile = (long)std::sqrt((double)available / (double)perTile);
                if (maxTile > 0)
                {
                    // 对齐到 16, 钳制 [64, 512]:
                    // 上限 512 与探针探测上限一致——即使 memBudget 反推出更大 tilesize,
                    // 也会被探针测得的模型真实输入上限钳制(见下方 modelMaxInput 钳制),
                    // 模型不支持大输入时自动回退, 不会触发 interp_scale 强行缩放/黑边
                    maxTile = (maxTile / 16) * 16;
                    if (maxTile < 64) maxTile = 64;
                    if (maxTile > 512) maxTile = 512;
                    mnnsr.tilesize = static_cast<uint>(maxTile);
                    fprintf(stderr, "memBudget mode: budget=%dMB, tileSize=%u\n", memBudgetMB, mnnsr.tilesize);
                }
            }
        }

        // 钳制 tilesize 到探针测得的模型输入上限(探针失败/缓存缺失时 modelMaxInput 可能为 0, 跳过)
        if (modelMaxInput >= 64 && mnnsr.tilesize > static_cast<uint>(modelMaxInput))
        {
            fprintf(stderr, "clamp tilesize %u -> %d (probe max input)\n", mnnsr.tilesize, modelMaxInput);
            mnnsr.tilesize = static_cast<uint>(modelMaxInput);
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

// 探针测试: 加载模型并测量其最大可用输入尺寸(复用 getModelMaxInputSize 缓存),
// 返回 "OK|maxInput=<N>|scale=<S>" 或 "ERR|<error message>"
extern "C" JNIEXPORT jstring JNICALL
Java_com_tumuyan_ncnn_realsr_MnnsrProcessor_probe(
    JNIEnv* env, jclass,
    jstring jModel, jint jScale, jint jBackend, jint jGpu)
{
    const char* model = env->GetStringUTFChars(jModel, nullptr);
    int scale = static_cast<int>(jScale);
    const int backend = static_cast<int>(jBackend);
    const int gpu = static_cast<int>(jGpu);

    std::string result;
    try
    {
        int effectiveBackend = backend;
        if (gpu == -1)
            effectiveBackend = 0; // MNN_FORWARD_CPU

        // 与 process 一致: 目录 → x<scale>.mnn
        std::string modelPath = resolveModelPath(model, scale);
        if (modelPath.empty())
        {
            result = "ERR|model not found: " + std::string(model);
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        MNNSR mnnsr(1, -1);   // colorType=RGB, decensor 关闭
        mnnsr.backend_type = static_cast<MNNForwardType>(effectiveBackend);
        mnnsr.scale = scale;
        mnnsr.tilesize = 64;  // 探测从 64 起步
        mnnsr.prepadding = 4;

        if (mnnsr.load(modelPath, true) != 0)
        {
            result = "ERR|MNNSR load failed";
            env->ReleaseStringUTFChars(jModel, model);
            return env->NewStringUTF(result.c_str());
        }

        int maxInput = getModelMaxInputSize(modelPath, scale, mnnsr);
        if (maxInput >= 64)
            result = "OK|maxInput=" + std::to_string(maxInput) +
                     "|scale=" + std::to_string(scale);
        else
            result = "ERR|probe failed: model output != input*scale at min size";
    }
    catch (const std::exception& e)
    {
        result = "ERR|exception: " + std::string(e.what());
    }
    catch (...)
    {
        result = "ERR|unknown native exception";
    }

    env->ReleaseStringUTFChars(jModel, model);
    return env->NewStringUTF(result.c_str());
}
