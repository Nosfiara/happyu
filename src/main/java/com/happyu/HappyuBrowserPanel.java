package com.happyu;

import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class HappyuBrowserPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(HappyuBrowserPanel.class);
    static final String HOME_URL = "https://www.baidu.com/";
    private static final Pattern MEDIA_URL = Pattern.compile(".*\\.(png|jpe?g|gif|webp|svg|ico|bmp|avif)(\\?.*)?$");

    private final String initialUrl;
    private final Consumer<String> openTab;
    private final Consumer<String> titleChanged;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JBTextField addressField = new JBTextField();
    private final JButton backButton = new JButton("<");
    private final JButton forwardButton = new JButton(">");
    private final JButton reloadButton = new JButton("Reload");
    private final JButton toggleImagesButton = new JButton();
    private final LafManagerListener lafListener = manager -> runOnUiThread(this::applyIdeTheme);

    private volatile boolean imagesBlocked = true;
    private volatile boolean disposed;
    // Built on the EDT; script workers read the snapshot without accessing Swing.
    private volatile String contentModeScript = "";
    private JBCefBrowser browser;
    private JBCefClient client;
    private volatile HappyuCefScripts scriptUpdates;
    private final CefResourceRequestHandler mediaBlocker = new CefResourceRequestHandlerAdapter() {
        @Override
        public boolean onBeforeResourceLoad(CefBrowser cefBrowser, CefFrame frame, CefRequest request) {
            return !disposed && imagesBlocked;
        }
    };

    HappyuBrowserPanel(Project project, String initialUrl, Consumer<String> openTab, Consumer<String> titleChanged) {
        this.initialUrl = normalizeUrl(initialUrl);
        this.openTab = openTab;
        this.titleChanged = titleChanged;
        addressField.setText(this.initialUrl);
        root.setBorder(BorderFactory.createEmptyBorder());
        root.add(createToolbar(), BorderLayout.NORTH);
        contentModeScript = createContentModeScript();

        if (JBCefApp.isSupported()) {
            createBrowser();
        } else {
            root.add(new JBLabel("JCEF is not supported in this IDE runtime."), BorderLayout.CENTER);
        }

        project.getMessageBus().connect(this).subscribe(LafManagerListener.TOPIC, lafListener);
        updateToggleButton();
        updateNavigationButtons(false, false);
        applyIdeTheme();
    }

    private JComponent createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        toolbar.setBorder(JBUI.Borders.empty(6, 8));

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        navigation.add(backButton);
        navigation.add(forwardButton);
        navigation.add(reloadButton);
        navigation.add(toggleImagesButton);

        backButton.setToolTipText("Back");
        forwardButton.setToolTipText("Forward");
        reloadButton.setToolTipText("Reload");
        toggleImagesButton.setToolTipText("Ctrl+Alt+I");
        addressField.setToolTipText("URL");

        backButton.addActionListener(event -> goBack());
        forwardButton.addActionListener(event -> goForward());
        reloadButton.addActionListener(event -> {
            if (browser != null) {
                browser.getCefBrowser().reload();
            }
        });
        toggleImagesButton.addActionListener(event -> toggleImages());
        addressField.addActionListener(event -> load(addressField.getText()));

        toolbar.add(navigation, BorderLayout.NORTH);
        toolbar.add(addressField, BorderLayout.CENTER);
        return toolbar;
    }

    private void createBrowser() {
        client = JBCefApp.getInstance().createClient();
        browser = JBCefBrowser.createBuilder()
                .setClient(client)
                .setUrl(initialUrl)
                .build();
        browser.setOpenLinksInExternalBrowser(false);
        scriptUpdates = new HappyuCefScripts(browser.getCefBrowser(), () -> contentModeScript,
                AppExecutorUtil.getAppExecutorService());
        updateBrowserPageBackground();

        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public boolean onOpenURLFromTab(CefBrowser cefBrowser, CefFrame frame, String targetUrl, boolean userGesture) {
                openInNewTab(targetUrl);
                return true;
            }

            @Override
            public void onRenderProcessTerminated(CefBrowser cefBrowser, TerminationStatus status) {
                if (!disposed) {
                    LOG.warn("happyu renderer terminated: " + status);
                    runOnUiThread(() -> addressField.setToolTipText(
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
                if (disposed || !imagesBlocked) return null;
                return resourceRequestHandlerFor(request.getResourceType(), request.getURL());
            }
        }, browser.getCefBrowser());

        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser cefBrowser, CefFrame frame, String targetUrl, String targetFrameName) {
                openInNewTab(targetUrl);
                return true;
            }
        }, browser.getCefBrowser());

        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser cefBrowser, boolean isLoading,
                                             boolean canGoBack, boolean canGoForward) {
                runOnUiThread(() -> updateNavigationButtons(canGoBack, canGoForward));
            }

            @Override
            public void onLoadStart(CefBrowser cefBrowser, CefFrame frame, CefRequest.TransitionType transitionType) {
                applyContentMode(frame);
            }

            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (!disposed && frame != null) {
                    applyContentMode(frame);
                    if (frame.isMain()) {
                        String url = Objects.toString(frame.getURL(), "");
                        runOnUiThread(() -> addressField.setText(url));
                    }
                }
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, ErrorCode errorCode,
                                    String errorText, String failedUrl) {
                if (!disposed && errorCode != ErrorCode.ERR_ABORTED) {
                    LOG.warn("happyu page load failed: " + errorCode + " (" + errorText + ")");
                    if (frame != null && frame.isMain()) {
                        runOnUiThread(() -> addressField.setToolTipText(
                                "Load failed: " + errorCode + ". Try Reload."));
                    }
                }
            }
        }, browser.getCefBrowser());

        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onTitleChange(CefBrowser cefBrowser, String title) {
                runOnUiThread(() -> titleChanged.accept(Objects.toString(title, "")));
            }

            @Override
            public void onAddressChange(CefBrowser cefBrowser, CefFrame frame, String url) {
                if (!disposed && frame != null && frame.isMain()) {
                    runOnUiThread(() -> {
                        addressField.setText(Objects.toString(url, ""));
                        addressField.setToolTipText("URL");
                    });
                }
            }
        }, browser.getCefBrowser());

        client.addKeyboardHandler(new CefKeyboardHandlerAdapter() {
            @Override
            public boolean onPreKeyEvent(CefBrowser cefBrowser, CefKeyboardHandler.CefKeyEvent event, BoolRef isKeyboardShortcut) {
                if (isToggleShortcut(event)) {
                    runOnUiThread(HappyuBrowserPanel.this::toggleImages);
                    return true;
                }
                return false;
            }
        }, browser.getCefBrowser());

        root.add(browser.getComponent(), BorderLayout.CENTER);
    }

    void toggleImages() {
        if (disposed) {
            return;
        }
        imagesBlocked = !imagesBlocked;
        updateToggleButton();
        contentModeScript = createContentModeScript();

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
        if (disposed || browser == null) {
            return;
        }

        String url = normalizeUrl(rawUrl);
        addressField.setText(url);
        updateBrowserPageBackground();
        browser.loadURL(url);
    }

    private void openInNewTab(String rawUrl) {
        String url = normalizeLoadedUrl(rawUrl);
        if (url == null) {
            return;
        }

        runOnUiThread(() -> openTab.accept(url));
    }

    private void runOnUiThread(Runnable task) {
        SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                task.run();
            }
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
        toggleImagesButton.setText(imagesBlocked ? "Images Off" : "Images On");
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

        // CSS handles ordinary elements. Only conflicting inline !important declarations
        // need adjustment, initially and in added subtrees / changed style attributes.
        return """
                (() => {
                  const key = '__happyuContentMode';
                  const css = '%s';
                  const imagesBlocked = %s;
                  let state = window[key];
                  if (state && state.css === css && state.style.isConnected) return;
                  if (!state) {
                    state = {
                      style: document.createElement('style'), css: '', imagesBlocked: false,
                      added: new Set(), changed: new Set(), scheduled: false
                    };
                    state.style.id = 'happyu-style';
                    const normalizedStyles = new WeakMap();
                    const themedProperties = [
                      'background-color', 'background-image', 'color', 'border-top-color',
                      'border-right-color', 'border-bottom-color', 'border-left-color',
                      'text-shadow', 'box-shadow', 'caret-color'
                    ];
                    const mediaSelector = "img, picture, source, video, audio, canvas, svg, iframe[src*='video']";
                    state.normalize = element => {
                      if (!element.style) return;
                      const properties = state.imagesBlocked && element.matches(mediaSelector)
                        ? [...themedProperties, 'display', 'visibility'] : themedProperties;
                      for (const property of properties) {
                        if (element.style.getPropertyPriority(property) === 'important') {
                          // Keep the site's value; let our stylesheet control the presentation.
                          element.style.setProperty(property, element.style.getPropertyValue(property), '');
                        }
                      }
                      normalizedStyles.set(element, element.getAttribute('style'));
                    };
                    state.scan = root => {
                      if (root.nodeType === Node.ELEMENT_NODE && root.hasAttribute('style')) {
                        state.normalize(root);
                      }
                      if (root.childElementCount) root.querySelectorAll('[style]').forEach(state.normalize);
                    };
                    state.install = () => {
                      const parent = document.head || document.documentElement;
                      if (parent && !state.style.isConnected) parent.appendChild(state.style);
                    };
                    state.flush = () => {
                      state.scheduled = false;
                      const added = state.added;
                      const changed = state.changed;
                      state.added = new Set();
                      state.changed = new Set();
                      for (const node of added) {
                        if (!node.isConnected) continue;
                        // Scan a new subtree once even if nested additions share this batch.
                        let parent = node.parentElement;
                        while (parent && !added.has(parent)) parent = parent.parentElement;
                        if (!parent) state.scan(node);
                      }
                      for (const element of changed) {
                        if (element.isConnected) state.normalize(element);
                      }
                    };
                    state.observer = new MutationObserver(records => {
                      state.install();
                      for (const record of records) {
                        if (record.type === 'attributes') {
                          // Ignore our own writes; src changes can turn an iframe into media.
                          if (record.attributeName !== 'style' ||
                              normalizedStyles.get(record.target) !== record.target.getAttribute('style')) {
                            state.changed.add(record.target);
                          }
                        } else {
                          for (const node of record.addedNodes) {
                            if (node.nodeType === Node.ELEMENT_NODE) state.added.add(node);
                          }
                        }
                      }
                      if (!state.scheduled && (state.added.size || state.changed.size)) {
                        // Bound work to once per frame, including pages that reassert styles.
                        state.scheduled = true;
                        requestAnimationFrame(state.flush);
                      }
                    });
                    state.observer.observe(document, {
                      childList: true, subtree: true, attributes: true,
                      attributeFilter: ['style', 'src']
                    });
                    window[key] = state;
                  }
                  const scanExisting = !state.css || (!state.imagesBlocked && imagesBlocked);
                  state.imagesBlocked = imagesBlocked;
                  state.css = css;
                  if (state.style.textContent !== css) state.style.textContent = css;
                  state.install();
                  if (scanExisting) state.scan(document);
                })();
                """.formatted(escapeJavaScript(css), imagesBlocked ? "true" : "false");
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

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        contentModeScript = "";
        if (scriptUpdates != null) {
            scriptUpdates.dispose();
            scriptUpdates = null;
        }
        if (browser != null) {
            Disposer.dispose(browser);
            browser = null;
        }
        if (client != null) {
            Disposer.dispose(client);
            client = null;
        }
    }
}
