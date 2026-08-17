package ru.mcs.aiproxy.service;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import ru.mcs.aiproxy.config.ProviderProperties;
import ru.mcs.aiproxy.model.ProxyRequest;

import java.net.URI;

@Service
public class UrlBuilderService {
    public URI build(ProxyRequest request) {
        ProviderProperties provider = request.provider();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(provider.getBaseUrl()).path(request.path());
        request.query().forEach((name, values) -> values.forEach(value -> builder.queryParam(name, value)));
        return builder.build().encode().toUri();
    }
}