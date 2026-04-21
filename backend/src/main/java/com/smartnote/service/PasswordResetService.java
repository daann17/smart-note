package com.smartnote.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartnote.dto.ResetPasswordRequest;
import com.smartnote.entity.User;
import com.smartnote.repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final RegisterEmailVerificationService registerEmailVerificationService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Cache<String, VerificationCodeEntry> verificationCodes;
    private final int resendSeconds;
    private final String mailUsername;
    private final String codeSubject;

    public PasswordResetService(
            UserRepository userRepository,
            JavaMailSender mailSender,
            PasswordEncoder passwordEncoder,
            RegisterEmailVerificationService registerEmailVerificationService,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${smartnote.auth.password-reset-code.expire-minutes:10}") long expireMinutes,
            @Value("${smartnote.auth.password-reset-code.resend-seconds:60}") int resendSeconds,
            @Value("${smartnote.auth.password-reset-code.subject:SmartNote 重置密码验证码}") String codeSubject
    ) {
        this.userRepository = userRepository;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.registerEmailVerificationService = registerEmailVerificationService;
        this.mailUsername = mailUsername == null ? "" : mailUsername.trim().toLowerCase(Locale.ROOT);
        this.resendSeconds = resendSeconds;
        this.codeSubject = codeSubject;
        this.verificationCodes = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, expireMinutes)))
                .maximumSize(10_000)
                .build();
    }

    public void sendResetCode(String email) {
        String normalizedEmail = registerEmailVerificationService.normalizeEmailForStorage(email);
        getUserByEmail(normalizedEmail);

        VerificationCodeEntry existing = verificationCodes.getIfPresent(normalizedEmail);
        if (existing != null && Duration.between(existing.sentAt(), LocalDateTime.now()).getSeconds() < resendSeconds) {
            throw new IllegalArgumentException("验证码发送过于频繁，请稍后再试");
        }

        ensureMailConfigured();

        String verificationCode = String.format("%06d", secureRandom.nextInt(1_000_000));
        sendVerificationEmail(normalizedEmail, verificationCode);
        verificationCodes.put(normalizedEmail, new VerificationCodeEntry(verificationCode, LocalDateTime.now()));
    }

    public void resetPassword(ResetPasswordRequest request) {
        String normalizedEmail = registerEmailVerificationService.normalizeEmailForStorage(request.getEmail());
        String normalizedCode = request.getVerificationCode() == null ? "" : request.getVerificationCode().trim();
        String normalizedPassword = request.getNewPassword() == null ? "" : request.getNewPassword().trim();

        if (normalizedCode.isBlank()) {
            throw new IllegalArgumentException("请输入邮箱验证码");
        }

        if (normalizedPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于 6 位");
        }

        VerificationCodeEntry entry = verificationCodes.getIfPresent(normalizedEmail);
        if (entry == null) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }

        if (!entry.code().equals(normalizedCode)) {
            throw new IllegalArgumentException("验证码错误");
        }

        User user = getUserByEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(normalizedPassword));
        userRepository.save(user);
        verificationCodes.invalidate(normalizedEmail);
    }

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("该邮箱尚未注册账号"));
    }

    private void ensureMailConfigured() {
        if (mailUsername.isBlank()) {
            throw new IllegalStateException("邮箱服务未配置，暂时无法发送验证码");
        }
    }

    private void sendVerificationEmail(String email, String verificationCode) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(email);
            helper.setSubject(codeSubject);
            helper.setFrom(mailUsername);
            helper.setText(buildMailContent(verificationCode), false);
            mailSender.send(message);
        } catch (MessagingException | MailSendException exception) {
            throw new RuntimeException("验证码邮件发送失败，请稍后重试", exception);
        }
    }

    private String buildMailContent(String verificationCode) {
        return """
                您正在重置 SmartNote 账号密码。
                本次验证码为：%s

                验证码 10 分钟内有效，请勿泄露给他人。
                如果这不是您的操作，请尽快检查账号安全。
                """.formatted(verificationCode);
    }

    private record VerificationCodeEntry(String code, LocalDateTime sentAt) {
    }
}
