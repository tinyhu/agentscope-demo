package com.skloda.agentscope.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decorator that fixes a defect shared by the Redis / MySQL / PostgreSQL
 * {@link AgentStateStore} implementations shipped in agentscope 2.0.3.
 *
 * <h3>Defect</h3>
 * In {@code saveIfVersion(userId, sessionId, key, state, AgentStateStore.UNVERSIONED)} the
 * extension stores write the state and then re-read the just-written row to return its new
 * version, using the abstract marker type {@code State.class}:
 * <pre>
 *   save(userId, sessionId, key, state);
 *   return getVersioned(userId, sessionId, key, State.class).version();   // always throws
 * </pre>
 * {@link State} is an empty marker interface without any Jackson type information, so the
 * re-read always fails with
 * {@code InvalidDefinitionException: Cannot construct instance of io.agentscope.core.state.State}.
 * <p>
 * The branch is not exotic: {@code ReActAgent.persistAgentStateCas} calls
 * {@code saveIfVersion(..., UNVERSIONED)} whenever a versioned save hits a CAS conflict and the
 * configured {@code ConflictPolicy} is {@code OVERWRITE} (the default), so every conflict
 * crashed the async state-saving mono instead of overwriting the state.
 *
 * <h3>Fix</h3>
 * The UNVERSIONED branch is re-implemented here using the concrete runtime class of the state
 * (e.g. {@code AgentState}), which Jackson can deserialize because it carries
 * {@code @JsonCreator} / {@code @JsonProperty} metadata. All other operations are delegated
 * verbatim, so versioned CAS semantics are unchanged and
 * {@code supportsVersioning()} still reports the real store capability.
 * <p>
 * Remove the wrappers once the project moves to an agentscope version that fixes the defect
 * upstream.
 */
public final class VersioningSafeAgentStateStore implements AgentStateStore {

    private final AgentStateStore delegate;

    private VersioningSafeAgentStateStore(AgentStateStore delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps {@code delegate} into a store whose UNVERSIONED {@code saveIfVersion} branch works.
     */
    public static AgentStateStore wrap(AgentStateStore delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new VersioningSafeAgentStateStore(delegate);
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        delegate.save(userId, sessionId, key, state);
    }

    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getVersioned(userId, sessionId, key, type);
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State state, long expectedVersion) {
        if (expectedVersion != AgentStateStore.UNVERSIONED) {
            return delegate.saveIfVersion(userId, sessionId, key, state, expectedVersion);
        }
        // Re-implementation of the framework's UNVERSIONED branch (see class javadoc):
        // read the new version back with the concrete state class instead of State.class.
        delegate.save(userId, sessionId, key, state);
        @SuppressWarnings("unchecked")
        Class<State> concreteType = (Class<State>) state.getClass();
        return delegate.getVersioned(userId, sessionId, key, concreteType).version();
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        delegate.save(userId, sessionId, key, states);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, sessionId, key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(userId, sessionId, key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
