package com.example.cloud.common.config.jwt.biz;

import com.example.cloud.common.config.jwt.cache.CacheKey;
import com.example.cloud.common.config.jwt.vo.CustomUserDetailVO;
import com.example.cloud.service.auth.database.rep.jpa.user.UserREP;
import lombok.RequiredArgsConstructor;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.example.database.auth.database.rep.jpa.user.UserEntity;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
public class CustomUserDetailService implements UserDetailsService {

    private final UserREP userREP;

    @Override
    @Cacheable(value = CacheKey.USER, key = "#p0", unless = "#result == null")
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserEntity userEntity = userREP.findByEmailWithRole(email)
                .orElseThrow(() -> new GeneralException(Code.NO_SEARCH_USER, "없는 회원입니다."));
        return CustomUserDetailVO.of(userEntity);
    }
}