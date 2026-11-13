package dev.codexresets.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;

final class StateStore implements AutoCloseable {
    private final Path path;
    private final ObjectMapper json;
    private FileChannel lockChannel;
    private FileLock lock;

    StateStore(Path path, ObjectMapper json) {
        this.path = path.toAbsolutePath();
        this.json = json;
    }

    void lock() throws IOException {
        Files.createDirectories(path.getParent());
        lockChannel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            throw new IOException("이미 같은 상태 파일을 사용하는 프로세스가 실행 중입니다.");
        }
        if (lock == null) throw new IOException("이미 같은 상태 파일을 사용하는 프로세스가 실행 중입니다.");
    }

    Set<String> load() throws IOException {
        if (!Files.exists(path)) return null;
        try {
            var root = json.readTree(Files.readAllBytes(path));
            if (root == null || root.path("version").asInt(-1) != 1 || !root.path("seen").isArray()) {
                throw new IOException("상태 파일 형식이 잘못되었습니다. 기존 파일을 확인하세요.");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (var key : root.path("seen")) {
                if (!key.isTextual() || key.asText().isBlank()) throw new IOException("잘못된 상태 키입니다.");
                seen.add(key.asText());
            }
            return seen;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("상태 파일이 손상되었습니다. 자동 초기화하지 않습니다.");
        }
    }

    void save(Set<String> seen) throws IOException {
        var root = json.createObjectNode().put("version", 1);
        var keys = root.putArray("seen");
        seen.stream().sorted().forEach(keys::add);
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "state-", ".tmp");
        try {
            byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            // Fail closed on filesystems without atomic moves instead of risking partial state.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override public void close() throws IOException {
        if (lock != null) lock.release();
        if (lockChannel != null) lockChannel.close();
    }
}
