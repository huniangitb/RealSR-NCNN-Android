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

#include "mnnsr.h"

#include <opencv2/opencv.hpp>

// JavaVM 全局引用（JNI_OnLoad 保存），用于回调线程调用 Java 方法。
static JavaVM* gJavaVM = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

// 处理单张图片。
// 参数与 CLI 对齐：input/output 图片路径、model 模型路径、scale 倍率、
// backend 推理后端 (CPU=0,AUTO=4,OPENCL=3,OPENGL=6,VULKAN=7,NN=5)、
// gpu 设备索引 (-1=CPU)、colorType 色彩空间、decensorMode 去码模式 (-1=关闭)。
// 返回格式：
//   成功: "OK|<backendName>|<totalMs>"
//   失败: "ERR|<error message>"
extern "C" JNIEXPORT jstring JNICALL
Java_com_tumuyan_ncnn_realsr_MnnsrProcessor_process(
    JNIEnv* env, jclass,
    jstring jInput, jstring jOutput, jstring jModel,
    jint jScale, jint jBackend, jint jGpu, jint jColorType, jint jDecensorMode)
{
    const char* input = env->GetStringUTFChars(jInput, nullptr);
    const char* output = env->GetStringUTFChars(jOutput, nullptr);
    const char* model = env->GetStringUTFChars(jModel, nullptr);
    const int scale = static_cast<int>(jScale);
    const int backend = static_cast<int>(jBackend);
    const int gpu = static_cast<int>(jGpu);
    const int colorType = static_cast<int>(jColorType);
    const int decensorMode = static_cast<int>(jDecensorMode);

    std::string result;
    do
    {
        // 与 CLI main.cpp 一致：-g -1 表示 CPU 后端
        int effectiveBackend = backend;
        if (gpu == -1)
            effectiveBackend = 0; // MNN_FORWARD_CPU

        MNNSR mnnsr(colorType, decensorMode);
        mnnsr.backend_type = static_cast<MNNForwardType>(effectiveBackend);
        mnnsr.scale = scale;
        mnnsr.tilesize = 0;      // 自动
        mnnsr.prepadding = 4;    // 与 CLI 默认一致 (tilesize>0 时为 4)

        if (mnnsr.load(model, true) != 0)
        {
            result = "ERR|MNNSR load failed";
            break;
        }

        // 读取输入图片 (与 CLI load 线程一致：IMREAD_UNCHANGED)
        cv::Mat image = cv::imread(input, cv::IMREAD_UNCHANGED);
        if (image.empty())
        {
            result = "ERR|Failed to load image";
            break;
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
            break;
        }

        // 推理
        cv::Mat outimage;
        if (decensorMode == -1)
            mnnsr.process(inimage, outimage);
        else
            mnnsr.decensor(inimage, outimage);
        if (outimage.empty())
        {
            result = "ERR|invalid result";
            break;
        }

        // 保存输出 (与 CLI save 线程一致：jpg 质量 90，带 alpha 时合并缩放)
        if (!inalpha.empty())
        {
            cv::Mat scaledAlpha;
            cv::resize(inalpha, scaledAlpha, cv::Size(), scale, scale, cv::INTER_LANCZOS4);
            std::vector<cv::Mat> outChannels;
            cv::split(outimage, outChannels);
            outChannels.push_back(scaledAlpha);
            cv::merge(outChannels, outimage);
        }
        std::string ext = output;
        size_t dot = ext.find_last_of('.');
        std::string suffix = (dot == std::string::npos) ? "" : ext.substr(dot + 1);
        if (suffix == "jpg" || suffix == "JPG" || suffix == "jpeg" || suffix == "JPEG")
        {
            if (!cv::imwrite(output, outimage, { cv::IMWRITE_JPEG_QUALITY, 90 }))
            {
                result = "ERR|Failed to save image";
                break;
            }
        }
        else
        {
            if (!cv::imwrite(output, outimage))
            {
                result = "ERR|Failed to save image";
                break;
            }
        }

        std::string backendName = "Unknown";
        try { backendName = get_backend_name(static_cast<MNNForwardType>(effectiveBackend)); }
        catch (...) { }
        result = "OK|" + backendName + "|" + std::to_string(scale);
    } while (false);

    env->ReleaseStringUTFChars(jInput, input);
    env->ReleaseStringUTFChars(jOutput, output);
    env->ReleaseStringUTFChars(jModel, model);

    return env->NewStringUTF(result.c_str());
}
