//> using scala 3.8.3
//> using scalacOption -no-indent
//> using dep io.get-coursier:interface:1.0.29-M4
//> using dep io.circe::circe-generic::0.14.15
//> using dep io.circe::circe-parser::0.14.15
//> using dep org.typelevel::cats-effect::3.7.0
//> using dep co.fs2::fs2-io::3.13.0
//> using dep com.github.alexarchambault::case-app::2.1.0
//> using dep org.scala-lang.modules::scala-xml::2.4.0
//> using dep org.http4s::http4s-ember-client::0.23.34

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import caseapp.*
import caseapp.core.RemainingArgs
import caseapp.core.app.Command
import caseapp.core.app.CommandsEntryPoint
import coursierapi.*
import fs2.Stream
import fs2.hashing.{HashAlgorithm, Hashing}
import fs2.io.file.{Files, Path}
import fs2.io.process.ProcessBuilder
import io.circe.{Codec, Decoder, Json, Printer}
import io.circe.parser.parse as parseJson
import io.circe.syntax.*
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.jar.JarFile
import org.http4s.{Request, Status, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import scala.jdk.CollectionConverters.*
import scala.xml.{Node, NodeSeq, XML}

// --- JSON model (lockfile) ---

case class ArtifactEntry(url: String, sha256: String) derives Codec.AsObject

case class NativeLockDeps(
    scalaNativeVersion: String,
    toolingDependencies: List[ArtifactEntry]
) derives Codec.AsObject

case class TestLock(
    sources: List[String],
    resourceDirs: List[String],
    libraryDependencies: List[ArtifactEntry]
) derives Codec.AsObject

case class TargetLock(
    scalaVersion: String,
    platform: String,
    exportHash: String,
    compiler: List[ArtifactEntry],
    libraryDependencies: List[ArtifactEntry],
    native: Option[NativeLockDeps],
    test: Option[TestLock]
) derives Codec.AsObject

case class LockFile(
    version: Int,
    sources: List[String],
    resourceDirs: List[String],
    targets: Map[String, TargetLock]
) derives Codec.AsObject

/** Lockfile shape for the `lock-coords` path: an app packaged straight from
  * Coursier coordinates, no scala-cli sources involved. Discriminated from the
  * scala-cli shape by `kind = "coursier-app"` at the top level; existing files
  * without `kind` are still treated as `kind = "scala-cli"` by the build side
  * (preserves backwards compatibility — no version bump needed).
  *
  * Only JARs are recorded — we don't shell out to scala-cli at build time for
  * this kind, so POMs (needed solely by scala-cli's offline resolver) would be
  * dead weight.
  */
case class CoursierAppLock(
    version: Int,
    kind: String,
    mainClass: String,
    javaOptions: List[String],
    dependencies: List[ArtifactEntry]
) derives Codec.AsObject

// --- JSON model (scala-cli export, only the fields we use) ---

case class ExportArtifactId(fullName: String) derives Decoder

case class ExportDependency(
    groupId: String,
    artifactId: ExportArtifactId,
    version: String
) derives Decoder

case class ExportScope(
    sources: List[String],
    resourceDirs: List[String],
    dependencies: List[ExportDependency],
    /** Deps scala-cli adds to this scope's Coursier resolution beyond what the
      * user declared (JVM test-runner, Native runtime libs, Native
      * test-interface, JS runtime libs, etc.). Populated by `export --json`
      * since the upstream commit "Expose per-scope injected deps in export
      * --json"; older scala-cli versions leave the field absent (decoded as
      * Nil) and we fall back to the hand-rolled injection in `computeLock`.
      */
    injectedDependencies: List[ExportDependency],
    resolvers: List[String]
)
object ExportScope {
  given Decoder[ExportScope] = Decoder.instance { c =>
    for {
      sources <- c.get[List[String]]("sources")
      resourceDirs <- c.getOrElse[List[String]]("resourceDirs")(Nil)
      deps <- c.getOrElse[List[ExportDependency]]("dependencies")(Nil)
      injected <- c.getOrElse[List[ExportDependency]]("injectedDependencies")(
        Nil
      )
      resolvers <- c.getOrElse[List[String]]("resolvers")(Nil)
    } yield ExportScope(sources, resourceDirs, deps, injected, resolvers)
  }
}

case class NativeOptionsExport(
    scalaNativeVersion: String,
    toolingDependencies: List[ExportDependency]
) derives Decoder

case class ExportInfo(
    scalaVersion: String,
    platform: Option[String],
    nativeOptions: Option[NativeOptionsExport],
    scopes: Map[String, ExportScope]
) derives Decoder

// --- Console helpers ---

object C {
  val bold = "\u001b[1m"
  val green = "\u001b[0;32m"
  val blue = "\u001b[0;34m"
  val yellow = "\u001b[0;33m"
  val red = "\u001b[0;31m"
  val dim = "\u001b[2m"
  val reset = "\u001b[0m"
}

def info(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.blue}ℹ${C.reset}  $msg")
def success(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.green}✔${C.reset}  $msg")
def step(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.bold}▶ $msg${C.reset}")
def warn(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.yellow}⚠${C.reset}  $msg")
def error(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.red}✖${C.reset}  $msg")
def errln(msg: String): IO[Unit] = IO.consoleForIO.errorln(msg)

// --- Hashing ---

/** Persisted entry for a hashed file. `size`+`mtime` form a cheap freshness
  * stamp: if either differs from the file's current attributes, we recompute.
  * Coursier-cached artifacts at non-SNAPSHOT coordinates are immutable, so this
  * is overwhelmingly cache-hit territory; SNAPSHOTs and re-downloads still get
  * the right answer because their mtime changes.
  */
case class HashCacheEntry(size: Long, mtime: Long, sha256: String)
    derives Codec.AsObject

case class HashCacheFile(version: Int, entries: Map[String, HashCacheEntry])
    derives Codec.AsObject

private val HashCacheVersion = 1

case class HashCacheStats(hits: Int, misses: Int)

class HashCache(
    state: Ref[IO, Map[String, HashCacheEntry]],
    stats: Ref[IO, HashCacheStats],
    val location: Path
) {
  def get(path: Path): IO[String] =
    Files[IO].getBasicFileAttributes(path).flatMap { attrs =>
      val key = path.toString
      val size = attrs.size
      val mtime = attrs.lastModifiedTime.toMillis
      state.get.flatMap { m =>
        m.get(key) match {
          case Some(e) if e.size == size && e.mtime == mtime =>
            stats.update(s => s.copy(hits = s.hits + 1)).as(e.sha256)
          case _ =>
            computeSha256(path).flatTap { hash =>
              state.update(
                _.updated(key, HashCacheEntry(size, mtime, hash))
              ) *>
                stats.update(s => s.copy(misses = s.misses + 1))
            }
        }
      }
    }

  def currentStats: IO[HashCacheStats] = stats.get

  def save: IO[Unit] =
    state.get
      .flatMap { entries =>
        val payload = HashCacheFile(HashCacheVersion, entries)
        val parent = location.parent.getOrElse(location)
        Files[IO].createDirectories(parent) *>
          writeFile(location, lockfilePrinter.print(payload.asJson) + "\n")
      }
      .attempt
      .void
}

private def computeSha256(path: Path): IO[String] =
  Files[IO]
    .readAll(path)
    .through(Hashing[IO].hash(HashAlgorithm.SHA256))
    .compile
    .lastOrError
    .map(h => Base64.getEncoder.encodeToString(h.bytes.toArray))

object HashCache {

  /** Resolve the on-disk cache location, honoring `XDG_CACHE_HOME` and falling
    * back to `~/.cache/scala-cli-nix/hashes.json`.
    */
  def defaultLocation: IO[Path] = IO {
    val xdg = sys.env.get("XDG_CACHE_HOME").filter(_.nonEmpty)
    val base = xdg.getOrElse(sys.props("user.home") + "/.cache")
    Path(base) / "scala-cli-nix" / "hashes.json"
  }

  /** Load the cache from `location`. Missing or unparseable files give an empty
    * cache — never fatal.
    */
  def load(location: Path): IO[HashCache] =
    for {
      entries <- Files[IO]
        .exists(location)
        .flatMap {
          case false => IO.pure(Map.empty[String, HashCacheEntry])
          case true  =>
            readFile(location).attempt.map {
              case Right(content) =>
                parseJson(content)
                  .flatMap(_.as[HashCacheFile])
                  .toOption
                  .filter(_.version == HashCacheVersion)
                  .map(_.entries)
                  .getOrElse(Map.empty)
              case Left(_) => Map.empty
            }
        }
      stateRef <- Ref.of[IO, Map[String, HashCacheEntry]](entries)
      statsRef <- Ref.of[IO, HashCacheStats](HashCacheStats(0, 0))
    } yield new HashCache(stateRef, statsRef, location)
}

def sha256Base64(path: Path)(using cache: HashCache): IO[String] =
  cache.get(path)

def sha1Hex(s: String): String =
  Hashing
    .hashPureStream(HashAlgorithm.SHA1, Stream.emits(s.getBytes("UTF-8")))
    .bytes
    .toArray
    .map("%02x".format(_))
    .mkString

// --- Process helpers ---

/** Run a process and capture stdout as a string. Stderr is forwarded to our
  * stderr.
  */
def exec(command: String, args: String*): IO[String] =
  ProcessBuilder(command, args.toList)
    .spawn[IO]
    .use { proc =>
      val stdout = proc.stdout.through(fs2.text.utf8.decode).compile.string
      val stderr = proc.stderr.through(fs2.io.stderr[IO]).compile.drain
      (stdout, stderr).parTupled.flatMap { case (out, _) =>
        proc.exitValue.flatMap { code =>
          IO.raiseError(
            new RuntimeException(s"$command exited with code $code")
          ).whenA(code != 0)
            .as(out)
        }
      }
    }

/** Run a process, discard output, return exit code. */
def execCode(command: String, args: String*): IO[Int] =
  ProcessBuilder(command, args.toList)
    .spawn[IO]
    .use { proc =>
      proc.stdout.compile.drain *>
        proc.stderr.compile.drain *>
        proc.exitValue
    }

// --- File helpers ---

def writeFile(path: Path, content: String): IO[Unit] =
  Stream
    .emit(content)
    .through(Files[IO].writeUtf8(path))
    .compile
    .drain

def readFile(path: Path): IO[String] =
  Files[IO].readUtf8(path).compile.string

def absolutePath(s: String): IO[Path] = {
  val p = Path(s)
  if (p.isAbsolute) IO.pure(p)
  else Files[IO].currentWorkingDirectory.map(_ / p)
}

// --- Coursier helpers ---

/** Extra Maven repositories to add to every Coursier fetch — populated from
  * `//> using repository` directives (read from scala-cli's export JSON) on the
  * `lock` path, and from `--repository` flags on `lock-coords`. Maven Central
  * is always implicitly included by Coursier's defaults; we only add the
  * non-default repos here.
  *
  * Credentials are loaded by Coursier itself from
  * `~/.config/coursier/credentials.properties` (and `COURSIER_CREDENTIALS` /
  * `COURSIER_CONFIG_DIR`), so we don't have to wire them through.
  */
case class Repos(urls: List[String]) {
  def mavenRepositories: List[MavenRepository] =
    urls.distinct.map(MavenRepository.of(_))
}
object Repos {
  val empty: Repos = Repos(Nil)
}

/** Filter scala-cli's `resolvers[]` (from `export --json`) down to extra Maven
  * repos worth passing to Coursier:
  *
  *   - Only `http(s)://` URLs — Ivy / local-file resolvers can't be expressed
  *     through `coursierapi.MavenRepository`, and local user caches aren't
  *     reproducible inputs anyway.
  *   - Maven Central is dropped because Coursier's defaults already include it;
  *     re-adding it would log a duplicate.
  *   - Order is preserved, duplicates removed.
  */
def extraRepoUrlsFromResolvers(resolvers: List[String]): List[String] = {
  val mavenCentralUrl = "https://repo1.maven.org/maven2"
  resolvers
    .filter(url => url.startsWith("https://") || url.startsWith("http://"))
    .filterNot(_.stripSuffix("/") == mavenCentralUrl)
    .distinct
}

def fetchArtifacts(
    deps: Dependency*
)(using repos: Repos): IO[List[(Artifact, File)]] =
  IO.blocking {
    val base = Fetch.create().addDependencies(deps*)
    val withRepos = repos.mavenRepositories match {
      case Nil => base
      case rs  => base.addRepositories(rs*)
    }
    val result = withRepos.fetchResult()
    result.getArtifacts().asScala.toList.map(e => (e.getKey, e.getValue))
  }

val cacheDir: File = Cache.create().getLocation()
val cachePath: Path = Path.fromNioPath(cacheDir.toPath)

def cacheFileForUrl(url: String): File = {
  val relative = url.replaceFirst("://", "/").replace("+", "%2B")
  File(cacheDir, relative)
}

def cachePathForUrl(url: String): Path =
  Path.fromNioPath(cacheFileForUrl(url).toPath)

def findPomForJar(jarUrl: String): IO[Option[Path]] = {
  val directPom = cachePathForUrl(jarUrl.stripSuffix(".jar") + ".pom")
  Files[IO].exists(directPom).flatMap {
    case true  => IO.pure(Some(directPom))
    case false =>
      // Classifier JAR: .../artifactId/version/artifactId-version-classifier.jar
      val jarFile = cacheFileForUrl(jarUrl)
      val dir = jarFile.getParentFile
      val version = dir.getName
      val artifactId = dir.getParentFile.getName
      val basePom =
        Path.fromNioPath(File(dir, s"$artifactId-$version.pom").toPath)
      Files[IO].exists(basePom).map(Option.when(_)(basePom))
  }
}

def urlForCachePath(file: Path): String = {
  val relative = file.toString.stripPrefix(cachePath.toString + "/")
  val proto = relative.takeWhile(_ != '/')
  val rest = relative.drop(proto.length + 1).replace("%2B", "+")
  s"$proto://$rest"
}

/** Parse a POM as XML. Throws on malformed XML — POMs in the Coursier cache are
  * well-formed by construction.
  */
private def loadPom(content: String): Node = XML.loadString(content)

private def childText(parent: NodeSeq, label: String): Option[String] =
  (parent \ label).headOption.map(_.text.trim).filter(_.nonEmpty)

private def parseGAV(node: NodeSeq): Option[(String, String, String)] =
  for {
    g <- childText(node, "groupId")
    a <- childText(node, "artifactId")
    v <- childText(node, "version")
  } yield (g, a, v)

def parseParent(content: String): Option[(String, String, String)] =
  (loadPom(content) \ "parent").headOption.flatMap(parseGAV(_))

def extractParent(pomPath: Path): IO[Option[(String, String, String)]] =
  readFile(pomPath).map(parseParent)

/** Given a POM URL and its coordinates, derive the repository base URL by
  * stripping the Maven layout suffix
  * `<groupPath>/<artifact>/<version>/<artifact>-<version>.pom`. Returns `""`
  * when the URL doesn't match (e.g. classifier'd POM, non-Maven repo layout) —
  * treated as "skip" by downstream callers.
  */
def repoBaseFromCoords(
    pomUrl: String,
    groupId: String,
    artifactId: String,
    version: String
): String = {
  val groupPath = groupId.replace('.', '/')
  val suffix = s"$groupPath/$artifactId/$version/$artifactId-$version.pom"
  if (pomUrl.endsWith(suffix)) pomUrl.dropRight(suffix.length)
  else ""
}

/** Parse the POM's <properties> block (top-level only) into a name->value map.
  * Values may themselves contain placeholders (`${name}`) that resolve against
  * properties higher in the parent chain — caller is responsible for combining
  * maps and resolving recursively if needed.
  */
def parseProperties(content: String): Map[String, String] =
  (loadPom(content) \ "properties").headOption
    .map { props =>
      props.child.collect { case e: scala.xml.Elem =>
        e.label -> e.text.trim
      }.toMap
    }
    .getOrElse(Map.empty)

/** Substitute `${name}` placeholders in `value` using `props`. Resolves
  * indirections by re-substituting until stable or until a fixed iteration cap
  * is hit. Unresolved placeholders are left intact.
  */
def substituteProperties(value: String, props: Map[String, String]): String = {
  val placeholder = "\\$\\{([^}]+)\\}".r
  def step(s: String): String =
    placeholder.replaceAllIn(
      s,
      m =>
        java.util.regex.Matcher
          .quoteReplacement(props.getOrElse(m.group(1), m.matched))
    )
  Iterator
    .iterate(value)(step)
    .sliding(2)
    .find(p => p.head == p.last)
    .map(_.head)
    .getOrElse(value)
}

/** Best-effort POM coordinate extraction. Falls back to <parent>'s groupId and
  * version when the POM itself doesn't redeclare them.
  */
def parsePomCoords(content: String): Option[(String, String, String)] = {
  val root = loadPom(content)
  val parent = (root \ "parent").headOption
  val artifactId = childText(root, "artifactId")
  val groupId =
    childText(root, "groupId").orElse(parent.flatMap(childText(_, "groupId")))
  val version =
    childText(root, "version").orElse(parent.flatMap(childText(_, "version")))
  (groupId, artifactId, version).tupled
}

/** Repo base for a POM, derived by parsing its coordinates from the POM content
  * and matching against `pomUrl`. Returns `""` when coords can't be parsed or
  * the URL doesn't follow Maven layout.
  */
def repoBaseForPom(pomPath: Path, pomUrl: String): IO[String] =
  readFile(pomPath).map { content =>
    parsePomCoords(content) match {
      case Some((g, a, v)) => repoBaseFromCoords(pomUrl, g, a, v)
      case None            => ""
    }
  }

/** Walk a POM's parent chain and merge their <properties> blocks, with
  * descendants overriding ancestors (Maven inheritance semantics). The POM at
  * `pomPath` is included.
  */
def collectAccumulatedProperties(
    pomPath: Path,
    repoBase: String
): IO[Map[String, String]] = {
  def loop(
      current: Path,
      acc: Map[String, String]
  ): IO[Map[String, String]] =
    readFile(current).flatMap { content =>
      // Parent's properties go in first so child's override on conflict.
      val ownProps = parseProperties(content)
      extractParent(current).flatMap {
        case Some((g, a, v)) if repoBase.nonEmpty =>
          val groupPath = g.replace('.', '/')
          val parentUrl = s"$repoBase$groupPath/$a/$v/$a-$v.pom"
          val parentPath = cachePathForUrl(parentUrl)
          Files[IO].exists(parentPath).flatMap {
            case true =>
              loop(parentPath, acc).map(parentAcc => parentAcc ++ ownProps)
            case false => IO.pure(acc ++ ownProps)
          }
        case _ => IO.pure(acc ++ ownProps)
      }
    }
  loop(pomPath, Map.empty)
}

def collectParentPoms(
    pomPath: Path,
    repoBase: String
)(using HashCache): IO[List[ArtifactEntry]] = {
  def loop(current: Path, acc: List[ArtifactEntry]): IO[List[ArtifactEntry]] =
    extractParent(current).flatMap {
      case Some((groupId, artifactId, version)) if repoBase.nonEmpty =>
        val groupPath = groupId.replace('.', '/')
        val url =
          s"$repoBase$groupPath/$artifactId/$version/$artifactId-$version.pom"
        val parentPomPath = cachePathForUrl(url)
        Files[IO].exists(parentPomPath).flatMap {
          case true =>
            sha256Base64(parentPomPath).flatMap { hash =>
              loop(parentPomPath, ArtifactEntry(url, hash) :: acc)
            }
          case false =>
            IO.pure(acc.reverse)
        }
      case _ =>
        IO.pure(acc.reverse)
    }
  loop(pomPath, Nil)
}

/** Capture POM-only artifacts imported as BOMs in the given POM's
  * <dependencyManagement>. Recursively follows the BOM's own parent chain and
  * any nested BOM imports. Returns an empty list when the BOM POM is absent
  * from the Coursier cache (e.g. because Coursier didn't end up needing it
  * during lock-time resolution).
  */
/** Collect all (g,a,v) BOM imports declared by `pomPath` and any POM in its
  * parent chain, with property substitution at each level using that level's
  * accumulated properties.
  */
private def collectBomCoordsFromChain(
    pomPath: Path,
    repoBase: String
): IO[List[(String, String, String)]] = {
  def loop(
      current: Path,
      acc: List[(String, String, String)]
  ): IO[List[(String, String, String)]] =
    for {
      content <- readFile(current)
      props <- collectAccumulatedProperties(current, repoBase)
      projectVersion = parsePomVersion(content)
      boms = parseImportedBoms(content, projectVersion, props)
      next <- extractParent(current).flatMap {
        case Some((g, a, v)) if repoBase.nonEmpty =>
          val groupPath = g.replace('.', '/')
          val parentUrl = s"$repoBase$groupPath/$a/$v/$a-$v.pom"
          val parentPath = cachePathForUrl(parentUrl)
          Files[IO].exists(parentPath).flatMap {
            case true  => loop(parentPath, acc ++ boms)
            case false => IO.pure(acc ++ boms)
          }
        case _ => IO.pure(acc ++ boms)
      }
    } yield next
  loop(pomPath, Nil)
}

def collectImportedBoms(
    pomPath: Path,
    repoBase: String,
    visited: Set[String] = Set.empty
)(using HashCache): IO[List[ArtifactEntry]] =
  if (repoBase.isEmpty) IO.pure(Nil)
  else
    collectBomCoordsFromChain(pomPath, repoBase).flatMap { boms =>
      boms.distinct
        .foldLeftM((List.empty[ArtifactEntry], visited)) {
          case ((acc, seen), (g, a, v)) =>
            val key = s"$g:$a:$v"
            if (seen.contains(key)) IO.pure((acc, seen))
            else {
              val groupPath = g.replace('.', '/')
              val url = s"$repoBase$groupPath/$a/$v/$a-$v.pom"
              val bomPomPath = cachePathForUrl(url)
              Files[IO].exists(bomPomPath).flatMap {
                case false => IO.pure((acc, seen + key))
                case true  =>
                  for {
                    hash <- sha256Base64(bomPomPath)
                    bomEntry = ArtifactEntry(url, hash)
                    parents <- collectParentPoms(bomPomPath, repoBase)
                    nestedSeen = seen + key
                    nested <- collectImportedBoms(
                      bomPomPath,
                      repoBase,
                      nestedSeen
                    )
                  } yield (acc ++ (bomEntry :: parents) ++ nested, nestedSeen)
              }
            }
        }
        .map(_._1)
    }

/** Pure version of extractDeclaredDeps. Returns (groupId, artifactId, version)
  * tuples for each <dependency> in the POM's <dependencies> section. Excludes
  * deps inside <dependencyManagement>. May include unresolved property
  * placeholders like ${project.version} — caller filters those out.
  */
def parseDeclaredDeps(pomContent: String): List[(String, String, String)] = {
  val root = loadPom(pomContent)
  (root \ "dependencies" \ "dependency").toList.flatMap(parseGAV(_))
}

def extractDeclaredDeps(pomPath: Path): IO[List[(String, String, String)]] =
  readFile(pomPath).map(parseDeclaredDeps)

/** Top-level POM version. Falls back to the parent's <version> when missing
  * (POM inherits the version from its parent).
  */
def parsePomVersion(pomContent: String): Option[String] = {
  val root = loadPom(pomContent)
  childText(root, "version").orElse {
    (root \ "parent").headOption.flatMap(childText(_, "version"))
  }
}

/** Returns BOM imports declared inside <dependencyManagement>: deps with
  * <scope>import</scope> (typically also <type>pom</type>). Resolves
  * `${project.version}` against the containing POM's version when known.
  */
def parseImportedBoms(
    pomContent: String,
    projectVersion: Option[String],
    properties: Map[String, String] = Map.empty
): List[(String, String, String)] = {
  val root = loadPom(pomContent)
  val effectiveProps =
    properties ++ projectVersion.map("project.version" -> _).toMap
  val deps = root \ "dependencyManagement" \ "dependencies" \ "dependency"
  deps.toList.flatMap { dep =>
    val isImport = childText(dep, "scope").contains("import")
    if (!isImport) Nil
    else
      parseGAV(dep).toList.flatMap { (g, a, v) =>
        val resolved = substituteProperties(v, effectiveProps)
        Option.when(!resolved.contains("$"))((g, a, resolved))
      }
  }
}

/** Walk all resolved POMs to find declared deps and fetch their JAR + POM
  * individually. This captures evicted versions that scala-cli may try to fetch
  * during offline resolution.
  *
  * Each declared dep is fetched as its own resolution. Unlike
  * `withTransitive(false)` which fails on some artifacts in the Coursier
  * interface, this works reliably. Failures are tolerated (e.g. for
  * placeholder/marker artifacts).
  */
def collectDeclaredPoms(
    resolvedPomPaths: List[Path],
    resolvedUrls: Set[String]
)(using HashCache, Repos): IO[List[ArtifactEntry]] =
  resolvedPomPaths.flatTraverse(extractDeclaredDeps).flatMap { allDeclared =>
    val candidates = allDeclared.distinct
      .filterNot { (_, _, v) => v.contains("$") }
      // Skip resolved winners (their JAR URL would already appear in resolvedUrls)
      .filterNot { (g, a, v) =>
        resolvedUrls.exists { u =>
          u.contains(s"${g.replace('.', '/')}/$a/$v/")
        }
      }

    candidates.flatTraverse { (g, a, v) =>
      val dep = Dependency.of(g, a, v)
      fetchArtifacts(dep).attempt.flatMap {
        case Right(arts) => collectEntriesNoRecurse(arts)
        case Left(_)     => IO.pure(List.empty[ArtifactEntry])
      }
    }
  }

/** Like collectEntries but does not recursively walk declared deps (avoids
  * infinite loop).
  */
def collectEntriesNoRecurse(
    artifacts: List[(Artifact, File)]
)(using HashCache, Repos): IO[List[ArtifactEntry]] =
  artifacts
    .flatTraverse { (artifact, file) =>
      val url = artifact.getUrl
      val artifactPath = Path.fromNioPath(file.toPath)

      sha256Base64(artifactPath).flatMap { jarHash =>
        val jarEntry = ArtifactEntry(url, jarHash)

        findPomForJar(url).flatMap {
          case None          => IO.pure(List(jarEntry))
          case Some(pomPath) =>
            val pomUrl = {
              val direct = url.replaceFirst("\\.jar$", ".pom")
              if (cachePathForUrl(direct).toString == pomPath.toString) direct
              else urlForCachePath(pomPath)
            }
            sha256Base64(pomPath).flatMap { pomHash =>
              for {
                repoBase <- repoBaseForPom(pomPath, pomUrl)
                parentEntries <- collectParentPoms(pomPath, repoBase)
                bomEntries <- collectImportedBoms(pomPath, repoBase)
              } yield jarEntry :: ArtifactEntry(
                pomUrl,
                pomHash
              ) :: parentEntries ++ bomEntries
            }
        }
      }
    }
    .map(_.distinctBy(_.url).sortBy(_.url))

def collectEntries(
    artifacts: List[(Artifact, File)]
)(using HashCache, Repos): IO[List[ArtifactEntry]] = {
  val resolved: IO[(List[ArtifactEntry], List[Path])] =
    artifacts.foldLeftM((List.empty[ArtifactEntry], List.empty[Path])) {
      case ((entries, poms), (artifact, file)) =>
        val url = artifact.getUrl
        val artifactPath = Path.fromNioPath(file.toPath)

        sha256Base64(artifactPath).flatMap { jarHash =>
          val jarEntry = ArtifactEntry(url, jarHash)

          findPomForJar(url).flatMap {
            case None          => IO.pure((jarEntry :: entries, poms))
            case Some(pomPath) =>
              val pomUrl = {
                val direct = url.replaceFirst("\\.jar$", ".pom")
                if (cachePathForUrl(direct).toString == pomPath.toString) direct
                else urlForCachePath(pomPath)
              }
              sha256Base64(pomPath).flatMap { pomHash =>
                for {
                  repoBase <- repoBaseForPom(pomPath, pomUrl)
                  parentEntries <- collectParentPoms(pomPath, repoBase)
                  bomEntries <- collectImportedBoms(pomPath, repoBase)
                } yield {
                  val newEntries =
                    jarEntry :: ArtifactEntry(
                      pomUrl,
                      pomHash
                    ) :: parentEntries ++ bomEntries ++ entries
                  (newEntries, pomPath :: poms)
                }
              }
          }
        }
    }

  for {
    (entries, pomPaths) <- resolved
    resolvedUrls = entries.map(_.url).toSet
    declaredPoms <- collectDeclaredPoms(pomPaths, resolvedUrls)
  } yield mergeWinnersAndDeclared(entries, declaredPoms)
}

def isJarUrl(e: ArtifactEntry): Boolean = e.url.endsWith(".jar")

/** The `…/<groupPath>/<artifact>` portion of a Maven URL — i.e. the URL with
  * its `/<version>/<file>` suffix stripped. Two URLs share this prefix iff
  * they're the same (group, artifact) coordinate.
  */
def groupArtifactPath(e: ArtifactEntry): String =
  e.url.replaceFirst("/[^/]+/[^/]+$", "")

/** Combine winners (from the resolved transitive fetch) with the
  * declared-evicted POM/JAR set, dropping any declared JAR whose (group,
  * artifact) is already represented by a winner. Extra JARs for the same
  * coordinate would otherwise shadow the winner's classes on the runtime
  * classpath (different versions => NoSuchMethodError). POMs of evicted
  * versions are kept — scala-cli's offline resolver still walks them.
  */
def mergeWinnersAndDeclared(
    winners: List[ArtifactEntry],
    declared: List[ArtifactEntry]
): List[ArtifactEntry] = {
  val winnerGroupArtifacts =
    winners.filter(isJarUrl).map(groupArtifactPath).toSet
  val filteredDeclared = declared.filterNot { e =>
    isJarUrl(e) && winnerGroupArtifacts.contains(groupArtifactPath(e))
  }
  (winners ++ filteredDeclared).distinctBy(_.url).sortBy(_.url)
}

// --- scala-cli helpers ---

// When launched via the Nix-wrapped `scala-cli-nix`, the wrapper sets
// SCALA_CLI_NIX_SCALA_CLI to an absolute path of the bundled scala-cli build.
// Outside that wrapper (e.g. running the CLI directly during development),
// fall back to whatever `scala-cli` is on PATH.
val resolveScalaCli: IO[String] =
  IO(sys.env.getOrElse("SCALA_CLI_NIX_SCALA_CLI", "scala-cli"))

// --- Target discovery ---

/** A cross-build target: one (platform, scalaVersion) combination. */
case class Target(platform: String, scalaVersion: Option[String]) {

  /** Platform name for the --platform flag (jvm -> jvm, native ->
    * scala-native).
    */
  def platformFlag: String = platform match {
    case "native" => "scala-native"
    case other    => other
  }

  /** Platform name stored in the lockfile (jvm -> JVM, native -> Native). */
  def platformLock: String = platform match {
    case "native" => "Native"
    case _        => "JVM"
  }
}

/** One entry in the JSON output of `scala-cli --power list-targets`. */
case class ListTargetEntry(platform: String, scalaVersion: Option[String])
    derives Decoder

/** Ask scala-cli for the full build matrix. The CLI emits one entry per
  * declared (platform, scalaVersion) combination, so we don't have to parse
  * `using` directives ourselves.
  */
def listTargets(scalaCli: String, inputArgs: List[String]): IO[List[Target]] =
  exec(scalaCli, ("--power" :: "list-targets" :: inputArgs)*)
    .flatMap { json =>
      IO.fromEither(
        parseJson(json)
          .flatMap(_.as[List[ListTargetEntry]])
          .leftMap(e =>
            new RuntimeException(
              s"Failed to parse list-targets JSON: ${e.getMessage}"
            )
          )
      )
    }
    .map(_.map { e =>
      val platform = e.platform match {
        case "Native" => "native"
        case _        => "jvm"
      }
      Target(platform, e.scalaVersion)
    })

/** Compute the lock key for a target given the full target list. */
def targetKey(target: Target, allTargets: List[Target]): String = {
  val multiPlatform = allTargets.map(_.platform).distinct.sizeIs > 1
  val multiVersion = allTargets.map(_.scalaVersion).distinct.sizeIs > 1
  (multiPlatform, multiVersion) match {
    case (true, true) =>
      s"${target.platform}-${target.scalaVersion.getOrElse("default")}"
    case (true, false)  => target.platform
    case (false, true)  => target.scalaVersion.getOrElse("default")
    case (false, false) => target.platform
  }
}

// --- Option case classes ---

case class LockOptions(
    @HelpMessage(
      "Source directory to lock. When set, scala-cli reads sources from <src> and the lockfile records source paths relative to <src>. The lockfile is still written to the current working directory. Useful for locking projects whose sources live outside the lockfile's directory (e.g. a Nix store path)."
    )
    src: Option[String] = None
)
case class InitOptions(
    ref: Option[String] = None
)

case class LockCoordsOptions(
    @HelpMessage(
      "Coursier coordinate (org:name:version or org::name::version). Repeatable."
    )
    dep: List[String] = Nil,
    @HelpMessage(
      "Also search the contrib channel (io.get-coursier:apps-contrib) when looking up the positional app name. Mirrors `cs install --contrib`."
    )
    contrib: Boolean = false,
    @HelpMessage(
      "Additional channel as a Maven coord (org:name) of the channel artifact. App descriptors are read from `<name>/resources/<app>.json` inside the channel JAR. Repeatable; searched after the default (and contrib, if enabled) channels."
    )
    channel: List[String] = Nil,
    @HelpMessage(
      "Extra Maven repository URL to add to Coursier resolution (e.g. an Artifactory virtual repo). Repeatable. Credentials are loaded from ~/.config/coursier/credentials.properties (or COURSIER_CREDENTIALS / COURSIER_CONFIG_DIR)."
    )
    repository: List[String] = Nil,
    @HelpMessage(
      "Main class for raw --dep mode. Optional: if omitted, auto-discovered from the Main-Class attribute in the --dep JARs' META-INF/MANIFEST.MF."
    )
    mainClass: Option[String] = None,
    @HelpMessage(
      "Scala version used to expand `::` / `:::` coordinates (descriptors and --dep). Default: 3.3.0. Set to 2.13.12 for apps published only for 2.13 (e.g. smithy4s)."
    )
    scalaBinary: String = "3.3.0",
    @HelpMessage("Output lockfile path (default: scala.lock.json).")
    @Name("o")
    output: Option[String] = None
)

// --- Lock command ---

val hashPrinter: Printer = Printer.noSpaces.copy(sortKeys = true)
val lockfilePrinter: Printer =
  Printer.spaces2.copy(sortKeys = true, colonLeft = "", dropNullValues = true)

/** Compute lockfile content without writing it. Always recomputes from scratch.
  */
def computeLock(inputs: List[String], sourceRoot: Option[Path])(using
    HashCache
): IO[String] = {
  // When `--src` is set, relative positional args (e.g. specific files to lock)
  // are resolved under the source root rather than cwd, so that
  // `scn lock --src /nix/store/xxx foo.scala` locks `xxx/foo.scala`. With no
  // positionals, the source root itself is the input. Without `--src`, behavior
  // is unchanged: positionals stay verbatim, or default to ".".
  val inputArgs = sourceRoot match {
    case Some(root) if inputs.isEmpty => List(root.toString)
    case Some(root)                   =>
      inputs.map { i =>
        val p = Path(i)
        if (p.isAbsolute) i else (root / p).toString
      }
    case None => if (inputs.nonEmpty) inputs else List(".")
  }

  for {
    scalaCli <- resolveScalaCli
    cwd <- Files[IO].currentWorkingDirectory
    basePath = sourceRoot.getOrElse(cwd)

    targets <- listTargets(scalaCli, inputArgs)

    _ <- info(
      s"Targets: ${C.bold}${targets.map(t => targetKey(t, targets)).mkString(", ")}${C.reset}"
    )

    // Top-level sources/resourceDirs are the UNION across every target's
    // export. A cross project can scope a source to one platform with a
    // `//> using target.platform <jvm|scala-native>` directive, so each
    // target's export lists a different set of files. Reading only one
    // target's export would silently drop the others' platform-scoped
    // sources and break those targets' Nix builds (the sandbox filters the
    // source tree by this list). Listing every target's sources here is safe:
    // the directive — not the filename — gates which files each platform
    // compiles, so a file scoped to another platform is simply ignored.
    _ <- step("Discovering sources...")
    perTargetScopes <- targets.traverse { target =>
      val versionArgs =
        target.scalaVersion.toList.flatMap(v => List("--scala-version", v))
      for {
        exportJson <- exec(
          scalaCli,
          ("--power" :: "export" :: "--json" :: "--server=false" ::
            "--platform" :: target.platformFlag :: versionArgs ++ inputArgs)*
        )
        export_ <- IO.fromEither(
          parseJson(exportJson)
            .flatMap(_.as[ExportInfo])
            .leftMap(e =>
              new RuntimeException(
                s"Failed to parse export JSON: ${e.getMessage}"
              )
            )
        )
      } yield export_.scopes
        .getOrElse("main", ExportScope(Nil, Nil, Nil, Nil, Nil))
    }
    sources = perTargetScopes
      .flatMap(_.sources.map(stripBase(basePath)))
      .distinct
    resourceDirs = perTargetScopes
      .flatMap(_.resourceDirs.map(stripBase(basePath)))
      .distinct

    targetLocks <- targets.traverse { target =>
      val key = targetKey(target, targets)
      computeTargetLock(scalaCli, inputArgs, basePath, target, key).map(
        key -> _
      )
    }

    lockFile = LockFile(
      version = 9,
      sources = sources,
      resourceDirs = resourceDirs,
      targets = targetLocks.toMap
    )
  } yield lockfilePrinter.print(lockFile.asJson) + "\n"
}

private def computeTargetLock(
    scalaCli: String,
    inputArgs: List[String],
    basePath: Path,
    target: Target,
    key: String
)(using HashCache): IO[TargetLock] = {
  val versionArgs =
    target.scalaVersion.toList.flatMap(v => List("--scala-version", v))
  val exportArgs =
    "--power" :: "export" :: "--json" :: "--server=false" ::
      "--platform" :: target.platformFlag :: versionArgs ++ inputArgs

  for {
    _ <- step(s"Exporting target ${C.bold}$key${C.reset}...")
    exportJson <- exec(scalaCli, exportArgs*)
    rawJson <- IO.fromEither(
      parseJson(exportJson).leftMap(e =>
        new RuntimeException(s"Failed to parse export JSON: ${e.message}")
      )
    )
    exportHash = sha1Hex(hashPrinter.print(rawJson) + "\n")
    export_ <- IO.fromEither(
      rawJson
        .as[ExportInfo]
        .leftMap(e =>
          new RuntimeException(s"Failed to decode export JSON: ${e.message}")
        )
    )
    result <- computeTargetLockContent(
      scalaCli,
      inputArgs,
      basePath,
      target,
      key,
      export_,
      exportHash
    )
  } yield result
}

private def stripBase(base: Path)(s: String): String =
  s.stripPrefix(base.toString + "/")
    .stripPrefix("/private" + base.toString + "/")

private def computeTargetLockContent(
    scalaCli: String,
    inputArgs: List[String],
    basePath: Path,
    target: Target,
    key: String,
    export_ : ExportInfo,
    exportHash: String
)(using HashCache): IO[TargetLock] = {
  val scalaVersion = export_.scalaVersion
  val scalaMajor = scalaVersion.takeWhile(_ != '.')
  val mainScope =
    export_.scopes.getOrElse("main", ExportScope(Nil, Nil, Nil, Nil, Nil))
  val testScope = export_.scopes.get("test")
  val deps = mainScope.dependencies.map { d =>
    s"${d.groupId}:${d.artifactId.fullName}:${d.version}"
  }
  // scala-cli reports the active resolver URLs (including ones added via
  // `//> using repository <url>`). We feed the non-default Maven repos into
  // every Coursier `Fetch` for this target so transitive resolution can find
  // artifacts that only live in private/Artifactory repos.
  val nonDefaultRepoUrls = extraRepoUrlsFromResolvers(mainScope.resolvers)
  given Repos = Repos(nonDefaultRepoUrls)

  scalaMajor match {
    case "3" | "2" =>
      val (compilerArtifact, libraryArtifact) = scalaMajor match {
        case "3" =>
          (
            Dependency.of("org.scala-lang", "scala3-compiler_3", scalaVersion),
            Dependency.of("org.scala-lang", "scala3-library_3", scalaVersion)
          )
        case _ =>
          (
            Dependency.of("org.scala-lang", "scala-compiler", scalaVersion),
            Dependency.of("org.scala-lang", "scala-library", scalaVersion)
          )
      }

      def toDeps(eds: List[ExportDependency]): List[Dependency] =
        eds.map(d => Dependency.of(d.groupId, d.artifactId.fullName, d.version))

      // scala-cli builds main and test as two separate `Build` scopes, each
      // with its own Coursier resolution. Per scope, the effective direct-dep
      // set is the user's deps plus scala-cli's own `injectedDependencies`
      // (Native/JS runtime libs + nscplugin for both scopes; JVM test-runner /
      // Native test-interface / JS test-bridge for Test only). Resolving each
      // scope with its full direct-dep set keeps the lockfile's winners
      // aligned with what `scala-cli package --offline` / `test --offline`
      // expects — including cases where a test framework outranks a main-scope
      // dep (e.g. munit 1.3.0 pulling javalib_native 0.5.11 while the main
      // scope pins 0.5.10).
      val mainInjected = toDeps(mainScope.injectedDependencies)
      val testInjected =
        testScope.map(s => toDeps(s.injectedDependencies)).getOrElse(Nil)

      for {
        _ <- info(s"Scala version: ${C.bold}$scalaVersion${C.reset}")
        _ <- info(s"Platform: ${C.bold}${target.platformLock}${C.reset}")
        _ <- info(s"Found ${C.bold}${deps.size}${C.reset} dependencies")
        _ <- nonDefaultRepoUrls.traverse_(url =>
          info(s"Extra repository: ${C.bold}$url${C.reset}")
        )

        _ <- step("Fetching compiler dependencies...")
        compilerArtifacts <- fetchArtifacts(compilerArtifact)
        _ <- info(
          s"Compiler: ${C.bold}${compilerArtifacts.size}${C.reset} artifacts"
        )

        _ <- step("Fetching library dependencies...")
        userDeps = deps.map { dep =>
          val parts = dep.split(":")
          Dependency.of(parts(0), parts(1), parts(2))
        }
        allLibDeps = (libraryArtifact +: userDeps) ++ mainInjected
        libArtifacts <- fetchArtifacts(allLibDeps*)
        _ <- info(
          s"Libraries: ${C.bold}${libArtifacts.size}${C.reset} artifacts (transitive)"
        )

        nativeLockDeps <- export_.nativeOptions.traverse { opts =>
          for {
            _ <- step("Fetching native tooling dependencies...")
            toolingArtifacts <- fetchArtifacts(
              toDeps(opts.toolingDependencies)*
            )
            _ <- info(
              s"Native tooling: ${C.bold}${toolingArtifacts.size}${C.reset} artifacts"
            )
            toolingEntries <- collectEntries(toolingArtifacts)
          } yield NativeLockDeps(
            scalaNativeVersion = opts.scalaNativeVersion,
            toolingDependencies = toolingEntries
          )
        }

        // Test scope: scala-cli runs a separate Coursier resolution that
        // combines user-declared test deps with scope-specific injections (JVM
        // test-runner, Native test-interface, JS test-bridge, plus the
        // platform runtime libs that also appear in the main scope). The
        // injections come from `tScope.injectedDependencies` when scala-cli
        // populates that field; otherwise `injectedFor` reconstructs them.
        testLock <- testScope
          .filter(s =>
            s.sources.nonEmpty || s.dependencies != mainScope.dependencies
          )
          .traverse { tScope =>
            val testDeps = tScope.dependencies.map(d =>
              Dependency.of(d.groupId, d.artifactId.fullName, d.version)
            )
            val allTestDeps = (libraryArtifact +: testDeps) ++ testInjected
            for {
              _ <- step("Fetching test dependencies...")
              testArtifacts <- fetchArtifacts(allTestDeps*)
              _ <- info(
                s"Test: ${C.bold}${testArtifacts.size}${C.reset} artifacts (transitive)"
              )
              testEntries <- collectEntries(testArtifacts)
            } yield TestLock(
              sources = tScope.sources.map(stripBase(basePath)),
              resourceDirs = tScope.resourceDirs.map(stripBase(basePath)),
              libraryDependencies = testEntries
            )
          }

        _ <- step("Hashing artifacts...")
        compilerEntries <- collectEntries(compilerArtifacts)
        libEntries <- collectEntries(libArtifacts)
      } yield TargetLock(
        scalaVersion = scalaVersion,
        platform = target.platformLock,
        exportHash = exportHash,
        compiler = compilerEntries,
        libraryDependencies = libEntries,
        native = nativeLockDeps,
        test = testLock
      )

    case _ =>
      error(
        s"Unsupported Scala major version: $scalaMajor (from $scalaVersion)"
      ) *>
        IO.raiseError(
          new RuntimeException(
            s"Unsupported Scala major version: $scalaMajor (from $scalaVersion)"
          )
        )
  }
}

/** Load the persistent hash cache, run `body` with it in scope, and save the
  * cache on the way out (even on failure, so partial progress isn't lost).
  */
def withHashCache[A](body: HashCache ?=> IO[A]): IO[A] =
  for {
    location <- HashCache.defaultLocation
    cache <- HashCache.load(location)
    result <- body(using cache).guarantee {
      cache.currentStats.flatMap { s =>
        info(
          s"Hash cache: ${C.bold}${s.hits}${C.reset} hits, ${C.bold}${s.misses}${C.reset} misses ${C.dim}(${cache.location})${C.reset}"
        )
      } *> cache.save
    }
  } yield result

def lock(inputs: List[String], sourceRoot: Option[String]): IO[ExitCode] =
  withHashCache {
    for {
      resolvedRoot <- sourceRoot.traverse(absolutePath)
      content <- computeLock(inputs, resolvedRoot)
      cwd <- Files[IO].currentWorkingDirectory
      lockfilePath = cwd / "scala.lock.json"
      existingContent <- Files[IO]
        .exists(lockfilePath)
        .ifM(readFile(lockfilePath), IO.pure(""))
      _ <-
        if (existingContent == content)
          info("Lock is up to date.")
        else
          step("Writing lockfile...") *>
            writeFile(lockfilePath, content) *>
            success(s"Wrote ${C.bold}scala.lock.json${C.reset}")
    } yield ExitCode.Success
  }

// --- Init command ---

private val ShaPattern = "^[0-9a-f]{40}$".r

private def pinSuffix(value: String): String =
  if (ShaPattern.matches(value)) s"?rev=$value"
  else s"?ref=$value"

def resolveScalaCliNixUrl(ref: Option[String]): IO[String] = {
  val baseUrl = "github:scala-nix/scala-cli-nix"
  ref match {
    case None                         => IO.pure(baseUrl)
    case Some(value) if value.isEmpty => IO.pure(baseUrl)
    case Some(value)                  => IO.pure(s"$baseUrl${pinSuffix(value)}")
  }
}

def init(
    inputs: List[String],
    ref: Option[String]
)(using Client[IO]): IO[ExitCode] =
  for {
    cwd <- Files[IO].currentWorkingDirectory
    lockExists <- Files[IO].exists(cwd / "scala.lock.json")
    result <-
      if (lockExists)
        info(
          s"${C.bold}scala.lock.json${C.reset} already exists, running ${C.bold}lock${C.reset} instead."
        ) *> lock(inputs, sourceRoot = None)
      else
        inputs match {
          case singleUrl :: Nil if looksLikeGitHubUrl(singleUrl) =>
            IO.fromEither(
              parseGitHubUrl(singleUrl)
                .leftMap(msg => new RuntimeException(msg))
            ).flatMap(doInitFromGitHub(cwd, _, ref))
          case _ if inputs.nonEmpty =>
            doInit(cwd, inputs, ref)
          case _ =>
            Files[IO]
              .list(cwd)
              .filter(_.extName == ".scala")
              .compile
              .toList
              .flatMap { scalaFiles =>
                if (scalaFiles.isEmpty)
                  error("No .scala files found in current directory.")
                    .as(ExitCode.Error)
                else
                  doInit(cwd, inputs, ref)
              }
        }
  } yield result

private def looksLikeGitHubUrl(s: String): Boolean = {
  val lower = s.toLowerCase
  lower.startsWith("https://github.com/") || lower.startsWith(
    "http://github.com/"
  )
}

private def doInit(
    cwd: Path,
    inputs: List[String],
    ref: Option[String]
): IO[ExitCode] = withHashCache {
  val pname = cwd.fileName.toString

  def prepareDerivation(
      isCross: Boolean
  ): IO[(List[(Path, String)], List[String])] =
    Files[IO].exists(cwd / "derivation.nix").flatMap {
      case true =>
        warn("derivation.nix already exists, skipping.")
          .as((Nil, Nil))
      case false =>
        val buildFn = if (isCross) "buildScalaCliApps" else "buildScalaCliApp"
        val content =
          s"""{ scala-cli-nix }:
             |
             |scala-cli-nix.$buildFn {
             |  pname = "$pname";
             |  version = "0.1.0";
             |  src = ./.;
             |  lockFile = ./scala.lock.json;
             |}
             |""".stripMargin
        IO.pure(
          (List(cwd / "derivation.nix" -> content), List("derivation.nix"))
        )
    }

  def prepareFlake(
      isCross: Boolean,
      scalaCliNixUrl: String
  ): IO[(List[(Path, String)], List[String])] =
    Files[IO].exists(cwd / "flake.nix").flatMap {
      case true =>
        errln("") *>
          warn("flake.nix already exists. Add the following to your flake:") *>
          errln("") *>
          errln(s"  ${C.bold}1.${C.reset} Add the input:") *>
          errln("") *>
          errln(
            s"""    ${C.dim}scala-cli-nix.url = "$scalaCliNixUrl";${C.reset}"""
          ) *>
          errln(
            s"""    ${C.dim}scala-cli-nix.inputs.nixpkgs.follows = "nixpkgs";${C.reset}"""
          ) *>
          errln("") *>
          errln(s"  ${C.bold}2.${C.reset} Apply the overlay to nixpkgs:") *>
          errln("") *>
          errln(s"    ${C.dim}pkgs = import nixpkgs {${C.reset}") *>
          errln(s"    ${C.dim}  inherit system;${C.reset}") *>
          errln(
            s"    ${C.dim}  overlays = [ scala-cli-nix.overlays.default ];${C.reset}"
          ) *>
          errln(s"    ${C.dim}};${C.reset}") *>
          errln("") *>
          errln(s"  ${C.bold}3.${C.reset} Add the package(s):") *>
          errln("") *>
          errln(
            if (isCross)
              s"    ${C.dim}# buildScalaCliApps returns an attrset — flatten into packages:${C.reset}\n" +
                s"    ${C.dim}pkgs.callPackage ./derivation.nix { }${C.reset}"
            else
              s"    ${C.dim}packages.default = pkgs.callPackage ./derivation.nix { };${C.reset}"
          ) *>
          errln("") *>
          errln(
            s"  ${C.bold}4.${C.reset} Expose tests as checks (so ${C.dim}nix flake check${C.reset} runs them):"
          ) *>
          errln("") *>
          errln(
            s"    ${C.dim}checks.$${system} = pkgs.scala-cli-nix.collectChecks self.packages.$${system};${C.reset}"
          ) *>
          errln("") *>
          errln(
            s"  ${C.bold}5.${C.reset} Add to your devShell (provides ${C.dim}scala-cli-nix${C.reset} and the ${C.dim}scn${C.reset} alias):"
          ) *>
          errln("") *>
          errln(s"    ${C.dim}pkgs.scala-cli${C.reset}") *>
          errln(s"    ${C.dim}pkgs.scala-cli-nix-cli${C.reset}") *>
          errln("").as((Nil, Nil))
      case false =>
        // For cross projects, flatten the attrset from buildScalaCliApps into named packages
        // (e.g. packages.${system}.jvm, packages.${system}.native) — no default package.
        // For cross projects, callPackage returns an attrset { jvm = ...; native = ...; }
        // which becomes the value of `packages.${system}` directly. For single-target,
        // wrap it as { default = ...; } so `nix build` works without a target name.
        val packagesExpr =
          if (isCross) "pkgs.callPackage ./derivation.nix { }"
          else "{ default = pkgs.callPackage ./derivation.nix { }; }"
        val content =
          s"""|{
              |  inputs = {
              |    nixpkgs.url = "github:NixOS/nixpkgs";
              |    scala-cli-nix.url = "$scalaCliNixUrl";
              |    scala-cli-nix.inputs.nixpkgs.follows = "nixpkgs";
              |  };
              |
              |  outputs = { self, nixpkgs, scala-cli-nix, ... }:
              |    let
              |      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
              |    in {
              |      packages = forAllSystems (system:
              |        let
              |          pkgs = import nixpkgs {
              |            inherit system;
              |            overlays = [ scala-cli-nix.overlays.default ];
              |          };
              |        in $packagesExpr
              |      );
              |
              |      # Pull `passthru.tests` from every package into checks, so
              |      # `nix flake check` runs the test scope of each target.
              |      checks = forAllSystems (system:
              |        let
              |          pkgs = import nixpkgs {
              |            inherit system;
              |            overlays = [ scala-cli-nix.overlays.default ];
              |          };
              |        in pkgs.scala-cli-nix.collectChecks self.packages.$${system}
              |      );
              |
              |      devShells = forAllSystems (system:
              |        let
              |          pkgs = import nixpkgs {
              |            inherit system;
              |            overlays = [ scala-cli-nix.overlays.default ];
              |          };
              |        in {
              |          default = pkgs.mkShell {
              |            buildInputs = [
              |              pkgs.scala-cli
              |              pkgs.scala-cli-nix-cli
              |            ];
              |          };
              |        }
              |      );
              |    };
              |}
              |""".stripMargin
        IO.pure((List(cwd / "flake.nix" -> content), List("flake.nix")))
    }

  for {
    _ <- errln("")
    _ <- errln(
      s"${C.bold}Initializing scala-cli-nix project: ${C.green}$pname${C.reset}"
    )
    _ <- errln("")
    scalaCli <- resolveScalaCli
    scalaCliNixUrl <- resolveScalaCliNixUrl(ref)
    targets <- listTargets(scalaCli, inputs)
    isCross = targets.sizeIs > 1
    derivation <- prepareDerivation(isCross)
    flake <- prepareFlake(isCross, scalaCliNixUrl)
    _ <- errln("")
    lockContent <- computeLock(inputs, sourceRoot = None)
    pendingFiles =
      derivation._1 ++ flake._1 ++ List(
        (cwd / "scala.lock.json") -> lockContent
      )
    fileNames = derivation._2 ++ flake._2 ++ List("scala.lock.json")
    _ <- step("Writing files...")
    _ <- pendingFiles.traverse_ { case (path, content) =>
      writeFile(path, content)
    }
    _ <- pendingFiles.traverse_ { case (path, _) =>
      success(s"Wrote ${C.bold}${path.fileName}${C.reset}")
    }
    _ <- errln("")
    isGit <- execCode("git", "rev-parse", "--is-inside-work-tree")
    _ <-
      if (isGit == 0 && fileNames.nonEmpty)
        step("Staging generated files...") *>
          exec("git", ("add" :: fileNames)*).void *>
          success(s"Staged ${C.bold}${fileNames.mkString(" ")}${C.reset}")
      else
        IO.unit
    _ <- errln(
      s"${C.bold}Done!${C.reset} Run ${C.green}nix build${C.reset} to build your project."
    )
  } yield ExitCode.Success
}

/** GitHub-URL flavoured `init`. Resolves the URL to a pinned commit + tarball,
  * writes a fetchFromGitHub-shaped `derivation.nix` next to a freshly-locked
  * `scala.lock.json`. Unlike the local `doInit`, this path doesn't scaffold a
  * `flake.nix` — external builds live inside the host repo's flake, not
  * standalone. We print a hint for Scala-Native projects that may need
  * `attrOverrides`-supplied native libs (libcurl, etc.).
  */
private def doInitFromGitHub(
    cwd: Path,
    repo: GitHubRepo,
    scalaCliNixRef: Option[String]
)(using Client[IO]): IO[ExitCode] = withHashCache {
  for {
    _ <- errln("")
    _ <- step(
      s"Resolving ${C.bold}${repo.owner}/${repo.repo}${C.reset}" +
        repo.ref.fold("")(r => s" @ ${C.bold}$r${C.reset}") + "..."
    )
    resolved <- resolveRev(repo)
    _ <- info(s"Commit: ${C.bold}${resolved.rev}${C.reset}")

    _ <- step("Computing version...")
    version <- computeVersion(resolved)
    _ <- info(s"Version: ${C.bold}$version${C.reset}")

    fetched <- prefetchTarball(resolved)
    _ <- info(s"Source: ${C.dim}${fetched.storePath}${C.reset}")

    derivationPath = cwd / "derivation.nix"
    lockPath = cwd / "scala.lock.json"

    derivationExists <- Files[IO].exists(derivationPath)
    _ <-
      if (derivationExists)
        warn(s"${C.bold}derivation.nix${C.reset} already exists, skipping.")
      else IO.unit

    // scala-cli refuses to evaluate `//> using computeVersion git:dynver`
    // when there's no .git (it materializes the source into its virtual-projects
    // cache, which is .git-less). GitHub tarballs never have one. We copy the
    // tarball to a writable dir, strip the directive, and lock against the copy.
    // The generated derivation embeds a matching `runCommand` wrapper so the
    // build step does the same.
    sanitizedSrc <- sanitizeSrcForDynver(fetched.storePath)
    needsDynverPatch = sanitizedSrc != fetched.storePath

    _ <- step("Locking dependencies...")
    lockContent <- computeLock(Nil, sourceRoot = Some(sanitizedSrc))
    isNative = lockContent.contains("\"platform\": \"Native\"")

    derivationContent = renderGitHubDerivation(
      pname = repo.repo,
      version = version,
      resolved = resolved,
      sha256Sri = fetched.sha256Sri,
      needsDynverPatch = needsDynverPatch
    )

    _ <-
      if (derivationExists) IO.unit
      else writeFile(derivationPath, derivationContent)
    _ <- writeFile(lockPath, lockContent)

    _ <- errln("")
    _ <- success(
      s"Wrote ${C.bold}derivation.nix${C.reset} and ${C.bold}scala.lock.json${C.reset}."
    )
    _ <-
      if (isNative) errln("") *> printNativeHint()
      else IO.unit
  } yield ExitCode.Success
}

/** Render the external-build `derivation.nix`. Single-target shape: the
  * `target =` arg of `buildScalaCliApp` is omitted because we expect one target
  * per external build (multi-target projects can switch to `buildScalaCliApps`,
  * but that's a manual edit and rare for upstreams that don't already package
  * themselves).
  */
def renderGitHubDerivation(
    pname: String,
    version: String,
    resolved: ResolvedRepo,
    sha256Sri: String,
    needsDynverPatch: Boolean
): String = {
  val args =
    if (needsDynverPatch) "{ scala-cli-nix, fetchFromGitHub, runCommand }"
    else "{ scala-cli-nix, fetchFromGitHub }"

  val fetchSection =
    s"""  src = fetchFromGitHub {
       |    owner = "${resolved.owner}";
       |    repo = "${resolved.repo}";
       |    rev = "${resolved.rev}";
       |    hash = "$sha256Sri";
       |  };""".stripMargin

  val srcSection =
    if (!needsDynverPatch)
      fetchSection
    else
      s"""  # The upstream `project.scala` declares `//> using computeVersion git:dynver`,
         |  # which scala-cli refuses to evaluate without a real .git directory. Strip
         |  # it so the build matches what `scn init` saw at lock time (BuildInfo falls
         |  # back to `projectVersion = None`).
         |  src =
         |    let
         |      raw = fetchFromGitHub {
         |        owner = "${resolved.owner}";
         |        repo = "${resolved.repo}";
         |        rev = "${resolved.rev}";
         |        hash = "$sha256Sri";
         |      };
         |    in runCommand "${resolved.repo}-src-${resolved.shortRev}" { } ''
         |      cp -r $${raw} $$out
         |      chmod -R u+w $$out
         |      find $$out -name '*.scala' -exec sed -i '/^\\/\\/> using computeVersion git:dynver$$/d' {} +
         |    '';""".stripMargin

  s"""$args:
     |
     |scala-cli-nix.buildScalaCliApp {
     |  pname = "$pname";
     |  version = "$version";
     |$srcSection
     |  lockFile = ./scala.lock.json;
     |}
     |""".stripMargin
}

/** Check whether any .scala file at `src` carries
  * `//> using computeVersion git:dynver`, and if so produce a writable copy
  * with the directive stripped. Returns the original path unchanged when no
  * patch is needed.
  *
  * We use `find -exec grep -l ...` to detect, then `cp -r` + `sed -i` to patch.
  * The temp dir is created under `$TMPDIR`; we leave it on disk because the
  * caller may still be reading from it (lock writes its results before
  * returning) — the OS cleans up on reboot.
  */
def sanitizeSrcForDynver(src: Path): IO[Path] = {
  val dynverPattern = "//> using computeVersion git:dynver"
  for {
    hits <- exec(
      "sh",
      "-c",
      s"""find ${shellEscape(
          src.toString
        )} -name '*.scala' -exec grep -l -F ${shellEscape(
          dynverPattern
        )} {} + || true"""
    )
    needsPatch = hits.trim.nonEmpty
    result <-
      if (!needsPatch) IO.pure(src)
      else {
        for {
          _ <- info(
            s"Source uses ${C.dim}git:dynver${C.reset}; patching for lock + build."
          )
          tmp <- exec("mktemp", "-d", "-t", "scn-init-XXXXXX").map(_.trim)
          dest = Path(tmp) / "src"
          _ <- exec("cp", "-r", src.toString, dest.toString)
          _ <- exec("chmod", "-R", "u+w", dest.toString)
          _ <- exec(
            "sh",
            "-c",
            s"""find ${shellEscape(
                dest.toString
              )} -name '*.scala' -exec sed -i.bak '/^\\/\\/> using computeVersion git:dynver$$/d' {} + && find ${shellEscape(
                dest.toString
              )} -name '*.bak' -delete"""
          )
        } yield dest
      }
  } yield result
}

private def shellEscape(s: String): String =
  "'" + s.replace("'", "'\\''") + "'"

private def printNativeHint(): IO[Unit] =
  errln(
    s"""${C.bold}Hint:${C.reset} this is a Scala Native project. If `nix build` fails with a
       |linker error, the binary likely needs extra native libs at link time
       |(e.g. libcurl for sttp's CurlBackend, libidn2 on Linux). Pass them via
       |`attrOverrides`:
       |
       |  ${C.dim}{ scala-cli-nix, fetchFromGitHub, lib, stdenv, curl, libidn2 }:${C.reset}
       |
       |  ${C.dim}scala-cli-nix.buildScalaCliApp {${C.reset}
       |  ${C.dim}  ...${C.reset}
       |  ${C.dim}  attrOverrides = attrs: _platform: attrs // {${C.reset}
       |  ${C.dim}    buildInputs = (attrs.buildInputs or [])${C.reset}
       |  ${C.dim}      ++ [ curl ]${C.reset}
       |  ${C.dim}      ++ lib.optional stdenv.isLinux libidn2;${C.reset}
       |  ${C.dim}  };${C.reset}
       |  ${C.dim}}${C.reset}""".stripMargin
  )

// --- GitHub URL handling (for `init <url>`) ---

/** A GitHub repo reference parsed from a browser URL:
  *   - `https://github.com/<owner>/<repo>` → ref = None
  *   - `https://github.com/<owner>/<repo>/tree/<ref>` → ref = Some(<ref>)
  * `<ref>` is anything: a branch, a tag, a commit sha. We resolve it to a
  * concrete sha later (see `resolveRev`).
  */
case class GitHubRepo(owner: String, repo: String, ref: Option[String])

case class ResolvedRepo(owner: String, repo: String, rev: String) {
  def shortRev: String = rev.take(7)
  def archiveUrl: String =
    s"https://github.com/$owner/$repo/archive/$rev.tar.gz"
}

case class FetchedRepo(
    resolved: ResolvedRepo,
    sha256Sri: String,
    storePath: Path
)

/** Parse a GitHub web URL into owner/repo/ref. Trailing `.git` is tolerated;
  * anything past `/tree/<ref>` (e.g. `/tree/main/path/inside`) is rejected
  * because we don't have a notion of subdirectory roots yet.
  */
def parseGitHubUrl(url: String): Either[String, GitHubRepo] = {
  val trimmed = url.trim.stripSuffix("/")
  val stripped = trimmed
    .stripPrefix("https://")
    .stripPrefix("http://")

  if (!stripped.startsWith("github.com/"))
    Left(s"Not a github.com URL: $url")
  else {
    val parts = stripped.stripPrefix("github.com/").split('/').toList
    parts match {
      case owner :: rawRepo :: Nil =>
        Right(GitHubRepo(owner, rawRepo.stripSuffix(".git"), None))
      case owner :: rawRepo :: "tree" :: ref :: Nil =>
        Right(GitHubRepo(owner, rawRepo.stripSuffix(".git"), Some(ref)))
      case owner :: rawRepo :: "tree" :: _ =>
        Left(
          s"Subdirectory refs (`/tree/<ref>/<path>`) are not supported: $url"
        )
      case _ =>
        Left(s"Unrecognised github.com URL shape: $url")
    }
  }
}

private val Sha1HexPattern = "^[0-9a-f]{40}$".r

/** GitHub API base — kept here so test code can override (and so we touch one
  * place if we ever proxy through a fork).
  */
private val GitHubApiBase: Uri =
  Uri.unsafeFromString("https://api.github.com")

/** Issue a GET against the GitHub REST API and return the response body, or
  * fail with an HTTP-coded error. `Accept: application/vnd.github+json` is
  * standard for v3 endpoints; no auth is sent — unauthenticated requests are
  * fine for public repos at the rate we run them (one or two per `init`).
  */
private def githubApiGet(
    path: String
)(using client: Client[IO]): IO[String] = {
  val uri = GitHubApiBase.withPath(Uri.Path.unsafeFromString(path))
  val req = Request[IO](uri = uri).putHeaders(
    org.http4s.headers.Accept(
      org.http4s.MediaType.unsafeParse("application/vnd.github+json")
    ),
    org.http4s.headers.`User-Agent`(
      org.http4s.ProductId("scala-cli-nix", Some("0.1"))
    )
  )
  client.run(req).use { resp =>
    resp.bodyText.compile.string.flatMap { body =>
      resp.status match {
        case Status.Ok => IO.pure(body)
        case sc        =>
          IO.raiseError(
            new RuntimeException(
              s"GitHub API GET $path failed: HTTP ${sc.code} ${sc.reason}\n$body"
            )
          )
      }
    }
  }
}

/** Resolve a ref to a commit sha. If `ref` is already a 40-char hex sha, skip
  * the API call. Otherwise GET /repos/:owner/:repo/commits/:ref — GitHub
  * returns the resolved commit (and follows branches & tags). When `ref` is
  * None we resolve the repo's default branch via the repo metadata.
  */
def resolveRev(
    repo: GitHubRepo
)(using Client[IO]): IO[ResolvedRepo] =
  repo.ref match {
    case Some(r) if Sha1HexPattern.matches(r) =>
      IO.pure(ResolvedRepo(repo.owner, repo.repo, r))
    case refOpt =>
      val refToResolve = refOpt match {
        case Some(r) => IO.pure(r)
        case None    =>
          githubApiGet(s"/repos/${repo.owner}/${repo.repo}").flatMap { body =>
            IO.fromEither(
              parseJson(body)
                .flatMap(_.hcursor.downField("default_branch").as[String])
                .leftMap(e =>
                  new RuntimeException(
                    s"Could not read default_branch from repo metadata: ${e.getMessage}"
                  )
                )
            )
          }
      }
      refToResolve.flatMap { r =>
        githubApiGet(s"/repos/${repo.owner}/${repo.repo}/commits/$r").flatMap {
          body =>
            IO.fromEither(
              parseJson(body)
                .flatMap(_.hcursor.downField("sha").as[String])
                .leftMap(e =>
                  new RuntimeException(
                    s"Could not read sha from commit metadata: ${e.getMessage}"
                  )
                )
            ).map(sha => ResolvedRepo(repo.owner, repo.repo, sha))
        }
      }
  }

/** Pick the highest-semver tag that is an ancestor of `rev`. Returns `None`
  * when no tag is reachable (fresh repo, or branch with no semver tag in its
  * history yet). Tags are matched as `v?<major>.<minor>.<patch>`; tags with
  * pre-release suffixes are ignored to keep the comparison simple.
  *
  * The reachability check uses `/compare/<tag>...<rev>`: if GitHub reports
  * `status == "ahead"` or `"identical"`, the tag is an ancestor. `behind` means
  * the tag is on a divergent branch; `diverged` means both.
  */
case class TagInfo(name: String, sha: String, semver: (Int, Int, Int))

private val SemverTagPattern = """^v?(\d+)\.(\d+)\.(\d+)$""".r

def listSemverTags(
    owner: String,
    repo: String
)(using Client[IO]): IO[List[TagInfo]] =
  githubApiGet(s"/repos/$owner/$repo/tags?per_page=100").flatMap { body =>
    IO.fromEither(
      parseJson(body).leftMap(e =>
        new RuntimeException(s"Failed to parse tags JSON: ${e.getMessage}")
      )
    ).map { json =>
      json.asArray.getOrElse(Vector.empty).toList.flatMap { entry =>
        for {
          name <- entry.hcursor.downField("name").as[String].toOption
          sha <- entry.hcursor
            .downField("commit")
            .downField("sha")
            .as[String]
            .toOption
          sv <- name match {
            case SemverTagPattern(maj, min, pat) =>
              for {
                m <- maj.toIntOption
                n <- min.toIntOption
                p <- pat.toIntOption
              } yield (m, n, p)
            case _ => None
          }
        } yield TagInfo(name, sha, sv)
      }
    }
  }

/** Output of GitHub's compare endpoint for the fields we use. */
case class CompareResult(status: String, aheadBy: Int)

def compareCommits(
    owner: String,
    repo: String,
    base: String,
    head: String
)(using Client[IO]): IO[CompareResult] =
  githubApiGet(s"/repos/$owner/$repo/compare/$base...$head").flatMap { body =>
    IO.fromEither(
      parseJson(body)
        .flatMap { json =>
          val c = json.hcursor
          for {
            status <- c.downField("status").as[String]
            ahead <- c.downField("ahead_by").as[Int]
          } yield CompareResult(status, ahead)
        }
        .leftMap(e =>
          new RuntimeException(
            s"Failed to parse compare JSON: ${e.getMessage}"
          )
        )
    )
  }

/** Format a dynver-style version string.
  * `<latest-reachable-tag>-<ahead>-<short-sha>` when a tag is reachable from
  * `rev` and we're at least one commit ahead; the plain tag (no suffix) when
  * `rev` *is* the tag; `0-unstable-<short-sha>` when no semver tag is
  * reachable.
  */
def computeVersion(
    resolved: ResolvedRepo
)(using Client[IO]): IO[String] = {
  val fallback = s"0-unstable-${resolved.shortRev}"
  listSemverTags(resolved.owner, resolved.repo).flatMap { tags =>
    // Walk tags newest-semver first; the first one we find that is an ancestor
    // (status == ahead | identical) wins. This avoids comparing every tag when
    // the repo's latest is the most likely match.
    val candidates =
      tags.sortBy(_.semver)(using Ordering[(Int, Int, Int)].reverse)
    def firstAncestor(
        remaining: List[TagInfo]
    ): IO[Option[(TagInfo, CompareResult)]] =
      remaining match {
        case Nil       => IO.pure(None)
        case t :: rest =>
          compareCommits(resolved.owner, resolved.repo, t.sha, resolved.rev)
            .flatMap {
              case cmp @ CompareResult("identical" | "ahead", _) =>
                IO.pure(Some((t, cmp)))
              case _ =>
                firstAncestor(rest)
            }
      }
    firstAncestor(candidates).map {
      case None                                       => fallback
      case Some((tag, CompareResult("identical", _))) =>
        tag.name.stripPrefix("v")
      case Some((tag, CompareResult(_, ahead))) =>
        s"${tag.name.stripPrefix("v")}-$ahead-${resolved.shortRev}"
    }
  }
}

/** Prefetch the tarball via `nix-prefetch-url --unpack --print-path`, returning
  * the SRI sha256 and the unpacked store path. We rely on the user's `nix`
  * command being on PATH — this is true for anyone running `scn` outside the
  * sandbox.
  */
def prefetchTarball(resolved: ResolvedRepo): IO[FetchedRepo] = {
  val url = resolved.archiveUrl
  for {
    _ <- step(s"Fetching tarball ${C.dim}$url${C.reset}...")
    out <- exec("nix-prefetch-url", "--unpack", "--print-path", url)
    lines = out.linesIterator.toList.filter(_.nonEmpty)
    parsed <- lines match {
      case base32 :: path :: Nil =>
        for {
          sri <- exec("nix", "hash", "convert", "--hash-algo", "sha256", base32)
            .map(_.trim)
        } yield FetchedRepo(resolved, sri, Path(path))
      case _ =>
        IO.raiseError(
          new RuntimeException(
            s"nix-prefetch-url returned unexpected output:\n$out"
          )
        )
    }
  } yield parsed
}

// --- lock-coords command ---

/** Subset of a coursier-apps channel JSON descriptor we actually use. The full
  * schema lives at https://github.com/coursier/apps — this captures only the
  * `dependencies`, `mainClass`, and `javaOptions` fields. Other fields like
  * `repositories`, `properties`, `exclusions` are ignored for v1; we resolve
  * against Maven Central via the Coursier interface defaults.
  */
case class ContribChannelApp(
    dependencies: List[String],
    mainClass: Option[String],
    javaOptions: List[String]
)
object ContribChannelApp {
  given Decoder[ContribChannelApp] = Decoder.instance { c =>
    for {
      deps <- c.getOrElse[List[String]]("dependencies")(Nil)
      mainClass <- c.get[Option[String]]("mainClass")
      javaOpts <- c.getOrElse[List[String]]("javaOptions")(Nil)
    } yield ContribChannelApp(deps, mainClass, javaOpts)
  }
}

/** A coursier "channel" is a Maven artifact whose resources/ directory holds
  * `<name>.json` app descriptors. We support two shapes:
  *
  *   - `Builtin`: the two well-known channels in github.com/coursier/apps. We
  *     hard-code their GitHub raw paths instead of resolving the channel
  *     artifact from Maven, which keeps the common case network-light (one HTTP
  *     GET per descriptor, no JAR fetch).
  *   - `Maven`: an arbitrary `org:name` channel artifact. We resolve the latest
  *     version via Coursier and read app descriptors from
  *     `<name>/resources/<app>.json` inside the resulting JAR.
  */
enum Channel {
  case Builtin(builtinLabel: String, raw: String)
  case Maven(org: String, name: String)

  def label: String = this match {
    case Builtin(l, _) => l
    case Maven(o, n)   => s"$o:$n"
  }
}

object Channel {
  val Default: Channel = Builtin(
    "default",
    "https://raw.githubusercontent.com/coursier/apps/main/apps/resources"
  )
  val Contrib: Channel = Builtin(
    "contrib",
    "https://raw.githubusercontent.com/coursier/apps/main/apps-contrib/resources"
  )

  /** Parse a `org:name` Maven channel coord. */
  def parseMaven(coord: String): Either[String, Channel] =
    coord.split(":", -1).toList match {
      case org :: name :: Nil if org.nonEmpty && name.nonEmpty =>
        Right(Maven(org, name))
      case _ =>
        Left(
          s"Invalid --channel value '$coord': expected `org:name` (e.g. `io.get-coursier:apps`)."
        )
    }
}

/** Parse a JSON descriptor body into a `ContribChannelApp`. `source` is shown
  * in the error message — e.g. the URL or `org:name` it was read from.
  */
private def parseAppDescriptor(
    name: String,
    source: String,
    body: String
): IO[ContribChannelApp] =
  IO.fromEither(
    parseJson(body)
      .flatMap(_.as[ContribChannelApp])
      .leftMap(e =>
        new RuntimeException(
          s"Failed to parse $name descriptor from $source: ${e.getMessage}"
        )
      )
  )

/** Fetch one app descriptor from a builtin (GitHub-raw) channel. Returns None
  * on 404 so callers can fall back to another channel; raises on other HTTP
  * errors.
  */
private def fetchAppFromBuiltin(
    raw: String,
    name: String
)(using client: Client[IO]): IO[Option[ContribChannelApp]] = {
  val url = s"$raw/$name.json"
  IO.fromEither(
    Uri
      .fromString(url)
      .leftMap(f =>
        new RuntimeException(s"Invalid channel URL $url: ${f.message}")
      )
  ).flatMap { uri =>
    client.run(Request[IO](uri = uri)).use { resp =>
      resp.status match {
        case Status.Ok       => resp.bodyText.compile.string.map(Some(_))
        case Status.NotFound => IO.pure(None)
        case sc              =>
          IO.raiseError(
            new RuntimeException(
              s"Failed to fetch app descriptor $name from $url: HTTP ${sc.code}"
            )
          )
      }
    }
  }.flatMap {
    case None       => IO.pure(None)
    case Some(body) => parseAppDescriptor(name, url, body).map(Some(_))
  }
}

/** Resolve a Maven channel artifact to its JAR. We fetch with `latest.release`,
  * no transitive deps, and pick the artifact whose URL matches the channel
  * coord — Fetch may still return other JARs from the resolution (e.g. parent
  * BOMs) even with transitivity off.
  */
private def fetchChannelJar(
    org: String,
    name: String
)(using Repos): IO[File] = {
  val coord = s"$org:$name"
  val dep = Dependency
    .of(org, name, "latest.release")
    .withTransitive(false)
  val suffix = s"/${org.replace('.', '/')}/$name/"
  fetchArtifacts(dep)
    .map(_.collect {
      case (a, f) if a.getUrl.contains(suffix) && a.getUrl.endsWith(".jar") =>
        f
    })
    .flatMap {
      case head :: _ => IO.pure(head)
      case Nil       =>
        IO.raiseError(
          new RuntimeException(
            s"Could not locate channel JAR for $coord in the resolved artifacts."
          )
        )
    }
}

/** Read `<name>.json` from the root of a JAR. Returns None if the entry is
  * missing.
  */
private def readJarEntry(jar: File, entry: String): IO[Option[String]] =
  IO.blocking {
    val jf = new JarFile(jar)
    try
      Option(jf.getEntry(entry)).map { e =>
        val is = jf.getInputStream(e)
        try new String(is.readAllBytes(), "UTF-8")
        finally is.close()
      }
    finally jf.close()
  }

/** Fetch one app descriptor from a Maven channel. Returns None when the channel
  * JAR has no `<name>.json` entry; raises if the channel coord itself fails to
  * resolve.
  */
private def fetchAppFromMaven(
    org: String,
    artifact: String,
    name: String
)(using Repos): IO[Option[ContribChannelApp]] =
  fetchChannelJar(org, artifact).flatMap { jar =>
    readJarEntry(jar, s"$name.json").flatMap {
      case None       => IO.pure(None)
      case Some(body) =>
        parseAppDescriptor(name, s"$org:$artifact", body).map(Some(_))
    }
  }

/** Fetch one app descriptor from a specific channel. Returns None when the
  * channel doesn't carry the app, so callers can fall back to another.
  */
def fetchAppFromChannel(
    channel: Channel,
    name: String
)(using Repos, Client[IO]): IO[Option[ContribChannelApp]] =
  channel match {
    case Channel.Builtin(_, raw)   => fetchAppFromBuiltin(raw, name)
    case Channel.Maven(org, aname) => fetchAppFromMaven(org, aname, name)
  }

/** Look up an app across the configured channels, in order. Matches coursier's
  * behavior: default channel always searched; contrib channel is added when the
  * caller passes `--contrib`; any user-supplied `--channel ORG:NAME` channels
  * are searched last, in the order given. The first hit wins.
  */
def lookupApp(
    name: String,
    includeContrib: Boolean,
    userChannels: List[Channel]
)(using Repos, Client[IO]): IO[ContribChannelApp] = {
  val channels =
    Channel.Default ::
      (if (includeContrib) List(Channel.Contrib) else Nil) :::
      userChannels
  channels
    .foldLeft(IO.pure(Option.empty[ContribChannelApp])) { (acc, ch) =>
      acc.flatMap {
        case found @ Some(_) => IO.pure(found)
        case None            => fetchAppFromChannel(ch, name)
      }
    }
    .flatMap {
      case Some(app) => IO.pure(app)
      case None      =>
        val labels = channels.map(_.label).mkString(", ")
        IO.raiseError(
          new RuntimeException(
            s"App '$name' not found in any of the searched channels: $labels."
          )
        )
    }
}

/** Parse a coursier coordinate string. Delegates to `Dependency.parse` — it
  * handles both `org:name:version` and the Scala-suffixed `org::name::version`
  * / `org:::name:::version` syntaxes. `scalaBinary` picks how the suffix is
  * expanded (e.g. `_3` vs `_2.13`).
  */
def parseCoursierDep(coord: String, scalaBinary: String): Dependency =
  Dependency.parse(coord, ScalaVersion.of(scalaBinary))

/** Read the `Main-Class` attribute from a JAR's `META-INF/MANIFEST.MF`, if
  * present. Returns None if the JAR has no manifest or no `Main-Class`.
  */
def readMainClassFromJar(file: File): IO[Option[String]] =
  IO.blocking {
    val jar = new JarFile(file)
    try
      Option(jar.getManifest)
        .flatMap(m => Option(m.getMainAttributes.getValue("Main-Class")))
    finally jar.close()
  }

/** The URL suffix produced by Coursier for a `(group, artifact, version)`
  * coord: `<group-as-path>/<artifact>/<version>/<artifact>-<version>.jar`. Used
  * to pick out the user's directly-passed deps from a transitive resolution
  * result.
  */
def jarUrlSuffix(dep: Dependency): String = {
  val m = dep.getModule
  val group = m.getOrganization.replace('.', '/')
  val name = m.getName
  val version = dep.getVersion
  s"/$group/$name/$version/$name-$version.jar"
}

/** Discover `Main-Class` by inspecting the manifests of the JARs corresponding
  * to the user-supplied direct deps. Errors if no direct dep declares a
  * `Main-Class`, or if multiple direct deps disagree.
  */
def autoDiscoverMainClass(
    parsedDeps: List[Dependency],
    artifacts: List[(Artifact, File)]
): IO[String] = {
  val urlToFile = artifacts.map { case (a, f) => a.getUrl -> f }.toMap
  val directJars: List[(Dependency, File)] = parsedDeps.flatMap { dep =>
    val suffix = jarUrlSuffix(dep)
    urlToFile.collectFirst {
      case (url, file) if url.endsWith(suffix) => dep -> file
    }
  }
  for {
    _ <- IO.raiseWhen(directJars.isEmpty)(
      new RuntimeException(
        "Auto-discovery of --main-class failed: could not match any --dep coordinate to a resolved JAR. Pass --main-class explicitly."
      )
    )
    candidates <- directJars.traverse { case (dep, file) =>
      readMainClassFromJar(file).map(dep -> _)
    }
    withMain = candidates.collect { case (dep, Some(mc)) => dep -> mc }
    result <- withMain match {
      case Nil =>
        IO.raiseError(
          new RuntimeException(
            s"Auto-discovery of --main-class failed: none of the --dep JARs declare a Main-Class in their manifest (checked: ${directJars
                .map(_._1)
                .mkString(", ")}). Pass --main-class explicitly."
          )
        )
      case (_, mc) :: rest if rest.forall(_._2 == mc) =>
        info(
          s"Discovered --main-class ${C.bold}$mc${C.reset} from JAR manifest"
        )
          .as(mc)
      case multiple =>
        val listed = multiple
          .map { case (dep, mc) => s"  - $dep -> $mc" }
          .mkString("\n")
        IO.raiseError(
          new RuntimeException(
            s"Auto-discovery of --main-class failed: --dep JARs declare conflicting Main-Class values:\n$listed\nPass --main-class explicitly."
          )
        )
    }
  } yield result
}

/** Resolve a set of coords transitively, hash all resulting JARs, and produce
  * the `coursier-app` lockfile content. If `mainClass` is None, attempt to
  * discover it from the manifest of the directly-passed deps' JARs.
  */
def computeCoursierAppLock(
    deps: List[String],
    mainClass: Option[String],
    javaOptions: List[String],
    scalaBinary: String
)(using HashCache, Repos): IO[String] = {
  val parsedDeps = deps.map(parseCoursierDep(_, scalaBinary))
  for {
    _ <- step("Resolving Coursier dependencies...")
    artifacts <- fetchArtifacts(parsedDeps*)
    _ <- info(
      s"Resolved ${C.bold}${artifacts.size}${C.reset} artifacts (transitive)"
    )
    resolvedMainClass <- mainClass match {
      case Some(mc) => IO.pure(mc)
      case None     => autoDiscoverMainClass(parsedDeps, artifacts)
    }
    _ <- step("Hashing JARs...")
    jarEntries <- artifacts
      .filter { case (artifact, _) => artifact.getUrl.endsWith(".jar") }
      .traverse { case (artifact, file) =>
        val url = artifact.getUrl
        val path = Path.fromNioPath(file.toPath)
        sha256Base64(path).map(hash => ArtifactEntry(url, hash))
      }
    sorted = jarEntries.distinctBy(_.url).sortBy(_.url)
    lock = CoursierAppLock(
      version = 9,
      kind = "coursier-app",
      mainClass = resolvedMainClass,
      javaOptions = javaOptions,
      dependencies = sorted
    )
  } yield lockfilePrinter.print(lock.asJson) + "\n"
}

def lockCoords(
    opts: LockCoordsOptions,
    appName: Option[String]
)(using Client[IO]): IO[ExitCode] = withHashCache {
  given Repos = Repos(opts.repository.distinct)
  for {
    _ <- opts.repository.distinct.traverse_(url =>
      info(s"Extra repository: ${C.bold}$url${C.reset}")
    )
    code <- appName match {
      case Some(name) =>
        for {
          userChannels <- IO.fromEither(
            opts.channel
              .traverse(Channel.parseMaven)
              .leftMap(new RuntimeException(_))
          )
          channelLabels =
            ("default" :: (if (opts.contrib) List("contrib") else Nil)) :::
              userChannels.map(_.label)
          _ <- info(
            s"Looking up ${C.bold}$name${C.reset} in channels (${channelLabels.mkString(" + ")})..."
          )
          app <- lookupApp(name, opts.contrib, userChannels)
          mainClass <- app.mainClass match {
            // Descriptors mark optional mainClass fields with a trailing `?`
            // (e.g. scalafmt's "org.scalafmt.cli.Cli?"); we just strip it.
            case Some(mc) => IO.pure(mc.stripSuffix("?"))
            case None     =>
              IO.raiseError(
                new RuntimeException(
                  s"App $name has no mainClass field in its descriptor. Use raw `--dep ... --main-class ...` instead."
                )
              )
          }
          // Descriptor's deps win; CLI --dep is treated as additive.
          deps = app.dependencies ++ opts.dep
          content <- computeCoursierAppLock(
            deps,
            Some(mainClass),
            app.javaOptions,
            opts.scalaBinary
          )
          _ <- writeCoordsLock(opts.output, content)
        } yield ExitCode.Success

      case None =>
        if (opts.dep.isEmpty)
          error(
            "Pass an app name (e.g. `scala-cli-nix lock-coords smithy4s --contrib`) or `--dep org:name:version`."
          ).as(ExitCode.Error)
        else
          for {
            content <- computeCoursierAppLock(
              opts.dep,
              opts.mainClass,
              Nil,
              opts.scalaBinary
            )
            _ <- writeCoordsLock(opts.output, content)
          } yield ExitCode.Success
    }
  } yield code
}

private def writeCoordsLock(
    outputOpt: Option[String],
    content: String
): IO[Unit] =
  for {
    cwd <- Files[IO].currentWorkingDirectory
    target = outputOpt match {
      case Some(p) =>
        val path = Path(p)
        if (path.isAbsolute) path else cwd / p
      case None => cwd / "scala.lock.json"
    }
    existing <- Files[IO]
      .exists(target)
      .ifM(readFile(target), IO.pure(""))
    _ <-
      if (existing == content) info("Lock is up to date.")
      else
        step(s"Writing ${target.fileName}...") *>
          writeFile(target, content) *>
          success(s"Wrote ${C.bold}${target.fileName}${C.reset}")
  } yield ()

// --- Main ---

/** Run an `IO[ExitCode]` and exit the JVM with the resulting code. We bridge
  * here because case-app's `Command.run` is sync and `Unit`-returning, while
  * our commands are written as `IO`. Any uncaught error short-circuits to a
  * non-zero exit.
  *
  * A single Ember HTTP client is built here and made available as a contextual
  * `Client[IO]` to the program — commands that don't hit the network simply
  * never touch it; the connection pool is torn down on exit either way.
  */
private def runIO(program: Client[IO] ?=> IO[ExitCode]): Unit = {
  val resource = EmberClientBuilder.default[IO].build
  val code = resource.use(client => program(using client)).unsafeRunSync()
  if (code != ExitCode.Success) sys.exit(code.code)
}

object ScalaCliNix extends CommandsEntryPoint {
  override def progName: String = "scala-cli-nix"
  override def description: String = "Nix packaging for scala-cli apps"
  override def commands: Seq[Command[?]] =
    Seq(InitCommand, LockCommand, LockCoordsCommand)
  override def enableCompleteCommand: Boolean = true
  override def enableCompletionsCommand: Boolean = true

  private object InitCommand extends Command[InitOptions] {
    override def name: String = "init"
    override def run(options: InitOptions, args: RemainingArgs): Unit =
      runIO(init(args.remaining.toList, options.ref))
  }

  private object LockCommand extends Command[LockOptions] {
    override def name: String = "lock"
    override def run(options: LockOptions, args: RemainingArgs): Unit =
      runIO(lock(args.remaining.toList, options.src))
  }

  private object LockCoordsCommand extends Command[LockCoordsOptions] {
    override def name: String = "lock-coords"
    override def run(options: LockCoordsOptions, args: RemainingArgs): Unit = {
      val positional = args.remaining.toList
      val appName = positional match {
        case Nil        => None
        case one :: Nil => Some(one)
        case many       =>
          throw new RuntimeException(
            s"lock-coords takes at most one positional app name; got ${many.mkString(", ")}"
          )
      }
      runIO(lockCoords(options, appName))
    }
  }
}
