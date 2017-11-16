/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service

import java.io._
import java.util.concurrent.CountDownLatch

import android.content.Context
import com.waz.ZLog._
import com.waz.api.ZmsVersion
import com.waz.cache.{CacheService, Expiration}
import com.waz.content.GlobalPreferences.PushToken
import com.waz.content.WireContentProvider.CacheUri
import com.waz.content.{AccountsStorage, GlobalPreferences}
import com.waz.log.{BufferedLogOutput, InternalLog}
import com.waz.model.{AccountId, Mime}
import com.waz.threading.{SerialDispatchQueue, Threading}
import com.waz.utils.wrappers.URI
import com.waz.utils.{IoUtils, RichFuture}
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._

trait ReportingService {
  import ReportingService._
  private implicit val dispatcher = new SerialDispatchQueue(name = "ReportingService")
  private[service] var reporters = Seq.empty[Reporter]

  def addStateReporter(report: PrintWriter => Future[Unit])(implicit tag: LogTag): Unit = Future {
    reporters = reporters :+ Reporter(tag, report)
  }

  private[service] def generateStateReport(writer: PrintWriter) =
    Future { reporters } flatMap { rs =>
      RichFuture.processSequential(rs)(_.apply(writer))
    }
}

object ReportingService {
  private implicit val tag: LogTag = logTagFor[ReportingService]

  case class Reporter(name: String, report: PrintWriter => Future[Unit]) {

    def apply(writer: PrintWriter) = {
      writer.println(s"\n###### $name:")
      report(writer)
    }
  }
}

class ZmsReportingService(user: AccountId, global: ReportingService) extends ReportingService {
  implicit val tag: LogTag = logTagFor[ZmsReportingService]
  private implicit val dispatcher = new SerialDispatchQueue(name = "ZmsReportingService")

  global.addStateReporter(generateStateReport)(s"ZMessaging[$user]")
}

class GlobalReportingService(context: Context, cache: CacheService, metadata: MetaDataService, storage: AccountsStorage, prefs: GlobalPreferences) extends ReportingService {
  import ReportingService._
  import Threading.Implicits.Background
  implicit val tag: LogTag = logTagFor[GlobalReportingService]

  def generateReport(): Future[URI] =
    cache.createForFile(mime = Mime("text/txt"), name = Some("wire_debug_report.txt"), cacheLocation = Some(cache.intCacheDir))(Expiration.in(12.hours)) flatMap { entry =>
      @SuppressWarnings(Array("deprecation"))
      lazy val writer = new PrintWriter(new OutputStreamWriter(entry.outputStream))

      val rs = if (metadata.internalBuild)
        VersionReporter +: PushRegistrationReporter +: ZUsersReporter +: reporters :+ LogCatReporter :+ InternalLogReporter
      else Seq(VersionReporter, LogCatReporter)

      RichFuture.processSequential(rs) { reporter =>
        reporter.apply(writer)
      } map { _ => CacheUri(entry.data, context) } andThen {
        case _ => writer.close()
      }
    }

  val VersionReporter = Reporter("Wire", { writer =>
    import android.os.Build._
    Future.successful {
      writer.println(s"time of log: ${Instant.now}")
      writer.println(s"package: ${ZMessaging.context.getPackageName}")
      writer.println(s"app version: ${metadata.appVersion}")
      writer.println(s"zms version: ${ZmsVersion.ZMS_VERSION}")
      writer.println(s"device: $MANUFACTURER $PRODUCT | $MODEL | $BRAND | $ID")
      writer.println(s"version: ${VERSION.RELEASE} | ${VERSION.CODENAME}")
    }
  })

  val ZUsersReporter = Reporter("ZUsers", { writer =>
    writer.println(s"current: ${ZMessaging.currentAccounts.activeAccountPref.signal.currentValue}")
    storage.list() map { all =>
      all foreach { u => writer.println(u.toString) }
    }
  })

  val PushRegistrationReporter = Reporter("Push", { writer =>
    prefs.preference(PushToken).apply().map(writer.println )
  })

  val LogCatReporter = Reporter("LogCat", { writer =>

    val latch = new CountDownLatch(2)

    def writeAll(input: InputStream): Unit = try {
      IoUtils.withResource(new BufferedReader(new InputStreamReader(input))) { reader =>
        Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(writer.println)
      }
    } finally {
      latch.countDown()
    }

    Future {
      import scala.sys.process._
      Process(Seq("logcat", "-d", "-v", "time")).run(new ProcessIO({in => latch.await(); in.close() }, writeAll, writeAll))
      latch.await()
    } (Threading.IO)
  })

  val InternalLogReporter = Reporter("InternalLog", { writer =>
    val outputs = InternalLog.getOutputs.flatMap {
      case o: BufferedLogOutput => Some(o)
      case _ => None
    }

    Future.sequence(outputs.map( _.flush() )).map { _ =>
      outputs.flatMap(_.getPaths) // paths should be sorted from the oldest to the youngest
             .map(new File(_))
             .filter(_.exists)
             .foreach { file =>
               IoUtils.withResource(new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
                 reader => Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(writer.println)
               }
             }
    }
  })
}
