package com.example.cloud.service.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginParamDTO {

    @NotBlank
    @Email
    private String userEmail;

    @NotBlank
    @Size(min=8)
    private String userPassword;
}
