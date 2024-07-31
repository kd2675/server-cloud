package com.example.cloud.service.auth.database.rep.redis.logout;

import org.example.database.auth.database.rep.redis.logout.LogoutAccessTokenRedis;
import org.springframework.data.keyvalue.repository.KeyValueRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LogoutAccessTokenRedisREP extends KeyValueRepository<LogoutAccessTokenRedis, String> {
}