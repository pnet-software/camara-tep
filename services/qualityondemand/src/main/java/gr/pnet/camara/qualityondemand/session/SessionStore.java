package gr.pnet.camara.qualityondemand.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where sessions live between requests, shared by every replica of the service.
 */
public interface SessionStore {

    /** Stores a new AVAILABLE session, indexed by device and TEP subscription, and due to expire at its expiresAt */
    void create(QodSession session);

    Optional<QodSession> find(UUID id);

    /** The device's sessions, ended ones included while they are kept */
    List<QodSession> findByDevice(String phoneNumber);

    Optional<QodSession> findBySubscription(String subscriptionId);

    /** Rewrites an AVAILABLE session, and moves its expiry to its expiresAt */
    void update(QodSession session);

    /** Rewrites a session that ended, kept for {@code retention} and no longer due to expire */
    void terminate(QodSession session, Duration retention);

    void delete(QodSession session);

    /** Ids of the sessions due to expire by {@code now} */
    List<UUID> dueForExpiry(Instant now);

    /** Takes a due session off the expiry list; true for the one caller, across replicas, that did */
    boolean claimExpiry(UUID id);

    void scheduleExpiry(UUID id, Instant at);

    /** Holds the device's create lock for at most {@code ttl}; false if someone else holds it */
    boolean lockDevice(String phoneNumber, Duration ttl);

    void unlockDevice(String phoneNumber);
}
