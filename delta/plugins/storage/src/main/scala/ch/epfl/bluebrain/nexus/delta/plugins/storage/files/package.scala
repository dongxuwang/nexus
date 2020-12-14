package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.File
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

package object files {

  /**
    * Type alias for a file specific resource.
    */
  type FileResource = ResourceF[File]

  /**
    * File schemas
    */
  object schemas {
    val file = iri"https://bluebrain.github.io/nexus/schemas/file.json"
  }

  /**
    * File vocabulary
    */
  val nxvFile = nxv + "File"

  /**
    * File contexts
    */
  object contexts {
    val file = iri"https://bluebrain.github.io/nexus/contexts/file.json"
  }
}