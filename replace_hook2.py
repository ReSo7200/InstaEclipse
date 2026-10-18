content = """package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class HideChatsHook {

private static final String TAG = "ie_hidechat_btn";
private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";
private static final String DIFF_UPDATE_ANCHOR = "InboxViewModelGenerator.calculateAndApplyDiffUpdate";
private static final String HEADER_ANCHOR = "PrebindThreadHeader";
private static final String THREAD_OPEN_ANCHOR = "igThreadIgid";

private static int threadHeaderId, backButtonId, tagKeyId;
public static volatile String currentOpenThreadId = null;

private static volatile Object generalFolderSingleton = null;
private static volatile WeakReference<Object> lastVmGeneratorRef = null;
private static volatile Method diffUpdateMethod = null;
private static volatile Field vmStateField = null;
private static volatile Field stateFolderField = null;
private static volatile List<Field> stateListFields = new ArrayList<>();
private static volatile Field sectionEnumField = null;
private static volatile Object lastInboxHeaderGenInstance = null;

private static final WeakHashMap<Activity, Boolean> registeredActivities = new WeakHashMap<>();
private static final WeakHashMap<View, Boolean> backTouchListeners = new WeakHashMap<>();
private static final WeakHashMap<View, int[]> savedBarParams = new WeakHashMap<>();

public void install(DexKitBridge bridge, ClassLoader classLoader) {
installFolderDescriptorHook(bridge, classLoader);
installInboxFilter(bridge, classLoader);
installInboxViewModelHooks(bridge, classLoader);
installThreadTracker(bridge, classLoader);
installHeaderButton(classLoader);
}

private void installFolderDescriptorHook(DexKitBridge bridge, ClassLoader classLoader) {
try {
List<ClassData> classes = bridge.findClass(FindClass.create()
.matcher(ClassMatcher.create().usingStrings("primary", "general", "requests_hidden_words", "school_channels")));
for (ClassData cd : classes) {
Class<?> folderBaseClass = cd.getInstance(classLoader);
if (folderBaseClass == null) continue;
for (Method m : folderBaseClass.getDeclaredMethods()) {
if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
&& m.getParameterTypes()[0] == String.class && m.getReturnType() == folderBaseClass) {
m.setAccessible(true);
generalFolderSingleton = m.invoke(null, "general");
break;
}
}
for (Method m : folderBaseClass.getDeclaredMethods()) {
if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() == boolean.class && m.getParameterCount() == 2) {
m.setAccessible(true);
XposedBridge.hookMethod(m, new XC_MethodHook() {
@Override
protected void beforeHookedMethod(MethodHookParam param) {
if (FeatureFlags.emptyGeneralFolder && isGeneralDescriptor(param.thisObject)) {
param.setResult(false);
}
}
});
}
}
}
} catch (Throwable ignored) {}
}

private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
XC_MethodHook filter = new XC_MethodHook() {
@Override
protected void afterHookedMethod(MethodHookParam param) {
FeatureFlags.hideSpecificChats = (FeatureFlags.hideChatsMode != 0);
Object r = param.getResult();
if (!(r instanceof List<?> originalList)) return;
if (FeatureFlags.emptyGeneralFolder && isGeneralFolderQuery(param.args)) {
param.setResult(new ArrayList<>());
return;
}
if (FeatureFlags.hideChatsMode == 0 || HiddenThreads.isEmpty()) return;
try {
List<Object> filtered = new ArrayList<>(originalList.size());
for (Object item : originalList) {
String id = threadIdOfRow(item);
if (id != null && HiddenThreads.isHidden(id)) continue;
filtered.add(item);
}
param.setResult(filtered);
} catch (Throwable ignored) {}
}
};
try {
int n = 0;
for (MethodData md : bridge.findMethod(FindMethod.create()
.matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)))) {
try {
XposedBridge.hookMethod(md.getMethodInstance(classLoader), filter);
n++;
} catch (Throwable ignored) {}
}
if (n > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
ModuleLog.line("(IE|HideChats) inbox filter hooked " + n + " method(s)");
} catch (Throwable t) {
ModuleLog.line("(IE|HideChats) ⚠️ inbox filter: " + t.getMessage());
}
}

private void installInboxViewModelHooks(DexKitBridge bridge, ClassLoader classLoader) {
try {
List<MethodData> diffMethods = bridge.findMethod(FindMethod.create()
.matcher(MethodMatcher.create().usingStrings(DIFF_UPDATE_ANCHOR)));
for (MethodData md : diffMethods) {
Method m = md.getMethodInstance(classLoader);
m.setAccessible(true);
diffUpdateMethod = m;
Class<?> vmGenClass = m.getDeclaringClass();
resolveViewModelStructures(bridge, classLoader, vmGenClass);

XposedBridge.hookMethod(m, new XC_MethodHook() {
@Override
protected void beforeHookedMethod(MethodHookParam param) {
FeatureFlags.hideSpecificChats = (FeatureFlags.hideChatsMode != 0);
Object vmGen = param.thisObject != null ? param.thisObject
: (param.args != null && param.args.length > 0 ? param.args[0] : null);
if (vmGen == null) return;
lastVmGeneratorRef = new WeakReference<>(vmGen);
try {
if (vmStateField == null) return;
Object state = vmStateField.get(vmGen);
if (state == null) return;
Object activeFolder = stateFolderField != null ? stateFolderField.get(state) : null;

if (FeatureFlags.emptyGeneralFolder && isGeneralDescriptor(activeFolder)) {
Object[] backup = new Object[stateListFields.size()];
for (int i = 0; i < stateListFields.size(); i++) {
Field lf = stateListFields.get(i);
backup[i] = lf.get(state);
lf.set(state, Collections.emptyList());
}
param.setObjectExtra("ie_list_backup", backup);
param.setObjectExtra("ie_state_obj", state);
return;
}

if (FeatureFlags.hideChatsMode != 0 && !HiddenThreads.isEmpty()) {
Object[] backup = new Object[stateListFields.size()];
boolean modified = false;
for (int i = 0; i < stateListFields.size(); i++) {
Field lf = stateListFields.get(i);
Object val = lf.get(state);
backup[i] = val;
if (val instanceof List<?> list && !list.isEmpty()) {
List<Object> filtered = new ArrayList<>(list.size());
boolean changed = false;
for (Object item : list) {
String tid = threadIdOfRow(item);
if (tid != null && HiddenThreads.isHidden(tid)) {
changed = true;
continue;
}
filtered.add(item);
}
if (changed) {
lf.set(state, filtered);
modified = true;
}
}
}
if (modified) {
param.setObjectExtra("ie_list_backup", backup);
param.setObjectExtra("ie_state_obj", state);
}
}
} catch (Throwable ignored) {}
}

@Override
protected void afterHookedMethod(MethodHookParam param) {
try {
Object[] backup = (Object[]) param.getObjectExtra("ie_list_backup");
Object state = param.getObjectExtra("ie_state_obj");
if (backup != null && state != null) {
for (int i = 0; i < stateListFields.size() && i < backup.length; i++) {
if (backup[i] != null) {
stateListFields.get(i).set(state, backup[i]);
}
}
}
} catch (Throwable ignored) {}
}
});
}
} catch (Throwable t) {
ModuleLog.line("(IE|HideChats) ⚠️ diff update hook: " + t.getMessage());
}
}

private void resolveViewModelStructures(DexKitBridge bridge, ClassLoader classLoader, Class<?> vmGenClass) {
try {
Class<?> stateClass = null;
Class<?> holdersClass = null;
for (Field f : vmGenClass.getDeclaredFields()) {
if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
Class<?> ft = f.getType();
if (!ft.getName().startsWith("X.")) continue;
int listCount = 0;
int totalFields = ft.getDeclaredFields().length;
for (Field sf : ft.getDeclaredFields()) {
if (List.class.isAssignableFrom(sf.getType())) listCount++;
}
if (listCount >= 3 && vmStateField == null) {
f.setAccessible(true);
vmStateField = f;
stateClass = ft;
} else if (totalFields >= 20 && holdersClass == null) {
f.setAccessible(true);
holdersClass = ft;
}
}

if (stateClass != null) {
List<Field> lFields = new ArrayList<>();
for (Field sf : stateClass.getDeclaredFields()) {
sf.setAccessible(true);
if (List.class.isAssignableFrom(sf.getType())) {
lFields.add(sf);
} else if (stateFolderField == null && sf.getType().getName().startsWith("X.")) {
for (Field ff : sf.getType().getDeclaredFields()) {
if (ff.getType() == Set.class) {
stateFolderField = sf;
break;
}
}
}
}
stateListFields = lFields;
}

if (holdersClass != null && stateClass != null) {
Method singleItemGenMethod = null;
for (Field hf : holdersClass.getDeclaredFields()) {
Class<?> genType = hf.getType();
if (!genType.getName().startsWith("X.")) continue;
if (singleItemGenMethod == null) {
for (Class<?> c = genType.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
for (Field ef : c.getDeclaredFields()) {
if (ef.getType().isEnum()) {
ef.setAccessible(true);
sectionEnumField = ef;
}
}
for (Method gm : c.getDeclaredMethods()) {
if (gm.getParameterCount() == 1 && gm.getParameterTypes()[0] == stateClass && gm.getReturnType() != void.class) {
gm.setAccessible(true);
singleItemGenMethod = gm;
}
}
}
}
if (genType.getName().contains("05Fv") || genType.getName().contains("C1316505Fv")) {
for (Field gf : genType.getDeclaredFields()) {
if (gf.getType().getName().contains("05Pu") || gf.getType().getName().contains("C1342405Pu")) {
gf.setAccessible(true);
headerFallbackItemField = gf;
}
}
}
}

if (singleItemGenMethod != null && sectionEnumField != null) {
XposedBridge.hookMethod(singleItemGenMethod, new XC_MethodHook() {
@Override
protected void afterHookedMethod(MethodHookParam param) {
try {
Object gen = param.thisObject;
if (gen == null) return;
Object enumVal = sectionEnumField.get(gen);
if (!(enumVal instanceof Enum<?> secEnum)) return;
String secName = secEnum.name();

if ("INBOX_HEADER".equals(secName)) {
lastInboxHeaderGenInstance = gen;
}

if (!FeatureFlags.hideDirectFoldersBar) return;

if ("FOLDER".equals(secName) || "FILTER_ROW".equals(secName)) {
param.setResult(null);
} else if ("INBOX_HEADER".equals(secName)) {
if (param.getResult() == null) {
Object headerItem = buildNativeInboxHeaderItem(gen);
if (headerItem != null) {
param.setResult(headerItem);
}
}
}
} catch (Throwable ignored) {}
}
});
ModuleLog.line("(IE|HideChats) hooked section generator A02 for HideFoldersBar & InboxHeader");
}
}
} catch (Throwable t) {
ModuleLog.line("(IE|HideChats) ⚠️ resolveViewModelStructures: " + t.getMessage());
}
}

private static Field headerFallbackItemField = null;
private static Object buildNativeInboxHeaderItem(Object headerGen) {
try {
if (headerGen != null && headerFallbackItemField != null) {
Object val = headerFallbackItemField.get(headerGen);
if (val != null) return val;
}
if (lastInboxHeaderGenInstance != null && headerFallbackItemField != null) {
Object val = headerFallbackItemField.get(lastInboxHeaderGenInstance);
if (val != null) return val;
}
} catch (Throwable ignored) {}
return null;
}

private static boolean isGeneralDescriptor(Object obj) {
if (obj == null) return false;
if (generalFolderSingleton != null && obj == generalFolderSingleton) return true;
String cn = obj.getClass().getName();
if (cn.endsWith(".0Gmm") || cn.contains("C425100Gmm")) return true;
return hasStringFieldValue(obj, "general");
}

private static boolean hasStringFieldValue(Object obj, String expected) {
if (obj == null) return false;
try {
for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
for (Field f : c.getDeclaredFields()) {
if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
f.setAccessible(true);
Object val = f.get(obj);
if (expected.equalsIgnoreCase((String) val)) return true;
}
}
} catch (Throwable ignored) {}
return false;
}

private static boolean isGeneralFolderQuery(Object[] args) {
if (args == null) return false;
for (Object arg : args) {
if (arg == null) continue;
if (isGeneralDescriptor(arg)) return true;
if (arg instanceof Collection<?> col) {
for (Object elem : col) {
if (isGeneralDescriptor(elem)) return true;
}
}
}
return false;
}
private void installThreadTracker(DexKitBridge bridge, ClassLoader classLoader) {
try {
List<MethodData> openMethods = bridge.findMethod(FindMethod.create()
.matcher(MethodMatcher.create()
.usingStrings(THREAD_OPEN_ANCHOR)
.paramTypes("com.instagram.model.direct.DirectThreadKey", "boolean")));
XC_MethodHook openHook = new XC_MethodHook() {
@Override
protected void beforeHookedMethod(MethodHookParam param) {
if (param.args.length > 0 && param.args[0] != null) {
String id = firstStringField(param.args[0]);
Object flag = param.args.length > 1 ? param.args[1] : Boolean.TRUE;
if (id != null && Boolean.TRUE.equals(flag)) {
currentOpenThreadId = id;
}
}
}
};
for (MethodData md : openMethods) {
try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), openHook); }
catch (Throwable ignored) {}
}
} catch (Throwable ignored) {}

try {
List<MethodData> headerMethods = bridge.findMethod(FindMethod.create()
.matcher(MethodMatcher.create().usingStrings(HEADER_ANCHOR)));
XC_MethodHook headerHook = new XC_MethodHook() {
@Override
protected void beforeHookedMethod(MethodHookParam param) {
if (param.args != null) {
for (Object arg : param.args) {
if (arg == null) continue;
if (arg.getClass().getName().startsWith("X.")) {
String id = extractThreadIdDeep(arg, 4);
if (id != null) {
currentOpenThreadId = id;
break;
}
}
}
}
}
};
for (MethodData md : headerMethods) {
try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), headerHook); }
catch (Throwable ignored) {}
}
} catch (Throwable ignored) {}
}

private static String threadIdOfRow(Object row) {
if (row == null) return null;
try {
if (row.getClass().getName().contains("DirectThreadKey")) {
return firstStringField(row);
}
Class<?> c = row.getClass();
while (c != null && c != Object.class) {
for (Field f : c.getDeclaredFields()) {
if (!f.getType().getName().contains("DirectThreadKey")) continue;
f.setAccessible(true);
Object key = f.get(row);
String id = firstStringField(key);
if (id != null) return id;
}
for (Method m : c.getDeclaredMethods()) {
if (m.getParameterCount() == 0 && m.getReturnType().getName().contains("DirectThreadKey")) {
m.setAccessible(true);
Object key = m.invoke(row);
String id = firstStringField(key);
if (id != null) return id;
}
}
c = c.getSuperclass();
}
} catch (Throwable ignored) {}
return null;
}

private static String extractThreadIdDeep(Object root, int maxDepth) {
Object found = scan(root, 0, Collections.newSetFromMap(new IdentityHashMap<>()), maxDepth);
if (found == null) return null;
if (found instanceof String s) return s;
return firstStringField(found);
}

public static void refreshActiveInbox(Activity activity) {
if (activity == null) return;
activity.runOnUiThread(() -> {
try {
tryInject(activity);
Object vmGen = lastVmGeneratorRef != null ? lastVmGeneratorRef.get() : null;
if (vmGen != null && diffUpdateMethod != null) {
try {
if (Modifier.isStatic(diffUpdateMethod.getModifiers())) {
diffUpdateMethod.invoke(null, vmGen);
} else {
diffUpdateMethod.invoke(vmGen);
}
} catch (Throwable ignored) {}
}
Method getSfm = activity.getClass().getMethod("getSupportFragmentManager");
Object fm = getSfm.invoke(activity);
if (fm != null) {
refreshFragmentsRecursive(fm);
}
walkDecorViewForRecyclerViews(activity.getWindow().getDecorView());
} catch (Throwable ignored) {}
});
}

private static void refreshFragmentsRecursive(Object fm) {
try {
Method getFrags = fm.getClass().getMethod("getFragments");
List<?> frags = (List<?>) getFrags.invoke(fm);
if (frags == null) return;
for (Object frag : frags) {
if (frag == null || frag.getClass().getName().contains("Dialog")) continue;
try {
Method isVis = frag.getClass().getMethod("isVisible");
if (Boolean.TRUE.equals(isVis.invoke(frag))) {
Method onP = frag.getClass().getMethod("onPause");
Method onR = frag.getClass().getMethod("onResume");
onP.invoke(frag);
onR.invoke(frag);
}
} catch (Throwable ignored) {}
try {
Method getChildFm = frag.getClass().getMethod("getChildFragmentManager");
Object childFm = getChildFm.invoke(frag);
if (childFm != null) refreshFragmentsRecursive(childFm);
} catch (Throwable ignored) {}
}
} catch (Throwable ignored) {}
}

private static void walkDecorViewForRecyclerViews(View v) {
if (v instanceof ViewGroup vg) {
if (v.getClass().getName().contains("RecyclerView")) {
try {
Method getAdapter = v.getClass().getMethod("getAdapter");
Object adapter = getAdapter.invoke(v);
if (adapter != null) {
Method notifyDS = adapter.getClass().getMethod("notifyDataSetChanged");
notifyDS.invoke(adapter);
}
} catch (Throwable ignored) {}
}
for (int i = 0; i < vg.getChildCount(); i++) {
walkDecorViewForRecyclerViews(vg.getChildAt(i));
}
}
}

private void installHeaderButton(ClassLoader classLoader) {
XC_MethodHook resume = new XC_MethodHook() {
@Override
protected void afterHookedMethod(MethodHookParam param) {
FeatureFlags.hideSpecificChats = (FeatureFlags.hideChatsMode != 0);
final Activity a = (Activity) param.thisObject;
a.runOnUiThread(() -> registerListener(a));
}
};
for (String act : new String[]{"com.instagram.modal.ModalActivity", "com.instagram.mainactivity.InstagramMainActivity"}) {
try { XposedHelpers.findAndHookMethod(act, classLoader, "onResume", resume); }
catch (Throwable ignored) {}
}
ModuleLog.line("(IE|HideChats) ✅ installed");
}

@SuppressLint("DiscouragedApi")
private static void ensureIds(Activity a) {
if (threadHeaderId != 0) return;
String pkg = a.getPackageName();
android.content.res.Resources r = a.getResources();
threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
backButtonId = r.getIdentifier("header_left_button", "id", pkg);
if (backButtonId == 0) {
backButtonId = r.getIdentifier("action_bar_button_back", "id", pkg);
}
tagKeyId = r.getIdentifier("direct_thread_view_layout_tag_key", "id", pkg);
}

private void registerListener(final Activity activity) {
try {
ensureIds(activity);
tryInject(activity);
if (registeredActivities.containsKey(activity)) return;
registeredActivities.put(activity, true);
final View decor = activity.getWindow().getDecorView();
decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
@Override
public void onGlobalLayout() {
tryInject(activity);
}
});
} catch (Throwable ignored) {}
}

private static boolean tryInject(Activity activity) {
try {
View header = threadHeaderId != 0 ? activity.findViewById(threadHeaderId) : null;
if (header == null) return false;

ViewGroup target;
int insertAt;
View back = backButtonId != 0 ? activity.findViewById(backButtonId) : null;

if (back != null && !backTouchListeners.containsKey(back)) {
backTouchListeners.put(back, true);
back.setOnTouchListener(new View.OnTouchListener() {
private final Handler handler = new Handler(Looper.getMainLooper());
private final boolean[] triggered = {false};
private float startX, startY;
private final Runnable longPressRunnable = () -> {
triggered[0] = true;
back.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
String threadId = resolveThreadId(activity, header, currentOpenThreadId);
if (threadId == null) {
Toast.makeText(activity, I18n(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
return;
}
boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
Toast.makeText(activity, I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden), Toast.LENGTH_SHORT).show();
refreshActiveInbox(activity);
};

@SuppressLint("ClickableViewAccessibility")
@Override
public boolean onTouch(View v, MotionEvent event) {
if (FeatureFlags.hideChatsMode != 2) {
handler.removeCallbacks(longPressRunnable);
return false;
}
int action = event.getAction();
int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
switch (action) {
case MotionEvent.ACTION_DOWN:
triggered[0] = false;
startX = event.getX();
startY = event.getY();
long sec = Math.max(1, FeatureFlags.hideChatsBackLongPressSeconds);
handler.postDelayed(longPressRunnable, sec * 1000L);
return false;
case MotionEvent.ACTION_MOVE:
if (Math.abs(event.getX() - startX) > slop || Math.abs(event.getY() - startY) > slop) {
handler.removeCallbacks(longPressRunnable);
}
break;
case MotionEvent.ACTION_UP:
handler.removeCallbacks(longPressRunnable);
if (triggered[0]) {
v.setPressed(false);
return true;
}
break;
case MotionEvent.ACTION_CANCEL:
handler.removeCallbacks(longPressRunnable);
triggered[0] = false;
break;
}
return false;
}
});
}

if (back != null && back.getParent() instanceof ViewGroup) {
target = (ViewGroup) back.getParent();
int bi = target.indexOfChild(back);
boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
insertAt = rtl ? bi : bi + 1;
} else if (header instanceof ViewGroup) {
target = (ViewGroup) header;
insertAt = 0;
} else return false;

View existingEye = target.findViewWithTag(TAG);
if (FeatureFlags.hideChatsMode == 1) {
if (existingEye == null) {
android.widget.ImageView btn = new android.widget.ImageView(activity);
btn.setTag(TAG);
android.graphics.drawable.Drawable icon = ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_eye_off, Color.WHITE);
if (icon != null) btn.setImageDrawable(icon);
btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
int pad = dp(activity, 9);
btn.setPadding(pad, pad, pad, pad);
btn.setClickable(true);
btn.setFocusable(true);
btn.setContentDescription("Hide chat");
int sz = dp(activity, 40);
LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
lp.gravity = Gravity.CENTER_VERTICAL;
btn.setLayoutParams(lp);

final View headerRoot = header;
final String boundAtInject = currentOpenThreadId != null ? currentOpenThreadId : KeepUnsentMessagesHook.currentThreadId;

btn.setOnClickListener(v -> {
String threadId = resolveThreadId(activity, headerRoot, boundAtInject);
if (threadId == null) {
Toast.makeText(activity, I18n(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
return;
}
boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
Toast.makeText(activity, I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden), Toast.LENGTH_SHORT).show();
refreshActiveInbox(activity);
});
try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
catch (Throwable t) { target.addView(btn); }
} else {
existingEye.setVisibility(View.VISIBLE);
}
} else {
if (existingEye != null) existingEye.setVisibility(View.GONE);
}
return true;
} catch (Throwable t) {
return false;
}
}

private static String I18n(Activity a, int res) {
return ps.reso.instaeclipse.utils.i18n.I18n.t(a, res);
}

private static String threadTitle(Activity a) {
try {
View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
if (!(header instanceof ViewGroup)) return "";
List<String> texts = new ArrayList<>();
collectTexts((ViewGroup) header, texts);
for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t;
return texts.isEmpty() ? "" : texts.get(0);
} catch (Throwable t) { return ""; }
}

private static void collectTexts(ViewGroup vg, List<String> out) {
for (int i = 0; i < vg.getChildCount() && out.size() < 12; i++) {
View c = vg.getChildAt(i);
if (c instanceof android.widget.TextView tv) {
CharSequence cs = tv.getText();
if (cs != null && !cs.toString().trim().isEmpty()) out.add(cs.toString().trim());
} else if (c instanceof ViewGroup) {
collectTexts((ViewGroup) c, out);
}
}
}

private static String resolveThreadId(Activity activity, View headerRoot, String boundAtInject) {
if (currentOpenThreadId != null && !currentOpenThreadId.isEmpty()) return currentOpenThreadId;
if (KeepUnsentMessagesHook.currentThreadId != null && !KeepUnsentMessagesHook.currentThreadId.isEmpty()) {
return KeepUnsentMessagesHook.currentThreadId;
}
if (boundAtInject != null && !boundAtInject.isEmpty()) return boundAtInject;

try {
Bundle ex = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
if (ex != null) {
Object dtk = scan(ex, 0, Collections.newSetFromMap(new IdentityHashMap<>()), 5);
if (dtk != null) {
String id = (dtk instanceof String s) ? s : firstStringField(dtk);
if (id != null) return id;
}
}

View v = headerRoot;
int up = 0;
while (v != null && up++ < 8) {
if (tagKeyId != 0) {
Object t = v.getTag(tagKeyId);
if (t != null) {
String id = extractThreadIdDeep(t, 3);
if (id != null) return id;
}
}
Object plain = v.getTag();
if (plain != null) {
String id = extractThreadIdDeep(plain, 3);
if (id != null) return id;
}
v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
}

try {
Method getSfm = activity.getClass().getMethod("getSupportFragmentManager");
Object fm = getSfm.invoke(activity);
if (fm != null) {
Method getFrags = fm.getClass().getMethod("getFragments");
List<?> frags = (List<?>) getFrags.invoke(fm);
if (frags != null) {
for (Object frag : frags) {
if (frag == null) continue;
String id = extractThreadIdDeep(frag, 5);
if (id != null) return id;
}
}
}
} catch (Throwable ignored) {}

Object dtk = scan(activity, 0, Collections.newSetFromMap(new IdentityHashMap<>()), 6);
if (dtk != null) {
return (dtk instanceof String s) ? s : firstStringField(dtk);
}
} catch (Throwable ignored) {}
return null;
}

private static Object scan(Object obj, int depth, Set<Object> seen, int maxDepth) {
if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
String cn = obj.getClass().getName();
if (cn.contains("DirectThreadKey") || cn.contains("ThreadIdParcelable") || cn.contains("UnifiedThreadKeyParcelable")) {
return obj;
}

if (cn.startsWith("X.")) {
String rowId = threadIdOfRow(obj);
if (rowId != null) return rowId;
}

if (obj instanceof Bundle b) {
try {
for (String k : b.keySet()) {
Object val = b.get(k);
if (val instanceof String s && (k.toLowerCase().contains("thread_id") || k.toLowerCase().contains("threadid"))) {
if (!s.isEmpty()) return s;
}
Object r = scan(val, depth + 1, seen, maxDepth);
if (r != null) return r;
}
} catch (Throwable ignored) {}
return null;
}

boolean descendable = cn.startsWith("X.") || cn.startsWith("com.instagram.") || cn.startsWith("androidx.fragment.");
if (!descendable) return null;

try {
for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
for (Field f : c.getDeclaredFields()) {
if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
f.setAccessible(true);
Object v;
try { v = f.get(obj); } catch (Throwable e) { continue; }
if (v == null) continue;
String vcn = v.getClass().getName();
if (vcn.contains("DirectThreadKey") || vcn.contains("ThreadIdParcelable")) return v;
Object r = scan(v, depth + 1, seen, maxDepth);
if (r != null) return r;
}
}
} catch (Throwable ignored) {}
return null;
}

private static String firstStringField(Object o) {
if (o == null) return null;
try {
for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
for (Field f : c.getDeclaredFields()) {
if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
f.setAccessible(true);
Object v = f.get(o);
if (v instanceof String s && !s.isEmpty()) return s;
}
}
} catch (Throwable ignored) {}
return null;
}

private static int dp(Activity a, int v) {
return Math.round(v * a.getResources().getDisplayMetrics().density);
}
}
"""

with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "w") as f:
    f.write(content)
