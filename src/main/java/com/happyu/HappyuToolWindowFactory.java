package com.happyu;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowContentUiType;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.util.List;
import java.util.function.Consumer;

public final class HappyuToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final String ID = "happyu";
    private static final Key<HappyuBrowserPanel> BROWSER_KEY = Key.create("happyu.browser.panel");

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setToHideOnEmptyContent(false);
        toolWindow.setContentUiType(ToolWindowContentUiType.TABBED, null);
        toolWindow.setTitleActions(List.of(new DumbAwareAction(
                "New Tab", "Open a new happyu browser tab", AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                addTab(project, toolWindow, HappyuBrowserPanel.HOME_URL);
            }
        }));
        addTab(project, toolWindow, HappyuBrowserPanel.HOME_URL);
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
        if (project.isDisposed()) {
            return;
        }
        ContentManager manager = toolWindow.getContentManager();
        Content content = manager.getFactory().createContent(new JPanel(), "New Tab", false);
        HappyuBrowserPanel panel = new HappyuBrowserPanel(project, url,
                target -> ToolWindowManager.getInstance(project).invokeLater(
                        () -> addTab(project, toolWindow, target)),
                title -> {
                    String label = title.isBlank() ? "happyu" : title.strip();
                    content.setDisplayName(label.length() > 32 ? label.substring(0, 32) + "…" : label);
                    content.setDescription(label);
                });
        content.setComponent(panel.getComponent());
        content.setPreferredFocusableComponent(panel.getPreferredFocusedComponent());
        content.setCloseable(true);
        content.putUserData(BROWSER_KEY, panel);
        content.setDisposer(panel);
        manager.addContent(content);
        manager.setSelectedContent(content, true);
    }
}
