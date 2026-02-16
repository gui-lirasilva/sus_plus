package br.com.sus_scheduling.service;

import br.com.sus_scheduling.repository.CacheRepositoryImpl;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CacheService {

    private final CacheRepositoryImpl cacheRepository;
    private static final Integer DEFAULT_TTL = 1;
    private static final TimeUnit DEFAULT_TIME_UNIT = TimeUnit.HOURS;

    public CacheService(CacheRepositoryImpl cacheRepository) {
        this.cacheRepository = cacheRepository;
    }

    public void save(String key, Object value) {
        cacheRepository.save(key, value, DEFAULT_TTL, DEFAULT_TIME_UNIT);
    }

    public void save(String key, Object value, Integer ttl, TimeUnit timeUnit) {
        cacheRepository.save(key, value, ttl, timeUnit);
    }

    public Object findByKey(String key) {
        return cacheRepository.findByKey(key);
    }

    public void remove(String key) {
        cacheRepository.delete(key);
    }
}
