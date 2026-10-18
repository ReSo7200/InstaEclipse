package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Resolves only the native removing store's message and account; never reads message text. */
final class UnsentMessageResolver {
    private static final String REMOVE = "Client context should not be null if messageId is null.";
    private static final String ID = "DirectMessage_getId_null_client_context";
    private static final String CLIENT = "DirectMessage_getClientContext_null";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String USER = "com.instagram.user.model.User";
    private static final String KEY = "com.instagram.model.direct.DirectThreadKey";
    static final int MAX_MESSAGES = 20_000;

    static final class Selection {
        final Object threadEntry, message;
        final String accountId, messageId, clientContext, senderId;
        final boolean own;

        Selection(Object entry, Object message, String account, String id, String client, String sender) {
            this.threadEntry = entry; this.message = message; this.accountId = account;
            this.messageId = id; this.clientContext = client; this.senderId = sender;
            this.own = account.equals(sender);
        }

        String identity() { return messageId != null ? messageId : clientContext; }
    }

    private final Method entryLookup, byId, byClient, idGetter, clientGetter, sessionId, sessionEnded;
    private final Field sender, storeSession, entrySession;
    private final List<Field> messageLists;
    private final Class<?> messageClass;
    private final Comparator<Object> messageOrder;

    /** Package-visible injection seam for reflection-only fixtures; production uses resolve(). */
    UnsentMessageResolver(Method entryLookup, Method byId, Method byClient, Method idGetter,
            Method clientGetter, Field sender, Field storeSession, Field entrySession,
            Method sessionId, Method sessionEnded, List<Field> messageLists, Comparator<Object> messageOrder) {
        this.entryLookup = entryLookup; this.byId = byId; this.byClient = byClient;
        this.idGetter = idGetter; this.clientGetter = clientGetter; this.sender = sender;
        this.storeSession = storeSession; this.entrySession = entrySession;
        this.sessionId = sessionId; this.sessionEnded = sessionEnded;
        this.messageClass = idGetter.getDeclaringClass();
        this.messageOrder = messageOrder;
        this.messageLists = Collections.unmodifiableList(new ArrayList<>(messageLists));
        for (Method method : new Method[]{entryLookup, byId, byClient, idGetter, clientGetter, sessionId, sessionEnded})
            method.setAccessible(true);
        for (Field field : this.messageLists) field.setAccessible(true);
        sender.setAccessible(true); storeSession.setAccessible(true); entrySession.setAccessible(true);
    }

    static UnsentMessageResolver resolve(DexKitBridge bridge, ClassLoader loader) {
        String stage = "anchors";
        try {
            List<MethodData> anchors = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(REMOVE)));
            diagnostic("anchors", anchors.size(), anchors.size());
            List<UnsentMessageResolver> resolved = new ArrayList<>();
            for (MethodData anchor : anchors) {
                stage = "remove-shape";
                Method remove = method(anchor, loader);
                if (remove == null || Modifier.isStatic(remove.getModifiers()) || remove.getReturnType() != void.class) {
                    diagnostic(stage, 1, 0); continue;
                }
                Class<?>[] p = remove.getParameterTypes();
                if (p.length != 5 || !p[0].getName().equals(KEY) || p[1] != Integer.class
                        || p[2] != String.class || p[3] != String.class || p[4] != boolean.class) {
                    diagnostic(stage, p.length, 0); continue;
                }
                diagnostic(stage, p.length, 1);
                stage = "remove-invokes";
                Map<Method, MethodData> invokes = methods(anchor.getInvokes(), loader);
                diagnostic(stage, anchor.getInvokes().size(), invokes.size());
                MethodData entryCall = unique(invokes, "entry-call", m -> m.getDeclaringClass() == remove.getDeclaringClass()
                        && !Modifier.isStatic(m.getModifiers()) && parameters(m, p[0])
                        && !m.getReturnType().isPrimitive() && m.getReturnType() != String.class);
                MethodData ownCall = unique(invokes, "ownership-call", m -> !Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == boolean.class && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getName().equals(USER));
                if (entryCall == null || ownCall == null) continue;
                Method entryLookup = entryCall.getMethodInstance(loader), own = ownCall.getMethodInstance(loader);
                Class<?> entryType = entryLookup.getReturnType(), messageType = own.getDeclaringClass();
                // Invoked-method metadata can be a cross-dex reference without a method body.
                // Requery the exact reflected method before inspecting the sender field it reads.
                stage = "ownership-definition";
                MethodData ownDefinition = unique(methods(bridge.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().declaredClass(messageType.getName()).name(own.getName()))), loader),
                        stage, own::equals);
                if (ownDefinition == null) continue;
                stage = "sender-field";
                Field sender = uniqueField(ownDefinition, loader, messageType, String.class);
                stage = "id-getter";
                Method id = anchoredGetter(bridge, loader, messageType, ID, stage);
                stage = "client-getter";
                Method client = anchoredGetter(bridge, loader, messageType, CLIENT, stage);
                if (sender == null || id == null || client == null) continue;
                stage = "entry-methods";
                Map<Method, MethodData> getters = methods(bridge.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().declaredClass(entryType.getName()))), loader);
                stage = "id-lookup";
                MethodData idLookup = unique(getters, stage, m -> parameters(m, String.class)
                        && m.getReturnType() == messageType && !Modifier.isStatic(m.getModifiers())
                        && invokes(getters.get(m), loader, target -> target.getDeclaringClass() == Collections.class
                        && target.getName().equals("binarySearch")));
                stage = "client-lookup";
                MethodData clientLookup = unique(getters, stage, m -> parameters(m, String.class)
                        && m.getReturnType() == messageType && !Modifier.isStatic(m.getModifiers())
                        && invokes(getters.get(m), loader, client::equals));
                stage = "session-fields";
                Class<?> session = loader.loadClass(SESSION);
                Field storeSession = typedField(remove.getDeclaringClass(), session, "store-session");
                Field entrySession = typedField(entryType, session, "entry-session");
                if (idLookup == null || clientLookup == null || storeSession == null || entrySession == null) continue;
                stage = "lookup-lists";
                Set<Field> lists = listFields(idLookup, loader, entryType);
                lists.addAll(listFields(clientLookup, loader, entryType));
                diagnostic(stage, lists.size(), !lists.isEmpty() && lists.size() <= 2 ? 1 : 0);
                if (lists.isEmpty() || lists.size() > 2) continue;
                stage = "lookup-comparator";
                Comparator<Object> messageOrder = lookupComparator(idLookup, loader);
                if (messageOrder == null) continue;
                stage = "construct";
                resolved.add(new UnsentMessageResolver(entryLookup, idLookup.getMethodInstance(loader),
                        clientLookup.getMethodInstance(loader), id, client, sender, storeSession, entrySession,
                        session.getMethod("getUserId"), session.getMethod("hasEnded"), new ArrayList<>(lists), messageOrder));
            }
            diagnostic("resolved", anchors.size(), resolved.size());
            return resolved.size() == 1 ? resolved.get(0) : null;
        } catch (Throwable failure) {
            ModuleLog.line("(IE|UnsentBinding-v1) stage=" + stage + " failure=" + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static void diagnostic(String stage, int candidates, int matches) {
        ModuleLog.line("(IE|UnsentBinding-v1) stage=" + stage + " candidates=" + candidates + " matches=" + matches);
    }

    Class<?> entryType() { return entryLookup.getReturnType(); }

    Selection lookup(Object store, Object threadKey, String messageId, String clientContext) {
        String id = identifier(messageId), client = identifier(clientContext);
        if (store == null || threadKey == null || (id == null && client == null)
                || (messageId != null && id == null) || (clientContext != null && client == null)) return null;
        try {
            synchronized (store) {
                Object entry = entryLookup.invoke(store, threadKey);
                if (entry == null) return null;
                synchronized (entry) {
                    String account = liveAccount(store, entry);
                    if (account == null) return null;
                    Object message = id != null ? byId.invoke(entry, id) : byClient.invoke(entry, client);
                    if (!messageClass.isInstance(message)) return null;
                    String actualId = identifier(idGetter.invoke(message)), actualClient = identifier(clientGetter.invoke(message));
                    if ((id != null && !id.equals(actualId)) || (client != null && !client.equals(actualClient))) return null;
                    if (id != null && client != null && byClient.invoke(entry, client) != message) return null;
                    // Reject aliases/collisions in only the native lookup's own two message lists.
                    Set<Object> matches = Collections.newSetFromMap(new IdentityHashMap<>());
                    int count = 0;
                    for (Field field : messageLists) {
                        Object value = field.get(entry);
                        if (!(value instanceof List<?> list) || list.size() > MAX_MESSAGES - count) return null;
                        count += list.size();
                        for (Object candidate : list) {
                            if (candidate == null) continue;
                            if (!messageClass.isInstance(candidate)) return null;
                            if ((id != null && id.equals(identifier(idGetter.invoke(candidate))))
                                    || (client != null && client.equals(identifier(clientGetter.invoke(candidate))))) matches.add(candidate);
                        }
                    }
                    if (matches.size() != 1 || !matches.contains(message)) return null;
                    String senderId = accountId(sender.get(message));
                    if (senderId == null || !account.equals(liveAccount(store, entry))) return null;
                    return new Selection(entry, message, account, actualId, actualClient, senderId);
                }
            }
        } catch (Throwable ignored) { return null; }
    }

    String identityOf(Object message) {
        if (!messageClass.isInstance(message)) return null;
        try {
            String id = identifier(idGetter.invoke(message));
            return id != null ? id : identifier(clientGetter.invoke(message));
        } catch (Throwable ignored) { return null; }
    }

    /** Caller holds the entry lock. Never sorts or mutates Instagram's binary-search list. */
    int insertionIndex(List<Object> live, Object message) {
        if (live == null || messageOrder == null || !messageClass.isInstance(message)) return -1;
        try {
            int size = live.size();
            if (size >= MAX_MESSAGES || identifier(idGetter.invoke(message)) == null
                    || messageOrder.compare(message, message) != 0) return -1;
            int insertion = size;
            Object previous = null;
            Set<String> ids = new LinkedHashSet<>();
            ids.add(identifier(idGetter.invoke(message)));
            int index = 0;
            for (Object existing : live) {
                if (index >= size || !messageClass.isInstance(existing)) return -1;
                String id = identifier(idGetter.invoke(existing));
                if (id == null || !ids.add(id)) return -1;
                if (previous != null && (messageOrder.compare(previous, existing) >= 0
                        || messageOrder.compare(existing, previous) <= 0)) return -1;
                int order = Integer.signum(messageOrder.compare(existing, message));
                int reverse = Integer.signum(messageOrder.compare(message, existing));
                // Even different IDs must not compare equal: binarySearch could select either.
                if (order == 0 || order != -reverse) return -1;
                if (order > 0 && insertion == size) insertion = index;
                if (order < 0 && insertion != size) return -1;
                previous = existing;
                index++;
            }
            return index == size && live.size() == size ? insertion : -1;
        } catch (Throwable ignored) { return -1; }
    }

    boolean stillReceived(Object store, Object entry, Object message, String expectedAccount,
            String expectedIdentity, String expectedSender) {
        if (store == null || entry == null || !messageClass.isInstance(message)
                || accountId(expectedAccount) == null || accountId(expectedSender) == null
                || identifier(expectedIdentity) == null || expectedAccount.equals(expectedSender)) return false;
        try {
            // Reconcile may already hold the entry lock: do not acquire the store lock in reverse order.
            synchronized (entry) {
                return expectedAccount.equals(liveAccount(store, entry))
                        && expectedSender.equals(accountId(sender.get(message)))
                        && expectedIdentity.equals(identityOf(message))
                        && expectedAccount.equals(liveAccount(store, entry));
            }
        } catch (Throwable ignored) { return false; }
    }

    private String liveAccount(Object store, Object entry) throws Exception {
        Object session = storeSession.get(store);
        if (session == null || entrySession.get(entry) != session
                || !Boolean.FALSE.equals(sessionEnded.invoke(session))) return null;
        return accountId(sessionId.invoke(session));
    }

    private static String identifier(Object value) {
        if (!(value instanceof String s) || s.isEmpty() || s.length() > 256) return null;
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i)) || Character.isISOControl(s.charAt(i))) return null;
        return s;
    }

    private static String accountId(Object value) {
        String s = identifier(value);
        if (s == null || s.length() > 64) return null;
        boolean positive = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return null;
            if (s.charAt(i) != '0') positive = true;
        }
        return positive ? s : null;
    }

    private interface MethodTest { boolean accepts(Method method); }
    private static boolean parameters(Method method, Class<?>... expected) {
        return java.util.Arrays.equals(method.getParameterTypes(), expected);
    }
    private static Method method(MethodData data, ClassLoader loader) {
        try { return data.getName().startsWith("<") ? null : data.getMethodInstance(loader); }
        catch (Throwable ignored) { return null; }
    }
    private static Map<Method, MethodData> methods(List<MethodData> data, ClassLoader loader) {
        Map<Method, MethodData> out = new LinkedHashMap<>();
        for (MethodData item : data) { Method m = method(item, loader); if (m != null) out.put(m, item); }
        return out;
    }
    private static MethodData unique(Map<Method, MethodData> candidates, String stage, MethodTest test) {
        MethodData result = null;
        int count = 0;
        for (Map.Entry<Method, MethodData> candidate : candidates.entrySet()) if (test.accepts(candidate.getKey())) {
            count++;
            result = candidate.getValue();
        }
        diagnostic(stage, candidates.size(), count);
        return count == 1 ? result : null;
    }
    private static boolean invokes(MethodData data, ClassLoader loader, MethodTest test) {
        for (Method m : methods(data.getInvokes(), loader).keySet()) if (test.accepts(m)) return true;
        return false;
    }
    private static Method anchoredGetter(DexKitBridge bridge, ClassLoader loader, Class<?> type, String anchor, String stage) {
        MethodData data = unique(methods(bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(type.getName()).usingStrings(anchor))), loader), stage, m -> m.getReturnType() == String.class
                && m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers()));
        return data == null ? null : method(data, loader);
    }
    private static Field uniqueField(MethodData method, ClassLoader loader, Class<?> owner, Class<?> type) throws Exception {
        Set<Field> fields = new LinkedHashSet<>();
        for (UsingFieldData used : method.getUsingFields()) {
            Field field = used.getField().getFieldInstance(loader);
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == type
                    && field.getDeclaringClass().isAssignableFrom(owner)) fields.add(field);
        }
        diagnostic("sender-field", method.getUsingFields().size(), fields.size());
        return fields.size() == 1 ? fields.iterator().next() : null;
    }
    private static Field typedField(Class<?> owner, Class<?> type, String stage) {
        Field result = null;
        int count = 0;
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                count++;
                result = f;
            }
        diagnostic(stage, count, count);
        return count == 1 ? result : null;
    }
    private static Set<Field> listFields(MethodData method, ClassLoader loader, Class<?> owner) throws Exception {
        Set<Field> result = new LinkedHashSet<>();
        for (UsingFieldData used : method.getUsingFields()) {
            Field field = used.getField().getFieldInstance(loader);
            if (!Modifier.isStatic(field.getModifiers()) && List.class.isAssignableFrom(field.getType())
                    && field.getDeclaringClass().isAssignableFrom(owner)) result.add(field);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Comparator<Object> lookupComparator(MethodData method, ClassLoader loader) throws Exception {
        Set<Field> candidates = new LinkedHashSet<>();
        for (UsingFieldData used : method.getUsingFields()) {
            Field field = used.getField().getFieldInstance(loader);
            if (Modifier.isStatic(field.getModifiers()) && Comparator.class.isAssignableFrom(field.getType()))
                candidates.add(field);
        }
        diagnostic("lookup-comparator", method.getUsingFields().size(), candidates.size());
        if (candidates.size() != 1) return null;
        Field field = candidates.iterator().next();
        field.setAccessible(true);
        Object value = field.get(null);
        diagnostic("comparator-value", 1, value instanceof Comparator<?> ? 1 : 0);
        return value instanceof Comparator<?> ? (Comparator<Object>) value : null;
    }
}
