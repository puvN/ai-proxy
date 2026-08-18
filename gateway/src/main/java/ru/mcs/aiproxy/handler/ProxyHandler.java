package ru.mcs.aiproxy.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.model.ProviderPath;
import ru.mcs.aiproxy.model.ProxyRequest;
import ru.mcs.aiproxy.service.ProviderService;
import ru.mcs.aiproxy.service.ProxyService;

@Component
@RequiredArgsConstructor
public class ProxyHandler {
    private final ProviderService providerService;
    private final ProxyService proxyService;

    public Mono<ServerResponse> handle(ServerRequest request) {
        var providerPath = ProviderPath.parse(request.path());
        var provider = providerService.getProvider(providerPath.provider());
        var proxyRequest = new ProxyRequest(provider, request.method(), providerPath.path(), request.headers().asHttpHeaders(), request.queryParams(), request.bodyToFlux(DataBuffer.class));
        return proxyService.forward(proxyRequest);
    }
}
