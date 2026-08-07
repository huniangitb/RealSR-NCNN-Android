// MNNSR_H implemented with MNN library

#ifndef MNNSR_H
#define MNNSR_H

#include <string>
#include <functional>

#include <opencv2/opencv.hpp>

#include <chrono>
#include "MNN/Tensor.hpp"
#include "MNN/Interpreter.hpp"
#include "MNN/ImageProcess.hpp"
#include "utils.hpp"
#include "mnn_utils.hpp"
#include "dcp.h"

using namespace std::chrono;

class MNNSR {
public:
    MNNSR(int color_type, int decensor_mode);

    ~MNNSR();

#if _WIN32
    int load(const std::wstring& modelpath, bool cachemodel, const bool nchw = true);
#else

    int load(const std::string &modelpath, bool cachemodel, const bool nchw = true);

#endif

    int process(const cv::Mat &inimage, cv::Mat &outimage, const cv::Mat &mask = cv::Mat());

    int decensor(const cv::Mat &inimage, cv::Mat &outimage, const bool det_box = false);

    /**
     * 探针推理: 测量模型最大可用输入尺寸。
     * 从 minProbe 起按 step 递增输入边长, 每个尺寸跑一次推理,
     * 校验输出 == 输入×scale 是否成立; 返回最后一个成立的输入边长(模型输入上限)。
     * 探测完成后恢复 load 时的 tilesize 状态, 不影响后续 process。
     * @param targetScale 目标倍率(用于判定输出尺寸)
     * @param maxProbe    探测上限(不超过内存预算允许值)
     * @return 最大可用输入边长(>= 64), 0 表示最小尺寸即失败(倍率不匹配/模型异常)
     */
    int probeMaxInputSize(int targetScale, int maxProbe = 512);

    /**
     * 调整 session 输入尺寸到 n×n(重建 host tensor 与输入/输出缓冲)。
     * 当 tilesize 在 load 之后被修改(memBudget/探针钳制)时必须调用,
     * 保证 process 的 paddedTile 尺寸与 session 输入一致, 否则输出错位/黑边。
     * @return 0 成功, 非 0 失败
     */
    int setInputSize(int n);

    cv::Mat TensorToCvMat(void);

    /**
     * 注册处理进度回调（JNI 桥接使用；CLI 可不设置）。
     * @param cb 回调函数，参数为 (已完成 tile 数, 总 tile 数, 当前 tile 有效宽, 当前 tile 有效高)；
     *           返回 false 表示请求取消处理（tile 循环提前退出并返回 -1）
     */
    void setProgressCallback(std::function<bool(int, int, int, int)> cb);

public:
    int scale;
    ColorType color;
    int model_channel = 3;
    uint tilesize;
    uint prepadding;

    float *input_buffer;
    float *output_buffer;
    MNNForwardType backend_type;
    DCP *dcp = nullptr;


private:
    MNN::Interpreter *interpreter;
    MNN::Session *session;
    MNN::Tensor *interpreter_input;
    MNN::Tensor *interpreter_output;
    MNN::Tensor *input_tensor;
    MNN::Tensor *output_tensor;
    std::shared_ptr<MNN::CV::ImageProcess> pretreat_ = nullptr;
    const float meanVals_[3] = {0, 0, 0};
    const float normVals_[3] = {1.0 / 255, 1.0 / 255, 1.0 / 255};
    bool cachemodel;
    int decensor_mode=-1;
    bool nchw_ = true;   // load 时的 tensor 布局标志(CAFFE=NCHW / TENSORFLOW=NHWC), 重建 host tensor 时必须一致

    std::function<bool(int, int, int, int)> progressCallback_ = nullptr;  // 进度回调 (已完成, 总数, tile宽, tile高); 返回 false 表示取消

    bool scale_checked = false; // Flag to check scale only once
    float interp_scale = 1.0f;  // Interpolation factor to match target scale
};

#endif // MNNSR_H
