package com.ruoyi.web.controller.ktv;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.stereotype.Component;

/** Live account checks shared by mobile sessions and merchant notifications. */
@Component
public class KtvPcAccountPolicy {
    private final ISysUserService users;
    private final SysPermissionService permissions;
    public KtvPcAccountPolicy(ISysUserService users, SysPermissionService permissions) {
        this.users=users;this.permissions=permissions;
    }
    String credential(long id) {
        SysUser user=users.selectUserById(id);
        if(user==null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())
                || !(user.isAdmin() || permissions.getMenuPermission(user).contains("ktv:manage"))) return null;
        return SecurityUtils.credentialVersion(user.getPassword());
    }
    boolean allowed(long id) {return credential(id)!=null;}
    boolean matches(long id,String version) {return version!=null && version.equals(credential(id));}
}
