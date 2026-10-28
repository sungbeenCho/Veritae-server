package com.veritae.veritae_server.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VideoUploadFilesTest {

    @TempDir
    Path uploadDir;

    @Test
    void save_shouldWriteUploadIntoUploadDirectory() throws Exception {
        VideoUploadFiles files = new VideoUploadFiles(uploadDir.resolve("nested"));

        Path saved = files.save(new MockMultipartFile("file", "test.mp4", "video/mp4", "fake-bytes".getBytes()));

        assertThat(saved.getParent()).isEqualTo(uploadDir.resolve("nested"));
        assertThat(Files.readAllBytes(saved)).isEqualTo("fake-bytes".getBytes());
    }

    @Test
    void deleteLeftovers_shouldRemoveOnlyVideoUploadsLeftByPreviousRun() throws Exception {
        // 처리 도중 서버가 꺼져 남은 파일은 시작할 때 지운다. 다른 파일은 건드리지 않는다.
        Path leftover = Files.write(uploadDir.resolve("video-123.upload"), new byte[]{1});
        Path unrelated = Files.write(uploadDir.resolve("keep.txt"), new byte[]{1});

        new VideoUploadFiles(uploadDir).deleteLeftovers();

        assertThat(leftover).doesNotExist();
        assertThat(unrelated).exists();
    }

    @Test
    void deleteLeftovers_whenDirectoryDoesNotExistYet_shouldDoNothing() {
        new VideoUploadFiles(uploadDir.resolve("missing")).deleteLeftovers();
    }
}
