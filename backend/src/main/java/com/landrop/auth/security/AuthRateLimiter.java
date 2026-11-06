package com.landrop.auth.security;

import com.landrop.auth.exception.RateLimitException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe sliding window rate limiter designed to protect CPU-intensive authentication
 * flows (such as PBKDF2 with 600,000 iterations) from brute-force and DoS attacks.
 * Tracks attempts by client IP address and by account identifier.
 */
public class AuthRateLimiter {

    public static final int DEFAULT_MAX_IP_LOGIN_ATTEMPTS = 10;
    public static final int DEFAULT_MAX_IP_REGISTER_ATTEMPTS = 5;
    public static final int DEFAULT_MAX_ACCOUNT_LOGIN_ATTEMPTS = 5;
    public static final Duration DEFAULT_WINDOW_DURATION = Duration.ofSeconds(60);

    private final int maxIpLoginAttempts;
    private final int maxIpRegisterAttempts;
    private final int maxAccountLoginAttempts;
    private final long windowMillis;

    private final ConcurrentHashMap<String, Deque<Long>> ipLoginHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> ipRegisterHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> accountLoginHistory = new ConcurrentHashMap<>();

    public AuthRateLimiter() {
        this(
            DEFAULT_MAX_IP_LOGIN_ATTEMPTS,
            DEFAULT_MAX_IP_REGISTER_ATTEMPTS,
            DEFAULT_MAX_ACCOUNT_LOGIN_ATTEMPTS,
            DEFAULT_WINDOW_DURATION
        );
    }

    public AuthRateLimiter(int maxIpLoginAttempts, int maxIpRegisterAttempts, int maxAccountLoginAttempts, Duration windowDuration) {
        if (maxIpLoginAttempts <= 0 || maxIpRegisterAttempts <= 0 || maxAccountLoginAttempts <= 0) {
            throw new IllegalArgumentException("Rate limits must be positive");
        }
        if (windowDuration == null || windowDuration.isNegative() || windowDuration.isZero()) {
            throw new IllegalArgumentException("Window duration must be positive");
        }
        this.maxIpLoginAttempts = maxIpLoginAttempts;
        this.maxIpRegisterAttempts = maxIpRegisterAttempts;
        this.maxAccountLoginAttempts = maxAccountLoginAttempts;
        this.windowMillis = windowDuration.toMillis();
    }

    /**
     * Checks whether the client IP has exceeded the allowed login rate limit.
     * Throws RateLimitException with retry-after information if exceeded.
     */
    public void checkIpLogin(String ip) {
        checkRate(ipLoginHistory, normalizeKey(ip), maxIpLoginAttempts, "Too many login attempts from this IP. Please try again later.");
    }

    /**
     * Records a login attempt from a client IP address.
     */
    public void recordIpLoginAttempt(String ip) {
        recordAttempt(ipLoginHistory, normalizeKey(ip));
    }

    /**
     * Checks whether the user account has exceeded the allowed failed login rate limit.
     * Throws RateLimitException with retry-after information if exceeded.
     */
    public void checkAccountLogin(String accountKey) {
        checkRate(accountLoginHistory, normalizeKey(accountKey), maxAccountLoginAttempts, "Too many failed login attempts for this account. Please try again later.");
    }

    /**
     * Records a failed login attempt for a user account.
     */
    public void recordAccountLoginFailure(String accountKey) {
        recordAttempt(accountLoginHistory, normalizeKey(accountKey));
    }

    /**
     * Resets failed login attempts for an account upon successful login.
     */
    public void recordAccountLoginSuccess(String accountKey) {
        if (accountKey != null) {
            accountLoginHistory.remove(normalizeKey(accountKey));
        }
    }

    /**
     * Checks whether the client IP has exceeded the allowed registration rate limit.
     */
    public void checkIpRegister(String ip) {
        checkRate(ipRegisterHistory, normalizeKey(ip), maxIpRegisterAttempts, "Too many registration attempts from this IP. Please try again later.");
    }

    /**
     * Records a registration attempt from a client IP address.
     */
    public void recordIpRegisterAttempt(String ip) {
        recordAttempt(ipRegisterHistory, normalizeKey(ip));
    }

    /**
     * Clears all recorded attempt history. Useful for test isolation.
     */
    public void reset() {
        ipLoginHistory.clear();
        ipRegisterHistory.clear();
        accountLoginHistory.clear();
    }

    private void checkRate(ConcurrentHashMap<String, Deque<Long>> map, String key, int maxAttempts, String errorMessage) {
        if (key.isEmpty()) return;
        long now = System.currentTimeMillis();
        Deque<Long> deque = map.get(key);
        if (deque == null) return;

        synchronized (deque) {
            purgeExpired(deque, now);
            if (deque.size() >= maxAttempts) {
                Long oldest = deque.peekFirst();
                long retryAfter = (oldest != null)
                    ? Math.max(1, (oldest + windowMillis - now + 999) / 1000)
                    : Math.max(1, windowMillis / 1000);
                throw new RateLimitException(errorMessage, retryAfter);
            }
        }
    }

    private void recordAttempt(ConcurrentHashMap<String, Deque<Long>> map, String key) {
        if (key.isEmpty()) return;
        long now = System.currentTimeMillis();
        Deque<Long> deque = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            purgeExpired(deque, now);
            deque.addLast(now);
        }
        if (map.size() > 5000) {
            cleanupStaleEntries(map, now);
        }
    }

    private void purgeExpired(Deque<Long> deque, long now) {
        long threshold = now - windowMillis;
        while (!deque.isEmpty() && deque.peekFirst() <= threshold) {
            deque.pollFirst();
        }
    }

    private void cleanupStaleEntries(ConcurrentHashMap<String, Deque<Long>> map, long now) {
        for (Map.Entry<String, Deque<Long>> entry : map.entrySet()) {
            Deque<Long> d = entry.getValue();
            synchronized (d) {
                purgeExpired(d, now);
                if (d.isEmpty()) {
                    map.remove(entry.getKey(), d);
                }
            }
        }
    }

    private static String normalizeKey(String raw) {
        return raw != null ? raw.trim().toLowerCase() : "";
    }
}
