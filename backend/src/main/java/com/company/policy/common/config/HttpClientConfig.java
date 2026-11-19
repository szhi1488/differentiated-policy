package com.company.policy.common.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfig {
    @Bean
    HttpClient externalHttpClient(ExternalHttpProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
