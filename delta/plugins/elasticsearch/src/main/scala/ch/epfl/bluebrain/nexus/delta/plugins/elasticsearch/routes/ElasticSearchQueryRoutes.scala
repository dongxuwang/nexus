package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.DefaultSearchRequest.{OrgSearch, ProjectSearch, RootSearch}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultSearchRequest, DefaultViewsQuery, ElasticSearchQueryError}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.AggregationResult.aggregationResultJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.JsonObject
import monix.bio.IO
import monix.execution.Scheduler

class ElasticSearchQueryRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    resourcesToSchemas: ResourceToSchemaMappings,
    schemeDirectives: DeltaSchemeDirectives,
    defaultViewsQuery: DefaultViewsQuery.Elasticsearch
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fetchContext: FetchContext[ElasticSearchQueryError]
) extends AuthDirectives(identities, aclCheck)
    with ElasticSearchViewsDirectives {

  import schemeDirectives._

  def routes: Route = concat(genericResourcesRoutes, resourcesListings)

  private val genericResourcesRoutes: Route =
    pathPrefix("resources") {
      extractCaller { implicit caller =>
        concat(
          (searchParametersAndSortList & paginated) { (params, sort, page) =>
            concat(
              // List/aggregate all resources
              pathEndOrSingleSlash {
                concat(
                  aggregated { _ => aggregate(RootSearch(params)) },
                  list(RootSearch(params, page, sort))
                )
              },
              (label & pathEndOrSingleSlash) { org =>
                concat(
                  aggregated { _ => aggregate(OrgSearch(org, params)) },
                  list(OrgSearch(org, params, page, sort))
                )
              }
            )
          },
          resolveProjectRef.apply { ref =>
            projectContext(ref) { implicit pc =>
              (searchParametersInProject & paginated) { (params, sort, page) =>
                concat(
                  // List/aggregate all resources inside a project
                  pathEndOrSingleSlash {
                    concat(
                      aggregated { _ => aggregate(ProjectSearch(ref, params)) },
                      list(ProjectSearch(ref, params, page, sort))
                    )
                  },
                  idSegment { schema =>
                    // List/aggregate all resources inside a project filtering by its schema type
                    pathEndOrSingleSlash {
                      underscoreToOption(schema) match {
                        case None        =>
                          concat(
                            aggregated { _ => aggregate(ProjectSearch(ref, params)) },
                            list(ProjectSearch(ref, params, page, sort))
                          )
                        case Some(value) =>
                          concat(
                            aggregated { _ => aggregate(ProjectSearch(ref, params, value)(fetchContext)) },
                            list(ProjectSearch(ref, params, page, sort, value)(fetchContext))
                          )
                      }
                    }
                  }
                )
              }
            }
          }
        )
      }
    }

  private val resourcesListings: Route =
    concat(resourcesToSchemas.value.map { case (Label(resourceSegment), resourceSchema) =>
      pathPrefix(resourceSegment) {
        extractCaller { implicit caller =>
          concat(
            (searchParametersAndSortList & paginated) { (params, sort, page) =>
              concat(
                // List all resources of type resourceSegment
                pathEndOrSingleSlash {
                  val request = DefaultSearchRequest.RootSearch(params, page, sort, resourceSchema)(fetchContext)
                  list(request)
                },
                // List all resources of type resourceSegment inside an organization
                (label & pathEndOrSingleSlash) { org =>
                  val request = DefaultSearchRequest.OrgSearch(org, params, page, sort, resourceSchema)(fetchContext)
                  list(request)
                }
              )
            },
            resolveProjectRef.apply { ref =>
              projectContext(ref) { implicit pc =>
                // List all resources of type resourceSegment inside a project
                (searchParametersInProject & paginated & pathEndOrSingleSlash) { (params, sort, page) =>
                  val request =
                    DefaultSearchRequest.ProjectSearch(ref, params, page, sort, resourceSchema)(fetchContext)
                  list(request)
                }
              }
            }
          )
        }
      }
    }.toSeq: _*)

  private def list(request: DefaultSearchRequest)(implicit caller: Caller): Route =
    list(IO.pure(request))

  private def list(request: IO[ElasticSearchQueryError, DefaultSearchRequest])(implicit caller: Caller): Route =
    (get & paginated & extractUri) { (page, uri) =>
      implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[JsonObject]] =
        searchResultsJsonLdEncoder(ContextValue(contexts.searchMetadata), page, uri)

      emit(request.flatMap(defaultViewsQuery.list))
    }

  private def aggregate(request: DefaultSearchRequest)(implicit caller: Caller): Route =
    aggregate(IO.pure(request))

  private def aggregate(request: IO[ElasticSearchQueryError, DefaultSearchRequest])(implicit caller: Caller): Route =
    get {
      implicit val searchJsonLdEncoder: JsonLdEncoder[AggregationResult] =
        aggregationResultJsonLdEncoder(ContextValue(contexts.aggregations))

      emit(request.flatMap(defaultViewsQuery.aggregate))
    }

}
