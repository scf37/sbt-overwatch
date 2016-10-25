package me.scf37.overwatch

import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.Executors

import me.scf37.filewatch.ChangeEvent
import me.scf37.filewatch.DeleteEvent
import me.scf37.filewatch.DesyncEvent
import me.scf37.filewatch.FileWatcher
import me.scf37.filewatch.FileWatcherEvent
import me.scf37.filewatch.util.EventDedup
import me.scf37.overwatch.Overwatch.OverwatchFileFilter
import sbt.Def.Initialize
import sbt.Keys._
import sbt._

import scala.language.postfixOps

object OverwatchPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val overwatch = taskKey[Unit]("Start watching over configured directories")
    val overwatchConfiguration = settingKey[Map[OverwatchFileFilter, TaskKey[_]]]("Map of file filter -> task to run. Use overwatchFilter() to build filters")
    def overwatchFilter(root: File, glob: String): OverwatchFileFilter = Overwatch.overwatchFilter(root.toPath, glob)
    def overwatchFilter(root: File, filter: Path => Boolean): OverwatchFileFilter = Overwatch.overwatchFilter(root.toPath, filter)
  }

  import Overwatch.RunConfiguration
  import autoImport._

  def sequential[B](tasks: Seq[Initialize[Task[B]]], head: List[B]): Initialize[Task[List[B]]] =
    tasks.toList match {
      case Nil => Def.task { head }
      case x :: xs =>
        Def.taskDyn {
          val v = x.value
          sequential(xs, v :: head)
        }
    }

  override lazy val globalSettings = Seq(
    overwatch <<= Def.taskDyn {
      val log = streams.value.log
      val config = overwatchConfiguration.value


      val states: Seq[Initialize[Task[RunConfiguration]]] = config.map { case (filter, task) =>
        log.info("Waiting for changes on " + filter)
        val st = state.value
        Def.task[RunConfiguration] {
          RunConfiguration(filter, task, st)
        }
      }.toSeq

      val combinedTask: Initialize[Task[List[RunConfiguration]]] = sequential(states, Nil)

      if (config.isEmpty) {
        log.warn("'overwatchConfiguration' key is empty. Please configure overwatch before use.")
      }

      combinedTask.map { conf =>
        Overwatch(conf, log)
      }
    },
    overwatch in Global <<= overwatch,
    overwatchConfiguration := Map.empty
  )
}

object Overwatch {

  case class RunConfiguration(filter: OverwatchFileFilter, taskKey: TaskKey[_], state: State) extends Identity

  /**
    * Watched file filter. To pass, file must match any [[includes]] AND does not match all [[excludes]]
    */
  case class OverwatchFileFilter(root: Path, includes: List[Path => Boolean], excludes: List[Path => Boolean]) extends (Path => Boolean) {
    def include(glob: String) = {
      this.copy(includes = fromGlob(glob) :: includes)
    }

    def include(filter: Path => Boolean) = {
      this.copy(includes = filter :: includes)
    }

    def exclude(glob: String) = {
      this.copy(excludes = fromGlob(glob) :: excludes)
    }

    def exclude(filter: Path => Boolean) = {
      this.copy(excludes = filter :: excludes)
    }

    override def apply(f: Path): Boolean = {
      if (!f.toAbsolutePath.startsWith(root.toAbsolutePath)) return false

      includes.exists(_ (f)) && !excludes.exists(_ (f))
    }

    private[this] def fromGlob(glob: String): Path => Boolean = {
      val matcher = FileSystems.getDefault.getPathMatcher("glob:" + glob)

      new ((Path) => Boolean) {
        override def apply(f: Path) = matcher.matches(f)
        override def toString() = glob
      }
    }

    override def toString = {
      val path = root.toString
      var s = path
      if (includes.nonEmpty) {
        s += " includes: " + includes.map(_.toString()).mkString(",")
      }
      if (excludes.nonEmpty) {
        s += " excludes: " + excludes.map(_.toString()).mkString(",")
      }
      s
    }
  }

  def overwatchFilter(root: Path, glob: String) = OverwatchFileFilter(root, Nil, Nil)
      .include(glob)

  def overwatchFilter(root: Path, filter: Path => Boolean) = OverwatchFileFilter(root, Nil, Nil)
    .include(filter)

  def apply(config: Seq[RunConfiguration], log: Logger): Unit = {

    val fileEventsDedup = new EventDedup(
      onError = e => log.error(e.toString),
      listener = events => handler(events, config, log)
    )

    val watcher = FileWatcher(
      followLinks = true,
      onError = e => log.error(e.toString),
      listener = fileEventsDedup
    )

    config.foreach { entry =>
      watcher.watch(entry.filter.root, entry.filter)
    }

    try {
      log.info("Waiting for changes... (press enter to interrupt)")
      while (System.in.read() != '\n') {}
    } finally {
      watcher.close()
    }
  }

  def handler(events: Seq[FileWatcherEvent], config: Seq[RunConfiguration], log: Logger): Unit = {
    printSummary(events, log)

    def matches(config: RunConfiguration, event: FileWatcherEvent): Boolean = {
      event match {
        case ChangeEvent(path) => config.filter(path)
        case DeleteEvent(path) => config.filter(path)
        case _ => false
      }
    }

    val configsToExecute = config.filter(c => events.exists(e => matches(c, e)))

    configsToExecute.foreach { cfg =>
      executeTask(cfg, log)
    }

  }

  private[this] def executeTask(e: RunConfiguration, log: Logger): Unit = {
    try {
      val t = System.nanoTime()

      Project.runTask(e.taskKey, e.state, checkCycles = true) match {
        case None => log.warn(
          s"No such task ${e.taskKey}. Probably wrong scope in configuration," +
            s" e.g. 'compile in myproject' instead of 'compile in myproject in Compile'")
        case Some((st, Inc(_))) =>
          log.warn("Task execution failed")
        case Some((st, Value(_))) =>
          log.info(s"Task execution complete in ${(System.nanoTime() - t)/10000 / 100.0}ms")
      }

    } catch {
      case e: Exception =>
        log.error(e.toString)
    }
  }

  private[this] def printSummary(events: Seq[FileWatcherEvent], log: Logger): Unit = {
    val (desyncEvents, fileEvents) = events.partition(_ eq DesyncEvent)

    if (desyncEvents.nonEmpty) {
      log.warn("File watcher desync: possibly missing some changes")
    }

    fileEvents.map {
      case ChangeEvent(p) => "[changed] " + p
      case DeleteEvent(p) => "[deleted] " + p
      case DesyncEvent => ???
    }.sorted.foreach { s =>
      log.info(s)
    }
  }
}