package com.happyu.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

public final class HappyuConfigurable implements SearchableConfigurable {
    private final HappyuSettings settings;
    private JPanel panel;
    private JBCheckBox autoSleep;
    private JSpinner idleMinutes;
    private JBLabel idleLabel;

    public HappyuConfigurable(Project project) {
        settings = project.getService(HappyuSettings.class);
    }

    @Override
    public @NotNull String getId() {
        return "com.happyu.settings";
    }

    @Override
    public String getDisplayName() {
        return "happyu";
    }

    @Override
    public JComponent createComponent() {
        if (panel != null) return panel;
        autoSleep = new JBCheckBox("Automatically sleep inactive background tabs");
        idleMinutes = new JSpinner(new SpinnerNumberModel(15, 1, 1440, 1));
        idleLabel = new JBLabel("Idle time (minutes):");
        autoSleep.addActionListener(event -> {
            idleMinutes.setEnabled(autoSleep.isSelected());
            idleLabel.setEnabled(autoSleep.isSelected());
        });
        panel = FormBuilder.createFormBuilder()
                .addComponent(autoSleep)
                .addLabeledComponent(idleLabel, idleMinutes)
                .addComponent(new JBLabel("<html>Sleeping tabs reload when opened.<br>"
                        + "Unsaved forms, page state and browsing history may be lost.<br>"
                        + "Background media and uploads are not excluded from automatic sleep.<br>"
                        + "Cookies are not explicitly cleared. The selected tab never sleeps.</html>"))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        if (panel == null) return false;
        HappyuSettings.Options saved = settings.getState();
        return autoSleep.isSelected() != saved.autoSleepEnabled
                || ((Number) idleMinutes.getValue()).intValue() != saved.idleMinutes;
    }

    @Override
    public void apply() throws ConfigurationException {
        if (panel == null) return;
        try {
            idleMinutes.commitEdit();
        } catch (java.text.ParseException e) {
            throw new ConfigurationException("Enter an idle time between 1 and 1440 minutes.");
        }
        int minutes = ((Number) idleMinutes.getValue()).intValue();
        if (minutes < 1 || minutes > 1440) {
            throw new ConfigurationException("Enter an idle time between 1 and 1440 minutes.");
        }
        settings.update(autoSleep.isSelected(), minutes);
    }

    @Override
    public void reset() {
        if (panel == null) return;
        HappyuSettings.Options saved = settings.getState();
        autoSleep.setSelected(saved.autoSleepEnabled);
        idleMinutes.setValue(saved.idleMinutes);
        idleMinutes.setEnabled(saved.autoSleepEnabled);
        idleLabel.setEnabled(saved.autoSleepEnabled);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        autoSleep = null;
        idleMinutes = null;
        idleLabel = null;
    }
}
