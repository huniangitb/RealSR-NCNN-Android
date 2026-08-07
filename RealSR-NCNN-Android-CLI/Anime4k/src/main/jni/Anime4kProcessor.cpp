// Anime4KCPP Android JNI 桥接
//
// 将 Anime4KCPP core 编译为 JNI 动态库（libanime4k.so），供 GUI 在
// app 进程内直接调用。相比独立可执行文件（exec 子进程），app 进程内
// dlopen libOpenCL.so 时继承 app 的 classloader namespace，且
// manifest 的 <uses-native-library>（libOpenCL.so / libGLES_mali.so）
// 对该进程生效，可绕过 Android 10+ 的 linker namespace 隔离。

#include <jni.h>

#include <string>
#include <vector>
#include <atomic>

#include "AC/Core.hpp"

#include <CL/cl.h>

// JavaVM 全局引用（JNI_OnLoad 保存），用于进度回调线程调用 Java 方法。
static JavaVM* gJavaVM = nullptr;

// 取消标志：Java 侧调用 cancel() 置位；core 的进度回调为 void 无法中断
// process，取消后进度不再转发，process 完成后返回取消状态。
static std::atomic<bool> gCancelled{false};

// 保存 Anime4kProcessor 类与回调方法引用（FindClass 后缓存，
// 避免回调线程中 FindClass 失败）。
static jclass gAnime4kProcessorClass = nullptr;
static jmethodID gOnNativeProgressMethod = nullptr;
static jmethodID gOnNativeInfoMethod = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

// 请求取消当前推理：置位取消标志，进度回调检查到后不再转发。
extern "C" JNIEXPORT void JNICALL
Java_com_tumuyan_ncnn_realsr_Anime4kProcessor_cancel(JNIEnv*, jclass)
{
    gCancelled.store(true);
}

// 清除取消标志：新任务开始前调用。
extern "C" JNIEXPORT void JNICALL
Java_com_tumuyan_ncnn_realsr_Anime4kProcessor_reset(JNIEnv*, jclass)
{
    gCancelled.store(false);
}

// 确保已缓存 Anime4kProcessor 类的回调方法引用；失败返回 false。
// 注：此函数仅在调用 process 的 Java 线程执行（env 有效），
// 进度回调在 process 内部同一线程执行，env 同样有效。
static bool ensureJavaCallbacks(JNIEnv* env)
{
    if (gAnime4kProcessorClass && gOnNativeProgressMethod && gOnNativeInfoMethod)
        return true;
    jclass clazz = env->FindClass("com/tumuyan/ncnn/realsr/Anime4kProcessor");
    if (!clazz) return false;
    if (gAnime4kProcessorClass)
        env->DeleteLocalRef(gAnime4kProcessorClass);
    gAnime4kProcessorClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    env->DeleteLocalRef(clazz);
    gOnNativeProgressMethod = env->GetStaticMethodID(gAnime4kProcessorClass, "onNativeProgress", "(II)V");
    gOnNativeInfoMethod = env->GetStaticMethodID(gAnime4kProcessorClass, "onNativeInfo", "(Ljava/lang/String;)V");
    return gOnNativeProgressMethod && gOnNativeInfoMethod;
}

// 处理开始前上报初始信息（推理后端 / GPU 型号），最先打印。
static void reportInitialInfo(JNIEnv* env, const ac::core::Processor& processor, const std::string& gpu)
{
    if (!gJavaVM || !ensureJavaCallbacks(env)) return;
    std::string info = "推理后端 ";
    info += processor.typeName() ? processor.typeName() : "Unknown";
    if (processor.type() == ac::core::Processor::OpenCL && !gpu.empty())
        info += "，GPU " + gpu;
    jstring jInfo = env->NewStringUTF(info.c_str());
    env->CallStaticVoidMethod(gAnime4kProcessorClass, gOnNativeInfoMethod, jInfo);
    env->DeleteLocalRef(jInfo);
}

// 查询当前可用的 OpenCL GPU 设备名（如 "Mali-G610 MC6 r0p0"）。
// 通过 opencl_loader 转发的 cl* 符号查询，失败返回空字符串。
static std::string queryOpenCLGPUName()
{
    cl_uint numPlatforms = 0;
    if (clGetPlatformIDs(0, nullptr, &numPlatforms) != CL_SUCCESS || numPlatforms == 0)
        return {};
    std::vector<cl_platform_id> platforms(numPlatforms);
    if (clGetPlatformIDs(numPlatforms, platforms.data(), nullptr) != CL_SUCCESS)
        return {};
    for (auto platform : platforms)
    {
        cl_uint numDevices = 0;
        if (clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, nullptr, &numDevices) != CL_SUCCESS || numDevices == 0)
            continue;
        cl_device_id device{};
        if (clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 1, &device, nullptr) != CL_SUCCESS)
            continue;
        size_t size = 0;
        if (clGetDeviceInfo(device, CL_DEVICE_NAME, 0, nullptr, &size) != CL_SUCCESS || size == 0)
            continue;
        std::string name(size, '\0');
        if (clGetDeviceInfo(device, CL_DEVICE_NAME, size, name.data(), nullptr) == CL_SUCCESS)
            return name;
    }
    return {};
}

// 处理单张图片。
// 返回格式：
//   成功: "OK|<backend>|<gpuName>"   backend = CPU / OpenCL；gpuName 为空表示 CPU
//   失败: "ERR|<error message>"
extern "C" JNIEXPORT jstring JNICALL
Java_com_tumuyan_ncnn_realsr_Anime4kProcessor_process(
    JNIEnv* env, jclass,
    jstring jInput, jstring jOutput,
    jstring jModel, jstring jProcessor,
    jint jDevice, jdouble jFactor)
{
    const char* input = env->GetStringUTFChars(jInput, nullptr);
    const char* output = env->GetStringUTFChars(jOutput, nullptr);
    const char* model = env->GetStringUTFChars(jModel, nullptr);
    const char* processorName = env->GetStringUTFChars(jProcessor, nullptr);
    const int device = static_cast<int>(jDevice);
    const double factor = static_cast<double>(jFactor);

    std::string result;
    do
    {
        auto processor = ac::core::Processor::create(processorName, device, model);
        if (!processor)
        {
            result = "ERR|Processor create failed";
            break;
        }
        if (!processor->ok())
        {
            result = std::string("ERR|Processor init failed: ") + processor->error();
            break;
        }

        // 处理开始前最先打印推理后端与 GPU 型号
        {
            std::string gpu;
            if (processor->type() == ac::core::Processor::OpenCL)
                gpu = queryOpenCLGPUName();
            reportInitialInfo(env, *processor, gpu);
        }

        // 注册进度回调：core 在 2x 放大阶段调用，转发到 Java 的 onNativeProgress；
        // 已请求取消时不再转发（core 回调为 void，无法中断，处理完成后再报告取消）
        processor->setProgressCallback([env](const int current, const int total) {
            if (gCancelled.load()) return;
            if (!gJavaVM || !ensureJavaCallbacks(env)) return;
            env->CallStaticVoidMethod(gAnime4kProcessorClass, gOnNativeProgressMethod, current, total);
        });

        auto src = ac::core::imread(input, ac::core::IMREAD_UNCHANGED);
        if (src.empty())
        {
            result = "ERR|Failed to load image";
            break;
        }

        auto dst = processor->process(src, factor);
        if (!processor->ok())
        {
            result = std::string("ERR|") + processor->error();
            break;
        }

        // 处理期间请求了取消: 不写输出文件, 返回取消状态(避免误报成功)
        if (gCancelled.load())
        {
            result = "ERR|cancelled";
            break;
        }

        if (!ac::core::imwrite(output, dst))
        {
            result = "ERR|Failed to save image";
            break;
        }

        // 成功：返回实际推理后端与 GPU 型号
        std::string backend = processor->typeName() ? processor->typeName() : "Unknown";
        std::string gpu;
        if (processor->type() == ac::core::Processor::OpenCL)
            gpu = queryOpenCLGPUName();
        result = "OK|" + backend + "|" + gpu;
    } while (false);

    env->ReleaseStringUTFChars(jInput, input);
    env->ReleaseStringUTFChars(jOutput, output);
    env->ReleaseStringUTFChars(jModel, model);
    env->ReleaseStringUTFChars(jProcessor, processorName);

    return env->NewStringUTF(result.c_str());
}
