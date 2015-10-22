package org.seacourt.build

import sbt._
import Keys._

import com.typesafe.config.{ Config }

import scala.collection.{ mutable, immutable }

import ProcessHelper.{runProcess}

//TODO: use JavaConverters instead
import scala.collection.JavaConversions._

/**
 * Build configurations for a particular project must inherit from this trait.
 * See the default in NativeDefaultBuild for more details
 */
trait BuildTypeTrait {
  def pathDirs: Seq[String]

  def targetDirectory(rootDirectory: File) = pathDirs.foldLeft(rootDirectory)(_ / _)
}

/**
 * The base trait from which all native compilers must be inherited in order
 */
trait Compiler {
  /**
   * TODO COMMENT: What are "tools"?
   */
  def toolPaths: Seq[File]
  def defaultLibraryPaths: Seq[File]
  def defaultIncludePaths: Seq[File]
  def ccExe: File
  def cxxExe: File
  def archiverExe: File
  def linkerExe: File
  def ccDefaultFlags: Seq[String]
  def cxxDefaultFlags: Seq[String]
  def archiveDefaultFlags: Seq[String]
  def dynamicLibraryLinkDefaultFlags: Seq[String]
  def executableLinkDefaultFlags: Seq[String]

  def findHeaderDependencies(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean = false): FunctionWithResultPath

  def ccCompileToObj(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean = false
  ): FunctionWithResultPath

  def cxxCompileToObj(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean = false
  ): FunctionWithResultPath
    
  def buildStaticLibrary(
    log: Logger,
    buildDirectory: File,
    libName: String,
    objectFiles: Seq[File],
    archiveFlags: Seq[String],
    quiet: Boolean = false
  ): FunctionWithResultPath

  def buildSharedLibrary(
    log: Logger,
    buildDirectory: File,
    libName: String,
    objectFiles: Seq[File],
    linkPaths: Seq[File],
    linkLibraries: Seq[String],
    dynamicLibraryLinkFlags: Seq[String],
    quiet: Boolean = false
  ): FunctionWithResultPath

  def buildExecutable(
    log: Logger,
    buildDirectory: File,
    exeName: String,
    executableLinkFlags: Seq[String],
    linkPaths: Seq[File],
    linkLibraries: Seq[String],
    inputFiles: Seq[File],
    quiet: Boolean = false): FunctionWithResultPath
}


trait CompilationProcess {
  protected def reportFileGenerated(
    log: Logger,
    genFile: File,
    quiet: Boolean) = if (!quiet) log.info(genFile.toString)
}

trait CompilerWithConfig extends Compiler {
  def buildTypeTrait: BuildTypeTrait
  def config: Config

  private val configPrefix = buildTypeTrait.pathDirs
  private def ton(d: Seq[String]) = d.mkString(".")

  override def toolPaths = config.getStringList(ton(configPrefix :+ "toolPaths")).map(file)
  override def defaultIncludePaths = config.getStringList(ton(configPrefix :+ "includePaths")).map(file)
  override def defaultLibraryPaths = config.getStringList(ton(configPrefix :+ "libraryPaths")).map(file)
  override def ccExe = file(config.getString(ton(configPrefix :+ "ccExe")))
  override def cxxExe = file(config.getString(ton(configPrefix :+ "cxxExe")))
  override def archiverExe = file(config.getString(ton(configPrefix :+ "archiver")))
  override def linkerExe = file(config.getString(ton(configPrefix :+ "linker")))
  override def ccDefaultFlags = config.getStringList(ton(configPrefix :+ "ccFlags"))
  override def cxxDefaultFlags = config.getStringList(ton(configPrefix :+ "cxxFlags"))
  override def archiveDefaultFlags = config.getStringList(ton(configPrefix :+ "archiveFlags"))
  override def dynamicLibraryLinkDefaultFlags = config.getStringList(ton(configPrefix :+ "dynamicLibraryLinkFlags"))
  override def executableLinkDefaultFlags = config.getStringList(ton(configPrefix :+ "executableLinkFlags"))
  
  def getCwd = (new java.io.File(".")).getCanonicalFile
}

/**
 * Gcc and compatible (e.g. Clang) compilers
 */
case class GccLikeCompiler(
  override val config: Config,
  override val buildTypeTrait: BuildTypeTrait) extends CompilerWithConfig with CompilationProcess {
  override def findHeaderDependencies(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean
  ) = FunctionWithResultPath(
    buildDirectory / (sourceFile.base + ".d")) { depFile =>
      val tmpDepFile = buildDirectory / (sourceFile.base + ".dt")
      val depArgs: Seq[String] = Seq(
        "-MF",
        tmpDepFile.toString,
        "-M",
        sourceFile.toString) ++
        compilerFlags ++
        includePaths.map(ip => "-I" + ip.toString) ++
        systemIncludePaths.map(ip => "-isystem" + ip.toString)

      val depResult = runProcess(
        tmpDir=buildDirectory,
        log = log,
        process = AbstractProcess( "DepCmd", ccExe, depArgs, getCwd, Map("PATH" -> toolPaths.mkString(":") ) ),
        mergeToStdout = true,
        quiet=quiet )

      // Strip off any trailing backslash characters from the output.
      val depFileLines = IO.readLines(tmpDepFile).map(_.replace("\\", ""))

      // Drop the first column and split on spaces to get all the files 
      // (potentially several per line).
      val allFiles = depFileLines.flatMap(_.split(" ").drop(1)).map(
        x => new File(x.trim))

      IO.write(depFile, allFiles.mkString("\n"))

      reportFileGenerated(log, depFile, quiet)
    }

  override def ccCompileToObj(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean
  ) = FunctionWithResultPath(
    buildDirectory / (sourceFile.base + ".o")) { outputFile =>
      val buildArgs: Seq[String] = Seq(
        "-fPIC",
        "-c",
        "-o",
        outputFile.toString,
        sourceFile.toString) ++
        compilerFlags ++
        includePaths.map(ip => "-I" + ip.toString) ++
        systemIncludePaths.map(ip => "-isystem" + ip.toString)

      runProcess(
        tmpDir=buildDirectory,      
        log=log,
        process = AbstractProcess( "CCCompile", ccExe, buildArgs, getCwd, Map("PATH" -> toolPaths.mkString(":")) ),
        mergeToStdout=true,
        quiet=quiet )

      reportFileGenerated(log, outputFile, quiet)
    }

  override def cxxCompileToObj(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean
  ) = FunctionWithResultPath(
    buildDirectory / (sourceFile.base + ".o")) { outputFile =>
      val buildArgs: Seq[String] = Seq(
        "-fPIC",
        "-c",
        "-o",
        outputFile.toString,
        sourceFile.toString) ++
        compilerFlags ++
        includePaths.map(ip => "-I" + ip.toString) ++
        systemIncludePaths.map(ip => "-isystem" + ip.toString)

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process = AbstractProcess("CXXCompile", cxxExe, buildArgs, getCwd, Map("PATH" -> toolPaths.mkString(":")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }

  override def buildStaticLibrary(
    log: Logger,
    buildDirectory: File,
    libName: String,
    objectFiles: Seq[File],
    linkFlags: Seq[String],
    quiet: Boolean
  ) =
    FunctionWithResultPath(buildDirectory / (libName + ".a")) { outputFile =>
      val arArgs: Seq[String] = Seq(
        "-c",
        "-r",
        outputFile.toString) ++ linkFlags ++ objectFiles.map(_.toString)

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process = AbstractProcess("AR", archiverExe, arArgs, getCwd, Map("PATH" -> toolPaths.mkString(":")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }

  override def buildSharedLibrary(
    log: Logger,
    buildDirectory: File,
    libName: String,
    objectFiles: Seq[File],
    linkPaths: Seq[File],
    linkLibraries: Seq[String],
    linkFlags: Seq[String],
    quiet: Boolean
  ) =
    FunctionWithResultPath(buildDirectory / (libName + ".so")) { outputFile =>
      val args: Seq[String] = Seq(
        "-shared",
        "-o",
        outputFile.toString) ++
        linkFlags ++
        objectFiles.map(_.toString) ++
        linkPaths.map(lp => "-L" + lp) ++
        linkLibraries.map(ll => "-l" + ll)

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process=AbstractProcess( "DynamicLibrary", cxxExe, args, getCwd, Map("PATH" -> toolPaths.mkString(":")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }

  override def buildExecutable(
    log: Logger,
    buildDirectory: File,
    exeName: String,
    linkFlags: Seq[String],
    linkPaths: Seq[File],
    linkLibraries: Seq[String],
    inputFiles: Seq[File],
    quiet: Boolean
  ) =
    FunctionWithResultPath(buildDirectory / exeName) { outputFile =>
      val linkArgs: Seq[String] = Seq(
        "-o" + outputFile.toString) ++
        linkFlags ++
        inputFiles.map(_.toString) ++
        linkPaths.map(lp => "-L" + lp) ++
        linkLibraries.map(ll => "-l" + ll)

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process=AbstractProcess("Exe", linkerExe, linkArgs, getCwd, Map("PATH" -> toolPaths.mkString(":")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }
}

/**
 * Visual studio cl.exe compiler
 */
case class VSCompiler(
  override val config: Config,
  override val buildTypeTrait: BuildTypeTrait) extends CompilerWithConfig with CompilationProcess {
  override def findHeaderDependencies(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean
  ) = FunctionWithResultPath(
    buildDirectory / (sourceFile.base + ".d")) { depFile =>
      val depArgs: Seq[String] = Seq(
        "/nologo",
        "/c",
        "/showIncludes",
        sourceFile.toString) ++
        compilerFlags ++
        includePaths.flatMap(ip => Seq("/I", ip.toString)) ++
        systemIncludePaths.flatMap(ip => Seq("/I", ip.toString))
        
      val depResult = runProcess(
        tmpDir=buildDirectory,
        log=log,
        process=AbstractProcess("DepCmd", ccExe, depArgs, getCwd, Map("PATH" -> toolPaths.mkString(";")) ),
        mergeToStdout=true,
        quiet=quiet)

      // Strip off any trailing backslash characters from the output.
      val prefix = "Note: including file:"
      val depFileLines = depResult.stdoutLines.filter(_.startsWith(prefix)).map(
        _.drop(prefix.size).trim)

      // Drop the first column and split on spaces to get all the files 
      // (potentially several per line).
      val allFiles = depFileLines.map(x => new File(x))

      IO.write(depFile, allFiles.mkString("\n"))

      reportFileGenerated(log, depFile, quiet)
    }

  override def ccCompileToObj(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean
  ) = FunctionWithResultPath(
    buildDirectory / (sourceFile.base + ".obj")) { outputFile =>
      val buildArgs: Seq[String] = Seq(
        "/nologo",
        "/c",
        "/EHsc",
        "/Fo" + outputFile.toString,
        sourceFile.toString) ++
        compilerFlags ++
        includePaths.flatMap(ip => Seq("/I", ip.toString)) ++
        systemIncludePaths.flatMap(ip => Seq("/I", ip.toString))

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process=AbstractProcess("CCCompile", ccExe, buildArgs, getCwd, Map("PATH" -> toolPaths.mkString(";")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }

  override def cxxCompileToObj(
    log: Logger,
    buildDirectory: File,
    includePaths: Seq[File],
    systemIncludePaths: Seq[File],
    sourceFile: File,
    compilerFlags: Seq[String],
    quiet: Boolean) = FunctionWithResultPath(
    buildDirectory / (sourceFile.base + ".obj")) { outputFile =>
      val buildArgs: Seq[String] = Seq(
        "/nologo",
        "/c",
        "/EHsc",
        "/Fo" + outputFile.toString,
        sourceFile.toString) ++
        compilerFlags ++
        includePaths.flatMap(ip => Seq("/I", ip.toString)) ++
        systemIncludePaths.flatMap(ip => Seq("/I", ip.toString))

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process=AbstractProcess("CXXCompile", cxxExe, buildArgs, getCwd, Map("PATH" -> toolPaths.mkString(";")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }

  override def buildStaticLibrary(
    log: Logger,
    buildDirectory: File,
    libName: String,
    objectFiles: Seq[File],
    linkFlags: Seq[String],
    quiet: Boolean) =
    FunctionWithResultPath(buildDirectory / (libName + ".lib")) { outputFile =>
      val arArgs: Seq[String] = Seq(
        "/nologo",
        "/OUT:" + outputFile.toString) ++
        linkFlags ++
        objectFiles.map(_.toString)

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process=AbstractProcess("AR", archiverExe, arArgs, getCwd, Map("PATH" -> toolPaths.mkString(";")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }

  override def buildSharedLibrary(
    log: Logger,
    buildDirectory: File,
    libName: String,
    objectFiles: Seq[File],
    linkPaths: Seq[File],
    linkLibraries: Seq[String],
    linkFlags: Seq[String],
    quiet: Boolean
  ) = FunctionWithResultPath(
    buildDirectory / ("lib" + libName + ".so")) { outputFile =>
      val args: Seq[String] = Seq(
        "/nologo",
        "/DLL",
        "/OUT:" + outputFile.toString) ++
        linkFlags ++
        objectFiles.map(_.toString)

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process=AbstractProcess("DynamicLibrary", cxxExe, args, getCwd, Map("PATH" -> toolPaths.mkString(";")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }

  override def buildExecutable(
    log: Logger,
    buildDirectory: File,
    exeName: String,
    linkFlags: Seq[String],
    linkPaths: Seq[File],
    linkLibraries: Seq[String],
    inputFiles: Seq[File],
    quiet: Boolean
  ) =
    FunctionWithResultPath(buildDirectory / exeName) { outputFile =>
      val linkArgs: Seq[String] = Seq(
        "/nologo",
        "/OUT:" + outputFile.toString) ++
        inputFiles.map(_.toString) ++
        linkPaths.map(lp => "/LIBPATH:" + lp) ++
        linkLibraries.map(ll => "-l" + ll)

      runProcess(
        tmpDir=buildDirectory,
        log=log,
        process=AbstractProcess("Executable", linkerExe, linkArgs, getCwd, Map("PATH" -> toolPaths.mkString(";")) ),
        mergeToStdout=true,
        quiet=quiet)

      reportFileGenerated(log, outputFile, quiet)
    }
}


