package com.obd.api.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import com.obd.api.devicetoken.DeviceAuthFilter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.core.AuthenticationException;
import java.io.IOException;

@Component
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        String reason = (String) request.getAttribute(JwtAuthFilter.ERROR_ATTR);
        String deviceReason = (String) request.getAttribute(DeviceAuthFilter.ERROR_ATTR);


        boolean carGone = "car_not_accessible".equals(deviceReason);
        HttpStatus status = carGone ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(carGone ? "Forbidden" : "Unauthorized");
        if (carGone) {
            problem.setDetail("This device token's car is no longer available to its owner");
        } else if (deviceReason != null) {
            problem.setDetail("token_revoked".equals(deviceReason)
                    ? "That device token has been revoked or has expired through disuse"
                    : "Invalid device token");
        } else {
            problem.setDetail("token_expired".equals(reason) ? "Access token expire": "Authentication required");
        }
        problem.setProperty("reason",
                deviceReason != null ? deviceReason : (reason == null ? "missing_token": reason));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
