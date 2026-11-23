package com.happyu;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
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
        manager = ContentFactory.getInstance().createContentManager(true, getProject());
        Disposer.register(getTestRootDisposable(), manager);
        ToolWindow window = (ToolWindow) Proxy.newProxyInstance(ToolWindow.class.getClassLoader(),
                new Class<?>[]{ToolWindow.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getContentManager": return manager;
                        case "setTitleActions":
                            for (Object action : (List<?>) args[0]) titleActions.add((AnAction) action);
                            return null;
                        case "setContentUiType", "setToHideOnEmptyContent": return null;
                        default: throw new AssertionError("Unexpected tool window call: " + method.getName());
                    }
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

        titleActions.get(0).actionPerformed(TestActionEvent.createTestEvent());
        assertEquals(1, manager.getContentCount());
        assertNotSame(panel, manager.getSelectedContent().getDisposer());
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

    private static boolean hasButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return true;
            if (child instanceof Container container && hasButton(container, text)) return true;
        }
        return false;
    }
}
