package com.skloda.agentscope.config;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link VersioningSafeAgentStateStore}: the UNVERSIONED {@code saveIfVersion}
 * branch must read the new version back with the concrete state class instead of the
 * abstract {@code State.class} (which always fails to deserialize).
 */
class VersioningSafeAgentStateStoreTest {

    private final AgentStateStore delegate = mock(AgentStateStore.class);
    private final AgentStateStore store = VersioningSafeAgentStateStore.wrap(delegate);
    private final AgentState state = AgentState.builder()
            .sessionId("sess-1")
            .userId("user-1")
            .build();

    @Test
    void unversionedSaveReadsVersionBackWithConcreteClass() {
        when(delegate.getVersioned("user-1", "sess-1", "agent_state", AgentState.class))
                .thenReturn(new VersionedState<>(state, 7L));

        long version = store.saveIfVersion(
                "user-1", "sess-1", "agent_state", state, AgentStateStore.UNVERSIONED);

        assertEquals(7L, version);
        verify(delegate).save("user-1", "sess-1", "agent_state", state);
        // Crucial: the concrete runtime class must be used, not State.class.
        verify(delegate).getVersioned("user-1", "sess-1", "agent_state", AgentState.class);
    }

    @Test
    void unversionedSaveRoundTripsThroughRealJsonCodec() {
        // The framework's buggy branch deserializes with State.class and always throws.
        String json = JsonUtils.getJsonCodec().toJson(state);
        assertThrows(JsonException.class,
                () -> JsonUtils.getJsonCodec().fromJson(json, State.class));

        // The fixed branch deserializes with the concrete class, which round-trips.
        when(delegate.getVersioned(anyString(), anyString(), anyString(), eq(AgentState.class)))
                .thenAnswer(invocation -> new VersionedState<>(
                        JsonUtils.getJsonCodec().fromJson(json, AgentState.class), 3L));

        long version = store.saveIfVersion(
                "user-1", "sess-1", "agent_state", state, AgentStateStore.UNVERSIONED);

        assertEquals(3L, version);
    }

    @Test
    void versionedSaveDelegatesVerbatim() {
        when(delegate.saveIfVersion("user-1", "sess-1", "agent_state", state, 5L)).thenReturn(6L);

        long version = store.saveIfVersion("user-1", "sess-1", "agent_state", state, 5L);

        assertEquals(6L, version);
        verify(delegate).saveIfVersion("user-1", "sess-1", "agent_state", state, 5L);
        verify(delegate, never()).save(anyString(), anyString(), anyString(), eq(state));
    }

    @Test
    void plainOperationsDelegate() {
        when(delegate.exists("user-1", "sess-1")).thenReturn(true);

        store.save("user-1", "sess-1", "agent_state", state);

        verify(delegate).save("user-1", "sess-1", "agent_state", state);
        org.junit.jupiter.api.Assertions.assertTrue(store.exists("user-1", "sess-1"));
    }
}
