package com.ruoyi.web.controller.ktv;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import static com.ruoyi.web.controller.ktv.KtvService.require;

/** Webhook credentials are never returned to clients or written to audit logs. */
final class KtvNoticeCrypto {
    static boolean ready(String key) {return key!=null && key.matches("[a-fA-F0-9]{64}");}
    static String encrypt(String value,String key) {return crypt(value,key,true);}
    static String decrypt(String value,String key) {return crypt(value,key,false);}
    private static String crypt(String value,String key,boolean encrypt) {
        require(ready(key),503,"通知安全配置尚未完成，请联系维护人员");
        try {
            byte[] bytes=new byte[32];for(int i=0;i<32;i++)bytes[i]=(byte)Integer.parseInt(key.substring(i*2,i*2+2),16);
            byte[] iv=new byte[12],input;
            if(encrypt){new SecureRandom().nextBytes(iv);input=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
            else {String[] parts=value.split("\\.",2);iv=Base64.getDecoder().decode(parts[0]);input=Base64.getDecoder().decode(parts[1]);}
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(bytes,"AES"),new GCMParameterSpec(128,iv));
            byte[] output=cipher.doFinal(input);
            return encrypt?Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(output):new String(output,java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception e){throw new IllegalStateException("Notification credential encryption failed");}
    }
}
