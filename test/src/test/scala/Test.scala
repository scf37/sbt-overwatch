import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

import org.apache.commons.io.FileUtils
import org.scalatest.FunSuite

/**
  * Created by asm on 07.05.17.
  */
class Test extends FunSuite {

  test("overwatch should react on file change/create/delete") {
    val sbt = new SbtDriver

    def modify(f: String): Unit = {
      val p = sbt.root.resolve(f)
      assert(Files.exists(p))
      Files.write(p, "\n".getBytes, StandardOpenOption.APPEND)
    }

    def create(f: String): Unit = {
      val p = sbt.root.resolve(f)
      assert(!Files.exists(p))
      Files.write(p, "\n".getBytes)
    }

    def delete(f: String): Unit = {
      val p = sbt.root.resolve(f)
      assert(Files.exists(p))
      Files.delete(p)
    }

    def change(f: String, ch: String => String): Unit = {
      val p = sbt.root.resolve(f)
      assert(Files.exists(p))
      val s = new String(Files.readAllBytes(p), "UTF-8")
      Files.write(p, ch(s).getBytes("UTF-8"))
    }

    try {
      sbt.assertPrinted("Application started!", 60)

      //modification without restart works
      modify("src/main/resources/file.css")
      sbt.assertPrinted("css changed", 1)

      modify("src/main/resources/file.js")
      sbt.assertPrinted("js changed", 1)

      //creation without restart works
      create("src/main/resources/file2.css")
      sbt.assertPrinted("css changed", 1)

      create("src/main/resources/file2.js")
      sbt.assertPrinted("js changed", 1)

      //deletion without restart works
      delete("src/main/resources/file2.css")
      sbt.assertPrinted("css changed", 1)

      delete("src/main/resources/file2.js")
      sbt.assertPrinted("js changed", 1)

      //modification of scala files with restart works
      change("src/main/scala/Main.scala", _.replace("started", "start_ed"))
      sbt.assertPrinted("Application start_ed!", 10)
      change("src/main/scala/Main.scala", _.replace("start_ed", "start__ed"))
      sbt.assertPrinted("Application start__ed!", 10)

      //creation of new scala files triggers restart as well
      create("src/main/scala/Main2.scala")
      sbt.assertPrinted("Application start__ed!", 10)

      //deletion of new scala files triggers restart as well
      delete("src/main/scala/Main2.scala")
      sbt.assertPrinted("Application start__ed!", 10)

      //rapid change of scala file eventually leads to running of latest version of the app
      change("src/main/scala/Main.scala", _.replace("start__ed", "started0"))
      for (i <- 1 to 20) {
        Thread.sleep(500)
        change("src/main/scala/Main.scala", _.replace("started" + (i - 1), "started" + i))
      }
      sbt.assertPrinted("Application started20!", 60)

    } finally {
      sbt.kill()
      FileUtils.deleteDirectory(sbt.root.toFile)
    }


  }

  //starts plugin sbt project and allows asserting on its output
  class SbtDriver {
    private[this] var lines = Vector.empty[String]
    private[this] var exitCode: Option[Int] = None
    private[this] var oldOutput = Vector.empty[String] //output already processed

    private[this] val (process, root_) = run()

    def kill(): Unit = {
      process.destroy()
    }

    def root: Path = root_

    //assert process stdout now contains given line
    //this method is stateful so we check lines appeared only after last call
    def assertPrinted(line: String, timeoutSec: Int): Unit = synchronized {
      val assertedOutput = Vector.newBuilder[String]

      val t = System.currentTimeMillis()

      while (System.currentTimeMillis() - t < timeoutSec * 1000) {
        exitCode.foreach { code =>
          failSbt(s"Sbt process already exited with exit code $code\nOutput: ", lines)
        }

        while (lines.nonEmpty) {
          val l = lines.head
          lines = lines.tail
          assertedOutput += l

          if (l.contains(line)) {
            oldOutput = oldOutput ++ assertedOutput.result()
            return
          }
        }

        wait(100)
      }

      failSbt(s"Sbt process output is expected to contain line '$line' but it does not:",
        assertedOutput.result())
    }

    private[this] def failSbt(msg: String, assertedOutput: Seq[String]): Unit = {
      val tail =
        if (assertedOutput.nonEmpty)
          "  << ASSERTED OUTPUT BELOW >>\n" + assertedOutput.mkString("\n")
        else ""

      fail(s"$msg\n" +
        oldOutput.mkString("\n") + tail
      )
    }

    private[this] def prepareWorkspace(): Path = {
      val testRoot = Files.createTempDirectory("overwatch-test")

      val thisProjectRoot = {
        val curr = Paths.get(".").toAbsolutePath.normalize()

        if (curr.endsWith("test"))
          curr
        else if (curr.endsWith("sbt-overwatch/"))
          curr.resolve("test")
        else {
          assume(false, "Can't find out project directory. " +
            s"Current directory is expected to be within this project but was: $curr ")
          ???
        }
      }

      def replaceInFile(path: Path, f: String => String): Unit = {
        val s = new String(Files.readAllBytes(path), "UTF-8")
        Files.write(path, f(s).getBytes("UTF-8"))
      }


      FileUtils.copyDirectory(thisProjectRoot.toFile, testRoot.toFile)

      val currentPluginDef = "lazy val overwatch = RootProject(file(\"../../plugin\"))"
      val newPluginDef = "lazy val overwatch = RootProject(file(\"" + thisProjectRoot.resolve("../plugin") + "\"))"

        replaceInFile(testRoot.resolve("project/build.sbt"),
        _.replace(currentPluginDef, newPluginDef))

      testRoot
    }

    private[this] def run(): (sys.process.Process, Path) = {
      import sys.process._

      val log = new ProcessLogger {
        override def err(s: => String): Unit = synchronized {
          lines = lines :+ s
          notify()
        }

        override def out(s: => String): Unit = synchronized {
          lines = lines :+ s
          notify()
        }

        override def buffer[T](f: => T): T = f
      }

      val testRoot = prepareWorkspace()

      val r = Process(command = "sbt overwatch", cwd = testRoot.toFile).run(log)

      new Thread(() => {
        val code = r.exitValue()
        synchronized {
          exitCode = Some(code)
          notify()
        }
      }).start()

      r -> testRoot
    }
  }
}
