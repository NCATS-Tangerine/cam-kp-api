package org.renci.cam

import java.nio.file.{Files, Paths}

import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.headers._
import org.http4s.implicits._
import org.renci.cam.domain._
import zio.Task
import zio.interop.catz._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object QueryServiceTest extends DefaultRunnableSpec {

  def spec =
    suite("QueryServiceSpec")(
      test("testGetNodeTypes") {
        val n0Node = TRAPIQueryNode("n0", Some("gene"), Some("NCBIGENE:558"))
        val n1Node = TRAPIQueryNode("n1", Some("biological_process"), None)
        val e0Edge = TRAPIQueryEdge("e0", "n1", "n0", Some("has_participant"))

        val queryGraph = TRAPIQueryGraph(List(n0Node, n1Node), List(e0Edge))
        val map = QueryService.getNodeTypes(queryGraph.nodes)
        map.foreach(a => printf("k: %s, v: %s%n", a._1, a._2))
        assert(map)(isNonEmpty)
      } @@ ignore,
      testM("test simple query") {
        for {
          httpClient <- HttpClient.makeHttpClient
          encoded = {
            val n0Node = TRAPIQueryNode("n0", Some("gene"), None)
            val n1Node = TRAPIQueryNode("n1", Some("biological_process"), None)
            val e0Edge = TRAPIQueryEdge("e0", "n1", "n0", Some("has_participant"))

            val queryGraph = TRAPIQueryGraph(List(n0Node, n1Node), List(e0Edge))
            val message = TRAPIMessage(Some(queryGraph), None, None)
            val requestBody = TRAPIQueryRequestBody(message)
            requestBody.asJson.deepDropNullValues.noSpaces
          }
          _ = println("encoded: " + encoded)
          uri = uri"http://127.0.0.1:8080/query".withQueryParam("limit", 1) // scala
          //uri = uri"http://127.0.0.1:6434/query".withQueryParam("limit", 1) // python
          request = Request[Task](Method.POST, uri)
            .withHeaders(Accept(MediaType.application.json), `Content-Type`(MediaType.application.json))
            .withEntity(encoded)
          response <- httpClient.use(_.expect[String](request))
          _ = println("response: " + response)
          _ = Files.writeString(Paths.get("src/test/resources/local-scala.json"), response)
        } yield assert(response)(isNonEmptyString)
      } @@ ignore,
      testM("test gene to process to process to gene") {
        val n0Node = TRAPIQueryNode("n0", Some("gene"), Some("UniProtKB:P30530"))
        val n1Node = TRAPIQueryNode("n1", Some("biological_process"), None)
        val n2Node = TRAPIQueryNode("n2", Some("biological_process"), None)
        val n3Node = TRAPIQueryNode("n3", Some("gene"), None)
        val e0Edge = TRAPIQueryEdge("e0", "n1", "n0", None)
        val e1Edge = TRAPIQueryEdge("e1", "n1", "n2", None /*Some("enabled_by")*/ )
        val e2Edge = TRAPIQueryEdge("e2", "n2", "n3", None)
        val queryGraph = TRAPIQueryGraph(List(n0Node, n1Node, n2Node, n3Node), List(e0Edge, e1Edge, e2Edge))
        val message = TRAPIMessage(Some(queryGraph), None, None)
        val requestBody = TRAPIQueryRequestBody(message)
        val encoded = requestBody.asJson.deepDropNullValues.noSpaces
        for {
          httpClient <- HttpClient.makeHttpClient
          uri = uri"http://127.0.0.1:8080/query".withQueryParam("limit", 10) // scala
          //uri = uri"http://127.0.0.1:6434/query".withQueryParam("limit", 1) // python
          request = Request[Task](Method.POST, uri)
            .withHeaders(Accept(MediaType.application.json), `Content-Type`(MediaType.application.json))
            .withEntity(encoded)
          response <- httpClient.use(_.expect[String](request))
          _ = println("response: " + response)
          _ = Files.writeString(Paths.get("src/test/resources/local-scala-gene-to-process-to-process-to-gene.json"), response)
        } yield assert(response)(isNonEmptyString)
      } @@ ignore,
      testM("find genes enabling any kind of catalytic activity") {
        val n0Node = TRAPIQueryNode("n0", Some("gene_or_gene_product"), None)
        val n1Node = TRAPIQueryNode("n1", Some("molecular_activity"), Some("GO:0003824"))
        val e0Edge = TRAPIQueryEdge("e0", "n1", "n0", Some("enabled_by"))
        val queryGraph = TRAPIQueryGraph(List(n0Node, n1Node), List(e0Edge))
        val message = TRAPIMessage(Some(queryGraph), None, None)
        val requestBody = TRAPIQueryRequestBody(message)
        val encoded = requestBody.asJson.deepDropNullValues.noSpaces
        for {
          httpClient <- HttpClient.makeHttpClient
          uri = uri"http://127.0.0.1:8080/query".withQueryParam("limit", 1) // scala
          //uri = uri"http://127.0.0.1:6434/query".withQueryParam("limit", 1) // python
          request = Request[Task](Method.POST, uri)
            .withHeaders(Accept(MediaType.application.json), `Content-Type`(MediaType.application.json))
            .withEntity(encoded)
          response <- httpClient.use(_.expect[String](request))
          _ = println("response: " + response)
          _ = Files.writeString(Paths.get("src/test/resources/local-scala-find-genes-enabling-catalytic-activity.json"), response)
        } yield assert(response)(isNonEmptyString)
      } @@ ignore,
      testM("negative regulation chaining") {
        val n0Node = TRAPIQueryNode("n0", Some("biological_process_or_activity"), Some("GO:0004252"))
        val n1Node = TRAPIQueryNode("n1", Some("biological_process_or_activity"), Some("GO:0003810"))
        val n2Node = TRAPIQueryNode("n2", Some("gene_or_gene_product"), None)
        val e0Edge = TRAPIQueryEdge("e0", "n0", "n1", Some("positively_regulates"))
        val e1Edge = TRAPIQueryEdge("e1", "n1", "n2", Some("enabled_by"))
        val queryGraph = TRAPIQueryGraph(List(n0Node, n1Node, n2Node), List(e0Edge, e1Edge))
        val message = TRAPIMessage(Some(queryGraph), None, None)
        val requestBody = TRAPIQueryRequestBody(message)
        val encoded = requestBody.asJson.deepDropNullValues.noSpaces
        for {
          httpClient <- HttpClient.makeHttpClient
          uri = uri"http://127.0.0.1:8080/query".withQueryParam("limit", 1) // scala
          //uri = uri"http://127.0.0.1:6434/query".withQueryParam("limit", 1) // python
          request = Request[Task](Method.POST, uri)
            .withHeaders(Accept(MediaType.application.json), `Content-Type`(MediaType.application.json))
            .withEntity(encoded)
          response <- httpClient.use(_.expect[String](request))
          _ = println("response: " + response)
          _ = Files.writeString(Paths.get("src/test/resources/local-scala-negative-regulation-chaining.json"), response)
        } yield assert(response)(isNonEmptyString)
      } @@ ignore
    )

}
