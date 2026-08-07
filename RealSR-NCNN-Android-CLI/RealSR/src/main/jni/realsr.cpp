// realsr implemented with ncnn library

#include "realsr.h"

#include <algorithm>
#include <vector>
//#include <omp.h>

#include "realsr_preproc.comp.hex.h"
#include "realsr_postproc.comp.hex.h"
#include "realsr_preproc_tta.comp.hex.h"
#include "realsr_postproc_tta.comp.hex.h"

RealSR::RealSR(int gpuid, bool _tta_mode, int num_threads)
{
    vkdev = gpuid == -1 ? 0 : ncnn::get_gpu_device(gpuid);

    net.opt.num_threads = num_threads;

    realsr_preproc = 0;
    realsr_postproc = 0;
    bicubic_2x = 0;
    bicubic_3x = 0;
    bicubic_4x = 0;
    tta_mode = _tta_mode;
}

RealSR::~RealSR()
{
    // cleanup preprocess and postprocess pipeline
    {
        delete realsr_preproc;
        delete realsr_postproc;
    }

    bicubic_2x->destroy_pipeline(net.opt);
    delete bicubic_2x;

    bicubic_3x->destroy_pipeline(net.opt);
    delete bicubic_3x;

    bicubic_4x->destroy_pipeline(net.opt);
    delete bicubic_4x;
}

#if _WIN32
int RealSR::load(const std::wstring& parampath, const std::wstring& modelpath)
#else
int RealSR::load(const std::string& parampath, const std::string& modelpath)
#endif
{

    net.opt.use_vulkan_compute = vkdev != nullptr;
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = true;
    net.opt.use_int8_storage = true;
    net.opt.use_int8_arithmetic = false; // 如果是 int8 模型，这一项也建议为 true
    net.opt.use_winograd_convolution = true; // 开启 Winograd 卷积优化 (通常默认开启)
    net.opt.use_sgemm_convolution = true;    // 开启 SGEMM 卷积优化
    if (vkdev) net.set_vulkan_device(vkdev);

#if _WIN32
    {
        FILE* fp = _wfopen(parampath.c_str(), L"rb");
        if (!fp)
        {
            fwprintf(stderr, L"_wfopen %ls failed\n", parampath.c_str());
        }

        net.load_param(fp);

        fclose(fp);
    }
    {
        FILE* fp = _wfopen(modelpath.c_str(), L"rb");
        if (!fp)
        {
            fwprintf(stderr, L"_wfopen %ls failed\n", modelpath.c_str());
        }

        net.load_model(fp);

        fclose(fp);
    }
#else
    net.load_param(parampath.c_str());
    net.load_model(modelpath.c_str());
#endif

    // 获取输入和输出名称
    const auto& input_names = net.input_names();
    const auto& output_names = net.output_names();

    if (input_names.empty()) {
        fprintf(stderr, "model not have input_names\n");
        return -1;
    }
    if (output_names.empty()) {
        fprintf(stderr, "model not have output_names\n");
        return -1;
    }

    // 检查输入名称是否存在
    if (std::find(input_names.begin(), input_names.end(), net_input_name) == input_names.end()) {
        fprintf(stderr, "net_input_name %s -> %s\n", net_input_name.c_str(), input_names[0]);
        net_input_name = input_names[0];
    }

    // 检查输出名称是否存在
    if (std::find(output_names.begin(), output_names.end(), net_output_name) == output_names.end()) {
        fprintf(stderr, "net_output_name %s -> %s\n", net_output_name.c_str(), output_names[0]);
        net_output_name = output_names[0];
    }
    // initialize preprocess and postprocess pipeline
    if (vkdev)
    {
        std::vector<ncnn::vk_specialization_type> specializations(1);
#if _WIN32
        specializations[0].i = 1;
#else
        specializations[0].i = 0;
#endif

        {
            static std::vector<uint32_t> spirv;
            static ncnn::Mutex lock;
            {
                ncnn::MutexLockGuard guard(lock);
                if (spirv.empty())
                {
                    if (tta_mode)
                        compile_spirv_module(realsr_preproc_tta_comp_data, sizeof(realsr_preproc_tta_comp_data), net.opt, spirv);
                    else
                        compile_spirv_module(realsr_preproc_comp_data, sizeof(realsr_preproc_comp_data), net.opt, spirv);
                }
            }

            realsr_preproc = new ncnn::Pipeline(vkdev);
            realsr_preproc->set_optimal_local_size_xyz(8, 8, 3);
            realsr_preproc->create(spirv.data(), spirv.size() * 4, specializations);
        }

        {
            static std::vector<uint32_t> spirv;
            static ncnn::Mutex lock;
            {
                ncnn::MutexLockGuard guard(lock);
                if (spirv.empty())
                {
                    if (tta_mode)
                        compile_spirv_module(realsr_postproc_tta_comp_data, sizeof(realsr_postproc_tta_comp_data), net.opt, spirv);
                    else
                        compile_spirv_module(realsr_postproc_comp_data, sizeof(realsr_postproc_comp_data), net.opt, spirv);
                }
            }

            realsr_postproc = new ncnn::Pipeline(vkdev);
            realsr_postproc->set_optimal_local_size_xyz(8, 8, 3);
            realsr_postproc->create(spirv.data(), spirv.size() * 4, specializations);
        }
    }

   // bicubic 2x/3x/4x for alpha channel
   {
       bicubic_2x = ncnn::create_layer("Interp");
       bicubic_2x->vkdev = vkdev;

       ncnn::ParamDict pd;
       pd.set(0, 3);// bicubic
       pd.set(1, 2.f);
       pd.set(2, 2.f);
       bicubic_2x->load_param(pd);

       bicubic_2x->create_pipeline(net.opt);
   }
   {
       bicubic_3x = ncnn::create_layer("Interp");
       bicubic_3x->vkdev = vkdev;

       ncnn::ParamDict pd;
       pd.set(0, 3);// bicubic
       pd.set(1, 3.f);
       pd.set(2, 3.f);
       bicubic_3x->load_param(pd);

       bicubic_3x->create_pipeline(net.opt);
   }
    // bicubic 4x for alpha channel
    {
        bicubic_4x = ncnn::create_layer("Interp");
        bicubic_4x->vkdev = vkdev;

        ncnn::ParamDict pd;
        pd.set(0, 3);// bicubic
        pd.set(1, 4.f);
        pd.set(2, 4.f);
        bicubic_4x->load_param(pd);

        bicubic_4x->create_pipeline(net.opt);
    }

    return 0;
}

int RealSR::process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const
{
    if (!vkdev)
    {
        // cpu only
        return process_cpu(inimage, outimage);
    }

    const unsigned char* pixeldata = (const unsigned char*)inimage.data;
    const int w = inimage.w;
    const int h = inimage.h;
    const int channels = inimage.elempack;

    const int TILE_SIZE_X = tilesize;
    const int TILE_SIZE_Y = tilesize;

    ncnn::VkAllocator* blob_vkallocator = vkdev->acquire_blob_allocator();
    ncnn::VkAllocator* staging_vkallocator = vkdev->acquire_staging_allocator();

    ncnn::Option opt = net.opt;
    opt.blob_vkallocator = blob_vkallocator;
    opt.workspace_vkallocator = blob_vkallocator;
    opt.staging_vkallocator = staging_vkallocator;

    // each tile 100x100
    const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;

    const size_t in_out_tile_elemsize =  (opt.use_fp16_storage || opt.use_fp16_packed)  ? 2u : 4u;
    high_resolution_clock::time_point begin = high_resolution_clock::now();
    high_resolution_clock::time_point time_print_progress;


    //#pragma omp parallel for num_threads(2)
    for (int yi = 0; yi < ytiles; yi++)
    {
        const int tile_h_nopad = std::min((yi + 1) * TILE_SIZE_Y, h) - yi * TILE_SIZE_Y;

        int in_tile_y0 = std::max(yi * TILE_SIZE_Y - prepadding, 0);
        int in_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y + prepadding, h);

        ncnn::Mat in;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage)
        {
            const int safe_tile_h = in_tile_y1 - in_tile_y0;
            const bool is_standard_size = (safe_tile_h == TILE_SIZE_Y + 2 * prepadding);
            if (is_standard_size) {
                in = ncnn::Mat(w, safe_tile_h, (unsigned char*)pixeldata + in_tile_y0 * w * channels, (size_t)channels, 1);
            } else {
                in.create(w, safe_tile_h, (size_t)channels, 1);
                const unsigned char* src = (unsigned char*)pixeldata + in_tile_y0 * w * channels;
                memcpy(in.data, src, (size_t)w * safe_tile_h * channels);
            }
        }
        else
        {
            if (channels == 3)
            {
#if _WIN32
                in = ncnn::Mat::from_pixels(pixeldata + in_tile_y0 * w * channels, ncnn::Mat::PIXEL_BGR2RGB, w, (in_tile_y1 - in_tile_y0));
#else
                in = ncnn::Mat::from_pixels(pixeldata + in_tile_y0 * w * channels, ncnn::Mat::PIXEL_RGB, w, (in_tile_y1 - in_tile_y0));
#endif
            }
            if (channels == 4)
            {
#if _WIN32
                in = ncnn::Mat::from_pixels(pixeldata + in_tile_y0 * w * channels, ncnn::Mat::PIXEL_BGRA2RGBA, w, (in_tile_y1 - in_tile_y0));
#else
                in = ncnn::Mat::from_pixels(pixeldata + in_tile_y0 * w * channels, ncnn::Mat::PIXEL_RGBA, w, (in_tile_y1 - in_tile_y0));
#endif
            }
        }

        ncnn::VkCompute cmd(vkdev);

        // upload
        ncnn::VkMat in_gpu;
        {
            cmd.record_clone(in, in_gpu, opt);

            if (xtiles > 1)
            {
                cmd.submit_and_wait();
                cmd.reset();
            }
        }

        int out_tile_y0 = std::max(yi * TILE_SIZE_Y, 0);
        int out_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h);

        ncnn::VkMat out_gpu;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage)
        {
            out_gpu.create(w * scale, (out_tile_y1 - out_tile_y0) * scale, (size_t)channels, 1, blob_vkallocator);
        }
        else
        {
            out_gpu.create(w * scale, (out_tile_y1 - out_tile_y0) * scale, channels, (size_t)4u, 1, blob_vkallocator);
        }

        for (int xi = 0; xi < xtiles; xi++)
        {
            const int tile_w_nopad = std::min((xi + 1) * TILE_SIZE_X, w) - xi * TILE_SIZE_X;

            if (tta_mode)
            {
                // preproc
                ncnn::VkMat in_tile_gpu[8];
                ncnn::VkMat in_alpha_tile_gpu;
                {
                    // crop tile
                    int tile_x0 = xi * TILE_SIZE_X - prepadding;
                    int tile_x1 = std::min((xi + 1) * TILE_SIZE_X, w) + prepadding;
                    int tile_y0 = yi * TILE_SIZE_Y - prepadding;
                    int tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h) + prepadding;

                    in_tile_gpu[0].create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
                    in_tile_gpu[1].create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
                    in_tile_gpu[2].create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
                    in_tile_gpu[3].create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
                    in_tile_gpu[4].create(tile_y1 - tile_y0, tile_x1 - tile_x0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
                    in_tile_gpu[5].create(tile_y1 - tile_y0, tile_x1 - tile_x0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
                    in_tile_gpu[6].create(tile_y1 - tile_y0, tile_x1 - tile_x0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
                    in_tile_gpu[7].create(tile_y1 - tile_y0, tile_x1 - tile_x0, 3, in_out_tile_elemsize, 1, blob_vkallocator);

                    if (channels == 4)
                    {
                        in_alpha_tile_gpu.create(tile_w_nopad, tile_h_nopad, 1, in_out_tile_elemsize, 1, blob_vkallocator);
                    }

                    std::vector<ncnn::VkMat> bindings(10);
                    bindings[0] = in_gpu;
                    bindings[1] = in_tile_gpu[0];
                    bindings[2] = in_tile_gpu[1];
                    bindings[3] = in_tile_gpu[2];
                    bindings[4] = in_tile_gpu[3];
                    bindings[5] = in_tile_gpu[4];
                    bindings[6] = in_tile_gpu[5];
                    bindings[7] = in_tile_gpu[6];
                    bindings[8] = in_tile_gpu[7];
                    bindings[9] = in_alpha_tile_gpu;

                    std::vector<ncnn::vk_constant_type> constants(13);
                    constants[0].i = in_gpu.w;
                    constants[1].i = in_gpu.h;
                    constants[2].i = in_gpu.cstep;
                    constants[3].i = in_tile_gpu[0].w;
                    constants[4].i = in_tile_gpu[0].h;
                    constants[5].i = in_tile_gpu[0].cstep;
                    constants[6].i = prepadding;
                    constants[7].i = prepadding;
                    constants[8].i = xi * TILE_SIZE_X;
                    constants[9].i = std::min(yi * TILE_SIZE_Y, prepadding);
                    constants[10].i = channels;
                    constants[11].i = in_alpha_tile_gpu.w;
                    constants[12].i = in_alpha_tile_gpu.h;

                    ncnn::VkMat dispatcher;
                    dispatcher.w = in_tile_gpu[0].w;
                    dispatcher.h = in_tile_gpu[0].h;
                    dispatcher.c = channels;

                    cmd.record_pipeline(realsr_preproc, bindings, constants, dispatcher);
                }

                // realsr
                ncnn::VkMat out_tile_gpu[8];
                for (int ti = 0; ti < 8; ti++)
                {
                    ncnn::Extractor ex = net.create_extractor();



                    ex.set_blob_vkallocator(blob_vkallocator);
                    ex.set_workspace_vkallocator(blob_vkallocator);
                    ex.set_staging_vkallocator(staging_vkallocator);


                    ex.input(net_input_name.c_str(), in_tile_gpu[ti]);

                    ex.extract("output", out_tile_gpu[ti], cmd);

                    {
                        cmd.submit_and_wait();
                        cmd.reset();
                    }
                }

                ncnn::VkMat out_alpha_tile_gpu;
                if (channels == 4)
                {
                    if (scale == 1)
                    {
                        out_alpha_tile_gpu = in_alpha_tile_gpu;
                    }
                    if (scale == 2)
                    {
                        bicubic_2x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                    }
                    if (scale == 3)
                    {
                        bicubic_3x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                    }
                    if (scale == 4)
                    {
                        bicubic_4x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                    }
                }

                // postproc
                {
                    std::vector<ncnn::VkMat> bindings(10);
                    bindings[0] = out_tile_gpu[0];
                    bindings[1] = out_tile_gpu[1];
                    bindings[2] = out_tile_gpu[2];
                    bindings[3] = out_tile_gpu[3];
                    bindings[4] = out_tile_gpu[4];
                    bindings[5] = out_tile_gpu[5];
                    bindings[6] = out_tile_gpu[6];
                    bindings[7] = out_tile_gpu[7];
                    bindings[8] = out_alpha_tile_gpu;
                    bindings[9] = out_gpu;

                    std::vector<ncnn::vk_constant_type> constants(13);
                    constants[0].i = out_tile_gpu[0].w;
                    constants[1].i = out_tile_gpu[0].h;
                    constants[2].i = out_tile_gpu[0].cstep;
                    constants[3].i = out_gpu.w;
                    constants[4].i = out_gpu.h;
                    constants[5].i = out_gpu.cstep;
                    constants[6].i = xi * TILE_SIZE_X * scale;
                    constants[7].i = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
                    constants[8].i = prepadding * scale;
                    constants[9].i = prepadding * scale;
                    constants[10].i = channels;
                    constants[11].i = out_alpha_tile_gpu.w;
                    constants[12].i = out_alpha_tile_gpu.h;

                    ncnn::VkMat dispatcher;
                    dispatcher.w = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
                    dispatcher.h = out_gpu.h;
                    dispatcher.c = channels;

                    cmd.record_pipeline(realsr_postproc, bindings, constants, dispatcher);
                }
            }
            else
            {
                // preproc
                ncnn::VkMat in_tile_gpu;
                ncnn::VkMat in_alpha_tile_gpu;
                {
                    // crop tile
                    int tile_x0 = xi * TILE_SIZE_X - prepadding;
                    int tile_x1 = std::min((xi + 1) * TILE_SIZE_X, w) + prepadding;
                    int tile_y0 = yi * TILE_SIZE_Y - prepadding;
                    int tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h) + prepadding;

                    in_tile_gpu.create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator);

                    if (channels == 4)
                    {
                        in_alpha_tile_gpu.create(tile_w_nopad, tile_h_nopad, 1, in_out_tile_elemsize, 1, blob_vkallocator);
                    }

                    std::vector<ncnn::VkMat> bindings(3);
                    bindings[0] = in_gpu;
                    bindings[1] = in_tile_gpu;
                    bindings[2] = in_alpha_tile_gpu;

                    std::vector<ncnn::vk_constant_type> constants(13);
                    constants[0].i = in_gpu.w;
                    constants[1].i = in_gpu.h;
                    constants[2].i = in_gpu.cstep;
                    constants[3].i = in_tile_gpu.w;
                    constants[4].i = in_tile_gpu.h;
                    constants[5].i = in_tile_gpu.cstep;
                    constants[6].i = prepadding;
                    constants[7].i = prepadding;
                    constants[8].i = xi * TILE_SIZE_X;
                    constants[9].i = std::min(yi * TILE_SIZE_Y, prepadding);
                    constants[10].i = channels;
                    constants[11].i = in_alpha_tile_gpu.w;
                    constants[12].i = in_alpha_tile_gpu.h;

                    ncnn::VkMat dispatcher;
                    dispatcher.w = in_tile_gpu.w;
                    dispatcher.h = in_tile_gpu.h;
                    dispatcher.c = channels;

                    cmd.record_pipeline(realsr_preproc, bindings, constants, dispatcher);
                }

                // realsr
                ncnn::VkMat out_tile_gpu;
                {
                    ncnn::Extractor ex = net.create_extractor();

                    ex.set_blob_vkallocator(blob_vkallocator);
                    ex.set_workspace_vkallocator(blob_vkallocator);
                    ex.set_staging_vkallocator(staging_vkallocator);

                    ex.input(net_input_name.c_str(), in_tile_gpu);

                    ex.extract(net_output_name.c_str(), out_tile_gpu, cmd);
                }

                ncnn::VkMat out_alpha_tile_gpu;
                if (channels == 4)
                {
                    if (scale == 1)
                    {
                        out_alpha_tile_gpu = in_alpha_tile_gpu;
                    }
                    if (scale == 2)
                    {
                        bicubic_2x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                    }
                    if (scale == 3)
                    {
                        bicubic_3x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                    }
                    if (scale == 4)
                    {
                        bicubic_4x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                    }
                }

                // postproc
                {
                    std::vector<ncnn::VkMat> bindings(3);
                    bindings[0] = out_tile_gpu;
                    bindings[1] = out_alpha_tile_gpu;
                    bindings[2] = out_gpu;

                    std::vector<ncnn::vk_constant_type> constants(13);
                    constants[0].i = out_tile_gpu.w;
                    constants[1].i = out_tile_gpu.h;
                    constants[2].i = out_tile_gpu.cstep;
                    constants[3].i = out_gpu.w;
                    constants[4].i = out_gpu.h;
                    constants[5].i = out_gpu.cstep;
                    constants[6].i = xi * TILE_SIZE_X * scale;
                    constants[7].i = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
                    constants[8].i = prepadding * scale;
                    constants[9].i = prepadding * scale;
                    constants[10].i = channels;
                    constants[11].i = out_alpha_tile_gpu.w;
                    constants[12].i = out_alpha_tile_gpu.h;

                    ncnn::VkMat dispatcher;
                    dispatcher.w = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
                    dispatcher.h = out_gpu.h;
                    dispatcher.c = channels;

                    cmd.record_pipeline(realsr_postproc, bindings, constants, dispatcher);
                }
            }

            if (xtiles > 1) {
                cmd.submit_and_wait();
                cmd.reset();
            }
            high_resolution_clock::time_point end = high_resolution_clock::now();
            float time_span_print_progress = duration_cast<duration<double>>(
                    end - time_print_progress).count();
            float progress_tile = (float) (yi * xtiles + xi + 1);
            if (time_span_print_progress > 0.5 || (yi + 1 == ytiles && xi + 3 > xtiles)) {
                double progress = progress_tile / (ytiles * xtiles);
                double time_span = duration_cast<duration<double>>(end - begin).count();
                fprintf(stderr, "%5.2f%%\t[%5.2fs /%5.2f ETA]\n", progress * 100, time_span,
                        time_span / progress - time_span);
                time_print_progress = end;
            }
        }

        // download
        {
            ncnn::Mat out;

            if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage)
            {
                int out_tile_y0 = std::max(yi * TILE_SIZE_Y, 0);
                size_t offset = out_tile_y0 * scale * w * scale * channels;
                out = ncnn::Mat(out_gpu.w, out_gpu.h, (unsigned char*)outimage.data + offset, (size_t)channels, 1, opt.blob_allocator);
            }

            cmd.record_clone(out_gpu, out, opt);

            cmd.submit_and_wait();

            if (!((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage))
            {
                if (channels == 3)
                {
#if _WIN32
                    out.to_pixels((unsigned char*)outimage.data + yi * scale * TILE_SIZE_Y * w * scale * channels, ncnn::Mat::PIXEL_RGB2BGR);
#else
                    out.to_pixels((unsigned char*)outimage.data + yi * scale * TILE_SIZE_Y * w * scale * channels, ncnn::Mat::PIXEL_RGB);
#endif
                }
                if (channels == 4)
                {
#if _WIN32
                    out.to_pixels((unsigned char*)outimage.data + yi * scale * TILE_SIZE_Y * w * scale * channels, ncnn::Mat::PIXEL_RGBA2BGRA);
#else
                    out.to_pixels((unsigned char*)outimage.data + yi * scale * TILE_SIZE_Y * w * scale * channels, ncnn::Mat::PIXEL_RGBA);
#endif
                }
            }
        }
    }

    vkdev->reclaim_blob_allocator(blob_vkallocator);
    vkdev->reclaim_staging_allocator(staging_vkallocator);

    return 0;
}

int RealSR::process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage) const
{
    const unsigned char* pixeldata = (const unsigned char*)inimage.data;
    const int w = inimage.w;
    const int h = inimage.h;
    const int channels = inimage.elempack;

    const int TILE_SIZE_X = tilesize;
    const int TILE_SIZE_Y = tilesize;

    ncnn::Option opt = net.opt;

    // each tile 100x100
    const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;

    // 重叠区线性权重混合: 相邻 tile 在 padding 输出重叠带按权重平滑过渡,
    // 消除硬拼接产生的 tile 边界伪影(与 mnnsr 实现一致)。
    std::vector<float> accum((size_t)w * scale * h * scale * channels, 0.f);
    std::vector<float> wsum((size_t)w * scale * h * scale, 0.f);

    high_resolution_clock::time_point begin = high_resolution_clock::now();
    high_resolution_clock::time_point time_print_progress;

    for (int yi = 0; yi < ytiles; yi++)
    {
        const int tile_h_nopad = std::min((yi + 1) * TILE_SIZE_Y, h) - yi * TILE_SIZE_Y;

        int in_tile_y0 = std::max(yi * TILE_SIZE_Y - prepadding, 0);
        int in_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y + prepadding, h);



        for (int xi = 0; xi < xtiles; xi++)
        {
            const int tile_w_nopad = std::min((xi + 1) * TILE_SIZE_X, w) - xi * TILE_SIZE_X;

            int in_tile_x0 = std::max(xi * TILE_SIZE_X - prepadding, 0);
            int in_tile_x1 = std::min((xi + 1) * TILE_SIZE_X + prepadding, w);

            // crop tile
            ncnn::Mat in;
            {
                if (channels == 3)
                {
#if _WIN32
                    in = ncnn::Mat::from_pixels_roi(pixeldata, ncnn::Mat::PIXEL_BGR2RGB, w, h, in_tile_x0, in_tile_y0, in_tile_x1 - in_tile_x0, in_tile_y1 - in_tile_y0);
#else
                    in = ncnn::Mat::from_pixels_roi(pixeldata, ncnn::Mat::PIXEL_RGB, w, h, in_tile_x0, in_tile_y0, in_tile_x1 - in_tile_x0, in_tile_y1 - in_tile_y0);
#endif
                }
                if (channels == 4)
                {
#if _WIN32
                    in = ncnn::Mat::from_pixels_roi(pixeldata, ncnn::Mat::PIXEL_BGRA2RGBA, w, h, in_tile_x0, in_tile_y0, in_tile_x1 - in_tile_x0, in_tile_y1 - in_tile_y0);
#else
                    in = ncnn::Mat::from_pixels_roi(pixeldata, ncnn::Mat::PIXEL_RGBA, w, h, in_tile_x0, in_tile_y0, in_tile_x1 - in_tile_x0, in_tile_y1 - in_tile_y0);
#endif
                }
            }

            ncnn::Mat out;

            if (tta_mode)
            {
                // split alpha and preproc
                ncnn::Mat in_tile[8];
                ncnn::Mat in_alpha_tile, in_alpah_tile_nocrop;
                {
                    in_tile[0].create(in.w, in.h, 3);
                    for (int q = 0; q < 3; q++)
                    {
                        const float* ptr = in.channel(q);
                        float* outptr0 = in_tile[0].channel(q);

                        for (int i = 0; i < in.h; i++)
                        {
                            for (int j = 0; j < in.w; j++)
                            {
                                *outptr0++ = *ptr++ * (1 / 255.f);
                            }
                        }
                    }

                    if (channels == 4)
                    {
                        in_alpah_tile_nocrop = in.channel_range(3, 1).clone();
                        int crop_top=(yi*TILE_SIZE_Y-in_tile_y0);
                        int crop_bottom=in_tile_y1-std::min(yi*TILE_SIZE_Y+TILE_SIZE_Y ,h);
                        int crop_left=(xi*TILE_SIZE_X-in_tile_x0);
                        int crop_right=in_tile_x1-std::min(xi*TILE_SIZE_X+TILE_SIZE_X ,w);
                        ncnn::copy_cut_border(in_alpah_tile_nocrop, in_alpha_tile, crop_top, crop_bottom, crop_left, crop_right);

                    }
                }

                // border padding
                {
                    int pad_top = std::max(prepadding - yi * TILE_SIZE_Y, 0);
                    int pad_bottom = std::max(std::min((yi + 1) * TILE_SIZE_Y + prepadding - h, prepadding), 0);
                    int pad_left = std::max(prepadding - xi * TILE_SIZE_X, 0);
                    int pad_right = std::max(std::min((xi + 1) * TILE_SIZE_X + prepadding - w, prepadding), 0);

                    ncnn::Mat in_tile_padded;
                    ncnn::copy_make_border(in_tile[0], in_tile_padded, pad_top, pad_bottom, pad_left, pad_right, 2, 0.f, net.opt);
                    in_tile[0] = in_tile_padded;
                }

                // the other 7 directions
                {
                    in_tile[1].create(in_tile[0].w, in_tile[0].h, 3);
                    in_tile[2].create(in_tile[0].w, in_tile[0].h, 3);
                    in_tile[3].create(in_tile[0].w, in_tile[0].h, 3);
                    in_tile[4].create(in_tile[0].h, in_tile[0].w, 3);
                    in_tile[5].create(in_tile[0].h, in_tile[0].w, 3);
                    in_tile[6].create(in_tile[0].h, in_tile[0].w, 3);
                    in_tile[7].create(in_tile[0].h, in_tile[0].w, 3);

                    for (int q = 0; q < 3; q++)
                    {
                        const ncnn::Mat in_tile_0 = in_tile[0].channel(q);
                        ncnn::Mat in_tile_1 = in_tile[1].channel(q);
                        ncnn::Mat in_tile_2 = in_tile[2].channel(q);
                        ncnn::Mat in_tile_3 = in_tile[3].channel(q);
                        ncnn::Mat in_tile_4 = in_tile[4].channel(q);
                        ncnn::Mat in_tile_5 = in_tile[5].channel(q);
                        ncnn::Mat in_tile_6 = in_tile[6].channel(q);
                        ncnn::Mat in_tile_7 = in_tile[7].channel(q);

                        for (int i = 0; i < in_tile[0].h; i++)
                        {
                            const float* outptr0 = in_tile_0.row(i);
                            float* outptr1 = in_tile_1.row(in_tile[0].h - 1 - i);
                            float* outptr2 = in_tile_2.row(i) + in_tile[0].w - 1;
                            float* outptr3 = in_tile_3.row(in_tile[0].h - 1 - i) + in_tile[0].w - 1;

                            for (int j = 0; j < in_tile[0].w; j++)
                            {
                                float* outptr4 = in_tile_4.row(j) + i;
                                float* outptr5 = in_tile_5.row(in_tile[0].w - 1 - j) + i;
                                float* outptr6 = in_tile_6.row(j) + in_tile[0].h - 1 - i;
                                float* outptr7 = in_tile_7.row(in_tile[0].w - 1 - j) + in_tile[0].h - 1 - i;

                                float v = *outptr0++;

                                *outptr1++ = v;
                                *outptr2-- = v;
                                *outptr3-- = v;
                                *outptr4 = v;
                                *outptr5 = v;
                                *outptr6 = v;
                                *outptr7 = v;
                            }
                        }
                    }
                }

                // realsr
                ncnn::Mat out_tile[8];
                for (int ti = 0; ti < 8; ti++)
                {
                    ncnn::Extractor ex = net.create_extractor();

                    ex.input(net_input_name.c_str(), in_tile[ti]);

                    ex.extract(net_output_name.c_str(), out_tile[ti]);
                }

                ncnn::Mat out_alpha_tile;
                if (channels == 4)
                {
                    if (scale == 1)
                    {
                        out_alpha_tile = in_alpha_tile;
                    }
                    if (scale == 2)
                    {
                        bicubic_2x->forward(in_alpha_tile, out_alpha_tile, opt);
                    }
                    if (scale == 3)
                    {
                        bicubic_3x->forward(in_alpha_tile, out_alpha_tile, opt);
                    }
                    if (scale == 4)
                    {
                        bicubic_4x->forward(in_alpha_tile, out_alpha_tile, opt);
                    }
                }

                // postproc and merge alpha
                {
                    out.create(tile_w_nopad * scale, tile_h_nopad * scale, channels);
                    for (int q = 0; q < 3; q++)
                    {
                        const ncnn::Mat out_tile_0 = out_tile[0].channel(q);
                        const ncnn::Mat out_tile_1 = out_tile[1].channel(q);
                        const ncnn::Mat out_tile_2 = out_tile[2].channel(q);
                        const ncnn::Mat out_tile_3 = out_tile[3].channel(q);
                        const ncnn::Mat out_tile_4 = out_tile[4].channel(q);
                        const ncnn::Mat out_tile_5 = out_tile[5].channel(q);
                        const ncnn::Mat out_tile_6 = out_tile[6].channel(q);
                        const ncnn::Mat out_tile_7 = out_tile[7].channel(q);
                        float* outptr = out.channel(q);

                        for (int i = 0; i < out.h; i++)
                        {
                            const float* ptr0 = out_tile_0.row(i + prepadding * scale) + prepadding * scale;
                            const float* ptr1 = out_tile_1.row(out_tile[0].h - 1 - i - prepadding * scale) + prepadding * scale;
                            const float* ptr2 = out_tile_2.row(i + prepadding * scale) + out_tile[0].w - 1 - prepadding * scale;
                            const float* ptr3 = out_tile_3.row(out_tile[0].h - 1 - i - prepadding * scale) + out_tile[0].w - 1 - prepadding * scale;

                            for (int j = 0; j < out.w; j++)
                            {
                                const float* ptr4 = out_tile_4.row(j + prepadding * scale) + i + prepadding * scale;
                                const float* ptr5 = out_tile_5.row(out_tile[0].w - 1 - j - prepadding * scale) + i + prepadding * scale;
                                const float* ptr6 = out_tile_6.row(j + prepadding * scale) + out_tile[0].h - 1 - i - prepadding * scale;
                                const float* ptr7 = out_tile_7.row(out_tile[0].w - 1 - j - prepadding * scale) + out_tile[0].h - 1 - i - prepadding * scale;

                                float v = (*ptr0++ + *ptr1++ + *ptr2-- + *ptr3-- + *ptr4 + *ptr5 + *ptr6 + *ptr7) / 8;

                                *outptr++ = v * 255.f + 0.5f;
                            }
                        }
                    }

                    if (channels == 4)
                    {
                        memcpy(out.channel_range(3, 1), out_alpha_tile, out_alpha_tile.total() * sizeof(float));
                    }
                }

                // TTA 分支: 不参与重叠混合(无单一 out_tile 源), 直接硬拼写 outimage。
                // 归一化循环会跳过 wsum<=0.5 的区域, 保留此处结果。
                if (channels == 3)
                {
                    out.to_pixels((unsigned char*)outimage.data + yi * scale * TILE_SIZE_Y * w * scale * channels + xi * scale * TILE_SIZE_X * channels, ncnn::Mat::PIXEL_RGB, w * scale * channels);
                }
                if (channels == 4)
                {
                    out.to_pixels((unsigned char*)outimage.data + yi * scale * TILE_SIZE_Y * w * scale * channels + xi * scale * TILE_SIZE_X * channels, ncnn::Mat::PIXEL_RGBA, w * scale * channels);
                }
            }
            else
            {
                // split alpha and preproc
                ncnn::Mat in_tile;
                ncnn::Mat in_alpha_tile, in_alpah_tile_nocrop;
                {
                    in_tile.create(in.w, in.h, 3);
                    for (int q = 0; q < 3; q++)
                    {
                        const float* ptr = in.channel(q);
                        float* outptr = in_tile.channel(q);

                        for (int i = 0; i < in.w * in.h; i++)
                        {
                            *outptr++ = *ptr++ * (1 / 255.f);
                        }
                    }

                    if (channels == 4)
                    {
                        in_alpah_tile_nocrop = in.channel_range(3, 1).clone();
                        int crop_top=(yi*TILE_SIZE_Y-in_tile_y0);
                        int crop_bottom=in_tile_y1-std::min(yi*TILE_SIZE_Y+TILE_SIZE_Y ,h);
                        int crop_left=(xi*TILE_SIZE_X-in_tile_x0);
                        int crop_right=in_tile_x1-std::min(xi*TILE_SIZE_X+TILE_SIZE_X ,w);
                        ncnn::copy_cut_border(in_alpah_tile_nocrop, in_alpha_tile, crop_top, crop_bottom, crop_left, crop_right);

//                        fprintf(stderr,"in_alpah_tile_nocrop: %d/%d/%d, in_alpha_tile: %d/%d/%d, crop: %d/%d/%d/%d, TILE_SIZE_X %d, TILE_SIZE_Y %d\n"
//                                ,in_alpah_tile_nocrop.w,in_alpah_tile_nocrop.h,in_alpah_tile_nocrop.c
//                                ,in_alpha_tile.w,in_alpha_tile.h,in_alpha_tile.c
//                                ,crop_top,crop_bottom,crop_left,crop_right
//                                ,TILE_SIZE_X,TILE_SIZE_Y
//                                );
                    }
                }

                // border padding
                {
                    int pad_top = std::max(prepadding - yi * TILE_SIZE_Y, 0);
                    int pad_bottom = std::max(std::min((yi + 1) * TILE_SIZE_Y + prepadding - h, prepadding), 0);
                    int pad_left = std::max(prepadding - xi * TILE_SIZE_X, 0);
                    int pad_right = std::max(std::min((xi + 1) * TILE_SIZE_X + prepadding - w, prepadding), 0);

                    ncnn::Mat in_tile_padded;
                    ncnn::copy_make_border(in_tile, in_tile_padded, pad_top, pad_bottom, pad_left, pad_right, 2, 0.f, net.opt);
                    in_tile = in_tile_padded;
                }

                // realsr
                ncnn::Mat out_tile;
                {
                    ncnn::Extractor ex = net.create_extractor();

                    ex.input(net_input_name.c_str(), in_tile);

                    ex.extract(net_output_name.c_str(), out_tile);
                }

                ncnn::Mat out_alpha_tile;
                if (channels == 4)
                {
                    if (scale == 1)
                    {
                        out_alpha_tile = in_alpha_tile;
                    }
                    if (scale == 2)
                    {
                        bicubic_2x->forward(in_alpha_tile, out_alpha_tile, opt);
                    }
                    if (scale == 3)
                    {
                        bicubic_3x->forward(in_alpha_tile, out_alpha_tile, opt);
                    }
                    if (scale == 4)
                    {
                        bicubic_4x->forward(in_alpha_tile, out_alpha_tile, opt);
                    }
                }

                // postproc: RGB 通道无需构建(重叠混合直接读 out_tile),
                // 仅 4 通道时保留 alpha 通道(无 padding 源, 混合时直接累积 out.channel(3))
                if (channels == 4)
                {
                    out.create(tile_w_nopad * scale, tile_h_nopad * scale, channels);
                    const float* sp = out_alpha_tile;
                    float* op = out.channel(3);
                    memcpy(op, sp, out_alpha_tile.total() * sizeof(float));
                }

                // 重叠区线性权重混合(仅非 tta 路径; out_tile 含 padding 推理输出):
                // 目标矩形在有效区基础上外扩 overlapOut, 与相邻 tile 重叠;
                // 重叠带权重从边缘 0 线性升到有效区 1, 消除拼接缝。
                {
                    const int overlapOut = prepadding * scale < 4 ? 4 : prepadding * scale;
                    const int out_x0 = xi * TILE_SIZE_X * scale;
                    const int out_y0 = yi * TILE_SIZE_Y * scale;
                    const int tileW = tile_w_nopad * scale;
                    const int tileH = tile_h_nopad * scale;
                    int dstX = out_x0 - std::min(overlapOut, out_x0);
                    int dstY = out_y0 - std::min(overlapOut, out_y0);
                    int dstX2 = std::min(out_x0 + tileW + overlapOut, w * scale);
                    int dstY2 = std::min(out_y0 + tileH + overlapOut, h * scale);
                    // 源(out_tile)偏移: 推理输出含 padding, 有效区起点为 prepadding*scale
                    int srcX = prepadding * scale - (out_x0 - dstX);
                    int srcY = prepadding * scale - (out_y0 - dstY);
                    int leftOverlap = out_x0 - dstX, topOverlap = out_y0 - dstY;
                    int rightOverlap = dstX2 - (out_x0 + tileW);
                    int bottomOverlap = dstY2 - (out_y0 + tileH);
                    // 说明: dstX<=out_x0 且 srcX = prepadding*scale - (out_x0-dstX) >= 0,
                    // 因此 srcX/srcY 恒为非负, 无需越界修正。
                    const int ow = w * scale;
                    for (int yy = 0; yy < dstY2 - dstY; yy++)
                    {
                        float wy = 1.0f;
                        if (topOverlap > 0 && yy < topOverlap)
                            wy = (float)(yy + 1) / (float)(topOverlap + 1);
                        else if (bottomOverlap > 0 && yy >= topOverlap + tileH)
                            wy = (float)(dstY2 - dstY - yy) / (float)(bottomOverlap + 1);
                        if (wy < 0.02f) wy = 0.02f;
                        for (int xx = 0; xx < dstX2 - dstX; xx++)
                        {
                            float wx = 1.0f;
                            if (leftOverlap > 0 && xx < leftOverlap)
                                wx = (float)(xx + 1) / (float)(leftOverlap + 1);
                            else if (rightOverlap > 0 && xx >= leftOverlap + tileW)
                                wx = (float)(dstX2 - dstX - xx) / (float)(rightOverlap + 1);
                            if (wx < 0.02f) wx = 0.02f;
                            const float wgt = wx * wy;
                            const size_t di = ((size_t)(dstY + yy) * ow + (dstX + xx)) * channels;
                            const int sx = srcX + xx, sy = srcY + yy;
                            for (int q = 0; q < 3; q++)
                            {
                                const float* sp = out_tile.channel(q).row(sy);
                                accum[di + q] += sp[sx] * 255.f * wgt;
                            }
                            wsum[(size_t)(dstY + yy) * ow + (dstX + xx)] += wgt;
                        }
                    }
                    // alpha 通道(4 通道): 无 padding 源, 有效区直接累积(权重 1)
                    if (channels == 4)
                    {
                        const int ow = w * scale;
                        for (int yy = 0; yy < tileH; yy++)
                        {
                            const float* sp = out.channel(3).row(yy);
                            for (int xx = 0; xx < tileW; xx++)
                            {
                                const size_t di = ((size_t)(out_y0 + yy) * ow + (out_x0 + xx)) * channels;
                                accum[di + 3] += sp[xx];
                            }
                        }
                    }
                }
            }

            high_resolution_clock::time_point end = high_resolution_clock::now();
            float time_span_print_progress = duration_cast<duration<double>>(
                    end - time_print_progress).count();
            float progress_tile = (float) (yi * xtiles + xi + 1);
            if (time_span_print_progress > 0.5 || (yi + 1 == ytiles && xi + 3 > xtiles)) {
                double progress = progress_tile / (ytiles * xtiles);
                double time_span = duration_cast<duration<double>>(end - begin).count();
//                fprintf(stderr, "%5.2f%%\t[%5.2fs /%5.2f] y %d x %d\n", progress * 100,time_span,
//                        time_span / progress - time_span ,yi,xi, omp_get_thread_num());
                fprintf(stderr, "%5.2f%%\t[%5.2fs /%5.2f ETA]\n", progress * 100, time_span,
                        time_span / progress - time_span);
                time_print_progress = end;
            }
        }
    }

    // 重叠区加权归一化: 各像素加权累积除以总权重, 消除 tile 边界伪影
    {
        const int ow = w * scale, oh = h * scale;
        const size_t total = (size_t)ow * oh;
        for (size_t i = 0; i < total; i++)
        {
            float wsum_i = wsum[i];
            if (wsum_i <= 0.5f) continue;  // 未覆盖区域(理论不会发生)保持原值
            const size_t base = i * channels;
            for (int q = 0; q < 3; q++)
            {
                float v = accum[base + q] / wsum_i;
                if (v < 0.f) v = 0.f;
                if (v > 255.f) v = 255.f;
                ((unsigned char*)outimage.data)[base + q] = (unsigned char)(v + 0.5f);
            }
        }
        if (channels == 4)
        {
            // alpha 通道直接复制(累积权重为 1, 无需归一化)
            for (size_t i = 0; i < total; i++)
            {
                ((unsigned char*)outimage.data)[i * channels + 3] =
                        (unsigned char)(accum[i * channels + 3] + 0.5f);
            }
        }
    }

    return 0;
}
