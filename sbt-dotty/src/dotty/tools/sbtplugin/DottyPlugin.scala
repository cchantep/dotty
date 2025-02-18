package dotty.tools.sbtplugin

import sbt._
import sbt.Def.Initialize
import sbt.Keys._
import sbt.librarymanagement.{
  ivy, DependencyResolution, ScalaModuleInfo, SemanticSelector, UpdateConfiguration, UnresolvedWarningConfiguration,
  VersionNumber
}
import sbt.internal.inc.ScalaInstance
import sbt.internal.inc.classpath.ClassLoaderCache
import xsbti.compile._
import xsbti.AppConfiguration
import java.net.URLClassLoader
import java.util.Optional
import java.util.{Enumeration, Collections}
import java.net.URL
import scala.util.Properties.isJavaAtLeast
import scala.annotation.nowarn

object DottyPlugin extends AutoPlugin {
  object autoImport {
    val isDotty = settingKey[Boolean]("Is this project compiled with Dotty?")
    val isDottyJS = settingKey[Boolean]("Is this project compiled with Dotty and Scala.js?")

    val useScaladoc = settingKey[Boolean]("Use scaladoc as the documentation tool")
    val useScala3doc = useScaladoc
    val tastyFiles = taskKey[Seq[File]]("List all testy files")

    // NOTE:
    // - this is a def to support `scalaVersion := dottyLatestNightlyBuild`
    // - if this was a taskKey, then you couldn't do `scalaVersion := dottyLatestNightlyBuild`
    // - if this was a settingKey, then this would evaluate even if you don't use it.
    def dottyLatestNightlyBuild(): Option[String] = {
      import scala.io.Source

      println("Fetching latest Dotty nightly version...")

      val nightly = try {
        // get majorVersion from dotty.epfl.ch
        val source0 = Source.fromURL("https://dotty.epfl.ch/versions/latest-nightly-base")
        val majorVersionFromWebsite = source0.getLines().toSeq.head
        source0.close()

        // get latest nightly version from maven
        def fetchSource(version: String): (scala.io.BufferedSource, String) =
          try {
            val url =
              if (version.startsWith("0"))
                s"https://repo1.maven.org/maven2/ch/epfl/lamp/dotty-compiler_$version/maven-metadata.xml"
              else
                s"https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_$version/maven-metadata.xml"
            Source.fromURL(url) -> version
          }
          catch { case t: java.io.FileNotFoundException =>
            val major :: minor :: Nil = version.split('.').toList
            if (minor.toInt <= 0) throw t
            else fetchSource(s"$major.${minor.toInt - 1}")
          }
        val (source1, majorVersion) = fetchSource(majorVersionFromWebsite)
        val Version = s"      <version>($majorVersion.*-bin.*)</version>".r
        val nightly = source1
          .getLines()
          .collect { case Version(version) => version }
          .toSeq
          .lastOption
        source1.close()
        nightly
      } catch {
        case _:java.net.UnknownHostException =>
          None
      }

      nightly match {
        case Some(version) =>
          println(s"Latest Dotty nightly build version: $version")
        case None =>
          println(s"Unable to get Dotty latest nightly build version. Make sure you are connected to internet")
      }

      nightly
    }

    implicit class DottyCompatModuleID(moduleID: ModuleID) {
      /** If this ModuleID cross-version is a Dotty version, replace it
       *  by the Scala 2.x version that the Dotty version is retro-compatible with,
       *  otherwise do nothing.
       *
       *  This setting is useful when your build contains dependencies that have only
       *  been published with Scala 2.x, if you have:
       *  {{{
       *  libraryDependencies += "a" %% "b" % "c"
       *  }}}
       *  you can replace it by:
       *  {{{
       *  libraryDependencies += ("a" %% "b" % "c").withDottyCompat(scalaVersion.value)
       *  }}}
       *  This will have no effect when compiling with Scala 2.x, but when compiling
       *  with Dotty this will change the cross-version to a Scala 2.x one. This
       *  works because Dotty is currently retro-compatible with Scala 2.x.
       *
       *  NOTE: As a special-case, the cross-version of scala3-library and scala3-compiler
       *  will never be rewritten because we know that they're Scala 3 only.
       *  This makes it possible to do something like:
       *  {{{
       *  libraryDependencies ~= (_.map(_.withDottyCompat(scalaVersion.value)))
       *  }}}
       */
      @deprecated("Use `.cross(CrossVersion.for3Use2_13)` instead, available in sbt >= 1.5.0 (cf https://eed3si9n.com/sbt-1.5.0)", "0.5.5")
      def withDottyCompat(scalaVersion: String): ModuleID = {
        val name = moduleID.name
        if (name != "scala3-library" && name != "scala3-compiler" &&
            name != "dotty" && name != "dotty-library" && name != "dotty-compiler")
          moduleID.crossVersion match {
            case binary: librarymanagement.Binary =>
              val compatVersion =
                CrossVersion.partialVersion(scalaVersion) match {
                  case Some((3, _)) =>
                    "2.13"
                  case Some((0, minor)) =>
                    if (minor > 18 || scalaVersion.startsWith("0.18.1"))
                      "2.13"
                    else
                      "2.12"
                  case _ =>
                    ""
                }
              if (compatVersion.nonEmpty)
                moduleID.cross(CrossVersion.constant(binary.prefix + compatVersion + binary.suffix))
              else
                moduleID
            case _ =>
              moduleID
          }
        else
          moduleID
      }
    }
  }

  import autoImport._

  override def requires: Plugins = plugins.JvmPlugin
  override def trigger = allRequirements

  /** Patches the IncOptions so that .tasty files are pruned as needed.
   *
   *  This code is adapted from `scalaJSPatchIncOptions` in Scala.js, which needs
   *  to do the exact same thing but for .sjsir files.
   *
   *  This complicated logic patches the ClassfileManager factory of the given
   *  IncOptions with one that is aware of .tasty files emitted by the Dotty
   *  compiler. This makes sure that, when a .class file must be deleted, the
   *  corresponding .tasty file is also deleted.
   *
   *  To support older versions of dotty, this also takes care of .hasTasty
   *  files, although they are not used anymore.
   */
  def dottyPatchIncOptions(incOptions: IncOptions): IncOptions = {
    val tastyFileManager = new TastyFileManager

    // Once sbt/zinc#562 is fixed, can be:
    // val newExternalHooks =
    //   incOptions.externalHooks.withExternalClassFileManager(tastyFileManager)
    val inheritedHooks = incOptions.externalHooks
    val external = Optional.of(tastyFileManager: ClassFileManager)
    val prevManager = inheritedHooks.getExternalClassFileManager
    val fileManager: Optional[ClassFileManager] =
      if (prevManager.isPresent) Optional.of(WrappedClassFileManager.of(prevManager.get, external))
      else external
    val newExternalHooks = new DefaultExternalHooks(inheritedHooks.getExternalLookup, fileManager)

    incOptions.withExternalHooks(newExternalHooks)
  }

  override val globalSettings: Seq[Def.Setting[_]] = Seq(
    Global / onLoad := (Global / onLoad).value.andThen { state =>

      val requiredVersion = ">=1.4.4"

      val sbtV = sbtVersion.value
      if (!VersionNumber(sbtV).matchesSemVer(SemanticSelector(requiredVersion)))
        sys.error(s"The sbt-dotty plugin cannot work with this version of sbt ($sbtV), sbt $requiredVersion is required.")

      val deprecatedVersion = ">=1.5.0"
      val logger = sLog.value
      if (VersionNumber(sbtV).matchesSemVer(SemanticSelector(deprecatedVersion))) {
        logger.warn(s"The sbt-dotty plugin is no longer neeeded with sbt >= 1.5.0, please remove it from your build.")
        logger.warn(s"For more information, see https://eed3si9n.com/sbt-1.5.0")
      }

      state
    }
  )

  // https://github.com/sbt/sbt/issues/3110
  val Def = sbt.Def

  private def scala3Artefact(version: String, name: String) =
    if (version.startsWith("0.")) s"dotty-$name"
    else if (version.startsWith("3.")) s"scala3-$name"
    else throw new RuntimeException(
      s"Cannot construct a Scala 3 artefact name $name for a non-Scala3 " +
      s"scala version ${version}")

  override def projectSettings: Seq[Setting[_]] = {
    Seq(
      isDotty := scalaVersion.value.startsWith("0.") || scalaVersion.value.startsWith("3."),

      /* The way the integration with Scala.js works basically assumes that the settings of ScalaJSPlugin
       * will be applied before those of DottyPlugin. It seems to be the case in the tests I did, perhaps
       * because ScalaJSPlugin is explicitly enabled, while DottyPlugin is triggered. However, I could
       * not find an authoritative source on the topic.
       *
       * There is an alternative implementation that would not have that assumption: it would be to have
       * another DottyJSPlugin, that would be auto-triggered by the presence of *both* DottyPlugin and
       * ScalaJSPlugin. That plugin would be guaranteed to have its settings be applied after both of them,
       * by the documented rules. However, that would require sbt-dotty to depend on sbt-scalajs to be
       * able to refer to ScalaJSPlugin.
       *
       * When the logic of sbt-dotty moves to sbt itself, the logic specific to the Dotty-Scala.js
       * combination will have to move to sbt-scalajs. Doing so currently wouldn't work since we
       * observe that the settings of DottyPlugin are applied after ScalaJSPlugin, so ScalaJSPlugin
       * wouldn't be able to fix up things like the dependency on dotty-library.
       */
      isDottyJS := {
        isDotty.value && (crossVersion.value match {
          case binary: librarymanagement.Binary => binary.prefix.contains("sjs1_")
          case _                                => false
        })
      },

      scalaOrganization := {
        if (scalaVersion.value.startsWith("0."))
          "ch.epfl.lamp"
        else if (scalaVersion.value.startsWith("3."))
          "org.scala-lang"
        else
          scalaOrganization.value
      },

      Compile / incOptions := {
        val inc = (Compile / incOptions).value
        if (isDotty.value)
          dottyPatchIncOptions(inc)
        else
          inc
      },

      scalaCompilerBridgeBinaryJar := Def.settingDyn {
        if (isDotty.value) Def.task {
          val updateReport = fetchArtifactsOf(
            scalaOrganization.value % scala3Artefact(scalaVersion.value, "sbt-bridge") % scalaVersion.value,
            dependencyResolution.value,
            scalaModuleInfo.value,
            updateConfiguration.value,
            (update / unresolvedWarningConfiguration).value,
            streams.value.log,
          )
          Option(getJar(updateReport, scalaOrganization.value, scala3Artefact(scalaVersion.value, "sbt-bridge"), scalaVersion.value))
        }
        else Def.task {
          None: Option[File]
        }
      }.value,

      // Prevent the consoleProject task from using the Scala 3 compiler bridge
      // The consoleProject must load the Scala 2.12 instance and the sbt classpath
      consoleProject / scalaCompilerBridgeBinaryJar := None,

      // Needed for RCs publishing
      scalaBinaryVersion := {
        scalaVersion.value.split("[\\.-]").toList match {
          case "0" :: minor :: _ => s"0.$minor"
          case "3" :: "0" :: "0" :: milestone :: _ =>
            s"3.0.0-$milestone"
          case "3" :: _ =>
            "3"
          case _ => scalaBinaryVersion.value
        }
      },

      // We want:
      //
      // 1. Nothing but the Java standard library on the _JVM_ bootclasspath
      //    (starting with Java 9 we cannot inspect it so we don't have a choice)
      //
      // 2. scala-library, dotty-library, dotty-compiler and its dependencies on the _JVM_
      //    classpath, because we need all of those to actually run the compiler.
      //    NOTE: All of those should have the *same version* (equal to scalaVersion
      //    for everything but scala-library).
      //    (Complication: because dottydoc is a separate artifact with its own dependencies,
      //     running it requires putting extra dependencies on the _JVM_ classpath)
      //
      // 3. scala-library, dotty-library on the _compiler_ bootclasspath or
      //    classpath (the only difference between them is that the compiler
      //    bootclasspath has higher priority, but that should never
      //    make a difference in a sane environment).
      //    NOTE: the versions of {scala,dotty}-library used here do not necessarily
      //    match the one used in 2. because a dependency of the current project might
      //    require a more recent standard library version, this is OK
      //    TODO: ... but if macros are used we might be forced to use the same
      //    versions in the JVM and compiler classpaths to avoid problems, this
      //    needs to be investigated.
      //
      // 4. every other dependency of the user project on the _compiler_
      //    classpath.
      //
      // By default, zinc will put on the compiler bootclasspath the
      // scala-library used on the JVM classpath, even if the current project
      // transitively depends on a newer scala-library (this works because Scala
      // 2 guarantees forward- and backward- binary compatibility, but we don't
      // necessarily want to keep doing that in Scala 3).
      // So for the moment, let's just put nothing at all on the compiler
      // bootclasspath, and instead let sbt dependency management choose which
      // scala-library and dotty-library to put on the compiler classpath.
      // Maybe eventually we should just remove the compiler bootclasspath since
      // it's a source of complication with only dubious benefits.

      // sbt crazy scoping rules mean that when we override `classpathOptions`
      // below we also override `classpathOptions in console` which is normally
      // set in https://github.com/sbt/sbt/blob/b6f02b9b8cd0abb15e3d8856fd76b570deb1bd61/main/src/main/scala/sbt/Defaults.scala#L503,
      // this breaks `sbt console` in Scala 2 projects.
      // There seems to be no way to avoid stomping over task-scoped settings,
      // so we need to manually set `classpathOptions in console` to something sensible,
      // ideally this would be "whatever would be set if this plugin was not enabled",
      // but I can't find a way to do this, so we default to whatever is set in ThisBuild.
      console / classpathOptions := {
        if (isDotty.value)
          classpathOptions.value // The Dotty REPL doesn't require anything special on its classpath
        else
          (ThisBuild / console / classpathOptions).value
      },
      classpathOptions := {
        val old = classpathOptions.value
        if (isDotty.value)
          old
            .withAutoBoot(false)      // we don't put the library on the compiler bootclasspath (as explained above)
            .withFilterLibrary(false) // ...instead, we put it on the compiler classpath
        else
          old
      },
      // ... but when running under Java 8, we still need a compiler bootclasspath
      // that contains the JVM bootclasspath, otherwise sbt incremental
      // compilation breaks.
      scalacOptions ++= {
        if (isDotty.value && !isJavaAtLeast("9"))
          Seq("-bootclasspath", sys.props("sun.boot.class.path"))
        else
          Seq()
      },
      // If the current scalaVersion is N and we transitively depend on
      // {scala, dotty}-{library, compiler, ...} M where M > N, we want the
      // newest version on our compiler classpath, but sbt by default will
      // instead rewrite all our dependencies to version N, the following line
      // prevents this behavior.
      scalaModuleInfo := {
        val old = scalaModuleInfo.value
        if (isDotty.value)
          old.map(_.withOverrideScalaVersion(false))
        else
          old
      },
      // Prevent sbt from creating a ScalaTool configuration
      managedScalaInstance := {
        val old = managedScalaInstance.value
        if (isDotty.value)
          false
        else
          old
      },
      // ... instead, we'll fetch the compiler and its dependencies ourselves.
      scalaInstance := Def.taskDyn {
        if (isDotty.value)
          dottyScalaInstanceTask(scala3Artefact(scalaVersion.value, "compiler"))
        else
          Def.valueStrict { scalaInstance.taskValue }
      }.value,

      // Configuration for the doctool
      resolvers ++= (if(!useScaladoc.value) Nil else Seq(Resolver.jcenterRepo)),
      useScaladoc := {
        val v = scalaVersion.value
        v.startsWith("3") && !v.startsWith("3.0.0-M1") && !v.startsWith("3.0.0-M2")
      },
      // We need to add doctool classes to the classpath so they can be called
      doc / scalaInstance := Def.taskDyn {
        if (isDotty.value)
          if (useScaladoc.value) {
            val v = scalaVersion.value
            val shouldUseScala3doc =
              v.startsWith("3.0.0-M1") || v.startsWith("3.0.0-M2") || v.startsWith("3.0.0-M3")  || v.startsWith("3.0.0-RC1-bin-20210")
            val name = if (shouldUseScala3doc) "scala3doc" else "scaladoc"
            dottyScalaInstanceTask(name)
          } else dottyScalaInstanceTask(scala3Artefact(scalaVersion.value, "doc"))
        else
          Def.valueStrict { (doc / scalaInstance).taskValue }
      }.value,

      // Because managedScalaInstance is false, sbt won't add the standard library to our dependencies for us
      libraryDependencies ++= {
        if (isDotty.value && autoScalaLibrary.value) {
          val name =
            if (isDottyJS.value) scala3Artefact(scalaVersion.value, "library_sjs1")
            else scala3Artefact(scalaVersion.value, "library")
          Seq(scalaOrganization.value %% name % scalaVersion.value)
        } else
          Seq()
      },

      // Patch up some more options if this is Dotty with Scala.js
      scalacOptions := {
        val prev = scalacOptions.value
        /* The `&& !prev.contains("-scalajs")` is future-proof, for when sbt-scalajs adds that
         * option itself but sbt-dotty is still required for the other Dotty-related stuff.
         */
        if (isDottyJS.value && !prev.contains("-scalajs")) prev :+ "-scalajs"
        else prev
      },
      libraryDependencies := {
        val prev = libraryDependencies.value
        if (!isDottyJS.value) {
          prev
        } else {
          prev
            /* Remove the dependencies we don't want:
             * * We don't want scalajs-library, because we need the one that comes
             *   as a dependency of dotty-library_sjs1
             * * We don't want scalajs-compiler, because that's a compiler plugin,
             *   which is replaced by the `-scalajs` flag in dotc.
             */
            .filterNot { moduleID =>
              moduleID.organization == "org.scala-js" && (
                moduleID.name == "scalajs-library" || moduleID.name == "scalajs-compiler"
              )
            }
            // Apply withDottyCompat to the dependency on scalajs-test-bridge
            .map { moduleID =>
              if (moduleID.organization == "org.scala-js" && moduleID.name == "scalajs-test-bridge")
                moduleID.withDottyCompat(scalaVersion.value): @nowarn
              else
                moduleID
            }
        }
      },

      // Turns off the warning:
      // [warn] Binary version (0.9.0-RC1) for dependency ...;0.9.0-RC1
      // [warn]  in ... differs from Scala binary version in project (0.9).
      scalaModuleInfo := {
        val old = scalaModuleInfo.value
        if (isDotty.value)
          old.map(_.withCheckExplicit(false))
        else
          old
      }
    ) ++ inConfig(Compile)(docSettings) ++ inConfig(Test)(docSettings)
  }

  private val docSettings = inTask(doc)(Seq(
    tastyFiles := {
      val sources = compile.value // Ensure that everything is compiled, so TASTy is available.
      // sbt is too smart and do not start doc task if there are no *.scala files defined
      file("___fake___.scala") +:
        (classDirectory.value ** "*.tasty").get.map(_.getAbsoluteFile)
    },
    sources := Def.taskDyn[Seq[File]] {
      val originalSources = sources.value
      if (isDotty.value && useScaladoc.value && originalSources.nonEmpty)
        Def.task { tastyFiles.value }
      else Def.task { originalSources }
    }.value,
    scalacOptions ++= {
      // From sbt 1.5 scaladoc is natively supported, and so the scalacOptions are already set.
      val isSbt15 = VersionNumber(sbtVersion.value)
        .matchesSemVer(SemanticSelector(">=1.5.0-M1"))
      if (isDotty.value && !isSbt15) {
        val projectName =
          if (configuration.value == Compile)
            name.value
          else
            s"${name.value}-${configuration.value}"
        Seq(
          "-project", projectName
        )
      }
      else
        Seq()
    },
  ))

  /** Fetch artifacts for moduleID */
  def fetchArtifactsOf(
    moduleID: ModuleID,
    dependencyRes: DependencyResolution,
    scalaInfo: Option[ScalaModuleInfo],
    updateConfig: UpdateConfiguration,
    warningConfig: UnresolvedWarningConfiguration,
    log: Logger): UpdateReport = {
    val descriptor = dependencyRes.wrapDependencyInModule(moduleID, scalaInfo)

    dependencyRes.update(descriptor, updateConfig, warningConfig, log) match {
      case Right(report) =>
        report
      case Left(warning) =>
        throw new MessageOnlyException(
          s"Couldn't retrieve `$moduleID` : ${warning.resolveException.getMessage}.")
    }
  }

  /** Get all jars in updateReport that match the given filter. */
  def getJars(updateReport: UpdateReport, organization: NameFilter, name: NameFilter, revision: NameFilter): Seq[File] = {
    updateReport.select(
      configurationFilter(Runtime.name),
      moduleFilter(organization, name, revision),
      artifactFilter(extension = "jar", classifier = "")
    )
  }

  /** Get the single jar in updateReport that match the given filter.
   *  If zero or more than one jar match, an exception will be thrown.
   */
  def getJar(updateReport: UpdateReport, organization: NameFilter, name: NameFilter, revision: NameFilter): File = {
    val jars = getJars(updateReport, organization, name, revision)
    assert(jars.size == 1, s"There should only be one $name jar but found: $jars")
    jars.head
  }

  /** Create a scalaInstance task that uses Dotty based on `moduleName`. */
  def dottyScalaInstanceTask(moduleName: String): Initialize[Task[ScalaInstance]] = Def.task {
    val updateReport =
      fetchArtifactsOf(
        scalaOrganization.value %% moduleName % scalaVersion.value,
        dependencyResolution.value,
        scalaModuleInfo.value,
        updateConfiguration.value,
        (update / unresolvedWarningConfiguration).value,
        streams.value.log)
    val scalaLibraryJar = getJar(updateReport,
      "org.scala-lang", "scala-library", revision = AllPassFilter)
    val dottyLibraryJar = getJar(updateReport,
      scalaOrganization.value, scala3Artefact(scalaVersion.value, s"library_${scalaBinaryVersion.value}"), scalaVersion.value)
    val compilerJar = getJar(updateReport,
      scalaOrganization.value, scala3Artefact(scalaVersion.value, s"compiler_${scalaBinaryVersion.value}"), scalaVersion.value)
    val allJars =
      getJars(updateReport, AllPassFilter, AllPassFilter, AllPassFilter)

    makeScalaInstance(
      state.value,
      scalaVersion.value,
      scalaLibraryJar,
      dottyLibraryJar,
      compilerJar,
      allJars,
      appConfiguration.value
    )
  }

  // Adapted from private mkScalaInstance in sbt
  def makeScalaInstance(
    state: State, dottyVersion: String, scalaLibrary: File, dottyLibrary: File, compiler: File, all: Seq[File], appConfiguration: AppConfiguration
  ): ScalaInstance = {
    /**
      * The compiler bridge must load the xsbti classes from the sbt
      * classloader, and similarly the Scala repl must load the sbt provided
      * jline terminal. To do so we add the `appConfiguration` loader in
      * the parent hierarchy of the scala 3 instance loader.
      *
      * The [[TopClassLoader]] ensures that the xsbti and jline classes
      * only are loaded from the sbt loader. That is necessary because
      * the sbt class loader contains the Scala 2.12 library and compiler
      * bridge.
      */
    val topLoader = new TopClassLoader(appConfiguration.provider.loader)

    val libraryJars = Array(dottyLibrary, scalaLibrary)
    val libraryLoader = state.classLoaderCache.cachedCustomClassloader(
      libraryJars.toList,
      () => new URLClassLoader(libraryJars.map(_.toURI.toURL), topLoader)
    )

    class DottyLoader
        extends URLClassLoader(all.map(_.toURI.toURL).toArray, libraryLoader)
    val fullLoader = state.classLoaderCache.cachedCustomClassloader(
      all.toList,
      () => new DottyLoader
    )
    new ScalaInstance(
      dottyVersion,
      fullLoader,
      libraryLoader,
      libraryJars,
      compiler,
      all.toArray,
      None)
  }
}

/**
 * The parent classloader of the Scala compiler.
 *
 * A TopClassLoader is constructed from the sbt classloader.
 *
 * To understand why a custom parent classloader is needed for the compiler,
 * let us describe some alternatives that wouldn't work.
 *
 * - `new URLClassLoader(urls)`:
 *   The compiler contains sbt phases that callback to sbt using the `xsbti.*`
 *   interfaces. If `urls` does not contain the sbt interfaces we'll get a
 *   `ClassNotFoundException` in the compiler when we try to use them, if
 *   `urls` does contain the interfaces we'll get a `ClassCastException` or a
 *   `LinkageError` because if the same class is loaded by two different
 *   classloaders, they are considered distinct by the JVM.
 *
 * - `new URLClassLoader(urls, sbtLoader)`:
 *    Because of the JVM delegation model, this means that we will only load
 *    a class from `urls` if it's not present in the parent `sbtLoader`, but
 *    sbt uses its own version of the scala compiler and scala library which
 *    is not the one we need to run the compiler.
 *
 * Our solution is to implement an URLClassLoader whose parent is
 * `new TopClassLoader(sbtLoader)`. We override `loadClass` to load the
 * `xsbti.*` interfaces from `sbtLoader`.
 *
 * The parent loader of the TopClassLoader is set to `null` so that the JDK
 * classes and only the JDK classes are loade from it.
 */
private class TopClassLoader(sbtLoader: ClassLoader) extends ClassLoader(null) {
  // We can't use the loadClass overload with two arguments because it's
  // protected, but we can do the same by hand (the classloader instance
  // from which we call resolveClass does not matter).
  // The one argument overload of loadClass delegates to this one.
  override protected def loadClass(name: String, resolve: Boolean): Class[_] = {
    if (name.startsWith("xsbti.") || name.startsWith("org.jline.")) {
      val c = sbtLoader.loadClass(name)
      if (resolve) resolveClass(c)
      c
    }
    else super.loadClass(name, resolve)
  }
}
