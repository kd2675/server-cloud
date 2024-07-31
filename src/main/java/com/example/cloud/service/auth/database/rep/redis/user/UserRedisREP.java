package com.example.cloud.service.auth.database.rep.redis.user;

import org.example.database.auth.database.rep.redis.user.UserRedisEntity;
import org.springframework.data.keyvalue.repository.KeyValueRepository;


public interface UserRedisREP extends KeyValueRepository<UserRedisEntity, String> {
}
