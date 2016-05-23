// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.alm.plugin.idea.ui.branch;

import com.google.common.base.Predicate;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.ui.SortedComboBoxModel;
import com.microsoft.alm.plugin.authentication.AuthHelper;
import com.microsoft.alm.plugin.context.ServerContext;
import com.microsoft.alm.plugin.context.ServerContextManager;
import com.microsoft.alm.plugin.idea.resources.TfPluginBundle;
import com.microsoft.alm.plugin.idea.ui.common.AbstractModel;
import com.microsoft.alm.plugin.idea.ui.common.ModelValidationInfo;
import com.microsoft.alm.plugin.idea.utils.GeneralGitHelper;
import com.microsoft.alm.plugin.idea.utils.TfGitHelper;
import com.microsoft.alm.plugin.telemetry.TfsTelemetryHelper;
import com.microsoft.alm.sourcecontrol.webapi.model.GitRefUpdate;
import com.microsoft.alm.sourcecontrol.webapi.model.GitRefUpdateResult;
import git4idea.GitRemoteBranch;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.ComboBoxModel;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Model for creating a new branch from an existing remote branch
 */
public class CreateBranchModel extends AbstractModel {
    private static final Logger logger = LoggerFactory.getLogger(CreateBranchModel.class);

    private static final String REFS_PREFIX = "refs/heads/";
    private static final String ORIGIN_PREFIX = "origin/";
    private static final String BASE_HASH = "0000000000000000000000000000000000000000";

    public static final String PROP_BRANCH_NAME = "branchName";
    public static final String PROP_SELECTED_REMOTE_BRANCH = "selectedRemoteBranch";
    public static final String PROP_REMOTE_BRANCH_COMBO_MODEL = "remoteBranchComboModel";
    public static final String CREATE_BRANCH_ACTION = "create-branch";

    private final Project project;
    private final GitRepository gitRepository;
    private final Collection<GitRemote> tfGitRemotes;
    private String branchName;
    private GitRemoteBranch selectedRemoteBranch;
    private SortedComboBoxModel<GitRemoteBranch> remoteBranchComboModel;

    protected CreateBranchModel(final Project project, final String defaultBranchName, final GitRepository gitRepository) {
        super();
        this.project = project;
        this.branchName = defaultBranchName;
        this.gitRepository = gitRepository;
        this.tfGitRemotes = TfGitHelper.getTfGitRemotes(gitRepository);
        this.remoteBranchComboModel = createRemoteBranchDropdownModel();
        this.selectedRemoteBranch = this.remoteBranchComboModel.getSelectedItem();
    }

    private SortedComboBoxModel<GitRemoteBranch> createRemoteBranchDropdownModel() {
        logger.info("CreateBranchModel.createRemoteBranchDropdownModel");
        // TODO: add option to retrieve more branches in case the branch they are looking for is missing locally
        return TfGitHelper.createRemoteBranchDropdownModel(tfGitRemotes, gitRepository.getInfo(), new Predicate<GitRemoteBranch>() {
            @Override
            public boolean apply(final GitRemoteBranch remoteBranch) {
                //  condition: remote must be a vso/tfs remote
                return tfGitRemotes.contains(remoteBranch.getRemote());
            }
        });
    }

    public ComboBoxModel getRemoteBranchDropdownModel() {
        return remoteBranchComboModel;
    }

    public GitRemoteBranch getSelectedRemoteBranch() {
        return selectedRemoteBranch;
    }

    public void setSelectedRemoteBranch(final GitRemoteBranch remoteBranch) {
        this.selectedRemoteBranch = remoteBranch;
        setChangedAndNotify(PROP_SELECTED_REMOTE_BRANCH);
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(final String branchName) {
        if (!StringUtils.equals(this.branchName, branchName)) {
            this.branchName = branchName;
            setChangedAndNotify(PROP_BRANCH_NAME);
        }
    }

    public ModelValidationInfo validate() {
        final String branchName = getBranchName();
        if (branchName == null || branchName.isEmpty()) {
            return ModelValidationInfo.createWithResource(PROP_BRANCH_NAME,
                    TfPluginBundle.KEY_CREATE_BRANCH_DIALOG_ERRORS_BRANCH_NAME_EMPTY);
        }

        for (GitRemoteBranch ref : remoteBranchComboModel.getItems()) {
            if (StringUtils.equals(ref.getName().replace(ORIGIN_PREFIX, StringUtils.EMPTY), getBranchName())) {
                return ModelValidationInfo.createWithResource(PROP_BRANCH_NAME,
                        TfPluginBundle.message(TfPluginBundle.KEY_CREATE_BRANCH_DIALOG_ERRORS_BRANCH_NAME_EXISTS, getBranchName()));
            }
        }

        return ModelValidationInfo.NO_ERRORS;
    }

    /**
     * Creates a new branch on the server from an existing branch on the server
     */
    public void createBranch() {
        logger.info("CreateBranchModel.createBranch");
        final ModelValidationInfo validationInfo = validate();
        if (validationInfo == null) {
            final String gitRemoteUrl = TfGitHelper.getTfGitRemote(gitRepository).getFirstUrl();
            final Task.Backgroundable createBranchTask = new Task.Backgroundable(project, TfPluginBundle.message(TfPluginBundle.KEY_CREATE_BRANCH_DIALOG_TITLE),
                    true, PerformInBackgroundOption.DEAF) {

                @Override
                public void run(@NotNull final ProgressIndicator progressIndicator) {
                    progressIndicator.setText(TfPluginBundle.message(TfPluginBundle.KEY_CREATE_BRANCH_DIALOG_TITLE));
                    // get context from manager, and store in active context
                    final ServerContext context = ServerContextManager.getInstance().getAuthenticatedContext(
                            gitRemoteUrl, true);

                    if (context == null) {
                        VcsNotifier.getInstance(project).notifyError(TfPluginBundle.message(
                                        TfPluginBundle.KEY_CREATE_BRANCH_ERRORS_AUTHENTICATION_FAILED_TITLE),
                                TfPluginBundle.message(TfPluginBundle.KEY_ERRORS_AUTH_NOT_SUCCESSFUL, gitRemoteUrl));
                        return;
                    }
                    doBranchCreate(context);
                }
            };
            createBranchTask.queue();
        }
    }

    private boolean doBranchCreate(@NotNull final ServerContext context) {
        logger.info("CreateBranchModel.doBranchCreate");
        // call server to create branch
        boolean hasNotifiedUser = false; //keep track of notifications because of recursive call
        String errorMessage = StringUtils.EMPTY;
        try {
            // ref update will create a new ref when no existing ref is found (we check for existing)
            final GitRefUpdate gitRefUpdate = new GitRefUpdate();
            gitRefUpdate.setName(REFS_PREFIX + getBranchName().replaceFirst(ORIGIN_PREFIX, StringUtils.EMPTY));
            gitRefUpdate.setOldObjectId(BASE_HASH); // since branch is new the last commit hash is all 0's
            gitRefUpdate.setNewObjectId(GeneralGitHelper.getLastCommitHash(project, gitRepository, selectedRemoteBranch)); // TODO: get the latest commit from server b/c the latest local commit could be incorrect
            gitRefUpdate.setRepositoryId(context.getGitRepository().getId());

            logger.info("CreateBranchModel.createBranch sending create ref call to server");
            final List<GitRefUpdateResult> results = context.getGitHttpClient().updateRefs(Arrays.asList(gitRefUpdate),
                    context.getGitRepository().getId(), context.getTeamProjectReference().getId().toString());

            // check returned results
            if (results.size() < 1 || !results.get(0).getSuccess()) {
                errorMessage = results.size() > 0 ? results.get(0).getCustomMessage() : TfPluginBundle.KEY_CREATE_BRANCH_ERRORS_UNEXPECTED_SERVER_ERROR;
            }
        } catch (Throwable t) {
            if (AuthHelper.isNotAuthorizedError(t)) {
                final ServerContext newContext = ServerContextManager.getInstance().updateAuthenticationInfo(context.getGitRepository().getRemoteUrl());
                if (newContext != null) {
                    //retry creating the branch with new context and authentication info
                    hasNotifiedUser = doBranchCreate(newContext);
                } else {
                    //user cancelled login, don't retry
                    errorMessage = t.getMessage();
                }
            } else {
                errorMessage = t.getMessage();
            }
        }

        if (!hasNotifiedUser) {
            // alert user to success or error in creating the branch
            if (StringUtils.isEmpty(errorMessage)) {
                logger.info("Create branch succeeded");
                VcsNotifier.getInstance(project).notifyImportantInfo(TfPluginBundle.message(TfPluginBundle.KEY_CREATE_BRANCH_DIALOG_SUCCESSFUL_TITLE),
                        TfPluginBundle.message(TfPluginBundle.KEY_CREATE_BRANCH_DIALOG_SUCCESSFUL_DESCRIPTION, getBranchName()));
            } else {
                logger.warn("Create branch failed", errorMessage);
                VcsNotifier.getInstance(project).notifyError(TfPluginBundle.message(TfPluginBundle.KEY_CREATE_BRANCH_DIALOG_FAILED_TITLE),
                        TfPluginBundle.message(TfPluginBundle.KEY_CREATE_BRANCH_ERRORS_BRANCH_CREATE_FAILED, errorMessage));
            }

            // Add Telemetry for the create call along with it's success/failure
            TfsTelemetryHelper.getInstance().sendEvent(CREATE_BRANCH_ACTION, new TfsTelemetryHelper.PropertyMapBuilder()
                    .currentOrActiveContext(context)
                    .actionName(CREATE_BRANCH_ACTION)
                    .success(StringUtils.isEmpty(errorMessage))
                    .message(errorMessage).build());

            hasNotifiedUser = true;
        }
        return hasNotifiedUser;
    }
}
