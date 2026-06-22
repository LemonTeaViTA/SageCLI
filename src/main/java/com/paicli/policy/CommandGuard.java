package com.paicli.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令快速拒绝：在 execute_command 进入 HITL 审批 / 真正调用 ProcessBuilder 之前的黑名单 fast-fail。
 *
 * 定位：辅助 HITL 而非主防线。黑名单是出名的反模式（永远列不全），但能拦住 LLM 容易踩的明显破坏性命令，
 * 减少 HITL 弹窗骚扰。真正的安全责任在 HITL 审批和用户判断。
 *
 * 设计取舍：
 * - 不做完整 shell 解析，只做正则模式匹配，够覆盖明显破坏性命令即可
 * - 命令替换段 $(...) 和反引号内的内容仍以原文存在，正则会一并扫描，不需要单独展开
 * - curl / git / 网络命令默认放行，只拦真正破坏性的（rm -rf 全盘、sudo、mkfs 等）
 */
public final class CommandGuard {

    private static final List<DenyRule> RULES = List.of(
            new DenyRule("禁止 sudo 提权",
                    Pattern.compile("(?i)\\bsudo\\b")),
            // rm 路径黑名单：匹配开头即可拦截，不强求路径结束边界。
            // /、~、/*、$HOME 是常见的"灾难性删除起点"，包括其作为前缀的所有子路径都拦掉，避免 LLM 误删根目录或用户目录。
            new DenyRule("禁止 rm -rf 删除全盘或用户目录",
                    Pattern.compile("(?i)\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~|\\$home)|" +
                            "\\brm\\s+-[a-z]*f[a-z]*r[a-z]*\\s+(/|~|\\$home)")),
            new DenyRule("禁止 mkfs 格式化磁盘",
                    Pattern.compile("(?i)\\bmkfs(\\.|\\b)")),
            new DenyRule("禁止 dd 写入裸设备",
                    Pattern.compile("(?i)\\bdd\\b[^\\n]*\\bof=/dev/")),
            new DenyRule("识别为 fork bomb",
                    Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:")),
            new DenyRule("禁止 curl / wget 管道直接执行远端脚本",
                    Pattern.compile("(?i)\\b(curl|wget)\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b")),
            new DenyRule("不允许扫描 /、~ 或整个文件系统",
                    Pattern.compile("(?i)\\bfind\\s+(/|~|\\$home)")),
            new DenyRule("禁止 chmod 777 全盘",
                    Pattern.compile("(?i)\\bchmod\\s+-R\\s+777\\s+(/|~)")),
            new DenyRule("禁止 shutdown / reboot / halt",
                    Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff)\\b"))
    );

    // 凭证路径拒绝规则（可整体关闭，见 check()）。只收"几乎不存在正当 shell 读取理由"的凭证路径，
    // best-effort 非安全边界：能挡顺手 cat 私钥，但 base64/变量拼接/改名都能绕过。
    // 故意不拦 /etc/passwd（世界可读、不含密码），避免误伤与制造安全错觉。
    private static final List<DenyRule> SENSITIVE_PATH_RULES = List.of(
            new DenyRule("禁止读取 SSH 私钥等 ~/.ssh 凭证",
                    Pattern.compile("(?i)(\\.ssh/|\\bid_rsa\\b|\\bid_ed25519\\b|\\bid_ecdsa\\b|\\bid_dsa\\b|\\bauthorized_keys\\b)")),
            new DenyRule("禁止读取 AWS / 云凭证目录",
                    Pattern.compile("(?i)(\\.aws/|\\.config/gcloud/|\\.azure/|\\.kube/config)")),
            new DenyRule("禁止读取 /etc/shadow 等系统密码影子文件",
                    Pattern.compile("(?i)(/etc/shadow|/etc/gshadow|/etc/sudoers)")),
            new DenyRule("禁止读取 .netrc / .pgpass / .git-credentials 等凭证文件",
                    Pattern.compile("(?i)(\\.netrc\\b|\\.pgpass\\b|\\.npmrc\\b|\\.pypirc\\b|\\.git-credentials\\b)")),
            new DenyRule("禁止通过 /proc 读取进程环境变量",
                    Pattern.compile("(?i)/proc/[^/\\s]+/environ"))
    );

    private CommandGuard() {
    }

    /**
     * 校验命令是否安全。
     *
     * 两层扫描：
     * 1. 整条命令扫描（保底）：覆盖跨操作符的模式，如 fork bomb {@code :(){ :|:& };:}，
     *    拆开后会被打散，必须在整条上匹配。
     * 2. 逐子命令扫描（对齐 CC：复合命令按 {@code && || ; |} 及命令替换 {@code $(...)}/反引号 拆开，
     *    每段独立过规则）：让带 {@code [^\n]*} 的规则（如 dd of=/dev/）不会跨 {@code ;} 误伤另一段命令，
     *    也能精确报出是哪一段触发。
     *
     * @return null 表示放行；非 null 字符串是拒绝原因，调用方包装成用户/LLM 可见的提示
     */
    public static String check(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        // 第一层：整条命令（保底跨操作符模式）。
        String whole = command.replaceAll("\\s+", " ").trim();
        String wholeReason = matchRules(whole);
        if (wholeReason != null) {
            return wholeReason;
        }
        // 第二层：逐子命令。
        for (String sub : splitSubcommands(command)) {
            String normalized = sub.replaceAll("\\s+", " ").trim();
            if (normalized.isEmpty()) {
                continue;
            }
            String reason = matchRules(normalized);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    /** 对单段已归一化的命令文本跑黑名单规则（含可关闭的凭证路径规则）。 */
    private static String matchRules(String normalized) {
        for (DenyRule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                return rule.reason();
            }
        }
        if (sensitivePathGuardEnabled()) {
            for (DenyRule rule : SENSITIVE_PATH_RULES) {
                if (rule.pattern().matcher(normalized).find()) {
                    return rule.reason() + "（凭证路径保护，可用 -Dpaicli.command.guard.sensitive.paths=false 关闭）";
                }
            }
        }
        return null;
    }

    /**
     * 把复合命令拆成子命令。拆分点：{@code && || ; | &} 和换行；引号内的操作符不拆。
     * 命令替换 {@code $(...)} 与反引号 `...` 内的内容作为独立子命令递归拆出（它们也会被 shell 执行）。
     * 这是 best-effort 拆分而非完整 shell 解析——目的是让黑名单逐段命中，不追求语法完备。
     */
    static List<String> splitSubcommands(String command) {
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;            // 当前所处引号：' 或 " 或 0（未在引号内）
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                cur.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                continue;
            }
            // 命令替换 $(...)：提取括号内内容作为独立段，递归拆分。
            if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
                int depth = 1;
                int j = i + 2;
                StringBuilder inner = new StringBuilder();
                while (j < command.length() && depth > 0) {
                    char cj = command.charAt(j);
                    if (cj == '(') depth++;
                    else if (cj == ')') {
                        depth--;
                        if (depth == 0) break;
                    }
                    inner.append(cj);
                    j++;
                }
                segments.addAll(splitSubcommands(inner.toString()));
                i = j; // 跳过整个 $(...)
                continue;
            }
            // 反引号 `...`：同样作为独立段。
            if (c == '`') {
                int j = i + 1;
                StringBuilder inner = new StringBuilder();
                while (j < command.length() && command.charAt(j) != '`') {
                    inner.append(command.charAt(j));
                    j++;
                }
                segments.addAll(splitSubcommands(inner.toString()));
                i = j;
                continue;
            }
            // 控制操作符：&& || ; | & 和换行。
            if (c == ';' || c == '\n') {
                flush(segments, cur);
                continue;
            }
            if (c == '&' || c == '|') {
                flush(segments, cur);
                if (i + 1 < command.length() && command.charAt(i + 1) == c) {
                    i++; // 吃掉成对的 && 或 ||
                }
                continue;
            }
            cur.append(c);
        }
        flush(segments, cur);
        return segments;
    }

    private static void flush(List<String> segments, StringBuilder cur) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
            segments.add(s);
        }
        cur.setLength(0);
    }

    private static boolean sensitivePathGuardEnabled() {
        String raw = System.getProperty("paicli.command.guard.sensitive.paths");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("PAICLI_COMMAND_GUARD_SENSITIVE_PATHS");
        }
        if (raw == null || raw.isBlank()) {
            return true; // 默认开
        }
        return !(raw.equalsIgnoreCase("false") || raw.equals("0") || raw.equalsIgnoreCase("off"));
    }

    private record DenyRule(String reason, Pattern pattern) {
    }
}
