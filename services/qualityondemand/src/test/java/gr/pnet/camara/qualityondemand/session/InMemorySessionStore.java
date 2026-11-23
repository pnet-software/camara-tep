package gr.pnet.camara.qualityondemand.session;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The store's contract in memory, for testing the service without Redis */
class InMemorySessionStore implements SessionStore {

    final Map<UUID, QodSession> sessions = new HashMap<>();
    final Map<UUID, Duration> retained = new HashMap<>();
    final Map<UUID, Instant> expiry = new HashMap<>();
    final Set<String> locks = new HashSet<>();
    RuntimeException failOnCreate;

    @Override
    public void create(QodSession session) {
        if (failOnCreate != null) {
            throw failOnCreate;
        }
        sessions.put(session.id(), session);
        expiry.put(session.id(), session.expiresAt());
    }

    @Override
    public Optional<QodSession> find(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public List<QodSession> findByDevice(String phoneNumber) {
        return sessions.values().stream().filter(s -> s.phoneNumber().equals(phoneNumber)).toList();
    }

    @Override
    public Optional<QodSession> findBySubscription(String subscriptionId) {
        return sessions.values().stream().filter(s -> s.subscriptionId().equals(subscriptionId)).findFirst();
    }

    @Override
    public void update(QodSession session) {
        sessions.put(session.id(), session);
        expiry.put(session.id(), session.expiresAt());
    }

    @Override
    public void terminate(QodSession session, Duration retention) {
        sessions.put(session.id(), session);
        retained.put(session.id(), retention);
        expiry.remove(session.id());
    }

    @Override
    public void delete(QodSession session) {
        sessions.remove(session.id());
        expiry.remove(session.id());
    }

    @Override
    public List<UUID> dueForExpiry(Instant now) {
        return expiry.entrySet().stream().filter(e -> !e.getValue().isAfter(now)).map(Map.Entry::getKey).toList();
    }

    @Override
    public boolean claimExpiry(UUID id) {
        return expiry.remove(id) != null;
    }

    @Override
    public void scheduleExpiry(UUID id, Instant at) {
        expiry.put(id, at);
    }

    @Override
    public boolean lockDevice(String phoneNumber, Duration ttl) {
        return locks.add(phoneNumber);
    }

    @Override
    public void unlockDevice(String phoneNumber) {
        locks.remove(phoneNumber);
    }
}
