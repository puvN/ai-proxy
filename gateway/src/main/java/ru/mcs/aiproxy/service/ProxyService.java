package ru.mcs.aiproxy.service;

import java.net.URI;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.model.ProxyRequest;

@Slf4j
@Service
public class ProxyService {
    private final WebClient webClient;
    private final UrlBuilderService urlBuilderService;
    private final AppProperties appProperties;

    public ProxyService(WebClient webClient, UrlBuilderService urlBuilderService, AppProperties appProperties) {
        this.webClient = webClient;
        this.urlBuilderService = urlBuilderService;
        this.appProperties = appProperties;
    }

    public Mono<ServerResponse> forward(ProxyRequest request) {
        var uri = urlBuilderService.build(request);
        log.debug("Forwarding {} {} to {}://{}{}", request.method(), request.path(),
                uri.getScheme(), uri.getHost(), uri.getPath());

        var requestBodySpec = webClient.method(request.method()).uri(uri);
        copyHeaders(request.headers(), requestBodySpec);

        var clientRequest = hasBody(request.method())
                ? requestBodySpec.body(request.body(), DataBuffer.class)
                : requestBodySpec;

        var responseMono = clientRequest
                .retrieve()
                .onStatus(status -> true, errorResponse -> Mono.empty())
                .toEntityFlux(DataBuffer.class);

        return responseMono
                .doOnNext(entity -> log.info("Proxied {} {} -> {}", request.method(), request.path(), entity.getStatusCode()))
                .flatMap(entity -> {
                    var filteredHeaders = filterHeaders(entity.getHeaders());
                    var body = entity.getBody() != null ? entity.getBody() : Flux.<DataBuffer>empty();
                    log.debug("Upstream {} returned {} ({} response headers)",
                            request.path(), entity.getStatusCode(), filteredHeaders.size());

                    return ServerResponse.status(entity.getStatusCode())
                            .headers(h -> h.addAll(filteredHeaders))
                            .body(BodyInserters.fromPublisher(body, DataBuffer.class));
                })
                .onErrorResume(error -> {
                    log.error("Upstream request {} {} failed: {}", request.method(), request.path(), error.getMessage());
                    return Mono.error(error);
                });
    }

    private boolean hasBody(HttpMethod method) {
        return method != HttpMethod.GET && method != HttpMethod.HEAD;
    }

    private HttpHeaders filterHeaders(HttpHeaders source) {
        var filtered = new HttpHeaders();
        source.forEach((name, values) -> {
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) return;
            if (HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)) return;
            if (HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) return;
            filtered.put(name, values);
        });
        return filtered;
    }

    private void copyHeaders(HttpHeaders source, WebClient.RequestBodySpec target) {
        var gatewayKeyHeader = appProperties.getGateway().getKeyHeader();
        source.forEach((name, values) -> {
            if (HttpHeaders.HOST.equalsIgnoreCase(name)) return;
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) return;
            if (HttpHeaders.ACCEPT_ENCODING.equalsIgnoreCase(name)) return;
            if (gatewayKeyHeader.equalsIgnoreCase(name)) return;
            values.forEach(value -> target.header(name, value));
        });
    }
}
