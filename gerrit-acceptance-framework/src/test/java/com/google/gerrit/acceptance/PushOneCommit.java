import com.google.gerrit.server.project.NoSuchChangeException;
      .committer(new PersonIdent(i, testRepo.getDate()));
  public void noParents() {
    commitBuilder.noParents();
  }

        throws OrmException, NoSuchChangeException {
        throws OrmException, NoSuchChangeException {
      Iterable<Account.Id> actualIds = approvalsUtil
          .getReviewers(db, notesFactory.createChecked(db, c))
          .values();