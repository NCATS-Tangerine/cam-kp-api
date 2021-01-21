package org.renci.cam

import com.google.common.base.CaseFormat
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor}
import org.apache.commons.lang3.StringUtils
import org.renci.cam.domain.{BiolinkClass, BiolinkPredicate, BiolinkTerm, IRI}

object Implicits {

  private val Curie = "^([^:]*):(.*)$".r
  private val protocols = Set("http", "https", "ftp", "file", "mailto")

  def iriDecoder(prefixesMap: Map[String, String]): Decoder[IRI] = new Decoder[IRI] {

    override def apply(c: HCursor): Result[IRI] = for {
      value <- c.value.as[String]
      curie <- value match {
        case Curie(p, l) => Right((p, l))
        case _           => Left(DecodingFailure(s"CURIE is malformed: $value", Nil))
      }
      (prefix, local) = curie
      namespace <-
        if (protocols(prefix)) Right(s"$prefix:")
        else prefixesMap.get(prefix).toRight(DecodingFailure(s"No prefix expansion found for $prefix:$local", Nil))
    } yield IRI(s"$namespace$local")

  }

  def iriEncoderOut(prefixesMap: Map[String, String]): Encoder[IRI] = Encoder.encodeString.contramap { iri =>
    val startsWith = prefixesMap.filter { case (_, namespace) => iri.value.startsWith(namespace) }
    if (startsWith.nonEmpty) {
      val (prefix, namespace) = startsWith.maxBy(_._2.length)
      StringUtils.prependIfMissing(iri.value.drop(namespace.length), s"$prefix:")
    } else iri.value
  }

  def iriEncoderIn(prefixesMap: Map[String, String]): Encoder[IRI] = Encoder.encodeString.contramap { iri =>
    val startsWith = prefixesMap.filter { case (prefix, namespace) => iri.value.startsWith(namespace) }
    if (startsWith.nonEmpty) {
      val (_, namespace) = startsWith.maxBy(_._2.length)
      s"$namespace${iri.value.drop(namespace.length)}"
    } else iri.value
  }

  def biolinkPredicateDecoder(biolinkPredicates: List[BiolinkPredicate]): Decoder[BiolinkPredicate] = Decoder.decodeString.emap { s =>
    biolinkPredicates.find(a => a.shorthand == s).toRight(s"BiolinkPredicate does not exist: $s")
  }

  def biolinkPredicateEncoder: Encoder[BiolinkPredicate] = Encoder.encodeString.contramap { s =>
    s"biolink:${s.shorthand}"
  }

  def biolinkClassDecoder(biolinkClasses: List[BiolinkClass]): Decoder[BiolinkClass] = Decoder.decodeString.emap { s =>
    biolinkClasses.find(a => a.shorthand == s).toRight(s"BiolinkClass does not exist: $s")
  }

  def biolinkClassEncoder: Encoder[BiolinkClass] = Encoder.encodeString.contramap { s =>
    if (s.shorthand.contains("_")) {
      s"biolink:${CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, s.shorthand)}"
    } else {
      s"biolink:${StringUtils.capitalize(s.shorthand)}"
    }
  }

}
