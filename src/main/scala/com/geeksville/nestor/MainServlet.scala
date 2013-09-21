/**
 * *****************************************************************************
 * Copyright 2013 Kevin Hester
 *
 * See LICENSE.txt for license details.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ****************************************************************************
 */
package com.geeksville.nestor

import org.scalatra._
import scalate.ScalateSupport
import java.io.ByteArrayOutputStream
import com.geeksville.util.CSVWriter
import scala.collection.mutable.HashSet
import com.geeksville.util.DateUtil
import java.io.PrintWriter
import java.io.FileOutputStream
import scala.collection.mutable.ListBuffer

class MainServlet extends NestorStack {

  /*
  get("/testinline") {
    <html>
      <body>
        <h1>Hello, world!</h1>
        Say<a href="hello-scalate">hello to Scalate</a>
        .
      </body>
    </html>
  }
  */

  get("/browse") {
    redirect(url("/view/pkulu2"))
  }

  /**
   * Standard data included with most CSV reports
   */
  private def standardCols(tlog: TLogChunk) = {
    val summary = tlog.summary

    Seq("date" -> DateUtil.isoDateFormat.format(summary.startTime),
      "vehicleType" -> summary.vehicleTypeGuess,
      "ownerId" -> summary.ownerGuess)
  }

  /**
   * Extract the parameters as a CSVable row
   */
  private def tlogToParamRow(tlog: TLogChunk): Option[Seq[(String, Any)]] = {
    val model = new PlaybackModel
    model.loadBytes(tlog.bytes)
    val params = model.parameters.flatMap { param =>
      for {
        id <- param.getId
        v <- param.getValue
      } yield {
        id -> v
      }
    }

    Some(standardCols(tlog) ++ params)
  }

  private def csvGenerator(cb: (TLogChunk) => Option[Seq[(String, Any)]]) = {
    val maxResults = 1
    println("Reading parameters")

    // One row per tlog, each record is a (tlog, list params as tuples)
    val rows = TLogChunkDAO.tlogsRecent(maxResults).flatMap { tlog =>
      println(s"Loading model for $tlog")
      try {
        cb(tlog)
      } catch {
        case ex: Exception =>
          println(s"Skipping due to $ex")
          None
      }
    }

    println("Generating CSV")
    // CSV requires all column names to be known in advance, so merge all param names, being careful to preserve order
    val seenCols = new HashSet[String]
    val colNames = ListBuffer[String]()
    rows.foreach { r =>
      r.foreach {
        case (id, _) =>
          if (!seenCols.contains(id)) {
            colNames += id
            seenCols += id
          }
      }
    }

    val outStr = new StringBuilder
    val csvOut = new CSVWriter(outStr, colNames)

    rows.foreach { r =>
      csvOut.emit(r: _*)
    }

    val resultStr = outStr.toString

    // For debugging
    val o = new PrintWriter(new FileOutputStream("/tmp/big.csv"))
    o.println(resultStr)
    o.close()

    println("Returning CSV")
    resultStr
  }

  get("/report/parameters.csv") {
    contentType = "text/csv"
    csvGenerator(tlogToParamRow)
  }

  /**
   * Our top level browse a flight page
   */
  get("/view/:id") {
    contentType = "text/html"
    val id = params("id")

    TLogChunkDAO.findOneById(id) match {
      case Some(tlog) =>
        scaml("tlogview",
          "tlog" -> tlog,
          "tlogbase" -> uriBase.resolve("/api/tlog/" + id).toString,
          // Use .gmaps.kmz if you want a limited URL
          // "mapsUrl" -> uriBase.resolve("/api/tlog/" + id + ".kmz").toString  
          "mapsUrl" -> publicUriBase.resolve("/api/tlog/" + id + ".gmaps.kmz").toString)
      case None =>
        scaml("failview",
          "message" -> "No tracklog found for ID '%s'".format(id))
    }
  }

  /**
   * Our list flights for a user page
   */
  get("/user/:id") {
    contentType = "text/html"
    val id = params("id")

    scaml("userview", "userId" -> id)
  }

  get("/recent") {
    contentType = "text/html"

    scaml("recentflights")
  }

  get("/upload.html") {
    contentType = "text/html"
    scaml("uploadview",
      "awsKey" -> S3Client.credentials.getAWSAccessKeyId,
      "s3pair" -> S3Client.s3Policy)
  }

  /*
  get("/testtemplate") {
    contentType = "text/html"

    // layoutTemplate("bob.ssp", "message" -> "Hello, World!")
    ssp("bob", "message" -> "Hello, World!")
  }
  */

  get("/testcoffee") {
    <html>
      <body>
        <h1>
          This is
          <a href="http://scalatra.org/2.2/guides/resources/coffeescript.html">resources/coffeescript</a>
          !
        </h1>
        <script type="text/javascript" src="compiled/scripts.js"></script>
      </body>
    </html>
  }
}
