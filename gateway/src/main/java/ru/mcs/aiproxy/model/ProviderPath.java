package ru.mcs.aiproxy.model;

public record ProviderPath(String provider, String path) {

    public static ProviderPath parse(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Empty request path");
        }

        var path = rawPath.trim();

        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        var withoutFirstSlash = path.substring(1);

        var separator = withoutFirstSlash.indexOf('/');

        String provider;
        String providerPath;

        if (separator == -1) {
            provider = withoutFirstSlash;
            providerPath = "/";
        } else {
            provider = withoutFirstSlash.substring(0, separator);
            providerPath = withoutFirstSlash.substring(separator);
        }

        if (provider.isBlank()) {
            throw new IllegalArgumentException("Provider is empty");
        }

        return new ProviderPath(provider, providerPath);
    }
}
