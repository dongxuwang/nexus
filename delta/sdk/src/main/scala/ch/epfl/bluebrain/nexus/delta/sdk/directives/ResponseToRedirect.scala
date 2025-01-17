package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ce.CatsResponseToJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import monix.bio.{IO => BIO, UIO}
import monix.execution.Scheduler

import scala.reflect.ClassTag

/**
  * Redirection response magnet.
  */
sealed trait ResponseToRedirect {
  def apply(redirection: Redirection): Route
}

object ResponseToRedirect {

  implicit def uioRedirect(io: UIO[Uri])(implicit s: Scheduler): ResponseToRedirect =
    new ResponseToRedirect {
      override def apply(redirection: Redirection): Route =
        onSuccess(io.runToFuture) { uri =>
          redirect(uri, redirection)
        }
    }

  implicit def bioRedirect[E: JsonLdEncoder: HttpResponseFields](
      io: BIO[E, Uri]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToRedirect =
    new ResponseToRedirect {
      override def apply(redirection: Redirection): Route =
        onSuccess(io.attempt.runToFuture) {
          case Left(value)     => ResponseToJsonLd.valueWithHttpResponseFields(value).apply(None)
          case Right(location) => redirect(location, redirection)
        }
    }

  implicit def ioRedirect(io: IO[Uri]): ResponseToRedirect =
    new ResponseToRedirect {
      override def apply(redirection: Redirection): Route =
        onSuccess(io.unsafeToFuture()) { uri =>
          redirect(uri, redirection)
        }
    }

  implicit def ioRedirectWithError[E <: Throwable: ClassTag: JsonLdEncoder: HttpResponseFields](
      io: IO[Uri]
  )(implicit cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToRedirect =
    new ResponseToRedirect {
      override def apply(redirection: Redirection): Route =
        onSuccess(io.attemptNarrow[E].unsafeToFuture()) {
          case Left(value)     => CatsResponseToJsonLd.valueWithHttpResponseFields[E](value).apply(None)
          case Right(location) => redirect(location, redirection)
        }
    }
}
