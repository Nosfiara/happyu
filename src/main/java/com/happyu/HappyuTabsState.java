package com.happyu;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
@State(name = "HappyuTabs", storages = @Storage(value = "happyu-tabs.xml", roamingType = RoamingType.DISABLED),
        reloadable = false)
public final class HappyuTabsState implements PersistentStateComponent<HappyuTabsState.Snapshot> {
    private Snapshot snapshot = new Snapshot();
    private volatile boolean closing;

    public HappyuTabsState(Project project) {
        ApplicationManager.getApplication().getMessageBus().connect(project)
                .subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
                    @Override
                    public void projectClosing(@NotNull Project closingProject) {
                        // Saving can fail and closing can still be cancelled after
                        // projectClosingBeforeSave. Freeze only once closing is committed.
                        if (closingProject == project) closing = true;
                    }
                });
    }

    @Override
    public synchronized @NotNull Snapshot getState() {
        return copy(snapshot);
    }

    @Override
    public synchronized void loadState(@NotNull Snapshot state) {
        snapshot = copy(state);
        closing = false;
    }

    synchronized void update(List<Tab> tabs, int selectedIndex) {
        // Project shutdown disposes contents as well; those removals are not user-closed tabs.
        if (closing) return;
        Snapshot next = new Snapshot();
        next.initialized = true;
        next.tabs = tabs;
        next.selectedIndex = selectedIndex;
        snapshot = copy(next);
    }

    private static Snapshot copy(Snapshot source) {
        Snapshot result = new Snapshot();
        result.initialized = source.initialized;
        if (source.tabs != null) {
            for (Tab tab : source.tabs) {
                if (tab != null) result.tabs.add(new Tab(tab.url, tab.title, tab.imagesBlocked));
            }
        }
        result.selectedIndex = result.tabs.isEmpty() ? -1
                : Math.max(0, Math.min(source.selectedIndex, result.tabs.size() - 1));
        return result;
    }

    public static final class Snapshot {
        public boolean initialized;
        public List<Tab> tabs = new ArrayList<>();
        public int selectedIndex = -1;
    }

    public static final class Tab {
        public String url = HappyuBrowserPanel.HOME_URL;
        public String title = "New Tab";
        public boolean imagesBlocked = true;

        public Tab() { }

        Tab(String url, String title, boolean imagesBlocked) {
            this.url = url == null || url.isBlank() ? HappyuBrowserPanel.HOME_URL : url;
            this.title = title == null || title.isBlank() ? "New Tab" : title;
            this.imagesBlocked = imagesBlocked;
        }
    }
}
