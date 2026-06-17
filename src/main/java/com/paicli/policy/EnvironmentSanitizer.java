package com.paicli.policy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 子进程环境变量脱敏：execute_command 起 bash 子进程时，默认从继承的环境里删掉疑似凭证的变量，
 * 避免 LLM 用 {@code env} / {@code printenv} / {@code echo $XXX} 一句话偷走宿主的 API key /
 * token / 云凭证。
 *
 * <p>定位：这是<b>确定性边界</b>——变量删了子进程就读不到，任何命令花样（base64 / 变量拼接 / 改名）
 * 都变不出来。这点和 {@link CommandGuard}（命令黑名单，best-effort、可绕过）、{@link PathGuard}
 * （文件工具路径围栏，不管 shell）互补，是三层里唯一对"偷 key"成立的硬保证。
 *
 * <p>策略：deny-list（按名字模式删凭证类），保留命令正常运行必需的非敏感变量（PATH / HOME /
 * LANG / SHELL / TERM 等）。父 JVM 自身环境不受影响，只清理交给子进程的副本。
 *
 * <p>开关：默认开；{@code -Dpaicli.command.env.isolation=false} 或
 * {@code PAICLI_COMMAND_ENV_ISOLATION=false} 整体关闭（关闭后子进程继承全部环境变量）。
 */
public final class EnvironmentSanitizer {

    // 凭证类变量名模式（大小写不敏感）。覆盖 SageCLI 自己的 *_API_KEY，以及常见云/CI/工具凭证。
    private static final List<Pattern> SENSITIVE_KEY_PATTERNS = List.of(
            Pattern.compile("(?i).*_API_KEY$"),
            Pattern.compile("(?i).*_APIKEY$"),
            Pattern.compile("(?i).*API_TOKEN$"),
            Pattern.compile("(?i).*_TOKEN$"),
            Pattern.compile("(?i).*_SECRET$"),
            Pattern.compile("(?i).*_SECRET_KEY$"),
            Pattern.compile("(?i).*_PASSWORD$"),
            Pattern.compile("(?i).*_PASSWD$"),
            Pattern.compile("(?i).*_ACCESS_KEY.*"),
            Pattern.compile("(?i).*CREDENTIAL.*"),
            Pattern.compile("(?i)^AWS_.*"),
            Pattern.compile("(?i)^OPENAI_.*"),
            Pattern.compile("(?i)^ANTHROPIC_.*"),
            Pattern.compile("(?i)^AZURE_.*KEY.*"),
            Pattern.compile("(?i)^GH_TOKEN$"),
            Pattern.compile("(?i)^GITHUB_TOKEN$"),
            Pattern.compile("(?i)^NPM_TOKEN$"),
            Pattern.compile("(?i)^HF_TOKEN$"),
            Pattern.compile("(?i)^SLACK_TOKEN$")
    );

    private EnvironmentSanitizer() {
    }

    /**
     * 就地清理传入的环境 map（通常是 {@code ProcessBuilder.environment()}）。
     * 关闭隔离时直接返回，不做任何删除。
     */
    public static void sanitize(Map<String, String> environment) {
        if (environment == null || !isEnabled()) {
            return;
        }
        environment.keySet().removeIf(EnvironmentSanitizer::isSensitiveKey);
    }

    /** 单个变量名是否命中凭证模式。供测试与外部判定复用。 */
    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (Pattern pattern : SENSITIVE_KEY_PATTERNS) {
            if (pattern.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEnabled() {
        String raw = System.getProperty("paicli.command.env.isolation");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("PAICLI_COMMAND_ENV_ISOLATION");
        }
        if (raw == null || raw.isBlank()) {
            return true; // 默认开
        }
        return !(raw.equalsIgnoreCase("false") || raw.equals("0") || raw.equalsIgnoreCase("off"));
    }
}
