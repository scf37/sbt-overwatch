import java.util.Date

object Main {
  def main(args: Array[String]): Unit = {
    println(new Date() + " Application started!")

    while (true) Thread.sleep(1000)

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = {
        println(new Date() + " Application stopped")
      }
    }))
  }
}
