package com.company.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.policy.common.config.AttachmentProperties;
import com.company.policy.common.config.ExternalHttpProperties;
import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.config.HttpClientConfig;
import com.company.policy.common.config.JackyunProperties;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

class LocalConfigLoadingTest {
    private static final Map<String, String> CONFIG_NAMES =
            Map.ofEntries(
                    Map.entry("FEISHU_APP_ID", "feishu.app-id"),
                    Map.entry("FEISHU_APP_SECRET", "feishu.app-secret"),
                    Map.entry("FEISHU_TENANT_KEY", "feishu.tenant-key"),
                    Map.entry("FEISHU_ALLOWED_ORIGINS", "feishu.allowed-origins"),
                    Map.entry("DB_URL", "spring.datasource.url"),
                    Map.entry("SERVER_ADDRESS", "server.address"),
                    Map.entry("SERVER_PORT", "server.port"),
                    Map.entry("POLICY_STORAGE_ROOT", "policy.storage.root"),
                    Map.entry(
                            "HTTP_CONNECT_TIMEOUT_SECONDS",
                            "external.http.connect-timeout-seconds"),
                    Map.entry("FEISHU_REQUEST_TIMEOUT_SECONDS", "feishu.request-timeout-seconds"),
                    Map.entry("JACKYUN_REQUEST_TIMEOUT_SECONDS", "jackyun.request-timeout-seconds"),
                    Map.entry(
                            "JACKYUN_IMPORT_SCHEDULING_ENABLED",
                            "jackyun.import-scheduling-enabled"),
                    Map.entry(
                            "JACKYUN_CLEAN_SCHEDULING_ENABLED", "jackyun.clean-scheduling-enabled"),
                    Map.entry("JACKYUN_IMPORT_POLL_INTERVAL_MS", "jackyun.import-poll-interval-ms"),
                    Map.entry("JACKYUN_CLEAN_POLL_INTERVAL_MS", "jackyun.clean-poll-interval-ms"));

    @TempDir Path workspace;

    @Test
    void loadsPrivateConfigurationFromBothRepositoryAndBackendWorkingDirectories()
            throws Exception {
        Path backend = Files.createDirectories(workspace.resolve("backend"));
        var settings = sampleSettings();
        writePrivateConfiguration(backend, settings);
        verifyStartup(workspace, settings, Map.of());
        verifyStartup(backend, settings, Map.of());
    }

    @Test
    void ignoresLegacyTenantConfiguration() throws Exception {
        Path backend = Files.createDirectories(workspace.resolve("backend"));
        var settings = sampleSettings();
        settings.remove("FEISHU_TENANT_KEY");
        settings.put("JACKYUN_TENANT_KEY", "legacy-test-tenant");
        writePrivateConfiguration(backend, settings);
        verifyStartup(workspace, settings, Map.of());
        verifyStartup(
                backend, settings, Map.of("JACKYUN_TENANT_KEY", "legacy-environment-test-tenant"));
    }

    @Test
    void environmentSettingsOverrideLocalFile() throws Exception {
        Path backend = Files.createDirectories(workspace.resolve("backend"));
        var settings = sampleSettings();
        writePrivateConfiguration(backend, settings);
        var environment =
                Map.ofEntries(
                        Map.entry("FEISHU_TENANT_KEY", "environment-test-tenant"),
                        Map.entry("FEISHU_ALLOWED_ORIGINS", "https://environment.example.test"),
                        Map.entry(
                                "DB_URL", "jdbc:mysql://environment.example.test:3306/config_test"),
                        Map.entry("SERVER_ADDRESS", "127.0.0.3"),
                        Map.entry("SERVER_PORT", "18081"),
                        Map.entry(
                                "POLICY_STORAGE_ROOT",
                                workspace
                                        .resolve("environment storage")
                                        .toString()
                                        .replace('\\', '/')),
                        Map.entry("HTTP_CONNECT_TIMEOUT_SECONDS", "9"),
                        Map.entry("FEISHU_REQUEST_TIMEOUT_SECONDS", "19"),
                        Map.entry("JACKYUN_REQUEST_TIMEOUT_SECONDS", "29"),
                        Map.entry("JACKYUN_IMPORT_SCHEDULING_ENABLED", "true"),
                        Map.entry("JACKYUN_CLEAN_SCHEDULING_ENABLED", "false"),
                        Map.entry("JACKYUN_IMPORT_POLL_INTERVAL_MS", "5000"),
                        Map.entry("JACKYUN_CLEAN_POLL_INTERVAL_MS", "6000"));
        verifyStartup(backend, settings, environment);
    }

    @Test
    void missingEnvironmentIdentityDoesNotUseDevelopmentDefaults() throws Exception {
        Path backend = Files.createDirectories(workspace.resolve("backend"));
        var settings = sampleSettings();
        settings.remove("FEISHU_APP_ID");
        settings.remove("FEISHU_ALLOWED_ORIGINS");
        settings.remove("DB_URL");
        writePrivateConfiguration(backend, settings);
        verifyStartup(backend, settings, Map.of());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "external.http.connect-timeout-seconds=0",
                "external.http.connect-timeout-seconds=61",
                "feishu.request-timeout-seconds=0",
                "feishu.request-timeout-seconds=61",
                "jackyun.request-timeout-seconds=0",
                "jackyun.request-timeout-seconds=61",
                "jackyun.import-poll-interval-ms=999",
                "jackyun.clean-poll-interval-ms=60001"
            })
    void rejectsInvalidRuntimeLimits(String setting) {
        new ApplicationContextRunner()
                .withUserConfiguration(ConfigProbe.class)
                .withPropertyValues(setting)
                .run(context -> assertThat(context).hasFailed());
    }

    private Map<String, String> sampleSettings() {
        var settings = new LinkedHashMap<String, String>();
        settings.put("FEISHU_APP_ID", "local-config-test-app");
        settings.put("FEISHU_APP_SECRET", "fake-config-test-secret");
        settings.put("FEISHU_TENANT_KEY", "local-config-test-tenant");
        settings.put("FEISHU_ALLOWED_ORIGINS", "https://app.example.test");
        settings.put("DB_URL", "jdbc:mysql://database.example.test:3306/config_test");
        settings.put("SERVER_ADDRESS", "127.0.0.2");
        settings.put("SERVER_PORT", "18080");
        settings.put(
                "POLICY_STORAGE_ROOT",
                workspace.resolve("storage with spaces").toString().replace('\\', '/'));
        settings.put("HTTP_CONNECT_TIMEOUT_SECONDS", "7");
        settings.put("FEISHU_REQUEST_TIMEOUT_SECONDS", "13");
        settings.put("JACKYUN_REQUEST_TIMEOUT_SECONDS", "23");
        settings.put("JACKYUN_IMPORT_SCHEDULING_ENABLED", "false");
        settings.put("JACKYUN_CLEAN_SCHEDULING_ENABLED", "true");
        settings.put("JACKYUN_IMPORT_POLL_INTERVAL_MS", "3000");
        settings.put("JACKYUN_CLEAN_POLL_INTERVAL_MS", "4000");
        return settings;
    }

    private void writePrivateConfiguration(Path backend, Map<String, String> settings)
            throws Exception {
        var contents = new StringBuilder();
        settings.forEach(
                (key, value) -> contents.append(key).append('=').append(value).append('\n'));
        Files.writeString(backend.resolve(".env.local.properties"), contents);
    }

    private void verifyStartup(
            Path workingDirectory, Map<String, String> settings, Map<String, String> environment)
            throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ArrayList<String>();
        command.addAll(
                java.util.List.of(javaExecutable, "-cp", classpath, ConfigProbe.class.getName()));
        CONFIG_NAMES.forEach(
                (key, property) ->
                        command.add(
                                property
                                        + "="
                                        + environment.getOrDefault(
                                                key, settings.getOrDefault(key, ""))));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile()).redirectErrorStream(true);
        builder.environment()
                .keySet()
                .removeIf(
                        key ->
                                CONFIG_NAMES.containsKey(key)
                                        || key.equals("JACKYUN_TENANT_KEY")
                                        || key.startsWith("SPRING_CONFIG_")
                                        || key.equals("SPRING_APPLICATION_JSON"));
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("配置探针应及时结束").isTrue();
            String output =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("从 %s 加载配置: %s", workingDirectory, output).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    // 只加载属性和未发起请求的 HTTP 客户端，不初始化数据库、任务或 Web 服务。
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        FeishuProperties.class,
        JackyunProperties.class,
        AttachmentProperties.class,
        ExternalHttpProperties.class
    })
    @Import(HttpClientConfig.class)
    public static class ConfigProbe {
        public static void main(String[] args) {
            try (var context =
                    new SpringApplicationBuilder(ConfigProbe.class)
                            .web(WebApplicationType.NONE)
                            .run(
                                    "--spring.config.location=classpath:/application.yml",
                                    "--spring.main.banner-mode=off",
                                    "--logging.level.root=OFF")) {
                var env = context.getEnvironment();
                for (String expectation : args) {
                    String[] pair = expectation.split("=", 2);
                    assertThat(env.getProperty(pair[0], "")).as(pair[0]).isEqualTo(pair[1]);
                }
                var feishu = context.getBean(FeishuProperties.class);
                assertThat(feishu.getTenantKey()).isEqualTo(env.getProperty("feishu.tenant-key"));
                assertThat(feishu.hasCompanyTenant()).isEqualTo(!feishu.getTenantKey().isBlank());
                assertThat(feishu.getRequestTimeoutSeconds())
                        .isEqualTo(
                                env.getProperty("feishu.request-timeout-seconds", Integer.class));
                assertThat(feishu.getAllowedOrigins())
                        .hasSize(env.getProperty("feishu.allowed-origins", "").isBlank() ? 0 : 1);
                var jackyun = context.getBean(JackyunProperties.class);
                assertThat(jackyun.getRequestTimeoutSeconds())
                        .isEqualTo(
                                env.getProperty("jackyun.request-timeout-seconds", Integer.class));
                assertThat(jackyun.getImportPollIntervalMs())
                        .isEqualTo(
                                env.getProperty("jackyun.import-poll-interval-ms", Integer.class));
                assertThat(jackyun.getCleanPollIntervalMs())
                        .isEqualTo(
                                env.getProperty("jackyun.clean-poll-interval-ms", Integer.class));
                var storage = context.getBean(AttachmentProperties.class);
                assertThat(storage.rootPath())
                        .isEqualTo(
                                Path.of(env.getProperty("policy.storage.root"))
                                        .toAbsolutePath()
                                        .normalize());
                var client = context.getBean(HttpClient.class);
                assertThat(client.connectTimeout())
                        .contains(
                                Duration.ofSeconds(
                                        env.getProperty(
                                                "external.http.connect-timeout-seconds",
                                                Integer.class)));
                assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            }
        }
    }
}
