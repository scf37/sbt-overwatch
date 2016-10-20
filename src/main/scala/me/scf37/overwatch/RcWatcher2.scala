package me.scf37.overwatch

import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchEvent.Kind
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

import com.sun.nio.file.SensitivityWatchEventModifier

import scala.collection.JavaConverters._

case class FileEvent(path: Option[File], kind: Kind[_]) {
  override def toString = kind.toString + " " + path.getOrElse("-")
}

class RcWatcher2(roots : Seq[File], notifyCb: FileEvent => Unit) {
  import RcWatcher2._

  private[this] val watchService: WatchService = FileSystems.getDefault.newWatchService()
  private[this] var watchKeys = Map.empty[Path, WatchKey]

  @volatile
  private[this] var started = true

  private[this] val workerThread = new Thread {
    override def run(): Unit = main()
    setName("rc-watcher")
    setDaemon(true)
    start()
  }

  def close(): Unit = {
    started = false
    workerThread.interrupt()
    workerThread.join(10000)
    watchService.close()
  }

  private[this] def main() = {
    roots.foreach {
      watchDirs(_, notify = false)
    }

    try {
      while (started) {
        pollEvents().foreach(handleEvent)
      }
    } catch {
      case e: InterruptedException =>
    }
  }

  private[this] def handleEvent(event: FileEvent): Unit = event match {
    case e @ FileEvent(Some(path), StandardWatchEventKinds.ENTRY_CREATE) if path.exists() && path.isDirectory =>
      watchDirs(path, notify = true)
      doNotifyCb(e)

    case e =>
      doNotifyCb(e)
  }

  private[this] def doNotifyCb(event: FileEvent): Unit = {
    if (event.path.exists(inRoots)) {
      notifyCb(event)
    }
  }

  private[this] def watchDirs(dir: File, notify: Boolean): Unit = {
    if (!dir.exists()) return

    if (!inRoots(dir)) return

    if (!started) return

    watchDir(dir.toPath)
    Option(dir.listFiles()).getOrElse(Array.empty).foreach { f: File =>
      if (notify) {
        doNotifyCb(FileEvent(Some(f), StandardWatchEventKinds.ENTRY_CREATE))
      }

      if (f.isDirectory) {
        watchDirs(f, notify)
      }
    }
  }

  private[this] def watchDir(dir: Path): Unit = {

    watchKeys.get(dir) match {
      case Some(key) if key.isValid => return
      case _ =>
    }

    var retryCount = 0
    var lastException: Exception = null

    while (retryCount < 3) {
      try {
        if (!started) return

        val watchKey = dir.register(watchService, WATCH_KINDS, WATCH_MODIFIERS: _*)
        watchKeys += dir -> watchKey
        return;
      } catch {

        case e: NoSuchFileException =>
          //dir is missing - silent exit
          return

        case e: FileSystemException if e.getMessage != null && e.getMessage.contains("Bad file descriptor") =>
          // retry after getting "Bad file descriptor" exception
          lastException = e

        case e: IOException =>
          // Windows at least will sometimes throw odd exceptions like java.nio.file.AccessDeniedException
          // if the file gets deleted while the watch is being set up.
          // So, we just ignore the exception if the dir doesn't exist anymore
          if (!dir.toFile.exists()) {
            // return silently when directory doesn't exist
            return
          } else {
            // no retry
            throw e
          }
      }
      retryCount += 1
    }
    throw lastException
  }

  private[this] def pollEvents(): Seq[FileEvent] = {
    val watchKey = watchService.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (watchKey != null)
      extractEvents(watchKey)
    else
      Seq.empty
  }

  private[this] def extractEvents(watchKey: WatchKey): Seq[FileEvent] = {
    val watchedPath = watchKey.watchable.asInstanceOf[Path]

    def transform(event: WatchEvent[_]): FileEvent = {
      val kind = event.kind
      val file = if (kind.`type` eq classOf[Path]) {
        val ev: WatchEvent[Path] = event.asInstanceOf[WatchEvent[Path]]
        Some(watchedPath.resolve(ev.context).toFile)
      } else None

      FileEvent(file, kind)
    }

    val watchEvents = watchKey.pollEvents()
    watchKey.reset()
    if (watchEvents.isEmpty)
      Seq(FileEvent(Some(watchedPath.toFile), StandardWatchEventKinds.ENTRY_DELETE))
    else
      watchEvents.asScala.map(transform)
  }

  private[this] def inRoots(f: File): Boolean = {
    roots.exists(f.getAbsolutePath startsWith _.getAbsolutePath)
  }

}

private object RcWatcher2 {
  final val POLL_TIMEOUT_SECONDS = 5
  final val WATCH_MODIFIERS = Array(SensitivityWatchEventModifier.HIGH)
  final val WATCH_KINDS = Array(
    StandardWatchEventKinds.ENTRY_CREATE,
    StandardWatchEventKinds.ENTRY_DELETE,
    StandardWatchEventKinds.ENTRY_MODIFY).map(_.asInstanceOf[WatchEvent.Kind[_]])
}