cmake_minimum_required(VERSION 3.5)
project(mnnsr-ncnn)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_INSTALL_RPATH "$ORIGIN")
#set(CMAKE_BUILD_TYPE Debug)
#set(CMAKE_BUILD_TYPE Release)

include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../CMake/arch.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../CMake/libwebp.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../CMake/opencv.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../CMake/mnn.cmake)

if(NOT ANDROID)
    add_executable(${PROJECT_NAME} main.cpp mnnsr.cpp dcp.cpp)
endif()


if (MSVC)  # Visual Studio
    target_link_libraries(${PROJECT_NAME} webp ${OpenCV_LIBS} ${MNN_LIB})
    
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
        # 1. 先手动创建目标文件夹
        COMMAND ${CMAKE_COMMAND} -E make_directory ${ASSETS_DIR}
        
        # DLL 路径
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${mnn_DIR}/lib/${TARGET_ARCH}/Release/Dynamic/MT/MNN.dll"
        ${ASSETS_DIR}/

        COMMAND_EXPAND_LISTS
    )

elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(${PROJECT_NAME} webp ${OpenCV_LIBS} ${MNN_LIB})
    set_target_properties(${PROJECT_NAME} PROPERTIES
        BUILD_RPATH "$ORIGIN/lib"
    )

elseif(ANDROID)
    # Android 可执行文件 mnnsr-ncnn: 供 GUI 以 CLI 子进程方式运行
    # (经 nsrun + libnspatch.so 包装, 绕过 app linker namespace 限制加载 vendor libOpenCL.so 等)。
    # OpenCV 静态链接(拆分库, 仅 core/imgproc/imgcodecs), MNN 链接同目录 .so,
    # 运行期依赖同目录 libMNN*.so / libc++_shared.so / libOpenCL.so(vendor, 经 nsrun 命名空间注入)。
    if(NOT DEFINED mnn_DIR)
        set(mnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../3rdparty/mnn_android)
    endif()
    set(OPENCV_STATIC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../3rdparty/OpenCV-android-sdk/sdk/native/staticlibs/${ANDROID_ABI})
    set(OPENCV_3RDPARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../3rdparty/OpenCV-android-sdk/sdk/native/3rdparty/libs/${ANDROID_ABI})
    set(MNN_SO ${mnn_DIR}/${ANDROID_ABI}/libMNN.so)
    set(MNN_CL_SO ${mnn_DIR}/${ANDROID_ABI}/libMNN_CL.so)
    set(MNN_VK_SO ${mnn_DIR}/${ANDROID_ABI}/libMNN_Vulkan.so)
    set(MNN_CXX_SHARED ${mnn_DIR}/${ANDROID_ABI}/libc++_shared.so)

    add_executable(${PROJECT_NAME} main.cpp mnnsr.cpp dcp.cpp)
    target_include_directories(${PROJECT_NAME} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../common
        ${OpenCV_INCLUDE_DIRS}
    )
    target_link_libraries(${PROJECT_NAME} PRIVATE
        -Wl,--start-group
        ${OPENCV_STATIC_DIR}/libopencv_imgcodecs.a
        ${OPENCV_STATIC_DIR}/libopencv_imgproc.a
        ${OPENCV_STATIC_DIR}/libopencv_core.a
        ${OPENCV_3RDPARTY_DIR}/liblibjpeg-turbo.a
        ${OPENCV_3RDPARTY_DIR}/liblibpng.a
        ${OPENCV_3RDPARTY_DIR}/liblibtiff.a
        ${OPENCV_3RDPARTY_DIR}/liblibwebp.a
        ${OPENCV_3RDPARTY_DIR}/liblibopenjp2.a
        ${OPENCV_3RDPARTY_DIR}/libIlmImf.a
        ${OPENCV_3RDPARTY_DIR}/libtegra_hal.a
        ${OPENCV_3RDPARTY_DIR}/libcpufeatures.a
        ${OPENCV_3RDPARTY_DIR}/libittnotify.a
        ${OPENCV_3RDPARTY_DIR}/libtbb.a
        -Wl,--end-group
        webp
        ${MNN_SO} ${MNN_CL_SO} ${MNN_VK_SO} ${MNN_CXX_SHARED}
        log dl z
    )

    # Android: 将可执行文件与运行期依赖拷贝到 GUI assets(AssetsCopyer 释放到 cacheDir/realsr)。
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${ASSETS_DIR}
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:${PROJECT_NAME}>
            ${ASSETS_DIR}/mnnsr-ncnn
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${MNN_SO} ${ASSETS_DIR}/libMNN.so
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${MNN_CL_SO} ${ASSETS_DIR}/libMNN_CL.so
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${MNN_VK_SO} ${ASSETS_DIR}/libMNN_Vulkan.so
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${MNN_CXX_SHARED} ${ASSETS_DIR}/libc++_shared.so
    )

else ()
    target_link_libraries(${PROJECT_NAME} webp MNN MNN_CL MNN_Vulkan ${OpenCV_LIBS})
    
endif ()

# 可执行文件的 POST_BUILD 拷贝逻辑仅在非 Android 分支需要
# (Android 分支在 ANDROID 分支内自行拷贝到 GUI assets)
if(NOT ANDROID)
    include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../CMake/assets.cmake)
endif()

