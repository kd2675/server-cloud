package com.example.cloud.service.auth.api.act;

import com.example.cloud.service.auth.api.biz.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseDTO;
import org.example.core.response.base.dto.ResponseDataDTO;
import org.example.core.response.base.vo.Code;
import org.example.database.auth.database.rep.jpa.user.UserDTO;
import org.example.database.auth.database.rep.jpa.user.UserEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(value = {"/api/auth"})
public class AuthController {

    private final AuthService authService;

//    @GetMapping("/ctf/user/{userEmail}")
//    public UserVO getUserInfo(@PathVariable(name = "userEmail") String userEmail) {
//        return userService.getUserInfo(userEmail);
//    }

    @GetMapping("/check")
    public ResponseDTO getCheck() {
        return ResponseDTO.of(true, Code.OK);
    }

    @GetMapping("/userInfo")
    public ResponseDataDTO getUserInfo(UserEntity userEntity) {
//        return ResponseDataDTO.of(authService.getUserInfo(userDetails));
        UserDTO userDTO = UserDTO.of(userEntity);
        return ResponseDataDTO.of(userDTO);
    }

    @GetMapping("/getUserEmail")
    public String getUserEmail(Authentication authentication) {
        return authentication.getName();
    }

}
