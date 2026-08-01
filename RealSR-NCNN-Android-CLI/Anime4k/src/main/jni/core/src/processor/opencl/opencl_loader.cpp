// Anime4KCPP Android: OpenCL 动态加载桥接
//
// 目标系统为 Android 9 (API 28)，<uses-native-library> 在该版本无效，
// 且可执行文件位于 app 数据目录时 linker 使用 app 的 classloader namespace，
// 无法在启动期解析 /vendor/lib64 下的 libOpenCL.so。
// 因此参考 MNN（libMNN_CL.so）的做法：编译期不链接 libOpenCL.so（消除
// DT_NEEDED 硬链接），运行时通过 dlopen + dlsym 加载设备厂商实现。
//
// 本文件为所有 Anime4KCPP 用到的 cl* 符号提供转发实现：首次调用时
// dlopen("libOpenCL.so")，之后通过 dlsym 获取真实函数指针并转发。

#include <cstdio>
#include <cstring>

#include <dlfcn.h>

#include <android/dlext.h>

#include <CL/cl.h>

namespace
{
    // android_get_exported_namespace 不在 NDK 公开头文件中（私有 API），
    // 且部分设备/Android 版本不导出该符号，故运行时 dlsym 解析。
    // android_namespace_t 已由 <android/dlext.h> 前向声明（全局作用域）。
    using AndroidGetExportedNamespaceFn = ::android_namespace_t* (*)(const char*);
    using AndroidDlopenExtFn = void* (*)(const char*, int, const android_dlextinfo*);

    // 优先尝试从导出的 vendor/vndk/sphal 命名空间加载 libOpenCL.so。
    // 成功返回句柄；符号缺失或加载失败返回 nullptr（由调用方回退）。
    void* try_vendor_namespace() noexcept
    {
        auto getNs = reinterpret_cast<AndroidGetExportedNamespaceFn>(
            dlsym(RTLD_DEFAULT, "android_get_exported_namespace"));
        auto dlopenExt = reinterpret_cast<AndroidDlopenExtFn>(
            dlsym(RTLD_DEFAULT, "android_dlopen_ext"));
        if (!getNs || !dlopenExt) return nullptr;

        const char* nsNames[] = { "vndk", "vendor", "sphal", nullptr };
        for (int i = 0; nsNames[i]; ++i)
        {
            android_namespace_t* ns = getNs(nsNames[i]);
            if (!ns) continue;
            android_dlextinfo info{};
            info.flags = ANDROID_DLEXT_USE_NAMESPACE;
            info.library_namespace = ns;
            void* h = dlopenExt("libOpenCL.so", RTLD_NOW, &info);
            if (h)
            {
                std::fprintf(stderr, "[Anime4KCPP] loaded libOpenCL.so via exported namespace %s\n", nsNames[i]);
                return h;
            }
            std::fprintf(stderr, "[Anime4KCPP] android_dlopen_ext(%s) failed: %s\n", nsNames[i], dlerror());
        }
        return nullptr;
    }

    // 复制文件并校验：仅当源文件存在、非空且为 ELF 64 位库时才复制，
    // 自动跳过不可用（32 位/空/缺失）库。
    bool copy_usable_opencl(const char* src, const char* dst)
    {
        std::FILE* in = std::fopen(src, "rb");
        if (!in) return false;

        unsigned char hdr[20] = {0};
        std::size_t n = std::fread(hdr, 1, sizeof(hdr), in);
        bool usable = (n == sizeof(hdr)) &&
            hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F' &&
            hdr[4] == 2; // ELFCLASS64
        if (!usable)
        {
            std::fclose(in);
            return false;
        }

        std::FILE* out = std::fopen(dst, "wb");
        if (!out)
        {
            std::fclose(in);
            return false;
        }
        std::rewind(in);
        char buf[8192];
        std::size_t r;
        while ((r = std::fread(buf, 1, sizeof(buf), in)) > 0)
            std::fwrite(buf, 1, r, out);
        std::fclose(in);
        std::fclose(out);
        return true;
    }

    void* opencl_handle() noexcept
    {
        static void* handle = []() -> void* {
            // 优先尝试从导出的 vendor/vndk/sphal 命名空间加载 libOpenCL.so
            // （Android 10+ 上 app 数据目录进程受 linker namespace 隔离，
            // android_dlopen_ext + ANDROID_DLEXT_USE_NAMESPACE 可绕过）。
            if (void* h = try_vendor_namespace())
                return h;

            // 与 MNN OpenCLWrapper.cpp 的加载方式一致：RTLD_NOW | RTLD_LOCAL，
            // Android 候选路径覆盖 Qualcomm Adreno / Mali 各厂商实现。
            const char* candidates[] = {
                "libOpenCL.so",
                "libGLES_mali.so",
                "libmali.so",
                "libOpenCL-pixel.so",
                "/system/vendor/lib64/libOpenCL.so",
                "/vendor/lib64/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/system/vendor/lib64/egl/libGLES_mali.so",
                "/vendor/lib64/egl/libGLES_mali.so",
                "/system/lib64/egl/libGLES_mali.so",
                "/system/vendor/lib/libOpenCL.so",
                "/vendor/lib/libOpenCL.so",
                "/system/lib/libOpenCL.so",
            };
            for (auto path : candidates)
            {
                void* h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
                if (!h)
                {
                    std::fprintf(stderr, "[Anime4KCPP] dlopen(%s) failed: %s\n", path, dlerror());
                    continue;
                }
                // 验证可用性：dlopen 成功但 clGetPlatformIDs 不可用（如 Mali 的
                // libOpenCL.so 封装内部 dlopen 绝对路径驱动失败）时自动跳过，
                // 继续尝试下一个候选（libGLES_mali.so 为 Mali 完整实现）。
                using PlatformIDsFn = cl_int (*)(cl_uint, cl_platform_id*, cl_uint*);
                auto fn = reinterpret_cast<PlatformIDsFn>(dlsym(h, "clGetPlatformIDs"));
                cl_uint count = 0;
                if (fn && fn(0, nullptr, &count) == CL_SUCCESS && count > 0)
                    return h;
                std::fprintf(stderr, "[Anime4KCPP] dlopen(%s) no usable OpenCL platform, skip\n", path);
                dlclose(h);
            }

            // 兜底：以上 dlopen 全部失败（常见于 Android 10+ linker namespace
            // 隔离，app 数据目录下无法直接 dlopen /vendor/lib64 系统库）。
            // 从系统候选路径复制可用的 OpenCL 库到运行目录（当前工作目录，
            // LD_LIBRARY_PATH 指向运行目录），再 dlopen 副本。
            // 复制前校验 ELF64 且非空，dlopen 失败则清理副本，自动跳过不可用库。
            const char* fallback_sources[] = {
                "/vendor/lib64/libOpenCL.so",
                "/system/vendor/lib64/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/vendor/lib64/egl/libGLES_mali.so",
                "/system/vendor/lib64/egl/libGLES_mali.so",
                "/system/lib64/egl/libGLES_mali.so",
            };
            const char* fallback_names[] = {
                "libOpenCL.so",
                "libOpenCL.so",
                "libOpenCL.so",
                "libGLES_mali.so",
                "libGLES_mali.so",
                "libGLES_mali.so",
            };
            for (int i = 0; i < 6; ++i)
            {
                if (!copy_usable_opencl(fallback_sources[i], fallback_names[i]))
                {
                    std::fprintf(stderr, "[Anime4KCPP] copy %s -> ./%s skipped (missing/invalid/32bit)\n",
                        fallback_sources[i], fallback_names[i]);
                    continue;
                }
                std::fprintf(stderr, "[Anime4KCPP] copied %s -> ./%s\n", fallback_sources[i], fallback_names[i]);
                void* h = dlopen(fallback_names[i], RTLD_NOW | RTLD_LOCAL);
                if (h) return h;
                std::fprintf(stderr, "[Anime4KCPP] dlopen(./%s) failed after copy: %s\n", fallback_names[i], dlerror());
                std::remove(fallback_names[i]); // 自动清理不可用副本
            }
            return static_cast<void*>(nullptr);
        }();
        return handle;
    }

    void* opencl_symbol(const char* name) noexcept
    {
        void* h = opencl_handle();
        if (!h) return nullptr;
        void* s = dlsym(h, name);
        if (!s) std::fprintf(stderr, "[Anime4KCPP] dlsym %s failed: %s\n", name, dlerror());
        return s;
    }
} // namespace

#define AC_OPENCL_FORWARD(name, ...)                                          \
    extern "C" ::name(__VA_ARGS__)                                            \
    {                                                                         \
        using Fn = decltype(&::name);                                         \
        static Fn fn = reinterpret_cast<Fn>(opencl_symbol(#name));            \
        if (!fn) return {};                                                   \
        return fn;                                                            \
    }

extern "C" cl_int clGetPlatformIDs(cl_uint num_entries, cl_platform_id* platforms, cl_uint* num_platforms)
{
    using Fn = cl_int (*)(cl_uint, cl_platform_id*, cl_uint*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clGetPlatformIDs"));
    if (!fn) return CL_INVALID_PLATFORM;
    return fn(num_entries, platforms, num_platforms);
}

extern "C" cl_int clGetPlatformInfo(cl_platform_id platform, cl_platform_info param_name,
    size_t param_value_size, void* param_value, size_t* param_value_size_ret)
{
    using Fn = cl_int (*)(cl_platform_id, cl_platform_info, size_t, void*, size_t*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clGetPlatformInfo"));
    if (!fn) return CL_INVALID_PLATFORM;
    return fn(platform, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" cl_int clGetDeviceIDs(cl_platform_id platform, cl_device_type device_type,
    cl_uint num_entries, cl_device_id* devices, cl_uint* num_devices)
{
    using Fn = cl_int (*)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clGetDeviceIDs"));
    if (!fn) return CL_INVALID_PLATFORM;
    return fn(platform, device_type, num_entries, devices, num_devices);
}

extern "C" cl_int clGetDeviceInfo(cl_device_id device, cl_device_info param_name,
    size_t param_value_size, void* param_value, size_t* param_value_size_ret)
{
    using Fn = cl_int (*)(cl_device_id, cl_device_info, size_t, void*, size_t*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clGetDeviceInfo"));
    if (!fn) return CL_INVALID_DEVICE;
    return fn(device, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" cl_context clCreateContext(const cl_context_properties* properties,
    cl_uint num_devices, const cl_device_id* devices,
    void(CL_CALLBACK* pfn_notify)(const char*, const void*, size_t, void*),
    void* user_data, cl_int* errcode_ret)
{
    using Fn = cl_context (*)(const cl_context_properties*, cl_uint, const cl_device_id*,
        void(CL_CALLBACK*)(const char*, const void*, size_t, void*), void*, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCreateContext"));
    if (!fn) return nullptr;
    return fn(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

extern "C" cl_int clGetContextInfo(cl_context context, cl_context_info param_name,
    size_t param_value_size, void* param_value, size_t* param_value_size_ret)
{
    using Fn = cl_int (*)(cl_context, cl_context_info, size_t, void*, size_t*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clGetContextInfo"));
    if (!fn) return CL_INVALID_CONTEXT;
    return fn(context, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" cl_int clRetainContext(cl_context context)
{
    using Fn = cl_int (*)(cl_context);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clRetainContext"));
    if (!fn) return CL_INVALID_CONTEXT;
    return fn(context);
}

extern "C" cl_int clReleaseContext(cl_context context)
{
    using Fn = cl_int (*)(cl_context);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clReleaseContext"));
    if (!fn) return CL_INVALID_CONTEXT;
    return fn(context);
}

extern "C" cl_int clRetainDevice(cl_device_id device)
{
    using Fn = cl_int (*)(cl_device_id);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clRetainDevice"));
    if (!fn) return CL_INVALID_DEVICE;
    return fn(device);
}

extern "C" cl_int clReleaseDevice(cl_device_id device)
{
    using Fn = cl_int (*)(cl_device_id);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clReleaseDevice"));
    if (!fn) return CL_INVALID_DEVICE;
    return fn(device);
}

extern "C" cl_command_queue clCreateCommandQueueWithProperties(cl_context context,
    cl_device_id device, const cl_queue_properties* properties, cl_int* errcode_ret)
{
    using Fn = cl_command_queue (*)(cl_context, cl_device_id, const cl_queue_properties*, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCreateCommandQueueWithProperties"));
    if (!fn) return nullptr;
    return fn(context, device, properties, errcode_ret);
}

extern "C" cl_command_queue clCreateCommandQueue(cl_context context,
    cl_device_id device, cl_command_queue_properties properties, cl_int* errcode_ret)
{
    using Fn = cl_command_queue (*)(cl_context, cl_device_id, cl_command_queue_properties, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCreateCommandQueue"));
    if (!fn) return nullptr;
    return fn(context, device, properties, errcode_ret);
}

extern "C" cl_int clReleaseCommandQueue(cl_command_queue command_queue)
{
    using Fn = cl_int (*)(cl_command_queue);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clReleaseCommandQueue"));
    if (!fn) return CL_INVALID_COMMAND_QUEUE;
    return fn(command_queue);
}

extern "C" cl_mem clCreateBuffer(cl_context context, cl_mem_flags flags,
    size_t size, void* host_ptr, cl_int* errcode_ret)
{
    using Fn = cl_mem (*)(cl_context, cl_mem_flags, size_t, void*, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCreateBuffer"));
    if (!fn) return nullptr;
    return fn(context, flags, size, host_ptr, errcode_ret);
}

extern "C" cl_mem clCreateImage(cl_context context, cl_mem_flags flags,
    const cl_image_format* image_format, const cl_image_desc* image_desc,
    void* host_ptr, cl_int* errcode_ret)
{
    using Fn = cl_mem (*)(cl_context, cl_mem_flags, const cl_image_format*,
        const cl_image_desc*, void*, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCreateImage"));
    if (!fn) return nullptr;
    return fn(context, flags, image_format, image_desc, host_ptr, errcode_ret);
}

extern "C" cl_mem clCreateImage2D(cl_context context, cl_mem_flags flags,
    const cl_image_format* image_format, size_t image_width, size_t image_height,
    size_t image_row_pitch, void* host_ptr, cl_int* errcode_ret)
{
    using Fn = cl_mem (*)(cl_context, cl_mem_flags, const cl_image_format*,
        size_t, size_t, size_t, void*, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCreateImage2D"));
    if (!fn) return nullptr;
    return fn(context, flags, image_format, image_width, image_height,
        image_row_pitch, host_ptr, errcode_ret);
}

extern "C" cl_int clReleaseMemObject(cl_mem memobj)
{
    using Fn = cl_int (*)(cl_mem);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clReleaseMemObject"));
    if (!fn) return CL_INVALID_MEM_OBJECT;
    return fn(memobj);
}

extern "C" cl_program clCreateProgramWithSource(cl_context context, cl_uint count,
    const char** strings, const size_t* lengths, cl_int* errcode_ret)
{
    using Fn = cl_program (*)(cl_context, cl_uint, const char**, const size_t*, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCreateProgramWithSource"));
    if (!fn) return nullptr;
    return fn(context, count, strings, lengths, errcode_ret);
}

extern "C" cl_program clLinkProgram(cl_context context, cl_uint num_devices,
    const cl_device_id* device_list, const char* options, cl_uint num_input_programs,
    const cl_program* input_programs,
    void(CL_CALLBACK* pfn_notify)(cl_program, void*), void* user_data, cl_int* errcode_ret)
{
    using Fn = cl_program (*)(cl_context, cl_uint, const cl_device_id*, const char*,
        cl_uint, const cl_program*, void(CL_CALLBACK*)(cl_program, void*), void*, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clLinkProgram"));
    if (!fn) return nullptr;
    return fn(context, num_devices, device_list, options, num_input_programs,
        input_programs, pfn_notify, user_data, errcode_ret);
}

extern "C" cl_int clCompileProgram(cl_program program, cl_uint num_devices,
    const cl_device_id* device_list, const char* options, cl_uint num_input_headers,
    const cl_program* input_headers, const char** header_include_names,
    void(CL_CALLBACK* pfn_notify)(cl_program, void*), void* user_data)
{
    using Fn = cl_int (*)(cl_program, cl_uint, const cl_device_id*, const char*,
        cl_uint, const cl_program*, const char**, void(CL_CALLBACK*)(cl_program, void*), void*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCompileProgram"));
    if (!fn) return CL_INVALID_PROGRAM;
    return fn(program, num_devices, device_list, options, num_input_headers,
        input_headers, header_include_names, pfn_notify, user_data);
}

extern "C" cl_int clRetainProgram(cl_program program)
{
    using Fn = cl_int (*)(cl_program);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clRetainProgram"));
    if (!fn) return CL_INVALID_PROGRAM;
    return fn(program);
}

extern "C" cl_int clReleaseProgram(cl_program program)
{
    using Fn = cl_int (*)(cl_program);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clReleaseProgram"));
    if (!fn) return CL_INVALID_PROGRAM;
    return fn(program);
}

extern "C" cl_int clBuildProgram(cl_program program, cl_uint num_devices,
    const cl_device_id* device_list, const char* options,
    void(CL_CALLBACK* pfn_notify)(cl_program, void*), void* user_data)
{
    using Fn = cl_int (*)(cl_program, cl_uint, const cl_device_id*, const char*,
        void(CL_CALLBACK*)(cl_program, void*), void*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clBuildProgram"));
    if (!fn) return CL_INVALID_PROGRAM;
    return fn(program, num_devices, device_list, options, pfn_notify, user_data);
}

extern "C" cl_int clGetProgramInfo(cl_program program, cl_program_info param_name,
    size_t param_value_size, void* param_value, size_t* param_value_size_ret)
{
    using Fn = cl_int (*)(cl_program, cl_program_info, size_t, void*, size_t*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clGetProgramInfo"));
    if (!fn) return CL_INVALID_PROGRAM;
    return fn(program, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" cl_int clGetProgramBuildInfo(cl_program program, cl_device_id device,
    cl_program_build_info param_name, size_t param_value_size,
    void* param_value, size_t* param_value_size_ret)
{
    using Fn = cl_int (*)(cl_program, cl_device_id, cl_program_build_info, size_t, void*, size_t*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clGetProgramBuildInfo"));
    if (!fn) return CL_INVALID_PROGRAM;
    return fn(program, device, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" cl_kernel clCreateKernel(cl_program program, const char* kernel_name, cl_int* errcode_ret)
{
    using Fn = cl_kernel (*)(cl_program, const char*, cl_int*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clCreateKernel"));
    if (!fn) return nullptr;
    return fn(program, kernel_name, errcode_ret);
}

extern "C" cl_int clSetKernelArg(cl_kernel kernel, cl_uint arg_index,
    size_t arg_size, const void* arg_value)
{
    using Fn = cl_int (*)(cl_kernel, cl_uint, size_t, const void*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clSetKernelArg"));
    if (!fn) return CL_INVALID_KERNEL;
    return fn(kernel, arg_index, arg_size, arg_value);
}

extern "C" cl_int clReleaseKernel(cl_kernel kernel)
{
    using Fn = cl_int (*)(cl_kernel);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clReleaseKernel"));
    if (!fn) return CL_INVALID_KERNEL;
    return fn(kernel);
}

extern "C" cl_int clEnqueueNDRangeKernel(cl_command_queue command_queue, cl_kernel kernel,
    cl_uint work_dim, const size_t* global_work_offset, const size_t* global_work_size,
    const size_t* local_work_size, cl_uint num_events_in_wait_list,
    const cl_event* event_wait_list, cl_event* event)
{
    using Fn = cl_int (*)(cl_command_queue, cl_kernel, cl_uint, const size_t*,
        const size_t*, const size_t*, cl_uint, const cl_event*, cl_event*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clEnqueueNDRangeKernel"));
    if (!fn) return CL_INVALID_COMMAND_QUEUE;
    return fn(command_queue, kernel, work_dim, global_work_offset, global_work_size,
        local_work_size, num_events_in_wait_list, event_wait_list, event);
}

extern "C" cl_int clEnqueueWriteImage(cl_command_queue command_queue, cl_mem image,
    cl_bool blocking_write, const size_t* origin, const size_t* region,
    size_t input_row_pitch, size_t input_slice_pitch, const void* ptr,
    cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event)
{
    using Fn = cl_int (*)(cl_command_queue, cl_mem, cl_bool, const size_t*, const size_t*,
        size_t, size_t, const void*, cl_uint, const cl_event*, cl_event*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clEnqueueWriteImage"));
    if (!fn) return CL_INVALID_COMMAND_QUEUE;
    return fn(command_queue, image, blocking_write, origin, region,
        input_row_pitch, input_slice_pitch, ptr, num_events_in_wait_list, event_wait_list, event);
}

extern "C" cl_int clEnqueueReadImage(cl_command_queue command_queue, cl_mem image,
    cl_bool blocking_read, const size_t* origin, const size_t* region,
    size_t row_pitch, size_t slice_pitch, void* ptr,
    cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event)
{
    using Fn = cl_int (*)(cl_command_queue, cl_mem, cl_bool, const size_t*, const size_t*,
        size_t, size_t, void*, cl_uint, const cl_event*, cl_event*);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clEnqueueReadImage"));
    if (!fn) return CL_INVALID_COMMAND_QUEUE;
    return fn(command_queue, image, blocking_read, origin, region,
        row_pitch, slice_pitch, ptr, num_events_in_wait_list, event_wait_list, event);
}

extern "C" cl_int clReleaseEvent(cl_event event)
{
    using Fn = cl_int (*)(cl_event);
    static Fn fn = reinterpret_cast<Fn>(opencl_symbol("clReleaseEvent"));
    if (!fn) return CL_INVALID_EVENT;
    return fn(event);
}
