// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.testFramework;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.ide.GeneratedSourceFileChangeTracker;
import com.intellij.ide.GeneratedSourceFileChangeTrackerImpl;
import com.intellij.ide.highlighter.ModuleFileType;
import com.intellij.ide.highlighter.ProjectFileType;
import com.intellij.ide.startup.impl.StartupManagerImpl;
import com.intellij.idea.IdeaLogger;
import com.intellij.idea.IdeaTestApplication;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.impl.LaterInvocator;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.impl.DocumentReferenceManagerImpl;
import com.intellij.openapi.command.impl.UndoManagerImpl;
import com.intellij.openapi.command.undo.DocumentReferenceManager;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl;
import com.intellij.openapi.module.EmptyModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.project.impl.ProjectImpl;
import com.intellij.openapi.project.impl.ProjectManagerImpl;
import com.intellij.openapi.project.impl.TooManyProjectLeakedException;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ex.ProjectRootManagerEx;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.impl.VirtualFilePointerTracker;
import com.intellij.openapi.vfs.impl.jar.JarFileSystemImpl;
import com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.PsiDocumentManagerBase;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageManagerImpl;
import com.intellij.util.MemoryDumpHelper;
import com.intellij.util.PathUtil;
import com.intellij.util.PlatformUtils;
import com.intellij.util.ThrowableRunnable;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexImpl;
import com.intellij.util.indexing.IndexableSetContributor;
import com.intellij.util.ui.UIUtil;
import gnu.trove.THashSet;
import gnu.trove.TIntHashSet;
import junit.framework.TestCase;
import org.jetbrains.annotations.*;

import javax.swing.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Base class for heavy tests.
 * <p/>
 * NOTE: Because of the performance difference, we recommend plugin developers to write light tests whenever possible.
 * <p/>
 * Please see <a href="http://www.jetbrains.org/intellij/sdk/docs/basics/testing_plugins.html">Testing Plugins</a> in IntelliJ Platform SDK DevGuide.
 *
 * @author yole
 */
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace"})
public abstract class HeavyPlatformTestCase extends UsefulTestCase implements DataProvider {
  private static IdeaTestApplication ourApplication;
  private static boolean ourReportedLeakedProjects;
  protected ProjectManagerEx myProjectManager;
  protected Project myProject;
  protected Module myModule;

  protected final Collection<File> myFilesToDelete = new THashSet<>();
  private final TempFiles myTempFiles = new TempFiles(myFilesToDelete);

  protected boolean myAssertionsInTestDetected;
  public static Thread ourTestThread;
  private static TestCase ourTestCase;
  private static final long DEFAULT_TEST_TIME = 300L;
  public static long ourTestTime = DEFAULT_TEST_TIME;
  private EditorListenerTracker myEditorListenerTracker;
  private ThreadTracker myThreadTracker;

  private static boolean ourPlatformPrefixInitialized;
  private static Set<VirtualFile> ourEternallyLivingFilesCache;
  private SdkLeakTracker myOldSdks;
  private VirtualFilePointerTracker myVirtualFilePointerTracker;
  @Nullable
  private CodeStyleSettingsTracker myCodeStyleSettingsTracker;


  @NotNull
  public TempFiles getTempDir() {
    return myTempFiles;
  }

  @NotNull
  protected final VirtualFile createTestProjectStructure() throws IOException {
    return PsiTestUtil.createTestProjectStructure(myProject, myModule, myFilesToDelete);
  }

  @NotNull
  protected final VirtualFile createTestProjectStructure(String rootPath) throws Exception {
    return PsiTestUtil.createTestProjectStructure(myProject, myModule, rootPath, myFilesToDelete);
  }

  /**
   * If a temp directory is reused from some previous test run, there might be cached children in its VFS.
   * Ensure they're removed
   */
  public static void synchronizeTempDirVfs(@NotNull VirtualFile tempDir) {
    tempDir.getChildren();
    tempDir.refresh(false, true);
  }

  public static void synchronizeTempDirVfs(@NotNull Path tempDir) {
    VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(FileUtil.toSystemIndependentName(tempDir.toString()));
    // null is ok, because Path can be only generated, but not created
    if (virtualFile != null) {
      synchronizeTempDirVfs(Objects.requireNonNull(virtualFile));
    }
  }

  protected void initApplication() throws Exception {
    boolean firstTime = ourApplication == null;
    ourApplication = IdeaTestApplication.getInstance();
    ourApplication.setDataProvider(this);

    if (firstTime) {
      cleanPersistedVFSContent();
    }
    // try to remember old sdks as soon as possible after the app instantiation
    myOldSdks = new SdkLeakTracker();
  }

  private static final String[] PREFIX_CANDIDATES = {
    "Rider", "GoLand",
    null,
    "AppCode", "CLion", "CidrCommonTests",
    "DataGrip",
    "Python", "PyCharmCore",
    "Ruby",
    "PhpStorm",
    "UltimateLangXml", "Idea", "PlatformLangXml"};

  public static void doAutodetectPlatformPrefix() {
    if (ourPlatformPrefixInitialized) {
      return;
    }
    if (System.getProperty(PlatformUtils.PLATFORM_PREFIX_KEY) != null) {
      ourPlatformPrefixInitialized = true;
      return;
    }
    for (String candidate : PREFIX_CANDIDATES) {
      String markerPath = candidate != null ? "META-INF/" + candidate + "Plugin.xml" : "idea/ApplicationInfo.xml";
      URL resource = HeavyPlatformTestCase.class.getClassLoader().getResource(markerPath);
      if (resource != null) {
        if (candidate != null) {
          setPlatformPrefix(candidate);
        }
        break;
      }
    }
  }

  private static void cleanPersistedVFSContent() {
    ((PersistentFSImpl)PersistentFS.getInstance()).cleanPersistedContents();
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    File tempDir = new File(FileUtilRt.getTempDirectory());
    myFilesToDelete.add(tempDir);

    if (ourTestCase != null) {
      String message = "Previous test " + ourTestCase + " hasn't called tearDown(). Probably overridden without super call.";
      ourTestCase = null;
      fail(message);
    }
    IdeaLogger.ourErrorsOccurred = null;

    LOG.debug(getClass().getName() + ".setUp()");

    initApplication();
    if (myOldSdks == null) { // some bastard's overridden initApplication completely
      myOldSdks = new SdkLeakTracker();
    }

    myEditorListenerTracker = new EditorListenerTracker();
    myThreadTracker = new ThreadTracker();

    setUpProject();

    boolean isTrackCodeStyleChanges = !(isStressTest() ||
                                        ApplicationManager.getApplication() == null ||
                                        ApplicationManager.getApplication() instanceof MockApplication);

    myCodeStyleSettingsTracker = isTrackCodeStyleChanges ? new CodeStyleSettingsTracker(() -> CodeStyle.getDefaultSettings()) : null;
    ourTestCase = this;
    if (myProject != null) {
      ProjectManagerEx.getInstanceEx().openTestProject(myProject);
      CodeStyle.setTemporarySettings(myProject, new CodeStyleSettings());
      InjectedLanguageManagerImpl.pushInjectors(myProject);
      ((PsiDocumentManagerBase)PsiDocumentManager.getInstance(myProject)).clearUncommittedDocuments();
    }

    UIUtil.dispatchAllInvocationEvents();
    myVirtualFilePointerTracker = new VirtualFilePointerTracker();
  }

  public final Project getProject() {
    return myProject;
  }

  @NotNull
  public final PsiManager getPsiManager() {
    return PsiManager.getInstance(myProject);
  }

  public Module getModule() {
    return myModule;
  }

  protected void setUpProject() throws Exception {
    myProjectManager = ProjectManagerEx.getInstanceEx();
    assertNotNull("Cannot instantiate ProjectManager component", myProjectManager);

    myProject = doCreateProject(getProjectDirOrFile());
    myProjectManager.openTestProject(myProject);
    LocalFileSystem.getInstance().refreshIoFiles(myFilesToDelete);

    WriteAction.run(() -> ProjectRootManagerEx.getInstanceEx(myProject).mergeRootsChangesDuring(() -> {
      setUpModule();
      setUpJdk();
    }));

    LightPlatformTestCase.clearUncommittedDocuments(getProject());

    runStartupActivities();
    ((FileTypeManagerImpl)FileTypeManager.getInstance()).drainReDetectQueue();
  }

  @NotNull
  protected Project doCreateProject(@NotNull Path projectFile) throws Exception {
    return createProject(projectFile);
  }

  @NotNull
  public static Project createProject(@NotNull Path file) {
    try {
      ProjectManagerImpl projectManager = (ProjectManagerImpl)ProjectManager.getInstance();
      // in tests it is caller responsibility to refresh VFS (because often not only the project file must be refreshed, but the whole dir - so, no need to refresh several times)
      return Objects.requireNonNull(projectManager.newProject(file, null, /* useDefaultProjectSettings = */ false, /* isRefreshVfsNeeded = */ false));
    }
    catch (TooManyProjectLeakedException e) {
      if (ourReportedLeakedProjects) {
        fail("Too many projects leaked, again.");
        return null;
      }
      ourReportedLeakedProjects = true;

      reportLeakedProjects(e);
      return null;
    }
  }

  @NotNull
  public static String publishHeapDump(@NotNull String fileNamePrefix) {
    String fileName = fileNamePrefix + ".hprof.zip";
    File dumpFile = new File(System.getProperty("teamcity.build.tempDir", System.getProperty("java.io.tmpdir")), fileName);
    try {
      FileUtil.delete(dumpFile);
      MemoryDumpHelper.captureMemoryDumpZipped(dumpFile);
    }
    catch (Exception ex) {
      ex.printStackTrace();
    }
    String dumpPath = dumpFile.getAbsolutePath();
    System.out.println("##teamcity[publishArtifacts '" + dumpPath + "']");
    return dumpPath;
  }

  @Contract("_ -> fail")
  public static void reportLeakedProjects(@NotNull TooManyProjectLeakedException e) {
    TIntHashSet hashCodes = new TIntHashSet();
    for (Project project : e.getLeakedProjects()) {
      hashCodes.add(System.identityHashCode(project));
    }

    String dumpPath = publishHeapDump("leakedProjects");

    StringBuilder leakers = new StringBuilder();
    leakers.append("Too many projects leaked: \n");
    LeakHunter
      .processLeaks(LeakHunter.allRoots(), ProjectImpl.class, p -> hashCodes.contains(System.identityHashCode(p)), (leaked, backLink) -> {
        int hashCode = System.identityHashCode(leaked);
        leakers.append("Leaked project found:").append(leaked).append("; hash: ").append(hashCode).append("; place: ")
          .append(getCreationPlace(leaked)).append("\n");
        leakers.append(backLink).append("\n");
        leakers.append(";-----\n");

        hashCodes.remove(hashCode);

        return !hashCodes.isEmpty();
      });

    fail(leakers + "\nPlease see '" + dumpPath + "' for a memory dump");
  }

  @NotNull
  @TestOnly
  public static String getCreationPlace(@NotNull Project project) {
    Object base;
    try {
      base = project.isDisposed() ? "" : project.getBaseDir();
    }
    catch (Exception e) {
      base = " (" + e + " while getting base dir)";
    }
    String place = project instanceof ProjectImpl ? ((ProjectImpl)project).getCreationTrace() : null;
    return project + " " + (place == null ? "" : place) + base;
  }

  protected void runStartupActivities() {
    final StartupManagerImpl startupManager = (StartupManagerImpl)StartupManager.getInstance(myProject);
    startupManager.runStartupActivities();
    startupManager.startCacheUpdate();
    startupManager.runPostStartupActivities();
  }

  @NotNull
  protected Path getProjectDirOrFile() {
    return getProjectDirOrFile(false);
  }

  protected boolean isCreateProjectFileExplicitly() {
    return true;
  }

  @NotNull
  protected final Path getProjectDirOrFile(boolean isDirectoryBasedProject) {
    if (!isDirectoryBasedProject && isCreateProjectFileExplicitly()) {
      try {
        File tempFile = FileUtil.createTempFile(getName(), ProjectFileType.DOT_DEFAULT_EXTENSION);
        myFilesToDelete.add(tempFile);
        return tempFile.toPath();
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    Path tempFile = TemporaryDirectory.generateTemporaryPath(FileUtil.sanitizeFileName(getName(), false) + (isDirectoryBasedProject ? "" : ProjectFileType.DOT_DEFAULT_EXTENSION));
    myFilesToDelete.add(tempFile.toFile());
    return tempFile;
  }

  protected void setUpModule() {
    try {
      WriteCommandAction.writeCommandAction(getProject()).run(() -> myModule = createMainModule());
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  protected Module createMainModule() throws IOException {
    return createModule(myProject.getName());
  }

  @NotNull
  protected Module createModule(@NonNls @NotNull String moduleName) {
    return doCreateRealModule(moduleName);
  }

  @NotNull
  protected Module doCreateRealModule(@NotNull String moduleName) {
    return doCreateRealModuleIn(moduleName, myProject, getModuleType());
  }

  @NotNull
  protected Module doCreateRealModuleIn(@NotNull String moduleName, @NotNull Project project, @NotNull ModuleType moduleType) {
    return createModuleAt(moduleName, project, moduleType, Objects.requireNonNull(project.getBasePath()));
  }

  @NotNull
  protected Module createModuleAt(@NotNull String moduleName,
                                  @NotNull Project project,
                                  @NotNull ModuleType moduleType,
                                  @NotNull String path) {
    if (isCreateProjectFileExplicitly()) {
      File moduleFile = new File(FileUtil.toSystemDependentName(path), moduleName + ModuleFileType.DOT_DEFAULT_EXTENSION);
      FileUtil.createIfDoesntExist(moduleFile);
      myFilesToDelete.add(moduleFile);
      return WriteAction.computeAndWait(() -> {
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(moduleFile);
        assertNotNull(virtualFile);
        Module module = ModuleManager.getInstance(project).newModule(virtualFile.getPath(), moduleType.getId());
        module.getModuleFile();
        return module;
      });
    }

    ModuleManager moduleManager = ModuleManager.getInstance(project);
    return WriteAction.computeAndWait(
      () -> moduleManager.newModule(path + File.separatorChar + moduleName + ModuleFileType.DOT_DEFAULT_EXTENSION, moduleType.getId()));
  }

  @NotNull
  protected ModuleType getModuleType() {
    return EmptyModuleType.getInstance();
  }

  public static void cleanupApplicationCaches(@Nullable Project project) {
    if (ApplicationManager.getApplication() == null) {
      return;
    }

    UndoManagerImpl globalInstance = (UndoManagerImpl)UndoManager.getGlobalInstance();
    if (globalInstance != null) {
      globalInstance.dropHistoryInTests();
    }

    if (project != null && !project.isDisposed()) {
      ((UndoManagerImpl)UndoManager.getInstance(project)).dropHistoryInTests();
      ((DocumentReferenceManagerImpl)DocumentReferenceManager.getInstance()).cleanupForNextTest();

      ((PsiManagerImpl)PsiManager.getInstance(project)).cleanupForNextTest();
    }

    final ProjectManagerEx projectManager = ProjectManagerEx.getInstanceEx();
    assert projectManager != null : "The ProjectManager is not initialized yet";
    if (projectManager.isDefaultProjectInitialized()) {
      Project defaultProject = projectManager.getDefaultProject();
      ((PsiManagerImpl)PsiManager.getInstance(defaultProject)).cleanupForNextTest();
    }

    NonBlockingReadActionImpl.cancelAllTasks();

    ((FileBasedIndexImpl)FileBasedIndex.getInstance()).cleanupForNextTest();

    LocalFileSystemImpl localFileSystem = (LocalFileSystemImpl)LocalFileSystem.getInstance();
    if (localFileSystem != null) {
      localFileSystem.cleanupForNextTest();
    }
  }

  @NotNull
  private static Set<VirtualFile> eternallyLivingFiles() {
    if (ourEternallyLivingFilesCache != null) {
      return ourEternallyLivingFilesCache;
    }

    Set<VirtualFile> survivors = new HashSet<>();

    for (IndexableSetContributor contributor : IndexableSetContributor.EP_NAME.getExtensions()) {
      for (VirtualFile file : IndexableSetContributor.getRootsToIndex(contributor)) {
        registerSurvivor(survivors, file);
      }
    }

    ourEternallyLivingFilesCache = survivors;
    return survivors;
  }

  public static void addSurvivingFiles(@NotNull Collection<? extends VirtualFile> files) {
    for (VirtualFile each : files) {
      registerSurvivor(eternallyLivingFiles(), each);
    }
  }

  private static void registerSurvivor(@NotNull Set<? super VirtualFile> survivors, @NotNull VirtualFile file) {
    addSubTree(file, survivors);
    while (file != null && survivors.add(file)) {
      file = file.getParent();
    }
  }

  private static void addSubTree(@NotNull VirtualFile root, @NotNull Set<? super VirtualFile> to) {
    if (root instanceof VirtualDirectoryImpl) {
      for (VirtualFile child : ((VirtualDirectoryImpl)root).getCachedChildren()) {
        if (child instanceof VirtualDirectoryImpl) {
          to.add(child);
          addSubTree(child, to);
        }
      }
    }
  }

  @Override
  protected void tearDown() throws Exception {
    Project project = myProject;
    if (project != null && !project.isDisposed()) {
      AutoPopupController.getInstance(project).cancelAllRequests(); // clear "show param info" delayed requests leaking project
      waitForProjectLeakingThreads(project, 10, TimeUnit.SECONDS);
    }
    // don't use method references here to make stack trace reading easier
    //noinspection Convert2MethodRef
    new RunAll()
      .append(() -> disposeRootDisposable())
      .append(() -> {
        if (project != null) {
          LightPlatformTestCase.doTearDown(project, ourApplication);
        }
      })
      .append(() -> disposeProject())
      .append(() -> UIUtil.dispatchAllInvocationEvents())
      .append(() -> {
        if (myCodeStyleSettingsTracker != null) {
          myCodeStyleSettingsTracker.checkForSettingsDamage();
        }
      })
      .append(() -> {
        if (project != null) {
          InjectedLanguageManagerImpl.checkInjectorsAreDisposed(project);
        }
      })
      .append(() -> {
        JarFileSystemImpl.cleanupForNextTest();

        getTempDir().deleteAll();
        LocalFileSystem.getInstance().refreshIoFiles(myFilesToDelete);
        LaterInvocator.dispatchPendingFlushes();
      })
      .append(() -> {
        if (!myAssertionsInTestDetected) {
          if (IdeaLogger.ourErrorsOccurred != null) {
            throw IdeaLogger.ourErrorsOccurred;
          }
        }
      })
      .append(() -> super.tearDown())
      .append(() -> {
        if (myEditorListenerTracker != null) {
          myEditorListenerTracker.checkListenersLeak();
        }
      })
      .append(() -> {
        if (myThreadTracker != null) {
          myThreadTracker.checkLeak();
        }
      })
      .append(() -> LightPlatformTestCase.checkEditorsReleased())
      .append(() -> myOldSdks.checkForJdkTableLeaks())
      .append(() -> myVirtualFilePointerTracker.assertPointersAreDisposed())
      .append(() -> {
        myProjectManager = null;
        myProject = null;
        myModule = null;
        myFilesToDelete.clear();
        myEditorListenerTracker = null;
        myThreadTracker = null;
        //noinspection AssignmentToStaticFieldFromInstanceMethod
        ourTestCase = null;
      })
      .run();
  }

  private void disposeProject() {
    if (myProject != null) {
      closeAndDisposeProjectAndCheckThatNoOpenProjects(myProject);
      myProject = null;
    }
  }

  public static void closeAndDisposeProjectAndCheckThatNoOpenProjects(@NotNull final Project projectToClose) {
    RunAll runAll = new RunAll();
    ProjectManagerEx projectManager = ProjectManagerEx.getInstanceEx();
    for (Project project : projectManager.closeTestProject(projectToClose)) {
      runAll = runAll
        .append(() -> {
          throw new IllegalStateException("Test project is not disposed: " + project + ";\n created in: " + getCreationPlace(project));
        })
        .append(() -> projectManager.forceCloseProject(project, true));
    }
    runAll.append(() -> WriteAction.run(() -> Disposer.dispose(projectToClose))).run();
  }

  protected void resetAllFields() {
    resetClassFields(getClass());
  }

  @NotNull
  @Override
  protected final <T extends Disposable> T disposeOnTearDown(@NotNull T disposable) {
    Disposer.register(myProject, disposable);
    return disposable;
  }

  private void resetClassFields(@NotNull Class<?> aClass) {
    try {
      clearDeclaredFields(this, aClass);
    }
    catch (IllegalAccessException e) {
      LOG.error(e);
    }

    if (aClass == HeavyPlatformTestCase.class) return;
    resetClassFields(aClass.getSuperclass());
  }

  protected void setUpJdk() {
    final Sdk jdk = getTestProjectJdk();
    Module[] modules = ModuleManager.getInstance(myProject).getModules();
    for (Module module : modules) {
      ModuleRootModificationUtil.setModuleSdk(module, jdk);
    }
  }

  @Nullable
  protected Sdk getTestProjectJdk() {
    return null;
  }

  @Override
  public void runBare() throws Throwable {
    if (!shouldRunTest()) return;

    TestRunnerUtil.replaceIdeEventQueueSafely();
    try {
      runBareImpl();
    }
    finally {
      try {
        EdtTestUtil.runInEdtAndWait(() -> {
          cleanupApplicationCaches(getProject());
          resetAllFields();
        });
      }
      catch (Throwable ignored) {
      }
    }
  }

  private void runBareImpl() throws Throwable {
    ThrowableRunnable<Throwable> runnable = () -> {
      ourTestThread = Thread.currentThread();
      ourTestTime = DEFAULT_TEST_TIME;
      try {
        try {
          myAssertionsInTestDetected = true;
          setUp();
          myAssertionsInTestDetected = false;
        }
        catch (Throwable e) {
          try {
            tearDown();
          }
          catch (Throwable ignored) {
          }

          throw e;
        }

        Throwable exception = null;
        try {
          myAssertionsInTestDetected = true;
          runTest();
          myAssertionsInTestDetected = false;
        }
        catch (Throwable e) {
          exception = e;
        }
        finally {
          try {
            tearDown();
          }
          catch (Throwable e) {
            if (exception == null) {
              exception = e;
            }
          }
        }

        if (exception != null) {
          throw exception;
        }
      }
      finally {
        ourTestThread = null;
      }
    };

    runBareRunnable(runnable);

    if (IdeaLogger.ourErrorsOccurred != null) {
      throw IdeaLogger.ourErrorsOccurred;
    }

    // just to make sure all deferred Runnable's to finish
    waitForAllLaters();
    if (IdeaLogger.ourErrorsOccurred != null) {
      throw IdeaLogger.ourErrorsOccurred;
    }
  }

  private static void waitForAllLaters() throws InterruptedException, InvocationTargetException {
    for (int i = 0; i < 3; i++) {
      SwingUtilities.invokeAndWait(EmptyRunnable.getInstance());
    }
  }

  protected void runBareRunnable(@NotNull ThrowableRunnable<Throwable> runnable) throws Throwable {
    if (runInDispatchThread()) {
      EdtTestUtil.runInEdtAndWait(runnable);
    }
    else {
      runnable.run();
    }
  }

  protected boolean isRunInWriteAction() {
    return false;
  }

  @Override
  protected void invokeTestRunnable(@NotNull final Runnable runnable) throws Exception {
    final Exception[] e = new Exception[1];
    Runnable runnable1 = () -> {
      try {
        if (ApplicationManager.getApplication().isDispatchThread() && isRunInWriteAction()) {
          ApplicationManager.getApplication().runWriteAction(runnable);
        }
        else {
          runnable.run();
        }
      }
      catch (Exception e1) {
        e[0] = e1;
      }
    };

    if (annotatedWith(WrapInCommand.class)) {
      CommandProcessor.getInstance().executeCommand(myProject, runnable1, "", null);
    }
    else {
      runnable1.run();
    }

    if (e[0] != null) {
      throw e[0];
    }
  }

  @Override
  public Object getData(@NotNull String dataId) {
    return myProject == null || myProject.isDisposed() ? null : new TestDataProvider(myProject).getData(dataId);
  }

  @NotNull
  public File createTempDir(@NonNls @NotNull String prefix) throws IOException {
    return createTempDir(prefix, true);
  }

  @NotNull
  public File createTempDir(@NonNls @NotNull String prefix, final boolean refresh) throws IOException {
    final File tempDirectory = FileUtilRt.createTempDirectory("idea_test_" + prefix, null, false);
    myFilesToDelete.add(tempDirectory);
    if (refresh) {
      getVirtualFile(tempDirectory);
    }
    return tempDirectory;
  }

  protected static VirtualFile getVirtualFile(@NotNull File file) {
    return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
  }

  @NotNull
  protected File createTempDirectory() throws IOException {
    return createTempDir("");
  }

  @NotNull
  protected File createTempDirectory(final boolean refresh) throws IOException {
    return createTempDir("", refresh);
  }

  @NotNull
  protected File createTempFile(@NotNull String name, @Nullable String text) throws IOException {
    File directory = createTempDirectory();
    File file = new File(directory, name);
    if (!file.createNewFile()) {
      throw new IOException("Can't create " + file);
    }
    if (text != null) {
      FileUtil.writeToFile(file, text);
    }
    return file;
  }

  public static void setContentOnDisk(@NotNull File file, @Nullable byte[] bom, @NotNull String content, @NotNull Charset charset)
    throws IOException {
    FileOutputStream stream = new FileOutputStream(file);
    if (bom != null) {
      stream.write(bom);
    }
    try (OutputStreamWriter writer = new OutputStreamWriter(stream, charset)) {
      writer.write(content);
    }
  }

  @NotNull
  public VirtualFile createTempFile(@NonNls @NotNull String ext,
                                    @Nullable byte[] bom,
                                    @NonNls @NotNull String content,
                                    @NotNull Charset charset) throws IOException {
    File temp = FileUtil.createTempFile("copy", "." + ext);
    setContentOnDisk(temp, bom, content, charset);

    myFilesToDelete.add(temp);
    final VirtualFile file = getVirtualFile(temp);
    assert file != null : temp;
    return file;
  }

  @Nullable
  protected PsiFile getPsiFile(@NotNull Document document) {
    return PsiDocumentManager.getInstance(getProject()).getPsiFile(document);
  }

  private static void setPlatformPrefix(@NotNull String prefix) {
    System.setProperty(PlatformUtils.PLATFORM_PREFIX_KEY, prefix);
    ourPlatformPrefixInitialized = true;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD, ElementType.TYPE})
  public @interface WrapInCommand {
  }

  @NotNull
  protected static VirtualFile createChildData(@NotNull final VirtualFile dir, @NotNull @NonNls final String name) {
    try {
      return WriteAction.computeAndWait(() -> dir.createChildData(null, name));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  protected static VirtualFile createChildDirectory(@NotNull final VirtualFile dir, @NotNull @NonNls final String name) {
    try {
      return WriteAction.computeAndWait(() -> dir.createChildDirectory(null, name));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected static void rename(@NotNull final VirtualFile vFile1, @NotNull final String newName) {
    try {
      WriteCommandAction.writeCommandAction(null).run(() -> vFile1.rename(vFile1, newName));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected static void delete(@NotNull final VirtualFile vFile1) {
    VfsTestUtil.deleteFile(vFile1);
  }

  public static void move(@NotNull final VirtualFile vFile1, @NotNull final VirtualFile newFile) {
    try {
      WriteCommandAction.writeCommandAction(null).run(() -> vFile1.move(vFile1, newFile));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  protected static VirtualFile copy(@NotNull final VirtualFile file, @NotNull final VirtualFile newParent, @NotNull final String copyName) {
    final VirtualFile[] copy = new VirtualFile[1];

    try {
      WriteCommandAction.writeCommandAction(null).run(() -> copy[0] = file.copy(file, newParent, copyName));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    return copy[0];
  }

  public static void copyDirContentsTo(@NotNull final VirtualFile vTestRoot, @NotNull final VirtualFile toDir) {
    try {
      WriteCommandAction.writeCommandAction(null).run(() -> {
        for (VirtualFile file : vTestRoot.getChildren()) {
          VfsUtil.copy(file, file, toDir);
        }
      });
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void setFileText(@NotNull final VirtualFile file, @NotNull final String text) {
    try {
      WriteAction.runAndWait(() -> VfsUtil.saveText(file, text));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void setBinaryContent(@NotNull final VirtualFile file, @NotNull final byte[] content) {
    try {
      WriteAction.runAndWait(() -> file.setBinaryContent(content));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void setBinaryContent(@NotNull final VirtualFile file,
                                      @NotNull final byte[] content,
                                      final long newModificationStamp,
                                      final long newTimeStamp,
                                      final Object requestor) {
    try {
      WriteAction.runAndWait(() -> file.setBinaryContent(content, newModificationStamp, newTimeStamp, requestor));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  protected VirtualFile getOrCreateProjectBaseDir() {
    String basePath = myProject.getBasePath();
    VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(Objects.requireNonNull(basePath));
    if (baseDir == null) {
      try {
        Files.createDirectories(Paths.get(basePath));
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      return Objects.requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath));
    }
    return baseDir;
  }

  @NotNull
  protected static VirtualFile getOrCreateModuleDir(@NotNull Module module) throws IOException {
    File moduleDir = new File(PathUtil.getParentPath(module.getModuleFilePath()));
    FileUtil.ensureExists(moduleDir);
    return Objects.requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(moduleDir));
  }

  public static void waitForProjectLeakingThreads(@NotNull Project project, long timeout, @NotNull TimeUnit timeUnit) throws Exception {
    NonBlockingReadActionImpl.cancelAllTasks();
    GeneratedSourceFileChangeTrackerImpl tracker =
      (GeneratedSourceFileChangeTrackerImpl)project.getComponent(GeneratedSourceFileChangeTracker.class);
    if (tracker != null) {
      tracker.cancelAllAndWait(timeout, timeUnit);
    }
  }
}
