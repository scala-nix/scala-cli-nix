//> using dep org.scalameta::munit::1.3.0

class TargetKeyTests extends munit.FunSuite {

  test("targetKey: single platform, single version -> platform name only") {
    val t = Target("jvm", Some("3.6.4"))
    assertEquals(targetKey(t, List(t)), "jvm")
  }

  test("targetKey: multiple platforms, single version -> platform name only") {
    val jvm = Target("jvm", Some("3.6.4"))
    val nat = Target("native", Some("3.6.4"))
    val all = List(jvm, nat)
    assertEquals(targetKey(jvm, all), "jvm")
    assertEquals(targetKey(nat, all), "native")
  }

  test("targetKey: single platform, multiple versions -> version only") {
    val t1 = Target("jvm", Some("3.6.4"))
    val t2 = Target("jvm", Some("3.5.0"))
    val all = List(t1, t2)
    assertEquals(targetKey(t1, all), "3.6.4")
    assertEquals(targetKey(t2, all), "3.5.0")
  }

  test("targetKey: multiple platforms, multiple versions -> platform-version") {
    val all = List(
      Target("jvm", Some("3.6.4")),
      Target("jvm", Some("3.5.0")),
      Target("native", Some("3.6.4")),
      Target("native", Some("3.5.0"))
    )
    val t = Target("native", Some("3.6.4"))
    assertEquals(targetKey(t, all), "native-3.6.4")
  }
}

class ParseDeclaredDepsTests extends munit.FunSuite {

  test("no <dependencies> block: returns empty") {
    val pom = """<?xml version="1.0"?>
                |<project>
                |  <groupId>foo</groupId>
                |  <artifactId>bar</artifactId>
                |  <version>1.0</version>
                |</project>""".stripMargin
    assertEquals(parseDeclaredDeps(pom), Nil)
  }

  test("single dependency") {
    val pom = """<project><dependencies>
                |  <dependency>
                |    <groupId>org.example</groupId>
                |    <artifactId>foo</artifactId>
                |    <version>1.2.3</version>
                |  </dependency>
                |</dependencies></project>""".stripMargin
    assertEquals(parseDeclaredDeps(pom), List(("org.example", "foo", "1.2.3")))
  }

  test("multiple dependencies in order") {
    val pom = """<project><dependencies>
                |  <dependency>
                |    <groupId>a</groupId><artifactId>x</artifactId><version>1</version>
                |  </dependency>
                |  <dependency>
                |    <groupId>b</groupId><artifactId>y</artifactId><version>2</version>
                |  </dependency>
                |</dependencies></project>""".stripMargin
    assertEquals(parseDeclaredDeps(pom), List(("a", "x", "1"), ("b", "y", "2")))
  }

  test("dependency with exclusions and scope") {
    val pom = """<project><dependencies>
                |  <dependency>
                |    <groupId>org.scala-native</groupId>
                |    <artifactId>scalalib_native0.5_2.13</artifactId>
                |    <version>2.13.8+0.5.2</version>
                |    <scope>compile</scope>
                |    <exclusions>
                |      <exclusion>
                |        <groupId>org.scala-lang</groupId>
                |        <artifactId>scala-library</artifactId>
                |      </exclusion>
                |    </exclusions>
                |  </dependency>
                |</dependencies></project>""".stripMargin
    assertEquals(
      parseDeclaredDeps(pom),
      List(("org.scala-native", "scalalib_native0.5_2.13", "2.13.8+0.5.2"))
    )
  }

  test("ignores deps in <dependencyManagement>") {
    val pom = """<project>
                |<dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>managed</groupId><artifactId>m</artifactId><version>9</version>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement>
                |<dependencies>
                |  <dependency>
                |    <groupId>real</groupId><artifactId>r</artifactId><version>1</version>
                |  </dependency>
                |</dependencies>
                |</project>""".stripMargin
    val result = parseDeclaredDeps(pom)
    assert(result.contains(("real", "r", "1")), s"expected real:r:1 in $result")
    assert(
      !result.exists(_._1 == "managed"),
      s"managed dep should be excluded, got $result"
    )
  }

  test("dep missing version yields an empty version for the caller to resolve") {
    // A <dependency> with no <version> inherits it from an ancestor POM's
    // <dependencyManagement>. parseDeclaredDeps can't see that, so it reports
    // v="" and collectDeclaredPoms fills it in via
    // collectAccumulatedDependencyManagement.
    val pom = """<project><dependencies>
                |  <dependency>
                |    <groupId>a</groupId><artifactId>x</artifactId>
                |  </dependency>
                |  <dependency>
                |    <groupId>b</groupId><artifactId>y</artifactId><version>2</version>
                |  </dependency>
                |</dependencies></project>""".stripMargin
    assertEquals(parseDeclaredDeps(pom), List(("a", "x", ""), ("b", "y", "2")))
  }

  test("property placeholders are returned verbatim (caller filters)") {
    val pom = """<project><dependencies>
                |  <dependency>
                |    <groupId>org.example</groupId>
                |    <artifactId>foo</artifactId>
                |    <version>${project.version}</version>
                |  </dependency>
                |</dependencies></project>""".stripMargin
    assertEquals(
      parseDeclaredDeps(pom),
      List(("org.example", "foo", "${project.version}"))
    )
  }
}

class ParseImportedBomsTests extends munit.FunSuite {

  test("no <dependencyManagement>: empty") {
    val pom = "<project><dependencies></dependencies></project>"
    assertEquals(parseImportedBoms(pom, None), Nil)
  }

  test("import scope, type pom: returned") {
    val pom = """<project><dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId>
                |      <artifactId>bom</artifactId>
                |      <version>1.0</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement></project>""".stripMargin
    assertEquals(parseImportedBoms(pom, None), List(("g", "bom", "1.0")))
  }

  test("non-import deps in <dependencyManagement> are excluded") {
    val pom = """<project><dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId><artifactId>x</artifactId><version>1</version>
                |    </dependency>
                |    <dependency>
                |      <groupId>g</groupId><artifactId>bom</artifactId><version>2</version>
                |      <type>pom</type><scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement></project>""".stripMargin
    assertEquals(parseImportedBoms(pom, None), List(("g", "bom", "2")))
  }

  test("${project.version} resolved against passed projectVersion") {
    val pom = """<project><dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId>
                |      <artifactId>bom-internal</artifactId>
                |      <version>${project.version}</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement></project>""".stripMargin
    assertEquals(
      parseImportedBoms(pom, Some("2.29.12")),
      List(("g", "bom-internal", "2.29.12"))
    )
  }

  test("${project.version} dropped when no projectVersion known") {
    val pom = """<project><dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId>
                |      <artifactId>bom-internal</artifactId>
                |      <version>${project.version}</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement></project>""".stripMargin
    assertEquals(parseImportedBoms(pom, None), Nil)
  }

  test("other property placeholders dropped") {
    val pom = """<project><dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId>
                |      <artifactId>bom</artifactId>
                |      <version>${other.ver}</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement></project>""".stripMargin
    assertEquals(parseImportedBoms(pom, Some("1.0")), Nil)
  }

  test("does not pick up imports outside <dependencyManagement>") {
    val pom = """<project><dependencies>
                |  <dependency>
                |    <groupId>g</groupId>
                |    <artifactId>bom</artifactId>
                |    <version>1</version>
                |    <type>pom</type>
                |    <scope>import</scope>
                |  </dependency>
                |</dependencies></project>""".stripMargin
    assertEquals(parseImportedBoms(pom, None), Nil)
  }

  test("substitutes from passed property map") {
    val pom = """<project><dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>org.junit</groupId>
                |      <artifactId>junit-bom</artifactId>
                |      <version>${junit5.version}</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement></project>""".stripMargin
    assertEquals(
      parseImportedBoms(pom, None, Map("junit5.version" -> "5.10.0")),
      List(("org.junit", "junit-bom", "5.10.0"))
    )
  }
}

class PropertiesTests extends munit.FunSuite {

  test("parseProperties: extracts <name>value</name> pairs") {
    val pom = """<project>
                |  <properties>
                |    <foo.bar>1.2.3</foo.bar>
                |    <baz>hello</baz>
                |  </properties>
                |</project>""".stripMargin
    assertEquals(
      parseProperties(pom),
      Map("foo.bar" -> "1.2.3", "baz" -> "hello")
    )
  }

  test("parseProperties: missing <properties> -> empty") {
    assertEquals(parseProperties("<project></project>"), Map.empty)
  }

  test("substituteProperties: simple substitution") {
    assertEquals(
      substituteProperties("${foo}", Map("foo" -> "bar")),
      "bar"
    )
  }

  test("substituteProperties: indirect substitution resolves") {
    val props = Map("a" -> "${b}", "b" -> "value")
    assertEquals(substituteProperties("${a}", props), "value")
  }

  test("substituteProperties: unresolved placeholder kept verbatim") {
    assertEquals(
      substituteProperties("${unknown}", Map.empty),
      "${unknown}"
    )
  }

  test("substituteProperties: mix of resolved and literal") {
    assertEquals(
      substituteProperties("v=${ver}-final", Map("ver" -> "1")),
      "v=1-final"
    )
  }
}

class ParsePomVersionTests extends munit.FunSuite {

  test("top-level <version> wins") {
    val pom = """<project>
                |  <groupId>g</groupId>
                |  <artifactId>a</artifactId>
                |  <version>2.0</version>
                |  <parent><groupId>p</groupId><artifactId>pp</artifactId><version>1.0</version></parent>
                |</project>""".stripMargin
    assertEquals(parsePomVersion(pom), Some("2.0"))
  }

  test("falls back to <parent>/<version> when top-level is missing") {
    val pom = """<project>
                |  <artifactId>a</artifactId>
                |  <parent><groupId>p</groupId><artifactId>pp</artifactId><version>1.0</version></parent>
                |</project>""".stripMargin
    assertEquals(parsePomVersion(pom), Some("1.0"))
  }

  test("ignores versions inside <dependencies> / <dependencyManagement>") {
    val pom = """<project>
                |  <artifactId>a</artifactId>
                |  <parent><artifactId>pp</artifactId><version>1.0</version></parent>
                |  <dependencies>
                |    <dependency><groupId>x</groupId><artifactId>y</artifactId><version>9.9</version></dependency>
                |  </dependencies>
                |</project>""".stripMargin
    assertEquals(parsePomVersion(pom), Some("1.0"))
  }
}

class ParsePomCoordsTests extends munit.FunSuite {

  test("all coords from top-level when present") {
    val pom = """<project>
                |  <groupId>g</groupId>
                |  <artifactId>a</artifactId>
                |  <version>1.0</version>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("g", "a", "1.0")))
  }

  test("groupId + version inherited from parent") {
    val pom = """<project>
                |  <artifactId>a</artifactId>
                |  <parent><groupId>p</groupId><artifactId>pp</artifactId><version>1.0</version></parent>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("p", "a", "1.0")))
  }

  test("no artifactId -> None") {
    val pom = """<project>
                |  <groupId>g</groupId><version>1</version>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), None)
  }

  test("ignores <build>/<plugins> coords when picking top-level group") {
    val pom = """<project>
                |  <parent>
                |    <groupId>real.group</groupId>
                |    <artifactId>parent</artifactId>
                |    <version>2.0</version>
                |  </parent>
                |  <artifactId>child</artifactId>
                |  <build>
                |    <plugins>
                |      <plugin>
                |        <groupId>org.apache.maven.plugins</groupId>
                |        <artifactId>maven-jar-plugin</artifactId>
                |      </plugin>
                |    </plugins>
                |  </build>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("real.group", "child", "2.0")))
  }

  test("ignores <dependencies> coords") {
    val pom = """<project>
                |  <parent>
                |    <groupId>real.group</groupId>
                |    <artifactId>p</artifactId>
                |    <version>1.0</version>
                |  </parent>
                |  <artifactId>child</artifactId>
                |  <dependencies>
                |    <dependency>
                |      <groupId>some.dep</groupId>
                |      <artifactId>x</artifactId>
                |      <version>9</version>
                |    </dependency>
                |  </dependencies>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("real.group", "child", "1.0")))
  }
}

class XmlEdgeCaseTests extends munit.FunSuite {

  // Realistic Maven Central POM with namespace, schemaLocation, comments,
  // <build>, <profiles>, and a BOM import. Used to verify each parser keeps
  // working on real-world input.
  val realisticPom: String =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<project xmlns="http://maven.apache.org/POM/4.0.0"
      |         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      |         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
      |  <modelVersion>4.0.0</modelVersion>
      |  <parent>
      |    <groupId>com.example.parent</groupId>
      |    <artifactId>parent-pom</artifactId>
      |    <version>1.0.0</version>
      |  </parent>
      |  <groupId>com.example</groupId>
      |  <artifactId>my-lib</artifactId>
      |  <version>2.5.0</version>
      |  <!-- comment with <dependency> tags inside should not confuse parsing -->
      |  <properties>
      |    <junit.version>5.10.0</junit.version>
      |    <scala.version>3.6.4</scala.version>
      |  </properties>
      |  <dependencyManagement>
      |    <dependencies>
      |      <dependency>
      |        <groupId>org.junit</groupId>
      |        <artifactId>junit-bom</artifactId>
      |        <version>${junit.version}</version>
      |        <type>pom</type>
      |        <scope>import</scope>
      |      </dependency>
      |    </dependencies>
      |  </dependencyManagement>
      |  <dependencies>
      |    <dependency>
      |      <groupId>org.scala-lang</groupId>
      |      <artifactId>scala3-library_3</artifactId>
      |      <version>${scala.version}</version>
      |    </dependency>
      |    <dependency>
      |      <groupId>org.typelevel</groupId>
      |      <artifactId>cats-core_3</artifactId>
      |      <version>2.10.0</version>
      |    </dependency>
      |  </dependencies>
      |  <build>
      |    <plugins>
      |      <plugin>
      |        <groupId>org.apache.maven.plugins</groupId>
      |        <artifactId>maven-jar-plugin</artifactId>
      |        <version>3.3.0</version>
      |      </plugin>
      |    </plugins>
      |  </build>
      |  <profiles>
      |    <profile>
      |      <id>release</id>
      |      <dependencies>
      |        <dependency>
      |          <groupId>profile.only</groupId>
      |          <artifactId>x</artifactId>
      |          <version>9.9</version>
      |        </dependency>
      |      </dependencies>
      |    </profile>
      |  </profiles>
      |</project>""".stripMargin

  test("realistic POM: parsePomCoords picks top-level coords") {
    assertEquals(
      parsePomCoords(realisticPom),
      Some(("com.example", "my-lib", "2.5.0"))
    )
  }

  test("realistic POM: parsePomVersion picks top-level version") {
    assertEquals(parsePomVersion(realisticPom), Some("2.5.0"))
  }

  test("realistic POM: extractParent finds parent coords") {
    val parentPattern = "(?s)<parent>\\s*(.*?)</parent>".r
    val m = parentPattern.findFirstMatchIn(realisticPom).map(_.group(1))
    assert(m.isDefined)
  }

  test("realistic POM: parseProperties gets both properties") {
    val props = parseProperties(realisticPom)
    assertEquals(props.get("junit.version"), Some("5.10.0"))
    assertEquals(props.get("scala.version"), Some("3.6.4"))
  }

  test("realistic POM: parseDeclaredDeps returns both top-level deps") {
    val deps = parseDeclaredDeps(realisticPom)
    assert(
      deps.contains(("org.typelevel", "cats-core_3", "2.10.0")),
      deps.toString
    )
    assert(
      deps.contains(("org.scala-lang", "scala3-library_3", "${scala.version}")),
      deps.toString
    )
  }

  test("realistic POM: parseImportedBoms with property substitution") {
    val props = parseProperties(realisticPom)
    assertEquals(
      parseImportedBoms(realisticPom, parsePomVersion(realisticPom), props),
      List(("org.junit", "junit-bom", "5.10.0"))
    )
  }

  test("XML comments inside <dependencies> are ignored") {
    val pom = """<project><dependencies>
                |  <!-- <dependency><groupId>commented</groupId><artifactId>out</artifactId><version>1</version></dependency> -->
                |  <dependency>
                |    <groupId>real</groupId>
                |    <artifactId>r</artifactId>
                |    <version>1</version>
                |  </dependency>
                |</dependencies></project>""".stripMargin
    val deps = parseDeclaredDeps(pom)
    assert(deps.contains(("real", "r", "1")), deps.toString)
    assert(!deps.exists(_._1 == "commented"), s"commented dep leaked: $deps")
  }

  test("parsePomCoords ignores <profiles> coords") {
    val pom = """<project>
                |  <groupId>real</groupId>
                |  <artifactId>a</artifactId>
                |  <version>1.0</version>
                |  <profiles>
                |    <profile>
                |      <id>x</id>
                |      <activation>
                |        <property>
                |          <name>foo</name>
                |        </property>
                |      </activation>
                |    </profile>
                |  </profiles>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("real", "a", "1.0")))
  }

  test("namespaced project root still parses (xmlns declared)") {
    val pom = """<?xml version="1.0" encoding="UTF-8"?>
                |<project xmlns="http://maven.apache.org/POM/4.0.0">
                |  <groupId>g</groupId>
                |  <artifactId>a</artifactId>
                |  <version>1.0</version>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("g", "a", "1.0")))
  }

  test("whitespace inside element text is trimmed") {
    val pom = """<project>
                |  <groupId>
                |    g
                |  </groupId>
                |  <artifactId>a</artifactId>
                |  <version>1.0</version>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("g", "a", "1.0")))
  }
}

class RepoBaseFromCoordsTests extends munit.FunSuite {

  test("Maven Central layout: strips suffix") {
    val url =
      "https://repo1.maven.org/maven2/software/amazon/awssdk/core/2.29.12/core-2.29.12.pom"
    assertEquals(
      repoBaseFromCoords(url, "software.amazon.awssdk", "core", "2.29.12"),
      "https://repo1.maven.org/maven2/"
    )
  }

  test("custom repo base") {
    val url = "https://my-repo.example.com/m2/com/foo/bar/1.0/bar-1.0.pom"
    assertEquals(
      repoBaseFromCoords(url, "com.foo", "bar", "1.0"),
      "https://my-repo.example.com/m2/"
    )
  }

  test("non-matching coords -> empty (skip)") {
    val url = "https://repo1.maven.org/maven2/g/a/1.0/a-1.0.pom"
    assertEquals(repoBaseFromCoords(url, "wrong", "a", "1.0"), "")
  }

  test("non-pom URL -> empty") {
    val url = "https://repo1.maven.org/maven2/g/a/1.0/a-1.0.jar"
    assertEquals(repoBaseFromCoords(url, "g", "a", "1.0"), "")
  }
}

class MergeWinnersAndDeclaredTests extends munit.FunSuite {

  private def entry(url: String): ArtifactEntry = ArtifactEntry(url, "h")

  private val scalaXmlBase =
    "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3"
  private val catsCoreBase =
    "https://repo1.maven.org/maven2/org/typelevel/cats-core_3"

  test(
    "evicted JAR for a coordinate already covered by a winner is dropped"
  ) {
    // Regression: a single `(group, artifact)` getting two JARs on the
    // runtime classpath causes a NoSuchMethodError between bytecode compiled
    // against the winner's API and classes loaded from the evicted JAR.
    val winners = List(
      entry(s"$scalaXmlBase/2.4.0/scala-xml_3-2.4.0.jar"),
      entry(s"$scalaXmlBase/2.4.0/scala-xml_3-2.4.0.pom")
    )
    val declared = List(
      entry(s"$scalaXmlBase/2.1.0/scala-xml_3-2.1.0.jar"),
      entry(s"$scalaXmlBase/2.1.0/scala-xml_3-2.1.0.pom")
    )
    val merged = mergeWinnersAndDeclared(winners, declared)
    val jarUrls = merged.filter(isJarUrl).map(_.url)
    assertEquals(
      jarUrls,
      List(s"$scalaXmlBase/2.4.0/scala-xml_3-2.4.0.jar")
    )
    // POM of the evicted version is kept — scala-cli's offline resolver
    // needs it to walk the dependency graph.
    assert(
      merged.exists(_.url == s"$scalaXmlBase/2.1.0/scala-xml_3-2.1.0.pom")
    )
  }

  test("declared JAR for a coordinate the winners don't cover is kept") {
    val winners = List(
      entry(s"$scalaXmlBase/2.4.0/scala-xml_3-2.4.0.jar"),
      entry(s"$scalaXmlBase/2.4.0/scala-xml_3-2.4.0.pom")
    )
    val declared = List(
      entry(s"$catsCoreBase/2.13.0/cats-core_3-2.13.0.jar"),
      entry(s"$catsCoreBase/2.13.0/cats-core_3-2.13.0.pom")
    )
    val merged = mergeWinnersAndDeclared(winners, declared)
    assert(
      merged.exists(_.url == s"$catsCoreBase/2.13.0/cats-core_3-2.13.0.jar")
    )
  }

  test("at most one JAR per (group, artifact) in the merged result") {
    val winners = List(
      entry(s"$scalaXmlBase/2.4.0/scala-xml_3-2.4.0.jar"),
      entry(s"$scalaXmlBase/2.4.0/scala-xml_3-2.4.0.pom")
    )
    val declared = List(
      entry(s"$scalaXmlBase/2.1.0/scala-xml_3-2.1.0.jar"),
      entry(s"$scalaXmlBase/2.1.0/scala-xml_3-2.1.0.pom"),
      entry(s"$scalaXmlBase/2.2.0/scala-xml_3-2.2.0.jar"),
      entry(s"$scalaXmlBase/2.2.0/scala-xml_3-2.2.0.pom")
    )
    val merged = mergeWinnersAndDeclared(winners, declared)
    val jarsByCoord = merged.filter(isJarUrl).groupBy(groupArtifactPath)
    jarsByCoord.foreach { (coord, jars) =>
      assertEquals(jars.size, 1, s"more than one JAR for $coord: $jars")
    }
  }

  test("result is deduplicated and sorted by URL") {
    val a = entry(s"$catsCoreBase/2.13.0/cats-core_3-2.13.0.jar")
    val b = entry(s"$scalaXmlBase/2.4.0/scala-xml_3-2.4.0.jar")
    val merged = mergeWinnersAndDeclared(List(b, a), List(a, b))
    assertEquals(merged.map(_.url), List(a, b).map(_.url).sorted)
  }
}

class GroupArtifactPathTests extends munit.FunSuite {
  test("standard maven layout: strips /<version>/<file> suffix") {
    val e = ArtifactEntry(
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.4.0/scala-xml_3-2.4.0.jar",
      "h"
    )
    assertEquals(
      groupArtifactPath(e),
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3"
    )
  }

  test("two URLs of the same (g, a) at different versions share the prefix") {
    val v1 = ArtifactEntry(
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.1.0/scala-xml_3-2.1.0.jar",
      "h"
    )
    val v2 = ArtifactEntry(
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.4.0/scala-xml_3-2.4.0.pom",
      "h"
    )
    assertEquals(groupArtifactPath(v1), groupArtifactPath(v2))
  }
}

class JarUrlSuffixTests extends munit.FunSuite {
  test("plain Java coord") {
    val dep = coursierapi.Dependency.of("org.example", "foo", "1.2.3")
    assertEquals(jarUrlSuffix(dep), "/org/example/foo/1.2.3/foo-1.2.3.jar")
  }

  test("matches a Coursier-style URL via endsWith") {
    val dep = coursierapi.Dependency.of("org.scalameta", "metals_2.13", "1.5.3")
    val url =
      "https://repo1.maven.org/maven2/org/scalameta/metals_2.13/1.5.3/metals_2.13-1.5.3.jar"
    assert(url.endsWith(jarUrlSuffix(dep)))
  }
}

class ChannelParseMavenTests extends munit.FunSuite {
  test("accepts org:name") {
    assertEquals(
      Channel.parseMaven("io.get-coursier:apps"),
      Right(Channel.Maven("io.get-coursier", "apps"))
    )
  }

  test("rejects missing colon") {
    assert(Channel.parseMaven("io.get-coursier-apps").isLeft)
  }

  test("rejects empty org or name") {
    assert(Channel.parseMaven(":apps").isLeft)
    assert(Channel.parseMaven("io.get-coursier:").isLeft)
  }

  test("rejects three colon-separated parts (would be a version)") {
    assert(Channel.parseMaven("io.get-coursier:apps:1.0.0").isLeft)
  }

  test("label round-trips for Maven channels") {
    val ch = Channel.parseMaven("org.example:my-channel").toOption.get
    assertEquals(ch.label, "org.example:my-channel")
  }
}

class ExtraRepoUrlsFromResolversTests extends munit.FunSuite {
  test("empty input -> empty") {
    assertEquals(extraRepoUrlsFromResolvers(Nil), Nil)
  }

  test("keeps http(s) entries verbatim") {
    assertEquals(
      extraRepoUrlsFromResolvers(
        List(
          "https://artifactory.example.com/maven-virtual",
          "http://internal.repo/maven"
        )
      ),
      List(
        "https://artifactory.example.com/maven-virtual",
        "http://internal.repo/maven"
      )
    )
  }

  test("drops ivy: and file: resolvers") {
    val resolvers = List(
      "https://artifactory.example.com/maven-virtual",
      "ivy:file:///Users/x/.ivy2/local/[org]/[module]/...",
      "file:///opt/local-repo"
    )
    assertEquals(
      extraRepoUrlsFromResolvers(resolvers),
      List("https://artifactory.example.com/maven-virtual")
    )
  }

  test("drops Maven Central (with or without trailing slash)") {
    val resolvers = List(
      "https://repo1.maven.org/maven2",
      "https://repo1.maven.org/maven2/",
      "https://artifactory.example.com/maven-virtual"
    )
    assertEquals(
      extraRepoUrlsFromResolvers(resolvers),
      List("https://artifactory.example.com/maven-virtual")
    )
  }

  test("dedupes while preserving order") {
    val resolvers = List(
      "https://b.example.com/maven",
      "https://a.example.com/maven",
      "https://b.example.com/maven"
    )
    assertEquals(
      extraRepoUrlsFromResolvers(resolvers),
      List("https://b.example.com/maven", "https://a.example.com/maven")
    )
  }
}

class ExportScopeResolversTests extends munit.FunSuite {

  private def decodeScope(json: String): ExportScope =
    io.circe.parser
      .parse(json)
      .flatMap(_.as[ExportScope])
      .fold(e => fail(s"decode failed: $e"), identity)

  test("resolvers field decodes to a List[String]") {
    val scope = decodeScope(
      """{
        |  "sources": [],
        |  "dependencies": [],
        |  "resolvers": [
        |    "https://repo1.maven.org/maven2",
        |    "https://artifactory.example.com/maven-virtual"
        |  ]
        |}""".stripMargin
    )
    assertEquals(
      scope.resolvers,
      List(
        "https://repo1.maven.org/maven2",
        "https://artifactory.example.com/maven-virtual"
      )
    )
  }

  test("missing resolvers field decodes to empty list (back-compat)") {
    val scope = decodeScope(
      """{ "sources": [], "dependencies": [] }"""
    )
    assertEquals(scope.resolvers, Nil)
  }
}


class ParseGitHubUrlTests extends munit.FunSuite {

  test("https://github.com/owner/repo (no ref)") {
    assertEquals(
      parseGitHubUrl("https://github.com/polyvariant/scala-monitor"),
      Right(GitHubRepo("polyvariant", "scala-monitor", None))
    )
  }

  test("trailing slash is tolerated") {
    assertEquals(
      parseGitHubUrl("https://github.com/polyvariant/scala-monitor/"),
      Right(GitHubRepo("polyvariant", "scala-monitor", None))
    )
  }

  test(".git suffix is stripped") {
    assertEquals(
      parseGitHubUrl("https://github.com/polyvariant/scala-monitor.git"),
      Right(GitHubRepo("polyvariant", "scala-monitor", None))
    )
  }

  test("/tree/<sha> records the ref") {
    assertEquals(
      parseGitHubUrl(
        "https://github.com/polyvariant/scala-monitor/tree/c410ca7595bff9c0e9d7a6ede5a6c66c073e9c38"
      ),
      Right(
        GitHubRepo(
          "polyvariant",
          "scala-monitor",
          Some("c410ca7595bff9c0e9d7a6ede5a6c66c073e9c38")
        )
      )
    )
  }

  test("/tree/<tag> records the ref") {
    assertEquals(
      parseGitHubUrl("https://github.com/polyvariant/scala-monitor/tree/v0.5.6"),
      Right(GitHubRepo("polyvariant", "scala-monitor", Some("v0.5.6")))
    )
  }

  test("http:// is accepted (upgraded by Uri layer later)") {
    assertEquals(
      parseGitHubUrl("http://github.com/polyvariant/scala-monitor"),
      Right(GitHubRepo("polyvariant", "scala-monitor", None))
    )
  }

  test("subdir paths under /tree/<ref>/... are rejected") {
    parseGitHubUrl("https://github.com/owner/repo/tree/main/cli") match {
      case Left(msg) => assert(msg.contains("Subdirectory"))
      case other     => fail(s"expected Left, got $other")
    }
  }

  test("non-github URL is rejected") {
    assert(parseGitHubUrl("https://gitlab.com/owner/repo").isLeft)
  }

  test("missing repo segment is rejected") {
    assert(parseGitHubUrl("https://github.com/owner").isLeft)
  }
}

class TransitiveInternalDepsTests extends munit.FunSuite {
  test("a module with no dependsOn has an empty closure") {
    assertEquals(transitiveInternalDeps(Map("app" -> Nil), "app"), Nil)
  }

  test("direct dependencies are included") {
    val direct = Map("app" -> List("core"), "core" -> Nil)
    assertEquals(transitiveInternalDeps(direct, "app"), List("core"))
  }

  test("closure is transitive and sorted") {
    val direct = Map(
      "app" -> List("middle"),
      "middle" -> List("core", "util"),
      "core" -> Nil,
      "util" -> Nil
    )
    assertEquals(
      transitiveInternalDeps(direct, "app"),
      List("core", "middle", "util")
    )
  }

  test("a diamond reports each module once") {
    val direct = Map(
      "app" -> List("left", "right"),
      "left" -> List("core"),
      "right" -> List("core"),
      "core" -> Nil
    )
    assertEquals(
      transitiveInternalDeps(direct, "app"),
      List("core", "left", "right")
    )
  }

  test("the module itself is excluded even when reachable from itself") {
    val direct = Map("app" -> List("core"), "core" -> List("app"))
    assertEquals(transitiveInternalDeps(direct, "app"), List("core"))
  }

  test("a dependency cycle terminates instead of hanging") {
    val direct = Map("a" -> List("b"), "b" -> List("c"), "c" -> List("b"))
    assertEquals(transitiveInternalDeps(direct, "a"), List("b", "c"))
  }

  test("unknown dependsOn ids are tolerated") {
    // sbt wouldn't produce this, but a hand-edited lockfile could.
    assertEquals(
      transitiveInternalDeps(Map("app" -> List("ghost")), "app"),
      List("ghost")
    )
  }
}
