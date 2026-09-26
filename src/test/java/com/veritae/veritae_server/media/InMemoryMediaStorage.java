package com.veritae.veritae_server.media;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** 테스트 전용 MediaStorage - 실제 R2 대신 메모리에 저장한다. */
public class InMemoryMediaStorage implements MediaStorage {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Map<String, String> contentTypes = new LinkedHashMap<>();
    private boolean enabled = true;
    private boolean failOnPut = false;

    public void disable() {
        this.enabled = false;
    }

    public void failOnPut() {
        this.failOnPut = true;
    }

    public Map<String, byte[]> objects() {
        return objects;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        if (failOnPut) {
            throw new IllegalStateException("저장소 업로드 실패(테스트)");
        }
        objects.put(key, bytes);
        contentTypes.put(key, contentType);
    }

    @Override
    public MediaObject get(String key, String range) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new MediaObjectNotFoundException(key);
        }
        return new MediaObject(new ByteArrayInputStream(bytes), contentTypes.get(key), bytes.length, null);
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
        contentTypes.remove(key);
    }

    @Override
    public void deleteByPrefix(String prefix) {
        objects.keySet().removeIf(key -> key.startsWith(prefix));
        contentTypes.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
