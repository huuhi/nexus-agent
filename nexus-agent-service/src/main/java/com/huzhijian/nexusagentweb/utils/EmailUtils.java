package com.huzhijian.nexusagentweb.utils;

import cn.hutool.core.util.RandomUtil;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import static com.huzhijian.nexusagentweb.content.RedisContent.EMAIL_CODE_PREFIX;


@Configuration
@Slf4j
public class EmailUtils {
    

    private final JavaMailSender mailSender;
    private final RedisUtils redisUtils;

    public EmailUtils(JavaMailSender mailSender, RedisUtils redisUtils) {
        this.mailSender = mailSender;
        this.redisUtils = redisUtils;
    }

    /**
     * 发送验证码邮件。
     * <p>
     * ⚠️ <b>2026-10-05 修复（两处，都是"看起来能跑、实际等于没保护"）</b>：
     * <ol>
     *   <li>{@code mailSender.send()} 原来写在 try 块**外面** —— 真正会失败的
     *       SMTP 连接/认证一步都没被保护；</li>
     *   <li>catch 的是 {@code jakarta.mail.MessagingException}，而 Spring 的
     *       {@code JavaMailSender.send()} 抛的是 {@code org.springframework.mail.MailException}
     *       （**unchecked**，与 MessagingException 毫无继承关系）—— 只 catch 前者等于没 catch。</li>
     * </ol>
     * 原实现的 catch 里还直接 {@code return} 且不打日志：验证码已经写进 Redis 了，
     * 邮件其实没发出去，日志里却一片干净，"用户说没收到邮件"时完全无从查起。
     */
    public void sendEmail(String to, String subject) {
        String verificationCode = RandomUtil.randomNumbers(4);
//         写入redis缓存！有效期5分钟！
        redisUtils.set(EMAIL_CODE_PREFIX + to, verificationCode, 5L);
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom("3108967414@qq.com");  // 发件人
            helper.setTo(to);                     // 收件人
            helper.setSubject(subject);           // 邮件主题

            // 创建HTML格式的邮件内容
            String htmlContent = buildHtmlContent(verificationCode);
            helper.setText(htmlContent, true);    // 设置为HTML格式
//            ⚠️ send 必须在 try 内，且必须同时接住 MailException（Spring 侧的 unchecked 异常）
            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            log.error("验证码邮件发送失败：to={} 原因={}", to, e.getMessage());
            throw new ValidationException("邮件发送失败，请稍后重试");
        }
    }
    
    // 构建HTML格式的邮件内容
    private String buildHtmlContent(String verificationCode) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>验证码邮件</title>
                <style>
                    body {
                        font-family: 'Microsoft YaHei', Arial, sans-serif;
                        background-color: #f5f5f5;
                        margin: 0;
                        padding: 0;
                    }
                    .container {
                        max-width: 600px;
                        margin: 20px auto;
                        background-color: #ffffff;
                        border-radius: 10px;
                        box-shadow: 0 0 10px rgba(0,0,0,0.1);
                        overflow: hidden;
                    }
                    .header {
                        background-color: #4CAF50;
                        color: white;
                        padding: 20px;
                        text-align: center;
                    }
                    .content {
                        padding: 30px;
                        text-align: center;
                    }
                    .verification-code {
                        font-size: 32px;
                        font-weight: bold;
                        color: #4CAF50;
                        letter-spacing: 5px;
                        margin: 30px 0;
                        padding: 15px;
                        border: 2px dashed #4CAF50;
                        border-radius: 5px;
                        display: inline-block;
                    }
                    .footer {
                        background-color: #f8f8f8;
                        padding: 20px;
                        text-align: center;
                        color: #666;
                        font-size: 14px;
                    }
                    .note {
                        color: #ff5722;
                        font-size: 14px;
                        margin-top: 20px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>欢迎使用我们的服务</h1>
                    </div>
                    <div class="content">
                        <h2>您的验证码是：</h2>
                        <div class="verification-code">%s</div>
                        <p>请在5分钟内使用此验证码完成验证。</p>
                        <p class="note">如果这不是您本人操作，请忽略此邮件。</p>
                    </div>
                    <div class="footer">
                        <p>此邮件由系统自动发送，请勿回复。</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(verificationCode);
    }
}
