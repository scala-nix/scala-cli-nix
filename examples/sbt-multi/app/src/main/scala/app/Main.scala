package app

import app.buildinfo.BuildInfo
import core.Greeting

object Main {
  def main(args: Array[String]): Unit =
    println(s"${Greeting.render} (${BuildInfo.name} ${BuildInfo.version})")
}
