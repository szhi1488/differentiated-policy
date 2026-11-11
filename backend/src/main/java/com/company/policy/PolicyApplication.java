package com.company.policy;

import com.company.policy.common.config.AttachmentProperties;
import com.company.policy.common.config.FeishuProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({FeishuProperties.class, AttachmentProperties.class})
public class PolicyApplication {
    public static void main(String[] args) {
        SpringApplication.run(PolicyApplication.class, args);
    }
}
