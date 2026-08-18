package ru.mcs.aiproxy.config;

import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Slf4j
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        var responseTimeout = Duration.ofMinutes(10);
        var client = HttpClient.create().responseTimeout(responseTimeout);
        var webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(client)).build();
        log.debug("WebClient created with response timeout {} ms", responseTimeout.toMillis());
        return webClient;
    }
}
