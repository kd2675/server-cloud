package com.example.cloud.common.config.resolver;

import com.example.cloud.common.config.jwt.provider.JwtTokenProvider;
import com.example.cloud.service.auth.database.rep.jpa.user.UserREP;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.example.database.auth.database.rep.jpa.user.UserEntity;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Slf4j
@RequiredArgsConstructor
@Component
public class UserEntityResolver implements HandlerMethodArgumentResolver {
    private final UserDetailsService userDetailsService;
    private final UserREP userREP;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(UserEntity.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        UserEntity userEntity = new UserEntity();
        String userEmail = jwtTokenProvider.getUserEmail();
        if (userEmail != null) {
            userEntity = userREP.findByEmailWithRole(userEmail)
                    .orElseThrow(() -> new GeneralException(Code.NO_SEARCH_USER, "회원이 없습니다."));
//        if (!userEntity.getUserEmail().equals(jwtTokenProvider.getCurrentUserEmail())) {
//            if (!userEntity.getEmail().equals(jwtTokenProvider.getUserEmail())) {
//                throw new GeneralException(Code.VALIDATION_ERROR, "회원 정보가 일치하지 않습니다.");
//            }
//        }
        }
        return userEntity;
    }
}
