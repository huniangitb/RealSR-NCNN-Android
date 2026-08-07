package com.tumuyan.ncnn.realsr;

public class ProgressLogHelper {

    private static final java.util.regex.Pattern PROGRESS_PATTERN =
            java.util.regex.Pattern.compile("PROGRESS[:：]?\\s*(\\d+)\\s*/\\s*(\\d+)(?:\\|(\\d+)x(\\d+))?");

    private final StringBuilder logBuilder = new StringBuilder();
    private String lastProgressLine = "";
    private long startTime;

    public void reset() {
        logBuilder.setLength(0);
        lastProgressLine = "";
        startTime = System.currentTimeMillis();
    }

    public static boolean isProgressLine(String line) {
        if (line == null) return false;
        // 兼容两种进度格式:
        // 1) CLI 原生百分比: "35%" / " 35.5%  [1.2s/3.0s ETA]"
        // 2) JNI 统一机器格式: "PROGRESS:3/10" (前端按关键字匹配更新标题栏百分比)
        if (line.matches("\\s*\\d([0-9.]*)%(\\s.+)?")) return true;
        return line.startsWith("PROGRESS:");
    }

    /**
     * 从单行进度文本提取用于显示的进度字符串（静态工具，供通知栏等复用）。
     * PROGRESS:3/10 → "30%"；其他格式原样返回；空行返回空串。
     */
    public static String getProgressTextFor(String line) {
        if (line == null || line.isEmpty()) return "";
        if (line.startsWith("PROGRESS:")) {
            java.util.regex.Matcher m = PROGRESS_PATTERN.matcher(line);
            if (m.find()) {
                int cur = Integer.parseInt(m.group(1));
                int total = Integer.parseInt(m.group(2));
                if (total > 0 && cur >= 0 && cur <= total) {
                    return (cur * 100 / total) + "%";
                }
            }
            return line;
        }
        return line.trim().split("\\s")[0];
    }

    public void appendLine(String line) {
        if (line == null || line.isEmpty()) return;

        if (isProgressLine(line)) {
            lastProgressLine = line;
        } else {
            logBuilder.append(line).append("\n");
            lastProgressLine = "";
        }
    }

    public String getDisplayText() {
        if (lastProgressLine.isEmpty()) {
            return logBuilder.toString();
        } else {
            return logBuilder.toString() + lastProgressLine;
        }
    }

    public String getFullLog() {
        return logBuilder.toString();
    }

    public String getProgressText() {
        if (lastProgressLine.isEmpty()) return "";
        // JNI 机器格式 "PROGRESS:3/10" → 百分比 "30%"
        if (lastProgressLine.startsWith("PROGRESS:")) {
            java.util.regex.Matcher m = PROGRESS_PATTERN.matcher(lastProgressLine);
            if (m.find()) {
                int cur = Integer.parseInt(m.group(1));
                int total = Integer.parseInt(m.group(2));
                if (total > 0 && cur >= 0 && cur <= total) {
                    return (cur * 100 / total) + "%";
                }
            }
            return lastProgressLine;
        }
        return lastProgressLine.trim().split("\\s")[0];
    }

    public boolean hasProgress() {
        return !lastProgressLine.isEmpty();
    }

    public float getElapsedTimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000f;
    }

    public String getCompletionSummary(boolean success, String modelName, boolean isNcnnCommand) {
        StringBuilder summary = new StringBuilder();

        if (!success) {
            summary.append("\nfail, use ").append(getElapsedTimeSeconds()).append(" second");
        } else {
            summary.append("\nfinish, use ").append(getElapsedTimeSeconds()).append(" second");
        }

        if (isNcnnCommand && modelName != null && !modelName.isEmpty()) {
            summary.append(", ").append(modelName);
        }

        summary.append("\n");
        return summary.toString();
    }
}
