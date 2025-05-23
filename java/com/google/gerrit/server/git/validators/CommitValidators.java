// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.git.validators;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.gerrit.entities.Change.CHANGE_ID_PATTERN;
import static com.google.gerrit.entities.RefNames.REFS_CHANGES;
import static com.google.gerrit.entities.RefNames.REFS_CONFIG;
import static com.google.gerrit.server.git.validators.CommitValidationInfo.NO_METADATA;
import static com.google.gerrit.server.project.ProjectCache.illegalState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.common.FooterConstants;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.BooleanProjectConfig;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.metrics.Counter2;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.ValidationError;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.patch.DiffNotAvailableException;
import com.google.gerrit.server.patch.gitdiff.ModifiedFile;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.plugincontext.PluginSetEntryContext;
import com.google.gerrit.server.project.LabelConfigValidator;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectConfig;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.approval.ApprovalQueryBuilder;
import com.google.gerrit.server.ssh.HostKey;
import com.google.gerrit.server.ssh.SshInfo;
import com.google.gerrit.server.util.MagicBranch;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.FooterKey;
import org.eclipse.jgit.revwalk.FooterLine;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.SystemReader;

/**
 * Represents a list of {@link CommitValidationListener}s to run for a push to one branch of one
 * project.
 */
public class CommitValidators {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final Pattern NEW_PATCHSET_PATTERN =
      Pattern.compile("^" + REFS_CHANGES + "(?:[0-9][0-9]/)?([1-9][0-9]*)(?:/[1-9][0-9]*)?$");

  @Singleton
  public static class Factory {
    private final PersonIdent gerritIdent;
    private final DynamicItem<UrlFormatter> urlFormatter;
    private final PluginSetContext<CommitValidationListener> pluginValidators;
    private final AllUsersName allUsers;
    private final AllProjectsName allProjects;
    private final ProjectCache projectCache;
    private final ProjectConfig.Factory projectConfigFactory;
    private final Config config;
    private final ChangeUtil changeUtil;
    private final MetricMaker metricMaker;
    private final ApprovalQueryBuilder approvalQueryBuilder;
    private final PluginSetContext<CommitValidationInfoListener> commitValidationInfoListeners;

    @Inject
    Factory(
        @GerritPersonIdent PersonIdent gerritIdent,
        DynamicItem<UrlFormatter> urlFormatter,
        @GerritServerConfig Config config,
        PluginSetContext<CommitValidationListener> pluginValidators,
        AllUsersName allUsers,
        AllProjectsName allProjects,
        ProjectCache projectCache,
        ProjectConfig.Factory projectConfigFactory,
        ChangeUtil changeUtil,
        MetricMaker metricMaker,
        ApprovalQueryBuilder approvalQueryBuilder,
        PluginSetContext<CommitValidationInfoListener> commitValidationInfoListeners) {
      this.gerritIdent = gerritIdent;
      this.urlFormatter = urlFormatter;
      this.config = config;
      this.pluginValidators = pluginValidators;
      this.allUsers = allUsers;
      this.allProjects = allProjects;
      this.projectCache = projectCache;
      this.projectConfigFactory = projectConfigFactory;
      this.changeUtil = changeUtil;
      this.metricMaker = metricMaker;
      this.approvalQueryBuilder = approvalQueryBuilder;
      this.commitValidationInfoListeners = commitValidationInfoListeners;
    }

    public CommitValidators forReceiveCommits(
        PermissionBackend.ForProject forProject,
        BranchNameKey branch,
        IdentifiedUser user,
        SshInfo sshInfo,
        NoteMap rejectCommits,
        RevWalk rw,
        @Nullable Change change,
        boolean skipValidation) {
      PermissionBackend.ForRef perm = forProject.ref(branch.branch());
      ProjectState projectState =
          projectCache.get(branch.project()).orElseThrow(illegalState(branch.project()));
      ImmutableList.Builder<CommitValidationListener> validators = ImmutableList.builder();
      validators
          .add(new UploadMergesPermissionValidator(perm))
          .add(new ProjectStateValidationListener(projectState))
          .add(new AmendedGerritMergeCommitValidationListener(perm, gerritIdent))
          .add(new AuthorUploaderValidator(user, perm, urlFormatter.get()))
          .add(new FileCountValidator(config, urlFormatter.get(), metricMaker))
          .add(new CommitterUploaderValidator(user, perm, urlFormatter.get()))
          .add(new SignedOffByValidator(user, perm, projectState))
          .add(
              new ChangeIdValidator(
                  changeUtil, projectState, user, urlFormatter.get(), config, sshInfo, change))
          .add(new ConfigValidator(projectConfigFactory, branch, user, rw, allUsers, allProjects))
          .add(new BannedCommitsValidator(rejectCommits));

      Iterator<PluginSetEntryContext<CommitValidationListener>> pluginValidatorsIt =
          pluginValidators.iterator();
      while (pluginValidatorsIt.hasNext()) {
        validators.add(skippablePluginValidator(pluginValidatorsIt.next().get(), skipValidation));
      }

      validators
          .add(new GroupCommitValidator(allUsers))
          .add(new LabelConfigValidator(approvalQueryBuilder));

      return new CommitValidators(commitValidationInfoListeners, validators.build());
    }

    public CommitValidators forGerritCommits(
        PermissionBackend.ForProject forProject,
        BranchNameKey branch,
        IdentifiedUser user,
        SshInfo sshInfo,
        RevWalk rw,
        @Nullable Change change) {
      PermissionBackend.ForRef perm = forProject.ref(branch.branch());
      ProjectState projectState =
          projectCache.get(branch.project()).orElseThrow(illegalState(branch.project()));
      ImmutableList.Builder<CommitValidationListener> validators = ImmutableList.builder();
      validators
          .add(new UploadMergesPermissionValidator(perm))
          .add(new ProjectStateValidationListener(projectState))
          .add(new AmendedGerritMergeCommitValidationListener(perm, gerritIdent))
          .add(new AuthorUploaderValidator(user, perm, urlFormatter.get()))
          .add(new FileCountValidator(config, urlFormatter.get(), metricMaker))
          .add(new SignedOffByValidator(user, perm, projectState))
          .add(
              new ChangeIdValidator(
                  changeUtil, projectState, user, urlFormatter.get(), config, sshInfo, change))
          .add(new ConfigValidator(projectConfigFactory, branch, user, rw, allUsers, allProjects));

      Iterator<PluginSetEntryContext<CommitValidationListener>> pluginValidatorsIt =
          pluginValidators.iterator();
      while (pluginValidatorsIt.hasNext()) {
        validators.add(pluginValidatorsIt.next().get());
      }

      validators
          .add(new GroupCommitValidator(allUsers))
          .add(new LabelConfigValidator(approvalQueryBuilder));

      return new CommitValidators(commitValidationInfoListeners, validators.build());
    }

    public CommitValidators forMergedCommits(
        PermissionBackend.ForProject forProject, BranchNameKey branch, IdentifiedUser user) {
      // Generally only include validators that are based on permissions of the
      // user creating a change for a merged commit; generally exclude
      // validators that would require amending the change in order to correct.
      //
      // Examples:
      //  - Change-Id and Signed-off-by can't be added to an already-merged
      //    commit.
      //  - If the commit is banned, we can't ban it here. In fact, creating a
      //    review of a previously merged and recently-banned commit is a use
      //    case for post-commit code review: so reviewers have a place to
      //    discuss what to do about it.
      //  - Plugin validators may do things like require certain commit message
      //    formats, so we play it safe and exclude them.
      PermissionBackend.ForRef perm = forProject.ref(branch.branch());
      ProjectState projectState =
          projectCache.get(branch.project()).orElseThrow(illegalState(branch.project()));
      ImmutableList.Builder<CommitValidationListener> validators = ImmutableList.builder();
      validators
          .add(new UploadMergesPermissionValidator(perm))
          .add(new ProjectStateValidationListener(projectState))
          .add(new AuthorUploaderValidator(user, perm, urlFormatter.get()))
          .add(new CommitterUploaderValidator(user, perm, urlFormatter.get()));
      return new CommitValidators(commitValidationInfoListeners, validators.build());
    }

    CommitValidationListener skippablePluginValidator(
        CommitValidationListener pluginValidator, boolean skipValidation) {
      return new CommitValidationListener() {
        @Override
        public String getValidatorName() {
          return pluginValidator.getValidatorName();
        }

        @Override
        public CommitValidationInfo validateCommit(CommitReceivedEvent receiveEvent)
            throws CommitValidationException {
          if (skipValidation && !pluginValidator.shouldValidateAllCommits()) {
            return CommitValidationInfo.skippedByUser(NO_METADATA);
          }
          return pluginValidator.validateCommit(receiveEvent);
        }
      };
    }
  }

  private final PluginSetContext<CommitValidationInfoListener> commitValidationInfoListeners;
  private final List<CommitValidationListener> validators;

  @Nullable private PatchSet.Id patchSetId;
  private boolean invokeCommitValidationInfoListeners = true;

  CommitValidators(
      PluginSetContext<CommitValidationInfoListener> commitValidationInfoListeners,
      List<CommitValidationListener> validators) {
    this.commitValidationInfoListeners = commitValidationInfoListeners;
    this.validators = validators;
  }

  /**
   * Sets the patch set for which the validation is done.
   *
   * <p>If the validation is done for a commit that is not associated with a patch set (e.g. when
   * commits are pushed directly, bypassing code-review) this method doesn't need to be called.
   *
   * @param patchSetId the patch set for which the validation is done
   * @return the {@link CommitValidators} instance to allow chaining calls
   */
  @CanIgnoreReturnValue
  public CommitValidators patchSet(PatchSet.Id patchSetId) {
    this.patchSetId = patchSetId;
    return this;
  }

  /**
   * Whether the {@link CommitValidationInfoListener}s should be invoked after the validation is
   * done.
   *
   * <p>If invoking the {@link CommitValidationInfoListener}s is skipped, it's the responsibility of
   * the caller to invoke them later. For example, this makes sense when commits are validated and
   * the information about the change/patch-set (see {@link
   * #patchSet(com.google.gerrit.entities.PatchSet.Id)} is not available yet.
   *
   * @param invokeCommitValidationInfoListeners Whether the {@link CommitValidationInfoListener}s
   *     should be invoked after the validation is done.
   * @return the {@link CommitValidators} instance to allow chaining calls
   */
  @CanIgnoreReturnValue
  public CommitValidators invokeCommitValidationInfoListeners(
      boolean invokeCommitValidationInfoListeners) {
    this.invokeCommitValidationInfoListeners = invokeCommitValidationInfoListeners;
    return this;
  }

  @CanIgnoreReturnValue
  public ImmutableMap<String, CommitValidationInfo> validate(CommitReceivedEvent receiveEvent)
      throws CommitValidationException {
    ImmutableMap.Builder<String, CommitValidationInfo> validationInfosBuilder =
        ImmutableMap.builder();
    for (CommitValidationListener commitValidator : validators) {
      try {
        try (TraceTimer ignored =
            TraceContext.newTimer(
                "Running CommitValidationListener",
                Metadata.builder()
                    .className(commitValidator.getClass().getSimpleName())
                    .projectName(receiveEvent.getProjectNameKey().get())
                    .branchName(receiveEvent.getBranchNameKey().branch())
                    .commit(receiveEvent.commit.name())
                    .build())) {
          CommitValidationInfo commitValidationInfo = commitValidator.validateCommit(receiveEvent);
          logger.atFine().log(
              "commit %s has passed validator %s: %s",
              receiveEvent.commit.name(), commitValidator.getValidatorName(), commitValidationInfo);
          validationInfosBuilder.put(commitValidator.getValidatorName(), commitValidationInfo);
        }
      } catch (CommitValidationException e) {
        // Keep the old messages (and their order) in case of an exception
        ImmutableList<CommitValidationMessage> messages =
            Streams.concat(
                    validationInfosBuilder.build().values().stream()
                        .flatMap(validationInfo -> validationInfo.validationMessages().stream()),
                    e.getMessages().stream())
                .collect(toImmutableList());
        logger.atFine().withCause(e).log(
            "commit %s was rejected by validator %s: %s",
            receiveEvent.commit.name(), commitValidator.getValidatorName(), messages);
        throw new CommitValidationException(e.getMessage(), messages);
      }
    }

    ImmutableMap<String, CommitValidationInfo> validationInfos = validationInfosBuilder.build();

    if (invokeCommitValidationInfoListeners) {
      commitValidationInfoListeners.runEach(
          commitValidationInfoListener ->
              commitValidationInfoListener.commitValidated(
                  validationInfos, receiveEvent, patchSetId));
    }

    return validationInfos;
  }

  public static class ChangeIdValidator implements CommitValidationListener {
    private static final String CHANGE_ID_PREFIX = FooterConstants.CHANGE_ID.getName() + ":";
    private static final String MISSING_CHANGE_ID_MSG = "missing Change-Id in message footer";
    private static final String MISSING_SUBJECT_MSG =
        "missing subject; Change-Id must be in message footer";
    private static final String CHANGE_ID_ABOVE_FOOTER_MSG = "Change-Id must be in message footer";
    private static final String MULTIPLE_CHANGE_ID_MSG =
        "multiple Change-Id lines in message footer";
    private static final String INVALID_CHANGE_ID_MSG =
        "invalid Change-Id line format in message footer";

    @VisibleForTesting
    public static final String CHANGE_ID_MISMATCH_MSG =
        "Change-Id in message footer does not match Change-Id of target change";

    private static final Pattern CHANGE_ID = Pattern.compile(CHANGE_ID_PATTERN);

    private final ChangeUtil changeUtil;
    private final ProjectState projectState;
    private final UrlFormatter urlFormatter;
    private final String installCommitMsgHookCommand;
    private final SshInfo sshInfo;
    private final IdentifiedUser user;
    private final Change change;

    public ChangeIdValidator(
        ChangeUtil changeUtil,
        ProjectState projectState,
        IdentifiedUser user,
        UrlFormatter urlFormatter,
        Config config,
        SshInfo sshInfo,
        Change change) {
      this.changeUtil = changeUtil;
      this.projectState = projectState;
      this.user = user;
      this.urlFormatter = urlFormatter;
      installCommitMsgHookCommand = config.getString("gerrit", null, "installCommitMsgHookCommand");
      this.sshInfo = sshInfo;
      this.change = change;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      if (!shouldValidateChangeId(receiveEvent)) {
        return Collections.emptyList();
      }
      RevCommit commit = receiveEvent.commit;
      List<CommitValidationMessage> messages = new ArrayList<>();
      List<String> idList = changeUtil.getChangeIdsFromFooter(commit);

      if (idList.isEmpty()) {
        String shortMsg = commit.getShortMessage();
        if (shortMsg.startsWith(CHANGE_ID_PREFIX)
            && CHANGE_ID.matcher(shortMsg.substring(CHANGE_ID_PREFIX.length()).trim()).matches()) {
          throw new CommitValidationException(MISSING_SUBJECT_MSG);
        }
        if (commit.getFullMessage().contains("\n" + CHANGE_ID_PREFIX)) {
          messages.add(
              new CommitValidationMessage(
                  CHANGE_ID_ABOVE_FOOTER_MSG
                      + "\n"
                      + "\n"
                      + "Hint: run\n"
                      + "  git commit --amend\n"
                      + "and move 'Change-Id: Ixxx..' to the bottom on a separate line\n",
                  ValidationMessage.Type.ERROR));
          throw new CommitValidationException(CHANGE_ID_ABOVE_FOOTER_MSG, messages);
        }
        if (projectState.is(BooleanProjectConfig.REQUIRE_CHANGE_ID)) {
          messages.add(getMissingChangeIdErrorMsg(MISSING_CHANGE_ID_MSG));
          throw new CommitValidationException(MISSING_CHANGE_ID_MSG, messages);
        }
      } else if (idList.size() > 1) {
        messages.add(getMultipleChangeIdsErrorMsg(idList));
        throw new CommitValidationException(MULTIPLE_CHANGE_ID_MSG, messages);
      } else {
        String v = idList.get(0).trim();
        // Reject Change-Ids with wrong format and invalid placeholder ID from
        // Egit (I0000000000000000000000000000000000000000).
        if (!CHANGE_ID.matcher(v).matches() || v.matches("^I00*$")) {
          messages.add(getMissingChangeIdErrorMsg(INVALID_CHANGE_ID_MSG));
          throw new CommitValidationException(INVALID_CHANGE_ID_MSG, messages);
        }
        if (change != null && !v.equals(change.getKey().get())) {
          throw new CommitValidationException(CHANGE_ID_MISMATCH_MSG);
        }
      }

      return Collections.emptyList();
    }

    private static boolean shouldValidateChangeId(CommitReceivedEvent event) {
      return MagicBranch.isMagicBranch(event.command.getRefName())
          || NEW_PATCHSET_PATTERN.matcher(event.command.getRefName()).matches();
    }

    private CommitValidationMessage getMissingChangeIdErrorMsg(String errMsg) {
      return new CommitValidationMessage(
          errMsg
              + "\n"
              + "\nHint: to automatically insert a Change-Id, install the hook:\n"
              + getCommitMessageHookInstallationHint()
              + "\n"
              + "and then amend the commit:\n"
              + "  git commit --amend --no-edit\n"
              + "Finally, push your changes again\n",
          ValidationMessage.Type.ERROR);
    }

    private CommitValidationMessage getMultipleChangeIdsErrorMsg(List<String> idList) {
      return new CommitValidationMessage(
          MULTIPLE_CHANGE_ID_MSG
              + "\n"
              + "\nHint: the following Change-Ids were found:\n"
              + idList.stream()
                  .map(
                      id ->
                          "* "
                              + id
                              + " ["
                              + (CHANGE_ID.matcher(id.trim()).matches() ? "VALID" : "INVALID")
                              + "]")
                  .collect(Collectors.joining("\n"))
              + "\n",
          ValidationMessage.Type.ERROR);
    }

    private String getCommitMessageHookInstallationHint() {
      if (installCommitMsgHookCommand != null) {
        return installCommitMsgHookCommand;
      }
      final List<HostKey> hostKeys = sshInfo.getHostKeys();

      // If there are no SSH keys, the commit-msg hook must be installed via
      // HTTP(S)
      Optional<String> webUrl = urlFormatter.getWebUrl();

      String httpHook =
          String.format(
              "f=\"$(git rev-parse --git-dir)/hooks/commit-msg\"; curl -o \"$f\""
                  + " %stools/hooks/commit-msg ; chmod +x \"$f\"",
              webUrl.get());

      if (hostKeys.isEmpty()) {
        checkState(webUrl.isPresent());
        return httpHook;
      }

      // SSH keys exist, so the hook might be able to be installed with scp.
      String sshHost;
      int sshPort;
      String host = hostKeys.get(0).getHost();
      int c = host.lastIndexOf(':');
      if (0 <= c) {
        if (host.startsWith("*:")) {
          checkState(webUrl.isPresent());
          sshHost = getGerritHost(webUrl.get());
        } else {
          sshHost = host.substring(0, c);
        }
        sshPort = Integer.parseInt(host.substring(c + 1));
      } else {
        sshHost = host;
        sshPort = 22;
      }

      // TODO(15944): Remove once both SFTP/SCP protocol are supported.
      //
      // In newer versions of OpenSSH, the default hook installation command will fail with a
      // cryptic error because the scp binary defaults to a different protocol.
      String scpFlagHint = "(for OpenSSH >= 9.0 you need to add the flag '-O' to the scp command)";

      String sshHook =
          String.format(
              "gitdir=$(git rev-parse --git-dir); scp -p -P %d %s@%s:hooks/commit-msg"
                  + " ${gitdir}/hooks/",
              sshPort, user.getUserName().orElse("<USERNAME>"), sshHost);
      return String.format("  %s\n%s\nor, for http(s):\n  %s", sshHook, scpFlagHint, httpHook);
    }
  }

  /** Limits the number of files per change. */
  private static class FileCountValidator implements CommitValidationListener {

    private static final int FILE_COUNT_WARNING_THRESHOLD = 10_000;

    private final int maxFileCount;
    private final UrlFormatter urlFormatter;
    private final Counter2<Integer, String> metricCountManyFilesPerChange;

    FileCountValidator(Config config, UrlFormatter urlFormatter, MetricMaker metricMaker) {
      this.urlFormatter = urlFormatter;
      this.metricCountManyFilesPerChange =
          metricMaker.newCounter(
              "validation/file_count",
              new Description("Count commits with many files per change."),
              Field.ofInteger("file_count", (meta, value) -> {})
                  .description("number of files in the patchset")
                  .build(),
              Field.ofString("host_repo", (meta, value) -> {})
                  .description("host and repository of the change in the format 'host/repo'")
                  .build());
      maxFileCount = config.getInt("change", null, "maxFiles", 100_000);
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      // TODO(zieren): Refactor interface to signal the intent of the event instead of hard-coding
      // it here. Due to interface limitations, this method is called from both receive commits
      // and from main Gerrit (e.g. when publishing a change edit). This is why we need to gate the
      // early return on REFS_CHANGES (though pushes to refs/changes are not possible).
      String refName = receiveEvent.command.getRefName();
      if (!refName.startsWith("refs/for/") && !refName.startsWith(RefNames.REFS_CHANGES)) {
        // This is a direct push bypassing review. We don't need to enforce any file-count limits
        // here.
        return Collections.emptyList();
      }

      // Use DiffFormatter to compute the number of files in the change. This should be faster than
      // the previous approach of using the PatchListCache.
      try {
        long changedFiles = countChangedFiles(receiveEvent);
        if (changedFiles > maxFileCount) {
          throw new CommitValidationException(
              String.format(
                  "Exceeding maximum number of files per change (%d > %d)",
                  changedFiles, maxFileCount));
        }
        if (changedFiles > FILE_COUNT_WARNING_THRESHOLD) {
          String host = getGerritHost(urlFormatter.getWebUrl().orElse(null));
          String project = receiveEvent.project.getNameKey().get();
          logger.atWarning().log(
              "Warning: Change with %d files on host %s, project %s, ref %s",
              changedFiles, host, project, refName);

          this.metricCountManyFilesPerChange.increment(
              Math.toIntExact(changedFiles), String.format("%s/%s", host, project));
        }
      } catch (DiffNotAvailableException e) {
        // This happens e.g. for cherrypicks.
        if (!receiveEvent.command.getRefName().startsWith(REFS_CHANGES)) {
          logger.atWarning().withCause(e).log(
              "Failed to validate file count for commit: %s", receiveEvent.commit);
        }
      }
      return Collections.emptyList();
    }

    private int countChangedFiles(CommitReceivedEvent receiveEvent)
        throws DiffNotAvailableException {
      // For merge commits this will compare against auto-merge.
      Map<String, ModifiedFile> modifiedFiles =
          receiveEvent.diffOperations.loadModifiedFilesAgainstParentIfNecessary(
              receiveEvent.getProjectNameKey(),
              receiveEvent.commit,
              0,
              /* enableRenameDetection= */ true);
      // We don't want to count the COMMIT_MSG and MERGE_LIST files.
      List<ModifiedFile> modifiedFilesList =
          modifiedFiles.values().stream()
              .filter(p -> !Patch.isMagic(p.newPath().orElse("")))
              .collect(Collectors.toList());
      return modifiedFilesList.size();
    }
  }

  /** If this is the special project configuration branch, validate the config. */
  public static class ConfigValidator implements CommitValidationListener {
    private final ProjectConfig.Factory projectConfigFactory;
    private final BranchNameKey branch;
    private final IdentifiedUser user;
    private final RevWalk rw;
    private final AllUsersName allUsers;
    private final AllProjectsName allProjects;

    public ConfigValidator(
        ProjectConfig.Factory projectConfigFactory,
        BranchNameKey branch,
        IdentifiedUser user,
        RevWalk rw,
        AllUsersName allUsers,
        AllProjectsName allProjects) {
      this.projectConfigFactory = projectConfigFactory;
      this.branch = branch;
      this.user = user;
      this.rw = rw;
      this.allProjects = allProjects;
      this.allUsers = allUsers;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      if (REFS_CONFIG.equals(branch.branch())) {
        List<CommitValidationMessage> messages = new ArrayList<>();

        try {
          ProjectConfig cfg = projectConfigFactory.create(receiveEvent.project.getNameKey());
          cfg.load(rw, receiveEvent.command.getNewId());
          if (!cfg.getValidationErrors().isEmpty()) {
            addError("Invalid project configuration:", messages);
            for (ValidationError err : cfg.getValidationErrors()) {
              addError("  " + err.getMessage(), messages);
            }
            throw new CommitValidationException("invalid project configuration", messages);
          }
          if (allUsers.equals(receiveEvent.project.getNameKey())
              && !allProjects.equals(cfg.getProject().getParent(allProjects))) {
            addError("Invalid project configuration:", messages);
            addError(
                String.format("  %s must inherit from %s", allUsers.get(), allProjects.get()),
                messages);
            throw new CommitValidationException("invalid project configuration", messages);
          }
        } catch (ConfigInvalidException | IOException e) {
          if (e instanceof ConfigInvalidException && !Strings.isNullOrEmpty(e.getMessage())) {
            addError(e.getMessage(), messages);
          }
          logger.atSevere().withCause(e).log(
              "User %s tried to push an invalid project configuration %s for project %s",
              user.getLoggableName(),
              receiveEvent.command.getNewId().name(),
              receiveEvent.project.getName());
          throw new CommitValidationException("invalid project configuration", messages);
        }
      }

      return Collections.emptyList();
    }
  }

  /** Require permission to upload merge commits. */
  public static class UploadMergesPermissionValidator implements CommitValidationListener {
    private final PermissionBackend.ForRef perm;

    public UploadMergesPermissionValidator(PermissionBackend.ForRef perm) {
      this.perm = perm;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      if (receiveEvent.commit.getParentCount() <= 1) {
        return Collections.emptyList();
      }
      try {
        if (perm.test(RefPermission.MERGE)) {
          return Collections.emptyList();
        }
        throw new CommitValidationException("you are not allowed to upload merges");
      } catch (PermissionBackendException e) {
        logger.atSevere().withCause(e).log("cannot check MERGE");
        throw new CommitValidationException("internal auth error");
      }
    }
  }

  public static class SignedOffByValidator implements CommitValidationListener {
    private final IdentifiedUser user;
    private final PermissionBackend.ForRef perm;
    private final ProjectState state;

    public SignedOffByValidator(
        IdentifiedUser user, PermissionBackend.ForRef perm, ProjectState state) {
      this.user = user;
      this.perm = perm;
      this.state = state;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      if (!state.is(BooleanProjectConfig.USE_SIGNED_OFF_BY)) {
        return Collections.emptyList();
      }

      RevCommit commit = receiveEvent.commit;
      PersonIdent committer = commit.getCommitterIdent();
      PersonIdent author = commit.getAuthorIdent();

      boolean signedOffByAuthor = false;
      boolean signedOffByCommitter = false;
      boolean signedOffByMe = false;
      for (FooterLine footer : commit.getFooterLines()) {
        if (footer.matches(FooterKey.SIGNED_OFF_BY)) {
          String e = footer.getEmailAddress();
          if (e != null) {
            signedOffByAuthor |= author.getEmailAddress().equals(e);
            signedOffByCommitter |= committer.getEmailAddress().equals(e);
            signedOffByMe |= user.hasEmailAddress(e);
          }
        }
      }
      if (!signedOffByAuthor && !signedOffByCommitter && !signedOffByMe) {
        try {
          if (!perm.test(RefPermission.FORGE_COMMITTER)) {
            throw new CommitValidationException(
                "not Signed-off-by author/committer/uploader in message footer");
          }
        } catch (PermissionBackendException e) {
          logger.atSevere().withCause(e).log("cannot check FORGE_COMMITTER");
          throw new CommitValidationException("internal auth error");
        }
      }
      return Collections.emptyList();
    }
  }

  /** Require that author matches the uploader. */
  public static class AuthorUploaderValidator implements CommitValidationListener {
    private final IdentifiedUser user;
    private final PermissionBackend.ForRef perm;
    private final UrlFormatter urlFormatter;

    public AuthorUploaderValidator(
        IdentifiedUser user, PermissionBackend.ForRef perm, UrlFormatter urlFormatter) {
      this.user = user;
      this.perm = perm;
      this.urlFormatter = urlFormatter;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      PersonIdent author = receiveEvent.commit.getAuthorIdent();
      if (user.hasEmailAddress(author.getEmailAddress())) {
        return Collections.emptyList();
      }
      try {
        if (!perm.test(RefPermission.FORGE_AUTHOR)) {
          throw new CommitValidationException(
              "invalid author", invalidEmail("author", author, user, urlFormatter));
        }
        return Collections.emptyList();
      } catch (PermissionBackendException e) {
        logger.atSevere().withCause(e).log("cannot check FORGE_AUTHOR");
        throw new CommitValidationException("internal auth error");
      }
    }
  }

  /** Require that committer matches the uploader. */
  public static class CommitterUploaderValidator implements CommitValidationListener {
    private final IdentifiedUser user;
    private final PermissionBackend.ForRef perm;
    private final UrlFormatter urlFormatter;

    public CommitterUploaderValidator(
        IdentifiedUser user, PermissionBackend.ForRef perm, UrlFormatter urlFormatter) {
      this.user = user;
      this.perm = perm;
      this.urlFormatter = urlFormatter;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      PersonIdent committer = receiveEvent.commit.getCommitterIdent();
      if (user.hasEmailAddress(committer.getEmailAddress())) {
        return Collections.emptyList();
      }
      try {
        if (!perm.test(RefPermission.FORGE_COMMITTER)) {
          throw new CommitValidationException(
              "invalid committer", invalidEmail("committer", committer, user, urlFormatter));
        }
        return Collections.emptyList();
      } catch (PermissionBackendException e) {
        logger.atSevere().withCause(e).log("cannot check FORGE_COMMITTER");
        throw new CommitValidationException("internal auth error");
      }
    }
  }

  /**
   * Don't allow the user to amend a merge created by Gerrit Code Review. This seems to happen all
   * too often, due to users not paying any attention to what they are doing.
   */
  public static class AmendedGerritMergeCommitValidationListener
      implements CommitValidationListener {
    private final PermissionBackend.ForRef perm;
    private final PersonIdent gerritIdent;

    public AmendedGerritMergeCommitValidationListener(
        PermissionBackend.ForRef perm, PersonIdent gerritIdent) {
      this.perm = perm;
      this.gerritIdent = gerritIdent;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      PersonIdent author = receiveEvent.commit.getAuthorIdent();
      if (receiveEvent.commit.getParentCount() > 1
          && author.getName().equals(gerritIdent.getName())
          && author.getEmailAddress().equals(gerritIdent.getEmailAddress())) {
        try {
          // Stop authors from amending the merge commits that Gerrit itself creates.
          perm.check(RefPermission.FORGE_SERVER);
        } catch (AuthException denied) {
          throw new CommitValidationException(
              String.format(
                  "pushing merge commit %s by %s requires '%s' permission",
                  receiveEvent.commit.getId(),
                  gerritIdent.getEmailAddress(),
                  RefPermission.FORGE_SERVER.name()),
              denied);
        } catch (PermissionBackendException e) {
          logger.atSevere().withCause(e).log("cannot check FORGE_SERVER");
          throw new CommitValidationException("internal auth error");
        }
      }
      return Collections.emptyList();
    }
  }

  /** Reject banned commits. */
  public static class BannedCommitsValidator implements CommitValidationListener {
    private final NoteMap rejectCommits;

    public BannedCommitsValidator(NoteMap rejectCommits) {
      this.rejectCommits = rejectCommits;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      try {
        if (rejectCommits.contains(receiveEvent.commit)) {
          throw new CommitValidationException(
              "contains banned commit " + receiveEvent.commit.getName());
        }
        return Collections.emptyList();
      } catch (IOException e) {
        throw new CommitValidationException("error checking banned commits", e);
      }
    }
  }

  /** Rejects updates to group branches. */
  public static class GroupCommitValidator implements CommitValidationListener {
    private final AllUsersName allUsers;

    public GroupCommitValidator(AllUsersName allUsers) {
      this.allUsers = allUsers;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      // Groups are stored inside the 'All-Users' repository.
      if (!allUsers.equals(receiveEvent.project.getNameKey())) {
        return Collections.emptyList();
      }

      if (receiveEvent.command.getRefName().startsWith(MagicBranch.NEW_CHANGE)) {
        // no validation on push for review, will be checked on submit by
        // MergeValidators.GroupMergeValidator
        return Collections.emptyList();
      }

      if (RefNames.isGroupRef(receiveEvent.command.getRefName())) {
        throw new CommitValidationException("group update not allowed");
      }
      return Collections.emptyList();
    }
  }

  /** Rejects updates to projects that don't allow writes. */
  public static class ProjectStateValidationListener implements CommitValidationListener {
    private final ProjectState projectState;

    public ProjectStateValidationListener(ProjectState projectState) {
      this.projectState = projectState;
    }

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      if (projectState.statePermitsWrite()) {
        return Collections.emptyList();
      }
      throw new CommitValidationException("project state does not permit write");
    }
  }

  public static CommitValidationMessage invalidEmail(
      String type, PersonIdent who, IdentifiedUser currentUser, UrlFormatter urlFormatter) {
    StringBuilder sb = new StringBuilder();

    sb.append("email address ")
        .append(who.getEmailAddress())
        .append(" is not registered in your account, and you lack 'forge ")
        .append(type)
        .append("' permission.\n");

    if (currentUser.getEmailAddresses().isEmpty()) {
      sb.append("You have not registered any email addresses.\n");
    } else {
      sb.append("The following addresses are currently registered:\n");
      for (String address : currentUser.getEmailAddresses()) {
        sb.append("   ").append(address).append("\n");
      }
    }

    if (urlFormatter.getSettingsUrl("").isPresent()) {
      sb.append("To register an email address, visit:\n")
          .append(urlFormatter.getSettingsUrl("EmailAddresses").get())
          .append("\n\n");
    }
    return new CommitValidationMessage(sb.toString(), ValidationMessage.Type.ERROR);
  }

  /**
   * Get the Gerrit hostname.
   *
   * @return the hostname from the canonical URL if it is configured, otherwise whatever the OS says
   *     the hostname is.
   */
  private static String getGerritHost(String canonicalWebUrl) {
    if (canonicalWebUrl != null) {
      try {
        return URI.create(canonicalWebUrl).toURL().getHost();
      } catch (MalformedURLException ignored) {
        logger.atWarning().log(
            "configured canonical web URL is invalid, using system default: %s",
            ignored.getMessage());
      }
    }

    return SystemReader.getInstance().getHostname();
  }

  private static void addError(String error, List<CommitValidationMessage> messages) {
    messages.add(new CommitValidationMessage(error, ValidationMessage.Type.ERROR));
  }
}
