import org.eclipse.jgit.junit.TestRepository;
  public static final String PATCH =
      "From %s Mon Sep 17 00:00:00 2001\n" +
      "From: Administrator <admin@example.com>\n" +
      "Date: %s\n" +
      "Subject: [PATCH] test commit\n" +
      "\n" +
      "Change-Id: %s\n" +
      "---\n" +
      "\n" +
      "diff --git a/a.txt b/a.txt\n" +
      "new file mode 100644\n" +
      "index 0000000..f0eec86\n" +
      "--- /dev/null\n" +
      "+++ b/a.txt\n" +
      "@@ -0,0 +1 @@\n" +
      "+some content\n" +
      "\\ No newline at end of file\n";
        PersonIdent i,
        TestRepository<?> testRepo);
        TestRepository<?> testRepo,
        TestRepository<?> testRepo,
  private final TestRepository<?> testRepo;
  private final TestRepository<?>.CommitBuilder commitBuilder;

      @Assisted PersonIdent i,
      @Assisted TestRepository<?> testRepo) throws Exception {
        db, i, testRepo, SUBJECT, FILE_NAME, FILE_CONTENT);
      @Assisted TestRepository<?> testRepo,
      @Assisted("content") String content) throws Exception {
        db, i, testRepo, subject, fileName, content, null);
      @Assisted TestRepository<?> testRepo,
      @Nullable @Assisted("changeId") String changeId) throws Exception {
    this.testRepo = testRepo;
    if (changeId != null) {
      commitBuilder = testRepo.amendRef("HEAD")
          .insertChangeId(changeId.substring(1));
    } else {
      commitBuilder = testRepo.branch("HEAD").commit().insertChangeId();
    }
    commitBuilder.message(subject)
      .author(i)
      .committer(new PersonIdent(i, testRepo.getClock()));
  public Result to(String ref) throws Exception {
    commitBuilder.add(fileName, content);
    return execute(ref);
  public Result rm(String ref) throws Exception {
    commitBuilder.rm(fileName);
    return execute(ref);
  private Result execute(String ref) throws Exception {
    RevCommit c = commitBuilder.create();
    if (changeId == null) {
      changeId = GitUtil.getChangeId(testRepo, c).get();
      TagCommand tagCommand = testRepo.git().tag().setName(tag.name);
    return new Result(ref, pushHead(testRepo, ref, tag != null, force), c,
        subject);
    private final RevCommit commit;
    private Result(String ref, PushResult resSubj, RevCommit commit,
          queryProvider.get().byKeyPrefix(changeId));
      return changeId;
      return commit;
      return commit;
      assertThat(c.getSubject()).isEqualTo(resSubj);
      assertThat(c.getStatus()).isEqualTo(expectedStatus);
      assertThat(Strings.emptyToNull(c.getTopic())).isEqualTo(expectedTopic);
      Iterable<Account.Id> actualIds =
          approvalsUtil.getReviewers(db, notesFactory.create(c)).values();
      assertThat(actualIds).containsExactlyElementsIn(
          Sets.newHashSet(TestAccount.ids(expectedReviewers)));
    public void assertErrorStatus() {
      RemoteRefUpdate refUpdate = result.getRemoteUpdate(ref);
      assertThat(refUpdate.getStatus())
        .named(message(refUpdate))
        .isEqualTo(Status.REJECTED_OTHER_REASON);
    }

      assertThat(refUpdate.getStatus())
        .isEqualTo(expectedStatus);
      assertThat(refUpdate.getMessage()).isEqualTo(expectedMessage);