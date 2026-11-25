package com.happyu.browser;

import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefClient;
import com.intellij.util.ui.JBUI;
import com.intellij.ui.ColorUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefKeyboardHandler;
import org.cef.handler.CefKeyboardHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.EventFlags;
import org.cef.network.CefRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class HappyuBrowserPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(HappyuBrowserPanel.class);
    private static final String CONTENT_MODE_SCRIPT = loadContentModeScript();
    public static final String HOME_URL = "https://www.baidu.com/";
    private static final Pattern MEDIA_URL = Pattern.compile(".*\\.(png|jpe?g|gif|webp|svg|ico|bmp|avif)(\\?.*)?$");

    private final Project project;
    private final Consumer<String> openTab;
    private final Consumer<String> titleChanged;
    private final Runnable stateChanged;
    private String currentUrl;
    private volatile boolean browserStarted;
    private volatile boolean sleeping;
    private volatile long browserGeneration;
    private JComponent pageContent;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JBTextField addressField = new JBTextField();
    private final JButton backButton = createIconButton("back.svg", "Back");
    private final JButton forwardButton = createIconButton("forward.svg", "Forward");
    private final JButton reloadButton = createIconButton("reload.svg", "Reload");
    private final JButton toggleImagesButton = createIconButton("images-off.svg", "Images Off");
    private final LafManagerListener lafListener = manager -> runOnUiThread(this::applyIdeTheme);

    private volatile boolean imagesBlocked = true;
    private volatile boolean disposed;
    // Built on the EDT; script workers read the snapshot without accessing Swing.
    private volatile String contentModeScript = "";
    private JBCefBrowser browser;
    private JBCefClient client;
    private CefRequestHandlerAdapter requestHandler;
    private CefLifeSpanHandlerAdapter lifeSpanHandler;
    private CefLoadHandlerAdapter loadHandler;
    private CefDisplayHandlerAdapter displayHandler;
    private CefKeyboardHandlerAdapter keyboardHandler;
    private volatile HappyuCefScripts scriptUpdates;
    private final CefResourceRequestHandler mediaBlocker = new CefResourceRequestHandlerAdapter() {
        @Override
        public boolean onBeforeResourceLoad(CefBrowser cefBrowser, CefFrame frame, CefRequest request) {
            return !disposed && imagesBlocked;
        }
    };

    public HappyuBrowserPanel(Project project, String initialUrl, boolean imagesBlocked,
                       Consumer<String> openTab, Consumer<String> titleChanged, Runnable stateChanged) {
        this(project, initialUrl, imagesBlocked, false, openTab, titleChanged, stateChanged);
    }

    public HappyuBrowserPanel(Project project, String initialUrl, boolean imagesBlocked, boolean sleeping,
                       Consumer<String> openTab, Consumer<String> titleChanged, Runnable stateChanged) {
        this.project = project;
        this.currentUrl = normalizeUrl(initialUrl);
        this.imagesBlocked = imagesBlocked;
        this.sleeping = sleeping;
        this.openTab = openTab;
        this.titleChanged = titleChanged;
        this.stateChanged = stateChanged;
        addressField.setText(currentUrl);
        root.setBorder(BorderFactory.createEmptyBorder());
        root.add(createToolbar(), BorderLayout.NORTH);
        contentModeScript = createContentModeScript();

        project.getMessageBus().connect(this).subscribe(LafManagerListener.TOPIC, lafListener);
        updateToggleButton();
        updateNavigationButtons(false, false);
        applyIdeTheme();
        if (sleeping) showSleepingPage();
    }

    public void ensureBrowserCreated() {
        if (disposed || browserStarted) return;
        boolean wasSleeping = sleeping;
        sleeping = false;
        browserStarted = true;
        browserGeneration++;
        try {
            if (JBCefApp.isSupported()) {
                createBrowser();
                applyContentMode();
            } else {
                setPageContent(new JBLabel("JCEF is not supported in this IDE runtime."));
            }
        } catch (RuntimeException e) {
            browserStarted = false;
            sleeping = wasSleeping;
            browserGeneration++;
            releaseBrowser();
            setPageContent(new JBLabel("Unable to load this tab. Click Reload to retry."));
            LOG.warn("Unable to create happyu browser", e);
        }
        root.revalidate();
        root.repaint();
        if (wasSleeping) stateChanged.run();
    }

    public boolean isBrowserStarted() {
        return browserStarted;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public boolean isImagesBlocked() {
        return imagesBlocked;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public boolean sleep() {
        if (disposed || !browserStarted || sleeping) return false;
        sleeping = true;
        browserStarted = false;
        browserGeneration++;
        releaseBrowser();
        updateNavigationButtons(false, false);
        showSleepingPage();
        stateChanged.run();
        return true;
    }

    private void showSleepingPage() {
        JBLabel message = new JBLabel("<html>Tab is sleeping.<br>Select this tab or click Reload to load it again.</html>");
        message.setHorizontalAlignment(JBLabel.CENTER);
        setPageContent(message);
    }

    private void setPageContent(JComponent component) {
        if (pageContent != null) root.remove(pageContent);
        pageContent = component;
        root.add(component, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    private void recordUrl(String url) {
        String value = normalizeLoadedUrl(url);
        if (value != null && !value.equals(currentUrl)) {
            currentUrl = value;
            stateChanged.run();
        }
    }

    private JComponent createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        toolbar.setBorder(JBUI.Borders.empty(6, 8));

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        navigation.add(backButton);
        navigation.add(forwardButton);
        navigation.add(reloadButton);
        navigation.add(toggleImagesButton);

        addressField.setToolTipText("URL");

        backButton.addActionListener(event -> goBack());
        forwardButton.addActionListener(event -> goForward());
        reloadButton.addActionListener(event -> {
            if (!browserStarted) {
                ensureBrowserCreated();
            } else if (browser != null) {
                browser.getCefBrowser().reload();
            }
        });
        toggleImagesButton.addActionListener(event -> toggleImages());
        addressField.addActionListener(event -> load(addressField.getText()));

        toolbar.add(navigation, BorderLayout.NORTH);
        toolbar.add(addressField, BorderLayout.CENTER);
        return toolbar;
    }

    private static JButton createIconButton(String icon, String name) {
        JButton button = new JButton(IconLoader.getIcon("/icons/" + icon, HappyuBrowserPanel.class));
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
        button.setPreferredSize(JBUI.size(28, 28));
        button.setMargin(JBUI.insets(4));
        return button;
    }

    private void createBrowser() {
        long generation = browserGeneration;
        browser = project.getService(HappyuBrowserClient.class).createBrowser(currentUrl);
        client = browser.getJBCefClient();
        Disposer.register(browser, this::removeBrowserHandlers);
        browser.setOpenLinksInExternalBrowser(false);
        scriptUpdates = new HappyuCefScripts(browser.getCefBrowser(), () -> contentModeScript,
                AppExecutorUtil.getAppExecutorService());
        Disposer.register(browser, scriptUpdates);
        updateBrowserPageBackground();

        client.addRequestHandler(requestHandler = new CefRequestHandlerAdapter() {
            @Override
            public boolean onOpenURLFromTab(CefBrowser cefBrowser, CefFrame frame, String targetUrl, boolean userGesture) {
                openInNewTab(targetUrl, generation);
                return true;
            }

            @Override
            public void onRenderProcessTerminated(CefBrowser cefBrowser, TerminationStatus status) {
                if (isCurrentBrowser(generation)) {
                    LOG.warn("happyu renderer terminated: " + status);
                    runOnBrowserUiThread(generation, () -> addressField.setToolTipText(
                            "Renderer stopped: " + status + ". Try Reload or reopen happyu."));
                }
            }

            @Override
            public CefResourceRequestHandler getResourceRequestHandler(CefBrowser cefBrowser,
                                                                       CefFrame frame,
                                                                       CefRequest request,
                                                                       boolean isNavigation,
                                                                       boolean isDownload,
                                                                       String requestInitiator,
                                                                       BoolRef disableDefaultHandling) {
                if (!isCurrentBrowser(generation) || !imagesBlocked) return null;
                return resourceRequestHandlerFor(request.getResourceType(), request.getURL());
            }
        }, browser.getCefBrowser());

        client.addLifeSpanHandler(lifeSpanHandler = new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser cefBrowser, CefFrame frame, String targetUrl, String targetFrameName) {
                openInNewTab(targetUrl, generation);
                return true;
            }
        }, browser.getCefBrowser());

        client.addLoadHandler(loadHandler = new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser cefBrowser, boolean isLoading,
                                             boolean canGoBack, boolean canGoForward) {
                runOnBrowserUiThread(generation, () -> updateNavigationButtons(canGoBack, canGoForward));
            }

            @Override
            public void onLoadStart(CefBrowser cefBrowser, CefFrame frame, CefRequest.TransitionType transitionType) {
                if (isCurrentBrowser(generation)) applyContentMode(frame);
            }

            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (isCurrentBrowser(generation) && frame != null) {
                    applyContentMode(frame);
                    if (frame.isMain()) {
                        String url = Objects.toString(frame.getURL(), "");
                        runOnBrowserUiThread(generation, () -> {
                            addressField.setText(url);
                            recordUrl(url);
                        });
                    }
                }
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, ErrorCode errorCode,
                                    String errorText, String failedUrl) {
                if (isCurrentBrowser(generation) && errorCode != ErrorCode.ERR_ABORTED) {
                    LOG.warn("happyu page load failed: " + errorCode + " (" + errorText + ")");
                    if (frame != null && frame.isMain()) {
                        runOnBrowserUiThread(generation, () -> addressField.setToolTipText(
                                "Load failed: " + errorCode + ". Try Reload."));
                    }
                }
            }
        }, browser.getCefBrowser());

        client.addDisplayHandler(displayHandler = new CefDisplayHandlerAdapter() {
            @Override
            public void onTitleChange(CefBrowser cefBrowser, String title) {
                runOnBrowserUiThread(generation, () -> titleChanged.accept(Objects.toString(title, "")));
            }

            @Override
            public void onAddressChange(CefBrowser cefBrowser, CefFrame frame, String url) {
                if (isCurrentBrowser(generation) && frame != null && frame.isMain()) {
                    runOnBrowserUiThread(generation, () -> {
                        addressField.setText(Objects.toString(url, ""));
                        addressField.setToolTipText("URL");
                        recordUrl(url);
                    });
                }
            }
        }, browser.getCefBrowser());

        client.addKeyboardHandler(keyboardHandler = new CefKeyboardHandlerAdapter() {
            @Override
            public boolean onPreKeyEvent(CefBrowser cefBrowser, CefKeyboardHandler.CefKeyEvent event, BoolRef isKeyboardShortcut) {
                if (isCurrentBrowser(generation) && isToggleShortcut(event)) {
                    runOnBrowserUiThread(generation, HappyuBrowserPanel.this::toggleImages);
                    return true;
                }
                return false;
            }
        }, browser.getCefBrowser());

        setPageContent(browser.getComponent());
    }

    public void toggleImages() {
        if (disposed) {
            return;
        }
        imagesBlocked = !imagesBlocked;
        updateToggleButton();
        contentModeScript = createContentModeScript();
        stateChanged.run();

        if (disposed || browser == null) {
            return;
        }

        applyContentMode();
        if (!imagesBlocked) {
            // Reload previously blocked resources; also update every current frame immediately.
            browser.getCefBrowser().reloadIgnoreCache();
        }
    }

    private void load(String rawUrl) {
        if (disposed) return;
        String url = normalizeUrl(rawUrl);
        recordUrl(url);
        addressField.setText(url);
        if (!browserStarted) {
            ensureBrowserCreated();
            return;
        }
        if (browser == null) return;
        updateBrowserPageBackground();
        browser.loadURL(url);
    }

    private void openInNewTab(String rawUrl, long generation) {
        String url = normalizeLoadedUrl(rawUrl);
        if (url == null) {
            return;
        }

        runOnBrowserUiThread(generation, () -> openTab.accept(url));
    }

    private void runOnUiThread(Runnable task) {
        SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                task.run();
            }
        });
    }

    private boolean isCurrentBrowser(long generation) {
        return !disposed && browserStarted && !sleeping && browserGeneration == generation;
    }

    private void runOnBrowserUiThread(long generation, Runnable task) {
        if (!isCurrentBrowser(generation)) return;
        SwingUtilities.invokeLater(() -> {
            if (isCurrentBrowser(generation)) task.run();
        });
    }

    private void goBack() {
        if (!disposed && browser != null) {
            browser.getCefBrowser().goBack();
        }
    }

    private void goForward() {
        if (!disposed && browser != null) {
            browser.getCefBrowser().goForward();
        }
    }

    private static String normalizeLoadedUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }

        String value = rawUrl.trim();
        if (value.isEmpty() || value.equals("about:blank") || value.startsWith("data:")) {
            return null;
        }
        return value;
    }

    private void updateNavigationButtons(boolean canGoBack, boolean canGoForward) {
        backButton.setEnabled(canGoBack);
        forwardButton.setEnabled(canGoForward);
    }

    static String normalizeUrl(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isEmpty()) {
            return HOME_URL;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value;
        }
        if (value.contains(".") && !value.contains(" ")) {
            return "https://" + value;
        }
        return "https://www.baidu.com/s?wd=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void updateToggleButton() {
        String name = imagesBlocked ? "Images Off" : "Images On";
        toggleImagesButton.setIcon(IconLoader.getIcon(
                imagesBlocked ? "/icons/images-off.svg" : "/icons/images.svg", HappyuBrowserPanel.class));
        toggleImagesButton.setToolTipText(name + " (Ctrl+Alt+I)");
        toggleImagesButton.getAccessibleContext().setAccessibleName(name);
    }

    private void applyIdeTheme() {
        if (disposed) {
            return;
        }
        Color background = UIUtil.getPanelBackground();
        Color foreground = UIUtil.getLabelForeground();
        root.setBackground(background);
        addressField.setBackground(UIUtil.getTextFieldBackground());
        addressField.setForeground(UIUtil.getTextFieldForeground());
        setComponentColors(root, background, foreground);
        updateBrowserPageBackground();
        contentModeScript = createContentModeScript();
        applyContentMode();
    }

    private void updateBrowserPageBackground() {
        if (browser != null) {
            browser.setPageBackgroundColor(toCssColor(UIUtil.getPanelBackground()));
        }
    }

    private void setComponentColors(JComponent component, Color background, Color foreground) {
        component.setBackground(background);
        component.setForeground(foreground);
        for (java.awt.Component child : component.getComponents()) {
            child.setBackground(background);
            child.setForeground(foreground);
            if (child instanceof JComponent childComponent) {
                setComponentColors(childComponent, background, foreground);
            }
        }
    }

    private void applyContentMode() {
        HappyuCefScripts updates = scriptUpdates;
        if (disposed || updates == null) {
            return;
        }

        updates.requestAllFrames();
    }

    private void applyContentMode(@Nullable CefFrame frame) {
        HappyuCefScripts updates = scriptUpdates;
        if (disposed || updates == null) {
            return;
        }
        updates.request(frame);
    }

    private String createContentModeScript() {
        Color background = UIUtil.getPanelBackground();
        Color foreground = UIUtil.getLabelForeground();
        Color fieldBackground = UIUtil.getTextFieldBackground();
        Color fieldForeground = UIUtil.getTextFieldForeground();
        boolean dark = ColorUtil.isDark(background);
        Color link = dark ? new Color(0x6CA8FF) : new Color(0x1750A6);
        Color border = dark ? new Color(0x4A4D50) : new Color(0xC9CCD0);
        Color card = dark ? ColorUtil.brighter(background, 1) : ColorUtil.darker(background, 1);

        String css = """
                :root { color-scheme: %s; }
                html, body {
                  background: %s !important;
                  color: %s !important;
                }
                *, *::before, *::after {
                  background-color: transparent !important;
                  background-image: none !important;
                  border-color: %s !important;
                  color: %s !important;
                  text-shadow: none !important;
                  box-shadow: none !important;
                  caret-color: %s !important;
                }
                header, nav, aside, main, article, section, table, thead, tbody, tfoot, tr, td, th,
                [class*='card' i], [class*='item' i], [class*='list' i], [class*='content' i],
                [class*='container' i], [class*='wrapper' i], [class*='panel' i],
                [class*='box' i], [class*='rank' i], [class*='hot' i] {
                  background-color: %s !important;
                }
                a, a *, a:visited, a:visited * { color: %s !important; }
                input, textarea, select, button {
                  background: %s !important;
                  color: %s !important;
                  border-color: %s !important;
                }
                %s
                """.formatted(
                dark ? "dark" : "light",
                toCssColor(background),
                toCssColor(foreground),
                toCssColor(border),
                toCssColor(foreground),
                toCssColor(foreground),
                toCssColor(card),
                toCssColor(link),
                toCssColor(fieldBackground),
                toCssColor(fieldForeground),
                toCssColor(border),
                imagesBlocked ? imageBlockCss() : imageRestoreCss()
        );

        return CONTENT_MODE_SCRIPT + "\n('" + escapeJavaScript(css) + "', " + imagesBlocked + ");";
    }

    private static String loadContentModeScript() {
        try (var source = HappyuBrowserPanel.class.getResourceAsStream("/scripts/content-mode.js")) {
            if (source == null) throw new IllegalStateException("Missing happyu content-mode script");
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load happyu content-mode script", e);
        }
    }

    @Nullable CefResourceRequestHandler resourceRequestHandlerFor(CefRequest.ResourceType type, @Nullable String url) {
        if (disposed || !imagesBlocked) return null;
        return isBlockedResource(type, url) ? mediaBlocker : null;
    }

    private static boolean isBlockedResource(CefRequest.ResourceType type, @Nullable String url) {
        if (type == CefRequest.ResourceType.RT_IMAGE ||
                type == CefRequest.ResourceType.RT_FAVICON ||
                type == CefRequest.ResourceType.RT_MEDIA) {
            return true;
        }

        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return MEDIA_URL.matcher(lower).matches();
    }

    private static boolean isToggleShortcut(CefKeyboardHandler.CefKeyEvent event) {
        if (event.type != CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN &&
                event.type != CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_KEYDOWN) {
            return false;
        }

        boolean controlOrCommand = hasFlag(event.modifiers, EventFlags.EVENTFLAG_CONTROL_DOWN) ||
                hasFlag(event.modifiers, EventFlags.EVENTFLAG_COMMAND_DOWN);
        boolean alt = hasFlag(event.modifiers, EventFlags.EVENTFLAG_ALT_DOWN);
        return controlOrCommand && alt && event.windows_key_code == KeyEvent.VK_I;
    }

    private static boolean hasFlag(int value, int flag) {
        return (value & flag) == flag;
    }

    private static String imageBlockCss() {
        return """
                img, picture, source, video, audio, canvas, svg, iframe[src*='video'] {
                  display: none !important;
                  visibility: hidden !important;
                }
                * {
                  background-image: none !important;
                }
                """;
    }

    private static String imageRestoreCss() {
        return "";
    }

    private static String toCssColor(Color color) {
        return "#%02x%02x%02x".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String escapeJavaScript(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    public @NotNull JComponent getComponent() {
        return root;
    }

    public @NotNull JComponent getPreferredFocusedComponent() {
        return addressField;
    }

    private void removeBrowserHandlers() {
        if (browser != null && client != null && !client.isDisposed()) {
            CefBrowser cefBrowser = browser.getCefBrowser();
            if (requestHandler != null) client.removeRequestHandler(requestHandler, cefBrowser);
            if (lifeSpanHandler != null) client.removeLifeSpanHandler(lifeSpanHandler, cefBrowser);
            if (loadHandler != null) client.removeLoadHandler(loadHandler, cefBrowser);
            if (displayHandler != null) client.removeDisplayHandler(displayHandler, cefBrowser);
            if (keyboardHandler != null) client.removeKeyboardHandler(keyboardHandler, cefBrowser);
        }
        requestHandler = null;
        lifeSpanHandler = null;
        loadHandler = null;
        displayHandler = null;
        keyboardHandler = null;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        browserStarted = false;
        browserGeneration++;
        contentModeScript = "";
        releaseBrowser();
    }

    private void releaseBrowser() {
        if (scriptUpdates != null) Disposer.dispose(scriptUpdates);
        if (browser != null) Disposer.dispose(browser);
        browser = null;
        client = null;
        scriptUpdates = null;
    }
}
