package com.ruoyi.web.controller.ktv;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.*;
import com.ruoyi.system.service.ISysUserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.web.controller.ktv.KtvService.object;

class KtvPcAdminSessionsTest {
    final SysLoginService login=mock(SysLoginService.class);
    final TokenService tokens=mock(TokenService.class);
    final ISysUserService users=mock(ISysUserService.class);
    final SysPermissionService permissions=mock(SysPermissionService.class);
    final KtvService service=mock(KtvService.class);
    final KtvPcAccountPolicy accounts=new KtvPcAccountPolicy(users,permissions);
    final KtvPcAdminSessions api=new KtvPcAdminSessions(login,tokens,accounts,service);
    @Test void closedPortalReturnsForbiddenWithoutAttemptingPasswordLogin() throws Exception {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"小程序工作人员入口已关闭，请使用电脑后台处理订单")).when(service).requireStaffPortal();
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(api)
            .setControllerAdvice(new com.ruoyi.framework.web.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ktv-api/sessions/pc-admin")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"username\":\"fixture-admin\",\"password\":\"fixture-password\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("小程序工作人员入口已关闭，请使用电脑后台处理订单"));
        verifyNoInteractions(login,tokens);
    }
    LoginUser fixture(long id) {
        SysUser user=new SysUser();user.setUserId(id);user.setStatus("0");user.setDelFlag("0");user.setPassword("fixture-hash");
        LoginUser session=new LoginUser();session.setUserId(id);session.setUser(user);session.setToken("fixture-session-key");
        session.setCredentialVersion(com.ruoyi.common.utils.SecurityUtils.credentialVersion(user.getPassword()));
        when(users.selectUserById(id)).thenReturn(user);when(tokens.getLoginUser(any())).thenReturn(session);
        when(login.login("fixture-admin","fixture-password",null,null)).thenReturn("fixture-jwt");return session;
    }
    @Test void authorizedPcUserReceivesIndependentMobileSessionAndTemporaryJwtIsRevoked() {
        LoginUser loginUser=fixture(1);when(service.pcAdminSession(1,loginUser.getCredentialVersion())).thenReturn(object("token","opaque-mobile-session","role","admin","subject","pc-1"));
        Object result=api.login(object("username","fixture-admin","password","fixture-password"),new MockHttpServletRequest(),new MockHttpServletResponse());
        assertEquals("opaque-mobile-session",((com.alibaba.fastjson2.JSONObject)result).getString("token"));
        verify(tokens).getLoginUser(argThat(request -> "Bearer fixture-jwt".equals(request.getHeader("Authorization"))));
        verify(tokens).delLoginUser("fixture-session-key");
    }
    @Test void loggedInUserWithoutKtvPermissionIsRejectedAndSessionRevoked() {
        fixture(2);when(permissions.getMenuPermission(any())).thenReturn(Collections.emptySet());
        assertThrows(ResponseStatusException.class,()->api.login(object("username","fixture-admin","password","fixture-password"),new MockHttpServletRequest(),new MockHttpServletResponse()));
        verify(tokens).delLoginUser("fixture-session-key");
    }
    @Test void disabledAdministratorCannotBindOrReceiveMerchantNotifications() {
        LoginUser session=fixture(1);session.getUser().setStatus("1");assertFalse(api.allowed(1));
        session.getUser().setStatus("0");session.getUser().setDelFlag("2");assertFalse(api.allowed(1));
    }
    @Test void mobileSessionFailureStillRevokesTemporaryJwt() {
        LoginUser user=fixture(1);when(service.pcAdminSession(1,user.getCredentialVersion())).thenThrow(new IllegalStateException("test database unavailable"));
        assertThrows(IllegalStateException.class,()->api.login(object("username","fixture-admin","password","fixture-password"),new MockHttpServletRequest(),new MockHttpServletResponse()));
        verify(tokens).delLoginUser("fixture-session-key");
    }
    @Test void livePolicyRejectsChangedPasswordAndPermission() {
        LoginUser user=fixture(2);when(permissions.getMenuPermission(any())).thenReturn(Collections.singleton("ktv:manage"));
        assertTrue(accounts.matches(2,user.getCredentialVersion()));
        user.getUser().setPassword("new-hash");assertFalse(accounts.matches(2,user.getCredentialVersion()));
        when(permissions.getMenuPermission(any())).thenReturn(Collections.emptySet());assertFalse(accounts.allowed(2));
        assertFalse(accounts.matches(2,null));
    }
}
