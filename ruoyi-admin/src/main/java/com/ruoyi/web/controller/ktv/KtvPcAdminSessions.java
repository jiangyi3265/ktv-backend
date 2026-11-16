package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import static com.ruoyi.web.controller.ktv.KtvService.*;

/** Reuses PC authentication, password lockout, permission checks and session invalidation. */
@Anonymous
@RestController
@RequestMapping("/ktv-api/sessions")
public class KtvPcAdminSessions {
    private final SysLoginService login; private final TokenService tokens;
    private final ISysUserService users; private final SysPermissionService permissions;
    public KtvPcAdminSessions(SysLoginService login,TokenService tokens,ISysUserService users,SysPermissionService permissions) {
        this.login=login;this.tokens=tokens;this.users=users;this.permissions=permissions;
    }
    boolean allowed(long id) {
        SysUser user=users.selectUserById(id);
        return user!=null && "0".equals(user.getStatus()) && "0".equals(user.getDelFlag()) && (user.isAdmin() || permissions.getMenuPermission(user).contains("ktv:manage"));
    }
    @PostMapping("/pc-admin") public Object login(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        String username=text(body,"username",64),password=body.getString("password");
        require(password!=null && password.length()<=72,400,"请输入管理员密码");
        String jwt=login.login(username,password,body.getString("code"),body.getString("uuid"));
        LoginUser user=tokens.getLoginUser(new HttpServletRequestWrapper(request){@Override public String getHeader(String name){return "Authorization".equalsIgnoreCase(name)?"Bearer "+jwt:super.getHeader(name);}});
        require(user!=null,401,"登录失败，请重试");
        if(!allowed(user.getUserId())) {tokens.delLoginUser(user.getToken());require(false,403,"此后台账号没有 KTV 管理权限");}
        return object("role","admin","token","pc."+jwt);
    }
}
