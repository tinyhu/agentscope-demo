package com.skloda.agentscope.service;

import com.skloda.agentscope.agent.AgentConfig;
import com.skloda.agentscope.agent.AgentConfigService;
import com.skloda.agentscope.agent.AgentFactory;
import com.skloda.agentscope.model.SessionInfo;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionManagerService {

    private static final Logger log = LoggerFactory.getLogger(SessionManagerService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentFactory agentFactory;
    private final AgentConfigService configService;
    private final Path sessionBasePath;

    private final ConcurrentHashMap<String, SessionContext> activeSessions = new ConcurrentHashMap<>();

    public SessionManagerService(AgentFactory agentFactory,
                                 AgentConfigService configService,
                                 @Value("${agentscope.session.storage-path:${user.home}/.agentscope/demo-sessions}") String storagePath) {
        this.agentFactory = agentFactory;
        this.configService = configService;
        this.sessionBasePath = Paths.get(storagePath);
        log.info("Session storage mode: in-memory (configured path ignored: {})",
                sessionBasePath.toAbsolutePath());
    }

    public static class SessionContext {
        private final String sessionId;
        private final String agentId;
        private final ReActAgent agent;
        private final AgentStateStore stateStore;
        private final long createdAt;
        private volatile long lastAccessedAt;
        private final String sessionType;

        SessionContext(String sessionId, String agentId, ReActAgent agent,
                       AgentStateStore stateStore, String sessionType) {
            this.sessionId = sessionId;
            this.agentId = agentId;
            this.agent = agent;
            this.stateStore = stateStore;
            this.createdAt = System.currentTimeMillis();
            this.lastAccessedAt = this.createdAt;
            this.sessionType = sessionType;
        }

        public String getSessionId() { return sessionId; }
        public String getAgentId() { return agentId; }
        public ReActAgent getAgent() { return agent; }
        public AgentStateStore getStateStore() { return stateStore; }
        public String getSessionType() { return sessionType; }

        public long getLastAccessedAt() { return lastAccessedAt; }
        public void touch() { this.lastAccessedAt = System.currentTimeMillis(); }
    }

    public SessionContext getOrCreateSession(String sessionId, String agentId, String sessionType) {
        if (sessionId != null && !sessionId.isBlank()) {
            SessionContext cached = activeSessions.get(sessionId);
            if (cached != null) {
                // Check if session type has changed
                AgentConfig config = configService.getAgentConfig(agentId);
                String effectiveType = resolveSessionType(config, sessionType);
                String cachedType = cached.getSessionType();
                if (effectiveType != null && !effectiveType.equalsIgnoreCase(cachedType)) {
                    log.info("Session type changed for sessionId {}: {} -> {}, migrating session",
                            sessionId, cachedType, effectiveType);
                    return migrateSession(sessionId, agentId, cached, sessionType, effectiveType);
                }
                cached.touch();
                return cached;
            }
            // Cache miss (e.g. after a process restart, or for a session restored from the
            // database). Rebuild the context under the SAME sessionId instead of discarding
            // it — the frontend stays attached to the historical session and the agent state
            // is reloaded from the AgentStateStore on the next stream call.
            log.info("Session {} not in memory, rebuilding context for agent {}", sessionId, agentId);
            return createSessionContext(sessionId, agentId, sessionType);
        }
        return createNewSession(agentId, sessionType);
    }

    private SessionContext migrateSession(String sessionId, String agentId, SessionContext oldContext,
                                         String requestType, String newType) {
        String storagePath = configService.getAgentConfig(agentId).getSessionConfig() != null
                ? configService.getAgentConfig(agentId).getSessionConfig().getStoragePath() : null;

        // Create new state store with requested type
        AgentStateStore newStateStore = agentFactory.createStateStore(newType, storagePath);
        ReActAgent newAgent = agentFactory.createAgentForSession(agentId, newStateStore);

        // Create new context
        SessionContext newContext = new SessionContext(sessionId, agentId, newAgent, newStateStore, newType);
        activeSessions.put(sessionId, newContext);
        log.info("Migrated session {} from {} to {}", sessionId, oldContext.getSessionType(), newType);
        return newContext;
    }

    public SessionContext getOrCreateSession(String sessionId, String agentId) {
        return getOrCreateSession(sessionId, agentId, null);
    }

    public SessionContext getOrCreateSessionForAgent(String agentId) {
        return activeSessions.values().stream()
                .filter(ctx -> Objects.equals(ctx.getAgentId(), agentId))
                .findFirst()
                .map(ctx -> {
                    ctx.touch();
                    return ctx;
                })
                .orElseGet(() -> {
                    // After a restart the in-memory cache is empty, but the agent may still own a
                    // conversation persisted in the distributed AgentStateStore (PG/MySQL/Redis
                    // profile) under its default session id (== agentId). Re-attach to that
                    // historical session instead of starting an orphan new one, so the restored
                    // history is continued under the same id.
                    if (hasPersistedState(agentId)) {
                        log.info("Re-attaching agent {} to its persisted session", agentId);
                        return createSessionContext(agentId, agentId, null);
                    }
                    return createNewSession(agentId);
                });
    }

    /**
     * Probe the configured AgentStateStore for persisted state of the agent's default
     * session id ({@code defaultSessionId(agentId)}). Returns false for in-memory stores.
     */
    private boolean hasPersistedState(String agentId) {
        try {
            AgentConfig config = configService.getAgentConfig(agentId);
            String effectiveType = resolveSessionType(config, null);
            String storagePath = config.getSessionConfig() != null
                    ? config.getSessionConfig().getStoragePath() : null;
            return agentFactory.createStateStore(effectiveType, storagePath).exists(null, agentId);
        } catch (Exception e) {
            log.warn("Persisted-state probe failed for agent {}: {}", agentId, e.getMessage());
            return false;
        }
    }

    public SessionContext createNewSession(String agentId, String sessionType) {
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return createSessionContext(sessionId, agentId, sessionType);
    }

    public SessionContext createNewSession(String agentId) {
        return createNewSession(agentId, null);
    }

    private SessionContext createSessionContext(String sessionId, String agentId, String sessionType) {
        AgentConfig config = configService.getAgentConfig(agentId);
        String effectiveType = resolveSessionType(config, sessionType);
        String storagePath = config.getSessionConfig() != null ? config.getSessionConfig().getStoragePath() : null;
        AgentStateStore stateStore = agentFactory.createStateStore(effectiveType, storagePath);
        ReActAgent agent = agentFactory.createAgentForSession(agentId, stateStore);
        SessionContext ctx = new SessionContext(sessionId, agentId, agent, stateStore, effectiveType);
        activeSessions.put(sessionId, ctx);
        log.info("Created session: {} for agent: {} [type={}]", sessionId, agentId, effectiveType);
        return ctx;
    }

    private SessionContext createSessionContext(String sessionId, String agentId) {
        return createSessionContext(sessionId, agentId, (String) null);
    }

    private String resolveSessionType(AgentConfig config, String requestType) {
        if (requestType != null && !requestType.isBlank()) return requestType;
        if (config.getSessionConfig() != null) return config.getSessionConfig().getDefaultType();
        return "memory";
    }

    public void saveSession(String sessionId) {
        SessionContext ctx = activeSessions.get(sessionId);
        if (ctx == null) return;
        ctx.touch();
        log.debug("Session {} touched", sessionId);
    }

    public List<SessionInfo> listSessions() {
        List<Map.Entry<String, SessionContext>> sorted = activeSessions.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().getLastAccessedAt(), a.getValue().getLastAccessedAt()))
                .toList();

        List<SessionInfo> result = new ArrayList<>();
        for (Map.Entry<String, SessionContext> entry : sorted) {
            String sid = entry.getKey();
            SessionContext ctx = entry.getValue();
            SessionInfo info = new SessionInfo();
            info.setSessionId(sid);
            info.setAgentId(ctx.getAgentId());
            AgentConfig cfg = configService.findAgentConfig(ctx.getAgentId()).orElse(null);
            info.setAgentName(cfg != null ? cfg.getName() : ctx.getAgentId());
            info.setMessageCount(0); // Session-based: message count no longer directly accessible
            info.setLastAccessedAt(formatTime(ctx.getLastAccessedAt()));
            result.add(info);
        }
        return result;
    }

    public void deleteSession(String sessionId) {
        activeSessions.remove(sessionId);
        log.info("Session {} deleted", sessionId);
    }

    /**
     * Remove every cached session context belonging to the given agent. Called when the
     * agent's persisted history is deleted — a surviving live context would otherwise
     * re-save the deleted conversation on its next message.
     */
    public void deleteSessionsByAgent(String agentId) {
        activeSessions.entrySet().removeIf(entry -> Objects.equals(entry.getValue().getAgentId(), agentId));
        log.info("Cached sessions of agent {} evicted", agentId);
    }

    public SessionContext getSession(String sessionId) {
        return sessionId != null ? activeSessions.get(sessionId) : null;
    }

    private String formatTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(FMT);
    }
}
