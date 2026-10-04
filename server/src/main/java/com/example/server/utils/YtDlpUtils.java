package com.example.server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class YtDlpUtils {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUtils.class);

    /** yt-dlp 的进度行，形如 {@code [download]  58.1% of   11.17MiB at  164KiB/s ETA 00:29}。 */
    private static final Pattern DOWNLOAD_PROGRESS = Pattern.compile("^\\[download]\\s+[\\d.]+% of ");

    private final String ytDlpPath;
    private final String ffmpegDir;
    private final String cookiesFile;
    private final String cookiesFromBrowser;

    public YtDlpUtils(@Value("${tool.ytdlp.path}") String ytDlpPath,
                      @Value("${tool.ffmpeg.dir}") String ffmpegDir,
                      @Value("${tool.ytdlp.cookies:}") String cookiesFile,
                      @Value("${tool.ytdlp.cookies-from-browser:}") String cookiesFromBrowser) {
        this.ytDlpPath = ytDlpPath;
        this.ffmpegDir = ffmpegDir;
        this.cookiesFile = cookiesFile;
        this.cookiesFromBrowser = cookiesFromBrowser;
    }

    public File downloadVideo(String url) throws Exception {
        PublicNetworkAddresses.requirePublicHttpUrl(url);
        Path outputPath = Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".mp4");
        Path logPath = Files.createTempFile("yt-dlp-", ".log");
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--no-playlist");
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("10");
        // 分片重试单独设：默认继承 --retries，但分段下载（HLS/DASH）的失败模式是
        // 单分片反复断连，与整文件重试不是一回事，显式给足次数更稳。
        command.add("--fragment-retries");
        command.add("10");
        command.add("--max-filesize");
        command.add("2048M");
        // 优先选广泛兼容的 H.264/AVC + AAC 组合：仅把 AV1 文件换个 MP4 容器，
        // 在部分 macOS 与硬件组合的 Safari 上仍然放不出来。
        //
        // 分辨率与帧率分层降级：分析链路只用关键帧和音轨，4K 60帧对结果没有任何增益，
        // 却让体积涨近十倍，进而被站点限速掐断连接（实测 B 站 4K 源 386MB，传 25MB 即断）。
        // 因此依次尝试「≤1080p 且 ≤30fps → ≤1080p → 原行为」，保证源没有低规格档时仍可下载。
        command.add("-f");
        command.add("bv*[vcodec^=avc1][ext=mp4][height<=1080][fps<=30]+ba[acodec^=mp4a][ext=m4a]"
                + "/bv*[vcodec^=avc1][ext=mp4][height<=1080]+ba[acodec^=mp4a][ext=m4a]"
                + "/b[vcodec^=avc1][ext=mp4][height<=1080]"
                + "/bv*[vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]"
                + "/b[vcodec^=avc1][ext=mp4]");
        command.add("--merge-output-format");
        command.add("mp4");
        command.add("--recode-video");
        command.add("mp4");
        if (ffmpegDir != null && !ffmpegDir.isBlank()) {
            command.add("--ffmpeg-location");
            command.add(ffmpegDir);
        }
        appendCookieArgs(command);
        command.add("-o");
        command.add(outputPath.toString());
        command.add(url);

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("视频链接下载超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
                String logs = Files.readString(logPath);
                throw new IllegalStateException("yt-dlp 下载失败: " + recentLogs(logs));
            }
            log.info("url_video_downloaded host={} bytes={}", URI.create(url).getHost(), Files.size(outputPath));
            return outputPath.toFile();
        } catch (Exception e) {
            Files.deleteIfExists(outputPath);
            throw e;
        } finally {
            Files.deleteIfExists(logPath);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /**
     * 部分站点（实测 B 站）对缺少登录态的请求直接返回 HTTP 412 风控页，
     * 仅靠 User-Agent 或手工构造的 buvid3 都绕不过，必须带真实浏览器会话 cookie。
     * 两种来源互斥，显式 cookie 文件优先：服务端用它更可控，也不依赖运行时能否访问浏览器 cookie 存储
     * （macOS 上读取浏览器 cookie 需要钥匙串授权，无人值守时会失败）。
     */
    private void appendCookieArgs(List<String> command) {
        if (cookiesFile != null && !cookiesFile.isBlank()) {
            command.add("--cookies");
            command.add(cookiesFile);
            return;
        }
        if (cookiesFromBrowser != null && !cookiesFromBrowser.isBlank()) {
            command.add("--cookies-from-browser");
            command.add(cookiesFromBrowser);
        }
    }

    /**
     * yt-dlp 的进度行用 \r 原地刷新，一个百兆视频能刷出上百行；直接截尾取错误信息会把真正的报错
     * 整个挤出窗口（实测踩过：只剩满屏 [download] 百分比，看不到失败原因）。这里先剔除进度行再截断。
     * 剔除后若什么都不剩，则退回原始日志，避免把全部上下文也一起丢掉。
     */
    private String recentLogs(String logs) {
        String cleaned = logs.lines()
                .filter(line -> !DOWNLOAD_PROGRESS.matcher(line.trim()).find())
                .collect(Collectors.joining("\n"));
        return tail(cleaned.isBlank() ? logs : cleaned, 2_000);
    }

    private String tail(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(value.length() - maxLength);
    }
}
