package com.veritae.veritae_server.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 영상 업로드를 처리가 끝날 때까지 디스크 임시 파일로 들고 있는다(2026-10-28, 배포 준비).
 * 예전엔 byte[]로 메모리에 들고 대기열(최대 50건, 건당 최대 100MB)에서 기다려서, 메모리 2GB 서버에서는
 * 영상 몇 개만 몰려도 메모리가 바닥날 수 있었다. 파일은 처리(VideoAnalysisAsyncWorker)가 끝나면 지운다.
 */
@Slf4j
@Component
public class VideoUploadFiles {

    private final Path directory;

    public VideoUploadFiles(@Value("${analysis.video-upload-dir}") Path directory) {
        this.directory = directory;
    }

    public Path save(MultipartFile file) {
        try {
            Files.createDirectories(directory);
            Path path = Files.createTempFile(directory, "video-", ".upload");
            file.transferTo(path);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 저장할 수 없습니다.", e);
        }
    }

    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("영상 임시 파일 삭제 실패 path={}", path, e);
        }
    }

    /**
     * 서버가 처리 도중 꺼지면 그 영상의 임시 파일이 남는다. 그 작업들은 StaleAnalysisJobCleaner가 FAILED로
     * 정리하므로 파일도 더는 쓰이지 않는다 - 시작할 때 전부 지운다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void deleteLeftovers() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "video-*.upload")) {
            for (Path path : files) {
                deleteQuietly(path);
            }
        } catch (IOException e) {
            log.warn("남은 영상 임시 파일 정리 실패 directory={}", directory, e);
        }
    }
}
