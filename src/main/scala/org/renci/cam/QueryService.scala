package org.renci.cam

import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import io.circe.syntax._
import org.apache.jena.query.QuerySolution
import org.phenoscape.sparql.SPARQLInterpolation._
import org.renci.cam.Biolink.{biolinkData, BiolinkData}
import org.renci.cam.HttpClient.HttpClient
import org.renci.cam.Util.IterableSPARQLOps
import org.renci.cam.domain._
import zio.config.ZConfig
import zio.{Has, RIO, Task, ZIO, config => _}

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.jdk.CollectionConverters._

object QueryService extends LazyLogging {

  val ProvWasDerivedFrom: IRI = IRI("http://www.w3.org/ns/prov#wasDerivedFrom")

  val RDFSSubClassOf: IRI = IRI("http://www.w3.org/2000/01/rdf-schema#subClassOf")

  val RDFSLabel: IRI = IRI("http://www.w3.org/2000/01/rdf-schema#label")

  val RDFType: IRI = IRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

  val OWLNamedIndividual: IRI = IRI("http://www.w3.org/2002/07/owl#NamedIndividual")

  val SesameDirectType: IRI = IRI("http://www.openrdf.org/schema/sesame#directType")

  val BiolinkMLSlotDefinition: IRI = IRI("https://w3id.org/linkml/SlotDefinition")

  val BiolinkMLIsA: IRI = IRI("https://w3id.org/linkml/is_a")

  val BiolinkMLMixins: IRI = IRI("https://w3id.org/linkml/mixins")

  val RDFSSubPropertyOf: IRI = IRI("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")

  val SlotMapping: IRI = IRI("http://cam.renci.org/biolink_slot")

  val BiolinkNamedThing: BiolinkClass = BiolinkClass("NamedThing", IRI(s"${BiolinkTerm.namespace}NamedThing"))

  final case class TRAPIEdgeKey(source_id: String, `type`: Option[BiolinkPredicate], target_id: String)

  final case class Triple(subj: IRI, pred: IRI, obj: IRI)

  final case class TripleString(subj: String, pred: String, obj: String)

  final case class TermWithLabelAndBiolinkType(term: IRI, biolinkType: IRI, label: Option[String])

  // instances are not thread-safe; should be retrieved for every use
  private def messageDigest: MessageDigest = MessageDigest.getInstance("SHA-256")

  def run(limit: Int,
          includeExtraEdges: Boolean,
          submittedQueryGraph: TRAPIQueryGraph): RIO[ZConfig[AppConfig] with HttpClient with Has[BiolinkData], TRAPIMessage] =
    for {
      biolinkData <- biolinkData
      _ = logger.debug("limit: {}, includeExtraEdges: {}", limit, includeExtraEdges)
      queryGraph = enforceQueryEdgeTypes(submittedQueryGraph, biolinkData.predicates)
      allPredicatesInQuery = queryGraph.edges.values.flatMap(_.predicates.getOrElse(Nil)).to(Set)
      predicatesToRelations <- mapQueryBiolinkPredicatesToRelations(allPredicatesInQuery)
      allRelationsInQuery = predicatesToRelations.values.flatten.to(Set)
      relationsToLabelAndBiolink <- mapRelationsToLabelAndBiolink(allRelationsInQuery)
      predicates = queryGraph.edges.map { case (queryEdgeID, queryEdge) =>
        val relationsForEdge = queryEdge.predicates.getOrElse(Nil).flatMap(predicatesToRelations.getOrElse(_, Set.empty)).to(Set)
        val predicatesQueryText = relationsForEdge.map(rel => sparql" $rel ").fold(sparql"")(_ + _)
        val edgeIDVar = Var(queryEdgeID)
        val edgeSourceVar = Var(queryEdge.subject)
        val edgeTargetVar = Var(queryEdge.`object`)
        val predicatesValuesClause = sparql""" VALUES $edgeIDVar { $predicatesQueryText } """
        val subjectNode = queryGraph.nodes(queryEdge.subject)
        val subjectNodeValuesClauses = (subjectNode.ids, subjectNode.categories) match {
          case (Some(idsList), _) =>
            sparql""" VALUES ${edgeSourceVar}_class { ${idsList.asValues} }
                      $edgeSourceVar $RDFType ${edgeSourceVar}_class .
                      """
          case (None, Some(biolinkTypes)) =>
            val irisList = biolinkTypes.map(_.iri)
            sparql""" VALUES ${edgeSourceVar}_class { ${irisList.asValues} }
                      $edgeSourceVar $RDFType ${edgeSourceVar}_class .
                      """
            sparql"$edgeSourceVar $RDFType $irisList . "
          case (None, None) => sparql""
        }
        val objectNode = queryGraph.nodes(queryEdge.`object`)
        val objectNodeValuesClauses = (objectNode.ids, objectNode.categories) match {
          case (Some(idsList), _) =>
            sparql""" VALUES ${edgeTargetVar}_class { ${idsList.asValues} }
                      $edgeTargetVar $RDFType ${edgeTargetVar}_class .
                      """
          case (None, Some(biolinkTypes)) =>
            val irisList = biolinkTypes.map(_.iri)
            sparql""" VALUES ${edgeTargetVar}_class { ${irisList.asValues} }
                      $edgeTargetVar $RDFType ${edgeTargetVar}_class .
                      """
          case (None, None) => sparql""
        }
        val nodesValuesClauses = List(subjectNodeValuesClauses, objectNodeValuesClauses).fold(sparql"")(_ + _)
        val ret = sparql"""
              $predicatesValuesClause
              $nodesValuesClauses
              $edgeSourceVar $edgeIDVar $edgeTargetVar .
            """
        (queryEdge, ret)
      }
      (edges, sparqlLines) = predicates.unzip
      projections = getProjections(queryGraph)
      nodesToDirectTypes = getNodesToDirectTypes(queryGraph.nodes)
      valuesClause = sparqlLines.fold(sparql"")(_ + _)
      limitSparql = if (limit > 0) sparql" LIMIT $limit" else sparql""
      queryString =
        sparql"""SELECT DISTINCT $projections
          WHERE {
            $nodesToDirectTypes
            $valuesClause
          }
          $limitSparql
          """
      querySolutions <- SPARQLQueryExecutor.runSelectQuery(queryString.toQuery)
      solutionTriples = for {
        queryEdge <- queryGraph.edges
        solution <- querySolutions
      } yield Triple(
        IRI(solution.getResource(queryEdge._2.subject).getURI),
        IRI(solution.getResource(queryEdge._1).getURI),
        IRI(solution.getResource(queryEdge._2.`object`).getURI)
      )
      provs <- getProvenance(solutionTriples.to(Set))
      initialKGNodes <- getTRAPINodes(queryGraph, querySolutions, biolinkData.classes)
      initialKGEdges <- getTRAPIEdges(queryGraph, querySolutions, relationsToLabelAndBiolink, provs)
      querySolutionsToEdgeBindings <- getTRAPIEdgeBindingsMany(queryGraph, querySolutions, relationsToLabelAndBiolink)
      trapiBindings <- ZIO.foreach(querySolutions) { querySolution =>
        getTRAPINodeBindings(queryGraph, querySolution) zip Task.effect(querySolutionsToEdgeBindings(querySolution))
      }
      _ <- ZIO.when(includeExtraEdges)(
        for {
          prov2CAMStuffTripleMap <- ZIO.foreachPar(provs.values)(prov => getCAMStuff(IRI(prov)).map(prov -> _)).map(_.toMap)
          allCAMTriples = prov2CAMStuffTripleMap.values.to(Set).flatten
          allTripleNodes = allCAMTriples.flatMap(t => Set(t.subj, t.obj))
          slotStuffNodeDetails <- getTRAPINodeDetails(allTripleNodes.to(List))
          extraKGNodes = getExtraKGNodes(allTripleNodes, slotStuffNodeDetails, biolinkData)
          allPredicates = allCAMTriples.map(_.pred)
          relationsToInfo <- mapRelationsToLabelAndBiolink(allPredicates)
          extraKGEdges = allCAMTriples.flatMap { triple =>
            for {
              (relationLabelOpt, relationBiolinkPredicate) <- relationsToInfo.get(triple.pred)
              predBLTermOpt = biolinkData.predicates.find(a => a.iri == relationBiolinkPredicate)
              key = getTRAPIEdgeKey(triple.subj.value, predBLTermOpt, triple.obj.value)
              edge = TRAPIEdge(predBLTermOpt, None, triple.subj, triple.obj, None)
            } yield key -> edge
          }.toMap
        } yield {
          initialKGNodes ++= extraKGNodes
          initialKGEdges ++= extraKGEdges
        }
      )
      results = trapiBindings.map { case (resultNodeBindings, resultEdgeBindings) => TRAPIResult(resultNodeBindings, resultEdgeBindings) }
    } yield TRAPIMessage(Some(queryGraph), Some(TRAPIKnowledgeGraph(initialKGNodes.toMap, initialKGEdges.toMap)), Some(results.distinct))

  def getNodesToDirectTypes(nodes: Map[String, TRAPIQueryNode]): QueryText =
    nodes
      .map { node =>
        val nodeVar = Var(node._1)
        val nodeTypeVar = Var(s"${node._1}_type")
        sparql""" $nodeVar $SesameDirectType $nodeTypeVar .  """
      }
      .fold(sparql"")(_ + _)

  def getProjections(queryGraph: TRAPIQueryGraph): QueryText = {
    val projectionVariableNames =
      queryGraph.edges.flatMap(entry => List(entry._1)) ++ queryGraph.edges.flatMap(e =>
        List(e._2.subject, e._2.`object`)) ++ queryGraph.nodes.map(entry => s"${entry._1}_type")
    projectionVariableNames.map(Var(_)).map(v => sparql" $v ").fold(sparql"")(_ + _)
  }

  def enforceQueryEdgeTypes(queryGraph: TRAPIQueryGraph, biolinkPredicates: List[BiolinkPredicate]): TRAPIQueryGraph = {
    val improvedEdgeMap = queryGraph.edges.map { case (edgeID, edge) =>
      val newPredicate = edge.predicates match {
        case None          => Some(List(BiolinkPredicate("related_to")))
        case somePredicate => somePredicate
      }
      val filteredPredicates = newPredicate.get.filter(pred => biolinkPredicates.contains(pred))
      edgeID -> edge.copy(predicates = Some(filteredPredicates))
    }
    queryGraph.copy(edges = improvedEdgeMap)
  }

  def getTRAPIEdgeKey(sub: String, pred: Option[BiolinkPredicate], obj: String): String = {
    val edgeKey = TRAPIEdgeKey(sub, pred, obj).asJson.deepDropNullValues.noSpaces
    String.format("%064x", new BigInteger(1, messageDigest.digest(edgeKey.getBytes(StandardCharsets.UTF_8))))
  }

  def getTRAPIEdges(queryGraph: TRAPIQueryGraph,
                    querySolutions: List[QuerySolution],
                    relationsMap: Map[IRI, (Option[String], IRI)],
                    provs: Map[TripleString, String]): ZIO[Has[BiolinkData], Throwable, collection.mutable.Map[String, TRAPIEdge]] =
    for {
      biolinkData <- biolinkData
      trapiEdges <- ZIO.foreach(querySolutions) { querySolution =>
        for {
          nodeTypeMap <- Task.effect(queryGraph.nodes.map(entry => (entry._1, IRI(querySolution.getResource(s"${entry._1}_type").getURI))))
          edges <- ZIO.foreach(queryGraph.edges) { (queryEdgeID, queryEdge) =>
            for {
              sourceType <- ZIO.fromOption(nodeTypeMap.get(queryEdge.subject)).orElseFail(new Exception("could not get source id"))
              targetType <- ZIO.fromOption(nodeTypeMap.get(queryEdge.`object`)).orElseFail(new Exception("could not get target id"))
              source = querySolution.getResource(queryEdge.subject).getURI
              target = querySolution.getResource(queryEdge.`object`).getURI
              predicate = querySolution.getResource(queryEdgeID).getURI
              predicateIRI = IRI(predicate)
              tripleString = TripleString(source, predicate, target)
              provValue <- ZIO.fromOption(provs.get(tripleString)).orElseFail(new Exception("no prov value"))
              attributes = List(TRAPIAttribute(IRI(source), Some("provenance"), List(provValue), Some(sourceType), None, None, None))
              relationLabelAndBiolinkPredicate <- ZIO
                .fromOption(relationsMap.get(predicateIRI))
                .orElseFail(new Exception("Unexpected edge relation"))
              (relationLabelOpt, biolinkPredicateIRI) = relationLabelAndBiolinkPredicate
              blPred = biolinkData.predicates.find(a => a.iri == biolinkPredicateIRI)
              trapiEdgeKey = getTRAPIEdgeKey(sourceType.value, blPred, targetType.value)
              //FIXME add relation CURIE here?
              trapiEdge = TRAPIEdge(blPred, None, sourceType, targetType, Some(attributes))
            } yield trapiEdgeKey -> trapiEdge
          }
        } yield edges.toList
      }
    } yield trapiEdges.flatten.to(collection.mutable.Map)

  def getTRAPINodes(queryGraph: TRAPIQueryGraph, querySolutions: List[QuerySolution], bionlinkClasses: List[BiolinkClass])
    : RIO[ZConfig[AppConfig] with HttpClient with Has[BiolinkData], collection.mutable.Map[IRI, TRAPINode]] = {
    val allOntClassIRIsZ = ZIO
      .foreach(querySolutions) { qs =>
        ZIO.foreach(qs.varNames.asScala.filter(_.endsWith("_type")).to(Iterable)) { typeVar =>
          ZIO.effect(IRI(qs.getResource(typeVar).getURI)).mapError { e =>
            new Exception(s"Value of _type variable $typeVar is not a URI", e)
          }
        }
      }
      .map(_.flatten)
    for {
      allOntClassIRIs <- allOntClassIRIsZ
      nodeDetails <- getTRAPINodeDetails(allOntClassIRIs)
      termToLabelAndTypes = nodeDetails.groupBy(_.term).map { case (term, termsAndTypes) =>
        val (labels, biolinkTypes) = termsAndTypes.map(t => t.label -> t.biolinkType).unzip
        term -> (labels.flatten.headOption, biolinkTypes)
      }
      trapiNodes <- ZIO.foreach(querySolutions) { querySolution =>
        for {
          nodeMap <- Task.effect(queryGraph.nodes.map(entry => (entry._1, querySolution.get(s"${entry._1}_type").toString)))
          nodes <- ZIO.foreach(queryGraph.nodes) { (k, v) =>
            for {
              nodeIRI <- ZIO.fromOption(nodeMap.get(k)).orElseFail(new Exception(s"Missing node IRI: $k"))
              labelAndTypes = termToLabelAndTypes.getOrElse(IRI(nodeIRI), (None, List(BiolinkNamedThing)))
              (labelOpt, biolinkTypes) = labelAndTypes
              biolinkTypesSet = biolinkTypes.to(Set)
              nodeBiolinkTypes = bionlinkClasses.filter(c => biolinkTypesSet(c.iri))
            } yield IRI(nodeIRI) -> TRAPINode(labelOpt, Some(nodeBiolinkTypes), None)
          }
        } yield nodes.toList
      }
    } yield trapiNodes.flatten.to(collection.mutable.Map)
  }

  def getTRAPINodeDetailsQueryText(nodeIdList: List[IRI]): QueryText = {
    // requiring biolinkType makes some terms not be found when these results are used elsewhere - must be handled there
    val nodeIds = nodeIdList.map(n => sparql" $n ").fold(sparql"")(_ + _)
    sparql"""SELECT ?term ?biolinkType (MIN(?term_label) AS ?label)
         WHERE {
           VALUES ?term { $nodeIds }
           ?term $RDFSSubClassOf ?biolinkType .
           ?biolinkType $BiolinkMLIsA* ${BiolinkNamedThing.iri} .
           OPTIONAL { ?term $RDFSLabel ?term_label }
         }
         GROUP BY ?term ?biolinkType"""
  }

  def getTRAPINodeDetails(nodeIdList: List[IRI]): RIO[ZConfig[AppConfig] with HttpClient, List[TermWithLabelAndBiolinkType]] =
    for {
      queryText <- Task.effect(getTRAPINodeDetailsQueryText(nodeIdList))
      termsAndBiolinkTypes <- SPARQLQueryExecutor.runSelectQueryAs[TermWithLabelAndBiolinkType](queryText.toQuery)
    } yield termsAndBiolinkTypes

  def getTRAPINodeBindings(queryGraph: TRAPIQueryGraph,
                           querySolution: QuerySolution): ZIO[Any, Throwable, Map[String, List[TRAPINodeBinding]]] =
    for {
      nodeMap <- Task.effect(queryGraph.nodes.map(n => (n._1, querySolution.get(s"${n._1}_type").toString)))
      nodeBindings <- ZIO.foreach(queryGraph.nodes) { (k, v) =>
        for {
          nodeIRI <- ZIO.fromOption(nodeMap.get(k)).orElseFail(new Exception(s"Missing node IRI: $k"))
        } yield k -> List(TRAPINodeBinding(IRI(nodeIRI)))
      }
    } yield nodeBindings

  def getTRAPIEdgeBindingsMany(queryGraph: TRAPIQueryGraph,
                               querySolutions: List[QuerySolution],
                               relationsMap: Map[IRI, (Option[String], IRI)])
    : ZIO[ZConfig[AppConfig] with HttpClient with Has[BiolinkData], Throwable, Map[QuerySolution, Map[String, List[TRAPIEdgeBinding]]]] =
    for {
      biolinkData <- biolinkData
      querySolutionsToEdgeBindings <- ZIO.foreach(querySolutions) { querySolution =>
        for {
          edgeBindings <- ZIO.foreach(queryGraph.edges) { (queryEdgeID, queryEdge) =>
            for {
              sourceType <- Task.effect(querySolution.get(s"${queryEdge.subject}_type").toString)
              targetType <- Task.effect(querySolution.get(s"${queryEdge.`object`}_type").toString)
              relation = querySolution.getResource(queryEdgeID).getURI
              relationIRI = IRI(relation)
              relationLabelAndBiolinkPredicate <- ZIO
                .fromOption(relationsMap.get(relationIRI))
                .orElseFail(new Exception("Unexpected edge relation"))
              (relationLabelOpt, biolinkPredicateIRI) = relationLabelAndBiolinkPredicate
              blPred = biolinkData.predicates.find(a => a.iri == biolinkPredicateIRI)
              trapiEdgeKey = getTRAPIEdgeKey(sourceType, blPred, targetType)
              trapiEdgeBinding = List(TRAPIEdgeBinding(trapiEdgeKey))
            } yield queryEdgeID -> trapiEdgeBinding
          }
        } yield querySolution -> edgeBindings
      }
    } yield querySolutionsToEdgeBindings.toMap

  def getProvenanceQueryText(edges: Set[Triple]): QueryText = {
    val values = edges.map(e => sparql"( ${e.subj} ${e.pred} ${e.obj} )").fold(sparql"")(_ + _)
    sparql"""SELECT ?s ?p ?o ?g ?other
        WHERE {
          VALUES (?s ?p ?o) { $values }
          GRAPH ?g { ?s ?p ?o }
          OPTIONAL { ?g $ProvWasDerivedFrom ?other . }
        }"""
  }

  def getProvenance(edges: Set[Triple]): ZIO[ZConfig[AppConfig] with HttpClient, Throwable, Map[TripleString, String]] =
    for {
      queryText <- Task.effect(getProvenanceQueryText(edges))
      querySolutions <- SPARQLQueryExecutor.runSelectQuery(queryText.toQuery)
      triplesToGraphs <- ZIO.foreach(querySolutions) { solution =>
        Task.effect {
          val graph = if (solution.contains("other")) solution.getResource("other").getURI else solution.getResource("g").getURI
          val triple = TripleString(solution.getResource("s").getURI, solution.getResource("p").getURI, solution.getResource("o").getURI)
          triple -> graph
        }
      }
    } yield triplesToGraphs.toMap

  def getCAMStuffQueryText(prov: IRI): QueryText =
    sparql"""SELECT DISTINCT (?s_type AS ?subj) (?p AS ?pred) (?o_type AS ?obj)
         WHERE { GRAPH $prov {
             ?s ?p ?o .
             ?s $RDFType $OWLNamedIndividual .
             ?o $RDFType $OWLNamedIndividual .
           }
         ?o $SesameDirectType ?o_type .
         ?s $SesameDirectType ?s_type .
         FILTER(isIRI(?o_type))
         FILTER(isIRI(?s_type))
       }"""

  def getCAMStuff(prov: IRI): RIO[ZConfig[AppConfig] with HttpClient, List[Triple]] =
    for {
      queryText <- Task.effect(getCAMStuffQueryText(prov))
      triples <- SPARQLQueryExecutor.runSelectQueryAs[Triple](queryText.toQuery)
    } yield triples

  def getExtraKGNodes(camNodes: Set[IRI],
                      slotStuffNodeDetails: List[TermWithLabelAndBiolinkType],
                      biolinkData: BiolinkData): Map[IRI, TRAPINode] = {
    val termToLabelAndTypes = slotStuffNodeDetails.groupBy(_.term).map { case (term, termsAndTypes) =>
      val (labels, biolinkTypes) = termsAndTypes.map(t => t.label -> t.biolinkType).unzip
      term -> (labels.flatten.headOption, biolinkTypes)
    }
    val nodeMap = camNodes.map { node =>
      val (labelOpt, biolinkTypes) = termToLabelAndTypes.getOrElse(node, (None, List(BiolinkNamedThing)))
      val biolinkTypesSet = biolinkTypes.to(Set)
      val classes = biolinkData.classes.filter(c => biolinkTypesSet(c.iri))
      node -> TRAPINode(labelOpt, Some(classes), None)
    }.toMap
    nodeMap
  }

  def mapRelationsToLabelAndBiolink(relations: Set[IRI]): RIO[ZConfig[AppConfig] with HttpClient, Map[IRI, (Option[String], IRI)]] = {
    final case class RelationInfo(relation: IRI, biolinkSlot: IRI, label: Option[String])
    val queryText = sparql"""
         SELECT DISTINCT ?relation ?biolinkSlot ?label
         WHERE {
           VALUES ?relation { ${relations.asValues} }
           ?relation $SlotMapping ?biolinkSlot .
           ?biolinkSlot a $BiolinkMLSlotDefinition .
           OPTIONAL { ?relation $RDFSLabel ?label . }
           FILTER NOT EXISTS {
             ?relation $SlotMapping ?other .
             ?other $BiolinkMLIsA+/$BiolinkMLMixins* ?biolinkSlot .
           }
         }"""
    SPARQLQueryExecutor.runSelectQueryAs[RelationInfo](queryText.toQuery).map { res =>
      res.groupMap(_.relation)(info => (info.label, info.biolinkSlot)).map { case (relationIRI, infos) => relationIRI -> infos.head }
    }
  }

  def mapQueryBiolinkPredicatesToRelations(
    predicates: Set[BiolinkPredicate]): RIO[ZConfig[AppConfig] with HttpClient, Map[BiolinkPredicate, Set[IRI]]] = {
    final case class Predicate(biolinkPredicate: BiolinkPredicate, predicate: IRI)
    val queryText = sparql"""
        SELECT DISTINCT ?biolinkPredicate ?predicate WHERE {
          VALUES ?biolinkPredicate { ${predicates.asValues} }
          ?predicate $SlotMapping ?biolinkPredicate .
          FILTER EXISTS { ?s ?predicate ?o }
          <http://www.bigdata.com/queryHints#Query> <http://www.bigdata.com/queryHints#filterExists> "SubQueryLimitOne"
        }"""
    for {
      predicates <- SPARQLQueryExecutor.runSelectQueryAs[Predicate](queryText.toQuery)
    } yield predicates.to(Set).groupMap(_.biolinkPredicate)(_.predicate)
  }

}
