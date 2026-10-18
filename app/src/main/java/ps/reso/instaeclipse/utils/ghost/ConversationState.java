package ps.reso.instaeclipse.utils.ghost;

import java.lang.ref.WeakReference;
import java.util.Objects;

/** Monotonic binding token: A -> B -> A and account switches invalidate old callbacks. */
public final class ConversationState {
    public static final class Token {
        public final String accountId, threadId;
        public final long generation;
        private final WeakReference<Object> owner, view;
        private Token(Object owner, Object view, String account, String thread, long generation) {
            this.owner = new WeakReference<>(owner); this.view = new WeakReference<>(view);
            this.accountId = account; this.threadId = thread; this.generation = generation;
        }
        private boolean same(Object owner, Object view, String account, String thread) {
            return owner != null && view != null && this.owner.get() == owner && this.view.get() == view
                    && Objects.equals(accountId, account) && Objects.equals(threadId, thread);
        }
    }
    private long generation;
    private Token current;
    public synchronized Token bind(Object owner, Object view, String account, String thread) {
        if (owner == null || view == null || !ConversationIdentity.validId(account)
                || !ConversationIdentity.validId(thread)) { invalidate(); return null; }
        if (current == null || !current.same(owner, view, account, thread))
            current = new Token(owner, view, account, thread, ++generation);
        return current;
    }
    public synchronized boolean matches(Token token) {
        return token != null && current != null && current.generation == token.generation
                && current.same(token.owner.get(), token.view.get(), token.accountId, token.threadId);
    }
    public synchronized void invalidate() { current = null; generation++; }
}
