package com.skloda.agentscope.service;

import com.skloda.agentscope.agent.AgentConfig;
import com.skloda.agentscope.agent.AgentConfigService;
import com.skloda.agentscope.model.ChatMessage;
import com.skloda.agentscope.model.SessionInfo;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads chat history back from the distributed {@link AgentStateStore}
 * (Redis / MySQL / PostgreSQL profiles).
 *
 * <p>When a distributed store profile ({@code postgresql}, {@code mysql}, {@code redis}) is
 * active, every ReActAgent persists its {@link AgentState} under the key
 * {@code (userId="__anon__", sessionId, stateKey="agent_state")}. In this application the
 * persisted sessionId equals the agentId (the builder default), so the database holds one
 * full conversation history per agent. The frontend used to read sessions/messages from
 * in-memory structures only ({@code SessionManagerService.activeSessions} /
 * {@code InMemoryChatHistoryRepository}) — both empty after a process restart, even though
 * the history was safely persisted in the database. This service bridges that gap so
 * sessions and their full message history are restored into the UI after a restart.
 *
 * <p>The service is a no-op when no distributed store bean is present
 * (default in-memory / json file session mode): all getters return empty results.
 */
@Service
public class PersistedSessionService {

    private static final Logger log = LoggerFactory.getLogger(PersistedSessionService.class);

    /** State key used by {@link io.agentscope.core.ReActAgent} when persisting its state. */
    private static final String AGENT_STATE_KEY = "agent_state";

    /**
     * Opening marker of the RAG context block injected by
     * {@code io.agentscope.core.rag.GenericRAGHook} as an extra USER message
     * right before the assistant reply. It is internal prompt plumbing, never
     * shown in the live UI, and must not be restored as a user turn.
     */
    private static final String RAG_INJECTION_MARKER = "<retrieved_knowledge";

    /**
     * Internal attachment prefix prepended by
     * {@code AgentService#preprocessMessage} when the user uploads a file
     * ({@code [用户上传了文件: <name>, 路径: <tmp-path>]}). Rewritten to the
     * live-UI form {@code [文件: <name>]} so the temp path is not leaked.
     */
    private static final Pattern UPLOAD_PREFIX_PATTERN =
            Pattern.compile("^\\[用户上传了文件: (.+?), 路径: [^\\]]*]\\s*");

    private final AgentConfigService configService;

    /**
     * Optional distributed AgentStateStore bean. Present only when a
     * {@code redis}/{@code mysql}/{@code postgresql} Spring profile is active
     * (see {@link com.skloda.agentscope.config.DistributedStateStoreConfig}).
     * Null by default -> all read paths return empty and callers fall back to
     * the in-memory stores.
     */
    @Autowired(required = false)
    private AgentStateStore stateStore;

    public PersistedSessionService(AgentConfigService configService) {
        this.configService = configService;
    }

    public boolean isEnabled() {
        return stateStore != null;
    }

    /**
     * List all persisted sessions visible to the anonymous demo user, newest first.
     * A session whose id matches a configured agentId is linked back to that agent
     * (the historical layout); anything else is exposed as a standalone session id.
     */
    public List<SessionInfo> listSessions() {
        if (stateStore == null) {
            return List.of();
        }
        List<SessionInfo> result = new ArrayList<>();
        try {
            Set<String> sessionIds = stateStore.listSessionIds(null);
            for (String sessionId : sessionIds) {
                AgentState state = readState(sessionId);
                if (state == null) {
                    continue;
                }
                List<ChatMessage> messages = toChatMessages(state.getContext());
                if (messages.isEmpty()) {
                    continue; // skip empty history shells
                }
                SessionInfo info = new SessionInfo();
                info.setSessionId(sessionId);
                // In the current architecture the persisted sessionId is the agentId;
                // resolve a display name when the agent is still configured.
                AgentConfig cfg = configService.findAgentConfig(sessionId).orElse(null);
                info.setAgentId(cfg != null ? sessionId : null);
                info.setAgentName(cfg != null ? cfg.getName() : sessionId);
                info.setMessageCount(messages.size());
                info.setLastAccessedAt(lastTimestamp(state.getContext()));
                result.add(info);
            }
            result.sort((a, b) -> compareDesc(a.getLastAccessedAt(), b.getLastAccessedAt()));
        } catch (Exception e) {
            log.warn("Failed to list persisted sessions from AgentStateStore: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Load the full UI-visible chat history (user / assistant text) for the given sessionId.
     * Returns an empty list when the store is disabled, the session is unknown, or on read errors.
     */
    public List<ChatMessage> loadMessages(String sessionId) {
        if (stateStore == null || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        AgentState state = readState(sessionId);
        return state != null ? toChatMessages(state.getContext()) : List.of();
    }

    /** Delete the persisted state of the given session (all state keys under the slot). */
    public void deleteSession(String sessionId) {
        if (stateStore == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            if (stateStore.exists(null, sessionId)) {
                stateStore.delete(null, sessionId);
                log.info("Deleted persisted session state for '{}'", sessionId);
            }
        } catch (Exception e) {
            log.warn("Failed to delete persisted session '{}': {}", sessionId, e.getMessage());
        }
    }

    private AgentState readState(String sessionId) {
        try {
            Optional<AgentState> state = stateStore.get(null, sessionId, AGENT_STATE_KEY, AgentState.class);
            return state.orElse(null);
        } catch (Exception e) {
            log.warn("Failed to read persisted state for session '{}': {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Convert AgentScope {@link Msg}s into UI-facing {@link ChatMessage}s.
     *
     * <p>Only USER / ASSISTANT text is kept (tool-call plumbing and empty shells are
     * skipped); thinking blocks are mapped to thinkingContent so a restored history
     * renders like a live stream.
     */
    static List<ChatMessage> toChatMessages(List<Msg> context) {
        List<ChatMessage> result = new ArrayList<>();
        if (context == null) {
            return result;
        }

        for (Msg msg : context) {
            MsgRole role = msg.getRole();
            if (role == MsgRole.USER) {
                String text = joinText(msg).strip();
                if (text.isEmpty() || text.startsWith(RAG_INJECTION_MARKER)) {
                    continue; // RAG hook injection — prompt plumbing, not a user turn
                }
                ChatMessage cm = ChatMessage.user(cleanUploadPrefix(text));
                applyTimestamp(cm, msg);
                result.add(cm);
            } else if (role == MsgRole.ASSISTANT) {
                String text = joinText(msg);
                String thinking = joinThinking(msg);
                if (text.isBlank() && thinking.isBlank()) {
                    continue; // pure tool-call round — not part of the visible transcript
                }
                ChatMessage cm = ChatMessage.assistant(text, thinking.isBlank() ? null : thinking);
                applyTimestamp(cm, msg);
                result.add(cm);
            }
            // SYSTEM / TOOL messages are internal plumbing — not shown in the transcript.
        }
        return result;
    }

    /**
     * Rewrite the internal upload prefix into the live-UI transcript form:
     * {@code [用户上传了文件: a.docx, 路径: /tmp/x]} + body → {@code body + [文件: a.docx]}.
     */
    private static String cleanUploadPrefix(String text) {
        Matcher matcher = UPLOAD_PREFIX_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        String fileName = matcher.group(1);
        String body = text.substring(matcher.end()).strip();
        return body.isEmpty() ? "[文件: " + fileName + "]" : body + "\n[文件: " + fileName + "]";
    }

    private static String joinText(Msg msg) {
        return msg.getContentBlocks(TextBlock.class).stream()
                .map(TextBlock::getText)
                .filter(t -> t != null && !t.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static String joinThinking(Msg msg) {
        return msg.getContentBlocks(ThinkingBlock.class).stream()
                .map(ThinkingBlock::getThinking)
                .filter(t -> t != null && !t.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static void applyTimestamp(ChatMessage message, Msg msg) {
        String ts = formatTimestamp(msg.getTimestamp());
        if (ts != null) {
            message.setCreatedAt(ts);
        }
    }

    /** Timestamp of the newest message in the context (used as "last accessed at"). */
    private static String lastTimestamp(List<Msg> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        for (int i = context.size() - 1; i >= 0; i--) {
            String ts = formatTimestamp(context.get(i).getTimestamp());
            if (ts != null) {
                return ts;
            }
        }
        return null;
    }

    /** Msg timestamps look like {@code 2026-09-24 17:04:17.977} — trim to seconds. */
    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.length() > 19 ? raw.substring(0, 19) : raw;
    }

    private static int compareDesc(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }
}
