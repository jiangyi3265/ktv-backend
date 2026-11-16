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
    final KtvPcAdminSessions api=new KtvPcAdminSessions(login,tokens,users,permissions);
    LoginUser fixture(long id) {
        SysUser user=new SysUser();user.setUserId(id);user.setStatus("0");user.setDelFlag("0");
        LoginUser session=new LoginUser();session.setUserId(id);session.setUser(user);session.setToken("fixture-session-key");
        when(users.selectUserById(id)).thenReturn(user);when(tokens.getLoginUser(any())).thenReturn(session);
        when(login.login("fixture-admin","fixture-password",null,null)).thenReturn("fixture-jwt");return session;
    }
    @Test void authorizedPcUserReceivesOriginalPcAuthentication() {
        fixture(1);Object result=api.login(object("username","fixture-admin","password","fixture-password"),new MockHttpServletRequest(),new MockHttpServletResponse());
        assertEquals("pc.fixture-jwt",((com.alibaba.fastjson2.JSONObject)result).getString("token"));
        verify(tokens).getLoginUser(argThat(request -> "Bearer fixture-jwt".equals(request.getHeader("Authorization"))));
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
}
