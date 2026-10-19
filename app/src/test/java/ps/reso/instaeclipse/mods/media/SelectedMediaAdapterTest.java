package ps.reso.instaeclipse.mods.media;

import org.junit.Test;
import org.luckypray.dexkit.wrap.DexMethod;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import static org.junit.Assert.*;

public class SelectedMediaAdapterTest {
    static final class SchemaFixture {
        static final String[] KEYS = {"video_dash_manifest", "video_versions"};
        public String manifest() { return "fixture"; }
        public static String misleadingStatic() { return "fixture"; }
    }

    @Test public void schemaInitializerIsRejectedBeforeDexKitReflection() throws Throwable {
        ClassLoader loader = SchemaFixture.class.getClassLoader();
        DexMethod initializer = new DexMethod("L" + SchemaFixture.class.getName().replace('.', '/') + ";-><clinit>()V");
        assertNull(SelectedMediaAdapter.getterCandidate("video_dash_manifest", "<clinit>", "void", 0,
                Modifier.STATIC, () -> initializer.getMethodInstance(loader)));
        Method getter = SchemaFixture.class.getDeclaredMethod("manifest");
        assertSame(getter, SelectedMediaAdapter.getterCandidate("video_dash_manifest", "manifest", "java.lang.String",
                0, Modifier.PUBLIC, () -> getter));
    }

    @Test public void staticHelperAndWrongReturnNeverBindAsInstanceGetter() throws Throwable {
        Method helper = SchemaFixture.class.getDeclaredMethod("misleadingStatic");
        assertNull(SelectedMediaAdapter.getterCandidate("video_dash_manifest", helper.getName(), "java.lang.String",
                0, helper.getModifiers(), () -> helper));
        Method getter = SchemaFixture.class.getDeclaredMethod("manifest");
        assertNull(SelectedMediaAdapter.getterCandidate("video_versions", getter.getName(), "java.lang.String",
                0, getter.getModifiers(), () -> getter));
    }
    static final class Media { }
    static class MenuCreator {
        Media selected, alias;
        Object controller;
        static Media global;
        MenuCreator(Media selected) { this.selected = selected; }
    }
    static class PostHandler {
        MenuCreator menu, alias;
        Object unrelated;
        static MenuCreator global;
        PostHandler(MenuCreator menu, Object unrelated) { this.menu = menu; this.unrelated = unrelated; }
    }
    static final class ChildPostHandler extends PostHandler {
        ChildPostHandler(MenuCreator menu, Object unrelated) { super(menu, unrelated); }
    }

    @Test public void postMenuOwnerResolvesDespiteOtherPostsInHandlerGraph() {
        Media selected = new Media();
        MenuCreator creator = new MenuCreator(selected);
        creator.controller = new Holder(new Media(), null);
        PostHandler handler = new ChildPostHandler(creator, new Holder(new Media(), null));
        assertNull(SelectedMediaAdapter.uniqueObject(handler, Media.class, 4));
        assertSame(selected, SelectedMediaAdapter.ownedMenuMedia(handler, MenuCreator.class, Media.class));
    }

    @Test public void postMenuOwnerIgnoresLargeControllerGraphsAndStaticReferences() {
        Media selected = new Media();
        MenuCreator creator = new MenuCreator(selected);
        PostHandler handler = new PostHandler(creator, tree(8));
        PostHandler.global = new MenuCreator(new Media());
        MenuCreator.global = new Media();
        try {
            assertSame(selected, SelectedMediaAdapter.ownedMenuMedia(handler, MenuCreator.class, Media.class));
            handler.menu = null;
            assertNull(SelectedMediaAdapter.ownedMenuMedia(handler, MenuCreator.class, Media.class));
        } finally { PostHandler.global = null; MenuCreator.global = null; }
    }

    @Test public void postMenuRejectsDifferentOwnersEvenIfTheyReferenceSameMedia() {
        Media selected = new Media();
        PostHandler handler = new PostHandler(new MenuCreator(selected), null);
        handler.alias = handler.menu;
        handler.menu.alias = selected;
        assertSame(selected, SelectedMediaAdapter.ownedMenuMedia(handler, MenuCreator.class, Media.class));
        handler.alias = new MenuCreator(selected);
        assertNull(SelectedMediaAdapter.ownedMenuMedia(handler, MenuCreator.class, Media.class));
        handler.alias = handler.menu;
        handler.menu.alias = new Media();
        assertNull(SelectedMediaAdapter.ownedMenuMedia(handler, MenuCreator.class, Media.class));
    }

    @Test public void postMenuDoesNotSubstituteDeepOrUntypedOwnersOrMedia() {
        Media unrelated = new Media();
        PostHandler handler = new PostHandler(new MenuCreator(null), new MenuCreator(unrelated));
        handler.menu.controller = new Holder(unrelated, null);
        assertNull(SelectedMediaAdapter.ownedMenuMedia(handler, MenuCreator.class, Media.class));
        assertNull(SelectedMediaAdapter.ownedMenuMedia(new Holder(new MenuCreator(unrelated), null), MenuCreator.class, Media.class));
    }

    @Test public void reelDispatcherUsesDirectBoundMediaAndIgnoresControllerGraphs() {
        Media selected = new Media();
        MenuCreator dispatcher = new MenuCreator(selected);
        dispatcher.controller = new Holder(new Media(), tree(8));
        assertNull(SelectedMediaAdapter.uniqueObject(dispatcher, Media.class, 4));
        assertSame(selected, SelectedMediaAdapter.ownedDirectMedia(dispatcher, MenuCreator.class, Media.class));
        dispatcher.alias = selected;
        assertSame(selected, SelectedMediaAdapter.ownedDirectMedia(dispatcher, MenuCreator.class, Media.class));
        dispatcher.alias = new Media();
        assertNull(SelectedMediaAdapter.ownedDirectMedia(dispatcher, MenuCreator.class, Media.class));
    }

    @Test public void reelDispatcherRequiresExpectedOwnerAndCurrentNonNullMedia() {
        MenuCreator dispatcher = new MenuCreator(new Media());
        assertNull(SelectedMediaAdapter.ownedDirectMedia(dispatcher, PostHandler.class, Media.class));
        assertNull(SelectedMediaAdapter.ownedDirectMedia(dispatcher, null, Media.class));
        assertNull(SelectedMediaAdapter.ownedDirectMedia(null, MenuCreator.class, Media.class));
        dispatcher.selected = null;
        dispatcher.controller = new Holder(new Media(), null);
        MenuCreator.global = new Media();
        try { assertNull(SelectedMediaAdapter.ownedDirectMedia(dispatcher, MenuCreator.class, Media.class)); }
        finally { MenuCreator.global = null; }
    }
    static final class Holder {
        Object first, second;
        static Object global;
        int methodCalls;
        Holder(Object first, Object second) { this.first = first; this.second = second; }
        public Media guessMedia() { methodCalls++; return new Media(); }
    }
    static final class StoryDispatcher {
        Media selected, second;
        Object controller;
        static Media global;
        StoryDispatcher(Media selected, Object controller) { this.selected = selected; this.controller = controller; }
        public static void dispatch(Object listener, StoryDispatcher owner, CharSequence label) { }
        public static void twoOwners(StoryDispatcher first, StoryDispatcher second) { }
        public static void explicit(Media selected, Media alias, Object listener) { }
        public void instance(Object listener) { }
    }

    @Test public void staticStoryDispatcherIgnoresUnrelatedDeepAndDirectItems() throws Exception {
        Media selected = new Media();
        StoryDispatcher owner = new StoryDispatcher(selected, new Holder(new Media(), null));
        StoryDispatcher unrelated = new StoryDispatcher(new Media(), null);
        Object[] args = {unrelated, owner, "Download"};
        assertNull(SelectedMediaAdapter.uniqueAcross(args, Media.class, 4));
        Method method = StoryDispatcher.class.getDeclaredMethod("dispatch", Object.class, StoryDispatcher.class, CharSequence.class);
        assertSame(selected, SelectedMediaAdapter.ownedStoryItem(method, null, args, Media.class));
        owner.selected = null;
        assertNull(SelectedMediaAdapter.ownedStoryItem(method, null, args, Media.class));
    }

    @Test public void instanceStoryDispatcherUsesReceiverAndRejectsAmbiguousDirectFields() throws Exception {
        Media selected = new Media();
        StoryDispatcher owner = new StoryDispatcher(selected, new Holder(new Media(), null));
        Method method = StoryDispatcher.class.getDeclaredMethod("instance", Object.class);
        Object[] args = {new StoryDispatcher(new Media(), null)};
        StoryDispatcher.global = new Media();
        try {
            assertSame(selected, SelectedMediaAdapter.ownedStoryItem(method, owner, args, Media.class));
            owner.second = selected;
            assertSame(selected, SelectedMediaAdapter.ownedStoryItem(method, owner, args, Media.class));
            owner.second = new Media();
            assertNull(SelectedMediaAdapter.ownedStoryItem(method, owner, args, Media.class));
            assertNull(SelectedMediaAdapter.ownedStoryItem(method, null, args, Media.class));
        } finally { StoryDispatcher.global = null; }
    }

    @Test public void storyDispatcherRequiresUniqueOwnerAndDeduplicatesOwnerAliases() throws Exception {
        StoryDispatcher owner = new StoryDispatcher(new Media(), null);
        Method method = StoryDispatcher.class.getDeclaredMethod("twoOwners", StoryDispatcher.class, StoryDispatcher.class);
        assertSame(owner.selected, SelectedMediaAdapter.ownedStoryItem(method, null, new Object[]{owner, owner}, Media.class));
        assertNull(SelectedMediaAdapter.ownedStoryItem(method, null,
                new Object[]{owner, new StoryDispatcher(new Media(), null)}, Media.class));
    }

    @Test public void explicitTypedStoryArgumentsRequireOneIdentity() throws Exception {
        Media selected = new Media();
        Method method = StoryDispatcher.class.getDeclaredMethod("explicit", Media.class, Media.class, Object.class);
        Object unrelated = new StoryDispatcher(new Media(), null);
        assertSame(selected, SelectedMediaAdapter.ownedStoryItem(method, null,
                new Object[]{selected, selected, unrelated}, Media.class));
        assertNull(SelectedMediaAdapter.ownedStoryItem(method, null,
                new Object[]{selected, new Media(), unrelated}, Media.class));
        assertNull(SelectedMediaAdapter.ownedStoryItem(method, null,
                new Object[]{null, null, unrelated}, Media.class));
    }

    @Test public void exactObjectAndAliasedFieldsResolveOnceWithoutMethodProbes() {
        Media media = new Media();
        Holder holder = new Holder(media, media);
        assertSame(media, SelectedMediaAdapter.uniqueObject(holder, Media.class, 4));
        assertSame(media, SelectedMediaAdapter.uniqueObject(media, Media.class, 4));
        assertEquals(0, holder.methodCalls);
    }
    @Test public void differentCapturedItemsFailClosedAndStaticBufferIsIgnored() {
        assertNull(SelectedMediaAdapter.uniqueObject(new Holder(new Media(), new Media()), Media.class, 4));
        Holder.global = new Media();
        assertNull(SelectedMediaAdapter.uniqueObject(new Holder(null, null), Media.class, 4));
        Holder.global = null;
    }
    @Test public void cyclesTerminateWithoutHidingASecondItem() {
        Holder cyclic = new Holder(new Media(), null);
        cyclic.second = cyclic;
        assertSame(cyclic.first, SelectedMediaAdapter.uniqueObject(cyclic, Media.class, 4));
        Holder other = new Holder(cyclic, new Media());
        assertNull(SelectedMediaAdapter.uniqueObject(other, Media.class, 4));
    }
    @Test public void shallowAliasIsExpandedEvenWhenLongerPathAppearsFirst() {
        Holder shared = new Holder(new Media(), null);
        Holder longPath = new Holder(new Holder(shared, null), new Media());
        Holder root = new Holder(longPath, shared);
        assertNull(SelectedMediaAdapter.uniqueObject(root, Media.class, 3));
    }
    @Test public void allTapRootsShareOneUniquenessCheck() {
        Media selected = new Media();
        assertSame(selected, SelectedMediaAdapter.uniqueAcross(new Object[]{selected, new Holder(selected, null)}, Media.class, 4));
        assertNull(SelectedMediaAdapter.uniqueAcross(new Object[]{selected, new Holder(new Media(), null)}, Media.class, 4));
    }
    @Test public void exceededTraversalBudgetNeverAcceptsPartialResult() {
        Holder tree = tree(8);
        tree.first = new Media();
        assertNull(SelectedMediaAdapter.uniqueObject(tree, Media.class, 10));
    }
    private static Holder tree(int depth) {
        return depth == 0 ? new Holder(null, null) : new Holder(tree(depth - 1), tree(depth - 1));
    }
}
