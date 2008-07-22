/*
 * Copyright 2000-2008 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.tfsIntegration.ui;

import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.core.tfs.WorkspaceInfo;
import org.jetbrains.tfsIntegration.core.tfs.VersionControlPath;
import org.jetbrains.tfsIntegration.core.tfs.version.ChangesetVersionSpec;
import org.jetbrains.tfsIntegration.core.tfs.version.DateVersionSpec;
import org.jetbrains.tfsIntegration.core.tfs.version.LatestVersionSpec;
import org.jetbrains.tfsIntegration.exceptions.TfsException;
import org.jetbrains.tfsIntegration.stubs.versioncontrol.repository.Changeset;
import org.jetbrains.tfsIntegration.stubs.versioncontrol.repository.VersionSpec;
import org.jetbrains.tfsIntegration.ui.servertree.ServerBrowserDialog;

import javax.swing.*;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

public class SelectChangesetForm {

  interface Listener {
    void selectionChanged(Integer changeset);
  }

  private JTable myChangesetsTable;
  private TextFieldWithBrowseButton myFileField;
  private JTextField myUserField;
  private JRadioButton myAllChangesRadioButton;
  private JRadioButton myChangeNumberRadioButton;
  private JTextField myFromChangesetField;
  private JTextField myToChangesetField;
  private JRadioButton myCreatedDateRadioButton;
  private JTextField myFromDateField;
  private JTextField myToDateField;
  private JButton myFindButton;
  private JPanel panel;

  private final ChangesetsTableModel myChangesetsTableModel;

  private final WorkspaceInfo myWorkspace;

  private final List<Listener> myListeners = new ArrayList<Listener>();

  private enum Column {
    Changeset("Changeset", 10) {
      public String getValue(Changeset changeset) {
        return String.valueOf(changeset.getCset());
      }
    },
    Date("Date", 15) {
      public String getValue(Changeset changeset) {
        return new SimpleDateFormat().format(changeset.getDate().getTime());
      }
    },
    User("User", 20) {
      public String getValue(Changeset changeset) {
        return changeset.getOwner();
      }
    },
    Comment("Comment", 50) {
      public String getValue(Changeset changeset) {
        return changeset.getComment();
      }
    };

    private final String myCaption;
    private final int myWidth;

    Column(String caption, int width) {
      myCaption = caption;
      myWidth = width;
    }

    public String getCaption() {
      return myCaption;
    }

    public abstract String getValue(Changeset changeset);

    public int getWidth() {
      return myWidth;
    }
  }

  private static class ChangesetsTableModel extends AbstractTableModel {
    private List<Changeset> myChangesets;

    public void setChangesets(List<Changeset> changesets) {
      myChangesets = changesets;
      fireTableDataChanged();
    }

    public List<Changeset> getChangesets() {
      return myChangesets;
    }

    public String getColumnName(final int column) {
      return Column.values()[column].getCaption();
    }

    public int getRowCount() {
      return myChangesets != null ? myChangesets.size() : 0;
    }

    public int getColumnCount() {
      return Column.values().length;
    }

    public Object getValueAt(final int rowIndex, final int columnIndex) {
      return Column.values()[columnIndex].getValue(myChangesets.get(rowIndex));
    }
  }

  public SelectChangesetForm(final WorkspaceInfo workspace, String serverPath, final WorkspaceInfo workspaceInfo) {
    myWorkspace = workspaceInfo;
    myChangesetsTableModel = new ChangesetsTableModel();
    myChangesetsTable.setModel(myChangesetsTableModel);
    myChangesetsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    myFileField.setText(serverPath);

    myFindButton.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        search();
      }
    });

    final ActionListener radioButtonListener = new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        updateControls();
      }
    };

    myAllChangesRadioButton.addActionListener(radioButtonListener);
    myChangeNumberRadioButton.addActionListener(radioButtonListener);
    myCreatedDateRadioButton.addActionListener(radioButtonListener);

    myChangesetsTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
      public void valueChanged(final ListSelectionEvent e) {
        fireSelectionChanged();
      }
    });

    myFileField.getButton().addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        String initialPath =
          myFileField.getText() != null && myFileField.getText().length() > 0 ? myFileField.getText() : VersionControlPath.ROOT_FOLDER;

        ServerBrowserDialog d = new ServerBrowserDialog("Choose Server Item", getPanel(), myWorkspace.getServer(), initialPath, false);
        d.show();
        if (d.isOK()) {
          myFileField.setText(d.getSelectedPath());
        }
      }
    });

    myAllChangesRadioButton.setSelected(true);
    updateControls();
  }

  private void search() {
    VersionSpec versionFrom = null;
    VersionSpec versionTo = LatestVersionSpec.INSTANCE;

    try {
      if (myChangeNumberRadioButton.isSelected()) {
        if (myFromChangesetField.getText() != null && myFromChangesetField.getText().length() > 0) {
          versionFrom = new ChangesetVersionSpec(Integer.parseInt(myFromChangesetField.getText()));
        }
        if (myToChangesetField.getText() != null && myToChangesetField.getText().length() > 0) {
          versionTo = new ChangesetVersionSpec(Integer.parseInt(myToChangesetField.getText()));
        }
      }
      else if (myCreatedDateRadioButton.isSelected()) {
        if (myFromDateField.getText() != null && myFromDateField.getText().length() > 0) {
          versionFrom = new DateVersionSpec(SimpleDateFormat.getInstance().parse(myFromDateField.getText()));
        }
        if (myToDateField.getText() != null && myToDateField.getText().length() > 0) {
          versionTo = new DateVersionSpec(SimpleDateFormat.getInstance().parse(myToDateField.getText()));
        }
      }

      getPanel().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
      List<Changeset> changesets = myWorkspace.getServer().getVCS().queryHistory(myWorkspace.getName(), myWorkspace.getOwnerName(),
                                                                                 myFileField.getText(), Integer.MIN_VALUE,
                                                                                 myUserField.getText(), versionFrom, versionTo, -1);
      if (changesets.isEmpty()) {
        Messages.showInfoMessage(panel, "No matching changesets found", "Find Changeset");
      }
      myChangesetsTableModel.setChangesets(changesets);
    }
    catch (TfsException ex) {
      myChangesetsTableModel.setChangesets(Collections.<Changeset>emptyList());
      Messages.showErrorDialog(panel, ex.getMessage(), "Find Changeset");
    }
    catch (NumberFormatException ex) {
      myChangesetsTableModel.setChangesets(Collections.<Changeset>emptyList());
      Messages.showErrorDialog(panel, "Invalid changeset number specified", "Find Changeset");
    }
    catch (ParseException e1) {
      myChangesetsTableModel.setChangesets(Collections.<Changeset>emptyList());
      Messages.showErrorDialog(panel, "Invalid date specified", "Find Changeset");
    }
    finally {
      getPanel().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
    }
  }

  private void updateControls() {
    myFromChangesetField.setEnabled(myChangeNumberRadioButton.isSelected());
    myToChangesetField.setEnabled(myChangeNumberRadioButton.isSelected());
    if (!myChangeNumberRadioButton.isSelected()) {
      myFromChangesetField.setText(null);
      myToChangesetField.setText(null);
    }

    myFromDateField.setEnabled(myCreatedDateRadioButton.isSelected());
    myToDateField.setEnabled(myCreatedDateRadioButton.isSelected());
    if (!myCreatedDateRadioButton.isSelected()) {
      myFromDateField.setText(null);
      myToDateField.setText(null);
    }
  }

  public JPanel getPanel() {
    return panel;
  }

  @Nullable
  public Integer getChangeset() {
    if (myChangesetsTable.getSelectedRowCount() == 1) {
      return myChangesetsTableModel.getChangesets().get(myChangesetsTable.getSelectedRow()).getCset();
    }
    else {
      return null;
    }
  }

  public void addListener(Listener listener) {
    myListeners.add(listener);
  }

  public void removeListener(Listener listener) {
    myListeners.remove(listener);
  }

  private void fireSelectionChanged() {
    Listener[] listenersArray = myListeners.toArray(new Listener[myListeners.size()]);
    Integer changeset = getChangeset();
    for (Listener listener : listenersArray) {
      listener.selectionChanged(changeset);
    }
  }


}
