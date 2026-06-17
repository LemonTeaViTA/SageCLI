package com.paicli.policy;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentSanitizerTest {

    @Test
    void removesCredentialLikeVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("GLM_API_KEY", "secret-glm");
        env.put("DEEPSEEK_API_KEY", "secret-ds");
        env.put("AWS_ACCESS_KEY_ID", "akia");
        env.put("AWS_SECRET_ACCESS_KEY", "wjalr");
        env.put("OPENAI_API_KEY", "sk-xxx");
        env.put("ANTHROPIC_API_KEY", "sk-ant");
        env.put("GITHUB_TOKEN", "ghp_xxx");
        env.put("DB_PASSWORD", "p@ss");
        env.put("SOME_SECRET", "shh");

        EnvironmentSanitizer.sanitize(env);

        assertFalse(env.containsKey("GLM_API_KEY"));
        assertFalse(env.containsKey("DEEPSEEK_API_KEY"));
        assertFalse(env.containsKey("AWS_ACCESS_KEY_ID"));
        assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(env.containsKey("OPENAI_API_KEY"));
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"));
        assertFalse(env.containsKey("GITHUB_TOKEN"));
        assertFalse(env.containsKey("DB_PASSWORD"));
        assertFalse(env.containsKey("SOME_SECRET"));
    }

    @Test
    void keepsNonSensitiveVariablesNeededToRunCommands() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", "/home/ubuntu");
        env.put("LANG", "en_US.UTF-8");
        env.put("SHELL", "/bin/bash");
        env.put("TERM", "xterm");
        env.put("PWD", "/home/ubuntu/project");

        EnvironmentSanitizer.sanitize(env);

        assertEquals("/usr/bin:/bin", env.get("PATH"));
        assertEquals("/home/ubuntu", env.get("HOME"));
        assertEquals("en_US.UTF-8", env.get("LANG"));
        assertEquals("/bin/bash", env.get("SHELL"));
        assertEquals("xterm", env.get("TERM"));
        assertEquals("/home/ubuntu/project", env.get("PWD"));
    }

    @Test
    void isSensitiveKeyMatchesCommonCredentialNames() {
        assertTrue(EnvironmentSanitizer.isSensitiveKey("GLM_API_KEY"));
        assertTrue(EnvironmentSanitizer.isSensitiveKey("stripe_secret_key"));
        assertTrue(EnvironmentSanitizer.isSensitiveKey("MY_TOKEN"));
        assertTrue(EnvironmentSanitizer.isSensitiveKey("aws_session_token"));
        assertFalse(EnvironmentSanitizer.isSensitiveKey("PATH"));
        assertFalse(EnvironmentSanitizer.isSensitiveKey("HOME"));
        assertFalse(EnvironmentSanitizer.isSensitiveKey(""));
        assertFalse(EnvironmentSanitizer.isSensitiveKey(null));
    }

    @Test
    void handlesNullEnvironmentGracefully() {
        assertDoesNotThrow(() -> EnvironmentSanitizer.sanitize(null));
    }
}
