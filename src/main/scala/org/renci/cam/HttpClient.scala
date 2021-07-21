package org.renci.cam

import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import zio._
import zio.interop.catz._

import scala.concurrent.duration.{Duration, MINUTES}

object HttpClient {

  type HttpClient = Has[Client[Task]]

  def makeHttpClient: TaskManaged[Client[Task]] =
    ZIO.runtime[Any].toManaged_.flatMap { implicit rts =>
      BlazeClientBuilder[Task](rts.platform.executor.asEC)
        .withConnectTimeout(Duration(5, MINUTES))
        .withIdleTimeout(Duration(4, MINUTES))
        .withRequestTimeout(3, MINUTES)
        .withResponseHeaderTimeout(2, MINUTES)
        .withMaxWaitQueueLimit(512)
        .resource
        .toManaged
    }

  def makeHttpClientLayer: TaskLayer[HttpClient] = makeHttpClient.toLayer

  val client: ZIO[HttpClient, Nothing, Client[Task]] = ZIO.service

}
