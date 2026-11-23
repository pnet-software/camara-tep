package gr.pnet.camara.qualityondemand.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import gr.pnet.camara.qualityondemand.generated.status.model.StatusInfo;
import tools.jackson.databind.json.JsonMapper;

/** The store against a real Redis; skipped where Docker isn't available, such as CI */
class RedisSessionStoreIT {

    private static final String PHONE = "+306912345678";

    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private RedisSessionStore store;

    @BeforeAll
    static void startRedis() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        redisContainer = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        redisContainer.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
        store = new RedisSessionStore(redis, JsonMapper.builder().build());
    }

    @Test
    void sessionLifecycle() {
        QodSession session = SessionFixtures.available(PHONE);
        store.create(session);

        assertThat(store.find(session.id())).contains(session);
        assertThat(store.findByDevice(PHONE)).containsExactly(session);
        assertThat(store.findBySubscription(session.subscriptionId())).contains(session);
        assertThat(store.dueForExpiry(session.expiresAt())).containsExactly(session.id());
        assertThat(store.dueForExpiry(session.expiresAt().minusSeconds(1))).isEmpty();

        QodSession ended = session.terminated(StatusInfo.DELETE_REQUESTED, SessionFixtures.STARTED.plusSeconds(5));
        store.terminate(ended, Duration.ofSeconds(360));
        assertThat(store.find(session.id())).contains(ended);
        assertThat(redis.getExpire(RedisSessionStore.sessionKey(session.id()))).isBetween(1L, 360L);
        assertThat(store.dueForExpiry(Instant.parse("2100-01-01T00:00:00Z"))).isEmpty();

        store.delete(ended);
        assertThat(store.find(session.id())).isEmpty();
        assertThat(store.findByDevice(PHONE)).isEmpty();
        assertThat(store.findBySubscription(session.subscriptionId())).isEmpty();
    }

    @Test
    void expiryIsClaimedOnce() {
        QodSession session = SessionFixtures.available(PHONE);
        store.create(session);

        assertThat(store.claimExpiry(session.id())).isTrue();
        assertThat(store.claimExpiry(session.id())).isFalse();
    }

    @Test
    void deviceLockIsExclusive() {
        assertThat(store.lockDevice(PHONE, Duration.ofSeconds(30))).isTrue();
        assertThat(store.lockDevice(PHONE, Duration.ofSeconds(30))).isFalse();
        store.unlockDevice(PHONE);
        assertThat(store.lockDevice(PHONE, Duration.ofSeconds(30))).isTrue();
    }
}
