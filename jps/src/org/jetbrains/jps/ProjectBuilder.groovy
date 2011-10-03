package org.jetbrains.jps

import org.codehaus.gant.GantBinding
import org.jetbrains.ether.ProjectWrapper
import org.jetbrains.ether.Reporter
import org.jetbrains.ether.dependencyView.Callbacks.Backend
import org.jetbrains.ether.dependencyView.StringCache
import org.jetbrains.jps.idea.OwnServiceLoader
import org.jetbrains.jps.listeners.BuildInfoPrinter
import org.jetbrains.jps.listeners.BuildStatisticsListener
import org.jetbrains.jps.listeners.DefaultBuildInfoPrinter
import org.jetbrains.jps.listeners.JpsBuildListener
import org.jetbrains.jps.builders.*
import org.jetbrains.jps.incremental.ProjectPaths

/**
 * @author max
 */
class ProjectBuilder {
  private final Set<ModuleChunk> compiledChunks = [] as Set
  private final Set<ModuleChunk> compiledTestChunks = [] as Set
  private ProjectChunks productionChunks
  private ProjectChunks testChunks

  final Project project;
  final GantBinding binding;

  final List<ModuleBuilder> sourceGeneratingBuilders = []
  final List<ModuleBuilder> sourceModifyingBuilders = []
  final List<ModuleBuilder> translatingBuilders = []
  final List<ModuleBuilder> weavingBuilders = []
  final CustomTasksBuilder preTasksBuilder = new CustomTasksBuilder()
  final CustomTasksBuilder postTasksBuilder = new CustomTasksBuilder()
  static final OwnServiceLoader<ModuleBuilderService> moduleBuilderLoader = OwnServiceLoader.load(ModuleBuilderService.class)

  final List<JpsBuildListener> listeners = [new BuildStatisticsListener()]
  BuildInfoPrinter buildInfoPrinter = new DefaultBuildInfoPrinter()
  boolean useInProcessJavac
  boolean compressJars = true
  boolean arrangeModuleCyclesOutputs

  private ProjectPaths projectPaths
  private String targetFolder = null

  private final TempFileContainer tempFileContainer

  def ProjectBuilder(GantBinding binding, Project project) {
    this.project = project
    this.binding = binding
    tempFileContainer = new TempFileContainer(project, "__build_temp__")
    sourceGeneratingBuilders << new GroovyStubGenerator(project)
    translatingBuilders << new JavacBuilder()
    translatingBuilders << new GroovycBuilder(project)
    translatingBuilders << new ResourceCopier()
    weavingBuilders << new JetBrainsInstrumentations(project)
    productionChunks = new ProjectChunks(project, ClasspathKind.PRODUCTION_COMPILE)
    testChunks = new ProjectChunks(project, ClasspathKind.TEST_COMPILE)

    moduleBuilderLoader*.registerBuilders(this)
  }

  String getTargetFolder() {
    return targetFolder
  }

  void setTargetFolder(String targetFolder) {
    this.targetFolder = targetFolder
    projectPaths = null
  }

  public ProjectPaths getProjectPaths() {
    if (projectPaths == null) {
      projectPaths = new ProjectPaths(project, targetFolder != null ? new File(targetFolder) : null)
      if (!arrangeModuleCyclesOutputs) {
        setOutputPathsForModuleCycles(projectPaths, productionChunks, false)
        setOutputPathsForModuleCycles(projectPaths, testChunks, true)
      }
    }
    return projectPaths
  }

  void setArrangeModuleCyclesOutputs(boolean arrangeModuleCyclesOutputs) {
    this.arrangeModuleCyclesOutputs = arrangeModuleCyclesOutputs
    projectPaths = null
  }

  private def setOutputPathsForModuleCycles(ProjectPaths projectPaths, final ProjectChunks chunks, final boolean forTests) {
    chunks.chunkList.each { ModuleChunk chunk ->
      if (chunk.elements.size() > 1) {
        File outputDir
        String targetFolder = project.targetFolder
        if (targetFolder != null) {
          def basePath = tests ? new File(targetFolder, "test").absolutePath : new File(targetFolder, "production").absolutePath
          if (name.length() > 100) {
            name = name.substring(0, 100) + "_etc"
          }
          outputDir = new File(basePath, name).absolutePath
        }
        else {
          outputDir = new File(forTests ? chunk.representativeModule().testOutputPath : chunk.representativeModule().outputPath)
        }
        chunk.elements.each {
          projectPaths.setCustomModuleOutputDir(it, forTests, outputDir)
        }
      }
    }
  }

  def ProjectChunks getChunks(boolean includeTests) {
    return includeTests ? testChunks : productionChunks
  }

  private def List<ModuleBuilder> builders() {
    [preTasksBuilder, sourceGeneratingBuilders, sourceModifyingBuilders, translatingBuilders, weavingBuilders, postTasksBuilder].flatten()
  }

  public def clean() {
    compiledChunks.clear()
    compiledTestChunks.clear()
  }

  public def buildAll() {
    buildAllModules(true)
  }

  public def buildSelected(Collection<Module> modules, boolean tests) {
    buildModules(modules, tests)
  }

  public def buildProduction() {
    buildAllModules(false)
  }

  private def buildAllModules(boolean includeTests) {
    buildModules(project.modules.values(), includeTests)
  }

  private def clearChunks(Collection<Module> modules) {
    getChunks(true).getChunkList().each {
      if (!modules.intersect(it.modules).isEmpty()) {
        clearChunk(it)
      }
    }
  }

  def buildStart() {
    listeners*.onBuildStarted(project)
  }

  def buildStop() {
    listeners*.onBuildFinished(project)
  }

  private def buildModules(Collection<Module> modules, boolean includeTests) {
    buildStart()
    clearChunks(modules)
    buildChunks(modules, false)
    if (includeTests) {
      buildChunks(modules, true)
    }
    buildStop()
  }

  private def buildChunks(Collection<Module> modules, boolean tests) {
    getChunks(tests).getChunkList().each {
      if (!modules.intersect(it.modules).isEmpty()) {
        buildChunk(it, tests)
      }
    }
  }

  def preModuleBuildTask(String moduleName, Closure task) {
    preTasksBuilder.registerTask(moduleName, task)
  }

  def postModuleBuildTask(String moduleName, Closure task) {
    postTasksBuilder.registerTask(moduleName, task)
  }

  private ModuleChunk chunkForModule(Module m, boolean tests) {
    return getChunks(tests).findChunk(m)
  }

  def makeModule(Module module) {
    makeModuleWithDependencies(module, false);
  }

  def makeModuleTests(Module module) {
    makeModuleWithDependencies(module, true);
  }

  def deleteTempFiles() {
    tempFileContainer.clean()
  }

  String getTempDirectoryPath(String name) {
    return tempFileContainer.getTempDirPath(name)
  }

  private def makeModuleWithDependencies(Module module, boolean includeTests) {
    def chunk = chunkForModule(module, includeTests)
    Set<Module> dependencies = new HashSet<Module>()
    Set<Module> runtimeDependencies = new HashSet<Module>()
    chunk.modules.each {
      collectModulesFromClasspath(it, ClasspathKind.compile(includeTests), dependencies)
      collectModulesFromClasspath(it, ClasspathKind.runtime(includeTests), runtimeDependencies)
    }
    dependencies.addAll(runtimeDependencies)

    buildModules(dependencies, includeTests)
  }

  def clearChunk(ModuleChunk chunk, Collection<StringCache.S> files, ProjectWrapper pw) {
    if (!project.dryRun) {
      if (files == null) {
        project.stage("Cleaning module ${chunk.name}")
        chunk.modules.each {project.cleanModule it}
      }
      else {
        project.stage("Cleaning output files for module ${chunk.name}")

        files.each {
          binding.ant.delete(file: pw.getAbsolutePath(it.value))
        }

        chunk.modules.each {
          binding.ant.delete(file: it.outputPath + File.separator + Reporter.myOkFlag)
          binding.ant.delete(file: it.outputPath + File.separator + Reporter.myFailFlag)
        }
      }
    }
  }

  private def clearChunk(ModuleChunk c) {
    clearChunk(c, null, null)
  }

  private def buildChunk(ModuleChunk chunk, boolean tests) {
    buildChunk(chunk, tests, null, null, null)
  }

  def buildChunk(ModuleChunk chunk, boolean tests, Collection<StringCache.S> files, Backend callback, ProjectWrapper pw) {
    Set<ModuleChunk> compiledSet = tests ? compiledTestChunks : compiledChunks
    if (compiledSet.contains(chunk) && files == null) return
    compiledSet.add(chunk)

    project.stage("Making${tests ? ' tests for' : ''} module ${chunk.name}")
    if (project.targetFolder == null && !arrangeModuleCyclesOutputs && chunk.modules.size() > 1) {
      project.warning("Modules $chunk.modules with cyclic dependencies will be compiled to output of ${chunk.modules.toList().first()} module")
    }

    compile(chunk, tests, files, callback, pw)
  }

  private String getModuleOutputFolder(Module module, boolean tests) {
    return getProjectPaths().getModuleOutputDir(module, tests)
  }

  private def compile(ModuleChunk chunk, boolean tests, Collection<StringCache.S> files, Backend callback, ProjectWrapper pw) {
    List<String> chunkSources = filterNonExistingFiles(tests ? chunk.testRoots : chunk.sourceRoots, true)
    if (chunkSources.isEmpty()) return

    List<String> sourceFiles = []

    if (files != null) {
      files.each {
        sourceFiles << pw.getAbsolutePath(it.value)
      }
    }

    if (!project.dryRun) {
      List<String> chunkClasspath = ProjectPaths.getPathsList(getProjectPaths().getClasspathFiles(chunk, ClasspathKind.compile(tests), files == null))

      List sourceRootsWithDependencies = getProjectPaths().getSourcePathsForModuleWithDependents(chunk, tests)
      Map<ModuleBuildState, ModuleChunk> states = new HashMap<ModuleBuildState, ModuleChunk>()
      def chunkState = new ModuleBuildState(
              iterated: false,
              loader: null,
              formInstrumenter: null,
              tests: tests,
              projectWrapper: pw,
              incremental: files != null,
              callback: callback,
              sourceFiles: sourceFiles,
              sourceRoots: chunkSources,
              excludes: chunk.excludes,
              classpath: chunkClasspath,
              sourceRootsFromModuleWithDependencies: sourceRootsWithDependencies,
      )
      if (arrangeModuleCyclesOutputs) {
        chunk.modules.each {
          List<String> sourceRoots = filterNonExistingFiles(tests ? it.testRoots : it.sourceRoots, false)
          if (!sourceRoots.isEmpty()) {
            def state = new ModuleBuildState(
                    iterated: false,
                    loader: null,
                    formInstrumenter: null,
                    tests: tests,
                    projectWrapper: pw,
                    incremental: chunkState.incremental,
                    callback: callback,
                    sourceFiles: sourceFiles,
                    sourceRoots: sourceRoots,
                    excludes: it.excludes,
                    classpath: chunkClasspath,
                    targetFolder: createOutputFolder(it.name, it, tests),
                    sourceRootsFromModuleWithDependencies: sourceRootsWithDependencies
            )
            states[state] = new ModuleChunk(it)
          }
        }
        if (chunk.modules.size() > 1) {
          chunkState.targetFolder = getTempDirectoryPath(chunk.name + (tests ? "_tests" : ""))
          binding.ant.mkdir(dir: chunkState.targetFolder)
          chunkClasspath.add(0, chunkState.targetFolder)
          chunkState.tempRootsToDelete << chunkState.targetFolder
        }
      }
      else {
        chunkState.targetFolder = createOutputFolder(chunk.name, chunk.representativeModule(), tests)
        states[chunkState] = chunk
      }

      listeners*.onCompilationStarted(chunk)

      try {
        builders().each {ModuleBuilder builder ->
          listeners*.onModuleBuilderStarted(builder, chunk)
          if (arrangeModuleCyclesOutputs && chunk.modules.size() > 1 && builder instanceof ModuleCycleBuilder) {
            ((ModuleCycleBuilder) builder).preprocessModuleCycle(chunkState, chunk, project)
          }
          states.keySet().each {
            builder.processModule(it, states[it], project)
          }
          listeners*.onModuleBuilderFinished(builder, chunk)
        }
      }
      catch (Exception e) {
        final String reason = e.toString();

        chunk.modules.each {
          Reporter.reportBuildFailure(it, tests, reason)
        }

        throw e;
      }

      states.keySet().each {
        it.tempRootsToDelete.each {
          BuildUtil.deleteDir(project, it)
        }
      }
      chunkState.tempRootsToDelete.each {
        BuildUtil.deleteDir(project, it)
      }
      listeners*.onCompilationFinished(chunk)
    }

    chunk.modules.each {
      Reporter.reportBuildSuccess(it, tests)
      project.exportProperty("module.${it.name}.output.${tests ? "test" : "main"}", getModuleOutputFolder(it, tests))
    }
  }

  private String createOutputFolder(String name, Module module, boolean tests) {
    def dst = getProjectPaths().getModuleOutputDir(module, tests)
    if (dst == null) {
      project.error("${tests ? 'Test output' : 'Output'} path for module $name is not specified")
    }
    def ant = binding.ant
    ant.mkdir(dir: dst.absolutePath)
    return dst.absolutePath
  }

  List<String> projectRuntimeClasspath(boolean tests) {
    Set<String> result = new LinkedHashSet<String>()
    ClasspathKind kind = ClasspathKind.runtime(tests)
    project.modules.values().each {Module module ->
      result.addAll(module.getClasspathRoots(kind))
      module.getClasspath(kind).each {ClasspathItem item ->
        if (!(item instanceof Module)) {
          result.addAll(item.getClasspathRoots(kind))
        }
      }
    }
    return result.asList()
  }

  List<String> moduleClasspath(Module module, ClasspathKind classpathKind) {
    return getProjectPaths().getClasspath(chunkForModule(module, classpathKind.isTestsIncluded()), classpathKind)
  }

  List<String> moduleRuntimeClasspath(Module module, boolean test) {
    return getProjectPaths().getClasspath(chunkForModule(module, test), ClasspathKind.runtime(test))
  }

  private def collectModulesFromClasspath(Module module, ClasspathKind kind, Set<Module> result) {
    if (result.contains(module)) return
    result << module
    module.getClasspath(kind).each {
      if (it instanceof Module) {
        collectModulesFromClasspath(it, kind, result)
      }
    }
  }

  String moduleOutput(Module module) {
    return getModuleOutputFolder(module, false)
  }

  String moduleTestsOutput(Module module) {
    return getModuleOutputFolder(module, true)
  }

  List<String> filterNonExistingFiles(List<String> list, boolean showWarnings) {
    List<String> answer = new ArrayList<String>()
    for (path in list) {
      if (new File(path).exists()) {
        answer.add(path)
      }
      else if (showWarnings) {
        project.warning("'$path' does not exist!")
      }
    }

    answer
  }
}
