package com.happyu;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowContentUiType;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class HappyuToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final String ID = "happyu";
    private static final Key<HappyuBrowserPanel> BROWSER_KEY = Key.create("happyu.browser.panel");

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentManager manager = toolWindow.getContentManager();
        HappyuTabsState.Snapshot saved = project.getService(HappyuTabsState.class).getState();
        boolean[] restoring = {true};
        ContentManagerListener listener = new ContentManagerListener() {
            @Override
            public void contentAdded(@NotNull ContentManagerEvent event) {
                if (!restoring[0]) saveTabs(project, manager);
            }

            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                if (!restoring[0]) saveTabs(project, manager);
            }

            @Override
            public void selectionChanged(@NotNull ContentManagerEvent event) {
                if (!restoring[0]) {
                    startSelected(manager);
                    saveTabs(project, manager);
                }
            }
        };
        manager.addContentManagerListener(listener);
        Disposer.register(manager, () -> manager.removeContentManagerListener(listener));
        toolWindow.setToHideOnEmptyContent(false);
        toolWindow.setContentUiType(ToolWindowContentUiType.TABBED, null);
        toolWindow.setTitleActions(List.of(new DumbAwareAction(
                "New Tab", "Open a new happyu browser tab", AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                addTab(project, toolWindow, HappyuBrowserPanel.HOME_URL);
            }
        }));
        if (saved.initialized) {
            for (HappyuTabsState.Tab tab : saved.tabs) addTab(project, toolWindow, tab, false);
            if (saved.selectedIndex >= 0) manager.setSelectedContent(manager.getContent(saved.selectedIndex));
        } else {
            addTab(project, toolWindow, new HappyuTabsState.Tab(), false);
        }
        restoring[0] = false;
        startSelected(manager);
        saveTabs(project, manager);
    }

    static void show(Project project, Consumer<HappyuBrowserPanel> onSelected) {
        if (project.isDisposed()) {
            return;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.activate(() -> {
            if (project.isDisposed()) {
                return;
            }
            ContentManager manager = toolWindow.getContentManager();
            if (manager.getContentCount() == 0) {
                addTab(project, toolWindow, HappyuBrowserPanel.HOME_URL);
            }
            Content selected = manager.getSelectedContent();
            if (selected != null) {
                HappyuBrowserPanel panel = selected.getUserData(BROWSER_KEY);
                if (panel != null) {
                    onSelected.accept(panel);
                }
            }
        });
    }

    private static void addTab(Project project, ToolWindow toolWindow, String url) {
        addTab(project, toolWindow, new HappyuTabsState.Tab(url, "New Tab", true), true);
    }

    private static void addTab(Project project, ToolWindow toolWindow, HappyuTabsState.Tab tab, boolean select) {
        if (project.isDisposed()) {
            return;
        }
        ContentManager manager = toolWindow.getContentManager();
        Content content = manager.getFactory().createContent(new JPanel(), "New Tab", false);
        setTitle(content, tab.title);
        HappyuBrowserPanel panel = new HappyuBrowserPanel(project, tab.url, tab.imagesBlocked,
                target -> ToolWindowManager.getInstance(project).invokeLater(
                        () -> addTab(project, toolWindow, target)),
                title -> {
                    setTitle(content, title);
                    saveTabs(project, manager);
                }, () -> saveTabs(project, manager));
        content.setComponent(panel.getComponent());
        content.setPreferredFocusableComponent(panel.getPreferredFocusedComponent());
        content.setCloseable(true);
        content.putUserData(BROWSER_KEY, panel);
        content.setDisposer(panel);
        manager.addContent(content);
        if (select) manager.setSelectedContent(content, true);
    }

    private static void setTitle(Content content, String title) {
        String label = title.isBlank() ? "happyu" : title.strip();
        content.setDisplayName(label.length() > 32 ? label.substring(0, 32) + "…" : label);
        content.setDescription(label);
    }

    private static void startSelected(ContentManager manager) {
        Content selected = manager.getSelectedContent();
        HappyuBrowserPanel panel = selected == null ? null : selected.getUserData(BROWSER_KEY);
        if (panel != null) panel.ensureBrowserCreated();
    }

    private static void saveTabs(Project project, ContentManager manager) {
        if (project.isDisposed() || manager.isDisposed()) return;
        List<HappyuTabsState.Tab> tabs = new ArrayList<>();
        int selectedIndex = -1;
        for (Content content : manager.getContents()) {
            HappyuBrowserPanel panel = content.getUserData(BROWSER_KEY);
            if (panel != null) {
                if (content == manager.getSelectedContent()) selectedIndex = tabs.size();
                tabs.add(new HappyuTabsState.Tab(panel.getCurrentUrl(), content.getDescription(), panel.isImagesBlocked()));
            }
        }
        project.getService(HappyuTabsState.class).update(tabs, selectedIndex);
    }
}
