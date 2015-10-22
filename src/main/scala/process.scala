package org.seacourt.build

import sbt._
import Keys._
import java.io.{File, PrintWriter}

case class AbstractProcess(name: String, exe: File, args: Seq[String], wd: File, env: Map[String, String]) {
  def cmdString = (exe.toString +: args).mkString(" ")
}

case class ProcessExecutionResult(retCode: Int, stdout: File, stderr: File) {
  def stdoutLines = IO.readLines(stdout)
  def stderrLines = IO.readLines(stderr)
}

trait AbstractRunner {
  def run(p: AbstractProcess, joinStdOutStdErr : Boolean) : ProcessExecutionResult
  def run(sp: Seq[AbstractProcess], joinStdOutStdErr : Boolean) : Seq[ProcessExecutionResult]
}

class LocalRunner(val tmpDir : File) extends AbstractRunner {
  import scala.sys.process._

  def run(p : AbstractProcess, joinStdOutStdErr : Boolean) : ProcessExecutionResult = {
    val outputId = java.util.UUID.randomUUID().toString()
    val stdoutFile = tmpDir / (outputId + ".stdout")
    val stderrFile = tmpDir / (outputId + ".stderr")

    val stdoutPW = new PrintWriter(stdoutFile)
    val stderrPW = new PrintWriter(stderrFile)

    def writeToStdout(line : String) = this.synchronized {
      stdoutPW.write(line + "\n")
    }

    def writeToStderr(line : String) = {
      if (joinStdOutStdErr) writeToStdout(line)
      else stderrPW.write(line + "\n")
    }

    val retCode = try {
      val pl = ProcessLogger(writeToStdout, writeToStderr)
      Process(p.exe.toString +: p.args, p.wd, p.env.toSeq : _*) ! pl
      } finally {
        stderrPW.close
        stdoutPW.close
      }

      ProcessExecutionResult(retCode, stdoutFile, stderrFile)
  }

  def run(sp : Seq[AbstractProcess], joinStdOutStdErr : Boolean) : Seq[ProcessExecutionResult] = sp.map(run(_, joinStdOutStdErr))
}

object ProcessHelper {
  def runProcess(tmpDir : File, log : Logger, process : AbstractProcess, mergeToStdout : Boolean, quiet : Boolean) : ProcessExecutionResult = {
    val lr = new LocalRunner(tmpDir)
    log.debug("Executing: " + process.cmdString)
    val res = lr.run(process, true)

    if (quiet) {
      IO.readLines(res.stdout).foreach(log.debug(_))
    } else if (res.retCode == 0) {
      IO.readLines(res.stdout).foreach(log.warn(_))
    } else {
      IO.readLines(res.stdout).foreach(log.error(_))
    }

    if (res.retCode != 0) throw new java.lang.RuntimeException("Non-zero exit code: " + res.retCode)

    res
  }
}

