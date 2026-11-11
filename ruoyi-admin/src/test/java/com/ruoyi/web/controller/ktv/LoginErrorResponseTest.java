package com.ruoyi.web.controller.ktv;

import com.ruoyi.common.exception.user.CaptchaException;
import com.ruoyi.common.exception.user.CaptchaExpireException;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginErrorResponseTest {
    @RestController
    static class LoginFailureController {
        @GetMapping("/login-failure/{kind}")
        public void fail(@PathVariable String kind) {
            if ("wrong".equals(kind)) throw new CaptchaException();
            if ("expired".equals(kind)) throw new CaptchaExpireException();
            throw new IllegalStateException("internal configuration must remain private");
        }
    }
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LoginFailureController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test void incorrectCaptchaHasActionableMessage() throws Exception {
        mvc.perform(get("/login-failure/wrong")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("验证码错误，请重新输入"));
    }
    @Test void expiredCaptchaRequestsRefresh() throws Exception {
        mvc.perform(get("/login-failure/expired")).andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("验证码已过期，请刷新后重试"));
    }
    @Test void unexpectedErrorsStillHideDetails() throws Exception {
        mvc.perform(get("/login-failure/unexpected")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.msg").value("服务暂不可用，请稍后重试"));
    }
}
