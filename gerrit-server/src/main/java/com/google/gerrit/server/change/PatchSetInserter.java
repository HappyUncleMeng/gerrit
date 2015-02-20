// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.server.change;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.SetMultimap;
import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetInfo;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ApprovalCopier;
import com.google.gerrit.server.ApprovalsUtil;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.BanCommit;
import com.google.gerrit.server.git.BatchUpdate;
import com.google.gerrit.server.git.GroupCollector;
import com.google.gerrit.server.git.BatchUpdate.ChangeContext;
import com.google.gerrit.server.git.BatchUpdate.Context;
import com.google.gerrit.server.git.BatchUpdate.RepoContext;
import com.google.gerrit.server.git.UpdateException;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidators;
import com.google.gerrit.server.mail.ReplacePatchSetSender;
import com.google.gerrit.server.notedb.ReviewerState;
import com.google.gerrit.server.patch.PatchSetInfoFactory;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.ChangeModifiedException;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.ssh.NoSshInfo;
import com.google.gerrit.server.ssh.SshInfo;
import com.google.gwtorm.server.AtomicUpdate;
import com.google.gwtorm.server.OrmException;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;

public class PatchSetInserter {
  private static final Logger log =
      LoggerFactory.getLogger(PatchSetInserter.class);

  public static interface Factory {
    PatchSetInserter create(Repository git, RevWalk revWalk, ChangeControl ctl,
        RevCommit commit);
    PatchSetInserter create(BatchUpdate batchUpdate, ChangeControl ctl,
        RevCommit commit);
  }

  /**
   * Whether to use {@link CommitValidators#validateForGerritCommits},
   * {@link CommitValidators#validateForReceiveCommits}, or no commit
   * validation.
   */
  public static enum ValidatePolicy {
    GERRIT, RECEIVE_COMMITS, NONE
  }

  private final ChangeHooks hooks;
  private final PatchSetInfoFactory patchSetInfoFactory;
  private final ReviewDb db;
  private final BatchUpdate.Factory batchUpdateFactory;
  private final ChangeControl.GenericFactory ctlFactory;
  private final CommitValidators.Factory commitValidatorsFactory;
  private final ReplacePatchSetSender.Factory replacePatchSetFactory;
  private final ApprovalsUtil approvalsUtil;
  private final ApprovalCopier approvalCopier;
  private final ChangeMessagesUtil cmUtil;

  private final RevCommit commit;
  private final ChangeControl ctl;
  private final IdentifiedUser user;
  private final BatchUpdate batchUpdate;

  private Repository git;
  private RevWalk revWalk;
  private PatchSet patchSet;
  private ChangeMessage changeMessage;
  private SshInfo sshInfo;
  private ValidatePolicy validatePolicy = ValidatePolicy.GERRIT;
  private boolean draft;
  private Iterable<String> groups;
  private boolean runHooks = true;
  private boolean sendMail = true;
  private Account.Id uploader;
  private boolean allowClosed;

  @AssistedInject
  public PatchSetInserter(ChangeHooks hooks,
      ReviewDb db,
      ChangeControl.GenericFactory ctlFactory,
      ApprovalsUtil approvalsUtil,
      ApprovalCopier approvalCopier,
      ChangeMessagesUtil cmUtil,
      PatchSetInfoFactory patchSetInfoFactory,
      CommitValidators.Factory commitValidatorsFactory,
      ReplacePatchSetSender.Factory replacePatchSetFactory,
      @Assisted BatchUpdate batchUpdate,
      @Assisted ChangeControl ctl,
      @Assisted RevCommit commit) {
    this.hooks = hooks;
    this.db = db;
    this.batchUpdateFactory = null;
    this.ctlFactory = ctlFactory;
    this.approvalsUtil = approvalsUtil;
    this.approvalCopier = approvalCopier;
    this.cmUtil = cmUtil;
    this.patchSetInfoFactory = patchSetInfoFactory;
    this.commitValidatorsFactory = commitValidatorsFactory;
    this.replacePatchSetFactory = replacePatchSetFactory;

    this.batchUpdate = batchUpdate;
    this.commit = commit;
    this.ctl = ctl;
    this.user = checkUser(ctl);
  }

  @AssistedInject
  public PatchSetInserter(ChangeHooks hooks,
      ReviewDb db,
      BatchUpdate.Factory batchUpdateFactory,
      ChangeControl.GenericFactory ctlFactory,
      ApprovalsUtil approvalsUtil,
      ApprovalCopier approvalCopier,
      ChangeMessagesUtil cmUtil,
      PatchSetInfoFactory patchSetInfoFactory,
      CommitValidators.Factory commitValidatorsFactory,
      ReplacePatchSetSender.Factory replacePatchSetFactory,
      @Assisted Repository git,
      @Assisted RevWalk revWalk,
      @Assisted ChangeControl ctl,
      @Assisted RevCommit commit) {
    this.hooks = hooks;
    this.db = db;
    this.batchUpdateFactory = batchUpdateFactory;
    this.ctlFactory = ctlFactory;
    this.approvalsUtil = approvalsUtil;
    this.approvalCopier = approvalCopier;
    this.cmUtil = cmUtil;
    this.patchSetInfoFactory = patchSetInfoFactory;
    this.commitValidatorsFactory = commitValidatorsFactory;
    this.replacePatchSetFactory = replacePatchSetFactory;

    this.batchUpdate = null;
    this.git = git;
    this.revWalk = revWalk;
    this.commit = commit;
    this.ctl = ctl;
    this.user = checkUser(ctl);
  }

  private static IdentifiedUser checkUser(ChangeControl ctl) {
    checkArgument(ctl.getCurrentUser().isIdentifiedUser(),
        "only IdentifiedUser may create patch set on change %s",
        ctl.getChange().getId());
    return (IdentifiedUser) ctl.getCurrentUser();
  }

  public PatchSetInserter setPatchSet(PatchSet patchSet) {
    Change c = ctl.getChange();
    PatchSet.Id psid = patchSet.getId();
    checkArgument(psid.getParentKey().equals(c.getId()),
        "patch set %s not for change %s", psid, c.getId());
    checkArgument(psid.get() > c.currentPatchSetId().get(),
        "new patch set ID %s is not greater than current patch set ID %s",
        psid.get(), c.currentPatchSetId().get());
    this.patchSet = patchSet;
    return this;
  }

  public PatchSet.Id getPatchSetId() throws IOException {
    init();
    return patchSet.getId();
  }

  public PatchSet getPatchSet() {
    checkState(patchSet != null,
        "getPatchSet() only valid after patch set is created");
    return patchSet;
  }

  public PatchSetInserter setMessage(String message)
      throws OrmException, IOException {
    init();
    changeMessage = new ChangeMessage(
        new ChangeMessage.Key(
            ctl.getChange().getId(), ChangeUtil.messageUUID(db)),
        user.getAccountId(), TimeUtil.nowTs(), patchSet.getId());
    changeMessage.setMessage(message);
    return this;
  }

  public PatchSetInserter setMessage(ChangeMessage changeMessage) {
    this.changeMessage = changeMessage;
    return this;
  }

  public PatchSetInserter setSshInfo(SshInfo sshInfo) {
    this.sshInfo = sshInfo;
    return this;
  }

  public PatchSetInserter setValidatePolicy(ValidatePolicy validate) {
    this.validatePolicy = checkNotNull(validate);
    return this;
  }

  public PatchSetInserter setDraft(boolean draft) {
    this.draft = draft;
    return this;
  }

  public PatchSetInserter setGroups(Iterable<String> groups) {
    this.groups = groups;
    return this;
  }

  public PatchSetInserter setRunHooks(boolean runHooks) {
    this.runHooks = runHooks;
    return this;
  }

  public PatchSetInserter setSendMail(boolean sendMail) {
    this.sendMail = sendMail;
    return this;
  }

  public PatchSetInserter setAllowClosed(boolean allowClosed) {
    this.allowClosed = allowClosed;
    return this;
  }

  public PatchSetInserter setUploader(Account.Id uploader) {
    this.uploader = uploader;
    return this;
  }

  public Change insert() throws InvalidChangeOperationException, OrmException,
      IOException, NoSuchChangeException, UpdateException, RestApiException {
    init();
    validate();

    // TODO(dborowitz): Kill once callers are migrated.
    // Eventually, callers should always be responsible for executing.
    boolean executeBatch = false;
    BatchUpdate bu = batchUpdate;
    if (batchUpdate == null) {
      bu = batchUpdateFactory.create(
          db, ctl.getChange().getProject(), patchSet.getCreatedOn());
      executeBatch = true;
    }

    Op op = new Op();
    try {
      bu.addOp(ctl, op);
      if (!messageIsForChange()) {
        commitMessageNotForChange(bu);
      }
      if (executeBatch) {
        bu.execute();
      }
    } finally {
      if (executeBatch) {
        bu.close();
      }
    }
    return op.change;
  }

  private class Op extends BatchUpdate.Op {
    private Change change;
    private SetMultimap<ReviewerState, Account.Id> oldReviewers;

    @Override
    public void updateRepo(RepoContext ctx) throws IOException {
      ctx.getBatchRefUpdate().addCommand(new ReceiveCommand(ObjectId.zeroId(),
          commit, patchSet.getRefName(), ReceiveCommand.Type.CREATE));
    }

    @Override
    public void updateChange(ChangeContext ctx) throws OrmException,
        InvalidChangeOperationException {
      change = ctx.readChange();
      Change.Id id = change.getId();
      final PatchSet.Id currentPatchSetId = change.currentPatchSetId();
      if (!change.getStatus().isOpen() && !allowClosed) {
        throw new InvalidChangeOperationException(String.format(
            "Change %s is closed", change.getId()));
      }

      ChangeUtil.insertAncestors(db, patchSet.getId(), commit);
      if (groups != null) {
        patchSet.setGroups(groups);
      } else {
        patchSet.setGroups(GroupCollector.getCurrentGroups(db, change));
      }
      db.patchSets().insert(Collections.singleton(patchSet));

      if (sendMail) {
        oldReviewers = approvalsUtil.getReviewers(db, ctl.getNotes());
      }

      // TODO(dborowitz): Throw ResourceConflictException instead of using
      // AtomicUpdate.
      change = db.changes().atomicUpdate(id, new AtomicUpdate<Change>() {
        @Override
        public Change update(Change change) {
          if (change.getStatus().isClosed() && !allowClosed) {
            return null;
          }
          if (!change.currentPatchSetId().equals(currentPatchSetId)) {
            return null;
          }
          if (change.getStatus() != Change.Status.DRAFT && !allowClosed) {
            change.setStatus(Change.Status.NEW);
          }
          change.setCurrentPatchSet(patchSetInfoFactory.get(commit,
              patchSet.getId()));
          ChangeUtil.updated(change);
          return change;
        }
      });
      if (change == null) {
        throw new ChangeModifiedException(String.format(
            "Change %s was modified", id));
      }

      approvalCopier.copy(db, ctl, patchSet);
      if (messageIsForChange()) {
        cmUtil.addChangeMessage(db, ctx.getChangeUpdate(), changeMessage);
      }
    }

    @Override
    public void postUpdate(Context ctx) throws OrmException {
      if (sendMail) {
        try {
          PatchSetInfo info =
              patchSetInfoFactory.get(commit, patchSet.getId());
          ReplacePatchSetSender cm = replacePatchSetFactory.create(
              change.getId());
          cm.setFrom(user.getAccountId());
          cm.setPatchSet(patchSet, info);
          cm.setChangeMessage(changeMessage);
          cm.addReviewers(oldReviewers.get(ReviewerState.REVIEWER));
          cm.addExtraCC(oldReviewers.get(ReviewerState.CC));
          cm.send();
        } catch (Exception err) {
          log.error("Cannot send email for new patch set on change "
              + change.getId(), err);
        }
      }

      if (runHooks) {
        hooks.doPatchsetCreatedHook(change, patchSet, ctx.getDb());
      }
    }
  }

  private void commitMessageNotForChange(BatchUpdate bu)
      throws NoSuchChangeException, OrmException {
    if (changeMessage == null) {
      return;
    }
    bu.addOp(ctlFactory.controlFor(
        changeMessage.getPatchSetId().getParentKey(), user), new Op() {
      @Override
      public void updateChange(ChangeContext ctx) throws OrmException {
        cmUtil.addChangeMessage(
            ctx.getDb(), ctx.getChangeUpdate(), changeMessage);
      }
    });
  }

  private void init() throws IOException {
    if (git == null) {
      git = batchUpdate.getRepository();
      revWalk = batchUpdate.getRevWalk();
    }
    if (sshInfo == null) {
      sshInfo = new NoSshInfo();
    }
    if (patchSet == null) {
      patchSet = new PatchSet(
          ChangeUtil.nextPatchSetId(git, ctl.getChange().currentPatchSetId()));
      patchSet.setCreatedOn(TimeUtil.nowTs());
      patchSet.setUploader(ctl.getChange().getOwner());
      patchSet.setRevision(new RevId(commit.name()));
    }
    patchSet.setDraft(draft);
    if (uploader != null) {
      patchSet.setUploader(uploader);
    }
  }

  private void validate() throws InvalidChangeOperationException, IOException {
    CommitValidators cv =
        commitValidatorsFactory.create(ctl.getRefControl(), sshInfo, git);

    String refName = patchSet.getRefName();
    CommitReceivedEvent event = new CommitReceivedEvent(
        new ReceiveCommand(
            ObjectId.zeroId(),
            commit.getId(),
            refName.substring(0, refName.lastIndexOf('/') + 1) + "new"),
        ctl.getProjectControl().getProject(), ctl.getRefControl().getRefName(),
        commit, user);

    try {
      switch (validatePolicy) {
      case RECEIVE_COMMITS:
        NoteMap rejectCommits = BanCommit.loadRejectCommitsMap(git, revWalk);
        cv.validateForReceiveCommits(event, rejectCommits);
        break;
      case GERRIT:
        cv.validateForGerritCommits(event);
        break;
      case NONE:
        break;
      }
    } catch (CommitValidationException e) {
      throw new InvalidChangeOperationException(e.getMessage());
    }
  }

  private boolean messageIsForChange() {
    return changeMessage != null && changeMessage.getKey().getParentKey()
        .equals(patchSet.getId().getParentKey());
  }
}
