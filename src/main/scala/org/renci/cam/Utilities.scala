package org.renci.cam

import scala.io.Source
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe._
import org.http4s.headers.Accept
import org.http4s.implicits._
import org.http4s.{MediaType, Method, Request}
import org.renci.cam.HttpClient.HttpClient
import org.renci.cam.Utilities.getClass
import zio._
import zio.interop.catz._
import zio.ZIO.ZIOAutoCloseableOps

object Utilities {

  def getBiolinkPrefixesFromURL: ZIO[HttpClient, Throwable, PrefixesMap] =
    for {
      httpClient <- HttpClient.client
      uri = uri"https://biolink.github.io/biolink-model/context.jsonld"
      request = Request[Task](Method.GET, uri).withHeaders(Accept(MediaType.application.`ld+json`))
      biolinkModelJson <- httpClient.expect[Json](request)
      cursor = biolinkModelJson.hcursor
      contextValue <- ZIO.fromEither(cursor.downField("@context").as[Map[String, Json]])
      curies =
        contextValue
          .map {
            case (key, value) => (key, value.as[String])
          }
          .collect {
            case (key, Right(value)) => (key, value)
          }
    } yield PrefixesMap(curies)

  def getBiolinkPrefixesFromFile: ZIO[Any, Throwable, PrefixesMap] = {
    val sourceManaged = for {
      fileStream <- Managed.fromAutoCloseable(Task.effect(getClass.getResourceAsStream("/prefixes.json")))
      source <- Managed.fromAutoCloseable(Task.effect(Source.fromInputStream(fileStream)))
    } yield source

    for {
      prefixesStr <- sourceManaged.use(source => ZIO.effect(source.getLines.mkString))
      prefixesJson <- ZIO.fromEither(parse(prefixesStr))
      cursor = prefixesJson.hcursor
      contextValue <- ZIO.fromEither(cursor.downField("@context").as[Map[String, Json]])
      curies =
        contextValue
          .map {
            case (key, value) => (key, value.as[String])
          }
          .collect {
            case (key, Right(value)) => (key, value)
          }
    } yield PrefixesMap(curies)
  }

  def getPrefixes: ZIO[HttpClient, Throwable, PrefixesMap] = getBiolinkPrefixesFromURL.orElse(getBiolinkPrefixesFromFile)

  def makePrefixesLayer: ZLayer[HttpClient, Throwable, Has[PrefixesMap]] = ZLayer.fromEffect(getPrefixes)

  val biolinkPrefixes: URIO[Has[PrefixesMap], PrefixesMap] = ZIO.service

  final case class PrefixesMap(prefixesMap: Map[String, String])

}
