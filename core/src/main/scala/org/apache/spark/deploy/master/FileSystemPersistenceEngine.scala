/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.master

import java.io._

import scala.reflect.ClassTag

import org.apache.spark.internal.Logging
import org.apache.spark.serializer.{DeserializationStream, SerializationStream, Serializer}
import org.apache.spark.util.Utils


/**
 * Stores data in a single on-disk directory with one file per application and worker.
 * Files are deleted when applications and workers are removed.
 *
 * @param dir Directory to store files. Created if non-existent (but not recursively).
 * @param serializer Used to serialize our objects.
  *
  * // 如果想利用Spark本身实现选举和故障恢复，可以设置spark.deploy.recoveryMode为FILESYSTEM.
  * 由于RECOVERY_DIR作为FileSystemPersistenceEngine作为构造参数传入，而且FileSystemPersistenceEngine将会把集群信息存入到
  * RECOVERY_DIR指定的目录，所以必须设置spark.deploy.recoveryDirectory指定目录。FileSystemPersistenceEngine最重要的方法
  * 是serializeLntoFile和deserializeFormFile，serializeLntoFile可以将任何AnyRef（如ApplicationInfo，DriverInfo,WorkerInfo）
  * 序列化写入文件，deserializeFromFile则可以将FileinputStream反序列化为任何对象（如ApplicationInfo,DriverInfo和WorkerInfo）.
  *
 */
private[master] class FileSystemPersistenceEngine(
    val dir: String,
    val serializer: Serializer)
  extends PersistenceEngine with Logging {

  new File(dir).mkdir()

  override def persist(name: String, obj: Object): Unit = {
    serializeIntoFile(new File(dir + File.separator + name), obj)
  }

  override def unpersist(name: String): Unit = {
    val f = new File(dir + File.separator + name)
    if (!f.delete()) {
      logWarning(s"Error deleting ${f.getPath()}")
    }
  }

  override def read[T: ClassTag](prefix: String): Seq[T] = {
    val files = new File(dir).listFiles().filter(_.getName.startsWith(prefix))
    files.map(deserializeFromFile[T])
  }

  private def serializeIntoFile(file: File, value: AnyRef) {
    val created = file.createNewFile()
    if (!created) { throw new IllegalStateException("Could not create file: " + file) }
    val fileOut = new FileOutputStream(file)
    var out: SerializationStream = null
    Utils.tryWithSafeFinally {
      out = serializer.newInstance().serializeStream(fileOut)
      out.writeObject(value)
    } {
      fileOut.close()
      if (out != null) {
        out.close()
      }
    }
  }

  private def deserializeFromFile[T](file: File)(implicit m: ClassTag[T]): T = {
    val fileIn = new FileInputStream(file)
    var in: DeserializationStream = null
    try {
      in = serializer.newInstance().deserializeStream(fileIn)
      in.readObject[T]()
    } finally {
      fileIn.close()
      if (in != null) {
        in.close()
      }
    }
  }

}
