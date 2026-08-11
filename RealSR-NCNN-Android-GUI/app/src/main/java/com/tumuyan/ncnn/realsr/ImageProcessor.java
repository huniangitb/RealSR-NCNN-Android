package com.tumuyan.ncnn.realsr;

import android.os.Debug;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 图像处理器类，负责管理后台任务和进程执行。
 * 使用 ExecutorService 替代原始线程，提供更好的并发控制。
 */
public class ImageProcessor {
    private static final String TAG = "ImageProcessor";
    private final ExecutorService executorService;
    private Process currentProcess;
    private Future<?> currentTask;
    private volatile boolean taskCancelled;

    public interface ProcessCallback {
        void onProgress(String line);
        void onCompleted(String result, boolean success);
        void onError(String error);
    }

    public ImageProcessor() {
        // 使用单线程执行器，确保任务按顺序执行（如果需要并发可以改为 newFixedThreadPool）
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void executeCommand(String command, String workingDir, ProcessCallback callback) {
        cancelCurrentTask();
        taskCancelled = false;

        currentTask = executorService.submit(() -> {
            // 记录处理前 app 内存(PSS, kB), 完成后输出增量 = 运行期间真实内存占用峰值
            // (替代 JNI getSessionInfo MEMORY: OpenCL/Vulkan 后端返回 0, 不可用)
            long startPss = 0;
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            try {
                Debug.getMemoryInfo(mi);
                startPss = mi.getTotalPss();
            } catch (Exception ignored) {}
            runProcess(command, workingDir, callback);
            try {
                Debug.getMemoryInfo(mi);
                long deltaMB = (mi.getTotalPss() - startPss) / 1024L;
                callback.onProgress("内存峰值: 约 " + deltaMB + " MB");
            } catch (Exception ignored) {}
        });
    }

    /**
     * 解析 Anime4k 命令并通过 JNI 调用 libanime4k.so 处理。
     * 命令格式：./Anime4k -i <input> -o <output> -m <model> -p <processor> -f <factor>
     * JNI 在 app 进程内执行，工作目录不是运行目录，因此相对路径
     * （input.png/output.png）需解析为运行目录的绝对路径。
     * JNI 返回格式：成功 "OK|<backend>|<gpuName>"，失败 "ERR|<error message>"。
     * @return 成功返回设备信息描述，失败返回 "ERR|..." 前缀的错误信息
     */
    private String runAnime4kJni(String command, String workingDir) {
        try {
            String input = null, output = null, model = null, processor = "auto";
            double factor = 2.0;
            String[] tokens = command.trim().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String t = tokens[i];
                String next = (i + 1 < tokens.length) ? tokens[i + 1] : null;
                if (next == null) continue;
                switch (t) {
                    case "-i": case "--input": input = next; i++; break;
                    case "-o": case "--output": output = next; i++; break;
                    case "-m": case "--model": model = next; i++; break;
                    case "-p": case "--processor": processor = next; i++; break;
                    case "-f": case "--factor":
                        try { factor = Double.parseDouble(next); } catch (NumberFormatException ignored) {}
                        i++; break;
                    default: break;
                }
            }
            if (input == null || output == null || model == null) {
                return "ERR|Anime4k: missing -i/-o/-m argument";
            }
            // 相对路径 → 运行目录绝对路径（JNI 进程 cwd 非运行目录）
            if (workingDir != null) {
                if (!input.startsWith("/")) input = workingDir + "/" + input;
                if (!output.startsWith("/")) output = workingDir + "/" + output;
            }
            Log.d(TAG, "Anime4k JNI: input=" + input + " output=" + output +
                    " model=" + model + " processor=" + processor + " factor=" + factor);
            return Anime4kProcessor.process(input, output, model, processor, -1, factor);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libanime4k.so not loaded", e);
            return "ERR|libanime4k.so not loaded: " + e.getMessage();
        } catch (Exception e) {
            Log.e(TAG, "Anime4k JNI exception", e);
            return "ERR|" + e.getMessage();
        }
    }

    /**
     * 解析 mnnsr 命令并通过 JNI 调用 libmnnsr.so 处理。
     * 命令格式：./mnnsr-ncnn -i <input> -o <output> -m <model> -s <scale> [-b <backend>] [-g <gpu>] [-c <color>]
     * JNI 在 app 进程内执行，工作目录不是运行目录，因此相对路径
     * （input.png/output.png）需解析为运行目录的绝对路径。
     * JNI 返回格式：成功 "OK|<backend>|<scale>"，失败 "ERR|<error message>"。
     * @return 成功返回设备信息描述，失败返回 "ERR|..." 前缀的错误信息
     */
    private String runMnnsrJni(String command, String workingDir) {
        try {
            String input = null, output = null, model = null;
            // gpu=-2 表示"未指定"(保留 -b 后端); 显式 -g -1 才强制 CPU
            int scale = 4, backend = 7, gpu = -2, colorType = 1, decensorMode = -1, tileSize = 0;
            String[] tokens = command.trim().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String t = tokens[i];
                String next = (i + 1 < tokens.length) ? tokens[i + 1] : null;
                if (next == null) continue;
                switch (t) {
                    case "-i": input = next; i++; break;
                    case "-o": output = next; i++; break;
                    case "-m": model = next; i++; break;
                    case "-s":
                        try { scale = Integer.parseInt(next); } catch (NumberFormatException ignored) {}
                        i++; break;
                    case "-b":
                        try { backend = Integer.parseInt(next); } catch (NumberFormatException ignored) {}
                        i++; break;
                    case "-g":
                        try { gpu = Integer.parseInt(next); } catch (NumberFormatException ignored) {}
                        i++; break;
                    case "-c":
                        try { colorType = Integer.parseInt(next); } catch (NumberFormatException ignored) {}
                        i++; break;
                    case "-d":
                        try { decensorMode = Integer.parseInt(next); } catch (NumberFormatException ignored) {}
                        i++; break;
                    case "-t":
                        try { tileSize = Integer.parseInt(next); } catch (NumberFormatException ignored) {}
                        i++; break;
                    default: break;
                }
            }
            if (input == null || output == null || model == null) {
                return "ERR|mnnsr: missing -i/-o/-m argument";
            }
            // 相对路径 → 运行目录绝对路径（JNI 进程 cwd 非运行目录）
            if (workingDir != null) {
                if (!input.startsWith("/")) input = workingDir + "/" + input;
                if (!output.startsWith("/")) output = workingDir + "/" + output;
                if (!model.startsWith("/")) model = workingDir + "/" + model;
            }
            Log.d(TAG, "mnnsr JNI: input=" + input + " output=" + output +
                    " model=" + model + " scale=" + scale + " backend=" + backend +
                    " gpu=" + gpu + " tileSize=" + tileSize);
            return MnnsrProcessor.process(input, output, model, scale, backend, gpu, colorType, decensorMode, tileSize);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libmnnsr.so not loaded", e);
            return "ERR|libmnnsr.so not loaded: " + e.getMessage();
        } catch (Exception e) {
            Log.e(TAG, "mnnsr JNI exception", e);
            return "ERR|" + e.getMessage();
        }
    }

    private void runProcess(String command, String workingDir, ProcessCallback callback) {
        StringBuilder resultBuilder = new StringBuilder();
        boolean success = false;

        // Anime4KCPP v3.2.0 改为 JNI 调用（app 进程内加载，继承 classloader
        // namespace，<uses-native-library> 声明生效，可绕过 linker namespace 隔离）。
        // 不再通过独立可执行文件（exec 子进程）运行 ./Anime4k。
        // JNI 返回格式："OK|<backend>|<gpuName>" 或 "ERR|<error message>"
        if (command != null && command.trim().startsWith("./Anime4k")) {
            // 新任务开始前清除取消标志（executeCommand 的 cancelCurrentTask 会无条件置位）
            Anime4kProcessor.reset();
            // 处理开始前最先显示推理后端 / GPU 型号（JNI 内 process 前回调）
            Anime4kProcessor.setOnInfoListener(info -> callback.onProgress(info));
            // 注册进度回调：JNI 内 2x 放大阶段进度 → GUI 进度显示
            Anime4kProcessor.setOnProgressListener((current, total) ->
                    callback.onProgress("PROGRESS:" + current + "/" + total));
            String result = runAnime4kJni(command, workingDir);
            Anime4kProcessor.setOnProgressListener(null);
            Anime4kProcessor.setOnInfoListener(null);
            if (result != null && result.startsWith("OK|")) {
                success = true;
                String[] parts = result.split("\\|", 3);
                String backend = parts.length > 1 ? parts[1] : "Unknown";
                String gpu = parts.length > 2 ? parts[2] : "";
                StringBuilder info = new StringBuilder("Anime4KCPP: 推理后端 " + backend);
                if (backend.equals("OpenCL") && !gpu.isEmpty()) {
                    info.append("，GPU ").append(gpu);
                }
                Log.d(TAG, info.toString());
                callback.onProgress(info.toString());
                callback.onCompleted(resultBuilder.toString(), true);
            } else {
                String error = (result != null && result.startsWith("ERR|"))
                        ? result.substring(4) : "Anime4k JNI failed";
                Log.e(TAG, "Anime4k JNI error: " + error);
                callback.onError(error);
            }
            return;
        }

        // mnnsr (MNN 超分) 改为 JNI 调用（app 进程内加载 libmnnsr.so，
        // 继承 classloader namespace，可绕过 linker namespace 隔离）。
        // JNI 返回格式："OK|<backend>|<scale>" 或 "ERR|<error message>"
        if (command != null && command.trim().startsWith("./mnnsr-ncnn")) {
            try {
                // 新任务开始前清除取消标志
                MnnsrProcessor.reset();
                // 处理开始前最先显示推理后端（JNI 内 load 后回调）
                MnnsrProcessor.setOnInfoListener(info -> callback.onProgress(info));
                // 注册进度回调：JNI 内每个 tile 处理进度 + 当前切块像素尺寸 → GUI 进度显示
                MnnsrProcessor.setOnProgressListener((current, total, tw, th) ->
                        callback.onProgress("PROGRESS:" + current + "/" + total + "|" + tw + "x" + th));
                String result = runMnnsrJni(command, workingDir);
                MnnsrProcessor.setOnProgressListener(null);
                MnnsrProcessor.setOnInfoListener(null);
                if (result != null && result.startsWith("OK|")) {
                    success = true;
                    String[] parts = result.split("\\|", 3);
                    String backend = parts.length > 1 ? parts[1] : "Unknown";
                    String scale = parts.length > 2 ? parts[2] : "";
                    String info = "mnnsr: 推理后端 " + backend + "，倍率 x" + scale;
                    Log.d(TAG, info);
                    callback.onProgress(info);
                    callback.onCompleted(resultBuilder.toString(), true);
                } else {
                    String error = (result != null && result.startsWith("ERR|"))
                            ? result.substring(4) : "mnnsr JNI failed";
                    Log.e(TAG, "mnnsr JNI error: " + error);
                    callback.onError(error);
                }
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "libmnnsr.so not loaded", e);
                callback.onError("libmnnsr.so not loaded: " + e.getMessage());
            }
            return;
        }

        try {
            Log.d(TAG, "Executing command: " + command);
            ProcessBuilder processBuilder = new ProcessBuilder("sh");
            processBuilder.directory(new File(workingDir));
            processBuilder.redirectErrorStream(true);
            
            synchronized (this) {
                currentProcess = processBuilder.start();
            }

            OutputStream os = currentProcess.getOutputStream();
            // workingDir 来自应用缓存目录（getCacheDir），参数来源可信
            String setupCmd = "cd " + workingDir + "; chmod +x *ncnn 2>/dev/null; export LD_LIBRARY_PATH=" + workingDir + ";\n";
            os.write(setupCmd.getBytes());
            os.write((command + "\n").getBytes());
            os.write("exit\n".getBytes());
            os.flush();
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Task interrupted");
                }
                // 过滤无用日志
                if (line.contains("unused DT entry")) continue;
                if (line.startsWith("CPU Group:")) continue;
                if (line.startsWith("(last_midr")) continue;
                if (line.startsWith("Error tunning info")) continue;
                
                Log.d(TAG, line);
                callback.onProgress(line);
                resultBuilder.append(line).append("\n");
            }
            
            int exitCode = currentProcess.waitFor();
            success = (exitCode == 0);
            Log.d(TAG, "Process finished with exit code: " + exitCode);

        } catch (InterruptedException e) {
            Log.w(TAG, "Process interrupted");
            callback.onError("Process interrupted");
        } catch (InterruptedIOException e) {
            Log.w(TAG, "Process stream closed by cancel, treating as normal cancellation");
            callback.onError("Task cancelled");
        } catch (Exception e) {
            Log.e(TAG, "Error executing process", e);
            callback.onError(e.getMessage());
        } finally {
            synchronized (this) {
                if (currentProcess != null) {
                    currentProcess.destroy();
                    currentProcess = null;
                }
            }
            if (!taskCancelled) {
                callback.onCompleted(resultBuilder.toString(), success);
            }
        }
    }

    public void cancelCurrentTask() {
        taskCancelled = true;
        // JNI 推理无法用线程中断强行停止：置位 native 取消标志，
        // tile 循环检查后提前退出（对 Anime4k/mnnsr JNI 均生效）
        try { MnnsrProcessor.cancel(); } catch (Throwable ignored) {}
        try { Anime4kProcessor.cancel(); } catch (Throwable ignored) {}
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
        synchronized (this) {
            if (currentProcess != null) {
                currentProcess.destroy();
                currentProcess = null;
            }
        }
    }

    public void shutdown() {
        executorService.shutdownNow();
    }
}
