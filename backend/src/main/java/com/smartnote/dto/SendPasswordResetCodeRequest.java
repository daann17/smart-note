package com.smartnote.dto;

import lombok.Data;

@Data
public class SendPasswordResetCodeRequest {
    private String email;
}
