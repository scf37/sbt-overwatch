package me.scf37.overwatch

import java.nio.file.FileSystems
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import me.scf37.overwatch.Overwatch.OverwatchFileFilter
import sbt.Def.Initialize
import sbt.Keys._
import sbt._

import scala.language.postfixOps
import scala.util.Try

object OverwatchPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val overwatch = taskKey[Unit]("Start watching over configured directories")
    val overwatchConfiguration = settingKey[Map[OverwatchFileFilter, TaskKey[_]]]("Map of file filter -> task to run. Use overwatchFilter() to build filters")
    def overwatchFilter(root: File, glob: String) = Overwatch.overwatchFilter(root, glob)
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
        Overwatch(conf, Some(log))
      }
    },
    overwatch in Global <<= overwatch,
    overwatchConfiguration := Map.empty
  )
}

object Overwatch {
  private[this] val scheduler = Executors.newSingleThreadScheduledExecutor()
  private[this] var events = Map.empty[RunConfiguration, Seq[FileEvent]]
  private[this] var isSchedulerBusy = false
  val overwatchScanIntervalMs = 200

  case class RunConfiguration(filter: OverwatchFileFilter, taskKey: TaskKey[_], state: State) extends Identity

  /**
    * Watched file filter. To pass, file must match any [[includes]] AND does not match all [[excludes]]
    */
  case class OverwatchFileFilter(root: File, includes: List[File => Boolean], excludes: List[File => Boolean]) extends (File => Boolean) {
    def include(glob: String) = {
      this.copy(includes = fromGlob(glob) :: includes)
    }

    def include(filter: File => Boolean) = {
      this.copy(includes = filter :: includes)
    }

    def exclude(glob: String) = {
      this.copy(excludes = fromGlob(glob) :: excludes)
    }

    def exclude(filter: File => Boolean) = {
      this.copy(excludes = filter :: excludes)
    }

    override def apply(f: File): Boolean = {
      if (!f.getAbsolutePath.startsWith(root.getAbsolutePath)) return false

      includes.exists(_ (f)) && !excludes.exists(_ (f))
    }

    private[this] def fromGlob(glob: String): File => Boolean = {
      val matcher = FileSystems.getDefault.getPathMatcher("glob:" + glob)

      new ((File) => Boolean) {
        override def apply(f: File) = matcher.matches(f.toPath)
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

  def overwatchFilter(root: File, glob: String) = OverwatchFileFilter(root, Nil, Nil)
      .include(glob)

  def apply(config: Seq[RunConfiguration], log: Option[Logger]): Unit = {
    val watcher = new RcWatcher2(config.map(_.filter.root), { event =>
      handler(event, config, log)
    })

    try {
      log.foreach(_.info("Waiting for changes... (press enter to interrupt)"))
      while (System.in.read() != '\n') {}
    } finally {
      watcher.close()
    }
  }

  def handler(ev: FileEvent, config: Seq[RunConfiguration], log: Option[Logger]): Unit = {
    if (ev.path.isEmpty) return
    val path = ev.path.get

    config.foreach { config =>
      if (config.filter(path)) {
        addTask(config, ev, log)
        None
      } else None
    }
  }

  private[this] def addTask(config: RunConfiguration, fileEvent: FileEvent, log: Option[Logger]): Unit = synchronized {
    events += config -> (events.getOrElse(config, Seq.empty) :+ fileEvent)
    if (isSchedulerBusy) return
    scheduleExecution(log)
    isSchedulerBusy = true
  }

  private[this] def scheduleExecution(log: Option[Logger]): Unit = {
    scheduler.schedule(new Runnable {
      override def run() = {

        val eventsToExecute = Overwatch.synchronized {
          val currEvents = events
          events = Map.empty
          currEvents
        }

        println(eventsToExecute.size)

        eventsToExecute.foreach { case(e, files) =>
          files.map(_.path.map(_.toString).getOrElse("-")).distinct.sorted.foreach { f =>
            log.foreach(_.info("Change detected for: " + f))
          }
          try {
            val t = System.nanoTime()
            Project.runTask(e.taskKey, e.state, checkCycles = true) match {
              case None => log.foreach(_.warn(
                s"No such task ${e.taskKey}. Probably wrong scope in configuration," +
                  s" e.g. 'compile in myproject' instead of 'compile in myproject in Compile'"))
              case Some((st, Inc(_))) =>
                log.foreach(_.warn("Task execution failed"))
              case Some((st, Value(_))) =>
                log.foreach(_.info(s"Task execution complete in ${(System.nanoTime() - t)/10000 / 100.0}ms"))
            }
          } catch {
            case e: Exception =>
              log.foreach(_.error(e.toString))
          }
        }

        Overwatch.synchronized {
          if (events.isEmpty) {
            isSchedulerBusy = false
          } else {
            scheduleExecution(log)
          }
        }

      }
    }, overwatchScanIntervalMs, TimeUnit.MILLISECONDS)
  }

  def now: String = {
    val tz = TimeZone.getTimeZone("UTC")
    val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    df.setTimeZone(tz)
    df.format(new Date())
  }
}