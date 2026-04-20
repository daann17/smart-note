package com.smartnote.controller;

import com.smartnote.dto.AuthResponse;
import com.smartnote.dto.LoginRequest;
import com.smartnote.dto.RegisterRequest;
import com.smartnote.dto.SendRegisterCodeRequest;
import com.smartnote.entity.User;
import com.smartnote.repository.UserRepository;
import com.smartnote.service.RegisterEmailVerificationService;
import com.smartnote.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RegisterEmailVerificationService registerEmailVerificationService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword())
            );
            
            SecurityContextHolder.getContext().setAuthentication(authentication);
            String token = jwtUtil.generateToken(loginRequest.getUsername());
            User user = userRepository.findByUsername(loginRequest.getUsername())
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), resolveDisplayName(user), user.getRole()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(401).body("Error: Invalid username or password");
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest registerRequest) {
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            return ResponseEntity.badRequest().body("Error: Username is already taken!");
        }

        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            return ResponseEntity.badRequest().body("Error: Email is already in use!");
        }

        try {
            registerEmailVerificationService.verifyCode(
                    registerRequest.getEmail(),
                    registerRequest.getVerificationCode()
            );
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", exception.getMessage()));
        }

        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setEmail(registerRequest.getEmail());
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setNickname(registerRequest.getUsername());
        user.setRole("USER");
        user.setActive(true);
        // CreatedAt and UpdatedAt are handled by JPA Auditing

        userRepository.save(user);

        return ResponseEntity.ok("User registered successfully!");
    }

    @PostMapping("/register/code")
    public ResponseEntity<?> sendRegisterCode(@RequestBody SendRegisterCodeRequest request) {
        try {
            registerEmailVerificationService.sendCode(request.getEmail());
            return ResponseEntity.ok(java.util.Map.of("message", "验证码已发送，请注意查收邮箱"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(503).body(java.util.Map.of("message", exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", exception.getMessage()));
        }
    }

    private String resolveDisplayName(User user) {
        if (user.getNickname() != null && !user.getNickname().trim().isEmpty()) {
            return user.getNickname().trim();
        }
        return user.getUsername();
    }
}
