package com.zaneli.typescript4s

import java.io.File
import org.apache.commons.io.{ FileUtils, IOUtils }
import org.mozilla.javascript.{ BaseFunction, Context, NativeObject, Scriptable, Undefined, WrappedException }
import org.mozilla.javascript.ScriptableObject.putProperty
import org.slf4s.Logging

object ScriptableObjectFactory extends Logging {

  private[this] val byteOrderMarkNone = 0 // "TypeScript.ByteOrderMark.None"

  private[this] lazy val executingFilePath = ScriptableObjectFactory.getClass.getResource(executingName).getPath
  private[this] lazy val defaultLibContents = IOUtils.toString(ScriptableObjectFactory.getClass.getResourceAsStream(defaultLibName))

  def createEnv(cx: Context, scope: Scriptable): Scriptable = {
    val ts4sEnv = cx.newObject(scope)

    putProperty(ts4sEnv, "newLine", System.getProperty("line.separator"))

    putProperty(ts4sEnv, "supportsCodePage", function({ () =>
      false
    }))

    ts4sEnv
  }

  def createIO(cx: Context, scope: Scriptable, args: Seq[String]): Scriptable = {
    val ts4sIO = cx.newObject(scope)

    val arguments = cx.newArray(scope, args.collect { case arg if arg.nonEmpty => arg.asInstanceOf[Object] }.toArray)
    putProperty(ts4sIO, "arguments", arguments)

    putProperty(ts4sIO, "getExecutingFilePath", function({ () =>
      executingFilePath
    }))

    putProperty(ts4sIO, "resolvePath", function({ path =>
      new File(path.toString).getAbsolutePath
    }))

    putProperty(ts4sIO, "dirName", function({ path =>
      new File(path.toString).getAbsoluteFile.getParent
    }))

    putProperty(ts4sIO, "fileExists", function({ path =>
      new File(path.toString).isFile()
    }))

    putProperty(ts4sIO, "directoryExists", function({ path =>
      new File(path.toString).isDirectory()
    }))

    putProperty(ts4sIO, "readFile", function({ arg =>
      val fileName = arg.toString
      val contents = if (isDefaultLib(fileName)) {
        defaultLibContents
      } else {
        FileUtils.readFileToString(new File(fileName))
      }
      val obj = cx.newObject(scope)
      putProperty(obj, "contents", contents)
      putProperty(obj, "byteOrderMark", byteOrderMarkNone)
      obj
    }))

    putProperty(ts4sIO, "writeFile", function({ (path, contents, _) =>
      FileUtils.writeStringToFile(new File(path.toString), contents.toString)
    }))

    putProperty(ts4sIO, "printLine", function({ msg =>
      println(msg.toString)
    }))

    val stderr = cx.newObject(scope)
    val errors = collection.mutable.ListBuffer[String]()
    putProperty(stderr, "Write", function({ msg =>
      errors += msg.toString
      log.error(msg.toString)
    }))
    putProperty(ts4sIO, "stderr", stderr)

    putProperty(ts4sIO, "quit", function({ status =>
      if (status.asInstanceOf[Double] == 1.0) {
        throw new WrappedException(new TypeScriptCompilerException(errors.mkString(", ")))
      }
    }))
    ts4sIO
  }

  def createUtil(cx: Context, scope: Scriptable): Scriptable = {
    val ts4sUtil = cx.newObject(scope)
    putProperty(ts4sUtil, "isDefaultLib", function({ fileName =>
      isDefaultLib(fileName.toString)
    }))

    putProperty(ts4sUtil, "isTypeCheckEnabled", function({ fileName =>
      !fileName.toString.endsWith(".d.ts")
    }))

    val cache = collection.mutable.Map[String, Object]()
    putProperty(ts4sUtil, "putParseResultCache", function({ (fileName, parseResult) =>
      cache.synchronized(cache.put(fileName.toString, parseResult))
      Unit
    }))
    putProperty(ts4sUtil, "getParseResultCache", function({ fileName =>
      cache.synchronized(cache.get(fileName.toString).getOrElse(Undefined.instance))
    }))
    ts4sUtil
  }

  private[this] def isDefaultLib(fileName: String) = fileName.endsWith(defaultLibName)

  private[this] def function(f: () => Any): BaseFunction = {
    function({ args => f() }, 0)
  }

  private[this] def function(f: (Object) => Any): BaseFunction = {
    function({ args => f(args(0)) }, 1)
  }

  private[this] def function(f: (Object, Object) => Any): BaseFunction = {
    function({ args => f(args(0), args(1)) }, 2)
  }

  private[this] def function(f: (Object, Object, Object) => Any): BaseFunction = {
    function({ args => f(args(0), args(1), args(2)) }, 3)
  }

  private[this] def function(f: (Array[Object]) => Any, arity: Int): BaseFunction = new BaseFunction() {
    override def call(cx: Context, scope: Scriptable, s: Scriptable, args: Array[Object]): Object = {
      f(args) match {
        case b: Boolean => if (b) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
        case _: Unit => Undefined.instance
        case o: Object => o
        case x => Context.toObject(x, scope)
      }
    }
    override def getArity = arity
  }
}
