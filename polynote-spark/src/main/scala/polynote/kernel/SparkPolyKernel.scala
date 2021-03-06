package polynote.kernel

import java.io.File
import java.net.{URL, URLDecoder}
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import cats.effect.IO
import cats.effect.concurrent.{Deferred, Ref}
import cats.syntax.flatMap._
import cats.syntax.apply._
import fs2.concurrent.Topic
import org.apache.spark.{SparkConf, Success}
import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler._
import org.apache.spark.sql.SparkSession
import polynote.kernel.PolyKernel._
import polynote.kernel.dependency.DependencyFetcher
import polynote.kernel.lang.LanguageKernel
import polynote.messages.Notebook

import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.{AbstractFile, Directory, PlainDirectory, VirtualDirectory}
import scala.tools.nsc.{Settings, io}
import scala.tools.nsc.interactive.Global

// TODO: Should the spark init stuff go into the Spark Scala kernel? That way PolyKernel could be the only Kernel.
class SparkPolyKernel(
  notebook: Ref[IO, Notebook],
  global: Global,
  dependencies: Map[String, List[(String, File)]],
  statusUpdates: Topic[IO, KernelStatusUpdate],
  extraClassPath: Seq[URL],
  outputPath: Path,
  subKernels: Map[String, LanguageKernel.Factory[IO]] = Map.empty,
  parentClassLoader: ClassLoader = classOf[SparkPolyKernel].getClassLoader
) extends PolyKernel(notebook, global, new PlainDirectory(new Directory(outputPath.toFile)), dependencies, statusUpdates, extraClassPath, subKernels, parentClassLoader) {

  private lazy val dependencyJars = classOf[polynote.runtime.ScalaCell].getProtectionDomain.getCodeSource.getLocation :: {
    // dependencies which have characters like "+" in them get mangled... de-mangle them into a temp directory for adding to spark
    val tmp = Files.createTempDirectory("dependencies")
    tmp.toFile.deleteOnExit()
    val jars = for {
      namedFiles <- dependencies.values.toList
      (_, file) <- namedFiles if file.getName endsWith ".jar"
    } yield file -> tmp.resolve(URLDecoder.decode(file.getName, "utf-8"))

    jars.map {
      case (file, path) =>
        val copied = Files.copy(file.toPath, path)
        copied.toFile.deleteOnExit()
        copied.toUri.toURL
    }
  }

  private val realOutputPath = Deferred.unsafe[IO, AbstractFile]

  override protected lazy val notebookClassLoader: AbstractFileClassLoader = realOutputPath.get.map {
    outputDir =>
      new AbstractFileClassLoader(outputDir, dependencyClassLoader)
  }.unsafeRunSync()

  // initialize the session, and add task listener
  private lazy val sparkListener = new KernelListener(statusUpdates)
  private lazy val session = {
    val conf = new SparkConf(loadDefaults = true)
    conf.setIfMissing("spark.master", "local[*]")
    conf.setJars(dependencyJars.map(_.toString))
    conf.set("spark.repl.class.outputDir", outputPath.toString)

    // TODO: experimental
    conf.set("spark.driver.userClassPathFirst", "true")
    conf.set("spark.executor.userClassPathFirst", "true")
    // TODO: experimental

    val sess = SparkSession.builder().config(conf)
      .appName("PolyNote session")
      .getOrCreate()

    // for some reason the jars aren't totally working...

    // If the session was already created, then we're sharing with another notebook. We have to put our classes in their
    // output dir, and also late-add our dependencies
    // TODO: better way to handle this?
    if (sess.conf.get("spark.repl.class.outputDir") != outputPath.toString) {
      val realOutputPathStr = sess.conf.get("spark.repl.class.outputDir")
      // TODO: should have some way to push arbitrary user-facing messages (warnings etc) to the frontend
      logger.warn(s"Spark session is shared with another notebook; changing compiler target directory to $realOutputPathStr. Dependencies are late-added, and shared with existing notebook sessions (this can cause problems!)")

      val dir = new PlainDirectory(new Directory(new File(realOutputPathStr)))
      realOutputPath.complete(dir).unsafeRunSync()
      global.settings.outputDirs.setSingleOutput(dir)

      dependencyJars.map(_.toString).foreach(sess.sparkContext.addJar)
    } else realOutputPath.complete(outputDir).unsafeRunSync()

    sess.sparkContext.addSparkListener(sparkListener)
    sess
  }

  override val init: IO[Unit] = super.init >> taskQueue.runTaskIO("spark", "Spark session", "Starting Spark session...") {
    taskInfo => IO(session) >> IO.unit
  }

  override def shutdown(): IO[Unit] = super.shutdown() *> IO(session.stop())
}

object SparkPolyKernel {
  def apply(
    notebook: Ref[IO, Notebook],
    dependencies: Map[String, List[(String, File)]],
    subKernels: Map[String, LanguageKernel.Factory[IO]],
    statusUpdates: Topic[IO, KernelStatusUpdate],
    extraClassPath: List[File] = Nil,
    baseSettings: Settings = defaultBaseSettings,
    parentClassLoader: ClassLoader = defaultParentClassLoader
  ): SparkPolyKernel = {

    val notebookFilename = notebook.get.map {
      nb => new File(nb.path).getName
    }.unsafeRunSync()

    val outputPath = Files.createTempDirectory(notebookFilename)
    outputPath.toFile.deleteOnExit()

    val outputDir = new PlainDirectory(new Directory(outputPath.toFile))

    val sparkClasspath = System.getProperty("java.class.path")
      .split(File.pathSeparatorChar)
      .view
      .map(new File(_))
      .filter(file => io.AbstractFile.getURL(file.toURI.toURL) != null)

    val GlobalInfo(global, classPath) = mkGlobal(dependencies, baseSettings, extraClassPath ++ sparkClasspath, outputDir)

    val kernel = new SparkPolyKernel(
      notebook,
      global,
      dependencies,
      statusUpdates,
      classPath.map(_.toURI.toURL),
      outputPath,
      subKernels,
      parentClassLoader
    )

    //global.extendCompilerClassPath(kernel.classPath: _*)

    kernel
  }

}