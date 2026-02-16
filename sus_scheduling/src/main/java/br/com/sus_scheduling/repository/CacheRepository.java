package br.com.sus_scheduling.repository;

import java.util.concurrent.TimeUnit;

public interface CacheRepository {

    void save(String key, Object value, Integer ttl, TimeUnit timeUnit);
    Object findByKey(String key);
    void delete(String key);
}
