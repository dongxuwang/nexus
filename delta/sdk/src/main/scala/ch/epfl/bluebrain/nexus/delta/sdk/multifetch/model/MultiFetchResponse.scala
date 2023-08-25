package ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.jsonLdEncoderSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation.{CompactedJsonLd, Dot, ExpandedJsonLd, NQuads, NTriples, SourceJson}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRepresentation}
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchResponse.Result
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchResponse.Result.itemEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json, JsonObject}
import monix.bio.{IO, UIO}

/**
  * A response for a multi-fetch operation
  * @param format
  *   the formats in which the resource should be represented
  * @param resources
  *   the result for each resource
  */
final case class MultiFetchResponse(format: ResourceRepresentation, resources: NonEmptyList[Result]) {

  /**
    * Encode the response as a Json payload
    */
  def asJson(implicit base: BaseUri, rcr: RemoteContextResolution): UIO[Json] = {
    val encodeItem = itemEncoder(format)
    resources.traverse(encodeItem).map { r =>
      Json.obj(
        "format"    -> format.asJson,
        "resources" -> r.asJson
      )
    }
  }.hideErrors
}

object MultiFetchResponse {

  sealed trait Result {

    def id: ResourceRef

    def project: ProjectRef
  }

  object Result {

    sealed trait Error extends Result {
      def reason: String
    }

    final case class AuthorizationFailed(id: ResourceRef, project: ProjectRef) extends Error {
      override def reason: String = "The supplied authentication is not authorized to access this resource."
    }

    final case class NotFound(id: ResourceRef, project: ProjectRef) extends Error {
      override def reason: String = s"The resource '${id.toString}' was not found in project '$project'."
    }

    final case class Success[A](id: ResourceRef, project: ProjectRef, content: JsonLdContent[A, _]) extends Result

    implicit private val itemErrorEncoder: Encoder.AsObject[Error] = {
      Encoder.AsObject.instance[Error] { r =>
        JsonObject(
          "@type"  -> Json.fromString(r.getClass.getSimpleName),
          "reason" -> Json.fromString(r.reason)
        )
      }
    }

    implicit val itemErrorJsonLdEncoder: JsonLdEncoder[Error] = {
      JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
    }

    implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

    private[model] def itemEncoder(repr: ResourceRepresentation)(implicit base: BaseUri, rcr: RemoteContextResolution) =
      (item: Result) => {
        val common = JsonObject(
          "@id"     -> item.id.asJson,
          "project" -> item.project.asJson
        )

        def valueToJson[A](value: JsonLdContent[A, _]): IO[RdfError, Json] = {
          implicit val encoder: JsonLdEncoder[A] = value.encoder
          toJson(value.resource, value.source)
        }

        def toJson[C, S](value: C, source: S)(implicit
            valueJsonLdEncoder: JsonLdEncoder[C],
            sourceEncoder: Encoder[S]
        ): IO[RdfError, Json] =
          repr match {
            case SourceJson      => UIO.pure(source.asJson)
            case CompactedJsonLd => value.toCompactedJsonLd.map { v => v.json }
            case ExpandedJsonLd  => value.toExpandedJsonLd.map { v => v.json }
            case NTriples        => value.toNTriples.map { v => v.value.asJson }
            case NQuads          => value.toNQuads.map { v => v.value.asJson }
            case Dot             => value.toDot.map { v => v.value.asJson }
          }

        val result = item match {
          case e: Error               => toJson(e, e).map { e => JsonObject("error" -> e) }
          case Success(_, _, content) => valueToJson(content).map { r => JsonObject("value" -> r) }
        }

        result.map(_.deepMerge(common))
      }

  }

}