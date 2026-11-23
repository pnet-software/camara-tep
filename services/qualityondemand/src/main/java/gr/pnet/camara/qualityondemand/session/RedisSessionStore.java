package gr.pnet.camara.qualityondemand.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sessions in Redis, as JSON:
 * <ul>
 * <li>{@code qod:session:<id>}: the session; given a TTL once it has ended</li>
 * <li>{@code qod:device:<phone>}: the ids of the device's sessions; ids whose session is gone are pruned on read</li>
 * <li>{@code qod:subscription:<id>}: the session id of a TEP AS session with QoS</li>
 * <li>{@code qod:expiry}: ids of AVAILABLE sessions, scored by their expiresAt in epoch milliseconds</li>
 * <li>{@code qod:lock:device:<phone>}: held while a session is created for the device</li>
 * </ul>
 */
@Component
public class RedisSessionStore implements SessionStore {

    static final String EXPIRY = "qod:expiry";
    private static final int EXPIRY_BATCH = 100;

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RedisSessionStore(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    static String sessionKey(UUID id) {
        return "qod:session:" + id;
    }

    static String deviceKey(String phoneNumber) {
        return "qod:device:" + phoneNumber;
    }

    static String subscriptionKey(String subscriptionId) {
        return "qod:subscription:" + subscriptionId;
    }

    static String lockKey(String phoneNumber) {
        return "qod:lock:device:" + phoneNumber;
    }

    @Override
    public void create(QodSession session) {
        redis.opsForValue().set(sessionKey(session.id()), json.writeValueAsString(session));
        redis.opsForSet().add(deviceKey(session.phoneNumber()), session.id().toString());
        redis.opsForValue().set(subscriptionKey(session.subscriptionId()), session.id().toString());
        scheduleExpiry(session.id(), session.expiresAt());
    }

    @Override
    public Optional<QodSession> find(UUID id) {
        return Optional.ofNullable(redis.opsForValue().get(sessionKey(id)))
                .map(value -> json.readValue(value, QodSession.class));
    }

    @Override
    public List<QodSession> findByDevice(String phoneNumber) {
        Set<String> ids = redis.opsForSet().members(deviceKey(phoneNumber));
        List<QodSession> sessions = new ArrayList<>();
        for (String id : ids == null ? Set.<String>of() : ids) {
            Optional<QodSession> session = find(UUID.fromString(id));
            if (session.isPresent()) {
                sessions.add(session.get());
            } else {
                // Its retention ran out
                redis.opsForSet().remove(deviceKey(phoneNumber), id);
            }
        }
        return sessions;
    }

    @Override
    public Optional<QodSession> findBySubscription(String subscriptionId) {
        return Optional.ofNullable(redis.opsForValue().get(subscriptionKey(subscriptionId)))
                .flatMap(id -> find(UUID.fromString(id)));
    }

    @Override
    public void update(QodSession session) {
        redis.opsForValue().set(sessionKey(session.id()), json.writeValueAsString(session));
        scheduleExpiry(session.id(), session.expiresAt());
    }

    @Override
    public void terminate(QodSession session, Duration retention) {
        redis.opsForValue().set(sessionKey(session.id()), json.writeValueAsString(session), retention);
        redis.expire(subscriptionKey(session.subscriptionId()), retention);
        redis.opsForZSet().remove(EXPIRY, session.id().toString());
    }

    @Override
    public void delete(QodSession session) {
        redis.delete(List.of(sessionKey(session.id()), subscriptionKey(session.subscriptionId())));
        redis.opsForSet().remove(deviceKey(session.phoneNumber()), session.id().toString());
        redis.opsForZSet().remove(EXPIRY, session.id().toString());
    }

    @Override
    public List<UUID> dueForExpiry(Instant now) {
        Set<String> ids = redis.opsForZSet().rangeByScore(EXPIRY, 0, now.toEpochMilli(), 0, EXPIRY_BATCH);
        return ids == null ? List.of() : ids.stream().map(UUID::fromString).toList();
    }

    @Override
    public boolean claimExpiry(UUID id) {
        Long removed = redis.opsForZSet().remove(EXPIRY, id.toString());
        return removed != null && removed > 0;
    }

    @Override
    public void scheduleExpiry(UUID id, Instant at) {
        redis.opsForZSet().add(EXPIRY, id.toString(), at.toEpochMilli());
    }

    @Override
    public boolean lockDevice(String phoneNumber, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey(phoneNumber), "1", ttl));
    }

    @Override
    public void unlockDevice(String phoneNumber) {
        redis.delete(lockKey(phoneNumber));
    }
}
