package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: 启动时把「安全相关开关」的实际状态打进日志。
 * <p>
 * 为什么需要它：鉴权是否开启原本是「静默」的 —— 有人把 {@code @Configuration}
 * 注释掉之后，没有任何日志提示，容易出现「以为有鉴权、其实没有」的情况，
 * 尤其在把本地配置误部署到线上时非常危险。
 * <p>
 * 现在关闭鉴权会打 **WARN**，让状态一眼可见。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityModeReporter {

    private final AgentProperties agentProperties;

    @PostConstruct
    public void report() {
        if (agentProperties.getSecurity().isEnabled()) {
            log.info("登录鉴权已启用（nexus.agent.security.enabled=true）");
        } else {
            log.warn("""
                    
                    ============================================================
                      ⚠️  登录鉴权已关闭（nexus.agent.security.enabled=false）
                      ⚠️  所有接口无需 token 即可访问，用户上下文将为空。
                      ⚠️  这**只能用于本地开发**，生产环境必须是 true！
                    ============================================================""");
        }
    }
}
