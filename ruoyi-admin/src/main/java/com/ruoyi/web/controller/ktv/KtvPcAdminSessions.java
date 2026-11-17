package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.framework.web.service.TokenService;
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
    private final KtvPcAccountPolicy accounts; private final KtvService service;
    public KtvPcAdminSessions(SysLoginService login,TokenService tokens,KtvPcAccountPolicy accounts,KtvService service) {
        this.login=login;this.tokens=tokens;this.accounts=accounts;this.service=service;
    }
    boolean allowed(long id) {return accounts.allowed(id);}
    @PostMapping("/pc-admin") public Object login(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        String username=text(body,"username",64),password=body.getString("password");
        require(password!=null && password.length()<=72,400,"请输入管理员密码");
        String jwt=login.login(username,password,body.getString("code"),body.getString("uuid"));
        LoginUser user=tokens.getLoginUser(new HttpServletRequestWrapper(request){@Override public String getHeader(String name){return "Authorization".equalsIgnoreCase(name)?"Bearer "+jwt:super.getHeader(name);}});
        require(user!=null,401,"登录失败，请重试");
        try {
            require(allowed(user.getUserId()),403,"此后台账号没有 KTV 管理权限");
            return service.pcAdminSession(user.getUserId(),user.getCredentialVersion());
        } finally {
            // The temporary PC session only verifies the password. Mobile lifetime is independent.
            tokens.delLoginUser(user.getToken());
        }
    }
}
