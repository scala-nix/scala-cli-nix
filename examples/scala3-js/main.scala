// Minimal Scala.js (frontend) example. Mirrors the signal-fun web flow:
// `scala-cli package . --js --js-module-kind es` links a single ES module.
// No node — the output JS is meant to be bundled downstream (e.g. by vite).
//> using platform scala-js
//> using scala 3.8.3
//> using jsModuleKind es
//> using jsMode release
//> using options -no-indent
//> using dep org.scala-js::scalajs-dom::2.8.0

import org.scalajs.dom

object Main {
  def main(args: Array[String]): Unit = {
    val msg = "hello from scala-js!"
    dom.console.log(msg)
  }
}
