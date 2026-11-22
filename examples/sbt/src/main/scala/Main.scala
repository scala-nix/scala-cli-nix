import cats.syntax.all.*

object Main {
  def main(args: Array[String]): Unit = {
    val parts = List("hello", "from", "sbt!").mkString(" ")
    println(parts)
  }
}
