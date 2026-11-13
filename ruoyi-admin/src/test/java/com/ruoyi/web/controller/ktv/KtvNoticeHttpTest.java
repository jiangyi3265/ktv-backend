package com.ruoyi.web.controller.ktv;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.ruoyi.web.controller.ktv.KtvService.object;

class KtvNoticeHttpTest {
    @Test void notificationRoutesReturnAuthenticationAndPermissionStatusInsteadOfGeneric500() throws Exception {
        KtvAccess access=mock(KtvAccess.class);
        KtvNoticeService notices=mock(KtvNoticeService.class);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new KtvNoticeController(notices,access,mock(KtvService.class)))
            .setControllerAdvice(new KtvErrors()).build();
        when(access.who(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录"));
        mvc.perform(get("/ktv-api/notifications/status")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("请先登录"));
        reset(access);
        when(access.who(any())).thenReturn(object("role","supplier","subject","A01"));
        when(notices.admin(any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可管理通知"));
        mvc.perform(get("/ktv-api/notifications/admin")).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("仅管理员可管理通知"));
    }
}
