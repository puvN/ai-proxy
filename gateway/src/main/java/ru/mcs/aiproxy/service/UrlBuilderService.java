package ru.mcs.aiproxy.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import ru.mcs.aiproxy.model.ProxyRequest;

import java.net.URI;

@Slf4j
@Service
public class UrlBuilderService {
    public URI build(ProxyRequest request) {
        var provider = request.provider();
        var builder = UriComponentsBuilder.fromHttpUrl(provider.getBaseUrl()).path(request.path());
        request.query().forEach((name, values) -> values.forEach(value -> builder.queryParam(name, value)));
        var uri = builder.build().encode().toUri();
        log.debug("Built upstream {}://{}{}", uri.getScheme(), uri.getHost(), uri.getPath());
        return uri;
    }
}
