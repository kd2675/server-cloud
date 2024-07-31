package com.example.cloud.service.auth.api.act;

import com.example.cloud.service.auth.api.biz.JoinService;
import com.example.cloud.service.auth.api.dto.JoinParamDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseDTO;
import org.example.core.response.base.dto.ResponseDataDTO;
import org.example.core.response.base.vo.Code;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/ctf/join")
public class JoinController {
    private final JoinService joinService;

    @PostMapping("/user")
    public ResponseDTO join(@Valid @RequestBody JoinParamDTO joinParamDTO, BindingResult bindingResult) {
        if (joinService.valid(joinParamDTO, bindingResult)) {
            return ResponseDTO.of(false, Code.VALIDATION_ERROR, bindingResult.getFieldError().getDefaultMessage());
        }

        joinService.joinUser(joinParamDTO);

        return ResponseDTO.of(true, Code.OK);
    }

    @PostMapping("/admin")
    public ResponseDTO joinAdmin(@Valid @RequestBody JoinParamDTO joinParamDTO, BindingResult bindingResult) {
        if (joinService.valid(joinParamDTO, bindingResult)) {
            return ResponseDTO.of(false, Code.VALIDATION_ERROR, bindingResult.getFieldError().getDefaultMessage());
        }

        joinService.joinAdmin(joinParamDTO);

        return ResponseDTO.of(true, Code.OK);
    }

    @PostMapping("/social")
    public ResponseDTO joinSocial(@Valid @RequestBody JoinParamDTO joinParamDTO, BindingResult bindingResult) {
        if (joinService.valid(joinParamDTO, bindingResult)) {
            return ResponseDTO.of(false, Code.VALIDATION_ERROR, bindingResult.getFieldError().getDefaultMessage());
        }

        joinService.joinSocial(joinParamDTO);

        return ResponseDTO.of(true, Code.OK);
    }

    @GetMapping("/selEmailCheck")
    public ResponseDataDTO joinIdCheck(@RequestParam(name = "userEmail") String email) {
        return ResponseDataDTO.of(joinService.duplEmail(email));
    }

    @GetMapping("/selNickCheck")
    public ResponseDataDTO joinNickCheck(@RequestParam(name = "userNick") String nick) {
        return ResponseDataDTO.of(joinService.duplNick(nick));
    }
}
