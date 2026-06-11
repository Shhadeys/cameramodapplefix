package net.mehvahdjukaar.vista.client.web.ffmpeg;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.mehvahdjukaar.vista.VistaMod;
import net.mehvahdjukaar.vista.client.web.files.ArchiveUtils;
import net.mehvahdjukaar.vista.client.web.files.FileDownloadUtils;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class FFmpegManager {

    private static final Path SOURCES_CONFIG_PATH = Paths.get("vista_ffmpeg_sources.json");
    private static final String SOURCES_RESOURCE_PATH = "/vista_ffmpeg_sources.json";
    private static final Path PROGRAM_FOLDER = Paths.get("vista_ffmpeg_bin");
    private static volatile int downloadProgress = -1;

    private static final OsType OS_TYPE = OsType.detect();
    private static final Path FFMPEG_PATH = PROGRAM_FOLDER.resolve(OS_TYPE.ffmpegName);
    private static final Path FFPROBE_PATH = PROGRAM_FOLDER.resolve(OS_TYPE.ffprobeName);

    public static CompletableFuture<FFmpeg> getOrDownload(@Nullable String customUrl) {
        return CompletableFuture.supplyAsync(() -> initialize(customUrl));
    }

    public static int getDownloadProgress() {
        return downloadProgress;
    }

    private static FFmpeg initialize(@Nullable String customUrl) {
        try {
            Files.createDirectories(PROGRAM_FOLDER);
            if (!hasRequiredFiles()) {
                downloadProgress = -1;
                String downloadUrl = customUrl != null ? customUrl : getDownloadUrlFromSources();
                Path archive = PROGRAM_FOLDER.resolve(ArchiveUtils.extractFileNameFromUrl(downloadUrl));

                if (Files.exists(archive) && !ArchiveUtils.isProbablyValid(archive)) {
                    Files.deleteIfExists(archive);
                }

                if (!Files.exists(archive)) {
                    //await
                    FileDownloadUtils.download(downloadUrl, archive, null, percent -> downloadProgress = percent);
                }

                extractAndInstall(archive);
                Files.deleteIfExists(archive);
                downloadProgress = -1;
            } else {
                downloadProgress = -1;
                VistaMod.LOGGER.info("FFmpeg binaries found at {}", FFMPEG_PATH);
            }
        } catch (Exception e) {
            downloadProgress = -1;
            throw new RuntimeException("FFmpeg setup failed. Aborting.", e);
        }
        return new FFmpeg(FFMPEG_PATH, FFPROBE_PATH);
    }

    public static boolean hasRequiredFiles() {
        return Files.exists(FFMPEG_PATH) && Files.exists(FFPROBE_PATH);
    }

    private static String getDownloadUrlFromSources() throws IOException {
        ensureSourcesConfigExists();

        String json = Files.readString(SOURCES_CONFIG_PATH, StandardCharsets.UTF_8);
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (IllegalStateException | JsonParseException e) {
            throw new IOException("Invalid JSON in " + SOURCES_CONFIG_PATH, e);
        }

        String key = OS_TYPE.jsonKey;
        if (!root.has(key)) {
            throw new IOException("Missing key '" + key + "' in " + SOURCES_CONFIG_PATH);
        }
        String url = root.get(key).getAsString().trim();
        if (url.isEmpty()) {
            throw new IOException("Empty URL for key '" + key + "' in " + SOURCES_CONFIG_PATH);
        }
        if (!url.startsWith("http")) {
            url = "https://" + url;
        }
        return url;
    }

    private static void ensureSourcesConfigExists() throws IOException {
        if (Files.exists(SOURCES_CONFIG_PATH)) return;

        try (InputStream in = FFmpegManager.class.getResourceAsStream(SOURCES_RESOURCE_PATH)) {
            if (in == null) {
                throw new IOException("Resource not found: " + SOURCES_RESOURCE_PATH);
            }
            Files.copy(in, SOURCES_CONFIG_PATH);
        }
    }

    private static void moveRequiredBinariesFromProgramFolder() throws IOException {
        Path ffmpeg = null;
        Path ffprobe = null;

        try (Stream<Path> stream = Files.walk(PROGRAM_FOLDER)) {
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String name = p.getFileName().toString();
                if (name.equals(FFMPEG_PATH.getFileName().toString())) {
                    ffmpeg = p;
                } else if (name.equals(FFPROBE_PATH.getFileName().toString())) {
                    ffprobe = p;
                }
                if (ffmpeg != null && ffprobe != null) {
                    break;
                }
            }
        }

        if (ffmpeg == null || ffprobe == null) {
            throw new IOException("Archive does not contain required binaries: "
                    + FFMPEG_PATH.getFileName() + ", " + FFPROBE_PATH.getFileName());
        }

        Files.move(ffmpeg, FFMPEG_PATH, StandardCopyOption.REPLACE_EXISTING);
        Files.move(ffprobe, FFPROBE_PATH, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extractAndInstall(Path archive) throws IOException, InterruptedException {
        if (!ArchiveUtils.isSupported(archive)) {
            throw new IOException("Unsupported archive format: " + archive.getFileName());
        }
        ArchiveUtils.extract(archive, PROGRAM_FOLDER);
        moveRequiredBinariesFromProgramFolder();
        if (OS_TYPE.requiresExecutableBit) {
            markExecutables();
        }
    }

    private static void markExecutables() throws IOException {
        if (!FFMPEG_PATH.toFile().setExecutable(true) || !FFPROBE_PATH.toFile().setExecutable(true)) {
            throw new IOException("Could not mark FFmpeg binaries as executable");
        }
    }

   private enum OsType {
    LINUX("linux", "ffmpeg", "ffprobe", true),
    MACOS("macos", "ffmpeg", "ffprobe", true),
    MACOS_ARM64("macos-arm64", "ffmpeg", "ffprobe", true),
    WINDOWS("windows", "ffmpeg.exe", "ffprobe.exe", false);

    private final String jsonKey;
    private final String ffmpegName;
    private final String ffprobeName;
    private final boolean requiresExecutableBit;

    OsType(String sourceKey, String ffmpegName, String ffprobeName, boolean requiresExecutableBit) {
        this.jsonKey = sourceKey;
        this.ffmpegName = ffmpegName;
        this.ffprobeName = ffprobeName;
        this.requiresExecutableBit = requiresExecutableBit;
    }

    private static OsType detect() {
        if (Minecraft.ON_OSX) {
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return MACOS_ARM64;
            }
            return MACOS;
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.contains("win") ? OsType.WINDOWS : OsType.LINUX;
    }
}


}
