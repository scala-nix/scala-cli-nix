package core

import cats.syntax.all.*

object Greeting {
  def words: List[String] = List("hello", "from", "sbt", "multi-module")

  def render: String = words.map(_.some).flatten.mkString(" ")
}
