package org.seacourt.build

import sbt._
import Keys._
import complete.DefaultParsers._
import com.typesafe.config.{ Config => TConfig, ConfigFactory, ConfigParseOptions }

/**
 * The base mechanics, keys and build graph for a native build.
 * The possible build configurations remain abstract via BuildType and
 * the configurations Set. These need to be provided in a derived class.
 */

object NativeBuild extends AutoPlugin {
  trait DebugOptLevel
  case object Release extends DebugOptLevel
  case object Debug extends DebugOptLevel

  trait NativeCompiler
  case object Gcc extends NativeCompiler
  case object Clang extends NativeCompiler
  case object VSCl extends NativeCompiler

  trait TargetPlatform
  case object LinuxPC extends TargetPlatform
  case object WindowsPC extends TargetPlatform

  /**
   * Keys for a native build that should be visible from all types of SBT
   * project (including Scala).
   */

  object Keys {
    lazy val buildRootDirectory = settingKey[File]("build root directory")
    lazy val nativeCompiler = taskKey[Compiler]("Compiler to use for this build")
    lazy val nativeBuildConfiguration = taskKey[BuildConfiguration]("Build configuration key")
    lazy val nativeConfigRootBuildDirectory = taskKey[File]("Build root directory (for the config, not the project)")
    lazy val nativeProjectBuildDirectory = taskKey[File]("Build directory for this config and project")
    lazy val nativeStateCacheDirectory = taskKey[File]("Build state cache directory")
    lazy val nativeProjectDirectory = taskKey[File]("Project directory")
    lazy val nativeSourceDirectories = taskKey[Seq[File]]("Source directories")
    lazy val nativeProjectIncludeDirectories = taskKey[Seq[File]]("Include directories local to this project only")
    lazy val nativeIncludeDirectories = taskKey[Seq[File]]("Include directories")
    lazy val nativeSystemIncludeDirectories = taskKey[Seq[File]]("System include directories")
    lazy val nativeLinkDirectories = taskKey[Seq[File]]("Link directories")
    lazy val nativeLibraries = taskKey[Seq[String]]("All native library dependencies for this project")
    lazy val nativeHeaderFiles = taskKey[Seq[File]]("All C source files for this project")
    lazy val nativeCCSourceFiles = taskKey[Seq[File]]("All C source files for this project")
    lazy val nativeCXXSourceFiles = taskKey[Seq[File]]("All C++ source files for this project")
    lazy val nativeCCSourceFilesWithDeps = taskKey[Seq[(File, Seq[File])]]("All C source files with dependencies for this project")
    lazy val nativeCXXSourceFilesWithDeps = taskKey[Seq[(File, Seq[File])]]("All C++ source files with dependencies for this project")
    lazy val nativeObjectFiles = taskKey[Seq[File]]("All object files for this project")
    lazy val nativeArchiveFiles = taskKey[Seq[File]]("All archive files for this project, specified by full path")
    //TODO: use Option
    lazy val nativeExe = taskKey[File]("Executable built by this project (if appropriate)")
    lazy val nativeTestExe = taskKey[Option[File]]("Test executable built by this project (if appropriate)")
    lazy val nativeTestProject = taskKey[Project]("The test sub-project for this project")
    lazy val nativeTestExtraDependencies = taskKey[Seq[File]]("Extra file dependencies of the test (used to calculate when to re-run tests)")
    lazy val nativeTest = taskKey[Option[(File, File)]]("Run the native test, returning the files with stdout and stderr respectively")
    lazy val test = taskKey[Unit]("Run the test associated with this project")
    lazy val nativeEnvironmentVariables = taskKey[Seq[(String, String)]]("Environment variables to be set for running programs and tests")
    lazy val nativeCleanAll = taskKey[Unit]("Clean the entire build directory")
    lazy val nativeCCCompileFlags = taskKey[Seq[String]]("Native C compile flags")
    lazy val nativeCXXCompileFlags = taskKey[Seq[String]]("Native C++ compile flags")
    lazy val nativeArchiveFlags = taskKey[Seq[String]]("Native archive flags (when creating archives/static libraries)")
    lazy val nativeDynamicLibraryLinkFlags = taskKey[Seq[String]]("Native flags for linking dynamic libraries")
    lazy val nativeExecutableLinkFlags = taskKey[Seq[String]]("Native flags for linking executables")
    lazy val nativeSourceDirectory = settingKey[File]("Parent directory of native sources")
    lazy val nativeExportedLibs = taskKey[Seq[File]]("All libraries exported by this project")
    lazy val nativeExportedLibDirectories = taskKey[Seq[File]]("All library directories exported by this project")
    lazy val nativeExportedIncludeDirectories = taskKey[Seq[File]]("All include directories exported by this project")
    /**
     * A default/example build setup with the following axes:
     *
     * 1. Release/Debug
     * 2. Gcc/Clang
     * 3. LinuxPC/BeagleBone
     *
     * Not all combinations of the above make sense, only the ones that do should
     * be added to the configurations Set
     */
    lazy val configurations = settingKey[Set[BuildConfiguration]]("Build configurations")
    lazy val compilerConfiguration = settingKey[TConfig]("Configuration")
    lazy val configKey = AttributeKey[BuildConfiguration]("configKey")
    //TODO: fix temporary name to avoid clashing with built in sbt compiler key
    lazy val compilerType = settingKey[NativeCompiler]("Compiler, gcc, clang, etc")
    lazy val targetPlatform = settingKey[TargetPlatform]("target platform")
    lazy val debugOptLevel = settingKey[DebugOptLevel]("Debug level")
  }

  import Keys.{compilerType => _, targetPlatform => _, debugOptLevel => _, _}

  object autoImport {
    lazy val compilerType = Keys.compilerType
    lazy val targetPlatform = Keys.targetPlatform
    lazy val debugOptLevel = Keys.debugOptLevel

    implicit class RichNativeProject(p: Project) {
      def nativeDependsOn(others: ProjectReference*): Project = {
        others.foldLeft(p) {
          case (np, other) =>
            np.dependsOn(other).settings(
              nativeIncludeDirectories in Compile ++= (nativeExportedIncludeDirectories in other).value,
              nativeLinkDirectories in Compile ++= (nativeExportedLibDirectories in other).value,
              nativeArchiveFiles in Compile ++= (nativeExportedLibs in other).value)
        }
      }

      def nativeSystemDependsOn(others: ProjectReference*): Project = {
        others.foldLeft(p) {
          case (np, other) =>
            np.dependsOn(other).settings(
              nativeSystemIncludeDirectories in Compile ++= (nativeExportedIncludeDirectories in other).value,
              nativeLinkDirectories in Compile ++= (nativeExportedLibDirectories in other).value,
              nativeArchiveFiles in Compile ++= (nativeExportedLibs in other).value)
        }
      }
    }

    lazy val nativeExeSettings = inConfig(Compile)(Seq( 
      nativeExe in Compile := {
        val allInputFiles = nativeObjectFiles.value ++ nativeArchiveFiles.value
        
        val blf = nativeCompiler.value.buildExecutable(
          streams.value.log,
          nativeProjectBuildDirectory.value,
          name.value,
          nativeExecutableLinkFlags.value,
          nativeLinkDirectories.value,
          nativeLibraries.value,
          allInputFiles
        )

        blf.runIfNotCached(nativeStateCacheDirectory.value, allInputFiles)
      },
      nativeTestExe in Test := None,
      compile in Compile := {
        val orderingDependency = nativeExe.value
        sbt.inc.Analysis.Empty
      },
      run := {
        val args: Seq[String] = spaceDelimited("<arg>").parsed
        val result = Process(nativeExe.value.toString +: args, baseDirectory.value, nativeEnvironmentVariables.value : _*).!
        if (result != 0) sys.error("Non-zero exit code: " + result.toString)
      } 
    ))

    lazy val staticLibrarySettings = inConfig(Compile)(
      Seq( 
        nativeExportedLibs := {
          val ofs = (nativeObjectFiles in Compile).value
          
          if (ofs.isEmpty) Seq() 
          else {
            val blf = nativeCompiler.value.buildStaticLibrary(
              streams.value.log,
              nativeProjectBuildDirectory.value,
              name.value,
              ofs,
              (nativeArchiveFlags in Compile).value 
            )
              
            Seq(blf.runIfNotCached(nativeStateCacheDirectory.value, ofs))
          }
        }
      ) ++
      librarySettings
    )

    lazy val sharedLibrarySettings = inConfig(Compile)(
      Seq( 
        nativeExportedLibs := {
          val allInputFiles = (nativeObjectFiles in Compile).value ++ (nativeArchiveFiles in Compile).value
          
          val blf = nativeCompiler.value.buildSharedLibrary(
            streams.value.log,
            nativeProjectBuildDirectory.value,
            name.value,
            allInputFiles,
            (nativeLinkDirectories in Compile).value,
            (nativeLibraries in Compile).value,
            (nativeDynamicLibraryLinkFlags in Compile).value
          )
          
          Seq(blf.runIfNotCached(nativeStateCacheDirectory.value, allInputFiles))
        }
      ) ++
      librarySettings
    )

    val Release = NativeBuild.Release
    val Debug = NativeBuild.Debug

    val Gcc = NativeBuild.Gcc
    val Clang = NativeBuild.Clang
    val VSCl = NativeBuild.VSCl

    val LinuxPC = NativeBuild.LinuxPC
    val WindowsPC = NativeBuild.WindowsPC
  }

  import autoImport._

  def librarySettings = Seq(
    nativeExportedIncludeDirectories := (nativeProjectIncludeDirectories in Compile).value,
    nativeExportedLibDirectories := (nativeExportedLibs in Compile).value.map(_.getParentFile).distinct,
    compile in Compile := {
      val orderingDependency = (nativeExportedLibs in Compile).value
      sbt.inc.Analysis.Empty
    }
  )


  lazy val headerFilePattern = Seq("*.h", "*.hpp", "*.hxx")
  lazy val ccFilePattern = Seq("*.c")
  lazy val cxxFilePattern = Seq("*.cpp", "*.cxx", "*.cc")

  case class BuildType(
    compiler: NativeCompiler,
    targetPlatform: TargetPlatform,
    debugOptLevel: DebugOptLevel
  ) extends BuildTypeTrait {
    def pathDirs = Seq(compiler.toString, targetPlatform.toString, debugOptLevel.toString)
    def name = pathDirs.mkString("_")
  }

  case class BuildConfiguration(val conf: BuildType, val compiler: Compiler)

  /**
   * Override this in your project to do appropriate checks on the 
   * build environment.
   */
  //TODO: move to task key
  //def checkConfiguration(log: Logger, env: BuildConfiguration) = {}
  
  def compilerForBuildType(buildType: BuildType, conf: TConfig) = buildType.compiler match {
    case Clang | Gcc => new GccLikeCompiler(conf, buildType)
    case VSCl => new VSCompiler(conf, buildType)
  }

  override lazy val projectSettings = Seq(
    commands ++= BasicCommands.allBasicCommands,
    compilerConfiguration := {
      //TODO: why use this for config?
      val parseOptions = ConfigParseOptions.defaults().setAllowMissing(true)
      val defaultConf = ConfigFactory.load(getClass.getClassLoader)
      val localConf = ConfigFactory.parseFile(file("build.conf").getAbsoluteFile, parseOptions)
      val userConf = ConfigFactory.parseFile(file("user.conf").getAbsoluteFile, parseOptions)
      userConf.withFallback(localConf).withFallback(defaultConf).resolve()
    },
    compilerType := Clang,
    targetPlatform := LinuxPC,
    debugOptLevel := Debug,
    buildRootDirectory := {
      file(compilerConfiguration.value.getString("build.rootdirectory")).getAbsoluteFile / name.value
    },
    // TODO: simply set nativeCompiler and config root directory keys, etc instead
    nativeBuildConfiguration := {
      val conf = compilerConfiguration.value
      val platform = targetPlatform.value
      val buildType = BuildType(compilerType.value, platform, debugOptLevel.value)
      BuildConfiguration(buildType, compilerForBuildType(buildType, conf)) 
    },
    shellPrompt := { state =>
      val projectId = Project.extract(state).currentProject.id
      s"${compilerType.value}-${targetPlatform.value}-${debugOptLevel.value}|$projectId:> "
    }
  ) ++ baseSettings

  // A selection of useful default settings from the standard sbt config
  lazy val relevantSbtDefaultSettings = Seq(
    watchTransitiveSources := Defaults.watchTransitiveSourcesTask.value,
    watch := Defaults.watchSetting.value
  )

  lazy val configSettings = Seq(
    target := buildRootDirectory.value / name.value,
    historyPath := {
      if ( !target.value.exists ) IO.createDirectory(target.value)
      Some( target.value / ".history" )
    },
    nativeConfigRootBuildDirectory := nativeBuildConfiguration.value.conf.targetDirectory( target.value ),
    clean := IO.delete( nativeConfigRootBuildDirectory.value ),
    nativeCleanAll := IO.delete(target.value),
    nativeCompiler := nativeBuildConfiguration.value.compiler,
    nativeProjectBuildDirectory := {
      val dir = nativeConfigRootBuildDirectory.value
      IO.createDirectory(dir)
      dir
    },
    nativeStateCacheDirectory := nativeProjectBuildDirectory.value / "state-cache",
    nativeSystemIncludeDirectories := nativeCompiler.value.defaultIncludePaths,
    nativeTest := None,
    nativeExportedLibs := Seq(),
    nativeExportedLibDirectories := Seq(),
    nativeExportedIncludeDirectories := Seq(),
    nativeExe := file("")
  ) 
    
  def scheduleTasks[T]( tasks : Seq[sbt.Def.Initialize[sbt.Task[T]]] ) = Def.taskDyn { tasks.joinWith( _.join ) }
    
  def findDependencies( sourceFile: File, compileFlags : TaskKey[Seq[String]] ) = Def.task {
    val depGen = nativeCompiler.value.findHeaderDependencies(
      state.value.log,
      nativeProjectBuildDirectory.value,
      nativeIncludeDirectories.value,
      nativeSystemIncludeDirectories.value,
      sourceFile,
      compileFlags.value)

    depGen.runIfNotCached(nativeStateCacheDirectory.value, Seq(sourceFile))

    (sourceFile, IO.readLines(depGen.resultPath).map(file))
  }

  def nativeBuildSettings = Seq(
    // Headers are collected for the purposes of IDE output generation, not explicitly used by SBT builds
    nativeHeaderFiles := nativeProjectIncludeDirectories.value.flatMap( sd => headerFilePattern.flatMap(fp => (sd * fp).get) ),
    nativeCCSourceFiles := nativeSourceDirectories.value.flatMap( sd => ccFilePattern.flatMap(fp => (sd * fp).get) ),
    nativeCXXSourceFiles := nativeSourceDirectories.value.flatMap( sd => cxxFilePattern.flatMap(fp => (sd * fp).get) ),
    nativeCCCompileFlags := nativeCompiler.value.ccDefaultFlags,
    nativeCXXCompileFlags := nativeCompiler.value.cxxDefaultFlags,
    nativeLinkDirectories := nativeCompiler.value.defaultLibraryPaths,
    nativeLibraries := Seq(),
    nativeArchiveFiles := Seq(),
    nativeArchiveFlags := nativeCompiler.value.archiveDefaultFlags,
    nativeDynamicLibraryLinkFlags := nativeCompiler.value.dynamicLibraryLinkDefaultFlags,
    nativeExecutableLinkFlags := nativeCompiler.value.executableLinkDefaultFlags,
    nativeCCSourceFilesWithDeps := Def.taskDyn {
      nativeCCSourceFiles.value.map { findDependencies( _, nativeCCCompileFlags ) }.joinWith( _.join )
    }.value,
    nativeCXXSourceFilesWithDeps := Def.taskDyn {
      nativeCXXSourceFiles.value.map { findDependencies( _, nativeCXXCompileFlags ) }.joinWith( _.join )
    }.value,
    nativeEnvironmentVariables := Seq(),
    nativeObjectFiles := Def.taskDyn {
      val ccTasks = nativeCCSourceFilesWithDeps.value.map { case (sourceFile, dependencies) =>
        val blf = nativeCompiler.value.ccCompileToObj(
          state.value.log,
          nativeProjectBuildDirectory.value,
          nativeIncludeDirectories.value,
          nativeSystemIncludeDirectories.value,
          sourceFile,
          nativeCCCompileFlags.value )

        Def.task { blf.runIfNotCached(nativeStateCacheDirectory.value, sourceFile +: dependencies) }
      }
      
      val cxxTasks = nativeCXXSourceFilesWithDeps.value.map { case (sourceFile, dependencies) =>
        val blf = nativeCompiler.value.cxxCompileToObj(
          state.value.log,
          nativeProjectBuildDirectory.value,
          nativeIncludeDirectories.value,
          nativeSystemIncludeDirectories.value,
          sourceFile,
          nativeCXXCompileFlags.value )

        Def.task { blf.runIfNotCached(nativeStateCacheDirectory.value, sourceFile +: dependencies) }
      }

      (ccTasks ++ cxxTasks).joinWith( _.join )
    }.value 
  )

  def compileSettings = inConfig(Compile)(nativeBuildSettings ++ Seq(
    //TODO: change to "c/cpp"
    nativeSourceDirectory := sourceDirectory.value / "native",
    nativeSourceDirectories := Seq(nativeSourceDirectory.value),
    nativeProjectIncludeDirectories := Seq(
      sourceDirectory.value / "interface", 
      sourceDirectory.value / "include"
    ),
    nativeIncludeDirectories := nativeProjectIncludeDirectories.value
  ))

  /*
  def testSettings = inConfig(Test)(nativeBuildSettings ++ Seq(
    nativeProjectDirectory := (sourceDirectory in Compile).value / "native-test",
    nativeProjectBuildDirectory := {
      val testBd = (nativeProjectBuildDirectory in Compile).value / "test"
      IO.createDirectory(testBd)
      testBd
    },
    nativeProjectIncludeDirectories := Seq( nativeProjectDirectory.value / "include" ),
    nativeIncludeDirectories ++= nativeProjectIncludeDirectories.value ++ (nativeIncludeDirectories in Compile).value,
    nativeIncludeDirectories ++= (nativeExportedIncludeDirectories in Compile).value,
    nativeLinkDirectories ++= (nativeLinkDirectories in Compile).value,
    nativeArchiveFiles ++= (nativeArchiveFiles in Compile).value,
    //TODO: use setting from compile
    nativeSourceDirectories := Seq(nativeProjectDirectory.value / "source"),

    nativeTestExe :=
    {
      if ( nativeObjectFiles.value.isEmpty )
      {
        streams.value.log.info( "No tests defined for: " + name.value )
        None
      }
      else
      {
        val allInputFiles = nativeObjectFiles.value ++ (nativeExportedLibs in Compile).value ++ nativeArchiveFiles.value
        val blf = nativeCompiler.value.buildExecutable(
          streams.value.log,
          nativeProjectBuildDirectory.value,
          name.value + "_test",
          nativeExecutableLinkFlags.value,
          nativeLinkDirectories.value,
          nativeLibraries.value,
          allInputFiles )
        Some( blf.runIfNotCached( nativeStateCacheDirectory.value, allInputFiles ) )
      }
    },

    nativeTestExtraDependencies := ((nativeProjectDirectory.value / "data") ** "*").get,
    
    nativeTest := {
      if ( !nativeBuildConfiguration.value.conf.isCrossCompile && nativeTestExe.value.isDefined )
      {
        val texe = nativeTestExe.value.get
        
        val resFile = file(texe + ".res")
        val stdoutFile = file(texe + ".stdout")

        val tcf = FunctionWithResultPath(stdoutFile) { _ =>
          streams.value.log.info("Running test: " + texe)

          val po = ProcessHelper.runProcess(
            nativeProjectBuildDirectory.value,
            log = streams.value.log,
            process = AbstractProcess( "Test exe", texe, Seq(), nativeProjectDirectory.value, (nativeEnvironmentVariables in Test).value.toMap ),
            mergeToStdout = true,
            quiet = true )

          IO.writeLines(stdoutFile, po.stdoutLines)
          IO.writeLines(resFile, Seq(po.retCode.toString))

        }

        tcf.runIfNotCached(nativeStateCacheDirectory.value, texe +: nativeTestExtraDependencies.value)

        Some((resFile, stdoutFile))
      }
      else
      {
        None
      }
    },
    compile <<= (nativeTestExe) map { nc => sbt.inc.Analysis.Empty },
    test := {
      nativeTest.value map
      { case (resFile, stdOutFile) =>
      
        val res = IO.readLines( resFile ).head.toInt
        if ( res != 0 )
        {
          streams.value.log.error( "Test failed: " + name.value )
          IO.readLines( stdOutFile ).foreach { l => streams.value.log.info(l) }
          sys.error( "Non-zero exit code: " + res.toString )
        }
      }
    }
  ))
  */

  lazy val baseSettings = 
    relevantSbtDefaultSettings ++ 
    configSettings ++
    //TODO: update test settings
    //testSettings ++
    inConfig(Compile)(compileSettings) ++ Seq(
      watchSources ++= {
        val ccsfd = (nativeCCSourceFilesWithDeps in Compile).value
        val cxxsfd = (nativeCXXSourceFilesWithDeps in Compile).value

        (ccsfd ++ cxxsfd).flatMap {
          case (sf, deps) => (sf +: deps.toList)
        }.toList.distinct
      },
      watchSources ++= {
        val ccsfd = (nativeCCSourceFilesWithDeps in Test).value
        val cxxsfd = (nativeCXXSourceFilesWithDeps in Test).value

        (ccsfd ++ cxxsfd).flatMap
        {
          case (sf, deps) => (sf +: deps.toList)
        }.toList.distinct
      }
    )
}

