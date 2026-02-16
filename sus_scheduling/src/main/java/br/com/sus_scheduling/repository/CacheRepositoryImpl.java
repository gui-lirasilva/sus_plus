package br.com.sus_scheduling.repository;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

@Repository
public class CacheRepositoryImpl implements CacheRepository{

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(CacheRepositoryImpl.class);
    private final RedisTemplate<String, Object> redisTemplate;

    public CacheRepositoryImpl(@Qualifier("customRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void simpleSave(String key, Object value) {
        save(key, value, 5, TimeUnit.MINUTES);
    }

    @Override
    public void save(String key, Object value, Integer ttl, TimeUnit timeUnit) {
        try {
            log.info("Salvando dados no cache: {}", value);
            this.redisTemplate.opsForValue().set(key, value, ttl, timeUnit);
        } catch (Exception e) {
            log.error("Erro ao salvar dados no cache: {}", e.getMessage());
        }
    }

    @Override
    public Object findByKey(String key) {
        log.info("Buscando dados em cache para chave: {}", key);
        Object jsonValue = this.redisTemplate.opsForValue().get(key);
        log.info("valor recuperado do cache: {}", jsonValue);
        try {
            //TODO: Converter para tipo de objeto de saída
            return jsonValue;
        } catch (Exception e) {
            log.error("Erro ao converter dados no cache: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void delete(String key) {
        try {
            log.info("Deletando dados do cache para chave: {}", key);
            this.redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Erro ao deletar dados do cache: {}", e.getMessage());
        }
    }
}
