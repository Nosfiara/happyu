package com.happyu;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.xmlb.XmlSerializer;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.network.CefRequest;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class HappyuToolWindowFactoryTest extends LightPlatformTestCase {
    private ContentManager manager;
    private final List<AnAction> titleActions = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Registry.get("ide.browser.jcef.enabled").setValue(false, getTestRootDisposable());
        getProject().getService(HappyuTabsState.class).loadState(new HappyuTabsState.Snapshot());
        createWindow();
    }

    private void createWindow() {
        manager = ContentFactory.getInstance().createContentManager(true, getProject());
        Disposer.register(getTestRootDisposable(), manager);
        ToolWindow window = (ToolWindow) Proxy.newProxyInstance(ToolWindow.class.getClassLoader(),
                new Class<?>[]{ToolWindow.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getContentManager" -> manager;
                    case "setTitleActions" -> {
                        for (Object action : (List<?>) args[0]) titleActions.add((AnAction) action);
                        yield null;
                    }
                    case "setContentUiType", "setToHideOnEmptyContent" -> null;
                    default -> throw new AssertionError("Unexpected tool window call: " + method.getName());
                });
        new HappyuToolWindowFactory().createToolWindowContent(getProject(), window);
    }

    public void testNewTabsHaveIndependentBrowserPanelsAndImageSwitches() {
        Content first = manager.getSelectedContent();
        assertNotNull(first);
        assertEquals(1, manager.getContentCount());
        assertTrue(first.isCloseable());

        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        Content second = manager.getSelectedContent();
        assertNotNull(second);
        assertEquals(2, manager.getContentCount());
        assertNotSame(first.getDisposer(), second.getDisposer());

        HappyuBrowserPanel secondPanel = (HappyuBrowserPanel) second.getDisposer();
        assertNotNull(secondPanel);
        secondPanel.toggleImages();
        assertTrue(hasButton(first.getComponent(), "Back"));
        assertTrue(hasButton(first.getComponent(), "Forward"));
        assertTrue(hasButton(first.getComponent(), "Reload"));
        assertTrue(hasButton(first.getComponent(), "Images Off"));
        assertTrue(hasButton(second.getComponent(), "Images On"));
    }

    public void testClosingAllTabsDisposesPanelsAndNewTabStillWorks() {
        Content first = manager.getSelectedContent();
        assertNotNull(first);
        HappyuBrowserPanel panel = (HappyuBrowserPanel) first.getDisposer();
        assertNotNull(panel);
        assertTrue(manager.removeContent(first, true));
        assertEquals(0, manager.getContentCount());
        assertTrue(Disposer.isDisposed(panel));
        assertTrue(savedTabs().tabs.isEmpty());

        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        assertEquals(1, manager.getContentCount());
        assertNotSame(panel, manager.getSelectedContent().getDisposer());
    }

    public void testRestoresSerializedTabsAndStartsOnlySelectedBrowser() {
        HappyuTabsState.Snapshot state = new HappyuTabsState.Snapshot();
        state.initialized = true;
        state.tabs = List.of(new HappyuTabsState.Tab("https://example.com/?q=a&b=c#123", "First", false),
                new HappyuTabsState.Tab("https://other.example/path", "第二页", true),
                new HappyuTabsState.Tab("https://last.example/", "Last", false));
        state.selectedIndex = 1;
        // Exercise the actual XML bean serializer used by PersistentStateComponent.
        restore(XmlSerializer.deserialize(XmlSerializer.serialize(state), HappyuTabsState.Snapshot.class));
        assertEquals(3, manager.getContentCount());
        assertEquals(manager.getContent(1), manager.getSelectedContent());
        assertEquals("First", manager.getContent(0).getDisplayName());
        assertEquals("第二页", manager.getContent(1).getDisplayName());
        assertEquals(state.tabs.get(0).url, panel(0).getCurrentUrl());
        assertFalse(panel(0).isImagesBlocked());
        assertTrue(panel(1).isImagesBlocked());
        assertFalse(panel(0).isBrowserStarted());
        assertTrue(panel(1).isBrowserStarted());
        assertFalse(panel(2).isBrowserStarted());
        manager.setSelectedContent(manager.getContent(2));
        assertTrue(panel(2).isBrowserStarted());
        assertFalse(panel(0).isBrowserStarted());
        assertEquals(2, savedTabs().selectedIndex);
    }

    public void testSavesNavigationImageStateAndRemovedTabs() throws Exception {
        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        HappyuBrowserPanel selected = panel(1);
        selected.toggleImages();
        // Simulate a URL delivered by a main-frame navigation callback, on the EDT.
        var recordUrl = HappyuBrowserPanel.class.getDeclaredMethod("recordUrl", String.class);
        recordUrl.setAccessible(true);
        recordUrl.invoke(selected, "https://example.com/new?q=C%2B%2B#123");
        assertEquals("https://example.com/new?q=C%2B%2B#123", savedTabs().tabs.get(1).url);
        assertFalse(savedTabs().tabs.get(1).imagesBlocked);
        assertEquals(1, savedTabs().selectedIndex);
        recordUrl.invoke(selected, "about:blank");
        assertEquals("https://example.com/new?q=C%2B%2B#123", savedTabs().tabs.get(1).url);
        assertTrue(manager.removeContent(manager.getContent(0), true));
        assertEquals(1, savedTabs().tabs.size());
        assertEquals(selected.getCurrentUrl(), savedTabs().tabs.get(0).url);
        assertEquals(0, savedTabs().selectedIndex);
    }

    public void testDisposingWindowDoesNotEraseSavedTabs() {
        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        HappyuTabsState.Snapshot before = savedTabs();
        Disposer.dispose(manager);
        assertEquals(before.tabs.size(), savedTabs().tabs.size());
        assertEquals(before.selectedIndex, savedTabs().selectedIndex);
        titleActions.clear();
        createWindow();
        assertEquals(before.tabs.size(), manager.getContentCount());
        assertEquals(before.selectedIndex, manager.getIndexOfContent(manager.getSelectedContent()));
    }

    public void testProjectShutdownRemovalsDoNotReplaceSavedTabs() {
        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        ApplicationManager.getApplication().getMessageBus().syncPublisher(ProjectManager.TOPIC)
                .projectClosing(getProject());
        manager.removeAllContents(true);
        assertEquals(2, savedTabs().tabs.size());
    }

    public void testCancelledProjectCloseStillSavesTabChanges() {
        // A failed save can cancel closing after this notification, without projectClosing.
        var publisher = ApplicationManager.getApplication().getMessageBus().syncPublisher(ProjectManager.TOPIC);
        publisher.projectClosingBeforeSave(getProject());
        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        assertEquals(2, manager.getContentCount());
        assertEquals(2, savedTabs().tabs.size());
        assertEquals(1, savedTabs().selectedIndex);
        panel(1).toggleImages();
        assertFalse(savedTabs().tabs.get(1).imagesBlocked);
        assertTrue(manager.removeContent(manager.getContent(0), true));
        assertEquals(1, savedTabs().tabs.size());
        assertFalse(savedTabs().tabs.get(0).imagesBlocked);
        publisher.projectClosingBeforeSave(getProject());
        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        assertEquals(2, savedTabs().tabs.size());
    }

    public void testExplicitlyClosedAllTabsRestoreEmptyAndInvalidSelectionIsClamped() {
        manager.removeAllContents(true);
        restore(savedTabs());
        assertEquals(0, manager.getContentCount());
        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        assertEquals(1, manager.getContentCount());
        HappyuTabsState.Snapshot state = savedTabs();
        state.selectedIndex = 999;
        restore(state);
        assertEquals(manager.getContent(0), manager.getSelectedContent());
    }

    private HappyuTabsState.Snapshot savedTabs() {
        return getProject().getService(HappyuTabsState.class).getState();
    }

    private HappyuBrowserPanel panel(int index) {
        return (HappyuBrowserPanel) manager.getContent(index).getDisposer();
    }

    private void restore(HappyuTabsState.Snapshot state) {
        Disposer.dispose(manager);
        getProject().getService(HappyuTabsState.class).loadState(state);
        titleActions.clear();
        createWindow();
    }

    public void testOnlyBlockedMediaRequestsGetResourceCallbacks() {
        HappyuBrowserPanel panel = (HappyuBrowserPanel) manager.getSelectedContent().getDisposer();
        assertNotNull(panel);
        assertNull(panel.resourceRequestHandlerFor(CefRequest.ResourceType.RT_SCRIPT, "https://example.com/app.js"));
        assertNull(panel.resourceRequestHandlerFor(CefRequest.ResourceType.RT_STYLESHEET, "https://example.com/app.css"));
        assertNull(panel.resourceRequestHandlerFor(CefRequest.ResourceType.RT_MAIN_FRAME, "https://example.com/"));
        CefResourceRequestHandler blocker = panel.resourceRequestHandlerFor(
                CefRequest.ResourceType.RT_IMAGE, "https://example.com/image");
        assertNotNull(blocker);
        assertSame(blocker, panel.resourceRequestHandlerFor(CefRequest.ResourceType.RT_MEDIA, null));
        assertSame(blocker, panel.resourceRequestHandlerFor(
                CefRequest.ResourceType.RT_MAIN_FRAME, "https://example.com/image.PNG?size=2"));

        panel.toggleImages();
        assertNull(panel.resourceRequestHandlerFor(CefRequest.ResourceType.RT_IMAGE, "https://example.com/image"));
        assertFalse(blocker.onBeforeResourceLoad(null, null, null));
    }

    private static boolean hasButton(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && name.equals(button.getAccessibleContext().getAccessibleName())) {
                assertNotNull(button.getIcon());
                assertEquals(16, button.getIcon().getIconWidth());
                assertTrue(button.getText() == null || button.getText().isEmpty());
                return true;
            }
            if (child instanceof Container container && hasButton(container, name)) return true;
        }
        return false;
    }
}
