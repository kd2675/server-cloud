package com.example.cloud.service.auth.database.rep.redis.refresh;

import org.example.database.auth.database.rep.redis.refresh.RefreshTokenRedis;
import org.springframework.data.keyvalue.repository.KeyValueRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRedisREP extends KeyValueRepository<RefreshTokenRedis, String> {
}
