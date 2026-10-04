package com.vicinity24.core.linkedstore.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MailContactRequest {

    @NotBlank(message = "full_name_required")
    @Size(max = 120, message = "full_name_too_long")
    @JsonAlias({"name", "fullName"})
    private String fullName;

    @NotBlank(message = "email_required")
    @Email(message = "invalid_email")
    @Size(max = 254, message = "email_too_long")
    private String email;

    @Size(max = 160, message = "company_too_long")
    @JsonAlias("companyName")
    private String company;

    @Size(max = 80, message = "phone_too_long")
    private String phone;

    @Size(max = 64, message = "solution_too_long")
    @JsonAlias({"plan", "planCode", "tier"})
    private String solution;

    @Size(max = 64, message = "plan_code_too_long")
    @JsonAlias("tierCode")
    private String planCode;

    @NotBlank(message = "message_required")
    @Size(min = 10, max = 5000, message = "message_length")
    private String message;
}
