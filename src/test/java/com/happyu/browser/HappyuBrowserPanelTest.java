package com.happyu.browser;

import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.testFramework.LightPlatformTestCase;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.network.CefRequest;
import org.cef.browser.CefBrowser;
import com.intellij.util.ui.UIUtil;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

public final class HappyuBrowserPanelTest extends LightPlatformTestCase {
    private HappyuBrowserPanel createPanel(boolean sleeping) {
        Registry.get("ide.browser.jcef.enabled").setValue(false, getTestRootDisposable());
        HappyuBrowserPanel panel = new HappyuBrowserPanel(getProject(), "https://example.com/last", true, sleeping,
                url -> { }, title -> { }, () -> { });
        Disposer.register(getTestRootDisposable(), panel);
        return panel;
    }

    public void testSleepCancelsPendingScriptWorkAndPanelCanWakeRepeatedly() throws Exception {
        HappyuBrowserPanel panel = createPanel(false);
        panel.ensureBrowserCreated();
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        CefBrowser browser = (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class}, (proxy, method, args) -> {
                    throw new AssertionError("Script touched a released browser: " + method.getName());
                });
        HappyuCefScripts scripts = new HappyuCefScripts(browser, () -> "theme", workers::add);
        scripts.requestAllFrames();
        var updates = HappyuBrowserPanel.class.getDeclaredField("scriptUpdates");
        updates.setAccessible(true);
        updates.set(panel, scripts);
        assertTrue(panel.sleep());
        workers.remove().run();
        assertTrue(workers.isEmpty());
        assertTrue(panel.isSleeping());
        assertFalse(panel.isBrowserStarted());
        panel.toggleImages();
        for (int i = 0; i < 3; i++) {
            panel.ensureBrowserCreated();
            assertTrue(panel.isBrowserStarted());
            assertFalse(panel.isSleeping());
            assertEquals("https://example.com/last", panel.getCurrentUrl());
            assertFalse(panel.isImagesBlocked());
            assertTrue(panel.sleep());
        }
        Disposer.dispose(panel);
        panel.ensureBrowserCreated();
        assertFalse(panel.isBrowserStarted());
        assertFalse(panel.sleep());
    }

    public void testQueuedCallbacksFromOldBrowserCannotModifyResumedTab() throws Exception {
        HappyuBrowserPanel panel = createPanel(false);
        panel.ensureBrowserCreated();
        var generation = HappyuBrowserPanel.class.getDeclaredField("browserGeneration");
        generation.setAccessible(true);
        var schedule = HappyuBrowserPanel.class.getDeclaredMethod("runOnBrowserUiThread", long.class, Runnable.class);
        schedule.setAccessible(true);
        AtomicInteger changes = new AtomicInteger();
        long old = generation.getLong(panel);
        schedule.invoke(panel, old, (Runnable) changes::incrementAndGet);
        assertTrue(panel.sleep());
        panel.ensureBrowserCreated();
        schedule.invoke(panel, old, (Runnable) changes::incrementAndGet);
        schedule.invoke(panel, generation.getLong(panel), (Runnable) changes::incrementAndGet);
        UIUtil.dispatchAllInvocationEvents();
        assertEquals(1, changes.get());
    }

    public void testRestoredSleepingTabStaysUnloadedUntilWoken() {
        HappyuBrowserPanel panel = createPanel(true);
        assertTrue(panel.isSleeping());
        assertFalse(panel.isBrowserStarted());
        panel.toggleImages();
        assertFalse(panel.isBrowserStarted());
        panel.ensureBrowserCreated();
        assertTrue(panel.isBrowserStarted());
        assertFalse(panel.isSleeping());
        assertFalse(panel.isImagesBlocked());
    }

    public void testOnlyBlockedMediaRequestsGetResourceCallbacks() {
        Registry.get("ide.browser.jcef.enabled").setValue(false, getTestRootDisposable());
        HappyuBrowserPanel panel = new HappyuBrowserPanel(getProject(), HappyuBrowserPanel.HOME_URL, true,
                url -> { }, title -> { }, () -> { });
        Disposer.register(getTestRootDisposable(), panel);
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
}
