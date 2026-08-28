package com.smy101.reader.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用自有配置(reader.*),值来自 application.yml / 环境变量。
 * 凭据类值(token 等)生产经环境变量注入,不写死入库。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "reader")
public class ReaderProperties {

    private final Auth auth = new Auth();
    private final Storage storage = new Storage();

    @Getter
    @Setter
    public static class Auth {
        /** 静态 Bearer token(D-4:单用户,无注册登录) */
        private String token;
    }

    @Getter
    @Setter
    public static class Storage {
        /** 书源文件与封面落盘根目录 */
        private String root;
    }
}
