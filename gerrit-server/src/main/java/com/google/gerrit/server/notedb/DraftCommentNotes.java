// Copyright (C) 2014 The Android Open Source Project
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

package com.google.gerrit.server.notedb;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.gerrit.server.notedb.NoteDbTable.CHANGES;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Comment;
import com.google.gerrit.reviewdb.client.PatchLineComment;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.RepoRefCache;
import com.google.gerrit.server.notedb.NoteDbChangeState.PrimaryStorage;
import com.google.gerrit.server.notedb.NoteDbUpdateManager.StagedResult;
import com.google.gerrit.server.notedb.rebuild.ChangeRebuilder;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * View of the draft comments for a single {@link Change} based on the log of
 * its drafts branch.
 */
public class DraftCommentNotes extends AbstractChangeNotes<DraftCommentNotes> {
  private static final Logger log =
      LoggerFactory.getLogger(DraftCommentNotes.class);

  public interface Factory {
    DraftCommentNotes create(Change change, Account.Id accountId);
    DraftCommentNotes createWithAutoRebuildingDisabled(
        Change.Id changeId, Account.Id accountId);
  }

  private final Change change;
  private final Account.Id author;
  private final NoteDbUpdateManager.Result rebuildResult;

  private ImmutableListMultimap<RevId, Comment> comments;
  private RevisionNoteMap<ChangeRevisionNote> revisionNoteMap;

  @AssistedInject
  DraftCommentNotes(
      Args args,
      @Assisted Change change,
      @Assisted Account.Id author) {
    this(args, change, author, true, null);
  }

  @AssistedInject
  DraftCommentNotes(
      Args args,
      @Assisted Change.Id changeId,
      @Assisted Account.Id author) {
    // PrimaryStorage is unknown; this should only called by
    // PatchLineCommentsUtil#draftByAuthor, which can live with this.
    super(args, changeId, null, false);
    this.change = null;
    this.author = author;
    this.rebuildResult = null;
  }

  DraftCommentNotes(
      Args args,
      Change change,
      Account.Id author,
      boolean autoRebuild,
      NoteDbUpdateManager.Result rebuildResult) {
    super(args, change.getId(), PrimaryStorage.of(change), autoRebuild);
    this.change = change;
    this.author = author;
    this.rebuildResult = rebuildResult;
  }

  RevisionNoteMap<ChangeRevisionNote> getRevisionNoteMap() {
    return revisionNoteMap;
  }

  public Account.Id getAuthor() {
    return author;
  }

  public ImmutableListMultimap<RevId, Comment> getComments() {
    return comments;
  }

  public boolean containsComment(Comment c) {
    for (Comment existing : comments.values()) {
      if (c.key.equals(existing.key)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected String getRefName() {
    return RefNames.refsDraftComments(getChangeId(), author);
  }

  @Override
  protected void onLoad(LoadHandle handle)
      throws IOException, ConfigInvalidException {
    ObjectId rev = handle.id();
    if (rev == null) {
      loadDefaults();
      return;
    }

    RevCommit tipCommit = handle.walk().parseCommit(rev);
    ObjectReader reader = handle.walk().getObjectReader();
    revisionNoteMap = RevisionNoteMap.parse(
        args.noteUtil, getChangeId(), reader, NoteMap.read(reader, tipCommit),
        PatchLineComment.Status.DRAFT);
    Multimap<RevId, Comment> cs = ArrayListMultimap.create();
    for (ChangeRevisionNote rn : revisionNoteMap.revisionNotes.values()) {
      for (Comment c : rn.getComments()) {
        cs.put(new RevId(c.revId), c);
      }
    }
    comments = ImmutableListMultimap.copyOf(cs);
  }

  @Override
  protected void loadDefaults() {
    comments = ImmutableListMultimap.of();
  }

  @Override
  public Project.NameKey getProjectName() {
    return args.allUsers;
  }

  @Override
  protected LoadHandle openHandle(Repository repo) throws IOException {
    if (rebuildResult != null) {
      StagedResult sr = checkNotNull(rebuildResult.staged());
      return LoadHandle.create(
          ChangeNotesCommit.newStagedRevWalk(repo, sr.allUsersObjects()),
          findNewId(sr.allUsersCommands(), getRefName()));
    } else if (change != null && autoRebuild) {
      NoteDbChangeState state = NoteDbChangeState.parse(change);
      // Only check if this particular user's drafts are up to date, to avoid
      // reading unnecessary refs.
      if (!NoteDbChangeState.areDraftsUpToDate(
          state, new RepoRefCache(repo), getChangeId(), author)) {
        return rebuildAndOpen(repo);
      }
    }
    return super.openHandle(repo);
  }

  private static ObjectId findNewId(
      Iterable<ReceiveCommand> cmds, String refName) {
    for (ReceiveCommand cmd : cmds) {
      if (cmd.getRefName().equals(refName)) {
        return cmd.getNewId();
      }
    }
    return null;
  }

  private LoadHandle rebuildAndOpen(Repository repo) throws IOException {
    Timer1.Context timer = args.metrics.autoRebuildLatency.start(CHANGES);
    try {
      Change.Id cid = getChangeId();
      ReviewDb db = args.db.get();
      ChangeRebuilder rebuilder = args.rebuilder.get();
      NoteDbUpdateManager.Result r;
      try (NoteDbUpdateManager manager = rebuilder.stage(db, cid)) {
        if (manager == null) {
          return super.openHandle(repo); // May be null in tests.
        }
        r = manager.stageAndApplyDelta(change);
        try {
          rebuilder.execute(db, cid, manager);
          repo.scanForRepoChanges();
        } catch (OrmException | IOException e) {
          // See ChangeNotes#rebuildAndOpen.
          log.debug("Rebuilding change {} via drafts failed: {}",
              getChangeId(), e.getMessage());
          args.metrics.autoRebuildFailureCount.increment(CHANGES);
          checkNotNull(r.staged());
          return LoadHandle.create(
              ChangeNotesCommit.newStagedRevWalk(
                  repo, r.staged().allUsersObjects()),
              draftsId(r));
        }
      }
      return LoadHandle.create(ChangeNotesCommit.newRevWalk(repo), draftsId(r));
    } catch (NoSuchChangeException e) {
      return super.openHandle(repo);
    } catch (OrmException e) {
      throw new IOException(e);
    } finally {
      log.debug("Rebuilt change {} in {} in {} ms via drafts",
          getChangeId(),
          change != null
              ? "project " + change.getProject()
              : "unknown project",
          TimeUnit.MILLISECONDS.convert(timer.stop(), TimeUnit.NANOSECONDS));
    }
  }

  private ObjectId draftsId(NoteDbUpdateManager.Result r) {
    checkNotNull(r);
    checkNotNull(r.newState());
    return r.newState().getDraftIds().get(author);
  }

  @VisibleForTesting
  NoteMap getNoteMap() {
    return revisionNoteMap != null ? revisionNoteMap.noteMap : null;
  }
}
