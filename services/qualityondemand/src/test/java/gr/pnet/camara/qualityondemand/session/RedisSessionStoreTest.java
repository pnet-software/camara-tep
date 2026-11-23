package gr.pnet.camara.qualityondemand.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import gr.pnet.camara.qualityondemand.generated.status.model.StatusInfo;
import tools.jackson.databind.json.JsonMapper;

/** The keys and operations the store uses; {@link RedisSessionStoreIT} runs them against a real Redis */
class RedisSessionStoreTest {

    private static final String PHONE = "+306912345678";

    private final JsonMapper json = JsonMapper.builder().build();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private ZSetOperations<String, String> zsets;
    private RedisSessionStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(zsets);
        store = new RedisSessionStore(redis, json);
    }

    @Test
    void createWritesTheSessionItsIndexesAndItsExpiry() {
        QodSession session = SessionFixtures.available(PHONE);

        store.create(session);

        verify(values).set("qod:session:" + session.id(), json.writeValueAsString(session));
        verify(sets).add("qod:device:" + PHONE, session.id().toString());
        verify(values).set("qod:subscription:" + session.subscriptionId(), session.id().toString());
        verify(zsets).add("qod:expiry", session.id().toString(), session.expiresAt().toEpochMilli());
    }

    @Test
    void sessionsRoundTripThroughJson() {
        QodSession session = SessionFixtures.available(PHONE, "200.24.24.0/24", new Ports(List.of(5060),
                List.of(new Ports.Range(5000, 5010))), Ports.ANY);
        when(values.get("qod:session:" + session.id())).thenReturn(json.writeValueAsString(session));
        when(values.get("qod:subscription:sub-9")).thenReturn(session.id().toString());

        assertThat(store.find(session.id())).contains(session);
        assertThat(store.findBySubscription("sub-9")).contains(session);
        assertThat(store.find(UUID.randomUUID())).isEmpty();
        assertThat(store.findBySubscription("unknown")).isEmpty();
    }

    // An id whose session's retention ran out is dropped from the device's set
    @Test
    void findByDevicePrunesSessionsThatAreGone() {
        QodSession kept = SessionFixtures.available(PHONE);
        UUID gone = UUID.randomUUID();
        when(sets.members("qod:device:" + PHONE))
                .thenReturn(new LinkedHashSet<>(List.of(kept.id().toString(), gone.toString())));
        when(values.get("qod:session:" + kept.id())).thenReturn(json.writeValueAsString(kept));

        assertThat(store.findByDevice(PHONE)).containsExactly(kept);
        verify(sets).remove("qod:device:" + PHONE, gone.toString());
        assertThat(store.findByDevice("+306900000000")).isEmpty();
    }

    @Test
    void updateRewritesAndReschedules() {
        QodSession session = SessionFixtures.available(PHONE);

        store.update(session);

        verify(values).set("qod:session:" + session.id(), json.writeValueAsString(session));
        verify(zsets).add("qod:expiry", session.id().toString(), session.expiresAt().toEpochMilli());
    }

    @Test
    void terminateKeepsTheSessionForTheRetentionOnly() {
        QodSession ended = SessionFixtures.available(PHONE).terminated(StatusInfo.NETWORK_TERMINATED,
                SessionFixtures.STARTED.plusSeconds(60));

        store.terminate(ended, Duration.ofSeconds(360));

        verify(values).set("qod:session:" + ended.id(), json.writeValueAsString(ended), Duration.ofSeconds(360));
        verify(redis).expire("qod:subscription:" + ended.subscriptionId(), Duration.ofSeconds(360));
        verify(zsets).remove("qod:expiry", ended.id().toString());
    }

    @Test
    void deleteRemovesEveryTrace() {
        QodSession session = SessionFixtures.available(PHONE);

        store.delete(session);

        verify(redis).delete(List.of("qod:session:" + session.id(), "qod:subscription:" + session.subscriptionId()));
        verify(sets).remove("qod:device:" + PHONE, session.id().toString());
        verify(zsets).remove("qod:expiry", session.id().toString());
    }

    @Test
    void expiryIsAScoredSetClaimedByRemoval() {
        UUID id = UUID.randomUUID();
        when(zsets.rangeByScore("qod:expiry", 0, 1_000, 0, 100)).thenReturn(Set.of(id.toString()));
        when(zsets.remove("qod:expiry", id.toString())).thenReturn(1L, 0L);

        assertThat(store.dueForExpiry(java.time.Instant.ofEpochMilli(1_000))).containsExactly(id);
        assertThat(store.claimExpiry(id)).isTrue();
        assertThat(store.claimExpiry(id)).isFalse();
        assertThat(store.dueForExpiry(java.time.Instant.ofEpochMilli(5))).isEmpty();

        store.scheduleExpiry(id, java.time.Instant.ofEpochMilli(42));
        verify(zsets).add("qod:expiry", id.toString(), 42);
    }

    @Test
    void deviceLockIsASetIfAbsentWithATtl() {
        when(values.setIfAbsent("qod:lock:device:" + PHONE, "1", Duration.ofSeconds(30))).thenReturn(true, false);

        assertThat(store.lockDevice(PHONE, Duration.ofSeconds(30))).isTrue();
        assertThat(store.lockDevice(PHONE, Duration.ofSeconds(30))).isFalse();
        store.unlockDevice(PHONE);

        verify(redis).delete("qod:lock:device:" + PHONE);
        assertThat(store.lockDevice("+306900000000", Duration.ofSeconds(30))).isFalse();
    }
}
