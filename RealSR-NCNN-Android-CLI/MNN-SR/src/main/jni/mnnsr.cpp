//
// Created by Yazii on 2025/3/16.
//
#include "mnnsr.h"
#include <thread>

#include "MNN/ErrorCode.hpp"
#define MNN_USER_SET_DEVICE
#include "MNN/MNNSharedContext.h"


#include <opencv2/opencv.hpp>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <vector>
#include <cstdio> // For fprintf

using namespace MNN;

MNNSR::MNNSR(int color_type, int decensor_mode) {
    this->decensor_mode = decensor_mode;
    if (decensor_mode > 0) {
        dcp = new DCP();
        return;
    }
    color = static_cast<ColorType>(color_type);
    if (color == ColorType::RGB)
        pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(
                MNN::CV::ImageProcess::create(MNN::CV::BGR, MNN::CV::RGB, meanVals_, 3, normVals_,
                                              3));
    else if (color == ColorType::GRAY || color == ColorType::Gray2YCbCr ||
             color == ColorType::Gray2YUV) {
        model_channel = 1;
        pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(
                MNN::CV::ImageProcess::create(MNN::CV::BGR, MNN::CV::GRAY, meanVals_, 3, normVals_,
                                              3));
    } else if (color == ColorType::YCbCr) {
        pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(
                MNN::CV::ImageProcess::create(MNN::CV::BGR, MNN::CV::YCrCb, meanVals_, 3, normVals_,
                                              3));
    } else if (color == ColorType::YUV) {
        pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(
                MNN::CV::ImageProcess::create(MNN::CV::BGR, MNN::CV::YUV, meanVals_, 3, normVals_,
                                              3));
    } else {
        fprintf(stderr, "color space error\n");
        exit(1);
    }

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
    }else{
        config.numThread = 128+4;
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
    if (cachemodel) {

#if _WIN32
        std::string cachefile = std::wstring_convert<std::codecvt_utf8<wchar_t>>().to_bytes(modelpath + L".cache");
#else
        std::string cachefile = modelpath + ".cache";
#endif
        interpreter->setCacheFile(cachefile.c_str());
    }

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
		modelInputSize_ = dims[2];   // 记录模型固定输入边长(探针失败时作为唯一可用尺寸)
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


int MNNSR::probeMaxInputSize(int targetScale, int maxProbe) {
    if (!interpreter || !session || !pretreat_) return 0;
    const uint savedTilesize = tilesize;
    int best = 0;
    // 递增探测输入边长: 128, 160, 192, ..., 至 maxProbe(默认 512); 每个尺寸跑一次推理,
    // 校验输出 == 输入×模型实际倍率; 首个不成立的尺寸之前的 lastOK 即模型输入上限。
    // 实际倍率由首个尺寸(128)的输出/输入比得出, 与命令 scale 无关:
    // 外载 .mnn 单文件模型 resolveModelPath 不修正倍率(如 x1 修复模型配 -s 4),
    // 若用命令 scale 判定会在最小尺寸误报 FAIL, 无法测出上限。
    int actScale = 0;   // 模型实际倍率(首个尺寸测量), 0 = 尚未确定
    for (int n = 128; n <= maxProbe; n += 32) {
        // 取消检查: progressCallback_ 返回 false 表示请求取消(停止按钮), 提前退出
        if (progressCallback_ && !progressCallback_(n, maxProbe, 0, 0)) {
            fprintf(stderr, "probe cancelled at %d\n", n);
            break;
        }
        if (infoCallback_)
            infoCallback_("探针测试: 正在测试 " + std::to_string(n) + "x" + std::to_string(n) + " ...");
        int ow = 0, oh = 0;
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
            // 分配成功后再销毁旧 tensor: 若 new 抛异常, 旧指针保持有效, 不会悬垂/双重释放
            MNN::Tensor::destroy(input_tensor);
            MNN::Tensor::destroy(output_tensor);
            input_tensor = newInput;
            output_tensor = newOutput;
            // 灰色探针图(内容无关, 尺寸决定输出)
            cv::Mat probe(n, n, CV_8UC3, cv::Scalar(128, 128, 128));
            pretreat_->convert(probe.data, probe.cols, probe.rows,
                               probe.cols * probe.channels(), input_tensor);
            interpreter_input->copyFromHostTensor(input_tensor);
            interpreter->runSession(session);
            interpreter_output->copyToHostTensor(output_tensor);
            ow = output_tensor->width();
            oh = output_tensor->height();
        } catch (const std::exception &e) {
            fprintf(stderr, "probe %d: exception %s\n", n, e.what());
            break;
        }
        // 首个尺寸(128)确定模型实际倍率(输出/输入比四舍五入), 与命令 scale 无关
        if (actScale == 0 && ow > 0)
            actScale = (ow + n / 2) / n;
        // actScale > 0 才判定: 首个尺寸异常(ow=0)时若 0==n*0 会误判, 需跳过
        if (actScale > 0 && ow == n * actScale && oh == n * actScale) {
            best = n;
            fprintf(stderr, "probe %dx%d -> %dx%d OK\n", n, n, ow, oh);
            if (infoCallback_)
                infoCallback_("探针测试: " + std::to_string(n) + "x" + std::to_string(n) +
                              " -> " + std::to_string(ow) + "x" + std::to_string(oh) + " OK");
        } else {
            fprintf(stderr, "probe %dx%d -> %dx%d FAIL (expect scale=%d), max input=%d\n",
                    n, n, ow, oh, actScale, best);
            if (infoCallback_)
                infoCallback_("探针测试: " + std::to_string(n) + "x" + std::to_string(n) +
                              " -> " + std::to_string(ow) + "x" + std::to_string(oh) +
                              " FAIL (期望 x" + std::to_string(actScale) + "), 最大可用输入=" +
                              std::to_string(best));
            break;
        }
    }
    // 恢复 load 时的 tilesize 状态, 不影响后续 process
    try {
        interpreter->resizeTensor(interpreter_input, 1, model_channel, savedTilesize, savedTilesize);
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
        tilesize = savedTilesize;
    } catch (const std::exception &e) {
        fprintf(stderr, "probe restore: exception %s\n", e.what());
        return 0;
    }
    // 探测失败(如固定输入模型, resizeTensor 后输出不随输入变化): 返回模型原生固定输入尺寸
    // 作为唯一可用输入上限, 避免 probe JNI 报 "probe failed" 误判
    if (best <= 0 && modelInputSize_ > 0) {
        fprintf(stderr, "probe: fixed-input model, use native input size %d\n", modelInputSize_);
        best = modelInputSize_;
    }
    return best;
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

// 一维变宽切分(最少块数 + 中心大块):
// 给定总长度 total、单块上限 maxEff(有效尺寸)、块下限 minTile,
// 切出最少数量 n = ceil(total/maxEff) 的块, 中心块用满 maxEff,
// 多余像素从边缘向中心对称削减, 使大块集中在图片中心区域。
// 返回每块尺寸数组, 各块之和 == total。
static std::vector<int> computeTileSizes(int total, int maxEff, int minTile) {
    std::vector<int> sizes;
    if (total <= 0) return sizes;
    if (total <= maxEff) { sizes.push_back(total); return sizes; }
    int n = (total + maxEff - 1) / maxEff;   // 最少块数
    sizes.assign(n, maxEff);
    int excess = n * maxEff - total;         // 需从各块削减的总量
    if (excess <= 0) return sizes;
    // 从左右两端向中心削减, 保持对称, 每块不低于 minTile
    int i = 0, j = n - 1;
    while (excess > 0 && i <= j) {
        if (i == j) {
            int cut = std::min(excess, sizes[i] - minTile);
            sizes[i] -= cut; excess -= cut;
            break;
        }
        int cut = std::min(excess, sizes[i] - minTile);
        sizes[i] -= cut; excess -= cut; i++;
        if (excess > 0 && i <= j) {
            cut = std::min(excess, sizes[j] - minTile);
            sizes[j] -= cut; excess -= cut; j--;
        }
    }
    // 极端情况仍有多余: 全部塞给最后一块(保持总和正确)
    if (excess > 0 && !sizes.empty()) sizes[n - 1] -= excess;
    return sizes;
}

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

    // 每个tile的有效大小
    int tileWidth = tilesize - prepadding * 2;
    int tileHeight = tilesize - prepadding * 2;

    // 变宽/变高切分: 最少块数, 中心大块(仅当启用且 tilesize 有效时)
    // 默认行为(等分网格)由调用方经 tilesize 控制; 此处统一走变宽切分,
    // 其生成的块数 <= 等分网格块数, 大块在中心。
    const int MIN_TILE = 32;   // 单块最小有效尺寸(安全下限)
    int effW = (tileWidth > MIN_TILE) ? tileWidth : MIN_TILE;
    int effH = (tileHeight > MIN_TILE) ? tileHeight : MIN_TILE;
    std::vector<int> colWidths = computeTileSizes(inWidth, effW, MIN_TILE);
    std::vector<int> rowHeights = computeTileSizes(inHeight, effH, MIN_TILE);
    std::vector<int> colOffs(colWidths.size() + 1, 0), rowOffs(rowHeights.size() + 1, 0);
    for (size_t i = 0; i < colWidths.size(); i++) colOffs[i + 1] = colOffs[i] + colWidths[i];
    for (size_t i = 0; i < rowHeights.size(); i++) rowOffs[i + 1] = rowOffs[i] + rowHeights[i];

    uint xtiles = (uint)colWidths.size();
    uint ytiles = (uint)rowHeights.size();
    uint xPrepadding = prepadding, yPrepadding = prepadding;

    fprintf(stderr,
        "process tiles: %d x %d, tilesize: %d -> %d %d, prepadding: %d\n",
        xtiles, ytiles, tilesize, tileWidth, tileHeight, prepadding);

    high_resolution_clock::time_point begin = high_resolution_clock::now();
    high_resolution_clock::time_point time_print_progress;

    // 重叠区线性权重混合: 相邻 tile 在 padding 输出重叠带按权重平滑过渡,
    // 消除硬拼接产生的 tile 边界伪影(参照 GeoAI smooth inference 思路)。
    // accum 累积各 tile 的加权输出, weightMap 累积权重, 结束后归一化。
    cv::Mat accum(outHeight, outWidth, CV_32FC3, cv::Scalar(0, 0, 0));
    cv::Mat weightMap(outHeight, outWidth, CV_32FC1, cv::Scalar(0));

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
        int out_tile_h = tileH * scale;

        for (uint xi = 0; xi < xtiles; xi++) {
            int tileW = colWidths[xi];

            if (!inMask.empty()) {
                int x0 = colOffs[xi], x = tileW, y0 = rowOffs[yi], y = tileH;
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
            int out_tile_w = tileW * scale;

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

            }
            else {
                cv::copyMakeBorder(inputTile, paddedTile, 0, 0, 0, 0, cv::BORDER_CONSTANT);
                pretreat_->convert(paddedTile.data, paddedTile.cols, paddedTile.rows,
                    paddedTile.cols * paddedTile.channels(),
                    input_tensor);
            }

            bool r = interpreter_input->copyFromHostTensor(input_tensor);

            interpreter->runSession(session);
            cv::Mat outputTile = TensorToCvMat();


            if (!scale_checked) {
                if(scale< 1e-5){
                    fprintf(stderr, "[err] Invalid scale value: %d\n", scale);
                    return -1;
				}

                if (outputTile.cols != paddedTile.cols * scale || outputTile.rows != paddedTile.rows * scale) {
                    float actual_model_scale = static_cast<float>(outputTile.cols) / static_cast<float>(paddedTile.cols);
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

            // 重叠区线性权重混合: 输出矩形在有效区基础上向四周扩展 overlapOut 像素,
            // 与相邻 tile 重叠; 重叠带权重从边缘 0 线性升到有效区 1, 消除拼接缝。
            int overlapOut = prepadding * scale;
            if (overlapOut < 4) overlapOut = 4;
            // 目标(输出图)矩形
            int dstX = out_x0 - std::min(overlapOut, out_x0);
            int dstY = out_y0 - std::min(overlapOut, out_y0);
            int dstX2 = std::min(out_x0 + out_tile_w + overlapOut, outWidth);
            int dstY2 = std::min(out_y0 + out_tile_h + overlapOut, outHeight);
            int dstW = dstX2 - dstX, dstH = dstY2 - dstY;
            // 源(outputTile)矩形(有效区 cropRect 起点外扩 overlapOut)
            int srcX = out_tile_x0 - (out_x0 - dstX);
            int srcY = out_tile_y0 - (out_y0 - dstY);
            if (srcX < 0) { dstW += srcX; srcX = 0; }
            if (srcY < 0) { dstH += srcY; srcY = 0; }
            if (srcX + dstW > outputTile.cols) dstW = outputTile.cols - srcX;
            if (srcY + dstH > outputTile.rows) dstH = outputTile.rows - srcY;
            int leftOverlap = out_x0 - dstX, topOverlap = out_y0 - dstY;
            int rightOverlap = dstX2 - (out_x0 + out_tile_w);
            int bottomOverlap = dstY2 - (out_y0 + out_tile_h);
            if (dstW <= 0 || dstH <= 0 || dstW > outputTile.cols - srcX || dstH > outputTile.rows - srcY)
            {
                // 兜底: 直接硬拼接(极端边界)
                croppedTile.copyTo(outimage(cv::Rect(out_x0, out_y0, croppedTile.cols, croppedTile.rows)));
            }
            else
            {
                cv::Mat tileSrc = outputTile(cv::Rect(srcX, srcY, dstW, dstH));
                cv::Mat dstAcc = accum(cv::Rect(dstX, dstY, dstW, dstH));
                cv::Mat dstWm = weightMap(cv::Rect(dstX, dstY, dstW, dstH));
                for (int yy = 0; yy < dstH; yy++)
                {
                    float wy = 1.0f;
                    if (topOverlap > 0 && yy < topOverlap)
                        wy = (float)(yy + 1) / (float)(topOverlap + 1);
                    else if (bottomOverlap > 0 && yy >= topOverlap + out_tile_h)
                        wy = (float)(dstH - yy) / (float)(bottomOverlap + 1);
                    if (wy < 0.02f) wy = 0.02f;
                    for (int xx = 0; xx < dstW; xx++)
                    {
                        float wx = 1.0f;
                        if (leftOverlap > 0 && xx < leftOverlap)
                            wx = (float)(xx + 1) / (float)(leftOverlap + 1);
                        else if (rightOverlap > 0 && xx >= leftOverlap + out_tile_w)
                            wx = (float)(dstW - xx) / (float)(rightOverlap + 1);
                        if (wx < 0.02f) wx = 0.02f;
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
