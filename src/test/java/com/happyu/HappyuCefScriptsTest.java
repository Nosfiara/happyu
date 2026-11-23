package com.happyu;

import junit.framework.TestCase;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class HappyuCefScriptsTest extends TestCase {
    private final ArrayDeque<Runnable> workers = new ArrayDeque<>();
    private final List<String> events = new ArrayList<>();
    private final AtomicReference<String> script = new AtomicReference<>("theme");
    private int frameLookups;

    public void testCallbackOnlyCopiesMetadataAndWorkerAcquiresItsOwnFrame() {
        AtomicBoolean callbackAlive = new AtomicBoolean(true);
        CefFrame callback = frame(false, "https://example.com/frame", callbackAlive, false);
        HappyuCefScripts queue = queue(frame(false, "https://example.com/frame", null, true));
        queue.request(callback);
        callbackAlive.set(false);
        assertEquals(0, frameLookups);
        assertTrue(events.isEmpty());

        workers.remove().run();
        assertEquals(1, frameLookups);
        assertEquals(List.of("script:theme", "dispose"), events);
    }

    public void testRepeatedLoadEventsAreCoalescedAndUseLatestTheme() {
        HappyuCefScripts queue = queue(frame(true, "https://example.com/", null, true));
        CefFrame callback = frame(true, "https://example.com/", new AtomicBoolean(true), false);
        for (int i = 0; i < 100; i++) queue.request(callback);
        assertEquals(1, workers.size());
        script.set("new theme");
        workers.remove().run();
        assertEquals(1, frameLookups);
        assertEquals(List.of("script:new theme", "dispose"), events);
    }

    public void testNavigationMakesOldQueuedFrameWorkObsolete() {
        HappyuCefScripts queue = queue(frame(false, "https://example.com/new", null, true));
        queue.request(frame(false, "https://example.com/old", new AtomicBoolean(true), false));
        workers.remove().run();
        assertEquals(List.of("dispose"), events);
    }

    public void testClosingPanelCancelsPendingWorkWithoutNativeCalls() {
        HappyuCefScripts queue = queue(frame(true, "https://example.com/", null, true));
        queue.requestMainFrame();
        queue.dispose();
        queue.requestMainFrame();
        workers.remove().run();
        assertEquals(0, frameLookups);
        assertTrue(events.isEmpty());
        assertTrue(workers.isEmpty());
    }

    private HappyuCefScripts queue(CefFrame acquired) {
        CefBrowser browser = (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMainFrame", "getFrame", "getFrameByIdentifier":
                            frameLookups++;
                            return acquired;
                        default: throw new AssertionError("Unexpected browser call: " + method.getName());
                    }
                });
        return new HappyuCefScripts(browser, script::get, workers::add);
    }

    private CefFrame frame(boolean main, String url, AtomicBoolean callbackAlive, boolean owned) {
        return (CefFrame) Proxy.newProxyInstance(CefFrame.class.getClassLoader(),
                new Class<?>[]{CefFrame.class}, (proxy, method, args) -> {
                    if (callbackAlive != null && !callbackAlive.get()) {
                        throw new AssertionError("Callback frame accessed after callback returned");
                    }
                    switch (method.getName()) {
                        case "isValid": return true;
                        case "isMain": return main;
                        case "getURL": return url;
                        case "getIdentifier":
                            return method.getReturnType() == long.class ? (Object) 42L : "frame-42";
                        case "executeJavaScript":
                            assertTrue("RPC executed inside callback", owned);
                            events.add("script:" + args[0]);
                            return null;
                        case "dispose":
                            assertTrue("Borrowed callback frame disposed", owned);
                            events.add("dispose");
                            return null;
                        default: throw new AssertionError("Unexpected frame call: " + method.getName());
                    }
                });
    }
}
