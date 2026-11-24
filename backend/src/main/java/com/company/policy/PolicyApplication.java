package com.company.policy;

import com.company.policy.common.config.AttachmentProperties;
import com.company.policy.common.config.ExternalHttpProperties;
import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.config.JackyunProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    FeishuProperties.class,
    ExternalHttpProperties.class,
    AttachmentProperties.class,
    JackyunProperties.class
})
public class PolicyApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "send-review-card".equals(args[0])) {
            PolicyCardCommand.run(args);
            return;
        }
        SpringApplication.run(PolicyApplication.class, args);
    }
}
