//
// Created by Yazii on 2025/3/16.
//
#include "mnnsr.h"
#include <thread>

// 切块阶段耗时统计(诊断用): 开启后 process 结束打印 load/infer/post 各阶段累计耗时,
// 用于真机确认"切块加载时间"占比。默认关闭。
// #define MNN_LOAD_PROFILE 1

#include "MNN/ErrorCode.hpp"
#define MNN_USER_SET_DEVICE
#include "MNN/MNNSharedContext.h"
#include "MNN/MNNForwardType.h" // MNN_GPU_TUNING_* / MNN_GPU_MEMORY_IMAGE 常量


#include <opencv2/opencv.hpp>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <vector>
#include <cstdio> // For fprintf
#include <dlfcn.h> // For dlopen (Vulkan 驱动可用性检测)

using namespace MNN;

MNNSR::MNNSR(int color_type, int decensor_mode) {
    this->decensor_mode = decensor_mode;
    if (decensor_mode > 0) {
        dcp = new DCP();
        return;
    }
    color = static_cast<ColorType>(color_type);
    // 统一用 Config 创建 ImageProcess: wrap=ZERO 使 convert 越界采样填 setPadding 值,
    // 配合 setMatrix 平移可从原图直接裁剪+padding(切块加载优化), 与 legacy 的
    // copyMakeBorder(BORDER_CONSTANT, 0) 填充语义一致。
    // 用旧版 5 参 API 创建 ImageProcess(BGR->目标, /255 归一化)。
    // 此前改用 Config API(create(Config)) 后在设备(Android libMNN)上 convert 输出全 0
    // (本机 Linux libMNN 正常), 旧 API 与 5c79fad 时代(设备验证正常)一致。
    // meanVals_ = {0,0,0}, normVals_ = {1/255,1/255,1/255}(见 mnnsr.h)。
    if (color == ColorType::RGB)
        pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(
                MNN::CV::ImageProcess::create(MNN::CV::BGR, MNN::CV::RGB, meanVals_, 3, normVals_, 3));
    else if (color == ColorType::GRAY || color == ColorType::Gray2YCbCr ||
             color == ColorType::Gray2YUV) {
        model_channel = 1;
        pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(
                MNN::CV::ImageProcess::create(MNN::CV::BGR, MNN::CV::GRAY, meanVals_, 3, normVals_, 3));
    } else if (color == ColorType::YCbCr) {
        pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(
                MNN::CV::ImageProcess::create(MNN::CV::BGR, MNN::CV::YCrCb, meanVals_, 3, normVals_, 3));
    } else if (color == ColorType::YUV) {
        pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(
                MNN::CV::ImageProcess::create(MNN::CV::BGR, MNN::CV::YUV, meanVals_, 3, normVals_, 3));
    } else {
        fprintf(stderr, "color space error\n");
        exit(1);
    }
    pretreat_->setPadding(0);
}

MNNSR::~MNNSR() {
    if (decensor_mode > 0) {
        dcp->~DCP();
        return;
    }
    MNN::Tensor::destroy(input_tensor);
    MNN::Tensor::destroy(output_tensor);
    interpreter->releaseSession(session);
    interpreter->releaseModel();
    MNN::Interpreter::destroy(interpreter);
}

void MNNSR::setProgressCallback(std::function<bool(int, int, int, int)> cb) {
    progressCallback_ = std::move(cb);
}

void MNNSR::setInfoCallback(std::function<void(const std::string&)> cb) {
    infoCallback_ = std::move(cb);
}

// GPU 后端的 cache/tuned 文件后缀: MNN 的 cache 内容与后端绑定, OpenCL/Vulkan
// 交替写同一个 .cache 会在另一后端加载时崩溃, 因此按后端分文件。
// CPU/AUTO/其他返回空后缀, 保持历史 ".cache"/".tuned" 文件名兼容。
static std::string gpuBackendTag(MNNForwardType t) {
    if (t == MNN_FORWARD_OPENCL) return ".cl";
    if (t == MNN_FORWARD_VULKAN) return ".vk";
    return "";
}

#if _WIN32
#include <codecvt>
int MNNSR::load(const std::wstring& modelpath, bool cachemodel,const bool nchw)
#else

int MNNSR::load(const std::string &modelpath, bool cachemodel,const bool nchw)
#endif
{

    if (decensor_mode > 0) {
        return dcp->load(modelpath, cachemodel, nchw);
    }

    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    backendConfig.memory = MNN::BackendConfig::Memory_High;
    backendConfig.power = MNN::BackendConfig::Power_High;
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
//    backendConfig.precision = MNN::BackendConfig::Precision_High;

    //MNNDeviceContext gpuDeviceConfig;
    //// CUDA Backend support user set device_id
    //if (backend_type == MNN_FORWARD_CUDA) {
    //    gpuDeviceConfig.deviceId = 0;
    //    backendConfig.sharedContext = &gpuDeviceConfig;
    //}
    //// OpenCL Backend support user set platform_size, platform_id, device_id
    //if (backend_type == MNN_FORWARD_OPENCL) {
    //    gpuDeviceConfig.platformSize = 1;// GPU Cards number
    //    gpuDeviceConfig.platformId = 1;  // Execute on Which GPU Card
    //    gpuDeviceConfig.deviceId = 0;    // Execute on Which GPU device
    //    backendConfig.sharedContext = &gpuDeviceConfig;
    //}

    config.backendConfig = &backendConfig;
//    config.type = MNN_FORWARD_NN;
//    config.type = MNN_FORWARD_VULKAN;
//    config.type = MNN_FORWARD_OPENCL;
//    config.type = MNN_FORWARD_AUTO;
    // Vulkan 驱动可用性检测: app 进程 dlopen libvulkan.so 可能因 linker namespace 失败,
    // 若不可用则自动降级 CPU, 避免 createSession 在 Vulkan 后端空指针崩溃(SIGSEGV)。
    // (经 nsrun/libnspatch 包装运行时命名空间不受限, 该检测通常能通过)
    if (backend_type == MNN_FORWARD_VULKAN) {
        void* vkLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (nullptr == vkLib) {
            fprintf(stderr, "Vulkan driver unavailable (dlopen libvulkan.so failed), fallback to CPU\n");
            if (infoCallback_) infoCallback_("Vulkan 驱动不可用, 已自动回退 CPU");
            backend_type = MNN_FORWARD_CPU;
        } else {
            dlclose(vkLib);
        }
    }
    // OpenCL 可用性检测: 设备无 OpenCL 库时自动降级 CPU, 避免 createSession 失败
    // (与 Vulkan 检测对称; 有 nsrun 包装时 vendor libOpenCL.so 可正常加载)
    if (backend_type == MNN_FORWARD_OPENCL) {
        void* clLib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        if (nullptr == clLib) {
            fprintf(stderr, "OpenCL driver unavailable (dlopen libOpenCL.so failed), fallback to CPU\n");
            if (infoCallback_) infoCallback_("OpenCL 驱动不可用, 已自动回退 CPU");
            backend_type = MNN_FORWARD_CPU;
        } else {
            dlclose(clLib);
        }
    }
    config.type = backend_type;
    //config.mode = MNN_GPU_TUNING_HEAVY | MNN_GPU_MEMORY_BUFFER;
	if (backend_type == MNN_FORWARD_AUTO)
		config.backupType = MNN_FORWARD_CPU;
	else
        config.backupType = MNN_FORWARD_AUTO;
    int num_threads = std::thread::hardware_concurrency();
    if (backend_type==0) {
        if (num_threads > 1)
            config.numThread = num_threads;
    } else {
        // GPU 后端: union 里 numThread 实际是 mode(见 MNNForwardType.h 的 MNNGpuMode)。
        // MNN_GPU_MEMORY_IMAGE | MNN_GPU_TUNING_WIDE —— WIDE 调优每算子跑 GPU benchmark
        // 选最优 LWS, 大模型首跑极慢且 GPU 满载(用户体验为卡死), 仅 tuneMode=1 时用。
        // MNN_GPU_MEMORY_IMAGE | MNN_GPU_TUNING_NONE —— 跳过调优, 首跑只编译 kernel,
        // 各设备首次运行快速可用(默认)。推理性能略低于调优后, 但可接受。
        config.numThread = tuneMode
                ? (MNN_GPU_MEMORY_IMAGE | MNN_GPU_TUNING_WIDE)
                : (MNN_GPU_MEMORY_IMAGE | MNN_GPU_TUNING_NONE);
    }

    fprintf(stderr, "set backend: %s, color type: %s, cpu: %d\n", get_backend_name(config.type).c_str(),
            colorTypeToStr(color), num_threads);

    const auto start = std::chrono::high_resolution_clock::now();

#if _WIN32
    interpreter = MNN::Interpreter::createFromFile(std::wstring_convert<std::codecvt_utf8<wchar_t>>().to_bytes(modelpath).c_str());
#else
    interpreter = MNN::Interpreter::createFromFile((modelpath).c_str());
#endif


    if (interpreter == nullptr) {
        fprintf(stderr, "interpreter null (模型文件损坏或无法解析)\n");
        return -1;   // 必须返回: 继续 createSession 会在部分后端(MNN Vulkan)内部空指针崩溃
    }

    this->cachemodel = cachemodel;
#if _WIN32
    this->modelpath_ = std::wstring_convert<std::codecvt_utf8<wchar_t>>().to_bytes(modelpath);
#else
    this->modelpath_ = modelpath;
#endif
    if (cachemodel) {

#if _WIN32
        std::string cachefile = std::wstring_convert<std::codecvt_utf8<wchar_t>>().to_bytes(modelpath + L".cache");
#else
        // cache 按后端分文件(仅 GPU 后端加后缀): MNN 的 cache 内容与后端绑定,
        // OpenCL/Vulkan 交替写同一个 .cache 会在另一后端加载时崩溃(段错误)。
        // CPU 后端保持无后缀 ".cache", 兼容历史缓存。
        std::string cachefile = modelpath + ".cache" + gpuBackendTag(backend_type);
#endif
        interpreter->setCacheFile(cachefile.c_str());
    }

    // OpenCL 调优进度回调(全局 API, MNN 修改版): 开启 WIDE 调优(tuneMode=1)时, MNN 在
    // createSession/resize 阶段就开始逐算子做 GPU benchmark, 通过全局回调上报"已优化算子/总数"。
    // 必须在 createSession 之前注册, 否则首阶段调优进度丢失。
    // CLI 场景: 首次回调时输出 TUNING| 状态行(GUI 据此显示"正在调优", 仅实际调优时打印,
    // 缓存命中二次运行时无调优则不输出)与 TUNE_PROGRESS: done/total 机器进度行
    // (GUI 复用 PROGRESS 通道显示百分比, 覆盖式不刷屏)。
    MNN::setGpuTuneProgressCallback([this](int done, int total) {
        if (!tuningReported_) {
            tuningReported_ = true;
            fprintf(stderr, "TUNING|模型算子调优中(首次运行较慢, 进度见下)...\n");
        }
        fprintf(stderr, "TUNE_PROGRESS: %d/%d\n", done, total);
        if (progressCallback_) {
            progressCallback_(done, total, 0, 0);
        }
    });

    // 可能对某些硬件取得正确推理结果有帮助
    //interpreter->setSessionHint(Interpreter::GEOMETRY_COMPUTE_MASK, 0);

    session = interpreter->createSession(config);
    if (session == nullptr) {
        fprintf(stderr, "session null (后端创建会话失败)\n");
        return -1;   // 必须返回: 空 session 后续 resizeSession/推理会崩溃
    }

    interpreter_input = interpreter->getSessionInput(session, nullptr);
    auto dims = interpreter_input->shape();
	if (dims.size() != 4) {
		fprintf(stderr, "model input tensor shape error, expect 4 dims, but got %zu\n", dims.size());
		return -1;
	}
	else if (dims[2] > 0 && dims[3] > 0 && dims[2] == dims[3]) {
		if (dims[2] != tilesize) {
			fprintf(stderr, "fix tilesize %d -> %d, model input shape:[%d, %d, %d, %d]\n", tilesize, dims[2], dims[0], dims[1], dims[2], dims[3]);
			tilesize = dims[2];
		}
	}

//    fprintf(stderr, "model input tensor(b/c/h/w): %d/%d/%d/%d -> 1/%d/%d/%d\n"
//            , input_tensor->batch(), input_tensor->channel(), input_tensor->height(), input_tensor->width()
//            ,model_channel, tilesize, tilesize
//            );
    interpreter->resizeTensor(interpreter_input, 1, model_channel, tilesize, tilesize);
    interpreter->resizeSession(session);
    interpreter_output = interpreter->getSessionOutput(session, nullptr);

    if (nchw) {
        input_tensor = new MNN::Tensor(interpreter_input, MNN::Tensor::CAFFE);
        output_tensor = new MNN::Tensor(interpreter_output, MNN::Tensor::CAFFE);
    } else {
        input_tensor = new MNN::Tensor(interpreter_input, MNN::Tensor::TENSORFLOW);
        output_tensor = new MNN::Tensor(interpreter_output, MNN::Tensor::TENSORFLOW);
    }
    nchw_ = nchw;   // 记录布局标志, probeMaxInputSize/setInputSize 重建 host tensor 时保持一致

    input_buffer = input_tensor->host<float>();
    output_buffer = output_tensor->host<float>();

    float memoryUsage = 0.0f;
    interpreter->getSessionInfo(session, MNN::Interpreter::MEMORY, &memoryUsage);
    float flops = 0.0f;
    interpreter->getSessionInfo(session, MNN::Interpreter::FLOPS, &flops);
    MNNForwardType backendType[2];
    interpreter->getSessionInfo(session, MNN::Interpreter::BACKENDS, backendType);

    if (cachemodel)
        interpreter->updateCacheFile(session);

	auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::high_resolution_clock::now() - start);
	fprintf(stderr, "load model %.3f s, session memory %sB, flops %s, "
		, static_cast<double>(duration.count()) / 1000
		, float2str(memoryUsage, 6).c_str()
		, float2str(flops, 6).c_str()
	);


    if (backendType[0] == MNN_FORWARD_CPU)
        fprintf(stderr, "backend: CPU, numThread=%d\n", config.numThread);
    else
        fprintf(stderr, "backend: %s, %s\n", get_backend_name(backendType[0]).c_str(),
                get_backend_name(backendType[1]).c_str());

    return 0;
}

int MNNSR::setInputSize(int n) {
    if (!interpreter || !session) return -1;
    try {
        interpreter->resizeTensor(interpreter_input, 1, model_channel, n, n);
        interpreter->resizeSession(session);
        interpreter_input = interpreter->getSessionInput(session, nullptr);
        interpreter_output = interpreter->getSessionOutput(session, nullptr);
        MNN::Tensor* newInput = nullptr;
        MNN::Tensor* newOutput = nullptr;
        if (nchw_) {
            newInput = new MNN::Tensor(interpreter_input, MNN::Tensor::CAFFE);
            newOutput = new MNN::Tensor(interpreter_output, MNN::Tensor::CAFFE);
        } else {
            newInput = new MNN::Tensor(interpreter_input, MNN::Tensor::TENSORFLOW);
            newOutput = new MNN::Tensor(interpreter_output, MNN::Tensor::TENSORFLOW);
        }
        // 分配成功后再销毁旧 tensor: 若 new 抛异常(bad_alloc), 旧指针保持有效, 不会悬垂/双重释放
        MNN::Tensor::destroy(input_tensor);
        MNN::Tensor::destroy(output_tensor);
        input_tensor = newInput;
        output_tensor = newOutput;
        input_buffer = input_tensor->host<float>();
        output_buffer = output_tensor->host<float>();
        tilesize = static_cast<uint>(n);
        fprintf(stderr, "setInputSize: session input resized to %d x %d\n", n, n);
        return 0;
    } catch (const std::exception &e) {
        fprintf(stderr, "setInputSize: exception %s\n", e.what());
        return -1;
    }
}

cv::Mat MNNSR::TensorToCvMat(void) {
    interpreter_output->copyToHostTensor(output_tensor);
    int C = output_tensor->channel();
    int H = output_tensor->height();
    int W = output_tensor->width();
    float *data = output_tensor->host<float>();

    cv::Mat result;
    if (C == 1) {
        // 灰度模式直接处理
        cv::Mat gray = cv::Mat(H, W, CV_32FC1, data);
        gray.convertTo(gray, CV_8UC1, 255.0);
        cv::cvtColor(gray, result, cv::COLOR_GRAY2BGR);
        return result;
    } else {
        // 处理彩色图像
        std::vector<cv::Mat> channels;
        for (int i = 0; i < C; i++) {
            channels.emplace_back(H, W, CV_32FC1, data + i * H * W);
        }

        // 合并通道（注意OpenCV默认是BGR顺序）
        if (color == RGB) {
            // 如果是RGB输入，需要交换R和B通道
            std::swap(channels[0], channels[2]); // RGB -> BGR
            cv::merge(channels, result);
        } else {
            // 先合并为RGB格式
            cv::Mat rgb;
            cv::merge(channels, rgb);
            rgb.convertTo(rgb, CV_8UC3, 255.0);

            // 转换为目标颜色空间
            if (color == YCbCr) {
                cv::cvtColor(rgb, result, cv::COLOR_BGR2YCrCb);
            } else if (color == YUV) {
                cv::cvtColor(rgb, result, cv::COLOR_BGR2YUV);
            }
            return result;
        }

        // 转换为8位
        result.convertTo(result, CV_8UC3, 255.0);
    }

    return result;
}

// In mnnsr.cpp
// Replace the entire MNNSR::process function with this new version.

int MNNSR::process(const cv::Mat& inimage, cv::Mat& outimage, const cv::Mat& mask) {
    int skiped_tile = 0;
    cv::Mat inMask;
    if (mask.empty()) {
        inMask = cv::Mat();
    }
    else {
        cv::resize(mask, inMask, inimage.size(), 0, 0, cv::INTER_AREA);
    }

    int inWidth = inimage.cols;
    int inHeight = inimage.rows;

    int outWidth = inWidth * scale;
    int outHeight = inHeight * scale;

    // 等宽切块 (5c79fad 语义):
    // 1) 固定 tile 输入尺寸 = tilesize(含 2*prepadding), 有效区 tileWidth = tilesize - 2*prepadding;
    // 2) xtiles = ceil(inWidth/tileWidth), 所有 tile 等宽(不削边缘像素);
    // 3) 余数通过重新分配 prepadding 吸收: xtiles*(tilesize-2*preP) + preP = inWidth,
    //    使最后一个 tile 的有效区多出 preP, 完整覆盖边缘像素, 且每个 tile 输入都是满 tilesize(无黑边浪费)。
    // 背景: 634df5d 引入的 computeTileSizes 变宽切分(中心大块)削减边缘 tile,
    // 边缘 tile 有效区被削小/输入非满幅, 推理拼接后出现条状条纹(9~14dB vs 正常 26dB);
    // 3a047f7 已等宽化但仍是"前 n-1 满块 + 尾部余数小块"(480 -> [108,108,108,108,48]),
    // 尾块有效区过小(48px)且输入仅 68px(大量黑边), 边缘仍被切像素。本实现完全回退 5c79fad 语义。
    int tileWidth = tilesize - prepadding * 2;
    int tileHeight = tilesize - prepadding * 2;

    uint xtiles = (inWidth + tileWidth - 1) / tileWidth;
    uint ytiles = (inHeight + tileHeight - 1) / tileHeight;

    int xPrepadding = prepadding, yPrepadding = prepadding;

    // 待重新分配的像素数(水平)
    int left = inWidth % tileWidth;
    if (xtiles > 1 && left > 0) {
        if (left < prepadding) {
            // 倒数第2个tile的prepadding已经包含了推理结果
            xtiles--;
        }
        else {
            if ((left + 1) / 2 <= prepadding)
                xtiles--;
            // xtiles * (tilesize - 2 * xPrepadding) + xPrepadding = inWidth
            int xPrepaddingCand = (xtiles * tilesize - inWidth) / (2 * xtiles - 1);
            // 余量守卫: 当 inWidth 接近 tilesize 的整数倍时, 重分配会把 prepadding 撑爆
            // (枚举验证 inWidth=129..138,237..305,... 时 xPrepadding 达 15~42, 远超配置值),
            // 使重叠带/源越界扩大, 边缘 tile 有效区过小。此时放弃重分配,
            // 回退为原始 prepadding + tileWidth(=tilesize-2*prepadding), 块数保持 ceil(inWidth/tileWidth)
            // 的 5c79fad 语义, 最后一块吸收余数(见下方 out_tile_w 分支), 保证最少块数且不浪费黑边。
            if (xPrepaddingCand > prepadding) {
                xPrepadding = prepadding;
                tileWidth = tilesize - prepadding * 2;
            }
            else {
                xPrepadding = xPrepaddingCand;
                tileWidth = tilesize - xPrepadding * 2;
            }
        }
    }
    // 待重新分配的像素数(垂直)
    left = inHeight % tileHeight;
    if (ytiles > 1 && left > 0) {
        if (left < prepadding) {
            // 倒数第2个tile的prepadding已经包含了推理结果
            ytiles--;
        }
        else {
            if ((left + 1) / 2 <= prepadding)
                ytiles--;
            // ytiles * (tilesize - 2 * yPrepadding) + yPrepadding = inHeight
            int yPrepaddingCand = (ytiles * tilesize - inHeight) / (2 * ytiles - 1);
            if (yPrepaddingCand > prepadding) {
                yPrepadding = prepadding;
                tileHeight = tilesize - prepadding * 2;
            }
            else {
                yPrepadding = yPrepaddingCand;
                tileHeight = tilesize - yPrepadding * 2;
            }
        }
    }

    // 等宽数组(供既有 colOffs/rowOffs 循环结构复用)
    std::vector<int> colWidths(xtiles, tileWidth);
    std::vector<int> rowHeights(ytiles, tileHeight);
    std::vector<int> colOffs(xtiles + 1, 0), rowOffs(ytiles + 1, 0);
    for (uint i = 0; i < xtiles; i++) colOffs[i + 1] = colOffs[i] + colWidths[i];
    for (uint i = 0; i < ytiles; i++) rowOffs[i + 1] = rowOffs[i] + rowHeights[i];

    fprintf(stderr,
        "process tiles: %d x %d, tilesize: %d -> %d %d, prepadding: %d -> %d %d\n",
        xtiles, ytiles, tilesize, tileWidth, tileHeight, prepadding, xPrepadding, yPrepadding);

    high_resolution_clock::time_point begin = high_resolution_clock::now();
    high_resolution_clock::time_point time_print_progress;
#ifdef MNN_LOAD_PROFILE
    double prof_load = 0, prof_infer = 0, prof_post = 0;   // 切块阶段累计耗时(秒): 加载/推理/后处理
#endif

    // 重叠区线性权重混合: 相邻 tile 在 padding 输出重叠带按权重平滑过渡,
    // 消除硬拼接产生的 tile 边界伪影(参照 GeoAI smooth inference 思路)。
    // accum 累积各 tile 的加权输出, weightMap 累积权重, 结束后归一化。
    // 经真机验证: 羽化路径在渐变图上产生密集横向条纹(seam_rows=238, 旧代码117),
    // 而 5c79fad/Real-ESRGAN 官方的"padding 上下文 + 有效区硬裁剪"是干净方案。
    // 故默认关闭羽化(doBlend=false), 走硬拷贝; 保留本段作为可开关的实验路径。
    bool doBlend = false;   // 如需重新启用羽化改为 true
    cv::Mat accum;
    cv::Mat weightMap;
    if (doBlend) {
        accum.create(outHeight, outWidth, CV_32FC3);
        weightMap.create(outHeight, outWidth, CV_32FC1);
        accum.setTo(0);
        weightMap.setTo(0);
    }

    //    cv::Mat imageOut(outHeight, outWidth, inimage.type()); // 填充灰色背景

    for (uint yi = 0; yi < ytiles; yi++) {
        // 从inimage中裁剪出含padding的tile （但是四边的tile需要再次padding）
        int tileH = rowHeights[yi];
        int in_tile_y0 = (rowOffs[yi] - yPrepadding);
        if (in_tile_y0 < 0)
            in_tile_y0 = 0;
        int in_tile_y1 = (rowOffs[yi + 1] + yPrepadding);
        if (in_tile_y1 > inHeight)
            in_tile_y1 = inHeight;
        // 从tile推理结果去除padding部分
        int out_tile_y0 = scale * yPrepadding;

        // 绘制到outimage的位置
        int out_y0 = rowOffs[yi] * scale;
        // 5c79fad 语义: 最后一块有效区吸收余数(xtiles*tileWidth+xPrepadding=inWidth)
        int out_tile_h = (yi + 1 == ytiles) ? inHeight * scale - out_y0 : tileH * scale;

        for (uint xi = 0; xi < xtiles; xi++) {
            int tileW = colWidths[xi];
#ifdef MNN_LOAD_PROFILE
            high_resolution_clock::time_point tp_stage = high_resolution_clock::now();
#endif

            if (!inMask.empty()) {
                // 5c79fad 语义: 最后一块 mask 尺寸吸收余数(inWidth-colOffs[xi])
                int x0 = colOffs[xi], x = (xi + 1 == xtiles) ? inWidth - colOffs[xi] : tileW;
                int y0 = rowOffs[yi], y = (yi + 1 == ytiles) ? inHeight - rowOffs[yi] : tileH;
                cv::Mat maskTile = inMask(cv::Rect(x0, y0, x, y));

                // 判断maskTile是否全部为0
                if (cv::countNonZero(maskTile) == 0) {
                    // 如果maskTile全部为0，跳过该tile
                    cv::Mat inputTile = inimage(
                        cv::Rect(x0, y0, x, y));
                    cv::Mat outputTile;
                    cv::resize(inputTile, outputTile, cv::Size(x * scale, y * scale), 0, 0,
                        cv::INTER_CUBIC);

                    outputTile.copyTo(
                        outimage(cv::Rect(x0 * scale, y0 * scale, outputTile.cols,
                            outputTile.rows)));
                    skiped_tile++;
                    continue;
                }
            }


            // 从inimage中裁剪出含padding的tile （但是四边的tile需要再次padding）
            int in_tile_x0 = (colOffs[xi] - xPrepadding);
            if (in_tile_x0 < 0)
                in_tile_x0 = 0;
            int in_tile_x1 = (colOffs[xi + 1] + xPrepadding);
            if (in_tile_x1 > inWidth)
                in_tile_x1 = inWidth;

            // 从tile推理结果去除padding部分
            int out_tile_x0 = scale * xPrepadding;

            // 绘制到outimage的位置
            int out_x0 = colOffs[xi] * scale;
            // 5c79fad 语义: 最后一块有效区吸收余数(xtiles*tileWidth+xPrepadding=inWidth)
            int out_tile_w = (xi + 1 == xtiles) ? inWidth * scale - out_x0 : tileW * scale;

            if (load_opt >= 1 && inimage.isContinuous()) {
                // 优化路径: 用 ImageProcess 矩阵平移 + wrap=ZERO, 直接从原图裁剪+padding 一步 convert,
                // 消除 inputTile/paddedTile 中间 Mat 的分配与全量拷贝(降低切块加载时间)。
                int t = (yi == 0) ? yPrepadding : 0;
                int l = (xi == 0) ? xPrepadding : 0;
                MNN::CV::Matrix m;
                // MNN ImageProcess::convert 用矩阵直接映射(实测验证): 采样 src = dest*sx + tx。
                // 要 dest(x) 采样原图 (in_tile_x0 - l + x), 则 tx = in_tile_x0 - l。
                // 注意: 此前误用 tx = l - in_tile_x0(逆矩阵语义), 导致非首 tile 采样全 0/错位。
                m.setScaleTranslate(1.f, 1.f, (float)(in_tile_x0 - l), (float)(in_tile_y0 - t));
                pretreat_->setMatrix(m);
                pretreat_->convert((const uint8_t*)inimage.data, inWidth, inHeight,
                    inimage.cols * inimage.channels(), input_tensor);
            } else {
                cv::Mat inputTile = inimage(cv::Rect(in_tile_x0, in_tile_y0, in_tile_x1 - in_tile_x0,
                    in_tile_y1 - in_tile_y0));

                cv::Mat paddedTile;
                if (inputTile.cols < tilesize || inputTile.rows < tilesize) {
                    int t = (yi == 0) ? yPrepadding : 0;
                    int b = tilesize + in_tile_y0 - in_tile_y1 - t;
                    int l = (xi == 0) ? xPrepadding : 0;
                    int r = tilesize + in_tile_x0 - in_tile_x1 - l;
                    cv::copyMakeBorder(inputTile, paddedTile, t, b, l, r, cv::BORDER_CONSTANT);

                    pretreat_->convert(paddedTile.data, paddedTile.cols, paddedTile.rows,
                        paddedTile.cols * paddedTile.channels(),
                        input_tensor);
                } else {
                    // 优化1: 内部 tile 尺寸已为 tilesize, 跳过空 copyMakeBorder(0,0,0,0) 的全量拷贝。
                    // ROI 的行距必须传 step(=整图行距), 传 cols*channels 会让 convert 按错位步长
                    // 采样出剪切错乱的模型输入(表现为内部 tile 密集条纹伪影)。
                    pretreat_->convert(inputTile.data, inputTile.cols, inputTile.rows,
                        (int)inputTile.step[0],
                        input_tensor);
                }
            }

            bool r = interpreter_input->copyFromHostTensor(input_tensor);
#ifdef MNN_LOAD_PROFILE
            prof_load += duration_cast<duration<double>>(high_resolution_clock::now() - tp_stage).count();
            tp_stage = high_resolution_clock::now();
#endif

            interpreter->runSession(session);
#ifdef MNN_LOAD_PROFILE
            prof_infer += duration_cast<duration<double>>(high_resolution_clock::now() - tp_stage).count();
            tp_stage = high_resolution_clock::now();
#endif
            cv::Mat outputTile = TensorToCvMat();


            if (!scale_checked) {
                if(scale< 1e-5){
                    fprintf(stderr, "[err] Invalid scale value: %d\n", scale);
                    return -1;
				}

                // paddedTile 经 copyMakeBorder 补边后尺寸恒为 tilesize(优化路径不创建 paddedTile), 用 tilesize 等价替代
                if (outputTile.cols != tilesize * scale || outputTile.rows != tilesize * scale) {
                    float actual_model_scale = static_cast<float>(outputTile.cols) / static_cast<float>(tilesize);
                    if (actual_model_scale > 1e-5) { // Avoid division by zero or invalid scale
                        this->interp_scale = static_cast<float>(scale) / actual_model_scale;
                        fprintf(stderr,
                            "\n[warn] Model scale: x%.2f, Target scale: x%d, Apply interp scale x%.2f\n",
                            actual_model_scale, scale, this->interp_scale);
                    }
                }
                scale_checked = true; // Mark as checked to avoid re-calculating
            }

            // Apply interpolation if a scale mismatch was found
            if (std::abs(this->interp_scale - 1.0f) > 1e-5) {
                cv::Mat tempTile;
                // Resize the model's output to match the target scale
                cv::resize(outputTile, tempTile, cv::Size(), this->interp_scale, this->interp_scale, cv::INTER_CUBIC);
                outputTile = tempTile;
            }

            // After potential resizing, the outputTile should have dimensions corresponding to the target 'scale'.
            // Now, we double-check if the final tile size is as expected before cropping.
            if (outputTile.cols != tilesize * scale || outputTile.rows != tilesize * scale) {
                fprintf(stderr,
                    "[err] Post-interpolation tile size is still incorrect. Expected %dx%d, but got %dx%d. Aborting.\n",
                    tilesize * scale, tilesize * scale, outputTile.cols, outputTile.rows);
                return -1; // Critical error if even after correction the size is wrong
            }
            // --- END: MODIFIED LOGIC ---


            cv::Rect cropRect(out_tile_x0, out_tile_y0, out_tile_w, out_tile_h);
            cv::Mat croppedTile = outputTile(cropRect);

            if (!doBlend) {
                // 硬裁剪直接拼 (与 5c79fad / Real-ESRGAN 官方一致):
                // padding 只为给网络提供边缘上下文, 结果只取有效区, 不做跨 tile 重叠加权。
                croppedTile.copyTo(outimage(cv::Rect(out_x0, out_y0, croppedTile.cols, croppedTile.rows)));
            }
            else
            {
                // 重叠带 feathering: 输出矩形在有效区基础上向四周扩展 overlapOut 像素,
                // 与相邻 tile 重叠; 重叠带权重从边缘 0 线性升到有效区 1, 消除拼接缝。
                // 关键修正: 源矩形截断到本 tile 有效区(cropRect), 绝不越过有效区去采模型对
                // padding 带的超分重建(相邻 tile 在接缝处这些预测不一致, 是密集条纹根因)。
                // 越界(有效区外)源自然被截断, 贡献权重为 0; 权重线性 1->0 且无 0.02 地板。
                int overlapOut = prepadding * scale;
                if (overlapOut < 4) overlapOut = 4;
                // 目标(输出图)矩形
                int dstX = out_x0 - std::min(overlapOut, out_x0);
                int dstY = out_y0 - std::min(overlapOut, out_y0);
                int dstX2 = std::min(out_x0 + out_tile_w + overlapOut, outWidth);
                int dstY2 = std::min(out_y0 + out_tile_h + overlapOut, outHeight);
                int dstW = dstX2 - dstX, dstH = dstY2 - dstY;
                // 期望源(outputTile)矩形(有效区 cropRect 起点外扩 overlapOut)
                int srcX0 = (int)out_tile_x0 - (out_x0 - dstX);
                int srcY0 = (int)out_tile_y0 - (out_y0 - dstY);
                // 有效区边界(源不得越过)
                int validX0 = (int)out_tile_x0, validX1 = (int)out_tile_x0 + out_tile_w;
                int validY0 = (int)out_tile_y0, validY1 = (int)out_tile_y0 + out_tile_h;
                // 源截断到有效区 ∩ outputTile 边界
                int srcX = std::max(0, std::max(srcX0, validX0));
                int srcY = std::max(0, std::max(srcY0, validY0));
                int srcX2 = std::min((int)(tilesize * scale), std::min(srcX0 + dstW, validX1));
                int srcY2 = std::min((int)(tilesize * scale), std::min(srcY0 + dstH, validY1));
                if (srcX2 <= srcX || srcY2 <= srcY) {
                    // 兜底: 无有效源, 直接硬拼接
                    croppedTile.copyTo(outimage(cv::Rect(out_x0, out_y0, croppedTile.cols, croppedTile.rows)));
                }
                else {
                    int srcW = srcX2 - srcX, srcH = srcY2 - srcY;
                    // 实际被此源覆盖的 dest 区域(源被截断/前移后, dest 对应右移)
                    int covDstX = dstX + (srcX - srcX0);
                    int covDstY = dstY + (srcY - srcY0);
                    cv::Mat tileSrc = outputTile(cv::Rect(srcX, srcY, srcW, srcH));
                    cv::Mat dstAcc = accum(cv::Rect(covDstX, covDstY, srcW, srcH));
                    cv::Mat dstWm = weightMap(cv::Rect(covDstX, covDstY, srcW, srcH));
                    int leftOverlap = out_x0 - dstX, topOverlap = out_y0 - dstY;
                    int rightOverlap = dstX2 - (out_x0 + out_tile_w);
                    int bottomOverlap = dstY2 - (out_y0 + out_tile_h);
                    for (int yy = 0; yy < srcH; yy++)
                    {
                        float wy = 1.0f;
                        if (topOverlap > 0 && yy < topOverlap)
                            wy = (float)(yy + 1) / (float)(topOverlap + 1);
                        else if (bottomOverlap > 0 && yy >= topOverlap + out_tile_h)
                            wy = (float)(srcH - yy) / (float)(bottomOverlap + 1);
                        for (int xx = 0; xx < srcW; xx++)
                        {
                            float wx = 1.0f;
                            if (leftOverlap > 0 && xx < leftOverlap)
                                wx = (float)(xx + 1) / (float)(leftOverlap + 1);
                            else if (rightOverlap > 0 && xx >= leftOverlap + out_tile_w)
                                wx = (float)(srcW - xx) / (float)(rightOverlap + 1);
                            float w = wx * wy;
                            const cv::Vec3b& s = tileSrc.at<cv::Vec3b>(yy, xx);
                            cv::Vec3f& a = dstAcc.at<cv::Vec3f>(yy, xx);
                            a[0] += (float)s[0] * w;
                            a[1] += (float)s[1] * w;
                            a[2] += (float)s[2] * w;
                            dstWm.at<float>(yy, xx) += w;
                        }
                    }
                }
            }
#ifdef MNN_LOAD_PROFILE
            prof_post += duration_cast<duration<double>>(high_resolution_clock::now() - tp_stage).count();
#endif


            high_resolution_clock::time_point end = high_resolution_clock::now();
            double time_span_print_progress = duration_cast<duration<double>>(
                end - time_print_progress).count();
            float progress_tile = (float)(yi * xtiles + xi + 1);
            // 进度回调（JNI 桥接使用）：每个 tile 完成后上报 (已完成, 总数, 当前tile有效宽, 当前tile有效高)；
            // 返回 false 表示请求取消，提前退出整个处理
            if (progressCallback_ && !progressCallback_(static_cast<int>(progress_tile), static_cast<int>(ytiles * xtiles), tileW, tileH))
            {
                fprintf(stderr, "progress callback cancel requested\n");
                return -1;
            }
            if (time_span_print_progress > 0.5 || (yi + 1 == ytiles && xi + 3 > xtiles)) {
                double progress = progress_tile / (ytiles * xtiles);
                // progress2 用于计算剩余时间，由于跳过的tile不会运行这段函数，因此不会出现分母为0或者分子为0的情况
                double progress2 = (progress_tile - skiped_tile) / (ytiles * xtiles - skiped_tile);
                double time_span = duration_cast<duration<double>>(end - begin).count();
#ifdef __ANDROID__
                fprintf(stderr, "%5.2f%%\t[%5.2fs /%5.2f ETA]\n", progress * 100, time_span,
                    time_span / progress2 - time_span);
#else
                fprintf(stderr, " %5.2f%%\t[%5.2fs /%5.2f ETA]   \r", progress * 100, time_span,
                    time_span / progress2 - time_span);
                fflush(stderr);
#endif

                time_print_progress = end;
            }

        }
    }

#ifndef __ANDROID__
    fprintf(stderr, "                                        \r");
#endif // !__ANDROID__

    // 重叠区加权归一化: 各像素加权累积除以总权重, 消除 tile 边界伪影;
    // 权重为 0 的区域(极端兜底硬拼接)保留 outimage 原值。
    // 全程浮点处理: 归一化 → 浮点钳制到 [0,255] → 四舍五入转 uchar。
    // 避免 (uchar)(int) 整数转换的溢出/舍入误差(白像素 B 通道溢出为 0 → 偏黄/噪点)。
    // 注意: Gray2YUV/Gray2YCbCr 分支随后会整体重写 outimage, 不受影响。
    if (doBlend) {
        for (int yy = 0; yy < outHeight; yy++)
        {
            for (int xx = 0; xx < outWidth; xx++)
            {
                float w = weightMap.at<float>(yy, xx);
                if (w > 0.5f)
                {
                    cv::Vec3f a = accum.at<cv::Vec3f>(yy, xx);
                    cv::Vec3b& o = outimage.at<cv::Vec3b>(yy, xx);
                    float v0 = std::max(0.0f, std::min(255.0f, a[0] / w));
                    float v1 = std::max(0.0f, std::min(255.0f, a[1] / w));
                    float v2 = std::max(0.0f, std::min(255.0f, a[2] / w));
                    o[0] = static_cast<uchar>(v0 + 0.5f);
                    o[1] = static_cast<uchar>(v1 + 0.5f);
                    o[2] = static_cast<uchar>(v2 + 0.5f);
                }
            }
        }
    }


    if (color == Gray2YUV) {
        // 把inimage转为YCbCr格式，放大scale倍，把通道2通道3复制给outimage的通道2通道3
        cv::Mat yuv;
        cv::cvtColor(inimage, yuv, cv::COLOR_BGR2YUV);
        cv::Mat yuv2;
        yuv2.create(inimage.rows * scale, inimage.cols * scale, CV_8UC3);
        cv::resize(yuv, yuv2, cv::Size(inimage.cols * scale, inimage.rows * scale), 0, 0,
            cv::INTER_CUBIC);
        cv::cvtColor(yuv2, outimage, cv::COLOR_YUV2BGR);
    }
    else if (color == Gray2YCbCr) {
        // 把inimage转为YCbCr格式，放大scale倍，把通道2通道3复制给outimage的通道2通道3
        cv::Mat ycc;
        cv::cvtColor(inimage, ycc, cv::COLOR_BGR2YCrCb);
        cv::Mat ycc2;
        ycc2.create(inimage.rows * scale, inimage.cols * scale, CV_8UC3);
        cv::resize(ycc, ycc2, cv::Size(inimage.cols * scale, inimage.rows * scale), 0, 0,
            cv::INTER_CUBIC);
        cv::cvtColor(ycc2, outimage, cv::COLOR_YCrCb2BGR);
    }


#ifdef MNN_LOAD_PROFILE
    fprintf(stderr, "[profile] load=%.3fs infer=%.3fs post=%.3fs (tiles=%ux%u)\n",
            prof_load, prof_infer, prof_post, xtiles, ytiles);
#endif

    // 把 GPU 调优结果写回 cache 文件: WIDE 调优在首次 resizeSession/推理时才真正执行,
    // load() 内的 updateCacheFile 调用发生在调优之前, 此时写入不含调优数据;
    // 推理结束后再写一次, 后续运行才能复用调优结果(秒开)。
    if (cachemodel && interpreter && session) {
        interpreter->updateCacheFile(session);
        // 调优状态标记: 本次实际发生过算子调优(tuningReported_)才写 <model>.mnn.tuned。
        // .cache 文件在调优/未调优时都会生成(几何/权重缓存), GUI 无法仅凭 cache 判断
        // 是否已调优; .tuned 标记文件显式区分两种状态(未调优运行/缓存命中二次运行不写)。
        if (tuningReported_ && !modelpath_.empty()) {
            // .tuned 标记与 cache 同样按后端分文件: 调优结果绑定后端,
            // OpenCL 调优完成不代表 Vulkan 可复用(GUI 按当前后端设置查对应标记)。
            std::string tunedPath = modelpath_ + ".tuned" + gpuBackendTag(backend_type);
            FILE* tf = fopen(tunedPath.c_str(), "w");
            if (tf) {
                fprintf(tf, "tuned\n");
                fclose(tf);
            } else {
                fprintf(stderr, "write tuned marker failed: %s\n", tunedPath.c_str());
            }
        }
    }

    return 0;
}

#include "mnnsr.h"
#include "utils.hpp"
#include <thread>

#include "MNN/ErrorCode.hpp"


#include <opencv2/opencv.hpp>
#include <vector>
/**
 * @brief Detects and removes mosaic censorship from an image.
 *
 * @param inimage Input image (BGR or BGRA).
 * @param outimage Output image with mosaic removed (same format as input).
 * @return 0 on success, -1 on failure or if no mosaic was detected.
 */
int MNNSR::decensor(const cv::Mat &inimage, cv::Mat &outimage, const bool det_box) {

    if (decensor_mode > 0) {
        outimage = dcp->decensor(inimage, inimage, false);
        return 0;
    }


    if (inimage.empty()) {
        fprintf(stderr, "decensor error: Input image is empty.\n");
        return -1;
    }

    // --- Constants (matching detectMosaicResolution logic) ---
    // Kept original names where possible, added comments based on detectMosaicResolution
    const int GBlur = 5;         // Gaussian blur kernel size (must be odd)
    const int CannyTr1 = 8;      // Canny lower threshold
    const int CannyTr2 = 30;     // Canny upper threshold
    const int LowRange = 2;      // Minimum mosaic block size to check
    const int HighRange = 32;    // Maximum mosaic block size to check
    const float DetectionTr = 0.29f; // Threshold for template matching correlation (kept float)

    // Validate Gaussian blur kernel size (from detectMosaicResolution)
    if (GBlur % 2 == 0 || GBlur <= 0) {
        fprintf(stderr,
                "decensor error: GBlur_kernel_size must be a positive odd number. Got %d.\n",
                GBlur);
        // Can't proceed with invalid blur kernel, but detection might still work somewhat without it.
        // Let's just warn for now, as the Canny step might still provide useful input.
        // If we wanted strict failure on bad constants, we would return -1 here.
    }

    // --- 1. Preprocessing for Detection ---
    cv::Mat img_detection_input; // Will be CV_8U after processing
    int original_channels = inimage.channels();

    // Convert input to BGR (or keep BGR if already) for color space conversions
    cv::Mat img_bgr;
    if (original_channels == 4) {
        cv::cvtColor(inimage, img_bgr, cv::COLOR_BGRA2BGR);
    } else if (original_channels == 3) {
        img_bgr = inimage.clone(); // Assuming BGR input
    } else {
        fprintf(stderr,
                "decensor error: Unsupported number of channels (%d). Input must be 3 (BGR) or 4 (BGRA).\n",
                original_channels);
        return -1;
    }

    // Convert to grayscale
    cv::Mat img_gray;
    cv::cvtColor(img_bgr, img_gray, cv::COLOR_BGR2GRAY);

    // Canny edge detection
    cv::Canny(img_gray, img_detection_input, CannyTr1, CannyTr2);

    // Invert edges: non-edges become bright (255), edges become dark (0)
    img_detection_input = 255 - img_detection_input; // In-place inversion

    // Gaussian Blur (on the inverted edges)
    cv::GaussianBlur(img_detection_input, img_detection_input, cv::Size(GBlur, GBlur), 0);

    // --- 2. Pattern Generation and Matching ---

    // Pattern vector size: HighRange + 2. Indices 0 to HighRange+1.
    // We only generate patterns for masksize from LowRange+2 up to HighRange+2.
    // Index for masksize M is M - 2. Relevant indices are [LowRange, HighRange].
    std::vector<cv::Mat> patterns(HighRange + 2);

    // Loop through potential mask sizes, matching detectMosaicResolution's loop range
    // Note: Loop is reversed compared to original decensor, matching detectMosaicResolution
    fprintf(stderr, "decensor: Generating patterns for mask sizes from %d down to %d...\n",
            HighRange + 2, LowRange + 2);
    for (int masksize = HighRange + 2; masksize >= LowRange + 2; --masksize) {
        int pattern_idx = masksize - 2; // Index in patterns vector [LowRange, HighRange]

        // Pattern size calculation from detectMosaicResolution: 2*masksize + 3
        int pattern_size = 2 * masksize + 3;

        // Check if pattern size is larger than the image. If so, it cannot be matched.
        if (pattern_size > img_detection_input.cols || pattern_size > img_detection_input.rows) {
            fprintf(stderr,
                    "decensor warning: Pattern size %d for masksize %d exceeds image dimensions (%dx%d). Skipping pattern creation and matching.\n",
                    pattern_size, masksize, img_detection_input.cols, img_detection_input.rows);
            // patterns[pattern_idx] will remain empty, which is handled below.
            continue;
        }

        // Create CV_8U pattern (Grayscale), matching the type of img_detection_input
        cv::Mat pattern = cv::Mat(pattern_size, pattern_size, CV_8U,
                                  cv::Scalar(255)); // White background

        // Draw black lines (0) with thickness 1, starting at index 2, stepping by masksize - 1
        // This directly implements the drawing logic from detectMosaicResolution
        for (int i = 2; i < pattern_size; i += masksize - 1) {
            cv::line(pattern, cv::Point(i, 0), cv::Point(i, pattern_size - 1), cv::Scalar(0),
                     1); // Vertical line
        }
        for (int j = 2; j < pattern_size; j += masksize - 1) {
            cv::line(pattern, cv::Point(0, j), cv::Point(pattern_size - 1, j), cv::Scalar(0),
                     1); // Horizontal line
        }
        patterns[pattern_idx] = pattern; // Store the created pattern (CV_8U)
    }
    fprintf(stderr, "decensor: Pattern generation complete.\n");


    // Mask to mark detected mosaic regions (CV_8U, 255 = mosaic, 0 = non-mosaic)
    // This mask will be used later to blend the processed region back.
    cv::Mat card_mask = cv::Mat::zeros(inimage.size(), CV_8U);

    // resolutions vector stores the count of matches for each potential masksize.
    // Size HighRange+3 (indices 0 to HighRange+2) initialized to 0.
    // Count for masksize M is stored at index M - 1. Relevant indices [LowRange+1, HighRange+1].
    // This matches the indexing structure used in detectMosaicResolution's analysis section.
    std::vector<int> resolutions(HighRange + 3, 0);

    fprintf(stderr, "decensor: Starting template matching...\n");

    // Loop through potential mask sizes again (matching pattern loop range)
    for (int masksize = HighRange + 2; masksize >= LowRange + 2; --masksize) {
        int pattern_idx = masksize - 2;      // Index in patterns vector [LowRange, HighRange]
        int resolution_idx =
                masksize - 1;   // Index in resolutions vector [LowRange+1, HighRange+1]

        // Ensure indices are within bounds (defensive check)
        if (pattern_idx < 0 || pattern_idx >= patterns.size() ||
            resolution_idx < 0 || resolution_idx >= resolutions.size()) {
            // Should not happen with correct constants and logic, but check anyway
            fprintf(stderr,
                    "decensor Error: Index calculation out of bounds during matching. pattern_idx=%d, resolution_idx=%d. Skipping masksize %d.\n",
                    pattern_idx, resolution_idx, masksize);
            continue;
        }

        // Skip if pattern was not created (e.g., too large for image)
        if (patterns[pattern_idx].empty()) {
            continue;
        }

        cv::Mat templateImg = patterns[pattern_idx]; // Already CV_8U

        int w = templateImg.cols;
        int h = templateImg.rows;

        // Ensure template is smaller than or equal to the image dimensions (redundant with pattern creation check, but safe)
        if (w > img_detection_input.cols || h > img_detection_input.rows) {
            // Should not happen if patterns[pattern_idx].empty() check works, but safe
            continue;
        }

        cv::Mat img_detection_result; // Result of matchTemplate (CV_32F)
        // Match the processed image (inverted, blurred edges) against the pattern (dark lines on white)
        cv::matchTemplate(img_detection_input, templateImg, img_detection_result,
                          cv::TM_CCOEFF_NORMED);

        // Threshold the result to find locations above the detection threshold
        cv::Mat detection_locations_mask; // CV_8U, 255 for matches, 0 otherwise
        cv::threshold(img_detection_result, detection_locations_mask, DetectionTr, 255,
                      cv::THRESH_BINARY);

        // Find all points that are above the threshold
        std::vector<cv::Point> points;
        cv::findNonZero(detection_locations_mask, points);

        int rects = points.size(); // Count of good matches for this masksize
        resolutions[resolution_idx] = rects; // Store count at index masksize - 1

        // Draw detected rectangles onto the card_mask (for visualization/masking later)
        // Draw white filled rectangles (255) on the CV_8U mask
        for (const auto &pt: points) {
            // Check bounds before drawing rectangle
            if (pt.x >= 0 && pt.y >= 0 && pt.x + w <= card_mask.cols &&
                pt.y + h <= card_mask.rows) {
                cv::rectangle(card_mask, pt, cv::Point(pt.x + w, pt.y + h), cv::Scalar(255),
                              -1); // Draw filled white rectangle
            } else {
                fprintf(stderr,
                        "decensor warning: Drawing rectangle out of bounds at (%d, %d) with size (%d, %d) for masksize %d. Skipping draw.\n",
                        pt.x, pt.y, w, h, masksize);
            }
        }
    }
    fprintf(stderr, "decensor: Template matching complete.\n");


    // --- 3. Calculating Dominant Mosaic Resolution ---

    // Check if any mosaic region was detected based on the populated resolutions vector
    // We check indices [LowRange + 1, HighRange + 1] which are where counts are stored.
    bool mosaic_detected_any_size = false;
    for (int i = LowRange + 1; i <= HighRange + 1; ++i) {
        if (i < resolutions.size() && resolutions[i] > 0) {
            mosaic_detected_any_size = true;
            break;
        }
    }

    if (!mosaic_detected_any_size) {
        // Also check the card_mask just in case findNonZero missed something, though unlikely
        if (cv::countNonZero(card_mask) == 0) {
            fprintf(stderr, "decensor: No mosaic regions detected. Copying input to output.\n");
            inimage.copyTo(outimage);
            return -1; // Indicate no mosaic found
        }
        // If mask has non-zero but resolutions don't, something unexpected happened.
        // Proceed anyway, maybe a very large pattern matched the whole image?
        fprintf(stderr,
                "decensor warning: card_mask has non-zero pixels, but no matches recorded in resolutions [LowRange+1, HighRange+1]. Proceeding with default resolution.\n");
    }

    fprintf(stderr, "decensor: Calculating resolution...\n");
    // Debugging: Print populated resolutions vector segment
    fprintf(stderr, "decensor: Resolutions counts (indices %d to %d): [", LowRange + 1,
            HighRange + 1);
    for (int i = LowRange + 1; i <= HighRange + 1; ++i) {
        if (i < resolutions.size()) { // Safety check
            fprintf(stderr, "%d%s", resolutions[i], i == HighRange + 1 ? "" : ", ");
        }
    }
    fprintf(stderr, "]\n");


    // Find local minima indices to define groups.
    // Matching detectMosaicResolution: find indices i where resolutions[i] < resolutions[i-1] AND resolutions[i] <= resolutions[i+1]
    // Loop range for finding minima is indices [1, resolutions.size() - 2], which is [1, HighRange + 1].
    // Add LowRange (index 2) and HighRange+2 (index HighRange+2) as boundary extrema.
    std::set<int> extrema_indices_set; // Use set to handle duplicates and keep sorted

    // Add boundary extrema as in detectMosaicResolution's final extrema list
    extrema_indices_set.insert(LowRange); // Index 2
    extrema_indices_set.insert(HighRange + 2); // Index HighRange + 2

    // Find true local minima within indices [1, HighRange + 1]
    for (int i = 1; i <= HighRange + 1; ++i) {
        // Ensure indices i-1, i, i+1 are valid
        if (i > 0 && i < resolutions.size() - 1) {
            // Strict less than left, less than or equal to right (matching detectMosaicResolution)
            if (resolutions[i] < resolutions[i - 1] && resolutions[i] <= resolutions[i + 1]) {
                extrema_indices_set.insert(i);
            }
        }
    }

    // Convert set to vector for easier indexing of groups
    std::vector<int> extrema_indices(extrema_indices_set.begin(), extrema_indices_set.end());

    // Debugging: Print final extrema indices
    fprintf(stderr, "decensor: Final Extrema indices defining groups: [");
    for (size_t i = 0; i < extrema_indices.size(); ++i) {
        fprintf(stderr, "%d%s", extrema_indices[i], i == extrema_indices.size() - 1 ? "" : ", ");
    }
    fprintf(stderr, "]\n");


    // Find the "biggest extrema group"
    int MosaicResolutionOfImage = HighRange + 1; // Default value
    int best_group_sum_score = -1; // Stores sum + int(sum*0.05)
    int best_group_max_val_score = -1; // Stores max_val + int(max_val*0.15)
    int best_original_index_of_max = -1; // The index in `resolutions` where the max count of the best group was found

    if (extrema_indices.size() < 2) {
        fprintf(stderr,
                "decensor: Not enough extrema points (%zu) to form groups. Cannot calculate resolution reliably. Using default HighRange + 1 (%d).\n",
                extrema_indices.size(), HighRange + 1);
        // Keep default resolution HighRange + 1
    } else {
        // Iterate through pairs of extrema indices to define groups [start_res_idx, end_res_idx] inclusive
        for (size_t i = 0; i < extrema_indices.size() - 1; ++i) {
            int group_start_res_index = extrema_indices[i];
            int group_end_res_index = extrema_indices[i + 1]; // Inclusive range [start, end]

            // Check bounds for group indices
            if (group_start_res_index < 0 || group_start_res_index >= (int) resolutions.size() ||
                group_end_res_index < 0 || group_end_res_index >= (int) resolutions.size() ||
                group_start_res_index > group_end_res_index) {
                fprintf(stderr,
                        "decensor ERROR: Invalid extrema group indices [%d, %d] for resolutions size %zu. Skipping group.\n",
                        group_start_res_index, group_end_res_index, resolutions.size());
                continue;
            }

            int current_group_sum = 0;
            int current_max_val_in_group = -1;
            int current_original_index_of_max_in_group = -1; // Index in `resolutions` for max of this group

            // Calculate sum and find max value + its original index within this group range
            for (int res_idx = group_start_res_index; res_idx <= group_end_res_index; ++res_idx) {
                current_group_sum += resolutions[res_idx];
                if (current_max_val_in_group == -1 ||
                    resolutions[res_idx] > current_max_val_in_group) {
                    current_max_val_in_group = resolutions[res_idx];
                    current_original_index_of_max_in_group = res_idx;
                } else if (resolutions[res_idx] == current_max_val_in_group) {
                    // Tie-breaker for max value within the group: prefer smaller index
                    if (current_original_index_of_max_in_group == -1 ||
                        res_idx < current_original_index_of_max_in_group) {
                        current_original_index_of_max_in_group = res_idx;
                    }
                }
            }

            // If the current group has no positive matches, skip it
            if (current_max_val_in_group <= 0) {
                // fprintf(stderr, "decensor: Skipping group [%d, %d] with no matches.\n", group_start_res_index, group_end_res_index);
                continue;
            }

            // Calculate scores based on Python's logic
            int current_sum_score = current_group_sum + static_cast<int>(current_group_sum * 0.05);
            int current_max_val_score =
                    current_max_val_in_group + static_cast<int>(current_max_val_in_group * 0.15);

            // Compare current group against the best found so far
            bool update_best = false;
            if (best_original_index_of_max == -1) { // First valid group
                update_best = true;
            } else {
                if (current_sum_score > best_group_sum_score) {
                    update_best = true;
                } else if (current_sum_score == best_group_sum_score) {
                    if (current_max_val_score > best_group_max_val_score) {
                        update_best = true;
                    } else if (current_max_val_score == best_group_max_val_score) {
                        // Tie-breaker: prefer group whose peak (max value) is at a smaller index
                        if (current_original_index_of_max_in_group < best_original_index_of_max) {
                            update_best = true;
                        }
                    }
                }
            }

            if (update_best) {
                best_group_sum_score = current_sum_score;
                best_group_max_val_score = current_max_val_score;
                best_original_index_of_max = current_original_index_of_max_in_group;
                // Debugging:
                // fprintf(stderr, "decensor: New best group found. Peak at res_idx %d (masksize %d). Sum Score %d, Max Score %d.\n",
                //         best_original_index_of_max, best_original_index_of_max + 1, best_group_sum_score, best_max_val_score);
            }
        } // End loop through extrema groups

        // Determine the final mosaic resolution from the best group's peak index
        if (best_original_index_of_max != -1) {
            // The index in `resolutions` corresponds to masksize = index + 1.
            // The index range for counts is [LowRange+1, HighRange+1].
            // So the masksize range is [LowRange+2, HighRange+2].
            MosaicResolutionOfImage = best_original_index_of_max + 1;

            // Python's final check: if MosaicResolutionOfImage == 0, set to HighRange+1.
            // Given our logic, it should be >= LowRange+2 if best_original_index_of_max != -1.
            // But keep check for safety/matching Python state.
            if (MosaicResolutionOfImage == 0) {
                fprintf(stderr,
                        "decensor: Calculated MosaicResolutionOfImage was unexpectedly 0. Setting to default HighRange + 1 (%d).\n",
                        HighRange + 1);
                MosaicResolutionOfImage = HighRange + 1;
            }

        } else {
            // No valid group found (e.g., no matches above threshold). Use default.
            fprintf(stderr,
                    "decensor: No clear best group found during resolution calculation. Using default HighRange + 1 (%d).\n",
                    HighRange + 1);
            // MosaicResolutionOfImage is already initialized to HighRange + 1
        }
    } // End if extrema_indices.size() >= 2


    {
        // 检测 cv::Mat card_mask 的每个连通的区域。如果区域的长、宽都小于 MosaicResolutionOfImage *1.5,则擦除这个区域。
        // 检测连通区域
        cv::Mat labels, stats, centroids;
        int num_labels = cv::connectedComponentsWithStats(card_mask, labels, stats, centroids, 8);
        int removed = 0;

        // 遍历所有连通区域（跳过背景0）
        for (int i = 1; i < num_labels; i++) {
            int width = stats.at<int>(i, cv::CC_STAT_WIDTH);
            int height = stats.at<int>(i, cv::CC_STAT_HEIGHT);

            // 如果区域尺寸小于阈值则擦除
            if (width < MosaicResolutionOfImage * 1.5 ||
                height < MosaicResolutionOfImage * 1.5) {
                cv::Mat region_mask = (labels == i);
                card_mask.setTo(0, region_mask); // 将该区域置为0
                removed++;
            }
        }

        if (removed > 0)
            fprintf(stderr, "decensor: Removed %d regions, total %d.\n", removed, num_labels);

    }

    // --- 4. ESRGAN Processing ---

    cv::Mat processed_region_esr; // Will hold the ESRGAN output resized to the target area size

    // Calculate downscale factor and number of SR loops
    int loops = std::round(std::log(MosaicResolutionOfImage) / std::log(scale));
    loops = std::max(1, loops); // Ensure at least one SR pass if mosaic is detected
    float total_model_upscale = std::pow(scale, loops);
    float pre_scale = total_model_upscale * 1.1 >= MosaicResolutionOfImage
                      ? 1.0f / MosaicResolutionOfImage
                      : sqrt(MosaicResolutionOfImage / total_model_upscale) /
                        MosaicResolutionOfImage;

    int Sx = static_cast<int>(inimage.cols * pre_scale);
    int Sy = static_cast<int>(inimage.rows * pre_scale);

    // Ensure Sx, Sy are at least 1
    Sx = std::max(1, Sx);
    Sy = std::max(1, Sy);


    fprintf(stderr, "decensor: Mosaic Resolution: %d, pre_scale=%.3f, loops=%d\n",
            MosaicResolutionOfImage, pre_scale, loops);

    cv::Mat shrinkedI;
    // Use INTER_AREA for downsampling, INTER_CUBIC for upsampling (later resize)
    cv::resize(inimage, shrinkedI, cv::Size(Sx, Sy), 0, 0, cv::INTER_AREA);

    cv::Mat esr_output_shrunken_scaled = shrinkedI; // Start the loop with the downscaled image
    cv::Mat out_mask;

    for (int i = 0; i < loops; i++) {
        cv::Mat current_esr_output(esr_output_shrunken_scaled.rows * scale,
                                   esr_output_shrunken_scaled.cols * scale,
                                   CV_8UC3);

        fprintf(stderr, "decensor: processing loop %d/%d, %d*%d -> %d*%d\n",
                i + 1, loops, esr_output_shrunken_scaled.rows, esr_output_shrunken_scaled.cols,
                current_esr_output.rows, current_esr_output.cols
        );
        if (process(esr_output_shrunken_scaled, current_esr_output, card_mask) != 0) {
            fprintf(stderr, "decensor error: MNNSR::process failed during SR loop %d.\n", i + 1);
            inimage.copyTo(outimage); // Fallback
            return -1;
        }

        cv::resize(inimage, esr_output_shrunken_scaled, current_esr_output.size(), 0, 0,
                   cv::INTER_AREA);
        cv::resize(card_mask, out_mask, current_esr_output.size(), 0, 0,
                   cv::INTER_NEAREST);
        current_esr_output.copyTo(esr_output_shrunken_scaled, out_mask);

//        current_esr_output.copyTo(esr_output_shrunken_scaled);
    }

    fprintf(stderr, "decensor: Combining processed image with original...\n");
    cv::resize(esr_output_shrunken_scaled, processed_region_esr, inimage.size(), 0, 0,
               cv::INTER_CUBIC);
    outimage = inimage.clone();
    processed_region_esr.copyTo(outimage, card_mask);

    if (det_box) {
        drawSemiTransparentMask(outimage, card_mask, 0.3f);
    }

    // Handle Alpha channel if original image had one
    if (original_channels == 4) {
        // Split the original BGRA image
        std::vector<cv::Mat> original_channels_split(4);
        cv::split(inimage, original_channels_split);
        cv::Mat alpha_channel = original_channels_split[3]; // The alpha channel

        // Convert the output BGR image to BGRA
        cv::cvtColor(outimage, outimage, cv::COLOR_BGR2BGRA);

        // Copy the original alpha channel to the output BGRA image's alpha channel
        std::vector<cv::Mat> output_channels_split(4);
        cv::split(outimage, output_channels_split);
        alpha_channel.copyTo(output_channels_split[3]);
        cv::merge(output_channels_split, outimage);
    }

    fprintf(stderr, "decensor: Processing complete.\n");
    return 0; // Success
}
