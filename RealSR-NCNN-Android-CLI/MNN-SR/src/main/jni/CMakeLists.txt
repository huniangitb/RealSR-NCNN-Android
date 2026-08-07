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

# Android 分支只构建 JNI 动态库 libmnnsr.so（供 GUI app 进程内调用），
# 不再构建 mnnsr-ncnn 可执行文件（可执行文件由 assets 方案提供）。
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
    # Android JNI 动态库：供 GUI 在 app 进程内直接调用 mnnsr (MNN 超分)。
    # 相比独立可执行文件（exec 子进程），app 进程内 dlopen libMNN.so /
    # libMNN_Vulkan.so 继承 app 的 classloader namespace，且 manifest 的
    # <uses-native-library> 声明生效，可绕过 Android 10+ 的 linker
    # namespace 隔离（与 Anime4k 的 JNI 迁移方式一致）。
    add_library(mnnsr SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/MnnsrProcessor.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/mnnsr.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/dcp.cpp
    )
    target_include_directories(mnnsr PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../common
        ${OpenCV_INCLUDE_DIRS}
    )

    # Android: 静态链接 OpenCV 拆分库（仅 mnnsr 需要的 core/imgproc/imgcodecs），
    # 替代 20M 的合并动态库 libopencv_java4.so，减小 APK 体积。
    # MNN 库直接链接绝对路径 .so，避免 find_library 拆分名匹配失败。
    if(NOT DEFINED mnn_DIR)
        set(mnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../3rdparty/mnn_android)
    endif()
    set(OPENCV_STATIC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../3rdparty/OpenCV-android-sdk/sdk/native/staticlibs/${ANDROID_ABI})
    set(OPENCV_3RDPARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../3rdparty/OpenCV-android-sdk/sdk/native/3rdparty/libs/${ANDROID_ABI})
    set(MNN_SO ${mnn_DIR}/${ANDROID_ABI}/libMNN.so)
    set(MNN_CL_SO ${mnn_DIR}/${ANDROID_ABI}/libMNN_CL.so)
    set(MNN_VK_SO ${mnn_DIR}/${ANDROID_ABI}/libMNN_Vulkan.so)
    target_link_libraries(mnnsr PRIVATE
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
        ${MNN_SO} ${MNN_CL_SO} ${MNN_VK_SO}
        log dl z
    )

    # Android: 将 JNI 库拷贝到 GUI 项目的 jniLibs，供 System.loadLibrary 加载。
    # libmnnsr.so 依赖 libMNN.so / libMNN_Vulkan.so / libMNN_CL.so，这些库
    # 同样需放入 jniLibs 目录（由 mnn.cmake 的 IMPORTED 定义 + 手动拷贝）。
    # OpenCV 为静态链接，无需再拷贝 libopencv_java4.so。
    set(MNNSR_GUI_JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../RealSR-NCNN-Android-GUI/app/src/main/jniLibs/${ANDROID_ABI})

    add_custom_command(TARGET mnnsr POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${MNNSR_GUI_JNILIBS_DIR}
            COMMAND ${CMAKE_COMMAND} -E copy
            $<TARGET_FILE:mnnsr>
            ${MNNSR_GUI_JNILIBS_DIR}/libmnnsr.so
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${mnn_DIR}/${ANDROID_ABI}/libMNN.so
            ${MNNSR_GUI_JNILIBS_DIR}/libMNN.so
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${mnn_DIR}/${ANDROID_ABI}/libMNN_CL.so
            ${MNNSR_GUI_JNILIBS_DIR}/libMNN_CL.so
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${mnn_DIR}/${ANDROID_ABI}/libMNN_Vulkan.so
            ${MNNSR_GUI_JNILIBS_DIR}/libMNN_Vulkan.so
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${mnn_DIR}/${ANDROID_ABI}/libc++_shared.so
            ${MNNSR_GUI_JNILIBS_DIR}/libc++_shared.so
    )

else ()
    target_link_libraries(${PROJECT_NAME} webp MNN MNN_CL MNN_Vulkan ${OpenCV_LIBS})
    
endif ()

# 可执行文件的 POST_BUILD 拷贝逻辑仅在非 Android 分支需要
# （Android 分支只构建 JNI 动态库 libmnnsr.so，无需拷贝可执行文件）
if(NOT ANDROID)
    include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../CMake/assets.cmake)
endif()

