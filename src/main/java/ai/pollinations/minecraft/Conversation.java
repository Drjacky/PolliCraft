package ai.pollinations.minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Chat memory between one player and one AI villager. Keeps the system prompt plus the latest turns. */
public final class Conversation {

    private final String systemPrompt;
    private final int maxMessages;
    private final Deque<PollinationsClient.Message> turns = new ArrayDeque<>();
    private long lastActivity;

    public Conversation(String systemPrompt, int maxMessages, long now) {
        this.systemPrompt = systemPrompt;
        this.maxMessages = Math.max(2, maxMessages);
        this.lastActivity = now;
    }

    public synchronized void addUser(String text, long now) {
        add(new PollinationsClient.Message("user", text));
        lastActivity = now;
    }

    public synchronized void addAssistant(String text, long now) {
        add(new PollinationsClient.Message("assistant", text));
        lastActivity = now;
    }

    /** Puts remembered turns from an earlier talk in front of this one, keeping the newest. */
    public synchronized void restore(List<PollinationsClient.Message> earlier) {
        List<PollinationsClient.Message> current = new ArrayList<>(turns);
        turns.clear();
        earlier.forEach(this::add);
        current.forEach(this::add);
    }

    /** Drops the last user turn when a request failed, so a retry does not repeat it. */
    public synchronized void dropLastUser() {
        if (!turns.isEmpty() && "user".equals(turns.peekLast().role())) {
            turns.pollLast();
        }
    }

    private void add(PollinationsClient.Message message) {
        turns.addLast(message);
        while (turns.size() > maxMessages) {
            turns.pollFirst();
        }
        // A chat history must not start with an assistant turn.
        while (!turns.isEmpty() && "assistant".equals(turns.peekFirst().role())) {
            turns.pollFirst();
        }
    }

    public synchronized List<PollinationsClient.Message> messages() {
        List<PollinationsClient.Message> list = new ArrayList<>(turns.size() + 1);
        list.add(new PollinationsClient.Message("system", systemPrompt));
        list.addAll(turns);
        return list;
    }

    /** The turns without the system prompt, for saving. */
    public synchronized List<PollinationsClient.Message> history() {
        return new ArrayList<>(turns);
    }

    public synchronized boolean isIdle(long now, long idleMillis) {
        return now - lastActivity > idleMillis;
    }

    public synchronized int size() {
        return turns.size();
    }
}
