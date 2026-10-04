package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.AuthRequest;
import com.example.server.dto.AuthResponse;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    public static final String REQUEST_USER_ID = "authenticatedUserId";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String PASSWORD_PREFIX = "pbkdf2";
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int TOKEN_BYTES = 32;
    private static final long SESSION_HOURS = 24;
    private static final String SESSION_PREFIX = "auth:session:";
    private static final String LOGIN_FAILURE_PREFIX = "auth:login-failures:";
    private static final int MAX_LOGIN_FAILURES = 8;
    private static final String LOGIN_IP_FAILURE_PREFIX = "auth:login-failures:ip:";
    private static final long LOGIN_FAILURE_WINDOW_MINUTES = 10;
    private static final DefaultRedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int MAX_NICKNAME_LENGTH = 50;

    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int maxLoginFailuresPerIp;
    /** 账号不存在时用它跑一次 PBKDF2，使响应时间与真实校验一致，无法据此判断账号是否注册。 */
    private final String timingDummyHash;

    public AuthService(StringRedisTemplate redisTemplate, UserMapper userMapper) {
        this(redisTemplate, userMapper, 30);
    }

    @Autowired
    public AuthService(StringRedisTemplate redisTemplate,
                       UserMapper userMapper,
                       @Value("${auth.login.max-failures-per-ip:30}") int maxLoginFailuresPerIp) {
        this.redisTemplate = redisTemplate;
        this.userMapper = userMapper;
        this.maxLoginFailuresPerIp = maxLoginFailuresPerIp;
        this.timingDummyHash = hashPassword("timing-equalizer-password");
    }

    public AuthResponse register(AuthRequest request) {
        String username = normalizeUsername(request.username());
        String password = request.password();
        if (username == null || password == null || password.length() < 8
                || password.length() > MAX_PASSWORD_LENGTH) {
            return response(400, "账号需为 3-32 位字母、数字或下划线，密码需为 8-128 位", null, null);
        }

        String nickname = normalizeNickname(request.nickname());
        if (nickname == null) {
            return response(400, "昵称不能超过 50 个字符", null, null);
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        if (userMapper.selectCount(query) > 0) {
            return response(409, "该账号已存在", null, null);
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(hashPassword(password));
        user.setNickname(nickname.isBlank() ? "用户" + System.currentTimeMillis() : nickname);
        user.setRole("USER");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException error) {
            return response(409, "该账号已存在", null, null);
        }
        log.info("user_registered userId={} username={}", user.getId(), username);
        return response(200, "注册成功", userView(user), null);
    }

    public AuthResponse login(AuthRequest request) {
        return login(request, null);
    }

    public AuthResponse login(AuthRequest request, String clientIp) {
        String username = normalizeUsername(request.username());
        if (username == null || request.password() == null || request.password().isBlank()) {
            return response(400, "请输入账号和密码", null, null);
        }
        String ipKey = loginIpFailureKey(clientIp);
        if (!loginAttemptAllowed(username)
                || (ipKey != null && !attemptAllowed(ipKey, maxLoginFailuresPerIp))) {
            return response(429, "登录尝试过于频繁，请稍后再试", null, null);
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        User user = userMapper.selectOne(query);
        if (user == null) {
            passwordMatches(request.password(), timingDummyHash);
        }
        if (user == null || !passwordMatches(request.password(), user.getPassword())) {
            recordLoginFailure(username);
            // 成功登录不清 IP 计数：否则登录一次自己的账号就能把喷洒攻击的计数清零。
            if (ipKey != null) recordFailure(ipKey);
            return response(401, "账号或密码错误", null, null);
        }

        if (!isHashed(user.getPassword())) {
            user.setPassword(hashPassword(request.password()));
            userMapper.updateById(user);
        }
        clearLoginFailures(username);
        String token = createSession(user.getId());
        log.info("user_logged_in userId={}", user.getId());
        return response(200, "登录成功", userView(user), token);
    }

    public void requireAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new SecurityException("仅管理员可操作失败任务");
        }
    }

    public String hashPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(password.toCharArray(), salt, PASSWORD_ITERATIONS);
        return String.join("$",
                PASSWORD_PREFIX,
                String.valueOf(PASSWORD_ITERATIONS),
                Base64.getEncoder().withoutPadding().encodeToString(salt),
                Base64.getEncoder().withoutPadding().encodeToString(hash));
    }

    public boolean passwordMatches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) return false;
        if (!isHashed(storedPassword)) {
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    storedPassword.getBytes(StandardCharsets.UTF_8));
        }

        try {
            String[] parts = storedPassword.split("\\$", -1);
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean isHashed(String password) {
        return password != null && password.startsWith(PASSWORD_PREFIX + "$");
    }

    public String createSession(Long userId) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        redisTemplate.opsForValue().set(sessionKey(token), String.valueOf(userId), SESSION_HOURS, TimeUnit.HOURS);
        return token;
    }

    public Long resolveUser(String authorization) {
        String token = bearerToken(authorization);
        String userId = redisTemplate.opsForValue().get(sessionKey(token));
        if (userId == null) throw new SecurityException("登录状态已失效");
        return Long.valueOf(userId);
    }

    public void revokeSession(String authorization) {
        redisTemplate.delete(sessionKey(bearerToken(authorization)));
    }

    public boolean loginAttemptAllowed(String username) {
        return attemptAllowed(loginFailureKey(username), MAX_LOGIN_FAILURES);
    }

    public void recordLoginFailure(String username) {
        recordFailure(loginFailureKey(username));
    }

    private boolean attemptAllowed(String key, int maxFailures) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) return true;
        try {
            return Long.parseLong(value) < maxFailures;
        } catch (NumberFormatException e) {
            redisTemplate.delete(key);
            return true;
        }
    }

    private void recordFailure(String key) {
        redisTemplate.execute(RECORD_FAILURE, List.of(key),
                String.valueOf(TimeUnit.MINUTES.toMillis(LOGIN_FAILURE_WINDOW_MINUTES)));
    }

    private String loginIpFailureKey(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? null : LOGIN_IP_FAILURE_PREFIX + clientIp.trim();
    }

    public void clearLoginFailures(String username) {
        redisTemplate.delete(loginFailureKey(username));
    }

    private String loginFailureKey(String username) {
        // MySQL's username collation is case-insensitive; every spelling shares this counter.
        return LOGIN_FAILURE_PREFIX + username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUsername(String username) {
        if (username == null) return null;
        String normalized = username.trim();
        return normalized.matches("[A-Za-z0-9_]{3,32}") ? normalized : null;
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return "";
        String normalized = nickname.trim();
        return normalized.length() <= MAX_NICKNAME_LENGTH ? normalized : null;
    }

    private AuthResponse.UserInfo userView(User user) {
        return new AuthResponse.UserInfo(
                user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), user.getRole());
    }

    private AuthResponse response(int code,
                                  String message,
                                  AuthResponse.UserInfo userInfo,
                                  String token) {
        return new AuthResponse(code, message, userInfo, token);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SecurityException("请先登录");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.length() < 32) throw new SecurityException("无效的登录凭证");
        return token;
    }

    private String sessionKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return SESSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成会话摘要", e);
        }
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        } finally {
            spec.clearPassword();
        }
    }
}
