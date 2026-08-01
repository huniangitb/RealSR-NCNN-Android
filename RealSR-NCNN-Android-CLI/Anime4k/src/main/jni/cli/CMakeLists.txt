project(ac_cli LANGUAGES CXX)

set(CLI_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR})
set(CLI_BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR})

include(${DEPENDENCY_DIR}/cli11.cmake)

add_executable(ac_cli
    ${CLI_SOURCE_DIR}/src/Main.cpp
    ${CLI_SOURCE_DIR}/src/Options.cpp
    ${CLI_SOURCE_DIR}/src/ProgressBar.cpp
)

target_include_directories(ac_cli PRIVATE
    $<BUILD_INTERFACE:${CLI_SOURCE_DIR}/include>
)

target_link_libraries(ac_cli PRIVATE
    ac
    ac_util_misc ac_util_threads
    $<$<TARGET_EXISTS:ac_video>:ac_video>
    dep::cli11
)

target_compile_definitions(ac_cli PRIVATE
    $<$<TARGET_EXISTS:ac_video>:AC_CLI_ENABLE_VIDEO>
)

ac_check_enable_static_crt(ac_cli)

install(
    TARGETS ac_cli
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)

# Android: GUI 已改为 JNI 调用 libanime4k.so（app 进程内加载，绕过 linker
# namespace 隔离），不再需要独立可执行文件 Anime4k，故不再拷贝 ac_cli。
# 仅拷贝 OpenMP 运行时 libomp.so 到 assets/realsr（其他 ncnn 后端仍以独立
# 可执行文件方式运行，运行目录 LD_LIBRARY_PATH 需要 libomp.so）。
# 注意：libOpenCL.so / libGLES_mali.so 不打包（运行时使用设备厂商实现，
# 如 /vendor/lib64/libOpenCL.so 动态链接）。
set(AC_GUI_ASSETS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../RealSR-NCNN-Android-GUI/app/src/main/assets/realsr)

if(NOT DEFINED ANDROID_NDK AND DEFINED CMAKE_ANDROID_NDK)
    set(ANDROID_NDK ${CMAKE_ANDROID_NDK})
endif()
if(ANDROID_NDK)
    file(GLOB AC_NDK_LIBOMP "${ANDROID_NDK}/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/aarch64/libomp.so")
endif()
list(GET AC_NDK_LIBOMP 0 AC_NDK_LIBOMP_FIRST)

if(AC_NDK_LIBOMP_FIRST)
    add_custom_command(TARGET ac_cli POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy
            ${AC_NDK_LIBOMP_FIRST}
            ${AC_GUI_ASSETS_DIR}/libomp.so
    )
endif()
