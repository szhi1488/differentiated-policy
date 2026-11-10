package com.company.policy.common.config;

import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地附件目录；兼容项目根目录和 backend 目录启动。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "policy.storage")
public class AttachmentProperties {
    private String root = "";

    public Path rootPath() {
        if (root != null && !root.isBlank()) {
            return Path.of(root).toAbsolutePath().normalize();
        }
        Path working = Path.of("").toAbsolutePath().normalize();
        if (working.getFileName().toString().equals("backend")) {
            working = working.getParent();
        }
        return working.resolve("data");
    }
}
