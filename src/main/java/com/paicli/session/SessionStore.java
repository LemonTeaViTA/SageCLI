package com.paicli.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 会话存储 —— 完整对话的无损持久化与恢复，按项目分组。
 *
 * <p>目录结构：{@code ~/.paicli/sessions/<projectHash>/<sessionId>.jsonl}，每行一条
 * {@link SessionMessageRecord}。按项目分组保证 /sessions、/resume 只列出当前项目的会话。
 *
 * <p>与 tui 包的 {@code ConversationSnapshot} 区别：那是给老 TUI 做"对话回放"，字段不全；
 * 这里为"喂回 LLM 继续推理"设计，存全 toolCalls / toolCallId。
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TITLE_MAX_CHARS = 40;

    private final Path baseDir;

    public SessionStore() {
        this(Path.of(System.getProperty("user.home"), ".paicli", "sessions"));
    }

    SessionStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** 会话元数据，用于 /sessions 列表展示。 */
    public record SessionMeta(
            String sessionId,
            String title,
            long createdAt,
            long lastActiveAt,
            int messageCount
    ) {}

    /** 生成新会话 ID。 */
    public static String generateSessionId(long timestamp) {
        return "session_" + timestamp;
    }

    /** 追加一条消息到指定会话（项目隔离）。 */
    public void appendMessage(String projectKey, String sessionId, SessionMessageRecord record) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(record, "record");
        Path file = sessionFile(projectKey, sessionId);
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(MAPPER.writeValueAsString(record));
                writer.newLine();
            }
        } catch (IOException e) {
            // 写盘失败不能影响主对话流程，记日志即可
            log.warn("append session message failed: session={}, err={}", sessionId, e.getMessage());
        }
    }

    /** 读取一个会话的全部消息；单行解析失败时跳过该行而非整体失败。 */
    public List<SessionMessageRecord> loadSession(String projectKey, String sessionId) {
        Path file = sessionFile(projectKey, sessionId);
        List<SessionMessageRecord> messages = new ArrayList<>();
        if (!Files.exists(file)) {
            return messages;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    messages.add(MAPPER.readValue(line, SessionMessageRecord.class));
                } catch (Exception e) {
                    log.warn("skip malformed session line in {}: {}", sessionId, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("load session failed: session={}, err={}", sessionId, e.getMessage());
        }
        return messages;
    }

    /** 列出当前项目的全部会话，按最后活跃时间倒序。 */
    public List<SessionMeta> listSessions(String projectKey) {
        Path dir = projectDir(projectKey);
        List<SessionMeta> metas = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return metas;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path path : stream) {
                SessionMeta meta = readMeta(path);
                if (meta != null) {
                    metas.add(meta);
                }
            }
        } catch (IOException e) {
            log.warn("list sessions failed: {}", e.getMessage());
        }
        metas.sort(Comparator.comparingLong(SessionMeta::lastActiveAt).reversed());
        return metas;
    }

    /** 当前项目最近一次会话的 ID；没有则返回 null。 */
    public String latestSessionId(String projectKey) {
        List<SessionMeta> metas = listSessions(projectKey);
        return metas.isEmpty() ? null : metas.get(0).sessionId();
    }

    /** 读单个会话文件得真实元数据：标题取首条 user 消息，消息数为真实行数。 */
    private SessionMeta readMeta(Path path) {
        String sessionId = path.getFileName().toString().replace(".jsonl", "");
        String title = null;
        long createdAt = 0;
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    SessionMessageRecord rec = MAPPER.readValue(line, SessionMessageRecord.class);
                    count++;
                    if (createdAt == 0 && rec.timestamp() > 0) {
                        createdAt = rec.timestamp();
                    }
                    if (title == null && "user".equals(rec.role()) && rec.content() != null && !rec.content().isBlank()) {
                        title = summarize(rec.content());
                    }
                } catch (Exception ignored) {
                    // 跳过损坏行
                }
            }
            long lastActiveAt = Files.getLastModifiedTime(path).toMillis();
            if (createdAt == 0) {
                createdAt = lastActiveAt;
            }
            return new SessionMeta(
                    sessionId,
                    title != null ? title : "(无标题会话)",
                    createdAt,
                    lastActiveAt,
                    count
            );
        } catch (IOException e) {
            log.warn("read session meta failed: {}", e.getMessage());
            return null;
        }
    }

    private static String summarize(String content) {
        String oneLine = content.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= TITLE_MAX_CHARS) {
            return oneLine;
        }
        return oneLine.substring(0, TITLE_MAX_CHARS) + "…";
    }

    private Path sessionFile(String projectKey, String sessionId) {
        return projectDir(projectKey).resolve(sessionId + ".jsonl");
    }

    private Path projectDir(String projectKey) {
        return baseDir.resolve(projectHash(projectKey));
    }

    /** 把项目路径稳定地映射成短目录名，避免路径里的特殊字符。 */
    private static String projectHash(String projectKey) {
        String key = projectKey == null || projectKey.isBlank()
                ? System.getProperty("user.dir")
                : projectKey;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
