package com.example.cloud.common.config.jwt.filter;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.Response;
import org.example.core.response.base.dto.ResponseDTO;
import org.example.core.response.base.vo.Code;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class JwtExceptionFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (SignatureException e) {
            errorResponse(response, Code.TOKEN_SIGNATURE, e);
        } catch (MalformedJwtException e) {
            errorResponse(response, Code.TOKEN_MALFORMED, e);
        } catch (ExpiredJwtException e) {
            errorResponse(response, Code.TOKEN_EXPIRED, e);
        } catch (UnsupportedJwtException e) {
            errorResponse(response, Code.TOKEN_UNSUPPORTED, e);
        } catch (IllegalArgumentException e) {
            errorResponse(response, Code.TOKEN_ILLEGAL_ARGUMENT, e);
        }
    }

    private void errorResponse(HttpServletResponse response, Code code, Exception e) throws IOException {
        log.error("{} - {}", code.getMessage(), e);
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(MapperFeature.PROPAGATE_TRANSIENT_MARKER, true);

        response.setCharacterEncoding("utf-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(code.getHttpStatus().value());
        response.getWriter().write(mapper.writeValueAsString(ResponseDTO.of(false, code)));
    }
}
