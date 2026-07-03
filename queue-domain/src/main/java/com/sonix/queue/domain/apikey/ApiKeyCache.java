package com.sonix.queue.domain.apikey;

import java.util.Optional;

public interface ApiKeyCache {
    Optional<ApiKey> get(String keyHash);
    void put(ApiKey apiKey);
    void putNegative(String keyHash);
    void invalidate(String keyHash);

}
