package com.company.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.policy.common.config.FeishuProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class LocalConfigLoadingTest {
    @TempDir Path workspace;

    @Test
    void loadsPrivateConfigurationFromBothRepositoryAndBackendWorkingDirectories() throws Exception {
        Path backend = Files.createDirectories(workspace.resolve("backend"));
        Files.writeString(backend.resolve(".env.local.properties"), """
                FEISHU_APP_ID=local-config-test-app
                FEISHU_APP_SECRET=fake-config-test-secret
                FEISHU_ALLOWED_ORIGINS=http://localhost:5173
                """);
        verifyStartup(workspace);
        verifyStartup(backend);
    }

    private void verifyStartup(Path workingDirectory) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp", classpath, ConfigProbe.class.getName());
        builder.directory(workingDirectory.toFile()).redirectErrorStream(true);
        for (String key : new String[]{"FEISHU_APP_ID", "FEISHU_APP_SECRET", "FEISHU_ALLOWED_ORIGINS",
                "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION",
                "SPRING_APPLICATION_JSON"}) {
            builder.environment().remove(key);
        }
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("configuration probe terminates").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("configuration loads from %s: %s", workingDirectory, output).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class ConfigProbe {
        public static void main(String[] args) {
            try (var context = new SpringApplicationBuilder(PolicyApplication.class)
                    .web(WebApplicationType.NONE).run("--spring.config.location=classpath:/application.yml",
                            "--spring.main.banner-mode=off", "--logging.level.root=OFF")) {
                var properties = context.getBean(FeishuProperties.class);
                if (!"local-config-test-app".equals(properties.getAppId())
                        || !"fake-config-test-secret".equals(properties.getAppSecret())
                        || !properties.isConfigured()) {
                    throw new IllegalStateException("Private configuration was not loaded");
                }
            }
        }
    }
}
